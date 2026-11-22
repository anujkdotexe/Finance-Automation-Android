package com.financeautomation.presentation.screens.drawer

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Person
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
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import com.financeautomation.R
import com.financeautomation.presentation.components.ButtonPair
import com.financeautomation.presentation.theme.FinanceShapes
import com.financeautomation.presentation.theme.FinanceTheme

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AddFriendSheet(
    onDismiss: () -> Unit,
    onSave: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    var friendName by rememberSaveable { mutableStateOf("") }

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
                    text = stringResource(R.string.friend_add_title),
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
                    .weight(1f, fill = false)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(FinanceTheme.spacing.l)
            ) {
                Column(verticalArrangement = Arrangement.spacedBy(FinanceTheme.spacing.xs)) {
                    Text(
                        text = stringResource(R.string.friend_add_name_label),
                        style = FinanceTheme.typography.labelSmall,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    OutlinedTextField(
                        value = friendName,
                        onValueChange = {
                            friendName = com.financeautomation.core.utils.InputPolicy.cleanName(it)
                                .take(com.financeautomation.core.utils.InputPolicy.MAX_NAME_LENGTH)
                        },
                        placeholder = { Text(stringResource(R.string.friend_add_name_placeholder)) },
                        leadingIcon = {
                            Icon(
                                imageVector = Icons.Rounded.Person,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.size(FinanceTheme.sizes.iconMedium)
                            )
                        },
                        singleLine = true,
                        shape = FinanceShapes.medium,
                        modifier = Modifier.fillMaxWidth()
                    )
                    Text(
                        text = stringResource(R.string.friend_add_name_helper),
                        style = FinanceTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }

            }

            Spacer(modifier = Modifier.height(FinanceTheme.spacing.m))

            // Action Button Pair (pinned below the scrolling form, above the keyboard via imePadding)
            ButtonPair(
                outlinedText = stringResource(R.string.action_cancel),
                onOutlinedClick = onDismiss,
                filledText = stringResource(R.string.action_save),
                onFilledClick = {
                    if (friendName.isNotBlank()) {
                        onSave(friendName.trim())
                    }
                },
                filledEnabled = friendName.isNotBlank(),
                modifier = Modifier.fillMaxWidth()
            )
        }
    }
}

