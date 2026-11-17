package com.financeautomation.data.remote

import com.financeautomation.core.model.Result
import kotlinx.coroutines.runBlocking
import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Unit tests verifying GoogleDriveClient.isFileUsable behavior across all HTTP status codes
 * and Google Drive response payloads.
 */
class GoogleDriveClientUsableTest {

    private val jsonMediaType = "application/json; charset=utf-8".toMediaType()
    private val tokens = object : TokenProvider {
        override fun getAccessToken(): String = "test_token"
        override fun refreshAccessToken(): String = "test_token"
    }

    private fun clientWithResponse(code: Int, body: String): GoogleDriveClient {
        val okHttpClient = OkHttpClient.Builder().addInterceptor(Interceptor { chain ->
            Response.Builder()
                .request(chain.request())
                .protocol(Protocol.HTTP_1_1)
                .code(code)
                .message(if (code in 200..299) "OK" else "Error")
                .body(body.toResponseBody(jsonMediaType))
                .build()
        }).build()
        return GoogleDriveClient(okHttpClient, tokens)
    }

    @Test
    fun fileNotFound404ReturnsSuccessFalse() = runBlocking {
        val client = clientWithResponse(404, """{"error":{"code":404,"message":"File not found","errors":[{"reason":"notFound"}]}}""")
        val res = client.isFileUsable("missing_id")
        assertTrue("404 must return Result.Success", res is Result.Success)
        assertFalse("404 file must not be usable", (res as Result.Success).data)
    }

    @Test
    fun fileForbidden403ReturnsSuccessFalse() = runBlocking {
        val client = clientWithResponse(403, """{"error":{"code":403,"message":"Forbidden","errors":[{"reason":"forbidden"}]}}""")
        val res = client.isFileUsable("forbidden_id")
        assertTrue("403 forbidden must return Result.Success", res is Result.Success)
        assertFalse("403 forbidden file must not be usable", (res as Result.Success).data)
    }

    @Test
    fun fileRateLimit403ReturnsError() = runBlocking {
        val client = clientWithResponse(403, """{"error":{"code":403,"message":"User Rate Limit Exceeded","errors":[{"reason":"rateLimitExceeded"}]}}""")
        val res = client.isFileUsable("ratelimited_id")
        assertTrue("403 rateLimitExceeded must return Result.Error", res is Result.Error)
    }

    @Test
    fun file429ReturnsError() = runBlocking {
        val client = clientWithResponse(429, """{"error":{"code":429,"message":"Too Many Requests"}}""")
        val res = client.isFileUsable("rate_429_id")
        assertTrue("429 must return Result.Error", res is Result.Error)
    }

    @Test
    fun file500ReturnsError() = runBlocking {
        val client = clientWithResponse(500, """{"error":{"code":500,"message":"Internal Server Error"}}""")
        val res = client.isFileUsable("server_err_id")
        assertTrue("500 must return Result.Error", res is Result.Error)
    }

    @Test
    fun file200TrashedTrueReturnsSuccessFalse() = runBlocking {
        val client = clientWithResponse(200, """{"id":"file_trashed","trashed":true}""")
        val res = client.isFileUsable("file_trashed")
        assertTrue("200 trashed true must return Result.Success", res is Result.Success)
        assertFalse("Trashed file must not be usable", (res as Result.Success).data)
    }

    @Test
    fun file200TrashedFalseReturnsSuccessTrue() = runBlocking {
        val client = clientWithResponse(200, """{"id":"file_ok","trashed":false}""")
        val res = client.isFileUsable("file_ok")
        assertTrue("200 trashed false must return Result.Success", res is Result.Success)
        assertTrue("Non-trashed file must be usable", (res as Result.Success).data)
    }

    @Test
    fun file200NoTrashedFieldReturnsSuccessTrue() = runBlocking {
        val client = clientWithResponse(200, """{"id":"file_no_field"}""")
        val res = client.isFileUsable("file_no_field")
        assertTrue("200 without trashed field must return Result.Success", res is Result.Success)
        assertTrue("File without trashed field must default to usable", (res as Result.Success).data)
    }
}
