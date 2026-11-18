package com.financeautomation.presentation.screens

import androidx.compose.ui.graphics.Color
import com.financeautomation.domain.model.Category
import com.financeautomation.domain.usecase.InsightsSlice
import com.financeautomation.presentation.theme.CategoryColorTokens
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

/**
 * Tests sliceColor in InsightsScreen:
 * - A category with a custom colour uses its theme token (light & dark variants).
 * - A category with "No colour" uses neutral theme grey (CategoryColorTokens.None).
 * - An unknown or unmatched category (e.g. Transfer) falls back to the old palette by index.
 * - Grouped "Other" slice uses the last palette colour.
 */
class InsightsSliceColorTest {

    private val testPalette = listOf(
        Color(0xFF111111),
        Color(0xFF222222),
        Color(0xFF333333),
        Color(0xFF444444),
        Color(0xFF555555),
        Color(0xFF999999) // outline / other
    )

    @Test
    fun categoryWithCustomColorUsesCustomTokenVariant() {
        val cat = Category(
            id = "cat_1",
            name = "Dining",
            isDefault = false,
            isExcludedFromLivingBurn = false,
            isArchived = false,
            displayOrder = 0,
            iconName = "Rose"
        )
        val slice = InsightsSlice(category = "Dining", amount = 1500.0, percent = 60.0)

        val colorLight = sliceColor(testPalette, index = 0, slice = slice, categories = listOf(cat), isDark = false)
        val colorDark = sliceColor(testPalette, index = 0, slice = slice, categories = listOf(cat), isDark = true)

        assertEquals("Light mode must use Rose light token", CategoryColorTokens.Rose.light, colorLight)
        assertEquals("Dark mode must use Rose dark token", CategoryColorTokens.Rose.dark, colorDark)
        assertNotEquals("Light and dark colors must be distinct", colorLight, colorDark)
    }

    @Test
    fun categoryWithNoColorUsesNeutralThemeGrey() {
        val cat = Category(
            id = "cat_2",
            name = "Shopping",
            isDefault = false,
            isExcludedFromLivingBurn = false,
            isArchived = false,
            displayOrder = 1,
            iconName = CategoryColorTokens.NO_COLOR
        )
        val slice = InsightsSlice(category = "Shopping", amount = 800.0, percent = 30.0)

        val colorLight = sliceColor(testPalette, index = 1, slice = slice, categories = listOf(cat), isDark = false)
        val colorDark = sliceColor(testPalette, index = 1, slice = slice, categories = listOf(cat), isDark = true)

        assertEquals("No colour in light mode must use None light neutral grey", CategoryColorTokens.None.light, colorLight)
        assertEquals("No colour in dark mode must use None dark neutral grey", CategoryColorTokens.None.dark, colorDark)
    }

    @Test
    fun unknownCategoryFallsBackToPaletteByIndex() {
        val slice = InsightsSlice(category = "Transfer", amount = 300.0, percent = 10.0)

        val color = sliceColor(testPalette, index = 2, slice = slice, categories = emptyList(), isDark = false)
        assertEquals("Unmatched category must fall back to palette by index", testPalette[2], color)
    }

    @Test
    fun otherSliceUsesPaletteLast() {
        val slice = InsightsSlice(category = "Other", amount = 100.0, percent = 5.0, isOther = true)

        val color = sliceColor(testPalette, index = 0, slice = slice, categories = emptyList(), isDark = false)
        assertEquals("Grouped other slice must use palette.last()", testPalette.last(), color)
    }
}
