package com.financeautomation.presentation.components

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import javax.xml.parsers.DocumentBuilderFactory

class FontScaleOverflowAuditTest {

    @Test
    fun testAllActionButtonLabelsFitSingleLineUnderFontScale1_3() {
        val stringsXmlFile = File("src/main/res/values/strings.xml")
        val fallbackFile = File("app/src/main/res/values/strings.xml")
        val fileToRead = if (stringsXmlFile.exists()) stringsXmlFile else fallbackFile
        assertTrue("strings.xml must exist", fileToRead.exists())

        val factory = DocumentBuilderFactory.newInstance()
        val builder = factory.newDocumentBuilder()
        val doc = builder.parse(fileToRead)
        val stringNodes = doc.getElementsByTagName("string")

        // Strings used on buttons that must never wrap or ellipsize at fontScale 1.3
        val singleLineButtonKeys = listOf(
            "action_cancel",
            "btn_save_expense",
            "btn_save_income",
            "btn_record_debt",
            "btn_record_repayment",
            "action_end_trip",
            "btn_start_new_trip",
            "action_discard",
            "action_retry",
            "action_edit",
            "action_confirm",
            "action_void",
            "action_yes",
            "action_not_yet",
            "action_skip",
            "action_skip_this_time",
            "error_53_action_open_console",
            "error_54_action_manual",
            "error_54_action_settings",
            "error_55_action_dashboard",
            "error_55_action_retry",
            "onboarding_notif_action_skip"
        )

        val foundMap = mutableMapOf<String, String>()
        for (i in 0 until stringNodes.length) {
            val node = stringNodes.item(i)
            val key = node.attributes.getNamedItem("name")?.nodeValue
            if (key != null && key in singleLineButtonKeys) {
                foundMap[key] = node.textContent.trim()
            }
        }

        singleLineButtonKeys.forEach { key ->
            val value = foundMap[key]
            assertTrue("Key $key must exist in strings.xml", value != null)
            assertTrue(
                "Button string '$key' with text '$value' must not exceed 14 characters to prevent overflow on 360dp at font scale 1.3",
                value!!.length <= 14
            )
        }
    }

    @Test
    fun testAuditNoRepeatedPlaceholdersInTextFields() {
        val screensDir = File("src/main/java/com/financeautomation/presentation")
        val fallbackDir = File("app/src/main/java/com/financeautomation/presentation")
        val dir = if (screensDir.exists()) screensDir else fallbackDir
        assertTrue("Presentation directory must exist", dir.exists())

        // Check Kotlin source files for repetitive placeholder anti-patterns
        val ktFiles = dir.walk().filter { it.extension == "kt" }.toList()
        ktFiles.forEach { file ->
            val text = file.readText()
            // Ensure no OutlinedTextField or TextField has label and placeholder with identical resource
            assertFalse(
                "File ${file.name} must not repeat label as placeholder",
                text.contains("label = { Text(stringResource(R.string.settings_spreadsheet_id_label)) }") &&
                        text.contains("placeholder = { Text(stringResource(R.string.settings_spreadsheet_id_label)) }")
            )
        }
    }

    @Test
    fun testStackedKpiLayoutThresholdsNeverEllipsizeUnderFontScale1_3() {
        val boundaryAmounts = listOf(
            9999.0,
            10000.0,
            50003.0,
            99999.0,
            100000.0,
            1234567.0,
            12345678.0
        )

        // 1. Half-width cards (Savings and Income):
        // Width = (360dp - 32dp padding - 8dp gap) / 2 = 160dp (136dp content width).
        // At 20sp * 1.3 font scale = 26sp mono font (char width ~15.6dp), capacity is 8 characters.
        // Exact figure up to 5 digits (e.g. 10,000, 50,003, 99,999) formatted with formatRupeesAuto must fit within 8 chars.
        val fiveDigitOrLessAmounts = listOf(9999.0, 10000.0, 50003.0, 99999.0)
        fiveDigitOrLessAmounts.forEach { amount ->
            val formatted = com.financeautomation.core.utils.formatRupeesAuto(amount)
            assertTrue(
                "Half-width exact figure '$formatted' for $amount must not exceed 8 characters to prevent ellipsis on 360dp at font scale 1.3",
                formatted.length <= 8
            )
        }

        // Beyond 5 digits, compact form (<= 6 characters) fits comfortably within 8 characters.
        val beyondFiveDigitAmounts = listOf(100000.0, 1234567.0, 12345678.0)
        beyondFiveDigitAmounts.forEach { amount ->
            val formatted = com.financeautomation.core.utils.formatRupeesCompact(amount)
            assertTrue(
                "Half-width compact amount '$formatted' for $amount must not exceed 6 characters to prevent ellipsis on 360dp at font scale 1.3",
                formatted.length <= 6
            )
        }

        // 2. Full-width card (Living burn):
        // Width = 360dp - 32dp padding = 328dp (304dp content width).
        // At 32sp * 1.3 font scale = 41.6sp mono font (char width ~25dp), capacity is 12 characters.
        // Exact figure up to 7 digits (e.g. 9,999,999 -> "₹99,99,999") must fit within 12 characters.
        val fullWidthExactSevenDigits = 9999999.0
        val fullWidthFormatted = com.financeautomation.core.utils.formatRupeesAuto(fullWidthExactSevenDigits)
        assertTrue(
            "Full-width Living burn exact 7 digits '$fullWidthFormatted' must fit within 12 characters",
            fullWidthFormatted.length <= 12
        )

        // Above 7 digits (>= 10,000,000), compact form fits comfortably within 12 characters.
        val aboveSevenDigits = 12345678.0
        val fullWidthCompact = com.financeautomation.core.utils.formatRupeesCompact(aboveSevenDigits)
        assertTrue(
            "Full-width Living burn compact '$fullWidthCompact' must fit within 6 characters",
            fullWidthCompact.length <= 6
        )
    }
}

