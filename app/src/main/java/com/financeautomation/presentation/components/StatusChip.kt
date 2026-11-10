package com.financeautomation.presentation.components

import android.content.res.Configuration
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.financeautomation.core.model.TransactionStatus
import com.financeautomation.presentation.theme.FinanceAutomationTheme
import com.financeautomation.presentation.theme.FinanceTheme

/**
 * Renders verified transaction status tags with explicit text and tint (Design Guide 4 & 8).
 * Never relies on color alone for state.
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
    val (bgColor, textColor) = when (status.uppercase()) {
        "SETTLED", "REPAID" -> FinanceTheme.colors.moneyIn.copy(alpha = 0.15f) to FinanceTheme.colors.moneyIn
        "PENDING", "PARTIAL" -> FinanceTheme.colors.moneyOwed.copy(alpha = 0.15f) to FinanceTheme.colors.moneyOwed
        "CARRIED FWD", "CARRIED_FWD" -> FinanceTheme.colors.info.copy(alpha = 0.15f) to FinanceTheme.colors.info
        "VOID" -> FinanceTheme.colors.moneyOut.copy(alpha = 0.15f) to FinanceTheme.colors.moneyOut
        "PERSONAL" -> FinanceTheme.colors.surfaceVariant to FinanceTheme.colors.onSurfaceVariant
        else -> FinanceTheme.colors.surfaceVariant to FinanceTheme.colors.onSurfaceVariant
    }

    Box(
        modifier = modifier
            .background(bgColor, FinanceTheme.shapes.chip)
            .padding(horizontal = FinanceTheme.spacing.s, vertical = 2.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = status,
            style = FinanceTheme.typography.labelMedium,
            color = textColor
        )
    }
}

@Preview(name = "Light", showBackground = true)
@Composable
private fun StatusChipPreviewLight() {
    FinanceAutomationTheme(darkTheme = false) {
        StatusChip(status = "Settled")
    }
}

@Preview(name = "Dark", uiMode = Configuration.UI_MODE_NIGHT_YES, showBackground = true)
@Composable
private fun StatusChipPreviewDark() {
    FinanceAutomationTheme(darkTheme = true) {
        StatusChip(status = "Pending")
    }
}

@Preview(name = "Large Font 200%", fontScale = 2.0f, showBackground = true)
@Composable
private fun StatusChipPreviewLargeFont() {
    FinanceAutomationTheme(darkTheme = false) {
        StatusChip(status = "Personal")
    }
}

