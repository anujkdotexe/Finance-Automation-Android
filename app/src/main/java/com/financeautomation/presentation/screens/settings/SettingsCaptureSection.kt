package com.financeautomation.presentation.screens.settings

import com.financeautomation.presentation.screens.SettingsCard

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.OpenInNew
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Category
import androidx.compose.material.icons.rounded.FlightTakeoff
import androidx.compose.material.icons.rounded.Layers
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.financeautomation.presentation.theme.FinanceTheme
import com.financeautomation.presentation.viewmodel.SettingsUiState

/**
 * Capture settings section managing overlay popup permissions, categories, and trip tags (Design Guide 5.2).
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun SettingsCaptureSection(
    state: SettingsUiState,
    onAddCategory: (String, Boolean) -> Unit,
    onToggleCategoryLivingBurnExclusion: (String, Boolean) -> Unit,
    onAddTripTag: (String) -> Unit,
    onToggleTripTagActive: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val hasOverlayPermission = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
        Settings.canDrawOverlays(context)
    } else true

    var newCategoryInput by remember { mutableStateOf("") }
    var newCategoryBurnExclude by remember { mutableStateOf(false) }
    var newTagInput by remember { mutableStateOf("") }

    Column(modifier = modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(FinanceTheme.spacing.l)) {
        // 1. Overlay Permission
        SettingsCard(
            title = "Display over other apps",
            subtitle = "Automatic expense sheet popup after UPI payment completes",
            icon = Icons.Rounded.Layers
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = if (hasOverlayPermission) "Overlay popup active" else "Overlay permission missing",
                        style = FinanceTheme.typography.bodyMedium,
                        color = if (hasOverlayPermission) FinanceTheme.colors.moneyIn else FinanceTheme.colors.moneyOut
                    )
                    Text(
                        text = if (hasOverlayPermission) {
                            "Bottom sheet pops up automatically after payment"
                        } else {
                            "Falls back to high-priority notification"
                        },
                        style = FinanceTheme.typography.labelMedium,
                        color = FinanceTheme.colors.onSurfaceVariant
                    )
                }

                if (!hasOverlayPermission && Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                    OutlinedButton(
                        onClick = {
                            val intent = Intent(
                                Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                                Uri.parse("package:${context.packageName}")
                            )
                            context.startActivity(intent)
                        },
                        shape = FinanceTheme.shapes.medium
                    ) {
                        Icon(imageVector = Icons.AutoMirrored.Rounded.OpenInNew, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(modifier = Modifier.width(FinanceTheme.spacing.xs))
                        Text("Enable")
                    }
                }
            }
        }

        // 2. Categories & Living Burn Exclusions
        SettingsCard(
            title = "Expense categories and living burn",
            subtitle = "Excluded categories are omitted from Monthly Living Burn KPIs (e.g. Savings, Transfers).",
            icon = Icons.Rounded.Category
        ) {
            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(FinanceTheme.spacing.s)
            ) {
                state.categories.forEach { category ->
                    Surface(
                        color = FinanceTheme.colors.surfaceVariant.copy(alpha = 0.5f),
                        shape = FinanceTheme.shapes.medium,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = FinanceTheme.spacing.m, vertical = FinanceTheme.spacing.s),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = category.name,
                                    style = FinanceTheme.typography.bodyMedium,
                                    color = FinanceTheme.colors.onSurface
                                )
                                Text(
                                    text = if (category.isExcludedFromLivingBurn) "Excluded from Living Burn" else "Included in Living Burn",
                                    style = FinanceTheme.typography.labelMedium,
                                    color = if (category.isExcludedFromLivingBurn) FinanceTheme.colors.moneyOut else FinanceTheme.colors.onSurfaceVariant
                                )
                            }

                            Switch(
                                checked = category.isExcludedFromLivingBurn,
                                onCheckedChange = { isChecked ->
                                    onToggleCategoryLivingBurnExclusion(category.id, isChecked)
                                }
                            )
                        }
                    }
                }
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(FinanceTheme.spacing.s)
            ) {
                OutlinedTextField(
                    value = newCategoryInput,
                    onValueChange = { newCategoryInput = it },
                    label = { Text("New category name") },
                    singleLine = true,
                    shape = FinanceTheme.shapes.medium,
                    modifier = Modifier.weight(1f)
                )

                Button(
                    onClick = {
                        if (newCategoryInput.isNotBlank()) {
                            onAddCategory(newCategoryInput, newCategoryBurnExclude)
                            newCategoryInput = ""
                            newCategoryBurnExclude = false
                        }
                    },
                    shape = FinanceTheme.shapes.medium
                ) {
                    Icon(imageVector = Icons.Rounded.Add, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(modifier = Modifier.width(FinanceTheme.spacing.xs))
                    Text("Add")
                }
            }

            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.padding(start = FinanceTheme.spacing.xs)
            ) {
                Checkbox(
                    checked = newCategoryBurnExclude,
                    onCheckedChange = { newCategoryBurnExclude = it }
                )
                Text(
                    text = "Exclude from Living Burn",
                    style = FinanceTheme.typography.labelMedium,
                    color = FinanceTheme.colors.onSurface
                )
            }
        }

        // 3. Trip & Event Tags
        SettingsCard(
            title = "Trip and event tags",
            subtitle = if (state.activeTripTag.isNotBlank()) "Active tag: ${state.activeTripTag}" else "No trip tag active.",
            icon = Icons.Rounded.FlightTakeoff
        ) {
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(FinanceTheme.spacing.s),
                verticalArrangement = Arrangement.spacedBy(FinanceTheme.spacing.s)
            ) {
                state.tripTags.forEach { tag ->
                    FilterChip(
                        selected = tag.isActive,
                        onClick = { onToggleTripTagActive(tag.id) },
                        label = { Text(tag.name) },
                        shape = FinanceTheme.shapes.chip
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
                    label = { Text("New trip tag (e.g. Goa 2026)") },
                    singleLine = true,
                    shape = FinanceTheme.shapes.medium,
                    modifier = Modifier.weight(1f)
                )

                Button(
                    onClick = {
                        if (newTagInput.isNotBlank()) {
                            onAddTripTag(newTagInput)
                            newTagInput = ""
                        }
                    },
                    shape = FinanceTheme.shapes.medium
                ) {
                    Icon(imageVector = Icons.Rounded.Add, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(modifier = Modifier.width(FinanceTheme.spacing.xs))
                    Text("Add")
                }
            }
        }
    }
}
