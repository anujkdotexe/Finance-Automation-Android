package com.financeautomation.presentation.components

import android.content.res.Configuration
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ChevronRight
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.financeautomation.core.utils.accountInitial
import com.financeautomation.presentation.theme.FinanceAutomationTheme
import com.financeautomation.presentation.theme.FinanceShapes
import com.financeautomation.presentation.theme.FinanceTheme

/**
 * Renders an individual companion row for Owed / Owe sections:
 * Initials circle (36dp), person name, amount in mono, chevron.
 */
@Composable
fun PersonDebtRow(
    name: String,
    amount: Double,
    modifier: Modifier = Modifier,
    isOwedToMe: Boolean = true,
    onClick: (() -> Unit)? = null
) {
    val initials = name.trim().split(" ")
        .filter { it.isNotBlank() }
        .take(2)
        .map { it.first().uppercase() }
        .joinToString("")
        .ifBlank { accountInitial(name) }

    Row(
        modifier = modifier
            .fillMaxWidth()
            .defaultMinSize(minHeight = 48.dp)
            .clip(FinanceShapes.small)
            .background(FinanceTheme.colors.surfaceContainerLow)
            .then(
                if (onClick != null) {
                    Modifier.clickable(role = Role.Button, onClick = onClick)
                } else Modifier
            )
            .padding(horizontal = FinanceTheme.spacing.s, vertical = FinanceTheme.spacing.s),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Row(
            modifier = Modifier.weight(1f).padding(end = FinanceTheme.spacing.s),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(FinanceTheme.spacing.s)
        ) {
            Box(
                modifier = Modifier
                    .size(32.dp)
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.secondaryContainer),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = initials,
                    style = FinanceTheme.typography.titleSmall.copy(fontWeight = FontWeight.Bold, fontSize = 12.sp),
                    color = MaterialTheme.colorScheme.onSecondaryContainer
                )
            }
            Text(
                text = name,
                style = FinanceTheme.typography.bodyMedium,
                color = FinanceTheme.colors.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }

        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            AmountText(
                amount = amount,
                tone = AmountTone.DEFAULT,
                style = FinanceTheme.typography.numeric.copy(fontWeight = FontWeight.SemiBold)
            )
            Icon(
                imageVector = Icons.Rounded.ChevronRight,
                contentDescription = null,
                tint = FinanceTheme.colors.onSurfaceVariant,
                modifier = Modifier.size(18.dp)
            )
        }
    }
}

@Preview(name = "Light", showBackground = true)
@Composable
private fun PersonDebtRowPreviewLight() {
    FinanceAutomationTheme(darkTheme = false) {
        PersonDebtRow(name = "Rahul V.", amount = 1400.0, isOwedToMe = true, onClick = {})
    }
}

@Preview(name = "Dark", uiMode = Configuration.UI_MODE_NIGHT_YES, showBackground = true)
@Composable
private fun PersonDebtRowPreviewDark() {
    FinanceAutomationTheme(darkTheme = true) {
        PersonDebtRow(name = "Karan M.", amount = 1120.0, isOwedToMe = false, onClick = {})
    }
}

@Preview(name = "Font Scale 1.3x", fontScale = 1.3f, showBackground = true)
@Composable
private fun PersonDebtRowPreviewScale() {
    FinanceAutomationTheme(darkTheme = false) {
        PersonDebtRow(name = "Priya Sharma", amount = 650.0, isOwedToMe = true, onClick = {})
    }
}

