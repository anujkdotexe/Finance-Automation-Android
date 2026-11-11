package com.financeautomation.data.remote

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class RawHttpErrorStoreTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    @Test
    fun testStoresEntryForFailedProvisioningCallAndCutsTo4KB() = runBlocking {
        val storeDir = tempFolder.newFolder("errors_test_1")
        val store = RawHttpErrorStore(
            filesDir = storeDir,
            ioDispatcher = Dispatchers.Unconfined,
            isDebugOrBeta = true
        )

        // 10 KB error response
        val largeBody = "X".repeat(10 * 1024)

        store.recordError(
            endpointId = "sheets.batchUpdate",
            httpStatus = 400,
            responseBody = largeBody,
            isProvisioningOrStructure = true
        )

        val entries = store.getEntries()
        assertEquals(1, entries.size)
        assertEquals("sheets.batchUpdate", entries[0].endpointId)
        assertEquals(400, entries[0].httpStatus)
        assertEquals(RawHttpErrorStore.MAX_BODY_BYTES, entries[0].responseBody.length)
    }

    @Test
    fun testTransactionRowCallStoresNoBody() = runBlocking {
        val storeDir = tempFolder.newFolder("errors_test_2")
        val store = RawHttpErrorStore(
            filesDir = storeDir,
            ioDispatcher = Dispatchers.Unconfined,
            isDebugOrBeta = true
        )

        // Transaction append call failure (carries row data)
        store.recordError(
            endpointId = "sheets.values.append",
            httpStatus = 400,
            responseBody = """{"error":"Invalid amount 500"}""",
            isProvisioningOrStructure = false
        )

        val entries = store.getEntries()
        assertTrue("Transaction row call must store no raw entry", entries.isEmpty())
    }

    @Test
    fun testKeepsOnlyLast20Entries() = runBlocking {
        val storeDir = tempFolder.newFolder("errors_test_3")
        val store = RawHttpErrorStore(
            filesDir = storeDir,
            ioDispatcher = Dispatchers.Unconfined,
            isDebugOrBeta = true
        )

        for (i in 1..25) {
            store.recordError(
                endpointId = "sheets.batchUpdate",
                httpStatus = 400 + i,
                responseBody = "Error body #$i",
                isProvisioningOrStructure = true
            )
        }

        val entries = store.getEntries()
        assertEquals(20, entries.size)
        // First entry should be #6, last should be #25
        assertEquals(406, entries[0].httpStatus)
        assertEquals("Error body #6", entries[0].responseBody)
        assertEquals(425, entries[19].httpStatus)
        assertEquals("Error body #25", entries[19].responseBody)
    }

    @Test
    fun testSurvivesRestart() = runBlocking {
        val storeDir = tempFolder.newFolder("errors_test_4")
        val store1 = RawHttpErrorStore(
            filesDir = storeDir,
            ioDispatcher = Dispatchers.Unconfined,
            isDebugOrBeta = true
        )

        store1.recordError(
            endpointId = "drive.files.create",
            httpStatus = 404,
            responseBody = """{"error":"Parent folder not found"}""",
            isProvisioningOrStructure = true
        )

        // Simulate app restart with a fresh store instance on the same directory
        val store2 = RawHttpErrorStore(
            filesDir = storeDir,
            ioDispatcher = Dispatchers.Unconfined,
            isDebugOrBeta = true
        )

        val loaded = store2.load()
        assertEquals(1, loaded.size)
        assertEquals("drive.files.create", loaded[0].endpointId)
        assertEquals(404, loaded[0].httpStatus)
        assertEquals("""{"error":"Parent folder not found"}""", loaded[0].responseBody)
    }

    @Test
    fun testAuthorizationHeaderAndRequestBodyNeverAppearInStoredEntry() = runBlocking {
        val storeDir = tempFolder.newFolder("errors_test_5")
        val store = RawHttpErrorStore(
            filesDir = storeDir,
            ioDispatcher = Dispatchers.Unconfined,
            isDebugOrBeta = true
        )

        val secretAuthToken = "ya29.secret_bearer_token_xyz"
        val requestPayload = """{"requests":[{"updateCells":{"userEnteredValue":"secret"}}]}"""
        val responseBody = """{"error":{"code":400,"message":"Invalid request"}}"""

        // Simulate error capture: only responseBody is passed, NEVER headers or request body
        store.recordError(
            endpointId = "sheets.batchUpdate",
            httpStatus = 400,
            responseBody = responseBody,
            isProvisioningOrStructure = true
        )

        val entries = store.getEntries()
        assertEquals(1, entries.size)
        assertFalse(entries[0].responseBody.contains(secretAuthToken))
        assertFalse(entries[0].responseBody.contains("ya29."))
        assertFalse(entries[0].responseBody.contains("secret_bearer_token"))
        assertFalse(entries[0].responseBody.contains(requestPayload))
        assertEquals(responseBody, entries[0].responseBody)
    }

    @Test
    fun testReleaseBuildStoresNoBody() = runBlocking {
        val storeDir = tempFolder.newFolder("errors_test_6")
        val store = RawHttpErrorStore(
            filesDir = storeDir,
            ioDispatcher = Dispatchers.Unconfined,
            isDebugOrBeta = false // Simulating release build
        )

        store.recordError(
            endpointId = "sheets.batchUpdate",
            httpStatus = 500,
            responseBody = """{"error":"Server error"}""",
            isProvisioningOrStructure = true
        )

        val entries = store.getEntries()
        assertTrue("Release build must not store raw error bodies", entries.isEmpty())
    }
}
