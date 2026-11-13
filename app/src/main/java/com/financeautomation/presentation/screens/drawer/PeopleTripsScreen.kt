package com.financeautomation.presentation.screens.drawer

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.BeachAccess
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.FlightTakeoff
import androidx.compose.material.icons.rounded.Group
import androidx.compose.material.icons.rounded.PersonAdd
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import com.financeautomation.R
import com.financeautomation.core.utils.formatRupees
import com.financeautomation.domain.model.Companion as FriendCompanion
import com.financeautomation.presentation.components.EmptyState
import com.financeautomation.presentation.components.HeaderActionButton
import com.financeautomation.presentation.theme.FinanceShapes
import com.financeautomation.presentation.theme.FinanceTheme
import com.financeautomation.presentation.viewmodel.SettingsUiState
import com.financeautomation.presentation.viewmodel.SettingsViewModel

@Composable
fun PeopleTripsScreen(
    viewModel: SettingsViewModel,
    state: SettingsUiState,
    onStartTrip: () -> Unit = {},
    modifier: Modifier = Modifier
) {
    val scrollState = rememberScrollState()
    var showAddFriendSheet by remember { mutableStateOf(false) }

    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(scrollState)
            .padding(horizontal = FinanceTheme.spacing.l, vertical = FinanceTheme.spacing.m),
        verticalArrangement = Arrangement.spacedBy(FinanceTheme.spacing.xl)
    ) {
        // Top Utility Header
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.End,
            verticalAlignment = Alignment.CenterVertically
        ) {
            HeaderActionButton(
                text = stringResource(R.string.people_add_friend),
                onClick = { showAddFriendSheet = true }
            )
        }

        // SECTION 1: FRIENDS
        Column(
            modifier = Modifier.fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(FinanceTheme.spacing.m)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(FinanceTheme.spacing.s)
                    ) {
                        Text(
                            text = stringResource(R.string.people_section_friends),
                            style = FinanceTheme.typography.titleLarge,
                            fontWeight = FontWeight.SemiBold,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                        Surface(
                            color = FinanceTheme.colors.surfaceContainerHigh,
                            shape = FinanceShapes.pill
                        ) {
                            Text(
                                text = "${state.companions.size} members",
                                style = FinanceTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(horizontal = FinanceTheme.spacing.s, vertical = FinanceTheme.spacing.xxs)
                            )
                        }
                    }
                    Text(
                        text = stringResource(R.string.people_friends_desc),
                        style = FinanceTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            if (state.companions.isEmpty()) {
                EmptyState(
                    message = stringResource(R.string.people_empty_title)
                )
            } else {
                Column(
                    modifier = Modifier.fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(FinanceTheme.spacing.xs)
                ) {
                    state.companions.forEach { companion ->
                        FriendItemRow(companion = companion)
                    }
                }
            }
        }

        // SECTION 2: TRIPS
        Column(
            modifier = Modifier.fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(FinanceTheme.spacing.m)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(FinanceTheme.spacing.s)
                    ) {
                        Text(
                            text = stringResource(R.string.people_section_trips),
                            style = FinanceTheme.typography.titleLarge,
                            fontWeight = FontWeight.SemiBold,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                        Surface(
                            color = FinanceTheme.colors.surfaceContainerHigh,
                            shape = FinanceShapes.pill
                        ) {
                            Text(
                                text = if (state.activeTripTag.isNotBlank()) stringResource(R.string.trip_active) else "0 active",
                                style = FinanceTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(horizontal = FinanceTheme.spacing.s, vertical = FinanceTheme.spacing.xxs)
                            )
                        }
                    }
                    Text(
                        text = stringResource(R.string.people_trips_desc),
                        style = FinanceTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            // Trip Cards
            if (state.activeTripTag.isNotBlank()) {
                TripCard(
                    tripName = state.activeTripTag,
                    statusText = stringResource(R.string.trip_active),
                    peopleCount = 4,
                    totalSpend = "₹18,400.00"
                )
            } else {
                Surface(
                    color = FinanceTheme.colors.surfaceContainerLowest,
                    shape = FinanceShapes.large,
                    shadowElevation = FinanceTheme.sizes.shadowElevationSmall,
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(FinanceShapes.large)
                        .clickable(onClick = onStartTrip)
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(FinanceTheme.spacing.m),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(FinanceTheme.spacing.m)
                    ) {
                        Surface(
                            color = MaterialTheme.colorScheme.secondaryContainer,
                            shape = RoundedCornerShape(FinanceTheme.spacing.s),
                            modifier = Modifier.size(FinanceTheme.sizes.buttonHeightMedium)
                        ) {
                            Box(contentAlignment = Alignment.Center) {
                                Icon(
                                    imageVector = Icons.Rounded.FlightTakeoff,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.onSecondaryContainer,
                                    modifier = Modifier.size(FinanceTheme.sizes.iconMedium)
                                )
                            }
                        }
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = stringResource(R.string.btn_start_new_trip),
                                style = FinanceTheme.typography.titleMedium,
                                fontWeight = FontWeight.SemiBold,
                                color = MaterialTheme.colorScheme.onSurface
                            )
                            Text(
                                text = "Isolate living burn with a dedicated trip ledger",
                                style = FinanceTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }
            }
        }
    }

    if (showAddFriendSheet) {
        AddFriendSheet(
            onDismiss = { showAddFriendSheet = false },
            onSave = { name ->
                viewModel.onAddCompanion(name)
                showAddFriendSheet = false
            }
        )
    }
}

