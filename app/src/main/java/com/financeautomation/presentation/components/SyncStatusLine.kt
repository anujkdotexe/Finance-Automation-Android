package com.financeautomation.presentation.components

import android.content.res.Configuration
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.CloudDone
import androidx.compose.material.icons.rounded.CloudOff
import androidx.compose.material.icons.rounded.Sync
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.financeautomation.presentation.theme.FinanceAutomationTheme
import com.financeautomation.presentation.theme.FinanceTheme

/**
 * Sync status indicator line (Design Guide 4 & 6.3).
 * Examples: "Synced 2 min ago", "2 waiting to sync", "Offline".
 */
@Composable
fun SyncStatusLine(
    statusText: String,
    modifier: Modifier = Modifier,
    isOffline: Boolean = false,
    hasPending: Boolean = false
) {
    val (icon, tint) = when {
        isOffline -> Icons.Rounded.CloudOff to FinanceTheme.colors.moneyOut
        hasPending -> Icons.Rounded.Sync to FinanceTheme.colors.moneyOwed
        else -> Icons.Rounded.CloudDone to FinanceTheme.colors.moneyIn
    }

    Row(
        modifier = modifier,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = tint,
            modifier = Modifier.size(14.dp)
        )
        Spacer(modifier = Modifier.width(FinanceTheme.spacing.xs))
        Text(
            text = statusText,
            style = FinanceTheme.typography.labelMedium,
            color = FinanceTheme.colors.onSurfaceVariant
        )
    }
}

@Preview(name = "Light", showBackground = true)
@Composable
private fun SyncStatusLinePreviewLight() {
    FinanceAutomationTheme(darkTheme = false) {
        SyncStatusLine(statusText = "Synced 2 min ago")
    }
}

@Preview(name = "Dark", uiMode = Configuration.UI_MODE_NIGHT_YES, showBackground = true)
@Composable
private fun SyncStatusLinePreviewDark() {
    FinanceAutomationTheme(darkTheme = true) {
        SyncStatusLine(statusText = "2 waiting to sync", hasPending = true)
    }
}

@Preview(name = "Large Font 200%", fontScale = 2.0f, showBackground = true)
@Composable
private fun SyncStatusLinePreviewLargeFont() {
    FinanceAutomationTheme(darkTheme = false) {
        SyncStatusLine(statusText = "Offline", isOffline = true)
    }
}

