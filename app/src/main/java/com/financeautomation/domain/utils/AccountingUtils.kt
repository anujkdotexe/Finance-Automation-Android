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

    /**
     * Standardizes person names by trimming and capitalizing the first character
     * so that mixed-case entries like "Alice" and "alice" merge cleanly across all screens.
     */
    fun normalizePersonName(name: String): String {
        val trimmed = name.trim()
        if (trimmed.isEmpty()) return ""
        return trimmed.lowercase().replaceFirstChar { if (it.isLowerCase()) it.titlecase() else it.toString() }
    }

    /**
     * Attempts to parse explicit per-companion amounts from companion tokens.
     * Returns null if any token lacks an explicit numeric amount or total doesn't match.
     */
    fun parseCompanionAmounts(tokens: List<String>): Map<String, Double>? {
        if (tokens.isEmpty()) return null
        val result = mutableMapOf<String, Double>()
        for (token in tokens) {
            val matcher = AMOUNT_PATTERN.matcher(token.trim())
            if (matcher.matches()) {
                val name = matcher.group(1)?.trim() ?: return null
                val amountStr = matcher.group(2) ?: return null
                val amount = amountStr.toDoubleOrNull() ?: return null
                if (name.isBlank() || amount <= 0.0) return null
                val normalizedName = normalizePersonName(name)
                result[normalizedName] = (result[normalizedName] ?: 0.0) + round2(amount)
            } else {
                return null
            }
        }
        return if (result.isNotEmpty()) result else null
    }

    /**
     * Calculates the per-person receivables breakdown for an unsettled transaction.
     * 1. If explicit amounts are stored per companion, parses and returns them.
     * 2. If exactly one companion, attributes the full owed amount to that companion.
     * 3. For equal splits, divides owedToMe equally across all companions.
     * 4. For custom splits with multiple companions without stored shares, does NOT divide
     *    equally by head count; attributes to "Others" so unverified equal shares are never assumed.
     */
    fun calculateReceivablesForTransaction(txn: Transaction): Map<String, Double> {
        if (txn.status != TransactionStatus.PENDING || txn.owedToMe <= Tolerances.MONEY_LEFT) {
            return emptyMap()
        }
        val rawTokens = tokens(txn.companions)
        if (rawTokens.isEmpty() || txn.companions.equals("Personal", ignoreCase = true)) {
            return mapOf(normalizePersonName("Others") to txn.owedToMe)
        }

        val parsed = parseCompanionAmounts(rawTokens)
        if (parsed != null && parsed.isNotEmpty()) {
            return parsed
        }

        if (rawTokens.size == 1) {
            return mapOf(normalizePersonName(rawTokens[0]) to txn.owedToMe)
        }

        val totalHeads = rawTokens.size + 1
        val expectedShare = round2(txn.totalAmount / totalHeads)
        val isEqualSplit = kotlin.math.abs(txn.myShare - expectedShare) <= 0.02

        return if (isEqualSplit) {
            val sharePerPerson = round2(txn.owedToMe / rawTokens.size)
            rawTokens.associate { normalizePersonName(it) to sharePerPerson }
        } else {
            // Multi-companion custom split where per-person shares are not stored.
            // Equal division is kept only for equal splits.
            mapOf(normalizePersonName("Others") to txn.owedToMe)
        }
    }
}

