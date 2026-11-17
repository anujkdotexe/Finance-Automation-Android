package com.financeautomation.presentation.components

import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.interaction.DragInteraction
import androidx.compose.foundation.interaction.Interaction
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.focus.FocusManager
import androidx.compose.ui.platform.LocalFocusManager
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.map

/** True only for the start of a finger drag. Scrolls the sheet does by itself (keyboard opening) emit no drag. */
fun isUserDragStart(interaction: Interaction): Boolean = interaction is DragInteraction.Start

/** Emits once per finger drag that starts on a scroll container, given its interaction flow. */
fun userDragStarts(interactions: Flow<Interaction>): Flow<Unit> =
    interactions.filter { isUserDragStart(it) }.map { }

/**
 * Clears keyboard focus when the user drags the sheet, but not when the sheet scrolls itself to keep the focused
 * field visible. Using ScrollState.isScrollInProgress for this dropped the cursor as the keyboard opened.
 */
@Composable
fun ClearFocusOnUserDrag(scrollState: ScrollState, focusManager: FocusManager = LocalFocusManager.current) {
    LaunchedEffect(scrollState) {
        userDragStarts(scrollState.interactionSource.interactions).collect { focusManager.clearFocus() }
    }
}
