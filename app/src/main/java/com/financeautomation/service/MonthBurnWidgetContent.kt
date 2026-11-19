package com.financeautomation.service

import com.financeautomation.core.utils.formatRupees
import com.financeautomation.domain.usecase.MonthBurnSummary
import com.financeautomation.domain.usecase.WidgetSyncState
import java.time.Month
import java.time.format.TextStyle
import java.util.Locale

/** The texts of the month burn widget, ready to put on screen. */
data class MonthBurnWidgetContent(
    val monthName: String,
    val title: String,
    val amount: String,
    val perDay: String,
    val syncState: String,
    /** One sentence for screen readers that covers everything the widget shows. */
    val description: String
)

/** Text templates, taken from string resources by the provider. */
data class MonthBurnWidgetLabels(
    val title: String,
    /** Template with one %1$s for the per day amount, for example "%1$s a day". */
    val perDayTemplate: String,
    val upToDate: String,
    /** Template with one %1$d for the number of waiting items, for example "%1$d waiting". */
    val waitingTemplate: String,
    /** Template for the spoken summary: month, amount, per day text, sync text. */
    val descriptionTemplate: String,
    /** Shown instead of the amount while the app lock is on. */
    val unlockToView: String = "Unlock to view",
    /** Shown instead of the amount when there is no signed-in account or no workbook. */
    val signInToView: String,
    /** Shown instead of the amount when the figures could not be read (failed or timed out refresh). */
    val cannotRead: String
)

/** Why the widget has no figure to show. [NONE] means the figures are shown. */
enum class WidgetUnavailable { NONE, NOT_SIGNED_IN, CANNOT_READ }

/**
 * Turns the month burn figures into the widget texts. Pure Kotlin, so the formatting is covered by plain unit tests.
 * Amounts follow the app rule: whole rupees with Indian digit grouping.
 */
object MonthBurnWidgetFormatter {

    fun format(
        summary: MonthBurnSummary,
        labels: MonthBurnWidgetLabels,
        locale: Locale = Locale.getDefault(),
        appLockOn: Boolean = false,
        unavailable: WidgetUnavailable = WidgetUnavailable.NONE
    ): MonthBurnWidgetContent {
        val monthName = Month.of(summary.month).getDisplayName(TextStyle.FULL, locale)
        if (appLockOn) {
            // No figure leaves the app while the lock is on
            return MonthBurnWidgetContent(
                monthName = monthName,
                title = labels.title,
                amount = labels.unlockToView,
                perDay = "",
                syncState = "",
                description = "$monthName ${labels.title}, ${labels.unlockToView}"
            )
        }
        if (unavailable != WidgetUnavailable.NONE) {
            // Never show a figure that was not read from the ledger
            val text = if (unavailable == WidgetUnavailable.NOT_SIGNED_IN) labels.signInToView else labels.cannotRead
            return MonthBurnWidgetContent(
                monthName = monthName,
                title = labels.title,
                amount = text,
                perDay = "",
                syncState = "",
                description = "$monthName ${labels.title}, $text"
            )
        }
        val amount = formatRupees(summary.livingBurn)
        val perDay = String.format(locale, labels.perDayTemplate, formatRupees(summary.perDayAverage))
        val sync = when (val state = summary.syncState) {
            WidgetSyncState.UpToDate -> labels.upToDate
            is WidgetSyncState.Waiting -> String.format(locale, labels.waitingTemplate, state.count)
        }
        val description = String.format(locale, labels.descriptionTemplate, monthName, amount, perDay, sync)
        return MonthBurnWidgetContent(
            monthName = monthName,
            title = labels.title,
            amount = amount,
            perDay = perDay,
            syncState = sync,
            description = description
        )
    }
}
