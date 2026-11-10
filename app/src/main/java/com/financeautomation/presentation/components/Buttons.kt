package com.financeautomation.presentation.components

import android.content.res.Configuration
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.financeautomation.presentation.theme.FinanceAutomationTheme
import com.financeautomation.presentation.theme.FinanceTheme

/**
 * Primary high-emphasis button for core flow confirmations (Design Guide 4).
 */
@Composable
fun PrimaryButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    isLoading: Boolean = false
) {
    Button(
        onClick = onClick,
        enabled = enabled && !isLoading,
        shape = FinanceTheme.shapes.medium,
        colors = ButtonDefaults.buttonColors(
            containerColor = FinanceTheme.colors.brand,
            contentColor = FinanceTheme.colors.surface
        ),
        modifier = modifier
            .fillMaxWidth()
            .defaultMinSize(minHeight = 48.dp)
    ) {
        if (isLoading) {
            CircularProgressIndicator(
                modifier = Modifier.size(20.dp),
                strokeWidth = 2.dp,
                color = FinanceTheme.colors.surface
            )
        } else {
            Text(
                text = text,
                style = FinanceTheme.typography.titleMedium
            )
        }
    }
}

/**
 * Secondary outlined button for secondary choices or dismissal (Design Guide 4).
 */
@Composable
fun SecondaryButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true
) {
    OutlinedButton(
        onClick = onClick,
        enabled = enabled,
        shape = FinanceTheme.shapes.medium,
        border = BorderStroke(1.dp, FinanceTheme.colors.outline),
        colors = ButtonDefaults.outlinedButtonColors(
            contentColor = FinanceTheme.colors.onSurface
        ),
        modifier = modifier
            .fillMaxWidth()
            .defaultMinSize(minHeight = 48.dp)
    ) {
        Text(
            text = text,
            style = FinanceTheme.typography.titleMedium
        )
    }
}

/**
 * Lightweight text action link (Design Guide 4).
 */
@Composable
fun TextAction(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true
) {
    TextButton(
        onClick = onClick,
        enabled = enabled,
        modifier = modifier.defaultMinSize(minHeight = 48.dp)
    ) {
        Text(
            text = text,
            style = FinanceTheme.typography.labelMedium,
            color = FinanceTheme.colors.info
        )
    }
}

@Preview(name = "Light", showBackground = true)
@Composable
private fun ButtonsPreviewLight() {
    FinanceAutomationTheme(darkTheme = false) {
        PrimaryButton(text = androidx.compose.ui.res.stringResource(com.financeautomation.R.string.quick_add_tile_label), onClick = {})
    }
}

@Preview(name = "Dark", uiMode = Configuration.UI_MODE_NIGHT_YES, showBackground = true)
@Composable
private fun ButtonsPreviewDark() {
    FinanceAutomationTheme(darkTheme = true) {
        SecondaryButton(text = androidx.compose.ui.res.stringResource(com.financeautomation.R.string.action_cancel), onClick = {})
    }
}

@Preview(name = "Large Font 200%", fontScale = 2.0f, showBackground = true)
@Composable
private fun ButtonsPreviewLargeFont() {
    FinanceAutomationTheme(darkTheme = false) {
        TextAction(text = androidx.compose.ui.res.stringResource(com.financeautomation.R.string.action_see_all), onClick = {})
    }
}

