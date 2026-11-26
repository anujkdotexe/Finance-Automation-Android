package com.financeautomation.domain.usecase

import com.financeautomation.core.model.TransactionStatus
import com.financeautomation.domain.model.Transaction

/** A carried forward copy is a debt moved into a later month: its text starts with "[Carried from". */
object CarriedCopies {
    private const val PREFIX = "[Carried from"

    private val LEADING_PREFIXES = Regex("^(?:\\[Carried from [^\\]]*\\]\\s*)+", RegexOption.IGNORE_CASE)
    private val SOURCE_TAB = Regex("^\\[Carried from (Jan|Feb|Mar|Apr|May|Jun|Jul|Aug|Sep|Oct|Nov|Dec)(\\d{2})\\]", RegexOption.IGNORE_CASE)
    private val MONTHS = listOf("jan", "feb", "mar", "apr", "may", "jun", "jul", "aug", "sep", "oct", "nov", "dec")

    /** A copy keeps the date and time of its original; two seconds absorbs the sheet's time rounding. */
    private const val SAME_MOMENT_SERIAL: Double = 2.0 / 86400.0

    fun isCopy(t: Transaction): Boolean = t.merchant.trim().startsWith(PREFIX, ignoreCase = true)

    /** The text without any leading "[Carried from ...]" prefixes, however many have stacked up. */
    fun stripPrefixes(text: String): String = text.trim().replace(LEADING_PREFIXES, "").trim()

    /** The month a copy was carried from as year * 12 + month index, or null when the text does not name a tab. */
    fun sourceMonthKey(text: String): Int? {
        val match = SOURCE_TAB.find(text.trim()) ?: return null
        val month = MONTHS.indexOf(match.groupValues[1].lowercase(java.util.Locale.US))
        return (2000 + match.groupValues[2].toInt()) * 12 + month
    }

    /**
     * For each carried forward row in [originals], the copies that hold its debt in turn, oldest hop first; the last
     * one is where the debt is now. Each hop is carried from a later month than the one before. A copy belongs to one
     * original only, so two expenses with the same name never share a copy. Copies with the original's own date and
     * time are the only candidates whenever there are any.
     */
    fun chains(originals: List<Transaction>, copies: List<Transaction>): Map<String, List<Transaction>> {
        val chains = originals.associate { it.id to mutableListOf<Transaction>() }
        val claimed = mutableSetOf<String>()
        val ordered = originals.sortedWith(compareBy<Transaction>({ it.timestamp }, { it.id }))
        var progressed = true
        // One hop for every original per round, so a long chain cannot take another original's first copy
        while (progressed) {
            progressed = false
            for (original in ordered) {
                val chain = chains.getValue(original.id)
                val node = chain.lastOrNull() ?: original
                if (node.status != TransactionStatus.CARRIED_FWD) continue
                val after = chain.lastOrNull()?.let { sourceMonthKey(it.merchant) }
                val text = stripPrefixes(original.merchant)
                val sameText = copies.filter { stripPrefixes(it.merchant).equals(text, ignoreCase = true) }
                val sameMoment = sameText.filter {
                    it.dateSerial == original.dateSerial &&
                        kotlin.math.abs(it.timeSerial - original.timeSerial) < SAME_MOMENT_SERIAL
                }
                val next = sameMoment.ifEmpty { sameText }
                    .filter { candidate ->
                        val from = sourceMonthKey(candidate.merchant)
                        candidate.id !in claimed && (after == null || from == null || from > after)
                    }
                    .minWithOrNull(
                        compareBy<Transaction>(
                            { sourceMonthKey(it.merchant) ?: Int.MAX_VALUE },
                            { if (kotlin.math.abs(it.owedToMe - node.owedToMe) < 0.005) 0 else 1 },
                            { it.id }
                        )
                    ) ?: continue
                claimed.add(next.id)
                chain.add(next)
                progressed = true
            }
        }
        return chains
    }
}
