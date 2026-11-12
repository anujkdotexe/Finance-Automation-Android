package com.financeautomation.presentation.screens.drawer

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.FlightTakeoff
import androidx.compose.material.icons.rounded.People
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import com.financeautomation.R
import com.financeautomation.presentation.screens.SettingsCard
import com.financeautomation.presentation.theme.FinanceTheme
import com.financeautomation.presentation.viewmodel.SettingsUiState
import com.financeautomation.presentation.viewmodel.SettingsViewModel

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun PeopleTripsScreen(
    viewModel: SettingsViewModel,
    state: SettingsUiState,
    modifier: Modifier = Modifier
) {
    val scrollState = rememberScrollState()
    var newCompanionInput by remember { mutableStateOf("") }
    var newTagInput by remember { mutableStateOf("") }

    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(scrollState)
            .padding(FinanceTheme.spacing.l),
        verticalArrangement = Arrangement.spacedBy(FinanceTheme.spacing.l)
    ) {
        // Friends & Companions Card
        SettingsCard(
            title = stringResource(R.string.settings_friends_title),
            subtitle = stringResource(R.string.settings_friends_desc),
            icon = Icons.Rounded.People
        ) {
            FlowRow(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(FinanceTheme.spacing.xs),
                verticalArrangement = Arrangement.spacedBy(FinanceTheme.spacing.xs)
            ) {
                state.companions.forEach { companion ->
                    FilterChip(
                        selected = false,
                        onClick = { },
                        label = { Text(companion.name) }
                    )
                }
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(FinanceTheme.spacing.s)
            ) {
                OutlinedTextField(
                    value = newCompanionInput,
                    onValueChange = { newCompanionInput = it },
                    label = { Text(stringResource(R.string.settings_new_friend_label)) },
                    singleLine = true,
                    shape = FinanceTheme.shapes.medium,
                    modifier = Modifier.weight(1f)
                )

                Button(
                    onClick = {
                        if (newCompanionInput.isNotBlank()) {
                            viewModel.onAddCompanion(newCompanionInput)
                            newCompanionInput = ""
                        }
                    },
                    shape = FinanceTheme.shapes.medium
                ) {
                    Icon(imageVector = Icons.Rounded.Add, contentDescription = null, modifier = Modifier.size(FinanceTheme.sizes.iconSmall))
                    Spacer(modifier = Modifier.width(FinanceTheme.spacing.xs))
                    Text(stringResource(R.string.action_add))
                }
            }
        }

        // Trip Tags Card
        SettingsCard(
            title = stringResource(R.string.settings_trip_tags_title),
            subtitle = "Active travel and event tags",
            icon = Icons.Rounded.FlightTakeoff
        ) {
            FlowRow(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(FinanceTheme.spacing.xs),
                verticalArrangement = Arrangement.spacedBy(FinanceTheme.spacing.xs)
            ) {
                state.tripTags.forEach { tag ->
                    FilterChip(
                        selected = tag.isActive,
                        onClick = { viewModel.onToggleTripTagActive(tag.id) },
                        label = { Text(tag.name) },
                        colors = FilterChipDefaults.filterChipColors(
                            selectedContainerColor = MaterialTheme.colorScheme.primaryContainer,
                            selectedLabelColor = MaterialTheme.colorScheme.onPrimaryContainer
                        )
                    )
                }
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(FinanceTheme.spacing.s)
            ) {
                OutlinedTextField(
                    value = newTagInput,
                    onValueChange = { newTagInput = it },
                    label = { Text(stringResource(R.string.settings_new_trip_tag_label)) },
                    singleLine = true,
                    shape = FinanceTheme.shapes.medium,
                    modifier = Modifier.weight(1f)
                )

                Button(
                    onClick = {
                        if (newTagInput.isNotBlank()) {
                            viewModel.onAddTripTag(newTagInput)
                            newTagInput = ""
                        }
                    },
                    shape = FinanceTheme.shapes.medium
                ) {
                    Icon(imageVector = Icons.Rounded.Add, contentDescription = null, modifier = Modifier.size(FinanceTheme.sizes.iconSmall))
                    Spacer(modifier = Modifier.width(FinanceTheme.spacing.xs))
                    Text(stringResource(R.string.action_add))
                }
            }
        }
    }
}

