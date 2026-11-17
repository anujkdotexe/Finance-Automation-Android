package com.financeautomation.data.repository

import com.financeautomation.core.model.Result
import com.financeautomation.domain.model.ConfigRow
import com.financeautomation.testutil.FakeConfigSheet
import com.financeautomation.testutil.MutableTestClock
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant

/** ConfigRepositoryImpl against the real GoogleSheetsClient talking to an in-memory Config tab. */
class ConfigRepositoryImplTest {

    private val now = Instant.parse("2026-11-17T10:00:00Z")
    private val clock = MutableTestClock(now)

    private fun repo(sheet: FakeConfigSheet) =
        ConfigRepositoryImpl(sheet.client(), ioDispatcher = Dispatchers.Unconfined, clock = clock)

    private fun categoryRow(updatedAt: Instant, value1: String = "false") =
        ConfigRow("category", "Groceries", listOf(value1, "false", "false", "1|Teal"), updatedAt, "dev", false)

    private fun <T> Result<T>.ok(): T = (this as Result.Success).data

    @Test
    fun readAllClampsFutureUpdatedAt() = runBlocking {
        val sheet = FakeConfigSheet()
        sheet.addRow("category", "Groceries", "false", "false", "false", "1|Teal", "2099-01-01T00:00:00Z", "dev", "")
        sheet.addRow("category", "Fuel", "false", "false", "false", "2|Teal", "2026-11-17T10:03:00Z", "dev", "")
        sheet.addRow("category", "Rent", "false", "false", "false", "3|Teal", "2026-11-17T09:00:00Z", "dev", "")

        val rows = repo(sheet).readAll("sheet").ok().associateBy { it.key }

        assertEquals("a 2099 stamp is cut to now plus 5 minutes", now.plusSeconds(300), rows.getValue("Groceries").updatedAt)
        assertEquals("a stamp inside the skew is kept", Instant.parse("2026-11-17T10:03:00Z"), rows.getValue("Fuel").updatedAt)
        assertEquals("a past stamp is kept", Instant.parse("2026-11-17T09:00:00Z"), rows.getValue("Rent").updatedAt)
    }

    @Test
    fun upsertReportsSkippedRowWhenRemoteIsNewer() = runBlocking {
        val sheet = FakeConfigSheet()
        sheet.addRow("category", "Groceries", "true", "false", "false", "1|Teal", "2026-11-17T09:00:00Z", "other", "")

        val written = repo(sheet).upsert(
            "sheet",
            listOf(
                categoryRow(Instant.parse("2026-11-17T08:00:00Z"), value1 = "false"),
                ConfigRow("category", "Fuel", listOf("false", "false", "false", "2|Teal"), now, "dev", false)
            )
        ).ok()

        assertEquals("only the row that reached the sheet is reported", setOf("category|fuel"), written)
        val groceries = sheet.cells[sheet.indexOfKey("category", "Groceries")]
        assertEquals("the newer remote row is untouched", "true", groceries[2])
        assertEquals("other", groceries[7])
        assertTrue(sheet.indexOfKey("category", "Fuel") > 0)
    }

    @Test
    fun upsertReportsAnUpdateOfAnOlderRemoteRow() = runBlocking {
        val sheet = FakeConfigSheet()
        sheet.addRow("category", "Groceries", "true", "false", "false", "1|Teal", "2026-11-17T08:00:00Z", "other", "")

        val written = repo(sheet).upsert("sheet", listOf(categoryRow(Instant.parse("2026-11-17T09:00:00Z"), "false"))).ok()

        assertEquals(setOf("category|groceries"), written)
        assertEquals("false", sheet.cells[sheet.indexOfKey("category", "Groceries")][2])
    }

    @Test
    fun aFutureDatedSheetRowDoesNotBlockALaterLocalWrite() = runBlocking {
        val sheet = FakeConfigSheet()
        sheet.addRow("category", "Groceries", "true", "false", "false", "1|Teal", "2099-01-01T00:00:00Z", "other", "")
        clock.set(now.plusSeconds(600))

        val written = repo(sheet).upsert("sheet", listOf(categoryRow(clock.instant(), "false"))).ok()

        assertEquals(setOf("category|groceries"), written)
        val row = sheet.cells[sheet.indexOfKey("category", "Groceries")]
        assertEquals("false", row[2])
        assertEquals("the bad stamp is replaced by the real edit time", clock.instant().toString(), row[6])
    }
}
