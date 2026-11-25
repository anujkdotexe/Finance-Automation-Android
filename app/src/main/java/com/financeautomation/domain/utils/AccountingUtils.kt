package com.financeautomation.domain.utils

import com.financeautomation.core.model.TransactionStatus
import com.financeautomation.domain.model.LedgerDebit
import com.financeautomation.domain.model.Transaction
import java.math.BigDecimal
import java.math.RoundingMode
import java.util.regex.Pattern

/**
 * Standard tolerances ported directly from Code.gs v2.3.0.
 */
object Tolerances {
    /** 0.009 threshold for residual money remaining */
    const val MONEY_LEFT: Double = 0.009

    /** 0.05 threshold for considering a companion's fractional share fully covered */
    const val SHARE_FULLY_COVERED: Double = 0.05

    /** 0.01 threshold for considering a balance fully covered */
    const val PAYMENT_COVERS_WHOLE: Double = 0.01
}

/**
 * Pure accounting and token utilities ported from Code.gs.
 * Pure Kotlin with zero Android framework dependencies.
 */
object AccountingUtils {

    private val AND_PATTERN = Pattern.compile("\\s+(&|and)\\s+", Pattern.CASE_INSENSITIVE)

    /**
     * Splits companion strings, replacing 'and' or '&' with commas,
     * trimming whitespace and omitting empty tokens.
     */
    fun tokens(s: String?): List<String> {
        if (s.isNullOrBlank()) return emptyList()
        val normalized = AND_PATTERN.matcher(s).replaceAll(", ")
        return normalized.split(",")
            .map { it.trim() }
            .filter { it.isNotEmpty() }
    }

    /**
     * Accurately rounds a double monetary amount to 2 decimal places using HALF_UP.
     * Uses BigDecimal.valueOf to avoid floating-point string conversion inaccuracies.
     */
    fun round2(x: Double): Double {
        return BigDecimal.valueOf(x).setScale(2, RoundingMode.HALF_UP).toDouble()
    }

    /**
     * Case-insensitive, whitespace-trimmed string comparison.
     */
    fun eq(a: String?, b: String?): Boolean {
        if (a == null && b == null) return true
        if (a == null || b == null) return false
        return a.trim().lowercase() == b.trim().lowercase()
    }

    /**
     * "name amount" needs an explicit marker: a colon or equals sign, brackets, or a currency symbol before the number.
     * A bare trailing number ("Rahul 2", "Rahul2") is part of the name, never an amount.
     */
    private val AMOUNT_PATTERN = Pattern.compile(
        "^(.*?)\\s*(?:[:=]\\s*[₹$€£]?\\s*(\\d+(?:\\.\\d+)?)|\\(\\s*[₹$€£]?\\s*(\\d+(?:\\.\\d+)?)\\s*\\)|[₹$€£]\\s*(\\d+(?:\\.\\d+)?))$"
    )

    data class ReceivableShare(
        val key: String,
        val displayName: String,
        val amount: Double
    )

    fun normalizePersonKey(name: String): String {
        return name.trim().lowercase()
    }

    /**
     * Standardizes person names by trimming and lowercasing so that
     * mixed-case entries like "Alice" and "alice" merge cleanly across all screens.
     */
    fun normalizePersonName(name: String): String {
        return normalizePersonKey(name)
    }

    fun formatReceivableLabel(tokens: List<String>): Pair<String, String> {
        val cleanTokens = tokens.map { it.trim() }.filter { it.isNotEmpty() }
        val key = cleanTokens.joinToString(", ") { normalizePersonKey(it) }
        val displayName = cleanTokens.joinToString(", ")
        return Pair(key, displayName)
    }

