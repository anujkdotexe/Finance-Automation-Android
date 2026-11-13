package com.financeautomation.core.utils

/**
 * Decides whether two payee texts can belong to the same payment. Used so that cross-source deduplication does not
 * merge two real payments of the same amount to different payees. An unreadable payee is compatible with anything.
 * Pure Kotlin.
 */
object MerchantSimilarity {

    private const val UNKNOWN_PAYEE = "unknown payee"
    private const val MIN_TOKEN_LENGTH = 3

    fun compatible(a: String, b: String): Boolean {
        if (isUnknown(a) || isUnknown(b)) return true
        val na = normalize(a)
        val nb = normalize(b)
        if (na.isEmpty() || nb.isEmpty()) return true
        if (na == nb || na.contains(nb) || nb.contains(na)) return true
        val ta = tokens(na)
        val tb = tokens(nb)
        return ta.any { it in tb }
    }

    private fun isUnknown(raw: String): Boolean {
        val t = raw.trim().lowercase()
        return t.isEmpty() || t == UNKNOWN_PAYEE
    }

    /** Lower case, VPA handle removed, everything except letters and digits turned into single spaces. */
    private fun normalize(raw: String): String =
        raw.lowercase().substringBefore('@')
            .replace(Regex("[^a-z0-9]+"), " ")
            .trim()

    private fun tokens(normalized: String): Set<String> =
        normalized.split(' ').filter { it.length >= MIN_TOKEN_LENGTH }.toSet()
}
