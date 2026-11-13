package com.financeautomation.presentation.theme

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class CategoryColorsTest {

    @Test
    fun standardCategoriesMapToSpecificTokens() {
        assertEquals("Amber", categoryColorToken("Food & Drink").name)
        assertEquals("Amber", categoryColorToken("food and drink").name)
        assertEquals("Amber", categoryColorToken("Dining Out").name)
        assertEquals("Emerald", categoryColorToken("Groceries").name)
        assertEquals("Sky", categoryColorToken("Transit").name)
        assertEquals("Purple", categoryColorToken("Subscriptions").name)
        assertEquals("Rose", categoryColorToken("Shopping").name)
        assertEquals("Cyan", categoryColorToken("Trip/Travel").name)
        assertEquals("Cyan", categoryColorToken("travel").name)
        assertEquals("Teal", categoryColorToken("Health").name)
        assertEquals("Indigo", categoryColorToken("Family").name)
        assertEquals("Lime", categoryColorToken("Savings").name)
        assertEquals("Slate", categoryColorToken("Other").name)
        assertEquals("Slate", categoryColorToken("Transfer").name)
    }

    @Test
    fun mappingIsCaseAndWhitespaceInsensitive() {
        assertEquals(categoryColorToken("Food & Drink"), categoryColorToken("  food & drink  "))
        assertEquals(categoryColorToken("GROCERIES"), categoryColorToken("groceries"))
        assertEquals(categoryColorToken("SAVINGS"), categoryColorToken("Savings"))
    }

    @Test
    fun customCategoriesUseStableFallback() {
        val custom1 = "Home Maintenance"
        val custom2 = "Pet Expenses"
        val custom3 = "Charity"

        val token1a = categoryColorToken(custom1)
        val token1b = categoryColorToken(custom1)
        assertEquals("Custom category must map deterministically", token1a, token1b)

        val token2 = categoryColorToken(custom2)
        assertNotNull(token2)
        assertTrue(CategoryColorTokens.ALL.contains(token2))

        val token3 = categoryColorToken(custom3)
        assertNotNull(token3)
        assertTrue(CategoryColorTokens.ALL.contains(token3))
    }

    @Test
    fun lightAndDarkVariantsAreDistinct() {
        CategoryColorTokens.ALL.forEach { token ->
            assertFalse(
                "Light and dark colors for ${token.name} should be distinct for proper theme adaptation",
                token.light == token.dark
            )
        }
    }

    @Test
    fun paletteColorsMatchExpectedSubset() {
        val lightPalette = categoryPaletteColors(isDark = false)
        val darkPalette = categoryPaletteColors(isDark = true)
        assertEquals(7, lightPalette.size)
        assertEquals(7, darkPalette.size)
        assertEquals(CategoryColorTokens.Teal.light, lightPalette[0])
        assertEquals(CategoryColorTokens.Teal.dark, darkPalette[0])
    }

    @Test
    fun noRawColorLiteralsForCategoryDotsOutsideThemeFolder() {
        val base = "src/main/java/com/financeautomation/presentation/"
        val categoriesScreen = File(base + "screens/drawer/CategoriesScreen.kt").readText()
        val addCategorySheet = File(base + "screens/drawer/AddCategorySheet.kt").readText()

        assertFalse(categoriesScreen.contains("Color(0x"))
        assertFalse(addCategorySheet.contains("Color(0x"))
        assertTrue(categoriesScreen.contains("categoryDotColor("))
        assertTrue(addCategorySheet.contains("categoryPaletteColors("))
    }
}
