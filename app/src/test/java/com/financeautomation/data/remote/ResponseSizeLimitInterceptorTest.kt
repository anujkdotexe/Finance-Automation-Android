package com.financeautomation.data.remote

import okhttp3.Call
import okhttp3.Connection
import okhttp3.Interceptor
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test
import java.io.IOException
import java.util.concurrent.TimeUnit

class ResponseSizeLimitInterceptorTest {

    private class FakeChain(private val response: Response) : Interceptor.Chain {
        override fun request(): Request = Request.Builder().url("https://example.com/test").build()

        override fun proceed(request: Request): Response = response

        override fun connection(): Connection? = null
        override fun call(): Call = throw UnsupportedOperationException()
        override fun connectTimeoutMillis(): Int = 1000
        override fun withConnectTimeout(timeout: Int, unit: TimeUnit): Interceptor.Chain = this
        override fun readTimeoutMillis(): Int = 1000
        override fun withReadTimeout(timeout: Int, unit: TimeUnit): Interceptor.Chain = this
        override fun writeTimeoutMillis(): Int = 1000
        override fun withWriteTimeout(timeout: Int, unit: TimeUnit): Interceptor.Chain = this
    }

    private val interceptor = ResponseSizeLimitInterceptor(maxBytes = 100 * 1024L) // 100 KB limit for test

    @Test
    fun smallResponsePassesThroughSuccessfully() {
        val rawBody = "Hello World".toByteArray()
        val mockResponse = Response.Builder()
            .request(Request.Builder().url("https://example.com/test").build())
            .protocol(Protocol.HTTP_1_1)
            .code(200)
            .message("OK")
            .body(rawBody.toResponseBody(null))
            .build()

        val chain = FakeChain(mockResponse)
        val result = interceptor.intercept(chain)
        assertEquals("Hello World", result.body?.string())
    }

    @Test
    fun oversizedResponseWithContentLengthHeaderThrowsResponseTooLargeException() {
        val largeBytes = ByteArray(150 * 1024) { 'A'.code.toByte() } // 150 KB
        val mockResponse = Response.Builder()
            .request(Request.Builder().url("https://example.com/test").build())
            .protocol(Protocol.HTTP_1_1)
            .code(200)
            .message("OK")
            .body(largeBytes.toResponseBody(null))
            .build()

        val chain = FakeChain(mockResponse)
        assertThrows(ResponseTooLargeException::class.java) {
            interceptor.intercept(chain)
        }
    }
}
