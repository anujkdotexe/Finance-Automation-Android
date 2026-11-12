package com.financeautomation.core

import com.financeautomation.core.utils.accountInitial
import org.junit.Assert.assertEquals
import org.junit.Test

class AccountInitialTest {

    @Test
    fun testFirstLetterIsUppercased() {
        assertEquals("A", accountInitial("anuj@example.com"))
        assertEquals("Z", accountInitial("Zed@example.com"))
    }

    @Test
    fun testBlankOrNullGivesQuestionMark() {
        assertEquals("?", accountInitial(null))
        assertEquals("?", accountInitial(""))
        assertEquals("?", accountInitial("   "))
    }

    @Test
    fun testLeadingWhitespaceIsIgnored() {
        assertEquals("M", accountInitial("  me@example.com"))
    }
}
