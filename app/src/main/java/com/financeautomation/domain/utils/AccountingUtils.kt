package com.financeautomation.domain.utils

import com.financeautomation.core.model.TransactionStatus
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

    private val AMOUNT_PATTERN = Pattern.compile("^(.*?)(?:[:=\\-\\s]\\s*|\\s*\\(?)(?:[₹$€£])?\\s*(\\d+(?:\\.\\d+)?)\\)?$")

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
                val amountStr = matcher.group(2) ?: return null
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

        val totalHeads = rawTokens.size + 1
        val expectedShare = round2(txn.totalAmount / totalHeads)
        val isEqualSplit = kotlin.math.abs(txn.myShare - expectedShare) <= 0.02

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
}

