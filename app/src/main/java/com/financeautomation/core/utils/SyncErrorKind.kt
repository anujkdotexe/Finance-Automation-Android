package com.financeautomation.core.utils

import com.financeautomation.data.remote.GoogleSheetsApiException
import java.net.ConnectException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.util.concurrent.TimeoutException

enum class SyncErrorKind {
    RATE_LIMITED,
    AUTH_EXPIRED,
    NO_NETWORK,
    SERVER_BUSY,
    SHEET_CHANGED,
    UNKNOWN;

    fun plainMessage(): String = when (this) {
        RATE_LIMITED -> "Google is busy. This will retry on its own."
        AUTH_EXPIRED -> "Sign-in expired. Reconnect to continue."
        NO_NETWORK -> "No internet. This will sync when you are back online."
        SERVER_BUSY -> "Google had a problem. This will retry on its own."
        SHEET_CHANGED -> "The sheet was changed. Open Sync and Backup to repair."
        UNKNOWN -> "Something went wrong. Tap Details."
    }

    companion object {
        private val STATUS_IN_TEXT = Regex("""(?:^\s*|\b(?:http|status|code)[\s:=]*)([1-5]\d\d)\b""")
        private val QUOTA_OR_RATE = Regex("""\b(?:quota\w*|rate)\b""")
        private val PERMISSION = Regex("""\b(?:insufficient|permission\w*)\b""")
        private val AUTH_WORDS = Regex("""\b(?:unauthenticated|invalid credentials|authexpired|auth)\b""")
        private val SERVER_WORDS = Regex("""\b(?:internal server error|backend error)\b""")
        private val SHEET_WORDS = Regex("""\b(?:sheet changed|header mismatch|tab not found|tab missing|schema mismatch|permanent mismatch)\b""")
        private val NETWORK_WORDS = Regex("""\b(?:unknownhost\w*|unable to resolve host|failed to connect|timeout|network unreachable)\b""")

        fun fromThrowable(throwable: Throwable?): SyncErrorKind {
            if (throwable == null) return UNKNOWN

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

            if (msg.contains("quotaexceeded") || msg.contains("rate limit")) {
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
         * Maps an HTTP status code. A 403 is ambiguous (permission, quota, API not enabled),
         * so it is classified from [message] and is UNKNOWN when the text does not say which.
         */
        fun fromStatusCode(code: Int, message: String = ""): SyncErrorKind {
            val msg = message.lowercase()
            return when (code) {
                429 -> RATE_LIMITED
                401 -> AUTH_EXPIRED
                403 -> when {
                    PERMISSION.containsMatchIn(msg) -> SHEET_CHANGED
                    QUOTA_OR_RATE.containsMatchIn(msg) -> RATE_LIMITED
                    else -> UNKNOWN
                }
                in 500..599 -> SERVER_BUSY
                else -> UNKNOWN
            }
        }
    }
}
