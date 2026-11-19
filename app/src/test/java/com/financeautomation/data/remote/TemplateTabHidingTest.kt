package com.financeautomation.data.remote

import com.financeautomation.core.model.Result
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class TemplateTabHidingTest {

    private lateinit var fakeClient: FakeTemplateSheetsClient

    @Before
    fun setUp() {
        fakeClient = FakeTemplateSheetsClient()
    }

    @Test
    fun testHideTemplateTabHidesVisibleTemplateTab() = runBlocking {
        fakeClient.detailedTabs["Template"] = GoogleSheetsClient.SheetTabInfo(
            sheetId = 10,
            title = "Template",
            isHidden = false
        )

        val res = fakeClient.hideTemplateTabIfExists("sheet_123")
        assertTrue(res is Result.Success)

        val reqs = fakeClient.executedBatchRequests
        assertEquals(1, reqs.size)
        val updateProps = reqs.first()["updateSheetProperties"] as? Map<*, *>
        val props = updateProps?.get("properties") as? Map<*, *>
        assertEquals(10, props?.get("sheetId"))
        assertEquals(true, props?.get("hidden"))
        assertEquals("hidden", updateProps?.get("fields"))
    }

    @Test
    fun testHideTemplateTabIsIdempotentWhenAlreadyHidden() = runBlocking {
        fakeClient.detailedTabs["Template"] = GoogleSheetsClient.SheetTabInfo(
            sheetId = 10,
            title = "Template",
            isHidden = true
        )

        val res = fakeClient.hideTemplateTabIfExists("sheet_123")
        assertTrue(res is Result.Success)
        assertTrue(fakeClient.executedBatchRequests.isEmpty())
    }

    @Test
    fun testHideTemplateTabIsNoOpWhenTemplateMissing() = runBlocking {
        fakeClient.detailedTabs["Dashboard"] = GoogleSheetsClient.SheetTabInfo(sheetId = 0, title = "Dashboard")

        val res = fakeClient.hideTemplateTabIfExists("sheet_123")
        assertTrue(res is Result.Success)
        assertTrue(fakeClient.executedBatchRequests.isEmpty())
    }

    @Test
    fun testStandardTabOrderPlacesTemplateLastAndDashboardFirst() = runBlocking {
        fakeClient.detailedTabs.clear()
        // Out of order tabs
        val tabNames = listOf("Config", "Template", "Jan26", "Dashboard", "Trips", "Subscriptions", "Feb26")
        tabNames.forEachIndexed { i, name ->
            fakeClient.detailedTabs[name] = GoogleSheetsClient.SheetTabInfo(
                sheetId = i + 1,
                title = name,
                index = i,
                isHidden = name == "Template"
            )
        }

        val res = fakeClient.ensureStandardTabOrder("sheet_123", year = 2026)
        assertTrue(res is Result.Success)

        val reorderReqs = fakeClient.executedBatchRequests.mapNotNull { it["updateSheetProperties"] as? Map<*, *> }
        val finalIndices = fakeClient.detailedTabs.values.associate { it.title to it.index }.toMutableMap()
        for (req in reorderReqs) {
            val props = req["properties"] as Map<*, *>
            val sheetId = props["sheetId"] as Int
            val index = props["index"] as Int
            val title = fakeClient.detailedTabs.values.first { it.sheetId == sheetId }.title
            finalIndices[title] = index
        }

        // Dashboard must be at index 0
        assertEquals(0, finalIndices["Dashboard"])
        // Month tabs follow
        assertEquals(1, finalIndices["Jan26"])
        assertEquals(2, finalIndices["Feb26"])
        // Subscriptions and Trips follow month tabs
        assertTrue(finalIndices["Subscriptions"]!! > finalIndices["Feb26"]!!)
        assertTrue(finalIndices["Trips"]!! > finalIndices["Subscriptions"]!!)
        // Config before Template
        assertTrue(finalIndices["Config"]!! > finalIndices["Trips"]!!)
        // Template must be last
        assertTrue(finalIndices["Template"]!! > finalIndices["Config"]!!)
    }

    private class FakeTemplateSheetsClient : GoogleSheetsClient(
        okHttpClient = okhttp3.OkHttpClient(),
        tokenProvider = null
    ) {
        val detailedTabs = mutableMapOf<String, SheetTabInfo>()
        val executedBatchRequests = mutableListOf<Map<String, Any?>>()

        override suspend fun getSheetTabsDetailed(spreadsheetId: String): Result<Map<String, SheetTabInfo>> =
            Result.Success(detailedTabs.toMap())

        override suspend fun batchUpdate(
            spreadsheetId: String,
            requests: List<Map<String, Any?>>
        ): Result<Unit> {
            executedBatchRequests.addAll(requests)
            return Result.Success(Unit)
        }
    }
}
