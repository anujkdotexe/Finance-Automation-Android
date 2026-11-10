package com.financeautomation.domain.utils

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
}

