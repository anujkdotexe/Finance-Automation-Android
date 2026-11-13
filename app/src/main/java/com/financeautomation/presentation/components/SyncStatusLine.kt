package com.financeautomation.presentation.components

import android.content.res.Configuration
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowRight
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.financeautomation.R
import com.financeautomation.presentation.theme.FinanceAutomationTheme
import com.financeautomation.presentation.theme.FinanceShapes
import com.financeautomation.presentation.theme.FinanceTheme

/**
 * Standard single-line quiet sync status indicator.
 * Typography: 12sp Medium.
 * States:
 * - "Synced 2m ago" (neutral)
 * - "N waiting to sync" (neutral)
 * - "N failed, tap to fix" (secondary container pill, chevron, opens failed items in Needs Review)
 * - "Offline, N waiting to sync" (neutral)
 * No pills for neutral states, no animated icons.
 */
@Composable
fun SyncStatusLine(
    modifier: Modifier = Modifier,
    waitingCount: Int = 0,
    failedCount: Int = 0,
    isSyncing: Boolean = false,
    isOffline: Boolean = false,
    lastSyncedText: String = "Synced just now",
    onFailedClick: () -> Unit = {},
    onClick: (() -> Unit)? = null
) {
    if (failedCount > 0) {
        // Calm secondary container pill with chevron
        Surface(
            shape = FinanceShapes.pill,
            color = MaterialTheme.colorScheme.secondaryContainer,
            contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
            modifier = modifier.clickable(
                role = Role.Button,
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = onFailedClick
            )
        ) {
            Row(
                modifier = Modifier.padding(horizontal = FinanceTheme.spacing.compact, vertical = FinanceTheme.spacing.xxs),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(FinanceTheme.spacing.xs)
            ) {
                Text(
                    text = stringResource(R.string.sync_status_failed_tap_to_fix, failedCount),
                    style = FinanceTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSecondaryContainer
                )
                Icon(
                    imageVector = Icons.AutoMirrored.Rounded.KeyboardArrowRight,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSecondaryContainer,
                    modifier = Modifier.size(FinanceTheme.sizes.iconXs)
                )
            }
        }
    } else {
        val statusText = when {
            isOffline -> if (waitingCount > 0) "Offline, $waitingCount waiting to sync" else "Offline"
            waitingCount > 0 -> "$waitingCount waiting to sync"
            isSyncing -> "Syncing..."
            else -> lastSyncedText
        }

        Row(
            modifier = modifier
                .then(
                    if (onClick != null) {
                        Modifier.clickable(
                            role = Role.Button,
                            onClick = onClick
                        )
                    } else {
                        Modifier
                    }
                )
                .padding(vertical = FinanceTheme.spacing.xxs),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = statusText,
                style = FinanceTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Preview(name = "Synced - Normal Font", showBackground = true, fontScale = 1.0f)
@Composable
private fun SyncStatusLinePreviewSyncedNormal() {
    FinanceAutomationTheme(darkTheme = false) {
        SyncStatusLine(lastSyncedText = "Synced 2m ago")
    }
}

@Preview(name = "Waiting - Normal Font", showBackground = true, fontScale = 1.0f)
@Composable
private fun SyncStatusLinePreviewWaitingNormal() {
    FinanceAutomationTheme(darkTheme = false) {
        SyncStatusLine(waitingCount = 3)
    }
}

@Preview(name = "Failed - Normal Font", showBackground = true, fontScale = 1.0f)
@Composable
private fun SyncStatusLinePreviewFailedNormal() {
    FinanceAutomationTheme(darkTheme = false) {
        SyncStatusLine(failedCount = 1)
    }
}

@Preview(name = "Offline - Normal Font", showBackground = true, fontScale = 1.0f)
@Composable
private fun SyncStatusLinePreviewOfflineNormal() {
    FinanceAutomationTheme(darkTheme = false) {
        SyncStatusLine(isOffline = true, waitingCount = 2)
    }
}

@Preview(name = "Failed - Font 1.3", showBackground = true, fontScale = 1.3f)
@Composable
private fun SyncStatusLinePreviewFailedLargeFont() {
    FinanceAutomationTheme(darkTheme = false) {
        SyncStatusLine(failedCount = 2)
    }
}

@Preview(name = "Failed - Dark", showBackground = true, fontScale = 1.0f, uiMode = Configuration.UI_MODE_NIGHT_YES)
@Composable
private fun SyncStatusLinePreviewFailedDark() {
    FinanceAutomationTheme(darkTheme = true) {
        SyncStatusLine(failedCount = 1)
    }
}
