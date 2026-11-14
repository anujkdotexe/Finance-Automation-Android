package com.financeautomation.presentation.components

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** Buttons grow with the font size and chips keep a 48dp touch target around a 32dp look. */
class ControlSizingTest {

    private val buttons = File("src/main/java/com/financeautomation/presentation/components/Buttons.kt").readText().replace("\r\n", "\n")
    private val chips = File("src/main/java/com/financeautomation/presentation/components/ChoiceChips.kt").readText().replace("\r\n", "\n")
    private fun body(name: String): String =
        buttons.substringAfter("fun $name(").substringBefore("\n}\n")

    @Test
    fun filledAndOutlinedButtonsHaveAMinimumHeightNotAFixedOne() {
        for (name in listOf("FilledButton", "OutlinedButton")) {
            val b = body(name)
            assertFalse("$name has a fixed height", b.contains(".height(FinanceTheme.sizes.buttonHeight)"))
            assertTrue(b.contains(".defaultMinSize(minHeight = FinanceTheme.sizes.buttonHeight)"))
        }
    }

    @Test
    fun buttonLabelsWrapToTwoLinesAndNeverEndInAnEllipsis() {
        for (name in listOf("FilledButton", "OutlinedButton")) {
            val b = body(name)
            assertTrue("$name allows two lines", b.contains("maxLines = 2"))
            assertFalse("$name cuts its label", b.contains("TextOverflow.Ellipsis"))
            assertFalse(b.contains("softWrap = false"))
        }
    }

    @Test
    fun chipsAre48dpToTouchAnd32dpToLookAt() {
        val chip = chips.substringAfter("fun AppChip(").substringBefore("\n}\n")
        assertTrue(chip.contains("defaultMinSize(minHeight = FinanceTheme.sizes.minTouchTarget)"))
        assertTrue(chip.contains(".height(FinanceTheme.sizes.chipHeight)"))
        assertEquals("the click is on the large box", 1, Regex("clickable\\(").findAll(chip).count())
        assertTrue(chip.indexOf("clickable(") < chip.indexOf(".height(FinanceTheme.sizes.chipHeight)"))
    }
}
