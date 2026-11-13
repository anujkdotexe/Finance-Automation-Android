package com.financeautomation.presentation.components

import android.content.res.Configuration
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.financeautomation.presentation.theme.FinanceAutomationTheme
import com.financeautomation.presentation.theme.FinanceTheme

/**
 * Standard section title with optional right-aligned action link.
 * Minimum touch target of 48dp on interactive elements.
 */
@Composable
fun SectionTitle(
    title: String,
    modifier: Modifier = Modifier,
    actionText: String? = null,
    onActionClick: (() -> Unit)? = null
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .defaultMinSize(minHeight = FinanceTheme.sizes.minTouchTarget)
            .padding(vertical = FinanceTheme.spacing.xs),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = title,
            style = FinanceTheme.typography.titleMedium,
            color = FinanceTheme.colors.onSurface
        )

        if (!actionText.isNullOrBlank() && onActionClick != null) {
            LinkButton(
                text = actionText,
                onClick = onActionClick
            )
        }
    }
}

@Preview(name = "Light", showBackground = true)
@Composable
private fun SectionTitlePreviewLight() {
    FinanceAutomationTheme(darkTheme = false) {
        SectionTitle(title = "Where it went", actionText = "See all", onActionClick = {})
    }
}

@Preview(name = "Dark", uiMode = Configuration.UI_MODE_NIGHT_YES, showBackground = true)
@Composable
private fun SectionTitlePreviewDark() {
    FinanceAutomationTheme(darkTheme = true) {
        SectionTitle(title = "Recent", actionText = "See all", onActionClick = {})
    }
}

@Preview(name = "Font Scale 1.3x", fontScale = 1.3f, showBackground = true)
@Composable
private fun SectionTitlePreviewScale() {
    FinanceAutomationTheme(darkTheme = false) {
        SectionTitle(title = "Where it went", actionText = "See all", onActionClick = {})
    }
}

