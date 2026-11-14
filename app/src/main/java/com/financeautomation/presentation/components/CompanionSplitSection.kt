package com.financeautomation.presentation.components

import androidx.compose.foundation.background
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Flight
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.draw.clip
import com.financeautomation.R
import com.financeautomation.core.model.SplitMode
import com.financeautomation.core.utils.AmountInputSanitizer
import com.financeautomation.core.utils.formatRupees
import com.financeautomation.core.utils.formatRupeesAuto
import com.financeautomation.presentation.theme.FinanceShapes
import com.financeautomation.presentation.theme.FinanceTheme
import com.financeautomation.presentation.viewmodel.QuickLogUiState

/**
 * Split panel displaying friend chips, inline friend addition, custom share input,
 * and live "My share" / "Owed to me" calculation cards.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun CompanionSplitSection(
    state: QuickLogUiState,
    onCompanionToggled: (String) -> Unit,
    onAddCompanion: (String) -> Unit,
    onCustomShareChanged: (String) -> Unit,
    onKeyboardFocusChanged: (Boolean) -> Unit,
    modifier: Modifier = Modifier
) {
    var showAddFriendInput by rememberSaveable { mutableStateOf(false) }
    var newFriendName by rememberSaveable { mutableStateOf("") }
    val focusManager = LocalFocusManager.current

    if (state.splitMode == SplitMode.JUST_ME) {
        return
    }

    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(top = FinanceTheme.spacing.s),
        verticalArrangement = Arrangement.spacedBy(FinanceTheme.spacing.s)
    ) {
        if (state.splitMode == SplitMode.CUSTOM) {
            // Numeric Input for My Share
            Text(
                text = stringResource(R.string.label_personal_share),
                style = FinanceTheme.typography.labelUppercase,
                color = FinanceTheme.colors.onSurfaceVariant
            )

            OutlinedTextField(
                value = state.customShareText,
                onValueChange = { input ->
                    if (AmountInputSanitizer.isValidExpressionInput(input)) {
                        onCustomShareChanged(input)
                    }
                },
                placeholder = { Text(text = "0.00", style = FinanceTheme.typography.bodyMedium) },
                singleLine = true,
                shape = FinanceShapes.card,
                keyboardOptions = KeyboardOptions(
                    keyboardType = KeyboardType.Text,
                    imeAction = ImeAction.Done
                ),
                keyboardActions = KeyboardActions(
                    onDone = { focusManager.clearFocus() }
                ),
                supportingText = if (state.customShareText.any { it in "+-*/" } && (state.customShare ?: 0.0) > 0.0) {
                    {
                        Text(
                            text = "= ${formatRupeesAuto(state.customShare ?: 0.0)}",
                            style = FinanceTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.primary
                        )
                    }
                } else null,
                modifier = Modifier
                    .fillMaxWidth()
                    .onFocusChanged { onKeyboardFocusChanged(it.isFocused) }
            )
        }

        // Friend Selection Chips Row
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = if (state.splitMode == SplitMode.CUSTOM) {
                    stringResource(R.string.split_with_friends)
                } else {
                    stringResource(R.string.split_participants)
                },
                style = FinanceTheme.typography.labelUppercase,
                color = FinanceTheme.colors.onSurfaceVariant
            )

            if (state.splitMode == SplitMode.SPLIT_EQUALLY && state.selectedCompanionIds.isNotEmpty()) {
                Text(
                    text = stringResource(R.string.companion_split_people_count, state.selectedCompanionIds.size + 1),
                    style = FinanceTheme.typography.numericMicro,
                    color = FinanceTheme.colors.primary
                )
            }
        }

        FlowRow(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(FinanceTheme.spacing.s),
            verticalArrangement = Arrangement.spacedBy(FinanceTheme.spacing.s)
        ) {
            // Friend chips
            state.availableCompanions.forEach { friend ->
                val isSelected = state.selectedCompanionIds.contains(friend.id)
                val initial = friend.name.trim().take(1).uppercase()

                Surface(
                    shape = FinanceShapes.pill,
                    color = if (isSelected) MaterialTheme.colorScheme.secondaryContainer else FinanceTheme.colors.surfaceContainerLow,
                    border = BorderStroke(
                        FinanceTheme.sizes.strokeThin,
                        if (isSelected) MaterialTheme.colorScheme.secondary else FinanceTheme.colors.outlineVariant
                    ),
                    modifier = Modifier
                        .clip(FinanceShapes.pill)
                        .clickable {
                            focusManager.clearFocus()
                            onCompanionToggled(friend.id)
                        }
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = FinanceTheme.spacing.m, vertical = FinanceTheme.spacing.xs),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(FinanceTheme.spacing.xs)
                    ) {
                        Surface(
                            shape = FinanceShapes.pill,
                            color = if (isSelected) MaterialTheme.colorScheme.primary else FinanceTheme.colors.surfaceVariant,
                            modifier = Modifier.size(FinanceTheme.sizes.icon18)
                        ) {
                            Box(contentAlignment = Alignment.Center) {
                                Text(
                                    text = initial,
                                    style = FinanceTheme.typography.labelNano,
                                    fontWeight = FontWeight.Bold,
                                    color = if (isSelected) MaterialTheme.colorScheme.onPrimary else FinanceTheme.colors.onSurfaceVariant
                                )
                            }
                        }

                        Text(
                            text = friend.name,
                            style = FinanceTheme.typography.labelMedium,
                            fontWeight = if (isSelected) FontWeight.SemiBold else FontWeight.Normal,
                            color = if (isSelected) MaterialTheme.colorScheme.onSecondaryContainer else FinanceTheme.colors.onSurface
                        )

                        if (isSelected) {
                            Icon(
                                imageVector = Icons.Rounded.Check,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.secondary,
                                modifier = Modifier.size(FinanceTheme.sizes.iconXs)
                            )
                        }
                    }
                }
            }

            // Add Friend Chip
            Surface(
                shape = FinanceShapes.pill,
                color = FinanceTheme.colors.surfaceContainerHigh,
                border = BorderStroke(FinanceTheme.sizes.strokeThin, FinanceTheme.colors.outlineVariant),
                modifier = Modifier
                    .clip(FinanceShapes.pill)
                    .clickable {
                        focusManager.clearFocus()
                        showAddFriendInput = !showAddFriendInput
                    }
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = FinanceTheme.spacing.m, vertical = FinanceTheme.spacing.xs),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(FinanceTheme.spacing.xs)
                ) {
                    Icon(
                        imageVector = Icons.Rounded.Add,
                        contentDescription = stringResource(R.string.cd_add_friend),
                        tint = FinanceTheme.colors.primary,
                        modifier = Modifier.size(FinanceTheme.sizes.iconSmall)
                    )
                    Text(
                        text = stringResource(R.string.action_add_friend),
                        style = FinanceTheme.typography.labelMedium,
                        color = FinanceTheme.colors.primary
                    )
                }
            }
        }

        // Inline Add Friend Form
        if (showAddFriendInput) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(FinanceTheme.spacing.s),
                verticalAlignment = Alignment.CenterVertically
            ) {
                OutlinedTextField(
                    value = newFriendName,
                    onValueChange = { newFriendName = it },
                    placeholder = { Text(stringResource(R.string.hint_friends_name), style = FinanceTheme.typography.bodyMedium) },
                    singleLine = true,
                    shape = FinanceShapes.card,
                    keyboardOptions = KeyboardOptions(
                        capitalization = KeyboardCapitalization.Words,
                        imeAction = ImeAction.Done
                    ),
                    keyboardActions = KeyboardActions(
                        onDone = {
                            if (newFriendName.isNotBlank()) {
                                focusManager.clearFocus()
                                onAddCompanion(newFriendName.trim())
                                newFriendName = ""
                                showAddFriendInput = false
                            }
                        }
                    ),
                    modifier = Modifier
                        .weight(1f)
                        .onFocusChanged { onKeyboardFocusChanged(it.isFocused) }
                )

                FilledButton(
                    text = stringResource(R.string.action_add),
                    onClick = {
                        if (newFriendName.isNotBlank()) {
                            focusManager.clearFocus()
                            onAddCompanion(newFriendName.trim())
                            newFriendName = ""
                            showAddFriendInput = false
                        }
                    },
                    enabled = newFriendName.isNotBlank()
                )
            }
        }

        // Live Calculation Summary Card
        Surface(
            shape = FinanceShapes.card,
            color = FinanceTheme.colors.surfaceContainerLow,
            modifier = Modifier.fillMaxWidth()
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(FinanceTheme.spacing.m),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = stringResource(R.string.label_my_share_uppercase),
                        style = FinanceTheme.typography.labelUppercase,
                        color = FinanceTheme.colors.onSurfaceVariant
                    )
                    Spacer(modifier = Modifier.height(FinanceTheme.spacing.xxs))
                    Text(
                        text = formatRupees(state.myShare, includeDecimals = true),
                        style = FinanceTheme.typography.kpiMedium,
                        color = FinanceTheme.colors.onSurface
                    )
                }

                Box(
                    modifier = Modifier
                        .width(FinanceTheme.sizes.strokeThin)
                        .height(FinanceTheme.sizes.icon32)
                        .background(FinanceTheme.colors.outlineVariant)
                )

                Column(
                    modifier = Modifier.weight(1f),
                    horizontalAlignment = Alignment.End
                ) {
                    Text(
                        text = stringResource(R.string.label_owed_to_me_uppercase),
                        style = FinanceTheme.typography.labelUppercase,
                        color = FinanceTheme.colors.onSurfaceVariant
                    )
                    Spacer(modifier = Modifier.height(FinanceTheme.spacing.xxs))
                    Text(
                        text = formatRupees(state.owedToMe, includeDecimals = true),
                        style = FinanceTheme.typography.kpiMedium,
                        color = FinanceTheme.colors.primary,
                        fontWeight = FontWeight.SemiBold
                    )
                }
            }
        }
    }
}

/**
 * Banner displayed when an active trip tag is configured.
 */
@Composable
fun ActiveTripBanner(
    tripName: String,
    onEndTrip: () -> Unit,
    modifier: Modifier = Modifier
) {
    Surface(
        shape = FinanceShapes.card,
        color = MaterialTheme.colorScheme.tertiaryContainer.copy(alpha = 0.5f),
        border = BorderStroke(FinanceTheme.sizes.strokeThin, MaterialTheme.colorScheme.tertiary.copy(alpha = 0.5f)),
        modifier = modifier.fillMaxWidth()
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = FinanceTheme.spacing.m, vertical = FinanceTheme.spacing.s),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Row(
                horizontalArrangement = Arrangement.spacedBy(FinanceTheme.spacing.sm),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(
                    imageVector = Icons.Rounded.Flight,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.tertiary,
                    modifier = Modifier.size(FinanceTheme.sizes.iconSmall)
                )
                Text(
                    text = stringResource(R.string.format_trip_tag, tripName),
                    style = FinanceTheme.typography.labelSmall,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onTertiaryContainer
                )
            }
            Text(
                text = stringResource(R.string.action_end_trip),
                style = FinanceTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.error,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.clickable { onEndTrip() }
            )
        }
    }
}
