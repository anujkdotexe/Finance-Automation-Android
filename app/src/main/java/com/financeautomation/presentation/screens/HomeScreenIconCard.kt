package com.financeautomation.presentation.screens

import android.appwidget.AppWidgetManager
import android.content.ComponentName
import android.content.Intent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Widgets
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.core.content.pm.ShortcutInfoCompat
import androidx.core.content.pm.ShortcutManagerCompat
import androidx.core.graphics.drawable.IconCompat
import com.financeautomation.R
import com.financeautomation.presentation.ui.EntryActivity
import com.financeautomation.service.QuickLogWidgetProvider

/**
 * Settings card offering 1-tap launcher widget pinning and static shortcut creation.
 */
@Composable
fun HomeScreenIconCard(
    modifier: Modifier = Modifier,
    onPinWidget: () -> Unit = {},
    onPinShortcut: () -> Unit = {}
) {
    val context = LocalContext.current
    val isShortcutPinningSupported = ShortcutManagerCompat.isRequestPinShortcutSupported(context)

    SettingsCard(
        title = stringResource(R.string.home_screen_widget_card_title),
        subtitle = stringResource(R.string.home_screen_widget_card_subtitle),
        icon = Icons.Rounded.Widgets,
        modifier = modifier
    ) {
        Text(
            text = stringResource(R.string.home_screen_widget_card_desc),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Button(
                onClick = {
                    val appWidgetManager = context.getSystemService(AppWidgetManager::class.java)
                    val myProvider = ComponentName(context, QuickLogWidgetProvider::class.java)
                    if (appWidgetManager != null && appWidgetManager.isRequestPinAppWidgetSupported) {
                        appWidgetManager.requestPinAppWidget(myProvider, null, null)
                    }
                    onPinWidget()
                },
                shape = RoundedCornerShape(10.dp),
                modifier = Modifier.weight(1f)
            ) {
                Icon(imageVector = Icons.Rounded.Widgets, contentDescription = null, modifier = Modifier.size(16.dp))
                Spacer(modifier = Modifier.width(6.dp))
                Text(stringResource(R.string.pin_1x1_widget_button))
            }

            if (isShortcutPinningSupported) {
                Button(
                    onClick = {
                        val pinIntent = Intent(context, EntryActivity::class.java).apply {
                            action = Intent.ACTION_VIEW
                            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
                        }
                        val icon = IconCompat.createWithResource(context, R.drawable.ic_quick_log_symbol)
                        val info = ShortcutInfoCompat.Builder(context, "quick_log")
                            .setShortLabel(context.getString(R.string.quick_log_shortcut_short_label))
                            .setLongLabel(context.getString(R.string.quick_log_shortcut_long_label))
                            .setIcon(icon)
                            .setIntent(pinIntent)
                            .build()
                        ShortcutManagerCompat.requestPinShortcut(context, info, null)
                        onPinShortcut()
                    },
                    shape = RoundedCornerShape(10.dp),
                    modifier = Modifier.weight(1f)
                ) {
                    Icon(imageVector = Icons.Rounded.Add, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(stringResource(R.string.add_as_app_icon_button))
                }
            } else {
                Text(
                    text = stringResource(R.string.pin_shortcut_not_supported_explanation),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.weight(1f)
                )
            }
        }
    }
}
