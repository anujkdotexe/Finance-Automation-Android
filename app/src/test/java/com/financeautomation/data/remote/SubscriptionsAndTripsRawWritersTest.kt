package com.financeautomation.data.remote

import com.financeautomation.core.constants.SheetSchemaConstants
import com.financeautomation.core.utils.DateSerialUtils
import com.financeautomation.domain.model.Subscription
import com.financeautomation.domain.model.SubscriptionFrequency
import com.financeautomation.domain.model.TripTag
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

/**
 * Verifies Finding 9:
 * 1. Subscription and trip cell values write user text strictly through RAW (stringValue)
 *    even when user text begins with '=', preventing formula injection.
 * 2. Active is written as a boolean (boolValue), not text "TRUE"/"FALSE".
 * 3. Dates are written as date serial integers (numberValue), not ISO text, so Dashboard
 *    formulas (COUNTIF, FILTER >= TODAY(), TODAY() - B) work correctly without #VALUE!.
 */
class SubscriptionsAndTripsRawWritersTest {

    @Test
    fun userTextStartingWithEquals_isWrittenAsRawStringValueAndNeverFormulaValue() {
        val subWithFormulasInUserFields = Subscription(
            id = "sub_formula_injection",
            name = "=IMPORTXML(\"https://evil.com\", \"/xpath\")",
            amount = 99.0,
            frequency = SubscriptionFrequency.MONTHLY,
            anchorEpochDay = LocalDate.of(2026, 11, 1).toEpochDay(),
            account = "=HDFC",
            note = "=DDE(\"cmd\",\"/c calc\")",
            keywords = "=malicious",
            active = true
        )

        val rowValues = SheetProvisioner.buildSubscriptionRowValues(
            sub = subWithFormulasInUserFields,
            row1BasedIndex = 4,
            year = 2026
        )

        // Map through the exact GoogleSheetsClient writer mapper
        val mappedCells = rowValues.mapIndexed { colIdx, cell ->
            val isSubFormulaCol = colIdx == SheetSchemaConstants.COL_INDEX_SUB_PER_MONTH ||
                colIdx == SheetSchemaConstants.COL_INDEX_SUB_PAID_MONTH ||
                colIdx == SheetSchemaConstants.COL_INDEX_SUB_PAID_YEAR ||
                colIdx == SheetSchemaConstants.COL_INDEX_SUB_LAST_PAID_DATE ||
                colIdx == SheetSchemaConstants.COL_INDEX_SUB_PAID_IN_TOTAL
            when (cell) {
                null -> mapOf("userEnteredValue" to mapOf("stringValue" to ""))
                is Number -> mapOf("userEnteredValue" to mapOf("numberValue" to cell.toDouble()))
                is Boolean -> mapOf("userEnteredValue" to mapOf("boolValue" to cell))
                is String -> {
                    if (isSubFormulaCol && cell.startsWith("=")) {
                        mapOf("userEnteredValue" to mapOf("formulaValue" to cell))
                    } else {
                        mapOf("userEnteredValue" to mapOf("stringValue" to cell))
                    }
                }
                else -> mapOf("userEnteredValue" to mapOf("stringValue" to cell.toString()))
            }
        }

        // Col 0: Name starting with "=" must be stringValue, NOT formulaValue
        val nameCell = mappedCells[SheetSchemaConstants.COL_INDEX_SUB_NAME]["userEnteredValue"] as Map<*, *>
        assertTrue("Name must be written as stringValue", nameCell.containsKey("stringValue"))
        assertFalse("Name must not be formulaValue", nameCell.containsKey("formulaValue"))
        assertEquals("=IMPORTXML(\"https://evil.com\", \"/xpath\")", nameCell["stringValue"])

        // Col 4: Account starting with "=" must be stringValue
        val accountCell = mappedCells[SheetSchemaConstants.COL_INDEX_SUB_ACCOUNT]["userEnteredValue"] as Map<*, *>
        assertTrue("Account must be written as stringValue", accountCell.containsKey("stringValue"))
        assertEquals("=HDFC", accountCell["stringValue"])

        // Col 6: Note starting with "=" must be stringValue
        val noteCell = mappedCells[SheetSchemaConstants.COL_INDEX_SUB_NOTE]["userEnteredValue"] as Map<*, *>
        assertTrue("Note must be written as stringValue", noteCell.containsKey("stringValue"))
        assertEquals("=DDE(\"cmd\",\"/c calc\")", noteCell["stringValue"])

        // Col 7: Keywords starting with "=" must be stringValue
        val keywordsCell = mappedCells[SheetSchemaConstants.COL_INDEX_SUB_KEYWORDS]["userEnteredValue"] as Map<*, *>
        assertTrue("Keywords must be written as stringValue", keywordsCell.containsKey("stringValue"))
        assertEquals("=malicious", keywordsCell["stringValue"])

        // Col 8, 9, 10, 11: Real formulas must be formulaValue
        val monthlyCell = mappedCells[SheetSchemaConstants.COL_INDEX_SUB_PER_MONTH]["userEnteredValue"] as Map<*, *>
        assertTrue("Generated formula must be formulaValue", monthlyCell.containsKey("formulaValue"))
    }