    /**
     * Attempts to parse explicit per-companion amounts from companion tokens.
     * Returns null if any token lacks an explicit numeric amount or total doesn't match.
     */
    fun parseCompanionAmountsWithDisplay(tokens: List<String>): List<ReceivableShare>? {
        if (tokens.isEmpty()) return null
        val result = mutableListOf<ReceivableShare>()
        for (token in tokens) {
            val matcher = AMOUNT_PATTERN.matcher(token.trim())
            if (matcher.matches()) {
                val name = matcher.group(1)?.trim() ?: return null
                val amountStr = matcher.group(2) ?: matcher.group(3) ?: matcher.group(4) ?: return null
                val amount = amountStr.toDoubleOrNull() ?: return null
                if (name.isBlank() || amount <= 0.0) return null
                val (key, display) = formatReceivableLabel(listOf(name))
                result.add(ReceivableShare(key, display, round2(amount)))
            } else {
                return null
            }
        }
        return if (result.isNotEmpty()) result else null
    }

    fun parseCompanionAmounts(tokens: List<String>): Map<String, Double>? {
        val list = parseCompanionAmountsWithDisplay(tokens) ?: return null
        return list.associate { it.key to it.amount }
    }

    /**
     * Calculates the per-person receivables breakdown for an unsettled transaction.
     * 1. If explicit amounts are stored per companion, parses and returns them.
     * 2. If exactly one companion, attributes the full owed amount to that companion.
     * 3. For equal splits, divides owedToMe equally across all companions.
     * 4. For custom splits with multiple companions without stored shares, does NOT divide
     *    equally by head count; attributes to a combined group label made of the real display names
     *    in the order stored (e.g. "Alice, Bob") with key as the normalized joined names ("alice, bob").
     *    Never uses the placeholder word "Others".
     * 5. The "Personal" case with owed above zero invents no person and returns an empty list.
     */
    fun calculateReceivablesForTransaction(txn: Transaction): List<ReceivableShare> {
        if (txn.status != TransactionStatus.PENDING || txn.owedToMe <= Tolerances.MONEY_LEFT) {
            return emptyList()
        }
        val rawTokens = tokens(txn.companions)
        if (rawTokens.isEmpty() || txn.companions.equals("Personal", ignoreCase = true)) {
            return emptyList()
        }

        val parsed = parseCompanionAmountsWithDisplay(rawTokens)
        if (parsed != null && parsed.isNotEmpty()) {
            return parsed
        }

        if (rawTokens.size == 1) {
            val (key, display) = formatReceivableLabel(rawTokens)
            return listOf(ReceivableShare(key, display, txn.owedToMe))
        }

        val isEqualSplit = isEqualSplit(txn.totalAmount, txn.myShare, txn.companions, txn.owedToMe)

        return if (isEqualSplit) {
            val sharePerPerson = round2(txn.owedToMe / rawTokens.size)
            rawTokens.map { token ->
                val (key, display) = formatReceivableLabel(listOf(token))
                ReceivableShare(key, display, sharePerPerson)
            }
        } else {
            // Multi-companion custom split: attribute to combined label of real display names in order stored
            val (key, display) = formatReceivableLabel(rawTokens)
            listOf(ReceivableShare(key, display, txn.owedToMe))
        }
    }

    fun isEqualSplit(totalAmount: Double, myShare: Double, companions: String?, owedToMe: Double? = null): Boolean {
        val rawTokens = tokens(companions)
        if (rawTokens.isEmpty() || companions.equals("Personal", ignoreCase = true)) {
            return false
        }
        val parsed = parseCompanionAmountsWithDisplay(rawTokens)
        if (parsed != null && parsed.isNotEmpty()) {
            return false
        }
        val totalHeads = rawTokens.size + 1
        val expectedShare = round2(totalAmount / totalHeads)
        if (kotlin.math.abs(myShare - expectedShare) <= 0.02) return true
        // After some friends repaid, they were removed from companions, so the head count shrank. In an equal split every
        // friend owes the same amount as the user's own share; recover the original head count from total / myShare and
        // accept the row when the remaining owed is the remaining friends times that share.
        if (owedToMe == null) return false
        val originalHeads = if (myShare > Tolerances.MONEY_LEFT) Math.round(totalAmount / myShare).toInt() else 0
        return originalHeads > totalHeads &&
            kotlin.math.abs(round2(totalAmount / originalHeads) - myShare) <= 0.02 &&
            kotlin.math.abs(owedToMe - rawTokens.size * myShare) <= 0.02 * rawTokens.size
    }

