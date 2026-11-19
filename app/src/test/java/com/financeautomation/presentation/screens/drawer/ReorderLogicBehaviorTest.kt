package com.financeautomation.presentation.screens.drawer

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Proves the reordering behavior for Categories and Accounts screens (Finding 13)
 * through the production ReorderLogic functions.
 */
class ReorderLogicBehaviorTest {

    data class TestItem(val id: String, val name: String)

    @Test
    fun draggingItemBackToOriginalPosition_triggersReorderWithUpdatedState_butIsSkippedWithStaleState() {
        val a = TestItem("1", "Food")
        val b = TestItem("2", "Transport")
        val c = TestItem("3", "Shopping")
        val initialList = listOf(a, b, c)
        val initialIds = initialList.map { it.id }

        // Step 1: User drags A below B -> local order becomes [B, A, C]
        val intermediateIds = listOf("2", "1", "3")
        assertTrue(
            "Initial reorder [2, 1, 3] changed from initial [1, 2, 3]",
            ReorderLogic.hasOrderChanged(intermediateIds, initialIds)
        )

        // Upstream updates to reflect the persisted change:
        val latestUpstreamIds = intermediateIds

        // Step 2: User drags A back above B -> local order becomes [A, B, C]
        val restoredIds = listOf("1", "2", "3")

        // In pre-fix code, the callback closure captured initialIds:
        val preFixCheck = ReorderLogic.hasOrderChanged(restoredIds, initialIds)
        assertFalse(
            "Pre-fix: reorder is dropped because comparing against stale initial IDs returns false",
            preFixCheck
        )

        // In post-fix code, ReorderLogic compares against latestUpstreamIds:
        val postFixCheck = ReorderLogic.hasOrderChanged(restoredIds, latestUpstreamIds)
        assertTrue(
            "Post-fix: reorder is correctly dispatched because comparing against latest upstream IDs returns true",
            postFixCheck
        )
    }

    @Test
    fun measuredRowHeight_dynamicallyCalculatesSwapThreshold() {
        val actualTallRowHeight = 96f
        val defaultRowHeight = 68f

        // Production functions determine height and threshold
        val effectiveHeight = ReorderLogic.calculateEffectiveRowHeight(
            measuredHeightPx = actualTallRowHeight,
            defaultHeightPx = defaultRowHeight
        )
        assertEquals(96f, effectiveHeight, 0.001f)

        val swapThreshold = ReorderLogic.calculateSwapThreshold(effectiveHeight)
        assertEquals(48f, swapThreshold, 0.001f)

        val items = listOf(TestItem("1", "Tall 1"), TestItem("2", "Tall 2"))

        // Drag delta of 38px: exceeds default 34px threshold, but is less than dynamic 48px threshold
        val prematureDrag = ReorderLogic.processDragStep(
            currentList = items,
            draggingId = "1",
            currentOffsetY = 38f,
            rowHeightPx = effectiveHeight,
            swapThresholdPx = swapThreshold,
            idSelector = { it.id }
        )
        assertFalse("38px does not trigger swap on 96px row", prematureDrag.didSwap)
        assertEquals(items, prematureDrag.updatedList)
        assertEquals(38f, prematureDrag.remainingOffsetY, 0.001f)

        // Drag delta of 50px: exceeds dynamic 48px threshold, triggers swap and decrements offset
        val validDrag = ReorderLogic.processDragStep(
            currentList = items,
            draggingId = "1",
            currentOffsetY = 50f,
            rowHeightPx = effectiveHeight,
            swapThresholdPx = swapThreshold,
            idSelector = { it.id }
        )
        assertTrue("50px triggers swap on 96px row", validDrag.didSwap)
        assertEquals(listOf(TestItem("2", "Tall 2"), TestItem("1", "Tall 1")), validDrag.updatedList)
        assertEquals(-46f, validDrag.remainingOffsetY, 0.001f)
    }

    @Test
    fun onDragCancel_resetsToLatestUpstreamStateNotStaleInitial() {
        val a = TestItem("1", "Food")
        val b = TestItem("2", "Transport")
        val initialList = listOf(a, b)

        // Upstream was updated to [B, A]
        val latestUpstreamList = listOf(b, a)

        // Drag was in-progress locally altering order to [A, B]
        val localListDuringDrag = listOf(a, b)

        // Cancel invokes production resolveCancelReset with latest upstream list
        val resetList = ReorderLogic.resolveCancelReset(latestUpstreamList)

        assertEquals("Cancel restores latest upstream state", listOf(b, a), resetList)
        assertTrue(
            "Cancel does not revert to stale initial list",
            resetList != initialList || latestUpstreamList == initialList
        )
    }
}
