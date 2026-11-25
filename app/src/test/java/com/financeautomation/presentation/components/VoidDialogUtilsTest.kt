package com.financeautomation.presentation.components

import com.financeautomation.R
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Tests the pure function selecting void confirmation dialog wording.
 */
class VoidDialogUtilsTest {

    @Test
    fun voidConfirmMessage_whenDeleteFromSheetChecked_returnsDeleteMessageResId() {
        val resId = VoidDialogUtils.voidConfirmMessageResId(deleteSheetRow = true)
        assertEquals(R.string.confirm_void_delete_message, resId)
    }

    @Test
    fun voidConfirmMessage_whenDeleteFromSheetNotChecked_returnsStandardVoidMessageResId() {
        val resId = VoidDialogUtils.voidConfirmMessageResId(deleteSheetRow = false)
        assertEquals(R.string.confirm_void_message, resId)
    }
}
