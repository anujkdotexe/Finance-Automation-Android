package com.financeautomation.domain.usecase

import com.financeautomation.core.model.TransactionStatus
import com.financeautomation.domain.model.Transaction

/**
 * Net expenditure of one trip (or any group of transactions).
 *
 * - [totalPaid]: everything paid out of pocket on the trip.
 * - [givenPending]: the part others still owe back (Pending and Carried Fwd rows).
 * - [returned]: the part others have already paid back (Settled rows).
 * - [netExpenditure]: what the trip really cost, which is My share. It equals
 *   [totalPaid] minus [givenPending] minus [returned] when the rows are consistent.
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
    val transactions: List<Transaction>
)

/** Pure Kotlin, no Android dependencies. */
class TripSummaryUseCase {

    fun forTrip(name: String, transactions: List<Transaction>): TripSummary {
        val key = name.trim().lowercase()
        val rows = transactions
            .filter { it.status != TransactionStatus.VOID && it.tripTag.trim().lowercase() == key }
            .sortedByDescending { it.timestamp }
        val pending = rows
            .filter { it.status == TransactionStatus.PENDING || it.status == TransactionStatus.CARRIED_FWD }
            .sumOf { it.owedToMe }
        val returned = rows.filter { it.status == TransactionStatus.SETTLED }.sumOf { it.owedToMe }
        val friends = rows
            .flatMap { it.companions.split(",") }
            .map { it.trim() }
            .filter { it.isNotEmpty() && !it.equals("Personal", ignoreCase = true) }
            .map { it.lowercase() }
            .toSet()
        return TripSummary(
            name = name.trim(),
            transactionCount = rows.size,
            peopleCount = friends.size + 1,
            totalPaid = rows.sumOf { it.totalAmount },
            givenPending = pending,
            returned = returned,
            netExpenditure = rows.sumOf { it.myShare },
            transactions = rows
        )
    }

    /** One summary per distinct trip name found on the rows, keyed by the lower case name. */
    fun byTrip(transactions: List<Transaction>): Map<String, TripSummary> {
        val names = transactions
            .filter { it.status != TransactionStatus.VOID }
            .map { it.tripTag.trim() }
            .filter { it.isNotEmpty() }
            .distinctBy { it.lowercase() }
        return names.associate { it.lowercase() to forTrip(it, transactions) }
    }
}
