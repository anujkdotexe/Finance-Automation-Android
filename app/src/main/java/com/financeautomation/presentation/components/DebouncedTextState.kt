package com.financeautomation.presentation.components

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * State holder that manages a [TextFieldValue] in Compose state to prevent character
 * reordering races between fast typing and asynchronous ViewModel state updates.
 */
class DebouncedTextState(
    initialText: String,
    private val debounceMs: Long = 0L,
    private val coroutineScope: CoroutineScope,
    private val cleanText: (String) -> String = { it },
    private val onValueChanged: (String) -> Unit
) {
    var textFieldValue by mutableStateOf(
        TextFieldValue(initialText, selection = TextRange(initialText.length))
    )
        private set

    var isFocused by mutableStateOf(false)
        private set

    private var lastSentText: String = initialText
    private var debounceJob: Job? = null

    fun onFocusChanged(focused: Boolean) {
        isFocused = focused
    }

    fun onValueChange(newValue: TextFieldValue) {
        val cleaned = cleanText(newValue.text)
        val adjustedValue = if (cleaned == newValue.text) {
            newValue
        } else {
            val selStart = minOf(newValue.selection.start, cleaned.length)
            val selEnd = minOf(newValue.selection.end, cleaned.length)
            newValue.copy(text = cleaned, selection = TextRange(selStart, selEnd))
        }
        textFieldValue = adjustedValue

        if (debounceMs <= 0L) {
            lastSentText = cleaned
            onValueChanged(cleaned)
        } else {
            debounceJob?.cancel()
            debounceJob = coroutineScope.launch {
                delay(debounceMs)
                lastSentText = cleaned
                onValueChanged(cleaned)
            }
        }
    }

    fun updateFromExternal(externalText: String) {
        if (!isFocused && externalText != lastSentText) {
            lastSentText = externalText
            textFieldValue = TextFieldValue(externalText, selection = TextRange(externalText.length))
        }
    }

    fun setExplicitText(text: String) {
        debounceJob?.cancel()
        val cleaned = cleanText(text)
        lastSentText = cleaned
        textFieldValue = TextFieldValue(cleaned, selection = TextRange(cleaned.length))
        onValueChanged(cleaned)
    }
}

@Composable
fun rememberDebouncedTextState(
    value: String,
    debounceMs: Long = 0L,
    cleanText: (String) -> String = { it },
    onValueChanged: (String) -> Unit
): DebouncedTextState {
    val scope = rememberCoroutineScope()
    val state = remember {
        DebouncedTextState(
            initialText = value,
            debounceMs = debounceMs,
            coroutineScope = scope,
            cleanText = cleanText,
            onValueChanged = onValueChanged
        )
    }

    LaunchedEffect(value) {
        state.updateFromExternal(value)
    }

    return state
}
