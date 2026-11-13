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
import com.financeautomation.presentation.ui.AppRoutes
import com.financeautomation.presentation.ui.MainActivity
import com.financeautomation.service.worker.MonthBurnWidgetWorker
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
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
                refresh(context)
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
            val summary = loadSummary(context)
            val content = MonthBurnWidgetFormatter.format(summary, labels(context))
            for (id in ids) {
                manager.updateAppWidget(id, buildViews(context, content, id))
            }
        }

        private suspend fun loadSummary(context: Context): MonthBurnSummary {
            val container = (context.applicationContext as FinanceApplication).appContainer
            val transactions = container.transactionRepository.getTransactions().first()
            val categories = container.categoryRepository.getCategories().first()
            val queue = container.awaitDatabase().syncQueueDao().getAllQueueFlow().first()
            val waiting = queue.count {
                it.status == SyncQueueEntity.STATUS_PENDING || it.status == SyncQueueEntity.STATUS_IN_PROGRESS
            }
            return MonthBurnSummaryUseCase()(transactions, categories, waiting)
        }

        private fun labels(context: Context) = MonthBurnWidgetLabels(
            title = context.getString(R.string.widget_month_burn_title),
            perDayTemplate = context.getString(R.string.widget_month_burn_per_day),
            upToDate = context.getString(R.string.widget_month_burn_up_to_date),
            waitingTemplate = context.getString(R.string.widget_month_burn_waiting),
            descriptionTemplate = context.getString(R.string.widget_month_burn_description)
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
