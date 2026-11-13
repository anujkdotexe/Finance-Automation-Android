package com.financeautomation.presentation.flows

import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Flight
import androidx.compose.material.icons.rounded.LocationOn
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import com.financeautomation.R
import com.financeautomation.domain.model.Companion
import com.financeautomation.domain.model.TripTag
import com.financeautomation.presentation.components.AppChip
import com.financeautomation.presentation.components.ButtonPair
import com.financeautomation.presentation.theme.FinanceShapes
import com.financeautomation.presentation.theme.FinanceTheme

/**
 * Rebuilt Trip Mode flow sheet following the frozen design specification:
 * - Trip name field.
 * - Optional Place (optional) text field.
 * - People on trip chips.
 * - Totals when a trip is active.
 * - ButtonPair: OutlinedButton "End trip" (or "Cancel"), FilledButton "Start new trip".
 * - No map, no "Active trip" or "Live sync" labels.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun TripModeFlowSheet(
    activeTag: TripTag?,
    companions: List<Companion> = emptyList(),
    onBack: () -> Unit,
    onDismiss: () -> Unit,
    onStartTrip: (tagName: String) -> Unit,
    onEndTrip: () -> Unit,
    onKeyboardFocusChanged: (Boolean) -> Unit,
    modifier: Modifier = Modifier
) {
    var tagNameText by remember { mutableStateOf("") }
    var placeText by remember { mutableStateOf("") }

    val scrollState = rememberScrollState()
    val focusManager = LocalFocusManager.current

    LaunchedEffect(scrollState.isScrollInProgress) {
        if (scrollState.isScrollInProgress) {
            focusManager.clearFocus()
        }
    }

    Column(
        modifier = modifier
            .fillMaxWidth()
            .imePadding()
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null
            ) { focusManager.clearFocus() }
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f, fill = false)
                .verticalScroll(scrollState),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(FinanceTheme.spacing.m)
        ) {
            if (activeTag != null) {
            // Active Trip Display Card
            Surface(
                shape = FinanceShapes.card,
                color = FinanceTheme.colors.surfaceContainerLow,
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(
                    modifier = Modifier.padding(FinanceTheme.spacing.m),
                    verticalArrangement = Arrangement.spacedBy(FinanceTheme.spacing.s)
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column {
                            Text(
                                text = stringResource(R.string.prompt_trip_name),
                                style = FinanceTheme.typography.labelUppercase,
                                color = FinanceTheme.colors.onSurfaceVariant
                            )
                            Spacer(modifier = Modifier.height(FinanceTheme.spacing.xxs))
                            Text(
                                text = activeTag.name,
                                style = FinanceTheme.typography.headlineSmall,
                                fontWeight = FontWeight.Bold,
                                color = FinanceTheme.colors.onSurface
                            )
                        }

                        Icon(
                            imageVector = Icons.Rounded.Flight,
                            contentDescription = null,
                            tint = FinanceTheme.colors.primary,
                            modifier = Modifier.size(FinanceTheme.sizes.iconLarge)
                        )
                    }

                    // Optional Place text field
                    OutlinedTextField(
                        value = placeText,
                        onValueChange = { placeText = it },
                        placeholder = {
                            Text(
                                text = stringResource(R.string.prompt_place_optional),
                                style = FinanceTheme.typography.bodyMedium
                            )
                        },
                        leadingIcon = {
                            Icon(
                                imageVector = Icons.Rounded.LocationOn,
                                contentDescription = null,
                                tint = FinanceTheme.colors.onSurfaceVariant,
                                modifier = Modifier.size(FinanceTheme.sizes.iconSmall)
                            )
                        },
                        singleLine = true,
                        shape = FinanceShapes.card,
                        keyboardOptions = KeyboardOptions(
                            capitalization = KeyboardCapitalization.Words,
                            imeAction = ImeAction.Done
                        ),
                        keyboardActions = KeyboardActions(onDone = { focusManager.clearFocus() }),
                        modifier = Modifier
                            .fillMaxWidth()
                            .onFocusChanged { onKeyboardFocusChanged(it.isFocused) }
                    )
                }
            }

            // Trip Summary Cards (Totals when active)
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
                            text = stringResource(R.string.trip_summary_total_spend),
                            style = FinanceTheme.typography.labelUppercase,
                            color = FinanceTheme.colors.onSurfaceVariant
                        )
                        Spacer(modifier = Modifier.height(FinanceTheme.spacing.xxs))
                        Text(
                            text = "₹0.00",
                            style = FinanceTheme.typography.kpiMedium,
                            color = FinanceTheme.colors.onSurface
                        )
                    }

                    Column(
                        modifier = Modifier.weight(1f),
                        horizontalAlignment = Alignment.End
                    ) {
                        Text(
                            text = stringResource(R.string.label_my_share_uppercase),
                            style = FinanceTheme.typography.labelUppercase,
                            color = FinanceTheme.colors.onSurfaceVariant
                        )
                        Spacer(modifier = Modifier.height(FinanceTheme.spacing.xxs))
                        Text(
                            text = "₹0.00",
                            style = FinanceTheme.typography.kpiMedium,
                            color = FinanceTheme.colors.primary,
                            fontWeight = FontWeight.SemiBold
                        )
                    }
                }
            }
        } else {
            // New Trip Input Fields
            Column(modifier = Modifier.fillMaxWidth()) {
                Text(
                    text = stringResource(R.string.prompt_trip_name),
                    style = FinanceTheme.typography.labelUppercase,
                    color = FinanceTheme.colors.onSurfaceVariant
                )

                Spacer(modifier = Modifier.height(FinanceTheme.spacing.xs))

                OutlinedTextField(
                    value = tagNameText,
                    onValueChange = { tagNameText = it },
                    placeholder = {
                        Text(
                            text = stringResource(R.string.hint_trip_name),
                            style = FinanceTheme.typography.bodyMedium
                        )
                    },
                    singleLine = true,
                    shape = FinanceShapes.card,
                    keyboardOptions = KeyboardOptions(
                        capitalization = KeyboardCapitalization.Words,
                        imeAction = ImeAction.Next
                    ),
                    modifier = Modifier
                        .fillMaxWidth()
                        .onFocusChanged { onKeyboardFocusChanged(it.isFocused) }
                )
            }

            // Optional Place field
            Column(modifier = Modifier.fillMaxWidth()) {
                Text(
                    text = stringResource(R.string.prompt_place_optional),
                    style = FinanceTheme.typography.labelUppercase,
                    color = FinanceTheme.colors.onSurfaceVariant
                )

                Spacer(modifier = Modifier.height(FinanceTheme.spacing.xs))

                OutlinedTextField(
                    value = placeText,
                    onValueChange = { placeText = it },
                    placeholder = {
                        Text(
                            text = stringResource(R.string.prompt_place_optional),
                            style = FinanceTheme.typography.bodyMedium
                        )
                    },
                    leadingIcon = {
                        Icon(
                            imageVector = Icons.Rounded.LocationOn,
                            contentDescription = null,
                            tint = FinanceTheme.colors.onSurfaceVariant,
                            modifier = Modifier.size(FinanceTheme.sizes.iconSmall)
                        )
                    },
                    singleLine = true,
                    shape = FinanceShapes.card,
                    keyboardOptions = KeyboardOptions(
                        capitalization = KeyboardCapitalization.Words,
                        imeAction = ImeAction.Done
                    ),
                    keyboardActions = KeyboardActions(onDone = { focusManager.clearFocus() }),
                    modifier = Modifier
                        .fillMaxWidth()
                        .onFocusChanged { onKeyboardFocusChanged(it.isFocused) }
                )
            }
        }

        // People on Trip Chips
        Column(modifier = Modifier.fillMaxWidth()) {
            Text(
                text = stringResource(R.string.trip_people_on_trip),
                style = FinanceTheme.typography.labelUppercase,
                color = FinanceTheme.colors.onSurfaceVariant
            )

            Spacer(modifier = Modifier.height(FinanceTheme.spacing.xs))

            FlowRow(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(FinanceTheme.spacing.s),
                verticalArrangement = Arrangement.spacedBy(FinanceTheme.spacing.s)
            ) {
                // "You" chip
                AppChip(
                    text = stringResource(R.string.label_you),
                    selected = true,
                    onClick = {},
                    leadingIcon = {
                        Icon(
                            imageVector = Icons.Rounded.Check,
                            contentDescription = null,
                            tint = FinanceTheme.colors.primary,
                            modifier = Modifier.size(FinanceTheme.sizes.iconXs)
                        )
                    }
                )

                companions.forEach { companion ->
                    AppChip(
                        text = companion.name,
                        selected = false,
                        onClick = {}
                    )
                }
            }
        }
        }

        Spacer(modifier = Modifier.height(FinanceTheme.spacing.m))

        // Pinned Action Buttons outside scrollable area
        if (activeTag != null) {
            ButtonPair(
                outlinedText = stringResource(R.string.action_end_trip),
                onOutlinedClick = onEndTrip,
                filledText = stringResource(R.string.btn_start_new_trip),
                onFilledClick = {
                    if (tagNameText.isNotBlank()) {
                        val effectiveTag = if (placeText.isNotBlank()) {
                            "${tagNameText.trim()} - ${placeText.trim()}"
                        } else {
                            tagNameText.trim()
                        }
                        onStartTrip(effectiveTag)
                    }
                },
                filledEnabled = tagNameText.isNotBlank()
            )
        } else {
            ButtonPair(
                outlinedText = stringResource(R.string.action_cancel),
                onOutlinedClick = onDismiss,
                filledText = stringResource(R.string.btn_start_new_trip),
                onFilledClick = {
                    if (tagNameText.isNotBlank()) {
                        val effectiveTag = if (placeText.isNotBlank()) {
                            "${tagNameText.trim()} - ${placeText.trim()}"
                        } else {
                            tagNameText.trim()
                        }
                        onStartTrip(effectiveTag)
                    }
                },
                filledEnabled = tagNameText.isNotBlank()
            )
        }
    }
}
