package com.financeautomation.core.utils

import com.financeautomation.data.remote.GoogleSheetsApiException
import java.net.ConnectException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.util.concurrent.TimeoutException

/** A partly sent entry whose saved part cannot be proven from the sheet, so nothing more may be written. */
class UnprovableReplayException :
    IllegalStateException("The sheet changed after this entry was first sent, so what was saved cannot be proven")

/** A carried copy that no longer matches its still open original, so what is owed cannot be proven. */
class CarryConflictException :
    IllegalStateException("A carried copy no longer matches its open original, so nothing was carried")

enum class SyncErrorKind {
    RATE_LIMITED,
    AUTH_EXPIRED,
    NO_NETWORK,
    SERVER_BUSY,
    SHEET_CHANGED,
    PARTLY_SENT,
    CARRY_CONFLICT,
    UNKNOWN;

    fun plainMessage(): String = when (this) {
        RATE_LIMITED -> "Google is busy. This will retry on its own."
        AUTH_EXPIRED -> "Sign-in expired. Reconnect to continue."
        NO_NETWORK -> "No internet. This will sync when you are back online."
        SERVER_BUSY -> "Google had a problem. This will retry on its own."
        SHEET_CHANGED -> "The sheet was changed. Open Sync and Backup to repair."
        PARTLY_SENT -> "Partly sent, then the sheet changed. Nothing more was written. Check this debt in the sheet, " +
            "then discard this item and enter only what is missing."
        CARRY_CONFLICT -> "A carried debt does not match its old row. In the sheet, correct the copy or mark the old " +
            "row Carried Fwd, then retry."
        UNKNOWN -> "Something went wrong. Tap Details."
    }

    companion object {
        private val STATUS_IN_TEXT = Regex("""(?:^\s*|\b(?:http|status|code)[\s:=]*)([1-5]\d\d)\b""")
        private val QUOTA_OR_RATE = Regex("""\b(?:quota\w*|rate)\b""")
        // Google reason strings are camelCase and run together once lowercased (ratelimitexceeded and so on)
        private val RUN_TOGETHER_RATE = listOf("ratelimit", "quotaexceeded", "dailylimit")
        private val RUN_TOGETHER_PERMISSION = listOf("insufficientpermission")
        private val PERMISSION = Regex("""\b(?:insufficient|permission\w*)\b""")
        private val AUTH_WORDS = Regex("""\b(?:unauthenticated|invalid credentials|authexpired|auth)\b""")
        private val SERVER_WORDS = Regex("""\b(?:internal server error|backend error)\b""")
        private val SHEET_WORDS = Regex("""\b(?:sheet changed|header mismatch|tab not found|tab missing|schema mismatch|permanent mismatch)\b""")
        private val NETWORK_WORDS = Regex("""\b(?:unknownhost\w*|unable to resolve host|failed to connect|timeout|network unreachable)\b""")

        fun fromThrowable(throwable: Throwable?): SyncErrorKind {
            if (throwable == null) return UNKNOWN
            if (throwable is UnprovableReplayException) return PARTLY_SENT
            if (throwable is CarryConflictException) return CARRY_CONFLICT

            val msg = throwable.message?.lowercase() ?: ""

            if (throwable is GoogleSheetsApiException) {
                val byStatus = fromStatusCode(throwable.statusCode, msg)
                if (byStatus != UNKNOWN) return byStatus
            } else {
                val code = STATUS_IN_TEXT.find(msg)?.groupValues?.get(1)?.toIntOrNull()
                if (code != null) {
                    val byCode = fromStatusCode(code, msg)
                    if (byCode != UNKNOWN) return byCode
                }
            }

            if (RUN_TOGETHER_RATE.any { msg.contains(it) } || msg.contains("rate limit")) {
                return RATE_LIMITED
            }
            if (AUTH_WORDS.containsMatchIn(msg)) return AUTH_EXPIRED
            if (SERVER_WORDS.containsMatchIn(msg)) return SERVER_BUSY
            if (SHEET_WORDS.containsMatchIn(msg)) return SHEET_CHANGED

            if (throwable is UnknownHostException || throwable is SocketTimeoutException || throwable is ConnectException || throwable is TimeoutException) {
                return NO_NETWORK
            }
            if (NETWORK_WORDS.containsMatchIn(msg)) return NO_NETWORK

            val cause = throwable.cause
            if (cause != null && cause !== throwable) {
                val fromCause = fromThrowable(cause)
                if (fromCause != UNKNOWN) return fromCause
            }

            return UNKNOWN
        }

        /**
         * Extracts an HTTP status code (100..599) from [throwable] or its cause chain.
         */
        fun extractHttpCode(throwable: Throwable?): Int? {
            if (throwable == null) return null
            if (throwable is GoogleSheetsApiException && throwable.statusCode > 0) {
                return throwable.statusCode
            }
            val cause = throwable.cause
            if (cause is GoogleSheetsApiException && cause.statusCode > 0) {
                return cause.statusCode
            }
            val msg = throwable.message?.lowercase() ?: ""
            val code = STATUS_IN_TEXT.find(msg)?.groupValues?.get(1)?.toIntOrNull()
            if (code != null) return code
            if (cause != null && cause !== throwable) {
                return extractHttpCode(cause)
            }
            return null
        }

        /**
         * Derives a short safe diagnostic code without exposing raw exception messages.
         * Example: "dashboard sync, HTTP 403" or "dashboard sync, ConnectException".
         */
        fun safeCode(stage: String, throwable: Throwable?, statusCode: Int? = null): String {
            val httpCode = statusCode ?: extractHttpCode(throwable)
            val codePart = if (httpCode != null && httpCode > 0) "HTTP $httpCode" else (throwable?.javaClass?.simpleName ?: "Error")
            return "$stage, $codePart"
        }

        /**
         * Returns the friendly classification sentence plus a short safe code.
         * Zero raw exception message text is exposed.
         * Example: "Google is busy. This will retry on its own. (dashboard sync, HTTP 429)"
         */
        fun formatUserMessage(stage: String, throwable: Throwable?, statusCode: Int? = null): String {
            val httpCode = statusCode ?: extractHttpCode(throwable)
            val kind = if (httpCode != null && httpCode > 0) {
                val byStatus = fromStatusCode(httpCode, throwable?.message.orEmpty())
                if (byStatus != UNKNOWN) byStatus else fromThrowable(throwable)
            } else {
                fromThrowable(throwable)
            }
            val code = safeCode(stage, throwable, statusCode)
            return "${kind.plainMessage()} ($code)"
        }

        /**
         * Maps an HTTP status code. A 403 is ambiguous (permission, quota, API not enabled),
         * so it is classified from [message] and is UNKNOWN when the text does not say which.
         */
        fun fromStatusCode(code: Int, message: String = ""): SyncErrorKind {
            val msg = message.lowercase()
            return when (code) {
                429 -> RATE_LIMITED
                401 -> AUTH_EXPIRED
                403 -> when {
                    PERMISSION.containsMatchIn(msg) || RUN_TOGETHER_PERMISSION.any { msg.contains(it) } -> SHEET_CHANGED
                    QUOTA_OR_RATE.containsMatchIn(msg) || RUN_TOGETHER_RATE.any { msg.contains(it) } -> RATE_LIMITED
                    else -> UNKNOWN
                }
                // The sheet or its tab is gone, or a range no longer exists
                404 -> SHEET_CHANGED
                400 -> if (msg.contains("range")) SHEET_CHANGED else UNKNOWN
                in 500..599 -> SERVER_BUSY
                else -> UNKNOWN
            }
        }
    }
}
