package com.financeautomation.presentation.components

import android.content.res.Configuration
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
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
 * Standard AppCard: 16dp radius, no border, one fill (surfaceContainerLow by default),
 * 16dp internal padding, 12dp spacing between cards.
 * Cards of one list share one height where the content type is the same.
 */
@Composable
fun AppCard(
    modifier: Modifier = Modifier,
    containerColor: Color = FinanceTheme.colors.surfaceContainerLow,
    content: @Composable ColumnScope.() -> Unit
) {
    Card(
        modifier = modifier.fillMaxWidth(),
        shape = FinanceShapes.appCard,
        colors = CardDefaults.cardColors(containerColor = containerColor),
        elevation = CardDefaults.cardElevation(defaultElevation = FinanceTheme.spacing.none),
        border = null
    ) {
        Column(
            modifier = Modifier.padding(FinanceTheme.sizes.cardPadding),
            content = content
        )
    }
}

/**
 * Backwards-compatibility alias for TonalCard.
 */
@Composable
fun TonalCard(
    modifier: Modifier = Modifier,
    containerColor: Color = FinanceTheme.colors.surfaceContainerLowest,
    content: @Composable ColumnScope.() -> Unit
) {
    AppCard(
        modifier = modifier,
        containerColor = containerColor,
        content = content
    )
}

@Preview(name = "AppCard Normal Font - Light", showBackground = true, fontScale = 1.0f)
@Composable
private fun AppCardPreviewNormalLight() {
    FinanceAutomationTheme(darkTheme = false) {
        Column(
            modifier = Modifier.padding(FinanceTheme.spacing.l),
            verticalArrangement = Arrangement.spacedBy(FinanceTheme.spacing.m)
        ) {
            AppCard {
                Text(text = "Card Title", style = MaterialTheme.typography.titleMedium)
                Text(text = "Card content with standard 16dp padding and 16dp corner radius.")
            }
        }
    }
}

@Preview(name = "AppCard Large Font 1.3 - Light", showBackground = true, fontScale = 1.3f)
@Composable
private fun AppCardPreviewLargeFontLight() {
    FinanceAutomationTheme(darkTheme = false) {
        Column(
            modifier = Modifier.padding(FinanceTheme.spacing.l),
            verticalArrangement = Arrangement.spacedBy(FinanceTheme.spacing.m)
        ) {
            AppCard {
                Text(text = "Card Title", style = MaterialTheme.typography.titleMedium)
                Text(text = "Card content with standard 16dp padding and 16dp corner radius.")
            }
        }
    }
}

@Preview(name = "AppCard Normal Font - Dark", showBackground = true, fontScale = 1.0f, uiMode = Configuration.UI_MODE_NIGHT_YES)
@Composable
private fun AppCardPreviewNormalDark() {
    FinanceAutomationTheme(darkTheme = true) {
        Column(
            modifier = Modifier.padding(FinanceTheme.spacing.l),
            verticalArrangement = Arrangement.spacedBy(FinanceTheme.spacing.m)
        ) {
            AppCard {
                Text(text = "Card Title", style = MaterialTheme.typography.titleMedium)
                Text(text = "Card content in dark mode with calm surface container fill.")
            }
        }
    }
}

@Preview(name = "AppCard Large Font 1.3 - Dark", showBackground = true, fontScale = 1.3f, uiMode = Configuration.UI_MODE_NIGHT_YES)
@Composable
private fun AppCardPreviewLargeFontDark() {
    FinanceAutomationTheme(darkTheme = true) {
        Column(
            modifier = Modifier.padding(FinanceTheme.spacing.l),
            verticalArrangement = Arrangement.spacedBy(FinanceTheme.spacing.m)
        ) {
            AppCard {
                Text(text = "Card Title", style = MaterialTheme.typography.titleMedium)
                Text(text = "Card content in dark mode with calm surface container fill.")
            }
        }
    }
}
