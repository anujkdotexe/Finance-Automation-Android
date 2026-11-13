package com.financeautomation.presentation.screens.drawer

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
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
 * Net expenditure of one trip: what you paid, what others still owe you, what came back, and what the trip really
 * cost you, followed by every transaction on the trip.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TripDetailSheet(summary: TripSummary, onDismiss: () -> Unit) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val dateFormat = remember {
        SimpleDateFormat("dd MMM", Locale.getDefault()).apply { timeZone = TimeZone.getTimeZone("Asia/Kolkata") }
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
            Text(
                text = summary.name,
                style = FinanceTheme.typography.titleLarge,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurface
            )
            Text(
                text = stringResource(R.string.trip_detail_people_and_count, summary.peopleCount, summary.transactionCount),
                style = FinanceTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

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
                }
            }

            Text(
                text = stringResource(R.string.trip_detail_transactions),
                style = FinanceTheme.typography.titleMedium,
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
            color = MaterialTheme.colorScheme.onSurface
        )
        Text(
            text = value,
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
                    StatusChip(status = "Voided")
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
