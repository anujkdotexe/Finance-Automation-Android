package com.financeautomation.data

import com.financeautomation.core.model.Result
import com.financeautomation.data.local.DatabaseEncryptionMigrator
import com.financeautomation.data.local.entity.SyncQueueEntity
import com.financeautomation.data.remote.AccountingRequestJsonAdapter
import com.financeautomation.data.remote.AccountingSyncExecutor
import com.financeautomation.data.remote.GoogleDriveClient
import com.financeautomation.data.remote.GoogleSheetsClient
import com.financeautomation.data.remote.TokenProvider
import com.financeautomation.domain.model.YearCloseRequest
import com.financeautomation.testutil.InMemoryLineageDao
import com.financeautomation.testutil.InMemoryQueueDao
import com.financeautomation.testutil.InMemoryTransactionDao
import kotlinx.coroutines.runBlocking
import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class SqlCipherMigrationAndDriveUploadTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    @Test
    fun secureDeleteWipesAndDeletesFile() {
        val file = tempFolder.newFile("sensitive_plain.db")
        file.writeBytes(ByteArray(1024) { 0x55 })
        assertTrue(file.exists())
        assertEquals(1024L, file.length())

        DatabaseEncryptionMigrator.securelyDeleteFile(file)
        assertFalse("File must be deleted after secure wipe", file.exists())
    }

    @Test
    fun driveUploadUsesMultipartRelatedMediaType() = runBlocking {
        var observedContentType: String? = null
        val interceptor = Interceptor { chain ->
            val req = chain.request()
            println("Test Interceptor received: ${req.method} ${req.url}")
            if (req.method == "POST") {
                observedContentType = req.body?.contentType()?.toString()
            }
            Response.Builder()
                .request(req)
                .protocol(Protocol.HTTP_1_1)
                .code(200)
                .message("OK")
                .body("""{"id":"uploaded_drive_file_123"}""".toResponseBody("application/json".toMediaType()))
                .build()
        }

        val client = OkHttpClient.Builder().addInterceptor(interceptor).build()
        val tokenProvider = object : TokenProvider {
            override fun getAccessToken(): String = "tok"
            override fun refreshAccessToken(): String = "tok"
        }
        val driveClient = GoogleDriveClient(client, tokenProvider)

        val result = driveClient.uploadOrUpdateTextFile(
            folderId = "folder_123",
            fileName = "carry_forward_2026.json",
            content = """{"year":2026,"closed":true}"""
        )

        assertTrue(result is Result.Success)
        assertNotNull(observedContentType)
        assertTrue("Drive upload must use multipart/related, observed: $observedContentType",
            observedContentType!!.startsWith("multipart/related"))
    }

    @Test
    fun yearCloseSurfacesDriveUploadFailureAsError() = runBlocking {
        val interceptor = Interceptor { chain ->
            val req = chain.request()
            val url = req.url.toString()
            val jsonMedia = "application/json".toMediaType()

            when {
                url.contains("spreadsheets") && !url.contains("/values") -> {
                    Response.Builder()
                        .request(req)
                        .protocol(Protocol.HTTP_1_1)
                        .code(200)
                        .message("OK")
                        .body("""{"spreadsheetId":"sheet1","sheets":[{"properties":{"sheetId":0,"title":"Dec26"}}]}""".toResponseBody(jsonMedia))
                        .build()
                }
                url.contains("values:batchGet") -> {
                    Response.Builder()
                        .request(req)
                        .protocol(Protocol.HTTP_1_1)
                        .code(200)
                        .message("OK")
                        .body("""{"valueRanges":[]}""".toResponseBody(jsonMedia))
                        .build()
                }
                url.contains("values:batchUpdate") -> {
                    Response.Builder()
                        .request(req)
                        .protocol(Protocol.HTTP_1_1)
                        .code(200)
                        .message("OK")
                        .body("""{"totalUpdatedCells":1}""".toResponseBody(jsonMedia))
                        .build()
                }
                url.contains("files?q=") && url.contains("FinanceAutomation") -> {
                    // findOrCreateFolder
                    Response.Builder()
                        .request(req)
                        .protocol(Protocol.HTTP_1_1)
                        .code(200)
                        .message("OK")
                        .body("""{"files":[{"id":"folder_id_1"}]}""".toResponseBody(jsonMedia))
                        .build()
                }
                url.contains("files?q=") && url.contains("carry_forward") -> {
                    // file search -> no existing file
                    Response.Builder()
                        .request(req)
                        .protocol(Protocol.HTTP_1_1)
                        .code(200)
                        .message("OK")
                        .body("""{"files":[]}""".toResponseBody(jsonMedia))
                        .build()
                }
                url.contains("uploadType=multipart") -> {
                    // Upload fails with HTTP 500 error
                    Response.Builder()
                        .request(req)
                        .protocol(Protocol.HTTP_1_1)
                        .code(500)
                        .message("Server Error")
                        .body("""{"error":{"message":"Internal Server Error","code":500}}""".toResponseBody(jsonMedia))
                        .build()
                }
                else -> {
                    Response.Builder()
                        .request(req)
                        .protocol(Protocol.HTTP_1_1)
                        .code(200)
                        .message("OK")
                        .body("{}".toResponseBody(jsonMedia))
                        .build()
                }
            }
        }

        val okHttpClient = OkHttpClient.Builder().addInterceptor(interceptor).build()
        val tokenProvider = object : TokenProvider {
            override fun getAccessToken(): String = "tok"
            override fun refreshAccessToken(): String = "tok"
        }
        val sheetsClient = GoogleSheetsClient(okHttpClient, tokenProvider)
        val driveClient = GoogleDriveClient(okHttpClient, tokenProvider)

        val executor = AccountingSyncExecutor(
            googleSheetsClient = sheetsClient,
            googleDriveClient = driveClient,
            syncQueueDao = InMemoryQueueDao(),
            lineageDao = InMemoryLineageDao(),
            transactionDao = InMemoryTransactionDao()
        )

        val queueItem = SyncQueueEntity(
            id = 1L,
            transactionId = "yc_1",
            operationType = SyncQueueEntity.OP_YEAR_CLOSE,
            payloadJson = AccountingRequestJsonAdapter.yearCloseRequestToJson(YearCloseRequest(year = 2026))
        )

        val result = executor.executeYearClose(queueItem, "sheet1")
        assertTrue("Drive upload failure must surface as Result.Error, got: $result", result is Result.Error)
    }

    private fun assertNotNull(value: Any?) {
        org.junit.Assert.assertNotNull(value)
    }
}
