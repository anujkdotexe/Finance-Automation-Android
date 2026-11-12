package com.financeautomation.core.utils

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
        fun fromThrowable(throwable: Throwable?): SyncErrorKind {
            if (throwable == null) return UNKNOWN

            val msg = throwable.message?.lowercase() ?: ""

            if (msg.contains("429") || msg.contains("quotaexceeded") || msg.contains("rate limit")) {
                return RATE_LIMITED
            }
            if (msg.contains("401") || msg.contains("403") || msg.contains("unauthenticated") || msg.contains("auth") || msg.contains("invalid credentials") || msg.contains("authexpired")) {
                return AUTH_EXPIRED
            }
            if (msg.contains("500") || msg.contains("502") || msg.contains("503") || msg.contains("504") || msg.contains("internal server error") || msg.contains("backend error")) {
                return SERVER_BUSY
            }
            if (msg.contains("sheet changed") || msg.contains("header mismatch") || msg.contains("tab not found") || msg.contains("tab missing") || msg.contains("schema mismatch") || msg.contains("permanent mismatch")) {
                return SHEET_CHANGED
            }

            if (throwable is UnknownHostException || throwable is SocketTimeoutException || throwable is ConnectException || throwable is TimeoutException) {
                return NO_NETWORK
            }
            if (msg.contains("unknownhost") || msg.contains("unable to resolve host") || msg.contains("failed to connect") || msg.contains("timeout") || msg.contains("network unreachable")) {
                return NO_NETWORK
            }

            val cause = throwable.cause
            if (cause != null && cause !== throwable) {
                val fromCause = fromThrowable(cause)
                if (fromCause != UNKNOWN) return fromCause
            }

            return UNKNOWN
        }

        fun fromStatusCode(code: Int): SyncErrorKind = when (code) {
            429 -> RATE_LIMITED
            401, 403 -> AUTH_EXPIRED
            in 500..599 -> SERVER_BUSY
            else -> UNKNOWN
        }
    }
}

