package com.financeautomation.service

import android.app.ActivityManager
import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.util.TypedValue
import android.widget.RemoteViews
import com.financeautomation.R
import com.financeautomation.presentation.ui.ModalBottomSheetActivity

/**
 * AppWidgetProvider powering the 1-tap Home Screen widget for instant manual expense logging.
 * Dynamically queries OS launcher icon metrics (ActivityManager.launcherLargeIconSize)
 * to adapt icon dimensions seamlessly across custom OS icon sizing settings.
 */
class QuickLogWidgetProvider : AppWidgetProvider() {

    override fun onUpdate(
        context: Context,
        appWidgetManager: AppWidgetManager,
        appWidgetIds: IntArray
    ) {
        for (appWidgetId in appWidgetIds) {
            updateAppWidget(context, appWidgetManager, appWidgetId)
        }
    }

    override fun onAppWidgetOptionsChanged(
        context: Context,
        appWidgetManager: AppWidgetManager,
        appWidgetId: Int,
        newOptions: Bundle
    ) {
        super.onAppWidgetOptionsChanged(context, appWidgetManager, appWidgetId, newOptions)
        updateAppWidget(context, appWidgetManager, appWidgetId)
    }

    companion object {
        fun updateAppWidget(
            context: Context,
            appWidgetManager: AppWidgetManager,
            appWidgetId: Int
        ) {
            val intent = Intent(context, ModalBottomSheetActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            }

            val pendingIntent = PendingIntent.getActivity(
                context,
                appWidgetId,
                intent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )

            val views = RemoteViews(context.packageName, R.layout.widget_quick_log)
            views.setOnClickPendingIntent(R.id.widget_container, pendingIntent)

            // Dynamically scale widget icon according to OS launcher icon metrics
            val activityManager = context.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager
            val systemIconSizePx = activityManager?.launcherLargeIconSize
                ?: context.resources.getDimensionPixelSize(android.R.dimen.app_icon_size)

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && systemIconSizePx > 0) {
                views.setViewLayoutWidth(R.id.widget_icon, systemIconSizePx.toFloat(), TypedValue.COMPLEX_UNIT_PX)
                views.setViewLayoutHeight(R.id.widget_icon, systemIconSizePx.toFloat(), TypedValue.COMPLEX_UNIT_PX)
            }

            appWidgetManager.updateAppWidget(appWidgetId, views)
        }
    }
}
