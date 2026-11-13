package com.financeautomation.presentation.screens.drawer

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import com.financeautomation.R
import com.financeautomation.core.utils.formatRupees
import com.financeautomation.domain.repository.BudgetRepository
import com.financeautomation.presentation.components.ButtonPair
import com.financeautomation.presentation.components.OutlinedButton
import com.financeautomation.presentation.theme.FinanceShapes
import com.financeautomation.presentation.theme.FinanceTheme
import com.financeautomation.presentation.viewmodel.BudgetSheetStateHolder

/**
 * "Monthly budget for <category>" sheet. The amount field accepts simple sums and shows the result live. Save and
 * Cancel are an equal width pair with Cancel on the left; "Clear budget" appears only when a budget exists.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BudgetSheet(
    category: String,
    existingAmount: Double?,
    repository: BudgetRepository,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier
) {
    val scope = rememberCoroutineScope()
    val holder = remember(category) { BudgetSheetStateHolder(category, existingAmount, repository, scope) }
    val state by holder.state.collectAsState()
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

    LaunchedEffect(state.finished) {
        if (state.finished) onDismiss()
    }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = FinanceTheme.colors.surfaceContainerLowest,
        shape = FinanceShapes.sheet,
        modifier = modifier
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = FinanceTheme.spacing.l)
                .padding(bottom = FinanceTheme.spacing.l)
                .imePadding()
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = androidx.compose.ui.Alignment.CenterVertically
            ) {
                Text(
                    text = stringResource(R.string.budget_sheet_title, category),
                    style = FinanceTheme.typography.titleLarge,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.weight(1f)
                )
                IconButton(
                    onClick = onDismiss,
                    modifier = Modifier.padding(start = FinanceTheme.spacing.s)
                ) {
                    Icon(
                        imageVector = Icons.Rounded.Close,
                        contentDescription = stringResource(R.string.action_cancel),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            Spacer(modifier = Modifier.height(FinanceTheme.spacing.m))

            Text(
                text = stringResource(R.string.budget_sheet_amount_label),
                style = FinanceTheme.typography.titleSmall,
                fontWeight = FontWeight.Medium,
                color = MaterialTheme.colorScheme.onSurface
            )
            Spacer(modifier = Modifier.height(FinanceTheme.spacing.xs))
            OutlinedTextField(
                value = state.input,
                onValueChange = holder::onInputChanged,
                placeholder = { Text(stringResource(R.string.budget_sheet_amount_placeholder)) },
                singleLine = true,
                isError = state.showInvalid,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Text),
                shape = FinanceShapes.medium,
                modifier = Modifier.fillMaxWidth(),
                supportingText = {
                    val preview = state.preview
                    when {
                        state.showInvalid -> Text(stringResource(R.string.budget_sheet_invalid))
                        preview != null -> Text(stringResource(R.string.budget_sheet_result, formatRupees(preview, includeDecimals = false)))
                        else -> Text(stringResource(R.string.budget_sheet_hint))
                    }
                }
            )
            if (state.saveFailed) {
                Text(
                    text = stringResource(R.string.budget_sheet_save_failed),
                    style = FinanceTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error
                )
            }

            Spacer(modifier = Modifier.height(FinanceTheme.spacing.l))

            ButtonPair(
                outlinedText = stringResource(R.string.action_cancel),
                onOutlinedClick = onDismiss,
                filledText = stringResource(R.string.action_save),
                onFilledClick = holder::save,
                filledEnabled = state.canSave,
                modifier = Modifier.fillMaxWidth()
            )

            if (state.hasExisting) {
                Spacer(modifier = Modifier.height(FinanceTheme.spacing.s))
                OutlinedButton(
                    text = stringResource(R.string.budget_sheet_clear),
                    onClick = holder::clear,
                    enabled = !state.isSaving,
                    modifier = Modifier.fillMaxWidth()
                )
            }
        }
    }
}
