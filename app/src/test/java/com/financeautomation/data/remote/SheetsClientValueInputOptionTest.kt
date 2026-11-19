package com.financeautomation.data.remote

import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import org.junit.Assert.assertThrows
import org.junit.Test

class SheetsClientValueInputOptionTest {

    private val fakeClient = GoogleSheetsClient(
        okHttpClient = OkHttpClient()
    )

    @Test
    fun appendTableScopedRejectsNonRawValueInputOption() {
        assertThrows(IllegalArgumentException::class.java) {
            runBlocking {
                fakeClient.appendTableScoped(
                    spreadsheetId = "sheet123",
                    tabName = "Nov26",
                    tableRange = "A4:L",
                    row = listOf("2026-11-18", "12:00", "Canara", "Lunch", "Personal", "Food", 250.0),
                    valueInputOption = "USER_ENTERED"
                )
            }
        }
    }

    @Test
    fun updateCellRejectsNonRawValueInputOption() {
        assertThrows(IllegalArgumentException::class.java) {
            runBlocking {
                fakeClient.updateCell(
                    spreadsheetId = "sheet123",
                    tabName = "Nov26",
                    cellCoordinate = "A4",
                    value = "test",
                    valueInputOption = "USER_ENTERED"
                )
            }
        }
    }

    @Test
    fun batchUpdateValuesRejectsNonRawValueInputOption() {
        assertThrows(IllegalArgumentException::class.java) {
            runBlocking {
                fakeClient.batchUpdateValues(
                    spreadsheetId = "sheet123",
                    updates = mapOf("A4" to listOf(listOf("test"))),
                    valueInputOption = "USER_ENTERED"
                )
            }
        }
    }
}
