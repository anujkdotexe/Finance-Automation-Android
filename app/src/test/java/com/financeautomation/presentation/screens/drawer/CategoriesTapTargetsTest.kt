package com.financeautomation.presentation.screens.drawer

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** Source level checks for the Categories row: two tap areas, each at least 48dp, and no exported component added. */
class CategoriesTapTargetsTest {

    private val source = File("src/main/java/com/financeautomation/presentation/screens/drawer/CategoriesScreen.kt").readText()
    private val manifest = File("src/main/AndroidManifest.xml").readText()

    @Test
    fun theWholeRowOpensTheEditDialogAndTheBudgetTextOpensTheBudgetSheet() {
        assertTrue(source.contains("selectedCategoryForEdit = category"))
        assertTrue(source.contains("onOpenBudget = { budgetCategory = category.name }"))
        assertEquals(1, Regex("\\.clickable\\(enabled = !isLocked, onClick = onClick\\)").findAll(source).count())
        assertEquals(1, Regex("\\.clickable\\(onClickLabel = editDescription, onClick = onOpenBudget\\)").findAll(source).count())
    }

    @Test
    fun rowAndBudgetTextAreAtLeastFortyEightDpInBothDirections() {
        assertEquals(48, Regex("val minTouchTarget: Dp = (\\d+)\\.dp").find(
            File("src/main/java/com/financeautomation/presentation/theme/Dimensions.kt").readText()
        )!!.groupValues[1].toInt())
        val budgetText = source.substringAfter("budget_row_none").substringBefore("clickable(onClickLabel")
        assertTrue(budgetText.contains("minWidth = FinanceTheme.sizes.minTouchTarget"))
        assertTrue(budgetText.contains("minHeight = FinanceTheme.sizes.minTouchTarget"))
        assertTrue(source.contains(".defaultMinSize(minHeight = FinanceTheme.sizes.minTouchTarget)"))
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