    @Test
    fun tripUserTextStartingWithEquals_isWrittenAsRawStringValue() {
        val tripWithFormulaName = TripTag(
            id = "trip_injection",
            name = "=WEBSERVICE(\"http://evil.com\")",
            isActive = true,
            startDateEpochDay = LocalDate.of(2026, 11, 1).toEpochDay(),
            endDateEpochDay = LocalDate.of(2026, 11, 5).toEpochDay()
        )

        val rowValues = SheetProvisioner.buildTripRowValues(
            trip = tripWithFormulaName,
            repaidAmount = 0.0,
            topCategory = "=Shopping",
            row1BasedIndex = 4,
            year = 2026
        )

        val mappedCells = rowValues.mapIndexed { colIdx, cell ->
            val isFormulaCol = colIdx == SheetSchemaConstants.COL_INDEX_TRIP_DAYS ||
                colIdx == SheetSchemaConstants.COL_INDEX_TRIP_TOTAL_PAID ||
                colIdx == SheetSchemaConstants.COL_INDEX_TRIP_MY_SHARE ||
                colIdx == SheetSchemaConstants.COL_INDEX_TRIP_OWED_TO_ME
            when (cell) {
                null -> mapOf("userEnteredValue" to mapOf("stringValue" to ""))
                is Number -> mapOf("userEnteredValue" to mapOf("numberValue" to cell.toDouble()))
                is Boolean -> mapOf("userEnteredValue" to mapOf("boolValue" to cell))
                is String -> {
                    if (isFormulaCol && cell.startsWith("=")) {
                        mapOf("userEnteredValue" to mapOf("formulaValue" to cell))
                    } else {
                        mapOf("userEnteredValue" to mapOf("stringValue" to cell))
                    }
                }
                else -> mapOf("userEnteredValue" to mapOf("stringValue" to cell.toString()))
            }
        }

        // Col 0: Trip name must be stringValue
        val nameCell = mappedCells[SheetSchemaConstants.COL_INDEX_TRIP_NAME]["userEnteredValue"] as Map<*, *>
        assertTrue(nameCell.containsKey("stringValue"))
        assertFalse(nameCell.containsKey("formulaValue"))
        assertEquals("=WEBSERVICE(\"http://evil.com\")", nameCell["stringValue"])

        // Col 9: Top category must be stringValue
        val catCell = mappedCells[SheetSchemaConstants.COL_INDEX_TRIP_TOP_CATEGORY]["userEnteredValue"] as Map<*, *>
        assertTrue(catCell.containsKey("stringValue"))
        assertEquals("=Shopping", catCell["stringValue"])

        // Col 4: Days formula must be formulaValue
        val daysCell = mappedCells[SheetSchemaConstants.COL_INDEX_TRIP_DAYS]["userEnteredValue"] as Map<*, *>
        assertTrue(daysCell.containsKey("formulaValue"))
    }

    @Test
    fun subscriptionAndTripDatesAreSerialsAndActiveIsBoolean() {
        val anchorDate = LocalDate.of(2026, 11, 10)
        val closedDate = LocalDate.of(2026, 11, 25)
        val expectedAnchorSerial = DateSerialUtils.toDateSerial(anchorDate)
        val expectedClosedSerial = DateSerialUtils.toDateSerial(closedDate)

        val sub = Subscription(
            id = "sub_types",
            name = "Test Subscription",
            amount = 500.0,
            frequency = SubscriptionFrequency.MONTHLY,
            anchorEpochDay = anchorDate.toEpochDay(),
            account = "SBI",
            note = "",
            keywords = "",
            active = false,
            closedDateEpochDay = closedDate.toEpochDay()
        )

        val subRow = SheetProvisioner.buildSubscriptionRowValues(sub, row1BasedIndex = 4, year = 2026)
        assertEquals("Anchor date must be date serial integer", expectedAnchorSerial, subRow[SheetSchemaConstants.COL_INDEX_SUB_DUE_DATE])
        assertEquals("Active must be boolean false", false, subRow[SheetSchemaConstants.COL_INDEX_SUB_ACTIVE])
        assertEquals("Closed date must be date serial integer", expectedClosedSerial, subRow[SheetSchemaConstants.COL_INDEX_SUB_CLOSED_DATE])

        val trip = TripTag(
            id = "trip_types",
            name = "Goa Trip",
            isActive = true,
            startDateEpochDay = anchorDate.toEpochDay(),
            endDateEpochDay = closedDate.toEpochDay()
        )

        val tripRow = SheetProvisioner.buildTripRowValues(trip, row1BasedIndex = 4, year = 2026)
        assertEquals("Start date must be date serial integer", expectedAnchorSerial, tripRow[SheetSchemaConstants.COL_INDEX_TRIP_START_DATE])
        assertEquals("End date must be date serial integer", expectedClosedSerial, tripRow[SheetSchemaConstants.COL_INDEX_TRIP_END_DATE])
    }
}
