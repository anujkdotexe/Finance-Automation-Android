package com.financeautomation.presentation.components

import android.content.res.Configuration
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import com.financeautomation.presentation.theme.FinanceAutomationTheme
import com.financeautomation.presentation.theme.FinanceTheme

/**
 * Calm utility empty state with one line of context and at most one action.
 * Enforces zero decorative noise.
 */
@Composable
fun EmptyState(
    message: String,
    modifier: Modifier = Modifier,
    actionText: String? = null,
    onActionClick: (() -> Unit)? = null
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(FinanceTheme.spacing.xl),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(FinanceTheme.spacing.m)
    ) {
        Text(
            text = message,
            style = FinanceTheme.typography.bodyMedium,
            color = FinanceTheme.colors.onSurfaceVariant,
            textAlign = TextAlign.Center
        )

        if (!actionText.isNullOrBlank() && onActionClick != null) {
            PrimaryButton(
                text = actionText,
                onClick = onActionClick
            )
        }
    }
}

@Preview(name = "Light", showBackground = true)
@Composable
private fun EmptyStatePreviewLight() {
    FinanceAutomationTheme(darkTheme = false) {
        EmptyState(
            message = "Nothing logged yet. Pay with UPI and the prompt will appear, or tap Add expense.",
            actionText = "Add expense",
            onActionClick = {}
        )
    }
}

@Preview(name = "Dark", uiMode = Configuration.UI_MODE_NIGHT_YES, showBackground = true)
@Composable
private fun EmptyStatePreviewDark() {
    FinanceAutomationTheme(darkTheme = true) {
        EmptyState(
            message = "All captured transactions reviewed."
        )
    }
}

@Preview(name = "Font Scale 1.3x", fontScale = 1.3f, showBackground = true)
@Composable
private fun EmptyStatePreviewScale() {
    FinanceAutomationTheme(darkTheme = false) {
        EmptyState(
            message = "Nothing logged yet. Pay with UPI and the prompt will appear, or tap Add expense."
        )
    }
}
