package com.financeautomation.data.remote

import android.util.Log
import com.financeautomation.BuildConfig
import java.io.IOException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * Standardized representation of a Google API (Drive and Sheets) REST error.
 * Extracts endpoint id, HTTP status, Google status, first reason, and safe message (max 300 chars).
 * Never includes OAuth tokens, request bodies, amounts, payees, or notification text.
 */
data class GoogleApiError(
    val endpointId: String,
    val httpStatus: Int,
    val status: String? = null,
    val reason: String? = null,
    val message: String
) {

    fun toLogcatString(): String {
        val detail = reason ?: status ?: "UNKNOWN"
        return "$endpointId HTTP $httpStatus $detail"
    }

    fun formatExceptionMessage(context: String = ""): String {
        val prefix = if (context.isNotBlank()) "$context: " else ""
        val detail = reason ?: status ?: "UNKNOWN"
        return "${prefix}$endpointId HTTP $httpStatus ($detail) - ${com.financeautomation.core.utils.PrivacyScrubber.scrub(message)}"
    }

    companion object {
        const val MAX_MESSAGE_LENGTH = 300
        private val json = Json { ignoreUnknownKeys = true }
        @Volatile
        var rawHttpErrorStore: RawHttpErrorStore? = null
        private val recordingScope = CoroutineScope(Dispatchers.IO)

        fun parse(
            endpointId: String,
            httpStatus: Int,
            responseBody: String?
        ): GoogleApiError {
            var extractedStatus: String? = null
            var extractedReason: String? = null
            var rawMessage: String? = null

            if (!responseBody.isNullOrBlank()) {
                try {
                    val root = json.parseToJsonElement(responseBody).jsonObject
                    val errorObj = root["error"]?.jsonObject

                    if (errorObj != null) {
                        extractedStatus = errorObj["status"]?.jsonPrimitive?.contentOrNull
                        rawMessage = errorObj["message"]?.jsonPrimitive?.contentOrNull

                        // 1. Look for first reason in details array (e.g. ErrorInfo)
                        val detailsArray = errorObj["details"]?.jsonArray
                        if (detailsArray != null) {
                            for (detailElement in detailsArray) {
                                val detailObj = detailElement.jsonObject
                                val r = detailObj["reason"]?.jsonPrimitive?.contentOrNull
                                if (!r.isNullOrBlank()) {
                                    extractedReason = r
                                    break
                                }
                            }
                        }

                        // 2. If not found in details, look in errors array
                        if (extractedReason == null) {
                            val errorsArray = errorObj["errors"]?.jsonArray
                            if (errorsArray != null) {
                                for (errElement in errorsArray) {
                                    val errObj = errElement.jsonObject
                                    val r = errObj["reason"]?.jsonPrimitive?.contentOrNull
                                    if (!r.isNullOrBlank()) {
                                        extractedReason = r
                                        break
                                    }
                                }
                            }
                        }
                    }
                } catch (e: kotlinx.serialization.SerializationException) {
                    // Body is not JSON (e.g. HTML proxy error or plain text)
                } catch (e: IllegalArgumentException) {
                    // Malformed JSON element structure
                }
            }

            val finalMessage = when {
                !rawMessage.isNullOrBlank() -> rawMessage
                !responseBody.isNullOrBlank() -> responseBody
                else -> "HTTP $httpStatus"
            }.take(MAX_MESSAGE_LENGTH)

            val error = GoogleApiError(
                endpointId = endpointId,
                httpStatus = httpStatus,
                status = extractedStatus,
                reason = extractedReason,
                message = finalMessage
            )

            logHttpError(error)
            com.financeautomation.core.utils.Diagnostics.record(
                "GoogleHttp",
                "${error.endpointId} HTTP ${error.httpStatus} (${error.reason ?: error.status ?: "UNKNOWN"}): ${error.message}"
            )
            val store = rawHttpErrorStore
            if (store != null && !responseBody.isNullOrBlank()) {
                recordingScope.launch {
                    store.recordError(endpointId, httpStatus, responseBody)
                }
            }
            return error
        }

        private fun logHttpError(error: GoogleApiError) {
            if (BuildConfig.DEBUG || BuildConfig.BUILD_TYPE == "beta") {
                Log.w("FinanceHttp", error.toLogcatString())
            }
        }
    }
}

/**
 * Domain exception for Google Drive REST API failures.
 */
class GoogleDriveApiException(
    val statusCode: Int,
    message: String,
    val apiError: GoogleApiError? = null
) : IOException(message)

val API_RATE_LIMIT_REASONS: Set<String> = setOf("ratelimitexceeded", "userratelimitexceeded", "quotaexceeded", "sharingratelimitexceeded")

/**
 * True when the Drive request is worth repeating later: server errors (5xx), 408, 429,
 * or 403 where the reason indicates a rate or quota limit.
 */
fun GoogleDriveApiException.isRetryable(): Boolean = when (statusCode) {
    in 500..599, 408, 429 -> true
    403 -> apiError?.reason?.lowercase() in API_RATE_LIMIT_REASONS
    else -> false
}
