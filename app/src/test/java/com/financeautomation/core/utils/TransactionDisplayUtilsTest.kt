package com.financeautomation.core.utils

import org.junit.Assert.assertEquals
import org.junit.Test

class TransactionDisplayUtilsTest {

    @Test
    fun testStripVoidPrefix_withLeadingPrefix() {
        assertEquals("Coffee", stripVoidPrefix("[Void] Coffee"))
        assertEquals("Coffee", stripVoidPrefix("[void] Coffee"))
        assertEquals("Coffee", stripVoidPrefix("[VOID] Coffee"))
        assertEquals("Dinner with friends", stripVoidPrefix("  [Void]   Dinner with friends  "))
        assertEquals("Lunch", stripVoidPrefix("[Void]Lunch"))
    }

    @Test
    fun testStripVoidPrefix_withoutPrefix() {
        assertEquals("Coffee Shop", stripVoidPrefix("Coffee Shop"))
        assertEquals("Dinner", stripVoidPrefix("Dinner"))
        assertEquals("Supermarket 123", stripVoidPrefix("Supermarket 123"))
    }

    @Test
    fun testStripVoidPrefix_withPrefixInsideName_isPreserved() {
        // [Void] inside the name must NOT be stripped
        assertEquals("Coffee [Void] Shop", stripVoidPrefix("Coffee [Void] Shop"))
        assertEquals("Shop [void] Central", stripVoidPrefix("Shop [void] Central"))
    }

    @Test
    fun testStripVoidPrefix_emptyOrNull() {
        assertEquals("", stripVoidPrefix(null))
        assertEquals("", stripVoidPrefix(""))
        assertEquals("", stripVoidPrefix("   "))
        assertEquals("", stripVoidPrefix("[Void]"))
    }
}
