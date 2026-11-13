package com.financeautomation.presentation.screens.drawer

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
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
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
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
import com.financeautomation.domain.model.TripTag
import com.financeautomation.presentation.components.EmptyState
import com.financeautomation.presentation.components.HeaderActionButton
import com.financeautomation.presentation.theme.FinanceShapes
import com.financeautomation.presentation.theme.FinanceTheme
import com.financeautomation.presentation.viewmodel.SettingsUiState
import com.financeautomation.presentation.viewmodel.SettingsViewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PeopleTripsScreen(
    viewModel: SettingsViewModel,
    state: SettingsUiState,
    onStartTrip: () -> Unit = {},
    modifier: Modifier = Modifier
) {
    val scrollState = rememberScrollState()
    var showAddFriendSheet by remember { mutableStateOf(false) }
    var openTripName by remember { mutableStateOf<String?>(null) }
    val tripUseCase = remember { com.financeautomation.domain.usecase.TripSummaryUseCase() }
    fun summaryFor(name: String) =
        state.tripSummaries[name.trim().lowercase()] ?: tripUseCase.forTrip(name, emptyList())
    var selectedCompanionForEdit by remember { mutableStateOf<FriendCompanion?>(null) }

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
                        val openCount = state.companionOpenItemCounts[companion.name.trim().lowercase()] ?: 0
                        FriendItemRow(
                            companion = companion,
                            openItemCount = openCount,
                            onClick = { selectedCompanionForEdit = companion }
                        )
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
            if (state.tripTags.isNotEmpty()) {
                Column(
                    modifier = Modifier.fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(FinanceTheme.spacing.s)
                ) {
                    state.tripTags.forEach { trip ->
                        val isActive = trip.isActive || trip.name.equals(state.activeTripTag, ignoreCase = true)
                        TripCard(
                            tripName = trip.name,
                            isActive = isActive,
                            statusText = if (isActive) stringResource(R.string.trip_active) else "Inactive",
                            peopleCount = summaryFor(trip.name).peopleCount,
                            totalSpend = formatRupees(summaryFor(trip.name).totalPaid),
                            onOpen = { openTripName = trip.name },
                            onEndTrip = { viewModel.onToggleTripTagActive(trip.id) },
                            onReactivateTrip = { viewModel.onToggleTripTagActive(trip.id) }
                        )
                    }
                }
            } else if (state.activeTripTag.isNotBlank()) {
                TripCard(
                    tripName = state.activeTripTag,
                    isActive = true,
                    statusText = stringResource(R.string.trip_active),
                    peopleCount = summaryFor(state.activeTripTag).peopleCount,
                    totalSpend = formatRupees(summaryFor(state.activeTripTag).totalPaid),
                    onOpen = { openTripName = state.activeTripTag },
                    onEndTrip = { viewModel.onActiveTripTagChanged("") },
                    onReactivateTrip = {}
                )
            }

            // Start new trip button
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
                            text = stringResource(R.string.trips_isolate_hint),
                            style = FinanceTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
        }
    }

    openTripName?.let { name ->
        TripDetailSheet(summary = summaryFor(name), onDismiss = { openTripName = null })
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

    selectedCompanionForEdit?.let { comp ->
        val openCount = state.companionOpenItemCounts[comp.name.trim().lowercase()] ?: 0
        FriendDetailSheet(
            companion = comp,
            openItemCount = openCount,
            onDismiss = { selectedCompanionForEdit = null },
            onRename = { newName ->
                viewModel.onRenameCompanion(comp.id, newName)
                selectedCompanionForEdit = null
            },
            onRemove = {
                viewModel.onRemoveCompanion(comp.id)
                selectedCompanionForEdit = null
            }
        )
    }
}

