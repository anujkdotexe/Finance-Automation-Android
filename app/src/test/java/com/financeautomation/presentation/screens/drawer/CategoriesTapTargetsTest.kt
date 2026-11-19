package com.financeautomation.presentation.screens.drawer

import androidx.compose.ui.unit.dp
import com.financeautomation.core.constants.CategoryConstants
import com.financeautomation.presentation.theme.FinanceSizes
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** Behavioral checks for category constants, min touch target tokens, and AndroidManifest export boundaries. */
class CategoriesTapTargetsTest {

    private val manifest = File("src/main/AndroidManifest.xml").readText()

    @Test
    fun protectedCategoriesAreLockedFromRenameOrArchive() {
        assertTrue(CategoryConstants.isProtectedCategory("Savings"))
        assertTrue(CategoryConstants.isProtectedCategory("Transfer"))
        assertTrue(CategoryConstants.isProtectedCategory("Other"))
        assertTrue(CategoryConstants.isProtectedCategory("Subscriptions"))
        assertFalse(CategoryConstants.isProtectedCategory("Food & Drink"))
        assertFalse(CategoryConstants.isProtectedCategory("Transit"))
        assertFalse(CategoryConstants.isProtectedCategory("Custom User Category"))
        assertFalse(CategoryConstants.isProtectedCategory("Shopping"))
    }

    @Test
    fun rowAndBudgetTextAreAtLeastFortyEightDpInBothDirections() {
        assertEquals(48.dp, FinanceSizes.minTouchTarget)
        assertTrue(FinanceSizes.minRowHeight >= 48.dp)
        assertTrue(FinanceSizes.accountRowHeight >= 48.dp)
    }

    @Test
    fun exportedComponentsAreExactlyTheKnownList() {
        val names = Regex("<(activity|activity-alias|service|receiver|provider)\\b(.*?)>", RegexOption.DOT_MATCHES_ALL)
            .findAll(manifest)
            .filter { it.groupValues[2].contains("android:exported=\"true\"") }
            .map { Regex("android:name=\"([^\"]+)\"").find(it.groupValues[2])!!.groupValues[1] }
            .toList()
        assertEquals(
            listOf(
                ".presentation.ui.MainActivity",
                ".MainActivity",
                ".presentation.ui.EntryActivity",
                ".service.NotificationListenerServiceImpl",
                ".service.QuickSettingsTileService",
                ".service.QuickLogWidgetProvider",
                ".service.MonthBurnWidgetProvider"
            ),
            names
        )
    }

    @Test
    fun theFileProviderIsNotExportedAndOnlyServesTheExportsFolder() {
        val provider = manifest.substringAfter("androidx.core.content.FileProvider").substringBefore("</provider>")
        assertTrue(provider.contains("android:exported=\"false\""))
        assertTrue(provider.contains("android:grantUriPermissions=\"true\""))
        val paths = File("src/main/res/xml/file_paths.xml").readText()
        assertEquals(1, Regex("<[a-z-]+-path\\b").findAll(paths).count())
        assertTrue(paths.contains("path=\"exports/\""))
    }
}
