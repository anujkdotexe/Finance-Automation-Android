package com.financeautomation.presentation.screens.drawer

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import com.financeautomation.R
import com.financeautomation.presentation.components.ButtonPair
import com.financeautomation.presentation.theme.FinanceShapes
import com.financeautomation.presentation.theme.FinanceTheme

private val CATEGORY_COLORS = listOf(
    Color(0xFF006874), // Teal
    Color(0xFFB57400), // Amber
    Color(0xFF3F51B5), // Indigo
    Color(0xFFC2185B), // Rose
    Color(0xFF00838F), // Cyan
    Color(0xFF558B2F), // Lime
    Color(0xFF4A6267)  // Slate
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AddCategorySheet(
    onDismiss: () -> Unit,
    onSave: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    var categoryName by remember { mutableStateOf("") }
    var selectedColorIndex by remember { mutableIntStateOf(0) }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = FinanceTheme.colors.surfaceContainerLowest,
        shape = FinanceShapes.sheet,
        modifier = modifier
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = FinanceTheme.spacing.l)
                .padding(bottom = FinanceTheme.spacing.l)
                .imePadding()
        ) {
            // Header
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = stringResource(R.string.categories_add_sheet_title),
                    style = FinanceTheme.typography.titleLarge,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurface
                )
                IconButton(onClick = onDismiss) {
                    Icon(
                        imageVector = Icons.Rounded.Close,
                        contentDescription = stringResource(R.string.action_cancel),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            Spacer(modifier = Modifier.height(FinanceTheme.spacing.m))

            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(FinanceTheme.spacing.l)
            ) {
                // Name Input
                Column(verticalArrangement = Arrangement.spacedBy(FinanceTheme.spacing.xs)) {
                    Text(
                        text = stringResource(R.string.categories_add_sheet_name_label),
                        style = FinanceTheme.typography.titleSmall,
                        fontWeight = FontWeight.Medium,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    OutlinedTextField(
                        value = categoryName,
                        onValueChange = { categoryName = it },
                        placeholder = { Text(stringResource(R.string.categories_add_sheet_name_placeholder)) },
                        singleLine = true,
                        shape = FinanceShapes.medium,
                        modifier = Modifier.fillMaxWidth()
                    )
                }

                // Colour Palette
                Column(verticalArrangement = Arrangement.spacedBy(FinanceTheme.spacing.s)) {
                    Text(
                        text = stringResource(R.string.categories_add_sheet_color_label),
                        style = FinanceTheme.typography.titleSmall,
                        fontWeight = FontWeight.Medium,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        CATEGORY_COLORS.forEachIndexed { index, color ->
                            val isSelected = selectedColorIndex == index
                            Box(
                                modifier = Modifier
                                    .size(FinanceTheme.sizes.icon32)
                                    .clip(CircleShape)
                                    .background(color)
                                    .clickable { selectedColorIndex = index },
                                contentAlignment = Alignment.Center
                            ) {
                                if (isSelected) {
                                    Icon(
                                        imageVector = Icons.Rounded.Check,
                                        contentDescription = null,
                                        tint = Color.White,
                                        modifier = Modifier.size(FinanceTheme.sizes.icon18)
                                    )
                                }
                            }
                        }
                    }
                }

                Spacer(modifier = Modifier.height(FinanceTheme.spacing.m))

                // Action Button Pair (pinned above keyboard via imePadding)
                ButtonPair(
                    outlinedText = stringResource(R.string.action_cancel),
                    onOutlinedClick = onDismiss,
                    filledText = stringResource(R.string.action_save),
                    onFilledClick = {
                        if (categoryName.isNotBlank()) {
                            onSave(categoryName.trim())
                        }
                    },
                    filledEnabled = categoryName.isNotBlank(),
                    modifier = Modifier.fillMaxWidth()
                )
            }
        }
    }
}
