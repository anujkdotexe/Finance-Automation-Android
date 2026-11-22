package com.financeautomation.domain.usecase

import com.financeautomation.domain.model.Transaction

/** A carried forward copy is a debt moved into a later month: its text starts with "[Carried from". */
object CarriedCopies {
    private const val PREFIX = "[Carried from"

    private val LEADING_PREFIXES = Regex("^(?:\\[Carried from [^\\]]*\\]\\s*)+", RegexOption.IGNORE_CASE)

    fun isCopy(t: Transaction): Boolean = t.merchant.trim().startsWith(PREFIX, ignoreCase = true)

    /** The text without any leading "[Carried from ...]" prefixes, however many have stacked up. */
    fun stripPrefixes(text: String): String = text.trim().replace(LEADING_PREFIXES, "").trim()
}
