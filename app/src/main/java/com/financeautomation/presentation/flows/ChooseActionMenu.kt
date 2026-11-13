package com.financeautomation.presentation.flows

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.CallReceived
import androidx.compose.material.icons.automirrored.rounded.ReceiptLong
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Flight
import androidx.compose.material.icons.rounded.Handshake
import androidx.compose.material.icons.rounded.Payment
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import com.financeautomation.R
import com.financeautomation.presentation.theme.FinanceTheme

/**
 * Choose Action launcher menu (Phase 5.6).
 * HIG-styled action picker presenting the five core financial logging flows.
 */
@Composable
fun ChooseActionMenu(
    onSelectExpense: () -> Unit,
    onSelectIncome: () -> Unit,
    onSelectFriendPaid: () -> Unit,
    onSelectRepaid: () -> Unit,
    onSelectTripMode: () -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier
) {
    val scrollState = rememberScrollState()

    Column(
        modifier = modifier
            .fillMaxWidth()
            .verticalScroll(scrollState),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        // 5 Flow Action Tiles
        ActionMenuTile(
            title = stringResource(R.string.action_menu_expense_title),
            subtitle = stringResource(R.string.action_menu_expense_sub),
            icon = Icons.AutoMirrored.Rounded.ReceiptLong,
            onClick = onSelectExpense
        )

        Spacer(modifier = Modifier.height(FinanceTheme.spacing.s))

        ActionMenuTile(
            title = stringResource(R.string.action_menu_income_title),
            subtitle = stringResource(R.string.action_menu_income_sub),
            icon = Icons.AutoMirrored.Rounded.CallReceived,
            onClick = onSelectIncome
        )

        Spacer(modifier = Modifier.height(FinanceTheme.spacing.s))

        ActionMenuTile(
            title = stringResource(R.string.action_menu_friend_paid_title),
            subtitle = stringResource(R.string.action_menu_friend_paid_sub),
            icon = Icons.Rounded.Handshake,
            onClick = onSelectFriendPaid
        )

        Spacer(modifier = Modifier.height(FinanceTheme.spacing.s))

        ActionMenuTile(
            title = stringResource(R.string.action_menu_repaid_friend_title),
            subtitle = stringResource(R.string.action_menu_repaid_friend_sub),
            icon = Icons.Rounded.Payment,
            onClick = onSelectRepaid
        )

        Spacer(modifier = Modifier.height(FinanceTheme.spacing.s))

        ActionMenuTile(
            title = stringResource(R.string.action_menu_trip_mode_title),
            subtitle = stringResource(R.string.action_menu_trip_mode_sub),
            icon = Icons.Rounded.Flight,
            onClick = onSelectTripMode
        )

        Spacer(modifier = Modifier.height(FinanceTheme.spacing.s))
    }
}

@Composable
private fun ActionMenuTile(
    title: String,
    subtitle: String,
    icon: ImageVector,
    onClick: () -> Unit
) {
    Surface(
        shape = FinanceTheme.shapes.large,
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f),
        border = BorderStroke(FinanceTheme.sizes.strokeThin, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f)),
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onClick() }
    ) {
        Row(
            modifier = Modifier.padding(horizontal = FinanceTheme.spacing.l, vertical = FinanceTheme.spacing.m),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(FinanceTheme.spacing.m)
        ) {
            Surface(
                shape = FinanceTheme.shapes.medium,
                color = FinanceTheme.colors.primaryContainer,
                modifier = Modifier.size(FinanceTheme.sizes.avatar38)
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(
                        imageVector = icon,
                        contentDescription = null,
                        tint = FinanceTheme.colors.primary,
                        modifier = Modifier.size(FinanceTheme.sizes.iconMedium)
                    )
                }
            }

            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = title,
                    style = FinanceTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                    color = FinanceTheme.colors.onSurface
                )
                Text(
                    text = subtitle,
                    style = FinanceTheme.typography.bodySmall,
                    color = FinanceTheme.colors.onSurfaceVariant
                )
            }
        }
    }
}
