package com.financeautomation.presentation.components

import android.content.res.Configuration
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.HourglassEmpty
import androidx.compose.material.icons.rounded.Rule
import androidx.compose.material.icons.rounded.Schedule
import androidx.compose.material.icons.rounded.Sync
import androidx.compose.material.icons.rounded.Warning
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.financeautomation.core.model.TransactionStatus
import com.financeautomation.presentation.theme.FinanceAutomationTheme
import com.financeautomation.presentation.theme.FinanceShapes
import com.financeautomation.presentation.theme.FinanceTheme

/**
 * Verified status chip with one-line icon + text and calm tonal colors (red only for Failed).
 * Supported: Pending, Needs review, Synced, Failed, Waiting to sync, plus legacy ledger tags.
 */
@Composable
fun StatusChip(
    status: TransactionStatus,
    modifier: Modifier = Modifier
) {
    StatusChip(status = status.value, modifier = modifier)
}

@Composable
fun StatusChip(
    status: String,
    modifier: Modifier = Modifier
) {
    val normalized = status.trim()
    val upper = normalized.uppercase()

    val (bgColor, textColor, icon) = when {
        upper == "FAILED" -> Triple(
            MaterialTheme.colorScheme.errorContainer,
            MaterialTheme.colorScheme.onErrorContainer,
            Icons.Rounded.Warning
        )
        upper == "NEEDS REVIEW" || upper == "NEEDS_REVIEW" -> Triple(
            MaterialTheme.colorScheme.secondaryContainer,
            MaterialTheme.colorScheme.onSecondaryContainer,
            Icons.Rounded.Rule
        )
        upper == "WAITING TO SYNC" || upper == "WAITING_TO_SYNC" -> Triple(
            MaterialTheme.colorScheme.surfaceVariant,
            MaterialTheme.colorScheme.onSurfaceVariant,
            Icons.Rounded.Sync
        )
        upper == "SYNCED" || upper == "SETTLED" || upper == "REPAID" -> Triple(
            FinanceTheme.colors.moneyIn.copy(alpha = 0.15f),
            FinanceTheme.colors.moneyIn,
            Icons.Rounded.CheckCircle
        )
        upper == "PENDING" || upper == "PARTIAL" -> Triple(
            MaterialTheme.colorScheme.secondaryContainer,
            MaterialTheme.colorScheme.onSecondaryContainer,
            Icons.Rounded.Schedule
        )
        upper == "CARRIED FWD" || upper == "CARRIED_FWD" -> Triple(
            FinanceTheme.colors.info.copy(alpha = 0.15f),
            FinanceTheme.colors.info,
            Icons.Rounded.HourglassEmpty
        )
        else -> Triple(
            MaterialTheme.colorScheme.surfaceVariant,
            MaterialTheme.colorScheme.onSurfaceVariant,
            null
        )
    }

    Row(
        modifier = modifier
            .background(bgColor, FinanceShapes.chip)
            .padding(horizontal = FinanceTheme.spacing.s, vertical = FinanceTheme.spacing.xxs),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(FinanceTheme.spacing.xs)
    ) {
        if (icon != null) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = textColor,
                modifier = Modifier.size(FinanceTheme.sizes.icon12)
            )
        }
        Text(
            text = normalized,
            style = FinanceTheme.typography.numericMicro,
            color = textColor,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}

@Preview(name = "Light", showBackground = true)
@Composable
private fun StatusChipPreviewLight() {
    FinanceAutomationTheme(darkTheme = false) {
        Row(horizontalArrangement = Arrangement.spacedBy(FinanceTheme.spacing.xs)) {
            StatusChip(status = "Synced")
            StatusChip(status = "Pending")
            StatusChip(status = "Needs review")
            StatusChip(status = "Waiting to sync")
            StatusChip(status = "Failed")
        }
    }
}

@Preview(name = "Dark", uiMode = Configuration.UI_MODE_NIGHT_YES, showBackground = true)
@Composable
private fun StatusChipPreviewDark() {
    FinanceAutomationTheme(darkTheme = true) {
        Row(horizontalArrangement = Arrangement.spacedBy(FinanceTheme.spacing.xs)) {
            StatusChip(status = "Pending")
            StatusChip(status = "Failed")
        }
    }
}

@Preview(name = "Font Scale 1.3x", fontScale = 1.3f, showBackground = true)
@Composable
private fun StatusChipPreviewScale() {
    FinanceAutomationTheme(darkTheme = false) {
        StatusChip(status = "Waiting to sync")
    }
}
