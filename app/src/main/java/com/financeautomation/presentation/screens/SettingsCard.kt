package com.financeautomation.presentation.screens

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import com.financeautomation.presentation.theme.FinanceTheme

/**
 * Standard group card for settings sections using calm utility design tokens (Design Guide 4).
 */
@Composable
fun SettingsCard(
    title: String,
    subtitle: String,
    icon: ImageVector,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit
) {
    Card(
        colors = CardDefaults.cardColors(containerColor = FinanceTheme.colors.surface),
        shape = FinanceTheme.shapes.large,
        border = BorderStroke(FinanceTheme.sizes.strokeThin, FinanceTheme.colors.outline),
        modifier = modifier.fillMaxWidth()
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(FinanceTheme.spacing.l),
            verticalArrangement = Arrangement.spacedBy(FinanceTheme.spacing.m)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    modifier = Modifier
                        .size(FinanceTheme.sizes.avatarSmall)
                        .background(FinanceTheme.colors.brand.copy(alpha = 0.12f), CircleShape),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = icon,
                        contentDescription = title,
                        tint = FinanceTheme.colors.brand,
                        modifier = Modifier.size(FinanceTheme.sizes.iconMedium)
                    )
                }
                Spacer(modifier = Modifier.width(FinanceTheme.spacing.m))
                Column {
                    Text(
                        text = title,
                        style = FinanceTheme.typography.titleMedium,
                        color = FinanceTheme.colors.onSurface
                    )
                    Text(
                        text = subtitle,
                        style = FinanceTheme.typography.labelMedium,
                        color = FinanceTheme.colors.onSurfaceVariant
                    )
                }
            }
            content()
        }
    }
}
