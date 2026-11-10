package com.financeautomation.presentation.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowRight
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.financeautomation.presentation.components.AmountText
import com.financeautomation.presentation.components.AmountTone
import com.financeautomation.presentation.components.SyncStatusLine
import com.financeautomation.presentation.theme.FinanceTheme
import com.financeautomation.presentation.viewmodel.CategorySpend

/**
 * Month header with navigation controls and sync status line (Design Guide 5.1).
 */
@Composable
fun MonthHeader(
    monthLabel: String,
    onPreviousMonth: () -> Unit,
    onNextMonth: () -> Unit,
    syncQueueCount: Int,
    isSyncing: Boolean,
    modifier: Modifier = Modifier
) {
    Column(modifier = modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(onClick = onPreviousMonth, modifier = Modifier.size(48.dp)) {
                Icon(
                    imageVector = Icons.AutoMirrored.Rounded.KeyboardArrowLeft,
                    contentDescription = "Previous Month",
                    tint = FinanceTheme.colors.onSurface
                )
            }

            Text(
                text = monthLabel,
                style = FinanceTheme.typography.titleMedium,
                color = FinanceTheme.colors.onSurface
            )

            IconButton(onClick = onNextMonth, modifier = Modifier.size(48.dp)) {
                Icon(
                    imageVector = Icons.AutoMirrored.Rounded.KeyboardArrowRight,
                    contentDescription = "Next Month",
                    tint = FinanceTheme.colors.onSurface
                )
            }
        }

        SyncStatusLine(
            pendingCount = syncQueueCount,
            isSyncing = isSyncing,
            modifier = Modifier.align(Alignment.CenterHorizontally)
        )
    }
}

/**
 * Category breakdown row with thin proportional bar (Design Guide 5.1).
 */
@Composable
fun CategoryBarRow(item: CategorySpend, modifier: Modifier = Modifier) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(vertical = FinanceTheme.spacing.xs)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = item.category,
                style = FinanceTheme.typography.bodyMedium,
                color = FinanceTheme.colors.onSurface
            )

            Row(horizontalArrangement = Arrangement.spacedBy(FinanceTheme.spacing.s)) {
                AmountText(amount = item.amount, tone = AmountTone.MONEY_OUT)
                Text(
                    text = "${(item.percentage * 100).toInt()}%",
                    style = FinanceTheme.typography.labelMedium,
                    color = FinanceTheme.colors.onSurfaceVariant
                )
            }
        }

        Spacer(modifier = Modifier.height(FinanceTheme.spacing.xs))

        LinearProgressIndicator(
            progress = { item.percentage },
            modifier = Modifier
                .fillMaxWidth()
                .height(4.dp),
            color = FinanceTheme.colors.brand,
            trackColor = FinanceTheme.colors.surfaceVariant
        )
    }
}

