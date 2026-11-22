package com.financeautomation.presentation.screens

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** The unconfirmed caption shows only when rows are waiting, uses string resources and opens Needs Review. */
class UnconfirmedCaptionTest {

    private val screen = File("src/main/java/com/financeautomation/presentation/screens/DashboardScreen.kt").readText()
    private val strings = File("src/main/res/values/strings.xml").readText()

    @Test
    fun shownOnlyWhenTheCountIsAboveZeroAndOpensReview() {
        assertTrue(screen.contains("if (state.unconfirmedCount > 0)"))
        assertTrue(screen.contains("onClick = onNavigateToReview"))
        assertTrue(screen.contains("R.plurals.dashboard_unconfirmed_rows"))
    }

    @Test
    fun textsHaveFormatArgumentsInStrings() {
        assertTrue(strings.contains("<plurals name=\"dashboard_unconfirmed_rows\">"))
        assertTrue(strings.contains("%1\$d unconfirmed rows, %2\$s not counted"))
        assertTrue(strings.contains("%1\$d unconfirmed row, %2\$s not counted"))
    }
}
