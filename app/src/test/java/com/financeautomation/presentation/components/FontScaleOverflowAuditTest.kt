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
}

