package com.financeautomation.core

import com.financeautomation.core.utils.SyncErrorKind
import org.junit.Assert.assertEquals
import org.junit.Test
import com.financeautomation.data.remote.GoogleSheetsApiException
import java.io.IOException
import java.net.ConnectException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.util.concurrent.TimeoutException

class SyncErrorKindTest {

    @Test
    fun testPlainMessages() {
        assertEquals("Google is busy. This will retry on its own.", SyncErrorKind.RATE_LIMITED.plainMessage())
        assertEquals("Sign-in expired. Reconnect to continue.", SyncErrorKind.AUTH_EXPIRED.plainMessage())
        assertEquals("No internet. This will sync when you are back online.", SyncErrorKind.NO_NETWORK.plainMessage())
        assertEquals("Google had a problem. This will retry on its own.", SyncErrorKind.SERVER_BUSY.plainMessage())
        assertEquals("The sheet was changed. Open Sync and Backup to repair.", SyncErrorKind.SHEET_CHANGED.plainMessage())
        assertEquals("Something went wrong. Tap Details.", SyncErrorKind.UNKNOWN.plainMessage())
    }

    @Test
    fun testFromThrowable_statusCodesAndMessages() {
        // Rate limited
        assertEquals(SyncErrorKind.RATE_LIMITED, SyncErrorKind.fromThrowable(IOException("HTTP 429 quotaExceeded")))
        assertEquals(SyncErrorKind.RATE_LIMITED, SyncErrorKind.fromStatusCode(429))

        // Auth expired
        assertEquals(SyncErrorKind.AUTH_EXPIRED, SyncErrorKind.fromThrowable(IOException("401 Unauthenticated")))
        assertEquals(SyncErrorKind.AUTH_EXPIRED, SyncErrorKind.fromThrowable(IOException("403 Forbidden: invalid credentials")))
        assertEquals(SyncErrorKind.AUTH_EXPIRED, SyncErrorKind.fromStatusCode(401))
        // A bare 403 is ambiguous and is no longer reported as an expired sign-in
        assertEquals(SyncErrorKind.UNKNOWN, SyncErrorKind.fromStatusCode(403))

        // Network errors
        assertEquals(SyncErrorKind.NO_NETWORK, SyncErrorKind.fromThrowable(UnknownHostException("sheets.googleapis.com")))
        assertEquals(SyncErrorKind.NO_NETWORK, SyncErrorKind.fromThrowable(SocketTimeoutException("Read timed out")))
        assertEquals(SyncErrorKind.NO_NETWORK, SyncErrorKind.fromThrowable(ConnectException("Connection refused")))
        assertEquals(SyncErrorKind.NO_NETWORK, SyncErrorKind.fromThrowable(TimeoutException("timeout")))

        // Server busy
        assertEquals(SyncErrorKind.SERVER_BUSY, SyncErrorKind.fromThrowable(IOException("500 Internal Server Error")))
        assertEquals(SyncErrorKind.SERVER_BUSY, SyncErrorKind.fromThrowable(IOException("503 Service Unavailable")))
        assertEquals(SyncErrorKind.SERVER_BUSY, SyncErrorKind.fromStatusCode(500))
        assertEquals(SyncErrorKind.SERVER_BUSY, SyncErrorKind.fromStatusCode(502))
        assertEquals(SyncErrorKind.SERVER_BUSY, SyncErrorKind.fromStatusCode(503))

        // Sheet changed
        assertEquals(SyncErrorKind.SHEET_CHANGED, SyncErrorKind.fromThrowable(IllegalStateException("Sheet changed: header mismatch")))
        assertEquals(SyncErrorKind.SHEET_CHANGED, SyncErrorKind.fromThrowable(IllegalStateException("Tab missing from spreadsheet")))

        // Unknown
        assertEquals(SyncErrorKind.UNKNOWN, SyncErrorKind.fromThrowable(IllegalArgumentException("Some unknown error")))
        assertEquals(SyncErrorKind.UNKNOWN, SyncErrorKind.fromStatusCode(418))
    }

    @Test
    fun testNumbersAndWordsInsideOtherTextAreNotMatched() {
        assertEquals(SyncErrorKind.UNKNOWN, SyncErrorKind.fromThrowable(IllegalStateException("wrote 500 rows")))
        assertEquals(SyncErrorKind.UNKNOWN, SyncErrorKind.fromThrowable(IllegalStateException("amount 403 for author")))
        assertEquals(SyncErrorKind.UNKNOWN, SyncErrorKind.fromThrowable(IllegalStateException("author")))
    }

    @Test
    fun testStatusCodeOnApiExceptionIsUsedFirst() {
        assertEquals(SyncErrorKind.RATE_LIMITED, SyncErrorKind.fromThrowable(GoogleSheetsApiException(429, "Too many")))
        assertEquals(SyncErrorKind.AUTH_EXPIRED, SyncErrorKind.fromThrowable(GoogleSheetsApiException(401, "nope")))
        assertEquals(SyncErrorKind.SERVER_BUSY, SyncErrorKind.fromThrowable(GoogleSheetsApiException(503, "down")))
    }

    @Test
    fun test403IsClassifiedFromMessage() {
        assertEquals(
            SyncErrorKind.UNKNOWN,
            SyncErrorKind.fromThrowable(GoogleSheetsApiException(403, "Google Sheets API has not been used in project 1 before"))
        )
        assertEquals(
            SyncErrorKind.SHEET_CHANGED,
            SyncErrorKind.fromThrowable(GoogleSheetsApiException(403, "The caller does not have permission"))
        )
        assertEquals(
            SyncErrorKind.SHEET_CHANGED,
            SyncErrorKind.fromThrowable(GoogleSheetsApiException(403, "Request had insufficient authentication scopes"))
        )
        assertEquals(
            SyncErrorKind.RATE_LIMITED,
            SyncErrorKind.fromThrowable(GoogleSheetsApiException(403, "Quota exceeded for quota metric"))
        )
        assertEquals(
            SyncErrorKind.RATE_LIMITED,
            SyncErrorKind.fromThrowable(GoogleSheetsApiException(403, "User rate limit exceeded"))
        )
    }

    @Test
    fun testUnknownHostIsNoNetwork() {
        assertEquals(SyncErrorKind.NO_NETWORK, SyncErrorKind.fromThrowable(UnknownHostException("no host")))
    }
}
