package com.financeautomation.presentation.components

import androidx.compose.foundation.interaction.DragInteraction
import androidx.compose.foundation.interaction.FocusInteraction
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.PressInteraction
import androidx.compose.ui.geometry.Offset
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class ClearFocusOnUserDragTest {

    @Test
    fun onlyAFingerDragStartClearsFocus() {
        assertTrue(isUserDragStart(DragInteraction.Start()))
        val start = DragInteraction.Start()
        assertFalse(isUserDragStart(DragInteraction.Stop(start)))
        assertFalse(isUserDragStart(DragInteraction.Cancel(start)))
        assertFalse(isUserDragStart(FocusInteraction.Focus()))
        assertFalse(isUserDragStart(PressInteraction.Press(Offset.Zero)))
    }

    @Test
    fun interactionStreamYieldsOneSignalPerDragAndNoneForOtherEvents() = runTest(UnconfinedTestDispatcher()) {
        val source = MutableInteractionSource()
        val signals = mutableListOf<Unit>()
        val job = launch { userDragStarts(source.interactions).toList(signals) }

        source.emit(FocusInteraction.Focus())
        val first = DragInteraction.Start()
        source.emit(first)
        source.emit(DragInteraction.Stop(first))
        source.emit(DragInteraction.Start())

        assertEquals(2, signals.size)
        job.cancel()
    }
}