@Composable
private fun FriendItemRow(
    companion: FriendCompanion,
    openItemCount: Int = 0,
    onClick: () -> Unit = {},
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
        openItemCount > 0 -> "$openItemCount ${if (openItemCount == 1) "open item" else "open items"}"
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
        modifier = modifier
            .fillMaxWidth()
            .clip(FinanceShapes.large)
            .clickable(onClick = onClick)
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
    isActive: Boolean = true,
    statusText: String,
    peopleCount: Int,
    totalSpend: String,
    onOpen: () -> Unit = {},
    onEndTrip: () -> Unit = {},
    onReactivateTrip: () -> Unit = {},
    modifier: Modifier = Modifier
) {
    Surface(
        color = FinanceTheme.colors.surfaceContainerLowest,
        shape = FinanceShapes.large,
        shadowElevation = FinanceTheme.sizes.shadowElevationSmall,
        modifier = modifier.fillMaxWidth().clickable(onClick = onOpen)
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
                            text = if (isActive) "Active travel ledger" else "Past travel ledger",
                            style = FinanceTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }

                Surface(
                    color = if (isActive) MaterialTheme.colorScheme.primaryContainer else FinanceTheme.colors.surfaceContainerHigh,
                    shape = FinanceShapes.pill
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = FinanceTheme.spacing.s, vertical = FinanceTheme.spacing.xxs),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(FinanceTheme.spacing.xs)
                    ) {
                        if (isActive) {
                            Icon(
                                imageVector = Icons.Rounded.CheckCircle,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.size(FinanceTheme.sizes.iconSmall)
                            )
                        }
                        Text(
                            text = statusText,
                            style = FinanceTheme.typography.labelSmall,
                            color = if (isActive) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                if (isActive) {
                    OutlinedButton(
                        onClick = onEndTrip,
                        shape = FinanceTheme.shapes.small
                    ) {
                        Text(stringResource(R.string.trips_end_trip), style = FinanceTheme.typography.labelMedium)
                    }
                } else {
                    Button(
                        onClick = onReactivateTrip,
                        shape = FinanceTheme.shapes.small
                    ) {
                        Text(stringResource(R.string.trips_reactivate), style = FinanceTheme.typography.labelMedium)
                    }
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

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun FriendDetailSheet(
    companion: FriendCompanion,
    openItemCount: Int,
    onDismiss: () -> Unit,
    onRename: (String) -> Unit,
    onRemove: () -> Unit
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    var renameInput by remember { mutableStateOf(companion.name) }
    var showRemoveConfirm by remember { mutableStateOf(false) }

    val hasOpenBalance = kotlin.math.abs(companion.balance) > 0.001 || openItemCount > 0

    if (showRemoveConfirm) {
        AlertDialog(
            onDismissRequest = { showRemoveConfirm = false },
            title = {
                Text(
                    text = "Remove ${companion.name}?",
                    style = FinanceTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold
                )
            },
            text = {
                Text(
                    text = "Are you sure you want to remove ${companion.name} from friends? Their past transactions will remain in your ledger.",
                    style = FinanceTheme.typography.bodyMedium
                )
            },
            confirmButton = {
                Button(
                    onClick = {
                        showRemoveConfirm = false
                        onRemove()
                    }
                ) {
                    Text(stringResource(R.string.friends_remove))
                }
            },
            dismissButton = {
                OutlinedButton(onClick = { showRemoveConfirm = false }) {
                    Text(stringResource(R.string.action_cancel))
                }
            }
        )
    }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = MaterialTheme.colorScheme.surface
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = FinanceTheme.spacing.l, vertical = FinanceTheme.spacing.m),
            verticalArrangement = Arrangement.spacedBy(FinanceTheme.spacing.m)
        ) {
            Text(
                text = stringResource(R.string.friends_details_title),
                style = FinanceTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onSurface
            )

            // Current balance info
            val balanceStr = formatRupees(kotlin.math.abs(companion.balance), includeDecimals = true)
            val balanceText = when {
                companion.balance > 0.001 -> "Owes you $balanceStr"
                companion.balance < -0.001 -> "You owe $balanceStr"
                else -> "Settled up (₹0.00)"
            }
            Text(
                text = balanceText,
                style = FinanceTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            // Rename section
            Column(verticalArrangement = Arrangement.spacedBy(FinanceTheme.spacing.xs)) {
                Text(
                    text = stringResource(R.string.friends_field_name),
                    style = FinanceTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                OutlinedTextField(
                    value = renameInput,
                    onValueChange = { renameInput = it },
                    singleLine = true,
                    shape = FinanceShapes.card,
                    modifier = Modifier.fillMaxWidth()
                )
            }

            Button(
                onClick = {
                    if (renameInput.isNotBlank()) {
                        onRename(renameInput.trim())
                    }
                },
                enabled = renameInput.isNotBlank() && renameInput.trim() != companion.name,
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(stringResource(R.string.friends_save_name))
            }

            androidx.compose.material3.HorizontalDivider()

            // Remove section
            if (hasOpenBalance) {
                Text(
                    text = "Cannot remove ${companion.name} while they have an open balance ($balanceText). Settle all balances first.",
                    style = FinanceTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error
                )
                OutlinedButton(
                    onClick = {},
                    enabled = false,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(stringResource(R.string.friends_remove_friend))
                }
            } else {
                OutlinedButton(
                    onClick = { showRemoveConfirm = true },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(stringResource(R.string.friends_remove_friend), color = MaterialTheme.colorScheme.error)
                }
            }

            Spacer(modifier = Modifier.height(FinanceTheme.spacing.m))
        }
    }
}
