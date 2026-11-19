package com.financeautomation.data.remote

import okhttp3.Interceptor
import okhttp3.Response
import okhttp3.ResponseBody.Companion.asResponseBody
import okio.Buffer
import okio.ForwardingSource
import okio.buffer
import java.io.IOException

/**
 * Thrown when an HTTP response exceeds the configured byte limit.
 * Marked as a non-retryable permanent error in sync error handling to prevent infinite retry loops.
 */
class ResponseTooLargeException(message: String) : IOException(message)

/**
 * OkHttp interceptor that enforces an upper bound on HTTP response body sizes.
 * Prevents memory exhaustion / OutOfMemoryError when receiving oversized or hostile responses.
 */
class ResponseSizeLimitInterceptor(
    private val maxBytes: Long = DEFAULT_MAX_RESPONSE_BYTES
) : Interceptor {

    companion object {
        const val DEFAULT_MAX_RESPONSE_BYTES = 5L * 1024L * 1024L // 5 MB
    }

    override fun intercept(chain: Interceptor.Chain): Response {
        val response = chain.proceed(chain.request())
        val body = response.body ?: return response

        val contentLength = body.contentLength()
        if (contentLength > maxBytes) {
            body.close()
            throw ResponseTooLargeException("HTTP response content-length ($contentLength bytes) exceeds size limit of $maxBytes bytes")
        }

        val boundedSource = object : ForwardingSource(body.source()) {
            private var totalBytesRead = 0L

            override fun read(sink: Buffer, byteCount: Long): Long {
                val bytesRead = super.read(sink, byteCount)
                if (bytesRead != -1L) {
                    totalBytesRead += bytesRead
                    if (totalBytesRead > maxBytes) {
                        throw ResponseTooLargeException("HTTP response stream exceeded size limit of $maxBytes bytes")
                    }
                }
                return bytesRead
            }
        }

        val newBody = boundedSource.buffer().asResponseBody(body.contentType(), contentLength)
        return response.newBuilder().body(newBody).build()
    }
}
