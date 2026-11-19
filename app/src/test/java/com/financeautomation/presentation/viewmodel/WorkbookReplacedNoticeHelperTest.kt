package com.financeautomation.presentation.viewmodel

import com.financeautomation.R
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class WorkbookReplacedNoticeHelperTest {

    @Test
    fun text_returnsExpectedResourceAndArgument() {
        val notice = WorkbookReplacedNotice.text()
        assertEquals(R.string.workbook_replaced_notice_partial, notice.resId)

        val firstArg = notice.args.firstOrNull() as? UiText
        assertNotNull(firstArg)
        assertEquals(R.string.workbook_replaced_lost_items, firstArg?.resId)
    }

    @Test
    fun isReplacedNotice_returnsTrueForMatchingNotice() {
        val notice = WorkbookReplacedNotice.text()
        assertTrue(WorkbookReplacedNotice.isReplacedNotice(notice))
    }

    @Test
    fun isReplacedNotice_returnsFalseForOtherNoticeOrNull() {
        assertFalse(WorkbookReplacedNotice.isReplacedNotice(null))
        assertFalse(WorkbookReplacedNotice.isReplacedNotice(UiText.dynamic("different message")))
        assertFalse(WorkbookReplacedNotice.isReplacedNotice(UiText.res(R.string.settings_msg_provisioned_workbook, "Finance2026")))
    }
}
