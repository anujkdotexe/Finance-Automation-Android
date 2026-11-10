package com.financeautomation.presentation.components

import android.content.res.Configuration
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import com.financeautomation.presentation.theme.FinanceAutomationTheme
import com.financeautomation.presentation.theme.FinanceTheme

/**
 * Standard labeled input/content container for bottom sheets and settings forms (Design Guide 4).
 */
@Composable
fun FieldRow(
    label: String,
    modifier: Modifier = Modifier,
    helperText: String? = null,
    isError: Boolean = false,
    content: @Composable () -> Unit
) {
    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(FinanceTheme.spacing.xs)
    ) {
        Text(
            text = label,
            style = FinanceTheme.typography.labelMedium,
            color = if (isError) FinanceTheme.colors.moneyOut else FinanceTheme.colors.onSurfaceVariant
        )

        content()

        if (!helperText.isNullOrBlank()) {
            Text(
                text = helperText,
                style = FinanceTheme.typography.labelMedium,
                color = if (isError) FinanceTheme.colors.moneyOut else FinanceTheme.colors.onSurfaceVariant
            )
        }
    }
}

@Preview(name = "Light", showBackground = true)
@Composable
private fun FieldRowPreviewLight() {
    FinanceAutomationTheme(darkTheme = false) {
        FieldRow(label = androidx.compose.ui.res.stringResource(com.financeautomation.R.string.prompt_what_was_this_for)) {
            Text(text = androidx.compose.ui.res.stringResource(com.financeautomation.R.string.preview_sample_merchant), style = FinanceTheme.typography.bodyMedium)
        }
    }
}

@Preview(name = "Dark", uiMode = Configuration.UI_MODE_NIGHT_YES, showBackground = true)
@Composable
private fun FieldRowPreviewDark() {
    FinanceAutomationTheme(darkTheme = true) {
        FieldRow(label = androidx.compose.ui.res.stringResource(com.financeautomation.R.string.label_amount), isError = false) {
            AmountText(amount = 1250.0)
        }
    }
}

@Preview(name = "Large Font 200%", fontScale = 2.0f, showBackground = true)
@Composable
private fun FieldRowPreviewLargeFont() {
    FinanceAutomationTheme(darkTheme = false) {
        FieldRow(label = androidx.compose.ui.res.stringResource(com.financeautomation.R.string.prompt_select_account)) {
            Text(text = androidx.compose.ui.res.stringResource(com.financeautomation.R.string.preview_sample_merchant), style = FinanceTheme.typography.bodyMedium)
        }
    }
}

