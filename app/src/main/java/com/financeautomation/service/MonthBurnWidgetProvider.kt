package com.financeautomation.service

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.widget.RemoteViews
import com.financeautomation.FinanceApplication
import com.financeautomation.R
import com.financeautomation.core.utils.Diagnostics
import com.financeautomation.domain.usecase.MonthBurnSummary
import com.financeautomation.domain.usecase.MonthBurnSummaryUseCase
import com.financeautomation.domain.usecase.LivingBurn
import com.financeautomation.domain.usecase.WidgetSyncState
import com.financeautomation.presentation.ui.AppRoutes
import com.financeautomation.presentation.ui.MainActivity
import com.financeautomation.service.worker.MonthBurnWidgetWorker
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import com.financeautomation.data.local.entity.SyncQueueEntity

/**
 * Home screen widget (2 by 1 cells, resizable horizontally) showing the month name, Living burn and the per day
 * average, plus a short sync state. Tapping it opens the Dashboard. The figures come from [MonthBurnSummaryUseCase],
 * which uses the same burn rule as the Dashboard. The widget is refreshed after every sync and every 30 minutes.
 */
class MonthBurnWidgetProvider : AppWidgetProvider() {

    override fun onUpdate(context: Context, appWidgetManager: AppWidgetManager, appWidgetIds: IntArray) {
        val pending = goAsync()
        CoroutineScope(Dispatchers.IO).launch {
            try {
                refreshSafely(load = { refresh(context) }, onFailure = { drawCannotRead(context) })
            } finally {
                pending.finish()
            }
        }
    }

    override fun onEnabled(context: Context) {
        MonthBurnWidgetWorker.schedule(context)
    }

    override fun onDisabled(context: Context) {
        MonthBurnWidgetWorker.cancel(context)
    }

