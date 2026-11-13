package com.financeautomation.domain.usecase

import com.financeautomation.domain.model.Category
import com.financeautomation.domain.model.Transaction
import java.time.Instant
import java.time.ZoneId

/** What the widget shows about syncing. */
sealed interface WidgetSyncState {
    /** Nothing is waiting to be written to the sheet. */
    data object UpToDate : WidgetSyncState

    /** [count] items are queued or in progress. */
    data class Waiting(val count: Int) : WidgetSyncState
}

data class MonthBurnSummary(
    val year: Int,
    val month: Int,
    val livingBurn: Double,
    val perDayAverage: Double,
    val daysElapsed: Int,
    val syncState: WidgetSyncState
)

/**
 * Figures for the month burn widget: living burn of the current month (Asia/Kolkata), the per day average and the
 * sync state. The burn and the average come from [LivingBurn], the same functions the Dashboard uses, so the widget
 * and the Dashboard cannot disagree for the same data.
 * Pure Kotlin with zero Android framework dependencies.
 */
class MonthBurnSummaryUseCase(private val zone: ZoneId = LivingBurn.KOLKATA) {

    /**
     * @param waitingCount queue items that are pending or in progress (failed items are not counted as waiting,
     * the same count the Dashboard shows).
     */
    operator fun invoke(
        transactions: List<Transaction>,
        categories: List<Category>,
        waitingCount: Int,
        nowMillis: Long = System.currentTimeMillis()
    ): MonthBurnSummary {
        val today = Instant.ofEpochMilli(nowMillis).atZone(zone).toLocalDate()
        val window = LivingBurn.monthWindow(today.year, today.monthValue, zone)
        val burn = LivingBurn.total(transactions, window, LivingBurn.excludedNames(categories))
        val daysElapsed = today.dayOfMonth
        return MonthBurnSummary(
            year = today.year,
            month = today.monthValue,
            livingBurn = burn,
            perDayAverage = LivingBurn.dailyAverage(burn, daysElapsed),
            daysElapsed = daysElapsed,
            syncState = if (waitingCount > 0) WidgetSyncState.Waiting(waitingCount) else WidgetSyncState.UpToDate
        )
    }
}
