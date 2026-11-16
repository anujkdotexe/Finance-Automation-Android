package com.financeautomation.core.utils

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class CrashSanitizerTest {

    @Test
    fun messageIsDroppedAndClassNameAndFramesAreKept() {
        val original = IllegalStateException("Paid Rs 450 to Rahul from account 1234")
        val sanitized = CrashSanitizer.sanitize(original)
        assertEquals("java.lang.IllegalStateException", sanitized.message)
        assertFalse(sanitized.toString().contains("Rahul"))
        assertArrayEquals(original.stackTrace, sanitized.stackTrace)
    }

    @Test
    fun causeChainKeepsOnlyClassNames() {
        val original = RuntimeException("outer secret", IllegalArgumentException("inner secret 987654"))
        val sanitized = CrashSanitizer.sanitize(original)
        val cause = sanitized.cause
        assertNotNull(cause)
        assertEquals("java.lang.IllegalArgumentException", cause!!.message)
        assertFalse(sanitized.toString().contains("secret"))
        assertFalse(cause.toString().contains("secret"))
    }

    @Test
    fun selfReferencingCauseDoesNotLoop() {
        val original = RuntimeException("x")
        val sanitized = CrashSanitizer.sanitize(original)
        assertNull(sanitized.cause)
    }
}
