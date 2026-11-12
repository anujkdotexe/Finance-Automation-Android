package com.financeautomation.presentation.components

import android.content.res.Configuration
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.CloudOff
import androidx.compose.material.icons.rounded.Schedule
import androidx.compose.material.icons.rounded.Sync
import androidx.compose.material.icons.rounded.Warning
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.financeautomation.presentation.theme.FinanceAutomationTheme
import com.financeautomation.presentation.theme.FinanceTheme

/**
 * Single-line sync status line:
 * Synced just now, N waiting, N failed, each with an icon.
 */
@Composable
fun SyncStatusLine(
    modifier: Modifier = Modifier,
    waitingCount: Int = 0,
    failedCount: Int = 0,
    isSyncing: Boolean = false,
    isOffline: Boolean = false,
    lastSyncedText: String = "Synced just now"
) {
    Row(
        modifier = modifier,
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        if (isOffline) {
            Icon(
                imageVector = Icons.Rounded.CloudOff,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.error,
                modifier = Modifier.size(13.dp)
            )
            Text(
                text = "Offline",
                style = FinanceTheme.typography.numeric.copy(fontSize = 11.sp),
                color = MaterialTheme.colorScheme.error
            )
        } else if (isSyncing) {
            Icon(
                imageVector = Icons.Rounded.Sync,
                contentDescription = null,
                tint = FinanceTheme.colors.brand,
                modifier = Modifier.size(13.dp)
            )
            Text(
                text = "Syncing...",
                style = FinanceTheme.typography.numeric.copy(fontSize = 11.sp),
                color = FinanceTheme.colors.brand
            )
        } else {
            // Synced state
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                Icon(
                    imageVector = Icons.Rounded.CheckCircle,
                    contentDescription = null,
                    tint = FinanceTheme.colors.moneyIn,
                    modifier = Modifier.size(13.dp)
                )
                Text(
                    text = lastSyncedText,
                    style = FinanceTheme.typography.numeric.copy(fontSize = 11.sp),
                    color = FinanceTheme.colors.moneyIn
                )
            }

            if (waitingCount > 0) {
                Text(
                    text = "•",
                    style = FinanceTheme.typography.numeric.copy(fontSize = 11.sp),
                    color = FinanceTheme.colors.onSurfaceVariant.copy(alpha = 0.5f)
                )
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    Icon(
                        imageVector = Icons.Rounded.Schedule,
                        contentDescription = null,
                        tint = FinanceTheme.colors.onSurfaceVariant,
                        modifier = Modifier.size(13.dp)
                    )
                    Text(
                        text = "$waitingCount waiting",
                        style = FinanceTheme.typography.numeric.copy(fontSize = 11.sp),
                        color = FinanceTheme.colors.onSurfaceVariant
                    )
                }
            }

            if (failedCount > 0) {
                Text(
                    text = "•",
                    style = FinanceTheme.typography.numeric.copy(fontSize = 11.sp),
                    color = FinanceTheme.colors.onSurfaceVariant.copy(alpha = 0.5f)
                )
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    Icon(
                        imageVector = Icons.Rounded.Warning,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.error,
                        modifier = Modifier.size(13.dp)
                    )
                    Text(
                        text = "$failedCount failed",
                        style = FinanceTheme.typography.numeric.copy(fontSize = 11.sp),
                        color = MaterialTheme.colorScheme.error
                    )
                }
            }
        }
    }
}

/**
 * Backward compatibility overloads.
 */
@Composable
fun SyncStatusLine(
    pendingCount: Int,
    isSyncing: Boolean,
    modifier: Modifier = Modifier,
    isOffline: Boolean = false,
    lastSyncedText: String = "Synced just now"
) {
    SyncStatusLine(
        modifier = modifier,
        waitingCount = pendingCount,
        isSyncing = isSyncing,
        isOffline = isOffline,
        lastSyncedText = lastSyncedText
    )
}

@Composable
fun SyncStatusLine(
    statusText: String,
    modifier: Modifier = Modifier,
    isOffline: Boolean = false,
    hasPending: Boolean = false
) {
    SyncStatusLine(
        modifier = modifier,
        waitingCount = if (hasPending) 1 else 0,
        isOffline = isOffline,
        lastSyncedText = statusText
    )
}

@Preview(name = "Light", showBackground = true)
@Composable
private fun SyncStatusLinePreviewLight() {
    FinanceAutomationTheme(darkTheme = false) {
        SyncStatusLine(waitingCount = 2, failedCount = 1)
    }
}

@Preview(name = "Dark", uiMode = Configuration.UI_MODE_NIGHT_YES, showBackground = true)
@Composable
private fun SyncStatusLinePreviewDark() {
    FinanceAutomationTheme(darkTheme = true) {
        SyncStatusLine(waitingCount = 0, failedCount = 0)
    }
}

@Preview(name = "Font Scale 1.3x", fontScale = 1.3f, showBackground = true)
@Composable
private fun SyncStatusLinePreviewScale() {
    FinanceAutomationTheme(darkTheme = false) {
        SyncStatusLine(waitingCount = 3, failedCount = 0)
    }
}
