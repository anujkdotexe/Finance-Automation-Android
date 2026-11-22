package com.financeautomation.presentation.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.EditNote
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import com.financeautomation.R
import com.financeautomation.core.utils.InputPolicy
import com.financeautomation.presentation.theme.FinanceShapes
import com.financeautomation.presentation.theme.FinanceTheme

/**
 * Shared NotesField composable (P2-H Item 2).
 *
 * Collapsed state: "Notes" text button with an Add icon and a 48 dp minimum touch target.
 * Expanded state: Outlined text field with 120 character counter, clear action, and edit action.
 */
@Composable
fun NotesField(
    value: String,
    onValueChange: (String) -> Unit,
    modifier: Modifier = Modifier,
    label: String = stringResource(R.string.notes_field_label),
    placeholder: String = stringResource(R.string.notes_field_placeholder),
    onFocusChanged: (Boolean) -> Unit = {}
) {
    val noteState = rememberDebouncedTextState(
        value = value,
        debounceMs = 0L,
        cleanText = { raw ->
            raw.filter { c ->
                c == '\n' || c == '\t' || (c.code >= 0x20 && c.code != 0x7F)
            }.take(InputPolicy.MAX_NOTE_LENGTH)
        },
        onValueChanged = onValueChange
    )
    var isExpanded by rememberSaveable(value) { mutableStateOf(value.isNotEmpty()) }
    val focusRequester = remember { FocusRequester() }
    val focusManager = LocalFocusManager.current
    val addNoteCd = stringResource(R.string.notes_field_add_cd)
    val clearNoteCd = stringResource(R.string.notes_field_clear_cd)

    LaunchedEffect(isExpanded) {
        if (isExpanded && noteState.textFieldValue.text.isEmpty()) {
            try {
                focusRequester.requestFocus()
            } catch (_: Exception) {}
        }
    }

    Column(modifier = modifier.fillMaxWidth()) {
        if (!isExpanded && noteState.textFieldValue.text.isEmpty()) {
            TextButton(
                onClick = { isExpanded = true },
                modifier = Modifier
                    .defaultMinSize(minHeight = FinanceTheme.sizes.minTouchTarget)
                    .semantics { contentDescription = addNoteCd }
            ) {
                Icon(
                    imageVector = Icons.Rounded.Add,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(FinanceTheme.sizes.icon18)
                )
                Spacer(modifier = Modifier.width(FinanceTheme.spacing.xs))
                Text(
                    text = stringResource(R.string.notes_field_add_button),
                    style = FinanceTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.primary
                )
            }
        } else {
            OutlinedTextField(
                value = noteState.textFieldValue,
                onValueChange = { noteState.onValueChange(it) },
                label = { Text(label, style = FinanceTheme.typography.bodySmall) },
                placeholder = { Text(placeholder, style = FinanceTheme.typography.bodyMedium) },
                leadingIcon = {
                    Icon(
                        imageVector = Icons.Rounded.EditNote,
                        contentDescription = null,
                        tint = FinanceTheme.colors.onSurfaceVariant
                    )
                },
                trailingIcon = {
                    if (noteState.textFieldValue.text.isNotEmpty()) {
                        IconButton(
                            onClick = {
                                noteState.setExplicitText("")
                                isExpanded = false
                            },
                            modifier = Modifier.semantics { contentDescription = clearNoteCd }
                        ) {
                            Icon(
                                imageVector = Icons.Rounded.Close,
                                contentDescription = clearNoteCd,
                                tint = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                },
                supportingText = {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.End
                    ) {
                        Text(
                            text = "${noteState.textFieldValue.text.length}/${InputPolicy.MAX_NOTE_LENGTH}",
                            style = FinanceTheme.typography.bodySmall,
                            color = if (noteState.textFieldValue.text.length >= InputPolicy.MAX_NOTE_LENGTH) {
                                MaterialTheme.colorScheme.error
                            } else {
                                MaterialTheme.colorScheme.onSurfaceVariant
                            }
                        )
                    }
                },
                shape = FinanceShapes.card,
                keyboardOptions = KeyboardOptions(
                    capitalization = KeyboardCapitalization.Sentences,
                    imeAction = ImeAction.Done
                ),
                keyboardActions = KeyboardActions(onDone = { focusManager.clearFocus() }),
                modifier = Modifier
                    .fillMaxWidth()
                    .focusRequester(focusRequester)
                    .onFocusChanged { focusState ->
                        noteState.onFocusChanged(focusState.isFocused)
                        onFocusChanged(focusState.isFocused)
                    }
            )
        }
    }
}
