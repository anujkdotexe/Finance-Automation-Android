package com.financeautomation.presentation.screens.drawer

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.financeautomation.R
import com.financeautomation.core.model.TransactionStatus
import com.financeautomation.core.utils.formatRupees
import com.financeautomation.core.utils.stripVoidPrefix
import com.financeautomation.domain.model.Transaction
import com.financeautomation.domain.usecase.TripSummary
import com.financeautomation.presentation.components.StatusChip
import com.financeautomation.presentation.theme.FinanceShapes
import com.financeautomation.presentation.theme.FinanceTheme
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/**
 * Net expenditure and full insights of one trip: what you paid, what others still owe you, what came back,
 * what the trip really cost you, insights (per day, biggest expense, breakdowns), and trip actions (End, Reactivate, Delete).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TripDetailSheet(
    summary: TripSummary,
    isActive: Boolean = false,
    onEndTrip: () -> Unit = {},
    onReactivateTrip: () -> Unit = {},
    onDeleteTrip: (String) -> Unit = {},
    onDismiss: () -> Unit
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    var showDeleteConfirm by rememberSaveable { mutableStateOf(false) }

    val dateFormat = remember {
        SimpleDateFormat("dd MMM", Locale.getDefault()).apply { timeZone = TimeZone.getTimeZone("Asia/Kolkata") }
    }

    if (showDeleteConfirm) {
        AlertDialog(
            onDismissRequest = { showDeleteConfirm = false },
            title = {
                Text(
                    text = stringResource(R.string.trip_delete_title, summary.name),
                    style = FinanceTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold
                )
            },
            text = {
                Text(
                    text = stringResource(R.string.trip_delete_body),
                    style = FinanceTheme.typography.bodyMedium
                )
            },
            dismissButton = {
                OutlinedButton(
                    onClick = { showDeleteConfirm = false },
                    modifier = Modifier.defaultMinSize(minHeight = FinanceTheme.sizes.minTouchTarget)
                ) {
                    Text(stringResource(R.string.action_cancel))
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        showDeleteConfirm = false
                        onDeleteTrip(summary.name)
                        onDismiss()
                    },
                    modifier = Modifier.defaultMinSize(minHeight = FinanceTheme.sizes.minTouchTarget)
                ) {
                    Text(stringResource(R.string.trip_delete_action))
                }
            }
        )
    }

    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = FinanceTheme.spacing.l)
                .padding(bottom = FinanceTheme.spacing.xl),
            verticalArrangement = Arrangement.spacedBy(FinanceTheme.spacing.m)
        ) {
            // Header
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = summary.name,
                        style = FinanceTheme.typography.titleLarge,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    if (summary.dateRange.isNotBlank()) {
                        Text(
                            text = summary.dateRange,
                            style = FinanceTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
                Surface(
                    color = if (isActive) MaterialTheme.colorScheme.primaryContainer else FinanceTheme.colors.surfaceContainerHigh,
                    shape = FinanceShapes.pill
                ) {
                    Text(
                        text = if (isActive) stringResource(R.string.trip_active) else stringResource(R.string.trip_ended),
                        style = FinanceTheme.typography.labelSmall,
                        color = if (isActive) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(horizontal = FinanceTheme.spacing.s, vertical = FinanceTheme.spacing.xxs)
                    )
                }
            }

            Text(
                text = stringResource(R.string.trip_detail_people_and_count, summary.peopleCount, summary.transactionCount),
                style = FinanceTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            // Net Expenditure Card
            Surface(
                color = FinanceTheme.colors.surfaceContainerLow,
                shape = FinanceShapes.card,
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(
                    modifier = Modifier.padding(FinanceTheme.spacing.m),
                    verticalArrangement = Arrangement.spacedBy(FinanceTheme.spacing.s)
                ) {
                    NetRow(stringResource(R.string.trip_net_paid), formatRupees(summary.totalPaid))
                    NetRow(stringResource(R.string.trip_net_given), formatRupees(summary.givenPending))
                    NetRow(stringResource(R.string.trip_net_returned), formatRupees(summary.returned))
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                    NetRow(stringResource(R.string.trip_net_expenditure), formatRupees(summary.netExpenditure), emphasised = true)

                    if (summary.daysCount > 0 && summary.averagePerDay > 0.0) {
                        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                        NetRow(stringResource(R.string.trip_insight_per_day), formatRupees(summary.averagePerDay, includeDecimals = true))
                    }
                    if (summary.biggestSingleExpense != null) {
                        NetRow(stringResource(R.string.trip_insight_biggest), "${summary.biggestSingleExpense.merchant} (${formatRupees(summary.biggestSingleExpense.totalAmount)})")
                    }
                }
            }

            // Category Breakdown Section
            if (summary.categoryBreakdown.isNotEmpty()) {
                Text(
                    text = stringResource(R.string.trip_by_category),
                    style = FinanceTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurface
                )
                Surface(
                    color = FinanceTheme.colors.surfaceContainerLow,
                    shape = FinanceShapes.card,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(
                        modifier = Modifier.padding(FinanceTheme.spacing.m),
                        verticalArrangement = Arrangement.spacedBy(FinanceTheme.spacing.xs)
                    ) {
                        summary.categoryBreakdown.forEach { cat ->
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(
                                    text = "${cat.category} (${String.format(Locale.US, "%.0f%%", cat.percentage)})",
                                    style = FinanceTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onSurface,
                                    maxLines = 2,
                                    overflow = TextOverflow.Ellipsis,
                                    modifier = Modifier.weight(1f)
                                )
                                Text(
                                    text = formatRupees(cat.amount),
                                    modifier = Modifier.padding(start = FinanceTheme.spacing.s),
                                    style = FinanceTheme.typography.bodyMedium,
                                    fontWeight = FontWeight.Medium,
                                    color = MaterialTheme.colorScheme.onSurface
                                )
                            }
                        }
                    }
                }
            }

            // Friend Breakdown Section
            if (summary.perFriendBreakdown.isNotEmpty()) {
                Text(
                    text = stringResource(R.string.trip_by_person),
                    style = FinanceTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurface
                )
                Surface(
                    color = FinanceTheme.colors.surfaceContainerLow,
                    shape = FinanceShapes.card,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(
                        modifier = Modifier.padding(FinanceTheme.spacing.m),
                        verticalArrangement = Arrangement.spacedBy(FinanceTheme.spacing.xs)
                    ) {
                        summary.perFriendBreakdown.forEach { friend ->
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(
                                    text = friend.name,
                                    style = FinanceTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onSurface,
                                    maxLines = 2,
                                    overflow = TextOverflow.Ellipsis,
                                    modifier = Modifier.weight(1f)
                                )
                                val statusDesc = when {
                                    friend.owedToMe > 0.001 -> stringResource(R.string.trip_friend_owes, formatRupees(friend.owedToMe))
                                    friend.repaid > 0.001 -> stringResource(R.string.trip_friend_repaid, formatRupees(friend.repaid))
                                    else -> stringResource(R.string.trip_friend_settled)
                                }
                                Text(
                                    text = statusDesc,
                                    modifier = Modifier.padding(start = FinanceTheme.spacing.s),
                                    style = FinanceTheme.typography.bodySmall,
                                    color = if (friend.owedToMe > 0.001) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                    }
                }
            }

            // Action Buttons
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(FinanceTheme.spacing.s),
                verticalAlignment = Alignment.CenterVertically
            ) {
                if (isActive) {
                    OutlinedButton(
                        onClick = {
                            onEndTrip()
                            onDismiss()
                        },
                        modifier = Modifier
                            .weight(1f)
                            .defaultMinSize(minHeight = FinanceTheme.sizes.minTouchTarget)
                    ) {
                        Text(stringResource(R.string.trips_end_trip))
                    }
                    OutlinedButton(
                        onClick = {},
                        enabled = false,
                        modifier = Modifier
                            .weight(1f)
                            .defaultMinSize(minHeight = FinanceTheme.sizes.minTouchTarget)
                    ) {
                        Text(stringResource(R.string.trip_delete_active_blocked))
                    }
                } else {
                    Button(
                        onClick = {
                            onReactivateTrip()
                            onDismiss()
                        },
                        modifier = Modifier
                            .weight(1f)
                            .defaultMinSize(minHeight = FinanceTheme.sizes.minTouchTarget)
                    ) {
                        Text(stringResource(R.string.trip_reactivate))
                    }
                    OutlinedButton(
                        onClick = { showDeleteConfirm = true },
                        modifier = Modifier
                            .weight(1f)
                            .defaultMinSize(minHeight = FinanceTheme.sizes.minTouchTarget)
                    ) {
                        Text(stringResource(R.string.trip_delete_action), color = MaterialTheme.colorScheme.error)
                    }
                }
            }

            Text(
                text = stringResource(R.string.trip_detail_transactions),
                style = FinanceTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurface
            )
            if (summary.transactions.isEmpty()) {
                Text(
                    text = stringResource(R.string.trip_detail_empty),
                    style = FinanceTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            } else {
                summary.transactions.forEach { TripTransactionRow(it, dateFormat) }
            }
            Spacer(modifier = Modifier.height(FinanceTheme.spacing.s))
        }
    }
}

@Composable
private fun NetRow(label: String, value: String, emphasised: Boolean = false) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = label,
            style = if (emphasised) FinanceTheme.typography.titleMedium else FinanceTheme.typography.bodyMedium,
            fontWeight = if (emphasised) FontWeight.SemiBold else FontWeight.Normal,
            color = MaterialTheme.colorScheme.onSurface,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f)
        )
        Text(
            text = value,
            modifier = Modifier.padding(start = FinanceTheme.spacing.s),
            style = FinanceTheme.typography.numeric,
            fontWeight = if (emphasised) FontWeight.Bold else FontWeight.Medium,
            color = if (emphasised) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface
        )
    }
}

@Composable
private fun TripTransactionRow(transaction: Transaction, dateFormat: SimpleDateFormat) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        val isVoid = transaction.status == TransactionStatus.VOID
        val cleanMerchant = stripVoidPrefix(transaction.merchant.ifBlank { transaction.category })

        Column(modifier = Modifier.weight(1f)) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(FinanceTheme.spacing.xs)
            ) {
                Text(
                    text = cleanMerchant,
                    style = if (isVoid) {
                        FinanceTheme.typography.bodyMedium.copy(textDecoration = TextDecoration.LineThrough)
                    } else {
                        FinanceTheme.typography.bodyMedium
                    },
                    fontWeight = FontWeight.Medium,
                    color = if (isVoid) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false)
                )

                if (isVoid) {
                    StatusChip(status = "VOID")
                }
            }
            Text(
                text = stringResource(
                    R.string.trip_detail_row_meta,
                    dateFormat.format(Date(transaction.timestamp)),
                    transaction.category
                ),
                style = FinanceTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
        Column(horizontalAlignment = Alignment.End) {
            Text(
                text = formatRupees(transaction.totalAmount),
                style = FinanceTheme.typography.numeric,
                color = if (isVoid) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onSurface
            )
            Text(
                text = stringResource(R.string.trip_detail_your_share, formatRupees(transaction.myShare)),
                style = FinanceTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}
