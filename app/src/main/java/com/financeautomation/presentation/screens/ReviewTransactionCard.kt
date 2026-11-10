package com.financeautomation.presentation.screens

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.DeleteOutline
import androidx.compose.material.icons.rounded.DirectionsCar
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.material.icons.rounded.LocalGroceryStore
import androidx.compose.material.icons.rounded.Payments
import androidx.compose.material.icons.rounded.Receipt
import androidx.compose.material.icons.rounded.Restaurant
import androidx.compose.material.icons.rounded.Savings
import androidx.compose.material.icons.rounded.ShoppingBag
import androidx.compose.material.icons.rounded.WarningAmber
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import com.financeautomation.R
import com.financeautomation.core.constants.CategoryConstants
import com.financeautomation.domain.model.Transaction
import com.financeautomation.presentation.components.AmountText
import com.financeautomation.presentation.components.AmountTone
import com.financeautomation.presentation.components.StatusChip
import com.financeautomation.presentation.theme.FinanceTheme
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Interactive card displaying a pending transaction for quick review and 1-tap confirmation.
 */
@Composable
fun ReviewTransactionCard(
    transaction: Transaction,
    onConfirm: () -> Unit,
    onVoid: () -> Unit,
    onEdit: () -> Unit,
    modifier: Modifier = Modifier
) {
    Card(
        colors = CardDefaults.cardColors(containerColor = FinanceTheme.colors.surface),
        shape = FinanceTheme.shapes.large,
        border = BorderStroke(FinanceTheme.sizes.strokeThin, FinanceTheme.colors.outline),
        elevation = CardDefaults.cardElevation(defaultElevation = FinanceTheme.sizes.strokeThin),
        modifier = modifier.fillMaxWidth()
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(FinanceTheme.spacing.l)
        ) {
            // Duplicate Warning Banner
            if (transaction.isPossibleDuplicate) {
                Surface(
                    color = MaterialTheme.colorScheme.errorContainer,
                    shape = FinanceTheme.shapes.card,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(bottom = FinanceTheme.spacing.m)
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = FinanceTheme.spacing.m, vertical = FinanceTheme.spacing.s),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            imageVector = Icons.Rounded.WarningAmber,
                            contentDescription = stringResource(R.string.cd_warning),
                            tint = MaterialTheme.colorScheme.error,
                            modifier = Modifier.size(FinanceTheme.sizes.iconSmall)
                        )
                        Spacer(modifier = Modifier.width(FinanceTheme.spacing.s))
                        Text(
                            text = stringResource(R.string.review_duplicate_warning),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onErrorContainer,
                            fontWeight = FontWeight.Medium
                        )
                    }
                }
            }

            // Top Row: Category Avatar, Merchant Info, and Amount
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.weight(1f)
                ) {
                    Box(
                        modifier = Modifier
                            .size(FinanceTheme.sizes.avatarMedium)
                            .background(
                                color = FinanceTheme.colors.primaryContainer,
                                shape = CircleShape
                            ),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = resolveCategoryIcon(transaction.category),
                            contentDescription = transaction.category,
                            tint = FinanceTheme.colors.primary,
                            modifier = Modifier.size(FinanceTheme.sizes.icon22)
                        )
                    }

                    Spacer(modifier = Modifier.width(FinanceTheme.spacing.m))

                    Column {
                        Text(
                            text = transaction.merchant.ifBlank { stringResource(R.string.review_unknown_merchant) },
                            style = FinanceTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold,
                            color = FinanceTheme.colors.onSurface
                        )
                        Text(
                            text = formatTimestamp(transaction.timestamp),
                            style = FinanceTheme.typography.bodySmall,
                            color = FinanceTheme.colors.onSurfaceVariant
                        )
                    }
                }

                AmountText(
                    amount = transaction.totalAmount,
                    tone = AmountTone.MONEY_OUT,
                    style = FinanceTheme.typography.amountLarge
                )
            }

            Spacer(modifier = Modifier.height(FinanceTheme.spacing.m))

            // Metadata Row: Category Pill, Status Badge, and Split Details
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(FinanceTheme.spacing.s),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Surface(
                    color = FinanceTheme.colors.surfaceVariant,
                    shape = FinanceTheme.shapes.small
                ) {
                    Text(
                        text = transaction.category.ifBlank { CategoryConstants.CATEGORY_FOOD_AND_DRINK },
                        style = FinanceTheme.typography.labelSmall,
                        color = FinanceTheme.colors.onSurfaceVariant,
                        fontWeight = FontWeight.Medium,
                        modifier = Modifier.padding(horizontal = FinanceTheme.spacing.s, vertical = FinanceTheme.spacing.xs)
                    )
                }

                StatusChip(status = transaction.status)

                if (transaction.companions.isNotBlank() && transaction.companions != "Personal") {
                    Text(
                        text = stringResource(R.string.split_with_companions, transaction.companions),
                        style = FinanceTheme.typography.bodySmall,
                        color = FinanceTheme.colors.onSurfaceVariant,
                        maxLines = 1
                    )
                }
            }

            Spacer(modifier = Modifier.height(FinanceTheme.spacing.l))

            // Action Buttons: 1-Tap Confirm, Edit/Split, Void
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(FinanceTheme.spacing.s),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Button(
                    onClick = onConfirm,
                    shape = FinanceTheme.shapes.card,
                    colors = ButtonDefaults.buttonColors(
                        containerColor = FinanceTheme.colors.primary,
                        contentColor = MaterialTheme.colorScheme.onPrimary
                    ),
                    modifier = Modifier.weight(1.2f)
                ) {
                    Icon(
                        imageVector = Icons.Rounded.Check,
                        contentDescription = null,
                        modifier = Modifier.size(FinanceTheme.sizes.icon18)
                    )
                    Spacer(modifier = Modifier.width(FinanceTheme.sizes.badgeOffset))
                    Text(
                        text = stringResource(R.string.action_confirm),
                        fontWeight = FontWeight.SemiBold
                    )
                }

                OutlinedButton(
                    onClick = onEdit,
                    shape = FinanceTheme.shapes.card,
                    border = BorderStroke(FinanceTheme.sizes.strokeThin, FinanceTheme.colors.outline),
                    modifier = Modifier.weight(1f)
                ) {
                    Icon(
                        imageVector = Icons.Rounded.Edit,
                        contentDescription = null,
                        modifier = Modifier.size(FinanceTheme.sizes.iconSmall)
                    )
                    Spacer(modifier = Modifier.width(FinanceTheme.spacing.xs))
                    Text(
                        text = stringResource(R.string.action_edit),
                        fontWeight = FontWeight.Medium
                    )
                }

                TextButton(
                    onClick = onVoid,
                    colors = ButtonDefaults.textButtonColors(contentColor = FinanceTheme.colors.error)
                ) {
                    Icon(
                        imageVector = Icons.Rounded.DeleteOutline,
                        contentDescription = stringResource(R.string.action_void),
                        modifier = Modifier.size(FinanceTheme.sizes.icon18)
                    )
                }
            }
        }
    }
}

