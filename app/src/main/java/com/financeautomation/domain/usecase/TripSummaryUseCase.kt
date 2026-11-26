package com.financeautomation.domain.usecase

import com.financeautomation.core.constants.CategoryConstants
import com.financeautomation.core.model.ReviewState
import com.financeautomation.core.model.TransactionStatus
import com.financeautomation.domain.model.Inflow
import com.financeautomation.domain.model.Transaction

/**
 * Category spend breakdown for a trip.
 */
data class TripCategoryBreakdown(
    val category: String,
    val amount: Double,
    val percentage: Double
)

/**
 * Breakdown of money owed and repaid for a specific companion on a trip.
 */
data class TripFriendDebt(
    val name: String,
    val owedToMe: Double,
    val repaid: Double
)

/**
 * Net expenditure and insights of one trip (or any group of transactions).
 *
 * - [totalPaid]: everything paid out of pocket on the trip (excludes Savings, Transfer, review rows, and refunds).
 * - [givenPending]: the part others still owe back (Pending and Carried Fwd rows).
 * - [returned]: the part others have already paid back (Settled rows).
 * - [netExpenditure]: what the trip really cost (Living Burn definition: My share minus refunds, never below zero).
 */
data class TripSummary(
    val name: String,
    val transactionCount: Int,
    val peopleCount: Int,
    val totalPaid: Double,
    val givenPending: Double,
    val returned: Double,
    val netExpenditure: Double,
    /** The trip rows, newest first, void rows left out. */
    val transactions: List<Transaction>,
    val dateRange: String = "",
    val daysCount: Int = 1,
    val averagePerDay: Double = 0.0,
    val perFriendBreakdown: List<TripFriendDebt> = emptyList(),
    val categoryBreakdown: List<TripCategoryBreakdown> = emptyList(),
    val biggestSingleExpense: Transaction? = null,
    val unconfirmedCount: Int = 0,
    val unconfirmedAmount: Double = 0.0
)

/** Pure Kotlin, no Android dependencies. */
class TripSummaryUseCase {