    companion object {
        /** The only route the widget asks MainActivity to open. It is on the route whitelist. */
        const val ROUTE_EXTRA = "extra_route"
        const val OPEN_ROUTE = AppRoutes.DASHBOARD

        /** Upper limit for one refresh; the system gives a broadcast about 10 seconds when it uses goAsync. */
        const val REFRESH_TIMEOUT_MS = 8_000L

        /**
         * Runs [load] without letting a failure or a slow read end the process. A thrown exception and a timeout are
         * recorded in Diagnostics (class name only, no message) and then [onFailure] runs. Cancellation of the caller
         * is rethrown.
         */
        suspend fun refreshSafely(
            timeoutMs: Long = REFRESH_TIMEOUT_MS,
            load: suspend () -> Unit,
            onFailure: suspend () -> Unit = {}
        ) {
            try {
                val finished = withTimeoutOrNull(timeoutMs) { load() }
                if (finished == null) {
                    Diagnostics.record("MonthBurnWidgetProvider", "refresh timed out")
                    runFailureHandler(onFailure)
                }
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                Diagnostics.record("MonthBurnWidgetProvider", "${e.javaClass.simpleName}: refresh failed")
                runFailureHandler(onFailure)
            }
        }

        /** The failure handler draws on the system widget host and can fail too; that must not end the process. */
        private suspend fun runFailureHandler(onFailure: suspend () -> Unit) {
            try {
                onFailure()
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                Diagnostics.record("MonthBurnWidgetProvider", "${e.javaClass.simpleName}: failure handler failed")
            }
        }

        /** Starts a refresh on a background thread. Safe to call from anywhere, including after a sync. */
        fun requestUpdate(context: Context) {
            val app = context.applicationContext
            CoroutineScope(Dispatchers.IO).launch {
                try {
                    refresh(app)
                } catch (e: Exception) {
                    if (e is CancellationException) throw e
                    Diagnostics.record("MonthBurnWidgetProvider", "${e.javaClass.simpleName}: requestUpdate failed")
                }
            }
        }

        /** Makes sure the 30 minute refresh is scheduled when at least one widget is on the home screen. */
        fun ensureScheduled(context: Context) {
            val manager = AppWidgetManager.getInstance(context) ?: return
            val ids = manager.getAppWidgetIds(ComponentName(context, MonthBurnWidgetProvider::class.java))
            if (ids.isNotEmpty()) MonthBurnWidgetWorker.schedule(context)
        }

        /** Loads the figures and redraws every placed widget. Does nothing when no widget is placed. */
        suspend fun refresh(context: Context) {
            val manager = AppWidgetManager.getInstance(context) ?: return
            val ids = manager.getAppWidgetIds(ComponentName(context, MonthBurnWidgetProvider::class.java))
            if (ids.isEmpty()) return
            val container = (context.applicationContext as FinanceApplication).appContainer
            container.ensureAppLockStarted()
            val appLockOn = container.appLockManager.isEnabled
            // With the app lock on no figure is read or shown; only the month name and the unlock hint
            val summary = if (appLockOn) {
                emptySummary()
            } else {
                loadIfAvailable(hasAccountAndWorkbook = { hasAccountAndWorkbook(container) }, load = { loadSummary(context) })
            }
            val content = MonthBurnWidgetFormatter.format(
                summary ?: emptySummary(), labels(context), appLockOn = appLockOn,
                unavailable = if (summary == null) WidgetUnavailable.NOT_SIGNED_IN else WidgetUnavailable.NONE
            )
            draw(context, manager, ids, content)
            // Remember a good figure so a later failed refresh can keep showing it; never keep one the user must not see
            if (!appLockOn && summary != null) saveLastGood(context, content) else clearLastGood(context)
        }

        /**
         * Returns the figures, or null without reading any transaction when there is no signed-in account or no
         * workbook for this year. A zero would read as a real figure.
         */
        suspend fun loadIfAvailable(
            hasAccountAndWorkbook: suspend () -> Boolean,
            load: suspend () -> MonthBurnSummary
        ): MonthBurnSummary? = if (hasAccountAndWorkbook()) load() else null

        private suspend fun hasAccountAndWorkbook(container: com.financeautomation.di.AppContainer): Boolean {
            val prefs = container.preferencesRepository
            val year = java.time.LocalDate.now(LivingBurn.KOLKATA).year
            return accountAndWorkbookAvailable(
                email = prefs.getGoogleAccountEmailSync(),
                yearId = prefs.getSpreadsheetIdForYearSync(year),
                legacyId = prefs.getSpreadsheetIdSync()
            )
        }

        /**
         * True when a Google account is signed in and a workbook is known: the id of this year, or the older single
         * id when the year id is empty. Blank counts as empty.
         */
        fun accountAndWorkbookAvailable(email: String?, yearId: String?, legacyId: String?): Boolean =
            !email.isNullOrBlank() && (!yearId.isNullOrBlank() || !legacyId.isNullOrBlank())

        private fun emptySummary(): MonthBurnSummary {
            val today = java.time.LocalDate.now(LivingBurn.KOLKATA)
            return MonthBurnSummary(
                year = today.year, month = today.monthValue, livingBurn = 0.0, perDayAverage = 0.0,
                daysElapsed = today.dayOfMonth, syncState = WidgetSyncState.UpToDate
            )
        }

        /**
         * Used when the refresh failed or timed out. Keeps the last good figures when there are some and the app lock is
         * off; the "open the app" text is shown only when there is no earlier content.
         */
        private suspend fun drawCannotRead(context: Context) {
            val manager = AppWidgetManager.getInstance(context) ?: return
            val ids = manager.getAppWidgetIds(ComponentName(context, MonthBurnWidgetProvider::class.java))
            if (ids.isEmpty()) return
            val container = (context.applicationContext as FinanceApplication).appContainer
            container.ensureAppLockStarted()
            val lastGood = if (container.appLockManager.isEnabled) null else loadLastGood(context)
            val content = lastGood
                ?: MonthBurnWidgetFormatter.format(emptySummary(), labels(context), unavailable = WidgetUnavailable.CANNOT_READ)
            draw(context, manager, ids, content)
        }

        private const val LAST_GOOD_PREFS = "month_burn_widget_last_good"

        private fun saveLastGood(context: Context, content: MonthBurnWidgetContent) {
            try {
                context.getSharedPreferences(LAST_GOOD_PREFS, Context.MODE_PRIVATE).edit()
                    .putString("monthName", content.monthName)
                    .putString("title", content.title)
                    .putString("amount", content.amount)
                    .putString("perDay", content.perDay)
                    .putString("syncState", content.syncState)
                    .putString("description", content.description)
                    .apply()
            } catch (e: Exception) {
                Diagnostics.record("MonthBurnWidgetProvider", "${e.javaClass.simpleName}: saveLastGood")
            }
        }

        /** Forgets the stored figures, for example when the app lock is turned on or the account changes. */
        fun clearLastGood(context: Context) {
            try {
                context.getSharedPreferences(LAST_GOOD_PREFS, Context.MODE_PRIVATE).edit().clear().apply()
            } catch (e: Exception) {
                Diagnostics.record("MonthBurnWidgetProvider", "${e.javaClass.simpleName}: clearLastGood")
            }
        }

        private fun loadLastGood(context: Context): MonthBurnWidgetContent? {
            return try {
                val p = context.getSharedPreferences(LAST_GOOD_PREFS, Context.MODE_PRIVATE)
                MonthBurnWidgetContent(
                    monthName = p.getString("monthName", null) ?: return null,
                    title = p.getString("title", null) ?: return null,
                    amount = p.getString("amount", null) ?: return null,
                    perDay = p.getString("perDay", null) ?: return null,
                    syncState = p.getString("syncState", null) ?: return null,
                    description = p.getString("description", null) ?: return null
                )
            } catch (e: Exception) {
                Diagnostics.record("MonthBurnWidgetProvider", "${e.javaClass.simpleName}: loadLastGood")
                null
            }
        }

        private fun draw(context: Context, manager: AppWidgetManager, ids: IntArray, content: MonthBurnWidgetContent) {
            for (id in ids) {
                manager.updateAppWidget(id, buildViews(context, content, id))
            }
        }

        private suspend fun loadSummary(context: Context): MonthBurnSummary {
            val container = (context.applicationContext as FinanceApplication).appContainer
            val transactions = container.transactionRepository.getTransactions().first()
            val categories = container.categoryRepository.getCategories(includeArchived = true).first()
            val queue = container.awaitDatabase().syncQueueDao().getAllQueueFlow().first()
            val waiting = queue.count {
                it.status == SyncQueueEntity.STATUS_PENDING || it.status == SyncQueueEntity.STATUS_IN_PROGRESS
            }
            val inflows = container.inflowRepository.getAllInflows().first()
            return MonthBurnSummaryUseCase()(transactions, categories, waiting, inflows = inflows)
        }

        private fun labels(context: Context) = MonthBurnWidgetLabels(
            title = context.getString(R.string.widget_month_burn_title),
            perDayTemplate = context.getString(R.string.widget_month_burn_per_day),
            upToDate = context.getString(R.string.widget_month_burn_up_to_date),
            waitingTemplate = context.getString(R.string.widget_month_burn_waiting),
            descriptionTemplate = context.getString(R.string.widget_month_burn_description),
            unlockToView = context.getString(R.string.widget_month_burn_unlock),
            signInToView = context.getString(R.string.widget_month_burn_sign_in),
            cannotRead = context.getString(R.string.widget_month_burn_unavailable)
        )

        private fun buildViews(context: Context, content: MonthBurnWidgetContent, appWidgetId: Int): RemoteViews {
            val views = RemoteViews(context.packageName, R.layout.widget_month_burn)
            views.setTextViewText(R.id.widget_month_name, content.monthName)
            views.setTextViewText(R.id.widget_month_title, content.title)
            views.setTextViewText(R.id.widget_month_amount, content.amount)
            views.setTextViewText(R.id.widget_month_per_day, content.perDay)
            views.setTextViewText(R.id.widget_month_sync, content.syncState)
            views.setContentDescription(R.id.widget_month_container, content.description)
            views.setOnClickPendingIntent(R.id.widget_month_container, openDashboardIntent(context, appWidgetId))
            return views
        }

        /** An explicit intent to MainActivity with the whitelisted Dashboard route. */
        fun openDashboardIntent(context: Context, requestCode: Int): PendingIntent {
            val intent = Intent(context, MainActivity::class.java).apply {
                putExtra(ROUTE_EXTRA, OPEN_ROUTE)
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
            }
            return PendingIntent.getActivity(
                context,
                requestCode,
                intent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
        }
    }
}
