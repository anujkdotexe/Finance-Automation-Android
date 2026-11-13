package com.financeautomation.presentation.components

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import javax.xml.parsers.DocumentBuilderFactory

class ButtonPairStringLengthTest {

    @Test
    fun testButtonPairMaxLabelLengthRule() {
        val validLabel1 = "Cancel"
        val validLabel2 = "Save"
        val validLabel3 = "Save expense"
        val validLabel4 = "12345678901234" // exactly 14

        assertTrue(validLabel1.length <= MAX_BUTTON_PAIR_LABEL_LENGTH)
        assertTrue(validLabel2.length <= MAX_BUTTON_PAIR_LABEL_LENGTH)
        assertTrue(validLabel3.length <= MAX_BUTTON_PAIR_LABEL_LENGTH)
        assertTrue(validLabel4.length <= MAX_BUTTON_PAIR_LABEL_LENGTH)

        val invalidLabel = "Save repayment long"
        assertTrue(invalidLabel.length > MAX_BUTTON_PAIR_LABEL_LENGTH)
    }

    @Test
    fun testAllButtonPairStringsXmlLabelsUnder14Chars() {
        val stringsXmlFile = File("src/main/res/values/strings.xml")
        val fallbackFile = File("app/src/main/res/values/strings.xml")
        val fileToRead = if (stringsXmlFile.exists()) stringsXmlFile else fallbackFile
        assertTrue("strings.xml must exist", fileToRead.exists())

        val factory = DocumentBuilderFactory.newInstance()
        val builder = factory.newDocumentBuilder()
        val doc = builder.parse(fileToRead)
        val stringNodes = doc.getElementsByTagName("string")

        val buttonPairStringKeys = setOf(
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
            "error_53_action_open_console",
            "error_54_action_manual",
            "error_54_action_settings",
            "error_55_action_dashboard",
            "error_55_action_retry",
            "onboarding_notif_action_skip",
            "action_skip",
            "action_skip_this_time"
        )

        for (i in 0 until stringNodes.length) {
            val node = stringNodes.item(i)
            val key = node.attributes.getNamedItem("name")?.nodeValue
            if (key != null && buttonPairStringKeys.contains(key)) {
                val value = node.textContent.trim()
                assertTrue(
                    "String resource '$key' with text '$value' (len=${value.length}) exceeds max $MAX_BUTTON_PAIR_LABEL_LENGTH chars",
                    value.length <= MAX_BUTTON_PAIR_LABEL_LENGTH
                )
            }
        }
    }
}