    /**
     * [excludedCategories]: the names of the categories excluded from living burn in Settings, trimmed and lower case
     * (use LivingBurn.excludedNames). [inflows]: income entries; refund inflows that mention the trip reduce the net spend.
     */
    fun forTrip(
        name: String,
        transactions: List<Transaction>,
        excludedCategories: Set<String> = emptySet(),
        inflows: List<Inflow> = emptyList()
    ): TripSummary {
        val key = name.trim().lowercase()
        val all = transactions
            .filter { it.status != TransactionStatus.VOID && it.tripTag.trim().lowercase() == key }
            .sortedByDescending { it.timestamp }
        // A carried forward copy is the same debt moved to a later month, not new spending
        val copies = all.filter { isCarriedCopy(it) }
        val rows = all - copies.toSet()

        val chains = CarriedCopies.chains(rows.filter { it.status == TransactionStatus.CARRIED_FWD }, copies)

        /** What others still owe on [row]; a carried forward original is judged by where its debt is now. */
        fun stillOwed(row: Transaction): Double {
            val live = chains[row.id]?.lastOrNull() ?: row
            return when (live.status) {
                TransactionStatus.PENDING, TransactionStatus.CARRIED_FWD -> live.owedToMe
                else -> 0.0
            }
        }

        fun isRefund(cat: String): Boolean =
            cat.equals(CategoryConstants.CATEGORY_REFUND, ignoreCase = true) || LivingBurn.isRefund(cat)

        fun isCountedSpend(row: Transaction): Boolean =
            row.reviewState != ReviewState.NEEDS_REVIEW &&
                !LivingBurn.isExcluded(row.category, excludedCategories) &&
                !isRefund(row.category)

        val spendRows = rows.filter { isCountedSpend(it) }

        val pending = spendRows.sumOf { stillOwed(it) }
        // Settled rows have owed 0, so what came back is what others owed in the first place minus what is still owed
        val returned = spendRows.sumOf { maxOf(0.0, (it.totalAmount - it.myShare) - stillOwed(it)) }

        val friends = spendRows
            .flatMap { it.companions.split(",") }
            .map { it.trim() }
            .filter { it.isNotEmpty() && !it.equals("Personal", ignoreCase = true) }
            .map { it.lowercase() }
            .toSet()

        val days = if (spendRows.isEmpty()) {
            0
        } else {
            // Calendar days in Asia/Kolkata, not 24 hour blocks
            fun dayOf(ts: Long) = java.time.Instant.ofEpochMilli(ts).atZone(LivingBurn.KOLKATA).toLocalDate()
            val first = dayOf(spendRows.minOf { it.timestamp })
            val last = dayOf(spendRows.maxOf { it.timestamp })
            maxOf(0L, java.time.temporal.ChronoUnit.DAYS.between(first, last)).toInt() + 1
        }

        // Shared LivingBurn definition: My share minus refunds
        val refundTxnTotal = rows
            .filter { it.reviewState != ReviewState.NEEDS_REVIEW && isRefund(it.category) }
            .sumOf { it.myShare }
        val refundInflowTotal = inflows
            .filter {
                !it.status.equals("VOID", ignoreCase = true) &&
                    LivingBurn.isRefund(it.inflowType) &&
                    (name.isBlank() || it.note.contains(name, ignoreCase = true) || it.source.contains(name, ignoreCase = true))
            }
            .sumOf { it.amount }
        val totalRefunds = refundTxnTotal + refundInflowTotal

        val netSpend = maxOf(0.0, spendRows.sumOf { it.myShare } - totalRefunds)
        val avgPerDay = if (days > 0) netSpend / days else 0.0

        val friendBreakdowns = friends.map { friendName ->
            val friendRows = spendRows.filter { row ->
                row.companions.split(",").any { it.trim().equals(friendName, ignoreCase = true) }
            }
            val friendPending = friendRows.sumOf { row ->
                val count = maxOf(1, row.companions.split(",").map { it.trim() }.filter { it.isNotEmpty() && !it.equals("Personal", ignoreCase = true) }.size)
                stillOwed(row) / count
            }
            val friendRepaid = friendRows.sumOf { row ->
                val count = maxOf(1, row.companions.split(",").map { it.trim() }.filter { it.isNotEmpty() && !it.equals("Personal", ignoreCase = true) }.size)
                maxOf(0.0, (row.totalAmount - row.myShare) - stillOwed(row)) / count
            }
            TripFriendDebt(
                name = friendName.replaceFirstChar { it.uppercase() },
                owedToMe = friendPending,
                repaid = friendRepaid
            )
        }

        val totalCatSpend = spendRows.sumOf { it.myShare }
        val catBreakdown = spendRows
            .groupBy { it.category.trim().ifBlank { CategoryConstants.CATEGORY_OTHER } }
            .map { (cat, catRows) ->
                val catSpend = catRows.sumOf { it.myShare }
                val pct = if (totalCatSpend > 0.001) (catSpend / totalCatSpend) * 100.0 else 0.0
                TripCategoryBreakdown(category = cat, amount = catSpend, percentage = pct)
            }
            .sortedByDescending { it.amount }

        val biggest = spendRows.maxByOrNull { it.totalAmount }
        val dateRangeFormatted = TripDateRangeFormatter.format(spendRows.ifEmpty { rows }, isActive = false)

        val unconfirmedRows = rows.filter {
            it.reviewState == ReviewState.NEEDS_REVIEW && !LivingBurn.isExcluded(it.category, excludedCategories)
        }

        return TripSummary(
            name = name.trim(),
            transactionCount = rows.size,
            peopleCount = friends.size + 1,
            totalPaid = spendRows.sumOf { it.totalAmount },
            givenPending = pending,
            returned = returned,
            netExpenditure = netSpend,
            transactions = rows,
            dateRange = dateRangeFormatted,
            daysCount = days,
            averagePerDay = avgPerDay,
            perFriendBreakdown = friendBreakdowns,
            categoryBreakdown = catBreakdown,
            biggestSingleExpense = biggest,
            unconfirmedCount = unconfirmedRows.size,
            unconfirmedAmount = unconfirmedRows.sumOf { it.myShare }
        )
    }

    private fun isCarriedCopy(t: Transaction): Boolean = CarriedCopies.isCopy(t)

    /** One summary per distinct trip name found on the rows, keyed by the lower case name. */
    fun byTrip(
        transactions: List<Transaction>,
        excludedCategories: Set<String> = emptySet(),
        inflows: List<Inflow> = emptyList()
    ): Map<String, TripSummary> {
        val names = transactions
            .filter { it.status != TransactionStatus.VOID }
            .map { it.tripTag.trim() }
            .filter { it.isNotEmpty() }
            .distinctBy { it.lowercase() }
        return names.associate { it.lowercase() to forTrip(it, transactions, excludedCategories, inflows) }
    }

    private companion object {
        const val CARRIED_PREFIX = "[Carried from"
    }
}
