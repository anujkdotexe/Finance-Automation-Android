package com.financeautomation.presentation.theme

import com.financeautomation.domain.model.ThemeMode
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ThemeResolutionTest {

    @Test
    fun `saved light wins on a dark phone`() {
        assertFalse(resolveDarkTheme(ThemeMode.LIGHT, systemDark = true))
    }

    @Test
    fun `saved dark wins on a light phone`() {
        assertTrue(resolveDarkTheme(ThemeMode.DARK, systemDark = false))
    }

    @Test
    fun `system follows the phone in both directions`() {
        assertTrue(resolveDarkTheme(ThemeMode.SYSTEM, systemDark = true))
        assertFalse(resolveDarkTheme(ThemeMode.SYSTEM, systemDark = false))
    }

    @Test
    fun `every mode resolves on a dark phone as expected`() {
        val result = ThemeMode.values().associateWith { resolveDarkTheme(it, systemDark = true) }
        assertFalse(result.getValue(ThemeMode.LIGHT))
        assertTrue(result.getValue(ThemeMode.DARK))
        assertTrue(result.getValue(ThemeMode.SYSTEM))
    }
}
