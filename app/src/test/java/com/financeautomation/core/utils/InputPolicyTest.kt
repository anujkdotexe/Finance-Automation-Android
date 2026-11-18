package com.financeautomation.core.utils

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.text.Normalizer

class InputPolicyTest {

    @Test
    fun cleanNameTrimsAndCollapsesInnerWhitespace() {
        val raw = "   Food    &    Dining   "
        val clean = InputPolicy.cleanName(raw)
        assertEquals("Food & Dining", clean)
    }

    @Test
    fun cleanNameStripsBidiAndControlCharacters() {
        // \u202E is RIGHT-TO-LEFT OVERRIDE, \u0000 is null char, \u001F is control char
        val raw = "Test\u202E\u0000String\u001F"
        val clean = InputPolicy.cleanName(raw)
        assertEquals("TestString", clean)
    }

    @Test
    fun cleanNameNormalizesToNFC() {
        // 'e' + combining acute accent U+0301 (NFD) -> 'é' U+00E9 (NFC)
        val nfd = "Cafe\u0301"
        val clean = InputPolicy.cleanName(nfd)
        val expectedNfc = "Café"
        assertEquals(expectedNfc, clean)
        assertTrue(Normalizer.isNormalized(clean, Normalizer.Form.NFC))
    }

    @Test
    fun validateNameRejectsBlank() {
        val res = InputPolicy.validateName("   \t  \n  ")
        assertFalse(res.isValid)
        assertEquals(InputPolicy.InputError.BLANK, (res as InputPolicy.ValidationResult.Error).reason)
    }

    @Test
    fun validateNameRejectsTooLong() {
        val long500 = "A".repeat(500)
        val res = InputPolicy.validateName(long500, maxLength = 40)
        assertFalse(res.isValid)
        val err = res as InputPolicy.ValidationResult.Error
        assertEquals(InputPolicy.InputError.TOO_LONG, err.reason)
        assertEquals(40, err.limit)
    }

    @Test
    fun validateNameRejectsForbiddenDelimiters() {
        val pipeRes = InputPolicy.validateName("Category|Name")
        assertFalse(pipeRes.isValid)
        assertEquals(InputPolicy.InputError.INVALID_CHARS, (pipeRes as InputPolicy.ValidationResult.Error).reason)

        val colonRes = InputPolicy.validateName("tag:active")
        assertFalse(colonRes.isValid)
        assertEquals(InputPolicy.InputError.INVALID_CHARS, (colonRes as InputPolicy.ValidationResult.Error).reason)

        val commaRes = InputPolicy.validateName("Raj, Jr")
        assertFalse(commaRes.isValid)
        assertEquals(InputPolicy.InputError.INVALID_CHARS, (commaRes as InputPolicy.ValidationResult.Error).reason)
    }

    @Test
    fun validateNameRejectsReservedNamesCaseInsensitively() {
        val reservedCases = listOf(
            "Cash", "cash", "CASH",
            "Other", "other",
            "Savings", "savings", "Saving",
            "Transfer", "transfer",
            "Personal", "personal",
            "Dashboard", "Config"
        )
        for (reserved in reservedCases) {
            val res = InputPolicy.validateName(reserved)
            assertFalse("Expected '$reserved' to be rejected as reserved", res.isValid)
            assertEquals(InputPolicy.InputError.RESERVED_NAME, (res as InputPolicy.ValidationResult.Error).reason)
        }
    }

    @Test
    fun validateNameRejectsDuplicatesCaseInsensitivelyAndUnderNFC() {
        val existing = listOf("Groceries", "Café")
        val dup1 = InputPolicy.validateName("groceries", existing)
        assertFalse(dup1.isValid)
        assertEquals(InputPolicy.InputError.DUPLICATE_NAME, (dup1 as InputPolicy.ValidationResult.Error).reason)

        // Decomposed NFD form of Café
        val dup2 = InputPolicy.validateName("Cafe\u0301", existing)
        assertFalse(dup2.isValid)
        assertEquals(InputPolicy.InputError.DUPLICATE_NAME, (dup2 as InputPolicy.ValidationResult.Error).reason)
    }

    @Test
    fun validateNameAcceptsValidComplexNames() {
        val validCases = listOf(
            "He said \"hi\"",
            "Gifts*",
            "=SUM(A1)",
            "~tilde",
            "Rent 🏠",
            "Trip 2026",
            "A"
        )
        for (name in validCases) {
            val res = InputPolicy.validateName(name)
            assertTrue("Expected '$name' to be valid", res.isValid)
        }
    }

    @Test
    fun validateNotePreservesNewlinesAndEnforcesLimit() {
        val validNote = "Line 1\nLine 2\tTabbed"
        val res = InputPolicy.validateNote(validNote, maxLength = 120)
        assertTrue(res.isValid)
        assertEquals(validNote, (res as InputPolicy.ValidationResult.Valid).cleanValue)

        val tooLong = "A".repeat(121)
        val errRes = InputPolicy.validateNote(tooLong, maxLength = 120)
        assertFalse(errRes.isValid)
        assertEquals(InputPolicy.InputError.TOO_LONG, (errRes as InputPolicy.ValidationResult.Error).reason)
    }

    @Test
    fun cleanDigits4KeepsOnlyDigitsUpToFour() {
        assertEquals("1824", InputPolicy.cleanDigits4("1824"))
        assertEquals("1824", InputPolicy.cleanDigits4("XXXX 1824"))
        assertEquals("1234", InputPolicy.cleanDigits4("1234567"))
        assertEquals("", InputPolicy.cleanDigits4("abcd"))
    }

    @Test
    fun extractSpreadsheetIdFromBareIdAndFullUrl() {
        val bareId = "1BxiMVs0XRA5nFMdKvBdBZjgmUUqptlbs74OgvE2upms"
        assertEquals(bareId, InputPolicy.extractSpreadsheetId(bareId))

        val fullUrl = "https://docs.google.com/spreadsheets/d/1BxiMVs0XRA5nFMdKvBdBZjgmUUqptlbs74OgvE2upms/edit#gid=0"
        assertEquals(bareId, InputPolicy.extractSpreadsheetId(fullUrl))

        val sharingUrl = "https://docs.google.com/spreadsheets/d/1BxiMVs0XRA5nFMdKvBdBZjgmUUqptlbs74OgvE2upms/edit?usp=sharing"
        assertEquals(bareId, InputPolicy.extractSpreadsheetId(sharingUrl))

        assertNull(InputPolicy.extractSpreadsheetId("abc"))
        assertNull(InputPolicy.extractSpreadsheetId("https://google.com/search?q=test"))
        assertNull(InputPolicy.extractSpreadsheetId("   "))
    }

    @Test
    fun validateSpreadsheetIdReturnsExpectedResults() {
        val valid = InputPolicy.validateSpreadsheetId("1BxiMVs0XRA5nFMdKvBdBZjgmUUqptlbs74OgvE2upms")
        assertTrue(valid.isValid)

        val invalid = InputPolicy.validateSpreadsheetId("invalid@sheet#id")
        assertFalse(invalid.isValid)
        assertEquals(InputPolicy.InputError.INVALID_SPREADSHEET_ID, (invalid as InputPolicy.ValidationResult.Error).reason)
    }
}
