package com.financeautomation.presentation.screens.settings

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
import androidx.compose.ui.res.stringResource
import com.financeautomation.R
import com.financeautomation.presentation.screens.SettingsCard
import com.financeautomation.presentation.theme.FinanceTheme
import com.financeautomation.presentation.viewmodel.SettingsUiState

/**
 * Settings section: Overlay Permission, Category Exclusions from Living Burn, Trip Tags.
 * Split from SettingsScreen to preserve the 400-line ceiling (Design Guide 4).
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun SettingsCaptureSection(
    state: SettingsUiState,
    onToggleCategoryLivingBurnExclusion: (String, Boolean) -> Unit,
    onAddCategory: (String, Boolean) -> Unit,
    onAddTripTag: (String) -> Unit,
    onToggleTripTagActive: (String) -> Unit,
    hasOverlayPermission: Boolean = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) Settings.canDrawOverlays(LocalContext.current) else true,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    var newCategoryInput by remember { mutableStateOf("") }
    var newCategoryBurnExclude by remember { mutableStateOf(false) }
    var newTagInput by remember { mutableStateOf("") }

    Column(modifier = modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(FinanceTheme.spacing.l)) {
        // 1. Overlay Permission
        SettingsCard(
            title = stringResource(R.string.settings_capture_title),
            subtitle = stringResource(R.string.settings_capture_desc),
            icon = Icons.Rounded.Layers
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = if (hasOverlayPermission) {
                            stringResource(R.string.settings_overlay_active)
                        } else {
                            stringResource(R.string.settings_overlay_missing)
                        },
                        style = FinanceTheme.typography.bodyMedium,
                        color = if (hasOverlayPermission) FinanceTheme.colors.moneyIn else FinanceTheme.colors.moneyOut
                    )
                    Text(
                        text = if (hasOverlayPermission) {
                            stringResource(R.string.settings_overlay_active_desc)
                        } else {
                            stringResource(R.string.settings_overlay_missing_desc)
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
                        Icon(imageVector = Icons.AutoMirrored.Rounded.OpenInNew, contentDescription = null, modifier = Modifier.size(FinanceTheme.sizes.iconSmall))
                        Spacer(modifier = Modifier.width(FinanceTheme.spacing.xs))
                        Text(stringResource(R.string.action_enable))
                    }
                }
            }
        }

        // 2. Categories & Living Burn Exclusions
        SettingsCard(
            title = stringResource(R.string.settings_categories_title),
            subtitle = stringResource(R.string.settings_categories_desc),
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
                                    text = if (category.isExcludedFromLivingBurn) {
                                        stringResource(R.string.settings_exclude_burn_label)
                                    } else {
                                        stringResource(R.string.settings_included_burn_label)
                                    },
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
                    label = { Text(stringResource(R.string.settings_new_category_label)) },
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
                    Icon(imageVector = Icons.Rounded.Add, contentDescription = null, modifier = Modifier.size(FinanceTheme.sizes.iconSmall))
                    Spacer(modifier = Modifier.width(FinanceTheme.spacing.xs))
                    Text(stringResource(R.string.action_add))
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
                    text = stringResource(R.string.settings_exclude_burn_label),
                    style = FinanceTheme.typography.labelMedium,
                    color = FinanceTheme.colors.onSurface
                )
            }
        }

        // 3. Trip & Event Tags
        SettingsCard(
            title = stringResource(R.string.settings_trip_tags_title),
            subtitle = if (state.activeTripTag.isNotBlank()) {
                stringResource(R.string.settings_active_trip_format, state.activeTripTag)
            } else {
                stringResource(R.string.settings_no_trip_active)
            },
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
                    label = { Text(stringResource(R.string.settings_new_trip_tag_label)) },
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
                    Icon(imageVector = Icons.Rounded.Add, contentDescription = null, modifier = Modifier.size(FinanceTheme.sizes.iconSmall))
                    Spacer(modifier = Modifier.width(FinanceTheme.spacing.xs))
                    Text(stringResource(R.string.action_add))
                }
            }
        }

        // 4. Capture Health & Pipeline Trail
        if (state.developerMode) {
            CaptureHealthCard(modifier = Modifier.fillMaxWidth())
        }
    }
}
