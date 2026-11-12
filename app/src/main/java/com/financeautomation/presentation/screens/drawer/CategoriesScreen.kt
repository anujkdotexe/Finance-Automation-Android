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
import androidx.compose.material.icons.rounded.Category
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
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
fun CategoriesScreen(
    viewModel: SettingsViewModel,
    state: SettingsUiState,
    modifier: Modifier = Modifier
) {
    val scrollState = rememberScrollState()
    var newCategoryInput by remember { mutableStateOf("") }
    var newCategoryBurnExclude by remember { mutableStateOf(false) }

    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(scrollState)
            .padding(FinanceTheme.spacing.l),
        verticalArrangement = Arrangement.spacedBy(FinanceTheme.spacing.l)
    ) {
        SettingsCard(
            title = stringResource(R.string.settings_categories_title),
            subtitle = stringResource(R.string.settings_categories_desc),
            icon = Icons.Rounded.Category
        ) {
            FlowRow(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(FinanceTheme.spacing.xs),
                verticalArrangement = Arrangement.spacedBy(FinanceTheme.spacing.xs)
            ) {
                state.categories.forEach { category ->
                    FilterChip(
                        selected = category.isExcludedFromLivingBurn,
                        onClick = {
                            viewModel.onToggleCategoryLivingBurnExclusion(
                                category.id,
                                !category.isExcludedFromLivingBurn
                            )
                        },
                        label = { Text(category.name) },
                        colors = FilterChipDefaults.filterChipColors(
                            selectedContainerColor = MaterialTheme.colorScheme.tertiaryContainer,
                            selectedLabelColor = MaterialTheme.colorScheme.onTertiaryContainer
                        )
                    )
                }
            }

            Text(
                text = "Tap a category to exclude or include it in Living Burn calculations.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

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
                            viewModel.onAddCategory(newCategoryInput, newCategoryBurnExclude)
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
                modifier = Modifier.padding(top = FinanceTheme.spacing.xs)
            ) {
                Checkbox(
                    checked = newCategoryBurnExclude,
                    onCheckedChange = { newCategoryBurnExclude = it }
                )
                Spacer(modifier = Modifier.width(FinanceTheme.spacing.xs))
                Text(
                    text = stringResource(R.string.settings_exclude_burn_label),
                    style = MaterialTheme.typography.bodyMedium
                )
            }
        }
    }
}

