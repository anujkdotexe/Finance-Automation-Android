package com.financeautomation.presentation.theme

import com.financeautomation.domain.model.ThemeMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SavedThemeTest {

    @Test
    fun `nothing is drawn while the saved theme is still loading`() {
        assertNull(resolveSavedDark(SavedTheme.Loading, systemDark = true))
        assertNull(resolveSavedDark(SavedTheme.Loading, systemDark = false))
    }

    @Test
    fun `loaded light on a dark phone resolves to light`() {
        assertEquals(false, resolveSavedDark(SavedTheme.Loaded(ThemeMode.LIGHT), systemDark = true))
        assertEquals(true, resolveSavedDark(SavedTheme.Loaded(ThemeMode.DARK), systemDark = false))
        assertEquals(true, resolveSavedDark(SavedTheme.Loaded(ThemeMode.SYSTEM), systemDark = true))
    }

    @Test
    fun `inside the app theme the app flag wins over the phone`() {
        assertEquals(false, resolveInheritedDark(appDark = false, systemDark = true))
        assertEquals(true, resolveInheritedDark(appDark = true, systemDark = false))
    }

    @Test
    fun `outside an app theme the phone flag is used`() {
        assertEquals(true, resolveInheritedDark(appDark = null, systemDark = true))
    }

    @Test
    fun `category colours use the light palette when the app theme is light on a dark phone`() {
        val isDark = resolveInheritedDark(appDark = false, systemDark = true)
        val token = categoryColorToken("Groceries")
        assertEquals(token.light, categoryDotColor("Groceries", isDark))
        assertNotEquals(token.dark, categoryDotColor("Groceries", isDark))
        assertEquals(CategoryColorTokens.PALETTE.map { it.light }, categoryPaletteColors(isDark))
        assertEquals(CategoryColorTokens.ALL[2].light, categoryDotColorByIndex(2, isDark))
    }
}
