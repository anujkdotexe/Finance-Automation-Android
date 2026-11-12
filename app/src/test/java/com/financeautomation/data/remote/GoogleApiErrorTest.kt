package com.financeautomation.data.remote

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class GoogleApiErrorTest {

    @Test
    fun testParseLocaleError400() {
        val json400 = """
            {
              "error": {
                "code": 400,
                "message": "Invalid locale: en_IN. Supported locales can be found in the Google Sheets documentation.",
                "status": "INVALID_ARGUMENT",
                "errors": [
                  {
                    "message": "Invalid locale: en_IN. Supported locales can be found in the Google Sheets documentation.",
                    "domain": "global",
                    "reason": "badRequest"
                  }
                ]
              }
            }
        """.trimIndent()

        val error = GoogleApiError.parse("sheets.batchUpdate", 400, json400)

        assertEquals("sheets.batchUpdate", error.endpointId)
        assertEquals(400, error.httpStatus)
        assertEquals("INVALID_ARGUMENT", error.status)
        assertEquals("badRequest", error.reason)
        assertTrue(error.message.contains("Invalid locale: en_IN"))
        assertEquals("sheets.batchUpdate HTTP 400 badRequest", error.toLogcatString())
    }

    @Test
    fun testParseServiceDisabled403() {
        val json403 = """
            {
              "error": {
                "code": 403,
                "message": "Google Sheets API has not been used in project 123456789 before or it is disabled. Enable it by visiting https://console.developers.google.com/apis/api/sheets.googleapis.com/overview?project=123456789 then retry.",
                "status": "PERMISSION_DENIED",
                "details": [
                  {
                    "@type": "type.googleapis.com/google.rpc.ErrorInfo",
                    "reason": "SERVICE_DISABLED",
                    "domain": "googleapis.com",
                    "metadata": {
                      "service": "sheets.googleapis.com"
                    }
                  }
                ]
              }
            }
        """.trimIndent()

        val error = GoogleApiError.parse("sheets.batchUpdate", 403, json403)

        assertEquals("sheets.batchUpdate", error.endpointId)
        assertEquals(403, error.httpStatus)
        assertEquals("PERMISSION_DENIED", error.status)
        assertEquals("SERVICE_DISABLED", error.reason)
        assertEquals("sheets.batchUpdate HTTP 403 SERVICE_DISABLED", error.toLogcatString())
    }

    @Test
    fun testParseNotFound404() {
        val json404 = """
            {
              "error": {
                "code": 404,
                "message": "Requested entity was not found.",
                "status": "NOT_FOUND",
                "errors": [
                  {
                    "message": "Requested entity was not found.",
                    "domain": "global",
                    "reason": "notFound"
                  }
                ]
              }
            }
        """.trimIndent()

        val error = GoogleApiError.parse("sheets.get", 404, json404)

        assertEquals("sheets.get", error.endpointId)
        assertEquals(404, error.httpStatus)
        assertEquals("NOT_FOUND", error.status)
        assertEquals("notFound", error.reason)
        assertEquals("Requested entity was not found.", error.message)
        assertEquals("sheets.get HTTP 404 notFound", error.toLogcatString())
    }

    @Test
    fun testMessageTruncationBeyond300Characters() {
        val longText = "A".repeat(500)
        val json = """
            {
              "error": {
                "code": 500,
                "message": "$longText",
                "status": "INTERNAL"
              }
            }
        """.trimIndent()

        val error = GoogleApiError.parse("sheets.values.append", 500, json)

        assertEquals(300, error.message.length)
        assertEquals("INTERNAL", error.status)
        assertEquals("sheets.values.append HTTP 500 INTERNAL", error.toLogcatString())
    }

    @Test
    fun testNonJsonResponseBodyFallback() {
        val rawHtml = "<html><head><title>502 Bad Gateway</title></head><body>Proxy error</body></html>"
        val error = GoogleApiError.parse("drive.files.create", 502, rawHtml)

        assertEquals("drive.files.create", error.endpointId)
        assertEquals(502, error.httpStatus)
        assertNull(error.status)
        assertNull(error.reason)
        assertEquals(rawHtml, error.message)
        assertEquals("drive.files.create HTTP 502 UNKNOWN", error.toLogcatString())
    }
}
