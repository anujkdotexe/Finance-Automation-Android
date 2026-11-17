package com.financeautomation.service

import com.financeautomation.domain.usecase.MonthBurnSummary
import com.financeautomation.domain.usecase.WidgetSyncState
import com.financeautomation.presentation.ui.MainRouteValidator
import com.financeautomation.service.worker.MonthBurnWidgetWorker
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.util.Locale

class MonthBurnWidgetTest {

    private val CRLF = String(charArrayOf(13.toChar(), 10.toChar()))
    private val LF = String(charArrayOf(10.toChar()))

    private val labels = MonthBurnWidgetLabels(
        title = "Living burn",
        perDayTemplate = "%1\$s a day",
        upToDate = "Up to date",
        waitingTemplate = "%1\$d waiting",
        descriptionTemplate = "%1\$s living burn %2\$s, %3\$s, %4\$s",
        signInToView = "Sign in to view"
    )

    private fun summary(burn: Double, perDay: Double, sync: WidgetSyncState = WidgetSyncState.UpToDate, month: Int = 11) =
        MonthBurnSummary(year = 2026, month = month, livingBurn = burn, perDayAverage = perDay, daysElapsed = 13, syncState = sync)

    private fun format(s: MonthBurnSummary) = MonthBurnWidgetFormatter.format(s, labels, Locale.US)

    @Test
    fun showsMonthNameTitleAmountAndPerDay() {
        val content = format(summary(12345.0, 949.6))
        assertEquals("November", content.monthName)
        assertEquals("Living burn", content.title)
        assertEquals("₹12,345", content.amount)
        assertEquals("₹949 a day", content.perDay)
    }

    @Test
    fun usesIndianGroupingForLargeAmounts() {
        assertEquals("₹1,23,456", format(summary(123456.0, 0.0)).amount)
        assertEquals("₹0", format(summary(0.0, 0.0)).amount)
        assertEquals("₹0 a day", format(summary(0.0, 0.0)).perDay)
    }

    @Test
    fun syncStateTextIsUpToDateOrWaiting() {
        assertEquals("Up to date", format(summary(1.0, 1.0)).syncState)
        assertEquals("3 waiting", format(summary(1.0, 1.0, WidgetSyncState.Waiting(3))).syncState)
    }

    @Test
    fun everyMonthHasItsFullName() {
        val names = (1..12).map { format(summary(1.0, 1.0, month = it)).monthName }
        assertEquals(
            listOf(
                "January", "February", "March", "April", "May", "June",
                "July", "August", "September", "October", "November", "December"
            ),
            names
        )
    }

    @Test
    fun theSpokenDescriptionCoversEverythingTheWidgetShows() {
        val content = format(summary(12345.0, 950.0, WidgetSyncState.Waiting(2)))
        assertEquals("November living burn ₹12,345, ₹950 a day, 2 waiting", content.description)
    }

    @Test
    fun signedOutShowsSignInTextAndNoFigure() {
        val content = MonthBurnWidgetFormatter.format(summary(0.0, 0.0), labels, Locale.US, unavailable = true)
        assertEquals("Sign in to view", content.amount)
        assertEquals("", content.perDay)
        assertEquals("", content.syncState)
        assertEquals("November", content.monthName)
        assertEquals("November Living burn, Sign in to view", content.description)
        assertFalse(content.amount.contains("₹"))
    }

    @Test
    fun availableSummaryStillShowsFiguresWhenFlagIsOff() {
        assertEquals("₹0", MonthBurnWidgetFormatter.format(summary(0.0, 0.0), labels, Locale.US, unavailable = false).amount)
    }

    // --- Manifest, widget info and wiring ----------------------------------------------------------------------

    private val manifest = File("src/main/AndroidManifest.xml").readText()

    @Test
    fun theAppHasExactlyTwoWidgets() {
        assertEquals(2, Regex("android.appwidget.provider\"").findAll(manifest).count())
        assertTrue(manifest.contains(".service.QuickLogWidgetProvider"))
        assertTrue(manifest.contains(".service.MonthBurnWidgetProvider"))
    }

    @Test
    fun theMonthBurnReceiverIsExportedWithOnlyTheStandardWidgetFilter() {
        val receiver = manifest.substringAfter("android:name=\".service.MonthBurnWidgetProvider\"").substringBefore("</receiver>")
        assertTrue(receiver.contains("android:exported=\"true\""))
        val actions = Regex("<action android:name=\"([^\"]+)\"").findAll(receiver).map { it.groupValues[1] }.toList()
        assertEquals(listOf("android.appwidget.action.APPWIDGET_UPDATE"), actions)
        assertFalse(receiver.contains("<category"))
        assertFalse(receiver.contains("<data"))
    }

    @Test
    fun theWidgetInfoIsTwoByOneResizableHorizontallyWithAPreview() {
        val info = File("src/main/res/xml/month_burn_widget_info.xml").readText()
        assertTrue(info.contains("android:targetCellWidth=\"2\""))
        assertTrue(info.contains("android:targetCellHeight=\"1\""))
        assertTrue(info.contains("android:resizeMode=\"horizontal\""))
        assertTrue(info.contains("android:previewImage=\"@drawable/widget_month_burn_preview\""))
        assertTrue(File("src/main/res/drawable/widget_month_burn_preview.xml").exists())
        assertTrue(info.contains("android:updatePeriodMillis=\"0\""))
    }

    @Test
    fun theCardUsesOneFixedColourPairForLightAndDarkWallpapers() {
        val colors = File("src/main/res/values/colors.xml").readText()
        assertTrue(colors.contains("widget_month_card"))
        assertFalse(
            "no night variant, the pair must not change with the theme",
            File("src/main/res/values-night/colors.xml").readText().contains("widget_month")
        )
        val background = File("src/main/res/drawable/widget_month_burn_background.xml").readText()
        assertTrue(background.contains("<corners"))
    }

    @Test
    fun tappingOpensTheDashboardThroughAWhitelistedRoute() {
        assertEquals("dashboard", MonthBurnWidgetProvider.OPEN_ROUTE)
        assertEquals("extra_route", MonthBurnWidgetProvider.ROUTE_EXTRA)
        assertEquals("dashboard", MainRouteValidator.validateRoute(MonthBurnWidgetProvider.OPEN_ROUTE))
    }

    @Test
    fun theRefreshRunsEveryThirtyMinutesWithoutANetworkConstraint() {
        assertEquals(30L, MonthBurnWidgetWorker.REPEAT_MINUTES)
        val source = File("src/main/java/com/financeautomation/service/worker/MonthBurnWidgetWorker.kt").readText()
        assertFalse(source.contains("setRequiredNetworkType"))
        assertFalse(source.contains("setConstraints"))
    }

    @Test
    fun theWidgetRefreshesAfterEverySync() {
        val container = File("src/main/java/com/financeautomation/di/AppContainer.kt").readText()
        assertTrue(container.contains("impl.onSyncCompleted"))
        assertTrue(container.contains("MonthBurnWidgetProvider.requestUpdate"))
        val repo = File("src/main/java/com/financeautomation/data/repository/TransactionRepositoryImpl.kt").readText().replace(CRLF, LF)
        assertTrue("called in a finally block so failed syncs refresh the waiting count too", repo.contains("finally {\n            try {\n                onSyncCompleted"))
    }
}
