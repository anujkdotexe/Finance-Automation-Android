package com.financeautomation.presentation.flows

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Checkbox
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.snapshots.SnapshotStateList
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import com.financeautomation.R
import com.financeautomation.presentation.theme.FinanceTheme

/**
 * Picker section for selecting open debits to reconcile in Friend Settlement (Phase 5.6 point 2).
 */
@Composable
fun OpenDebtPickerSection(
    openDebts: List<OpenDebtItem>,
    autoSettleAll: Boolean,
    selectedDebtIds: SnapshotStateList<String>,
    onToggleAutoSettle: (Boolean) -> Unit,
    modifier: Modifier = Modifier
) {
    Column(modifier = modifier.fillMaxWidth()) {
        Text(
            text = stringResource(R.string.prompt_select_bill_to_settle),
            style = FinanceTheme.typography.labelMedium,
            fontWeight = FontWeight.SemiBold,
            color = FinanceTheme.colors.onSurfaceVariant,
            modifier = Modifier.fillMaxWidth()
        )
        Spacer(modifier = Modifier.height(FinanceTheme.spacing.xs))

        // Auto-Settle All option
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable { onToggleAutoSettle(true) }
                .padding(vertical = FinanceTheme.spacing.xs),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Checkbox(
                checked = autoSettleAll,
                onCheckedChange = { onToggleAutoSettle(it) }
            )
            Spacer(modifier = Modifier.width(FinanceTheme.spacing.s))
            Text(
                text = stringResource(R.string.prompt_auto_settle),
                style = FinanceTheme.typography.bodyMedium,
                fontWeight = FontWeight.Medium
            )
        }

        // Individual open debts
        openDebts.forEach { debt ->
            val isSelected = selectedDebtIds.contains(debt.uuid)
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable {
                        if (isSelected) selectedDebtIds.remove(debt.uuid) else selectedDebtIds.add(debt.uuid)
                        if (selectedDebtIds.isEmpty()) onToggleAutoSettle(true) else onToggleAutoSettle(false)
                    }
                    .padding(vertical = FinanceTheme.spacing.xs),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Checkbox(
                    checked = isSelected && !autoSettleAll,
                    onCheckedChange = { checked ->
                        if (checked) selectedDebtIds.add(debt.uuid) else selectedDebtIds.remove(debt.uuid)
                        if (selectedDebtIds.isEmpty()) onToggleAutoSettle(true) else onToggleAutoSettle(false)
                    }
                )
                Spacer(modifier = Modifier.width(FinanceTheme.spacing.s))
                Text(
                    text = debt.displayLabel,
                    style = FinanceTheme.typography.bodySmall,
                    color = FinanceTheme.colors.onSurface
                )
            }
        }
        Spacer(modifier = Modifier.height(FinanceTheme.spacing.s))
    }
}
