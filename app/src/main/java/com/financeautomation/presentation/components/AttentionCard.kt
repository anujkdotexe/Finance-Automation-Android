package com.financeautomation.presentation.components

import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.ErrorOutline
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import com.financeautomation.R
import com.financeautomation.presentation.health.AttentionContent
import com.financeautomation.presentation.health.AttentionState
import com.financeautomation.presentation.theme.FinanceShapes
import com.financeautomation.presentation.theme.FinanceTheme

/**
 * The one card every screen uses for a health, permission or setup state.
 * OK shows a done state; IN_PROGRESS shows a spinner; NEEDS_ATTENTION shows the label, a themed border, the reason in
 * plain words and the one primary button that fixes it. The caller re-checks on resume and passes the new content.
 */
@Composable
fun AttentionCard(
    content: AttentionContent,
    onAction: () -> Unit,
    modifier: Modifier = Modifier,
    detail: String? = null
) {
    val needs = content.state == AttentionState.NEEDS_ATTENTION
    Surface(
        color = FinanceTheme.colors.surfaceContainerLowest,
        shape = FinanceShapes.large,
        shadowElevation = FinanceTheme.sizes.shadowElevationSmall,
        modifier = modifier
            .fillMaxWidth()
            .border(
                width = if (needs) FinanceTheme.sizes.strokeThick else FinanceTheme.sizes.strokeThin,
                color = if (needs) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.outlineVariant,
                shape = FinanceShapes.large
            )
    ) {
        Column(
            modifier = Modifier.padding(FinanceTheme.spacing.m),
            verticalArrangement = Arrangement.spacedBy(FinanceTheme.spacing.s)
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(FinanceTheme.spacing.m)
            ) {
                when (content.state) {
                    AttentionState.OK -> Icon(
                        imageVector = Icons.Rounded.CheckCircle,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(FinanceTheme.sizes.iconMedium)
                    )
                    AttentionState.IN_PROGRESS -> CircularProgressIndicator(
                        modifier = Modifier.size(FinanceTheme.sizes.iconMedium),
                        strokeWidth = FinanceTheme.sizes.strokeThick
                    )
                    AttentionState.NEEDS_ATTENTION -> Icon(
                        imageVector = Icons.Rounded.ErrorOutline,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.error,
                        modifier = Modifier.size(FinanceTheme.sizes.iconMedium)
                    )
                }
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = stringResource(content.title),
                        style = FinanceTheme.typography.titleMedium,
                        fontWeight = FontWeight.Medium,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    val sub = when (content.state) {
                        AttentionState.OK -> detail ?: stringResource(content.doneText)
                        AttentionState.IN_PROGRESS -> stringResource(R.string.attn_in_progress)
                        AttentionState.NEEDS_ATTENTION -> stringResource(R.string.attn_needs_attention)
                    }
                    Text(
                        text = sub,
                        style = FinanceTheme.typography.labelSmall,
                        fontWeight = FontWeight.SemiBold,
                        color = if (needs) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
            if (needs && content.reason != null) {
                Text(
                    text = detail ?: stringResource(content.reason),
                    style = FinanceTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            if (needs && content.actionLabel != null) {
                FilledButton(
                    text = stringResource(content.actionLabel),
                    onClick = onAction,
                    modifier = Modifier
                        .fillMaxWidth()
                        .defaultMinSize(minHeight = FinanceTheme.sizes.minTouchTarget)
                )
            }
        }
    }
}