@Composable
private fun FriendItemRow(
    companion: FriendCompanion,
    modifier: Modifier = Modifier
) {
    val initial = companion.name.firstOrNull()?.uppercase() ?: "F"

    val balance = companion.balance
    val isPositive = balance > 0.001
    val isNegative = balance < -0.001

    val amountText = when {
        isPositive -> formatRupees(balance, includeDecimals = true)
        isNegative -> formatRupees(kotlin.math.abs(balance), includeDecimals = true)
        else -> "₹0.00"
    }
    val statusText = when {
        isPositive -> stringResource(R.string.people_owes_you)
        isNegative -> stringResource(R.string.people_you_owe)
        else -> stringResource(R.string.people_settled)
    }
    val subtitleText = when {
        isPositive -> stringResource(R.string.people_owes_you)
        isNegative -> stringResource(R.string.people_you_owe)
        else -> stringResource(R.string.people_all_caught_up)
    }
    val textColor = when {
        isPositive -> MaterialTheme.colorScheme.primary
        isNegative -> MaterialTheme.colorScheme.error
        else -> MaterialTheme.colorScheme.outline
    }

    Surface(
        color = FinanceTheme.colors.surfaceContainerLowest,
        shape = FinanceShapes.large,
        shadowElevation = FinanceTheme.sizes.shadowElevationSmall,
        modifier = modifier.fillMaxWidth()
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .defaultMinSize(minHeight = FinanceTheme.sizes.minTouchTarget)
                .padding(horizontal = FinanceTheme.spacing.m, vertical = FinanceTheme.spacing.s),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Row(
                modifier = Modifier.weight(1f),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(FinanceTheme.spacing.m)
            ) {
                Box(
                    modifier = Modifier
                        .size(FinanceTheme.sizes.buttonHeightMedium)
                        .clip(CircleShape)
                        .background(FinanceTheme.colors.surfaceContainerHigh),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = initial,
                        style = FinanceTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.primary
                    )
                }

                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = companion.name,
                        style = FinanceTheme.typography.titleMedium,
                        fontWeight = FontWeight.Medium,
                        color = MaterialTheme.colorScheme.onSurface,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Text(
                        text = subtitleText,
                        style = FinanceTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            Column(horizontalAlignment = Alignment.End) {
                Text(
                    text = amountText,
                    style = FinanceTheme.typography.kpiMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = textColor
                )
                Text(
                    text = statusText,
                    style = FinanceTheme.typography.bodySmall,
                    color = textColor
                )
            }
        }
    }
}

@Composable
private fun TripCard(
    tripName: String,
    statusText: String,
    peopleCount: Int,
    totalSpend: String,
    modifier: Modifier = Modifier
) {
    Surface(
        color = FinanceTheme.colors.surfaceContainerLowest,
        shape = FinanceShapes.large,
        shadowElevation = FinanceTheme.sizes.shadowElevationSmall,
        modifier = modifier.fillMaxWidth()
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(FinanceTheme.spacing.m),
            verticalArrangement = Arrangement.spacedBy(FinanceTheme.spacing.s)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.Top
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(FinanceTheme.spacing.m)
                ) {
                    Surface(
                        color = MaterialTheme.colorScheme.secondaryContainer,
                        shape = RoundedCornerShape(FinanceTheme.spacing.s),
                        modifier = Modifier.size(FinanceTheme.sizes.buttonHeightMedium)
                    ) {
                        Box(contentAlignment = Alignment.Center) {
                            Icon(
                                imageVector = Icons.Rounded.BeachAccess,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.onSecondaryContainer,
                                modifier = Modifier.size(FinanceTheme.sizes.iconMedium)
                            )
                        }
                    }
                    Column {
                        Text(
                            text = tripName,
                            style = FinanceTheme.typography.titleLarge,
                            fontWeight = FontWeight.SemiBold,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                        Text(
                            text = "Active travel ledger",
                            style = FinanceTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }

                Surface(
                    color = FinanceTheme.colors.surfaceContainerHigh,
                    shape = FinanceShapes.pill
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = FinanceTheme.spacing.s, vertical = FinanceTheme.spacing.xxs),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(FinanceTheme.spacing.xs)
                    ) {
                        Icon(
                            imageVector = Icons.Rounded.CheckCircle,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(FinanceTheme.sizes.iconSmall)
                        )
                        Text(
                            text = statusText,
                            style = FinanceTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(FinanceTheme.spacing.xs)
                ) {
                    Icon(
                        imageVector = Icons.Rounded.Group,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(FinanceTheme.sizes.icon18)
                    )
                    Text(
                        text = stringResource(R.string.trip_people_count, peopleCount),
                        style = FinanceTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }

                Column(horizontalAlignment = Alignment.End) {
                    Text(
                        text = stringResource(R.string.trip_total_spend_label).uppercase(),
                        style = FinanceTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Text(
                        text = totalSpend,
                        style = FinanceTheme.typography.kpiMedium,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                }
            }
        }
    }
}
