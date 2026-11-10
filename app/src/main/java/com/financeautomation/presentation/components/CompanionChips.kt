package com.financeautomation.presentation.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.InputChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.financeautomation.domain.model.Companion

/**
 * Multi-select friend chips component with an integrated Add Friend dialog.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun CompanionChips(
    companions: List<Companion>,
    selectedCompanionIds: Set<String>,
    onCompanionToggled: (String) -> Unit,
    onAddCompanion: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    var showAddDialog by remember { mutableStateOf(false) }
    var newFriendName by remember { mutableStateOf("") }

    FlowRow(
        modifier = modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        companions.forEach { companion ->
            val isSelected = selectedCompanionIds.contains(companion.id)
            FilterChip(
                selected = isSelected,
                onClick = { onCompanionToggled(companion.id) },
                label = {
                    Text(
                        text = companion.name,
                        style = MaterialTheme.typography.labelMedium
                    )
                },
                shape = androidx.compose.foundation.shape.RoundedCornerShape(10.dp),
                colors = FilterChipDefaults.filterChipColors(
                    selectedContainerColor = MaterialTheme.colorScheme.tertiaryContainer,
                    selectedLabelColor = MaterialTheme.colorScheme.tertiary
                )
            )
        }

        InputChip(
            selected = false,
            onClick = { showAddDialog = true },
            shape = androidx.compose.foundation.shape.RoundedCornerShape(10.dp),
            label = {
                Text(
                    text = "+ Add Friend",
                    style = MaterialTheme.typography.labelMedium
                )
            }
        )
    }

    if (showAddDialog) {
        AlertDialog(
            onDismissRequest = {
                showAddDialog = false
                newFriendName = ""
            },
            title = {
                Text(
                    text = "Add New Friend",
                    style = MaterialTheme.typography.titleMedium
                )
            },
            text = {
                OutlinedTextField(
                    value = newFriendName,
                    onValueChange = { newFriendName = it },
                    label = { Text("Friend Name") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        val trimmed = newFriendName.trim()
                        if (trimmed.isNotEmpty()) {
                            onAddCompanion(trimmed)
                        }
                        showAddDialog = false
                        newFriendName = ""
                    }
                ) {
                    Text("Add")
                }
            },
            dismissButton = {
                TextButton(
                    onClick = {
                        showAddDialog = false
                        newFriendName = ""
                    }
                ) {
                    Text("Cancel")
                }
            }
        )
    }
}
