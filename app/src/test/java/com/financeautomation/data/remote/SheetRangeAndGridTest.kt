package com.financeautomation.data.remote

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** Sheet names are quoted in every A1 range, and a grid limit error is retried after the grid grows. */
class SheetRangeAndGridTest {

    @Test
    fun theLayoutRangeQuotesTheSheetName() {
        assertEquals("'Nov26'!A3:AF3", WorkbookResolver.layoutRange("Nov26"))
        assertEquals("'It''s'!A3:AF3", WorkbookResolver.layoutRange("It's"))
    }

    @Test
    fun noRangeIsBuiltFromAnUnquotedSheetName() {
        val sources = File("src/main/java").walkTopDown().filter { it.extension == "kt" }.toList()
        val bad = Regex("\"\\$\\{?(tab|tabTitle|tabName)\\}?![A-Z]")
        for (f in sources) {
            assertFalse("${f.name} builds an unquoted range", bad.containsMatchIn(f.readText()))
        }
    }

    @Test
    fun aGridLimitErrorIsRetryable() {
        val grid = GoogleSheetsApiException(400, "Range ('Nov26'!A4:L700) exceeds grid limits. Max rows: 600")
        assertTrue(grid.isRetryable())
        assertFalse(GoogleSheetsApiException(400, "Invalid value at 'data'").isRetryable())
    }

    @Test
    fun aFailedGridExpansionStopsTheAppend() {
        val client = File("src/main/java/com/financeautomation/data/remote/GoogleSheetsClient.kt").readText()
        val append = client.substringAfter("suspend fun appendTableScoped(")
        assertTrue(append.contains("val capacity = ensureGridCapacityBeforeAppend("))
        assertTrue(append.contains("if (capacity is Result.Error) return@withContext Result.Error(capacity.exception)"))
    }
}
