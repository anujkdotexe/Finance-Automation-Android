package com.financeautomation.data.repository

import com.financeautomation.core.model.Result
import com.financeautomation.domain.model.ConfigRow
import com.financeautomation.testutil.FakeConfigSheet
import com.financeautomation.testutil.MutableTestClock
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import com.financeautomation.core.utils.Diagnostics
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

    @Before
    fun clearDiagnostics() {
        Diagnostics.clear()
    }

    private fun diagnostic(code: String) = Diagnostics.getRecords().any { it.reason.contains(code) }

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

    @Test
    fun readAllKeepsNewestOfDuplicateKeys() = runBlocking {
        val sheet = FakeConfigSheet()
        sheet.addRow("category", "Food", "false", "false", "false", "1|Teal", "2026-11-17T08:00:00Z", "a", "")
        sheet.addRow("category", "Fuel", "false", "false", "false", "2|Teal", "2026-11-17T08:00:00Z", "a", "")
        sheet.addRow("category", "food ", "true", "false", "false", "1|Teal", "2026-11-17T09:00:00Z", "b", "")
        sheet.addRow("category", "FOOD", "false", "false", "false", "1|Teal", "2026-11-17T07:00:00Z", "c", "")

        val rows = repo(sheet).readAll("sheet").ok().filter { it.type == "category" }

        assertEquals(listOf("food", "Fuel"), rows.map { it.key })
        assertEquals("the newest duplicate wins", "b", rows.first().updatedBy)
    }

    @Test
    fun readAllDuplicateTieKeepsTombstone() = runBlocking {
        val same = "2026-11-17T08:00:00Z"
        for (tombstoneFirst in listOf(true, false)) {
            val sheet = FakeConfigSheet()
            val live = arrayOf("category", "Food", "false", "false", "false", "1|Teal", same, "live", "")
            val dead = arrayOf("category", "Food", "false", "false", "false", "1|Teal", same, "dead", "true")
            if (tombstoneFirst) { sheet.addRow(*dead); sheet.addRow(*live) } else { sheet.addRow(*live); sheet.addRow(*dead) }

            val rows = repo(sheet).readAll("sheet").ok().filter { it.type == "category" }

            assertEquals(1, rows.size)
            assertTrue("tombstone wins a tie (tombstone first: $tombstoneFirst)", rows.single().deleted)
        }
    }

    @Test
    fun readAllNormalisesUnicodeKeys() = runBlocking {
        val sheet = FakeConfigSheet()
        sheet.addRow("category", "Cafe\u0301", "false", "false", "false", "1|Teal", "2026-11-17T08:00:00Z", "decomposed", "")
        sheet.addRow("category", "Caf\u00e9", "false", "false", "false", "1|Teal", "2026-11-17T09:00:00Z", "composed", "")

        val rows = repo(sheet).readAll("sheet").ok().filter { it.type == "category" }

        assertEquals("one key, not two", 1, rows.size)
        assertEquals("composed", rows.single().updatedBy)
    }

    @Test
    fun upsertWritesFirstDuplicateRow() = runBlocking {
        val sheet = FakeConfigSheet()
        sheet.addRow("category", "Food", "false", "false", "false", "1|Teal", "2026-11-17T06:00:00Z", "first", "")
        sheet.addRow("category", "Fuel", "false", "false", "false", "2|Teal", "2026-11-17T06:00:00Z", "x", "")
        sheet.addRow("category", "food", "false", "false", "false", "1|Teal", "2026-11-17T07:00:00Z", "second", "")
        val firstIndex = 2
        val secondIndex = 4

        val local = ConfigRow("category", "Food", listOf("true", "false", "false", "1|Teal"), Instant.parse("2026-11-17T09:00:00Z"), "me", false)
        val written = repo(sheet).upsert("sheet", listOf(local)).ok()

        assertEquals(setOf("category|food"), written)
        assertEquals("the first row is written", "me", sheet.cells[firstIndex][7])
        assertEquals("the later duplicate is left alone", "second", sheet.cells[secondIndex][7])
        assertEquals(5, sheet.cells.size)
    }

    @Test
    fun upsertDoesNotOverwriteWhenALaterDuplicateIsNewer() = runBlocking {
        val sheet = FakeConfigSheet()
        sheet.addRow("category", "Food", "false", "false", "false", "1|Teal", "2026-11-17T06:00:00Z", "first", "")
        sheet.addRow("category", "food", "true", "false", "false", "1|Teal", "2026-11-17T09:00:00Z", "newest", "")

        val local = ConfigRow("category", "Food", listOf("false", "false", "false", "1|Teal"), Instant.parse("2026-11-17T08:00:00Z"), "me", false)
        val written = repo(sheet).upsert("sheet", listOf(local)).ok()

        assertEquals(emptySet<String>(), written)
        assertEquals("first", sheet.cells[2][7])
    }

    private fun row(key: String, value1: String = "false", updatedBy: String = "dev") =
        arrayOf("category", key, value1, "false", "false", "1|Teal", "2026-11-17T08:00:00Z", updatedBy, "")

    @Test
    fun readAllSkipsOversizedKey() = runBlocking {
        val sheet = FakeConfigSheet()
        sheet.addRow(*row("k".repeat(81)))
        sheet.addRow(*row("k".repeat(80)))
        sheet.addRow(*row("Fine", value1 = "v".repeat(401)))
        sheet.addRow(*row("AlsoFine", value1 = "v".repeat(400)))

        val keys = repo(sheet).readAll("sheet").ok().filter { it.type == "category" }.map { it.key }

        assertEquals(listOf("k".repeat(80), "AlsoFine"), keys)
        assertTrue(diagnostic("CONFIG_ROW_TOO_LONG: 2"))
    }

    @Test
    fun readAllAllowsALongSubscriptionValue() = runBlocking {
        val sheet = FakeConfigSheet()
        sheet.addRow("subscription", "sub_1", "x".repeat(1500), "", "", "", "2026-11-17T08:00:00Z", "dev", "")
        sheet.addRow("subscription", "sub_2", "x".repeat(2001), "", "", "", "2026-11-17T08:00:00Z", "dev", "")

        val keys = repo(sheet).readAll("sheet").ok().filter { it.type == "subscription" }.map { it.key }

        assertEquals(listOf("sub_1"), keys)
    }

    @Test
    fun readAllSkipsControlCharacters() = runBlocking {
        val sheet = FakeConfigSheet()
        sheet.addRow(*row("New\nLine"))
        sheet.addRow(*row("Right\u202Eleft"))
        sheet.addRow(*row("Zero\u200Bwidth"))
        sheet.addRow(*row("Tab\there"))
        sheet.addRow(*row("Clean", value1 = "bad\u0007value").also { it[2] = "bad\u0007value" })
        sheet.addRow(*row("Plain name with spaces & symbols"))

        val keys = repo(sheet).readAll("sheet").ok().filter { it.type == "category" }.map { it.key }

        assertEquals(listOf("Plain name with spaces & symbols"), keys)
        assertTrue(diagnostic("CONFIG_ROW_BAD_CHARS: 5"))
    }

    @Test
    fun readAllStopsAtRowLimit() = runBlocking {
        val sheet = FakeConfigSheet()
        repeat(2500) { sheet.addRow(*row("k$it")) }

        val keys = repo(sheet).readAll("sheet").ok().filter { it.type == "category" }.map { it.key }

        // Rows 1 and 2 are the header and meta rows, so 1998 data rows fit in the 2000 requested
        assertEquals(1998, keys.size)
        assertEquals("k1997", keys.last())
        assertTrue(diagnostic("CONFIG_ROW_LIMIT"))
    }

    @Test
    fun readAllShortRowStillParses() = runBlocking {
        val sheet = FakeConfigSheet()
        sheet.addRow("category", "OnlyKey")
        sheet.addRow("person", "Asha", "0")

        val rows = repo(sheet).readAll("sheet").ok().filter { it.type != "meta" }

        assertEquals(listOf("OnlyKey", "Asha"), rows.map { it.key })
        assertEquals(Instant.EPOCH, rows.first().updatedAt)
        assertEquals(listOf("", "", "", ""), rows.first().values)
    }
}