/**
 * Empty inbox illustration and prompt with clean icon and friendly fintech typography.
 */
@Composable
fun EmptyInboxState(modifier: Modifier = Modifier) {
    Column(
        modifier = modifier.padding(FinanceTheme.spacing.xxl),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Box(
            modifier = Modifier
                .size(FinanceTheme.sizes.avatarLarge)
                .background(
                    color = FinanceTheme.colors.primaryContainer,
                    shape = CircleShape
                ),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                imageVector = Icons.Rounded.CheckCircle,
                contentDescription = stringResource(R.string.review_empty_title),
                tint = FinanceTheme.colors.primary,
                modifier = Modifier.size(FinanceTheme.sizes.avatar38)
            )
        }
        Spacer(modifier = Modifier.height(FinanceTheme.spacing.l))
        Text(
            text = stringResource(R.string.review_empty_title),
            style = FinanceTheme.typography.titleLarge,
            fontWeight = FontWeight.Bold,
            color = FinanceTheme.colors.onSurface
        )
        Spacer(modifier = Modifier.height(FinanceTheme.spacing.s))
        Text(
            text = stringResource(R.string.review_empty_desc),
            style = FinanceTheme.typography.bodyMedium,
            color = FinanceTheme.colors.onSurfaceVariant,
            textAlign = androidx.compose.ui.text.style.TextAlign.Center
        )
    }
}

private fun resolveCategoryIcon(category: String): ImageVector {
    return when (category.lowercase()) {
        "food & drink", "dining", "food" -> Icons.Rounded.Restaurant
        "commute", "travel", "cab", "uber", "fuel" -> Icons.Rounded.DirectionsCar
        "shopping", "clothing", "electronics" -> Icons.Rounded.ShoppingBag
        "groceries", "supermarket" -> Icons.Rounded.LocalGroceryStore
        "utilities", "recharge", "electricity", "bills" -> Icons.Rounded.Receipt
        "savings", "investment", "sip", "stocks" -> Icons.Rounded.Savings
        else -> Icons.Rounded.Payments
    }
}

private fun formatTimestamp(timestamp: Long): String {
    if (timestamp <= 0L) return ""
    val formatter = SimpleDateFormat("dd MMM, hh:mm a", Locale.getDefault())
    return formatter.format(Date(timestamp))
}
