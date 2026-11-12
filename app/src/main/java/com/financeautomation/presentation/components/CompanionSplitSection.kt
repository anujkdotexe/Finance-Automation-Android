package com.financeautomation.presentation.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Flight
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
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
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.financeautomation.R
import com.financeautomation.core.model.SplitMode
import com.financeautomation.core.utils.formatRupees
import com.financeautomation.presentation.theme.FinanceTheme
import com.financeautomation.presentation.viewmodel.QuickLogUiState

/**
 * Expandable companion picker, inline friend addition, custom share input, and split calculation preview.
 */
@Composable
fun CompanionSplitSection(
    state: QuickLogUiState,
    onCompanionToggled: (String) -> Unit,
    onAddCompanion: (String) -> Unit,
    onCustomShareChanged: (String) -> Unit,
    onKeyboardFocusChanged: (Boolean) -> Unit,
    modifier: Modifier = Modifier
) {
    var showAddFriendInput by remember { mutableStateOf(false) }
    var newFriendName by remember { mutableStateOf("") }
    val focusManager = LocalFocusManager.current

    if (state.splitMode != SplitMode.JUST_ME) {
        Column(
            modifier = modifier
                .fillMaxWidth()
                .padding(top = 10.dp)
        ) {
                val companionsScroll = rememberScrollState()
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .horizontalScroll(companionsScroll),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Surface(
                        shape = FinanceTheme.shapes.medium,
                        color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.5f),
                        modifier = Modifier.clickable {
                            focusManager.clearFocus()
                            showAddFriendInput = !showAddFriendInput
                        }
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(4.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Rounded.Add,
                                contentDescription = stringResource(R.string.cd_add_friend),
                                tint = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.size(14.dp)
                            )
                            Text(
                                text = stringResource(R.string.label_friend),
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.primary
                            )
                        }
                    }

                    state.availableCompanions.forEach { friend ->
                        val isSelected = state.selectedCompanionIds.contains(friend.id)
                        Surface(
                            shape = FinanceTheme.shapes.medium,
                            color = if (isSelected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
                            modifier = Modifier.clickable {
                                focusManager.clearFocus()
                                onCompanionToggled(friend.id)
                            }
                        ) {
                            Text(
                                text = friend.name,
                                style = MaterialTheme.typography.labelSmall,
                                fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal,
                                color = if (isSelected) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurface,
                                modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp)
                            )
                        }
                    }
                }

                if (showAddFriendInput) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = 8.dp),
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        OutlinedTextField(
                            value = newFriendName,
                            onValueChange = { newFriendName = it },
                            placeholder = { Text(stringResource(R.string.hint_friends_name)) },
                            singleLine = true,
                            shape = FinanceTheme.shapes.card,
                            keyboardOptions = KeyboardOptions(
                                imeAction = ImeAction.Done
                            ),
                            keyboardActions = KeyboardActions(
                                onDone = { focusManager.clearFocus() }
                            ),
                            modifier = Modifier
                                .weight(1f)
                                .onFocusChanged { onKeyboardFocusChanged(it.isFocused) }
                        )
                        Button(
                            onClick = {
                                if (newFriendName.isNotBlank()) {
                                    focusManager.clearFocus()
                                    onAddCompanion(newFriendName.trim())
                                    newFriendName = ""
                                    showAddFriendInput = false
                                }
                            },
                            shape = FinanceTheme.shapes.card
                        ) {
                            Text(stringResource(R.string.action_add))
                        }
                    }
                }

                if (state.splitMode == SplitMode.CUSTOM) {
                    Spacer(modifier = Modifier.height(8.dp))
                    OutlinedTextField(
                        value = state.customShareText,
                        onValueChange = onCustomShareChanged,
                        label = { Text(stringResource(R.string.label_personal_share)) },
                        singleLine = true,
                        shape = FinanceTheme.shapes.medium,
                        keyboardOptions = KeyboardOptions(
                            keyboardType = KeyboardType.Decimal,
                            imeAction = ImeAction.Done
                        ),
                        keyboardActions = KeyboardActions(
                            onDone = { focusManager.clearFocus() }
                        ),
                        modifier = Modifier
                            .fillMaxWidth()
                            .onFocusChanged { onKeyboardFocusChanged(it.isFocused) }
                    )
                }

                // Split Preview
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 6.dp),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text(
                        text = stringResource(R.string.format_my_share, formatRupees(state.myShare, includeDecimals = true)),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Text(
                        text = stringResource(R.string.format_owed_to_me, formatRupees(state.owedToMe, includeDecimals = true)),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.primary,
                        fontWeight = FontWeight.SemiBold
                    )
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
        shape = FinanceTheme.shapes.medium,
        color = MaterialTheme.colorScheme.tertiaryContainer.copy(alpha = 0.5f),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.tertiary.copy(alpha = 0.5f)),
        modifier = modifier.fillMaxWidth()
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 6.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Row(
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(
                    imageVector = Icons.Rounded.Flight,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.tertiary,
                    modifier = Modifier.size(16.dp)
                )
                Text(
                    text = stringResource(R.string.format_trip_tag, tripName),
                    style = MaterialTheme.typography.labelSmall,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onTertiaryContainer
                )
            }
            Text(
                text = stringResource(R.string.action_end_trip),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.error,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.clickable { onEndTrip() }
            )
        }
    }
}
