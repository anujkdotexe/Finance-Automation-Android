package com.financeautomation.core.utils

import com.financeautomation.data.remote.RawHttpErrorEntry
import com.financeautomation.domain.model.CompatibilityReportBuilder
import com.financeautomation.domain.model.CompatibilityReportData
import com.financeautomation.domain.model.DeviceHeaderInfo
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PrivacyScrubberScrubbingTest {

    @Test
    fun privacyScrubberScrubsBearerTokensAndYa29Tokens() {
        val raw = "Authorization: Bearer ya29.a0AfH6SMDh7823hdjkshfkjsdhf9823hfsdjkf"
        val scrubbed = PrivacyScrubber.scrub(raw)
        assertFalse("Raw token must not appear in scrubbed output: $scrubbed", scrubbed.contains("ya29.a0AfH6"))
        assertTrue("Scrubbed output must contain [token]: $scrubbed", scrubbed.contains("[token]"))
    }

    @Test
    fun privacyScrubberScrubsEmailsAndRupeeAmounts() {
        val raw = "Sent ₹ 4,500.50 to user.name@domain.com via UPI"
        val scrubbed = PrivacyScrubber.scrub(raw)
        assertFalse("Amount must be scrubbed: $scrubbed", scrubbed.contains("4,500.50"))
        assertFalse("Email must be scrubbed: $scrubbed", scrubbed.contains("user.name@domain.com"))
        assertTrue("Scrubbed output must contain [amount]: $scrubbed", scrubbed.contains("[amount]"))
        assertTrue("Scrubbed output must contain [email]: $scrubbed", scrubbed.contains("[email]"))
    }

    @Test
    fun compatibilityReportScrubsRawGoogleErrorsCompletely() {
        val secretEmail = "secret.user@bank.com"
        val secretToken = "ya29.a0AfH6SMBF9812739812738912hsdjkfhsdfkjhsdkfj"
        val secretAmount = "Rs. 15,000.00"
        val secretSpreadsheetId = "1BxiMVs0XRA5nFMdKvBdBZjgmUUqptlbs74OgvE2upms"

        val rawErrorJson = """
            {
               "error": {
                  "code": 403,
                  "message": "The caller $secretEmail with token Bearer $secretToken does not have permission for spreadsheet $secretSpreadsheetId on transaction $secretAmount"
               }
            }
        """.trimIndent()

        val reportData = CompatibilityReportData(
            header = DeviceHeaderInfo("8.0", "beta", "Google", "Google", "Pixel 8", 34, "UQ1A"),
            rawGoogleErrors = listOf(
                RawHttpErrorEntry("2026-11-18T12:00:00Z", "SHEETS_BATCH_UPDATE", 403, rawErrorJson)
            )
        )

        val reportMarkdown = CompatibilityReportBuilder.buildMarkdown(reportData)

        // Assert NONE of the secrets appear in the generated report
        assertFalse("Report must not contain user email", reportMarkdown.contains(secretEmail))
        assertFalse("Report must not contain auth token", reportMarkdown.contains(secretToken))
        assertFalse("Report must not contain amount", reportMarkdown.contains(secretAmount))
        assertFalse("Report must not contain spreadsheet ID", reportMarkdown.contains(secretSpreadsheetId))

        // Assert placeholder is present (quoted payloads > 13 chars are scrubbed as [text])
        assertTrue("Report must contain [text] placeholder", reportMarkdown.contains("[text]"))
    }
}
