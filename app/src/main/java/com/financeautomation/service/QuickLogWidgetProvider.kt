package com.financeautomation.service

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.os.Build
import android.os.Bundle
import android.util.SizeF
import android.util.TypedValue
import android.widget.RemoteViews
import androidx.core.content.ContextCompat
import androidx.core.graphics.drawable.DrawableCompat
import com.financeautomation.R
import com.financeautomation.core.utils.Diagnostics
import com.financeautomation.presentation.ui.EntryActivity

/**
 * AppWidgetProvider powering the 1-tap launcher widget for instant manual expense logging (Spec 5, Step 6.1 & 6.2).
 * Renders an adaptive bitmap icon at the exact dimensions provided by the host launcher.
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
        private const val DEFAULT_SIZE_DP = 40f
        private const val MIN_SIDE_PX = 48
        private const val MAX_SIDE_PX = 512
        private const val CORNER_RADIUS_RATIO = 0.22f
        private const val SYMBOL_SCALE_RATIO = 0.56f

        fun updateAppWidget(
            context: Context,
            appWidgetManager: AppWidgetManager,
            appWidgetId: Int
        ) {
            try {
                val options = appWidgetManager.getAppWidgetOptions(appWidgetId) ?: Bundle.EMPTY
                val (widthDp, heightDp) = resolveWidgetSizeDp(options)

                val metrics = context.resources.displayMetrics
                val widthPx = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, widthDp, metrics).toInt()
                val heightPx = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, heightDp, metrics).toInt()
                val side = minOf(widthPx, heightPx).coerceIn(MIN_SIDE_PX, MAX_SIDE_PX)

                val bitmap = renderWidgetBitmap(context, side)

                val views = RemoteViews(context.packageName, R.layout.widget_quick_log)
                views.setImageViewBitmap(R.id.widget_icon, bitmap)

                val intent = Intent(context, EntryActivity::class.java).apply {
                    action = Intent.ACTION_VIEW
                    flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
                }

                val pendingIntent = PendingIntent.getActivity(
                    context,
                    appWidgetId,
                    intent,
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
                )

                views.setOnClickPendingIntent(R.id.widget_container, pendingIntent)
                views.setOnClickPendingIntent(R.id.widget_icon, pendingIntent)

                appWidgetManager.updateAppWidget(appWidgetId, views)
            } catch (e: Exception) {
                Diagnostics.record("QuickLogWidgetProvider", "${e.javaClass.simpleName}: updateAppWidget failed")
            }
        }

        private fun resolveWidgetSizeDp(options: Bundle): Pair<Float, Float> {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                val sizes = options.getParcelableArrayList<SizeF>(AppWidgetManager.OPTION_APPWIDGET_SIZES)
                if (!sizes.isNullOrEmpty()) {
                    val minW = sizes.minOf { it.width }
                    val minH = sizes.minOf { it.height }
                    return Pair(minW, minH)
                }
            }

            val minWidth = options.getInt(AppWidgetManager.OPTION_APPWIDGET_MIN_WIDTH, DEFAULT_SIZE_DP.toInt())
            val minHeight = options.getInt(AppWidgetManager.OPTION_APPWIDGET_MIN_HEIGHT, DEFAULT_SIZE_DP.toInt())
            return Pair(
                minWidth.toFloat().coerceAtLeast(DEFAULT_SIZE_DP),
                minHeight.toFloat().coerceAtLeast(DEFAULT_SIZE_DP)
            )
        }

        private fun renderWidgetBitmap(context: Context, side: Int): Bitmap {
            val bitmap = Bitmap.createBitmap(side, side, Bitmap.Config.ARGB_8888)
            val canvas = Canvas(bitmap)

            val radius = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                val resId = context.resources.getIdentifier(
                    "system_app_widget_background_radius",
                    "dimen",
                    "android"
                )
                if (resId > 0) {
                    try {
                        context.resources.getDimension(resId)
                    } catch (_: Exception) {
                        CORNER_RADIUS_RATIO * side
                    }
                } else {
                    CORNER_RADIUS_RATIO * side
                }
            } else {
                CORNER_RADIUS_RATIO * side
            }

            val bgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = ContextCompat.getColor(context, R.color.widget_background)
                style = Paint.Style.FILL
            }
            val rect = RectF(0f, 0f, side.toFloat(), side.toFloat())
            canvas.drawRoundRect(rect, radius, radius, bgPaint)

            val symbolDrawable = ContextCompat.getDrawable(context, R.drawable.ic_quick_log_symbol)?.mutate()
            if (symbolDrawable != null) {
                DrawableCompat.setTint(symbolDrawable, ContextCompat.getColor(context, R.color.widget_symbol))
                val symbolSize = (side * SYMBOL_SCALE_RATIO).toInt()
                val offset = (side - symbolSize) / 2
                symbolDrawable.setBounds(offset, offset, offset + symbolSize, offset + symbolSize)
                symbolDrawable.draw(canvas)
            }

            return bitmap
        }
    }
}
