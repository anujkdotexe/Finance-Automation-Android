package com.financeautomation.core.utils

import org.junit.Assert.assertEquals
import org.junit.Test

class PayableFormatUtilsTest {

    @Test
    fun testBuildEffectiveWhatFor_blankCategory_returnsWhatFor() {
        assertEquals("Dinner", PayableFormatUtils.buildEffectiveWhatFor("Dinner", ""))
        assertEquals("Dinner", PayableFormatUtils.buildEffectiveWhatFor("Dinner", "   "))
    }

    @Test
    fun testBuildEffectiveWhatFor_blankWhatFor_returnsCategory() {
        assertEquals("Food & Drink", PayableFormatUtils.buildEffectiveWhatFor("", "Food & Drink"))
        assertEquals("Food & Drink", PayableFormatUtils.buildEffectiveWhatFor("   ", "Food & Drink"))
    }

    @Test
    fun testBuildEffectiveWhatFor_bothProvided_appendsCategoryInParentheses() {
        assertEquals("Dinner with team (Food & Drink)", PayableFormatUtils.buildEffectiveWhatFor("Dinner with team", "Food & Drink"))
        assertEquals("Cab ride (Transport)", PayableFormatUtils.buildEffectiveWhatFor("Cab ride", "Transport"))
    }

    @Test
    fun testBuildEffectiveWhatFor_alreadyContainsCategory_doesNotDuplicate() {
        assertEquals("Food & Drink dinner", PayableFormatUtils.buildEffectiveWhatFor("Food & Drink dinner", "Food & Drink"))
        assertEquals("Dinner (food & drink)", PayableFormatUtils.buildEffectiveWhatFor("Dinner (food & drink)", "Food & Drink"))
    }
}
