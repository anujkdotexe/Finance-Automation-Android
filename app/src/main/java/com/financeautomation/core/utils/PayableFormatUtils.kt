package com.financeautomation.core.utils

/**
 * Shared formatting helper for Table 3 payables.
 *
 * Table 3 in the master sheet schema does not have a dedicated category column
 * (its 7 columns are Date Incurred, Time, Paid By, What For, Amount I Owe, Date Repaid, Status).
 * When a user specifies a category during the friend-paid flow, it is integrated into the
 * "What For" description so the category context is preserved on the sheet.
 */
object PayableFormatUtils {

    fun buildEffectiveWhatFor(whatFor: String, category: String = ""): String {
        val trimmedWhat = whatFor.trim()
        val trimmedCat = category.trim()
        if (trimmedCat.isBlank()) return trimmedWhat
        if (trimmedWhat.isBlank()) return trimmedCat
        if (trimmedWhat.contains(trimmedCat, ignoreCase = true)) return trimmedWhat
        return "$trimmedWhat ($trimmedCat)"
    }
}
