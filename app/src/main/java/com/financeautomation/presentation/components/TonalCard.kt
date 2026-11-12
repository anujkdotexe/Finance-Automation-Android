package com.financeautomation.presentation.components

import android.content.res.Configuration
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.financeautomation.presentation.theme.FinanceAutomationTheme
import com.financeautomation.presentation.theme.FinanceShapes
import com.financeautomation.presentation.theme.FinanceTheme

/**
 * Standard card with flat tonal fill, 12dp rounded corners, and 16dp internal padding.
 * Enforces Calm Financial Design with zero outline and zero drop shadows.
 */
@Composable
fun TonalCard(
    modifier: Modifier = Modifier,
    containerColor: Color = FinanceTheme.colors.surfaceContainerLowest,
    content: @Composable ColumnScope.() -> Unit
) {
    Card(
        modifier = modifier.fillMaxWidth(),
        shape = FinanceShapes.card,
        colors = CardDefaults.cardColors(containerColor = containerColor),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
        border = null
    ) {
        Column(
            modifier = Modifier.padding(FinanceTheme.spacing.l),
            content = content
        )
    }
}

@Preview(name = "Light", showBackground = true)
@Composable
private fun TonalCardPreviewLight() {
    FinanceAutomationTheme(darkTheme = false) {
        TonalCard {
            Text(text = "Living burn this month", style = FinanceTheme.typography.titleMedium)
            Text(text = "6549.00", style = FinanceTheme.typography.kpiLarge)
        }
    }
}

@Preview(name = "Dark", showBackground = true, uiMode = Configuration.UI_MODE_NIGHT_YES)
@Composable
private fun TonalCardPreviewDark() {
    FinanceAutomationTheme(darkTheme = true) {
        TonalCard {
            Text(text = "Owed to me", style = FinanceTheme.typography.titleMedium)
            Text(text = "2050.00", style = FinanceTheme.typography.kpiMedium)
        }
    }
}

@Preview(name = "Font Scale 1.3x", showBackground = true, fontScale = 1.3f)
@Composable
private fun TonalCardPreviewScale() {
    FinanceAutomationTheme(darkTheme = false) {
        TonalCard {
            Text(text = "Where it went", style = FinanceTheme.typography.titleMedium)
        }
    }
}