    fun isEqualSplit(debit: LedgerDebit): Boolean =
        isEqualSplit(debit.total, debit.myShare, debit.companions, debit.owed)

    /**
     * Tolerance for matching legacy entry timestamps: strictly under one second.
     * 1 second in serial days = 1.0 / 86400.0 (~0.000011574)
     */
    const val LEGACY_TIME_SERIAL_TOLERANCE: Double = 1.0 / 86400.0

    /**
     * Derives the linked payable UUID for an expense UUID if they share a common suffix.
     * e.g. "txn_abc123" -> "pay_abc123"
     */
    fun linkedPayableIdForExpense(expenseId: String): String? {
        if (expenseId.startsWith("txn_")) {
            return "pay_" + expenseId.removePrefix("txn_")
        }
        return null
    }

    /**
     * Derives the linked expense UUID for a payable UUID if they share a common suffix.
     * e.g. "pay_abc123" -> "txn_abc123"
     */
    fun linkedExpenseIdForPayable(payableId: String): String? {
        if (payableId.startsWith("pay_")) {
            return "txn_" + payableId.removePrefix("pay_")
        }
        return null
    }

    /**
     * Checks whether a candidate payable agrees with an expense for legacy matching.
     * Both Creditor AND Description must strictly agree:
     * 1. dateIncurredSerial == txDateSerial
     * 2. timeSerial != null and abs(timeSerial - txTimeSerial) < 1.0 / 86400.0 (< 1 second)
     * 3. Creditor agreement: non-empty creditor matches the expense creditor/account.
     * 4. Description agreement: full description matches (no loose prefix or substring).
     */
    fun legacyPayableAgreesWithExpense(
        dateIncurredSerial: Int?,
        timeSerial: Double?,
        paidBy: String,
        whatFor: String,
        txDateSerial: Int,
        txTimeSerial: Double,
        txMerchant: String,
        txAccount: String = ""
    ): Boolean {
        if (dateIncurredSerial == null || dateIncurredSerial != txDateSerial) return false
        if (timeSerial == null || kotlin.math.abs(timeSerial - txTimeSerial) >= LEGACY_TIME_SERIAL_TOLERANCE) return false

        val cleanMerchant = txMerchant.removePrefix("[Void] ").trim()
        val cleanWhatFor = whatFor.removePrefix("[Void] ").trim()
        val cleanPaidBy = paidBy.trim()
        val cleanAccount = txAccount.trim()

        if (cleanPaidBy.isBlank() || cleanWhatFor.isBlank() || cleanMerchant.isBlank()) return false

        // 1. Creditor agreement
        val creditorAgrees = when {
            cleanMerchant.startsWith("Paid by ", ignoreCase = true) -> {
                val creditor = cleanMerchant.substring(8).trim()
                eq(creditor, cleanPaidBy)
            }
            eq(cleanPaidBy, cleanAccount) -> true
            cleanMerchant.endsWith("($cleanPaidBy)", ignoreCase = true) -> true
            cleanMerchant.contains("Paid by $cleanPaidBy", ignoreCase = true) -> true
            else -> false
        }
        if (!creditorAgrees) return false

        // 2. Description agreement (exact or known standard suffix, never bare prefix like "Din" for "Dinner")
        return eq(cleanWhatFor, cleanMerchant) ||
            cleanWhatFor.equals("$cleanMerchant (Other)", ignoreCase = true) ||
            cleanWhatFor.equals("$cleanMerchant (via Split)", ignoreCase = true) ||
            cleanWhatFor.equals("$cleanMerchant ($cleanPaidBy)", ignoreCase = true) ||
            (cleanWhatFor.equals("Paid by $cleanPaidBy", ignoreCase = true) && cleanMerchant.equals("Paid by $cleanPaidBy", ignoreCase = true))
    }
}

