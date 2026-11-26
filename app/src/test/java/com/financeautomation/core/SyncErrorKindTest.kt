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
        assertEquals(
            "The sheet changed after this was first sent, so what was saved cannot be confirmed. Nothing more was written. Check the sheet, then discard this item and enter only what is missing.",
            SyncErrorKind.PARTLY_SENT.plainMessage()
        )
        assertEquals(
            "A carried debt does not match its old row. In the sheet, correct the copy or mark the old row Carried Fwd, then retry.",
            SyncErrorKind.CARRY_CONFLICT.plainMessage()
        )
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

        // Partly sent and Carry conflict
        assertEquals(SyncErrorKind.PARTLY_SENT, SyncErrorKind.fromThrowable(com.financeautomation.core.utils.UnprovableReplayException()))
        assertEquals(SyncErrorKind.CARRY_CONFLICT, SyncErrorKind.fromThrowable(com.financeautomation.core.utils.CarryConflictException()))

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

    @Test
    fun testExtractHttpCode() {
        assertEquals(403, SyncErrorKind.extractHttpCode(GoogleSheetsApiException(403, "Forbidden")))
        assertEquals(429, SyncErrorKind.extractHttpCode(IOException("HTTP 429 quotaExceeded")))
        assertEquals(503, SyncErrorKind.extractHttpCode(IOException("Server error status: 503")))
        assertEquals(null, SyncErrorKind.extractHttpCode(UnknownHostException("no host")))
    }

    @Test
    fun testSafeCodeAndFormatUserMessage() {
        val stage = "dashboard sync"

        // 1. RATE_LIMITED with HTTP code
        val rateLimited = GoogleSheetsApiException(429, "Too many requests to https://sheets.googleapis.com")
        assertEquals("dashboard sync, HTTP 429", SyncErrorKind.safeCode(stage, rateLimited))
        assertEquals(
            "Google is busy. This will retry on its own. (dashboard sync, HTTP 429)",
            SyncErrorKind.formatUserMessage(stage, rateLimited)
        )

        // 2. AUTH_EXPIRED with HTTP code
        val authExpired = GoogleSheetsApiException(401, "Bearer token expired")
        assertEquals("dashboard sync, HTTP 401", SyncErrorKind.safeCode(stage, authExpired))
        assertEquals(
            "Sign-in expired. Reconnect to continue. (dashboard sync, HTTP 401)",
            SyncErrorKind.formatUserMessage(stage, authExpired)
        )

        // 3. NO_NETWORK with exception class name
        val noNet = UnknownHostException("unable to resolve host")
        assertEquals("dashboard sync, UnknownHostException", SyncErrorKind.safeCode(stage, noNet))
        assertEquals(
            "No internet. This will sync when you are back online. (dashboard sync, UnknownHostException)",
            SyncErrorKind.formatUserMessage(stage, noNet)
        )

        // 4. SERVER_BUSY with HTTP code
        val serverBusy = GoogleSheetsApiException(500, "Internal Server Error")
        assertEquals("dashboard sync, HTTP 500", SyncErrorKind.safeCode(stage, serverBusy))
        assertEquals(
            "Google had a problem. This will retry on its own. (dashboard sync, HTTP 500)",
            SyncErrorKind.formatUserMessage(stage, serverBusy)
        )

        // 5. SHEET_CHANGED with HTTP code
        val sheetChanged = GoogleSheetsApiException(403, "The caller does not have permission")
        assertEquals("dashboard sync, HTTP 403", SyncErrorKind.safeCode(stage, sheetChanged))
        assertEquals(
            "The sheet was changed. Open Sync and Backup to repair. (dashboard sync, HTTP 403)",
            SyncErrorKind.formatUserMessage(stage, sheetChanged)
        )

        // 6. UNKNOWN with generic exception class name
        val unknownEx = IllegalArgumentException("Something invalid")
        assertEquals("dashboard sync, IllegalArgumentException", SyncErrorKind.safeCode(stage, unknownEx))
        assertEquals(
            "Something went wrong. Tap Details. (dashboard sync, IllegalArgumentException)",
            SyncErrorKind.formatUserMessage(stage, unknownEx)
        )
    }
}
