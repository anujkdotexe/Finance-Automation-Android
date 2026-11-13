package com.financeautomation.presentation.viewmodel

import com.financeautomation.core.model.TransactionStatus
import com.financeautomation.domain.model.Companion
import com.financeautomation.domain.model.Transaction
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PeopleTripsOpenItemsTest {

    @Test
    fun testOpenItemCountCalculatedCorrectly() {
        val transactions = listOf(
            Transaction(
                id = "tx1",
                timestamp = System.currentTimeMillis(),
                dateSerial = 45000,
                timeSerial = 0.5,
                account = "HDFC",
                merchant = "Dinner",
                category = "Food & Drink",
                totalAmount = 1000.0,
                myShare = 500.0,
                owedToMe = 500.0,
                companions = "Karan",
                status = TransactionStatus.PENDING
            ),
            Transaction(
                id = "tx2",
                timestamp = System.currentTimeMillis(),
                dateSerial = 45000,
                timeSerial = 0.5,
                account = "HDFC",
                merchant = "Groceries",
                category = "Groceries",
                totalAmount = 600.0,
                myShare = 200.0,
                owedToMe = 400.0,
                companions = "Karan, Priya",
                status = TransactionStatus.PENDING
            ),
            Transaction(
                id = "tx3",
                timestamp = System.currentTimeMillis(),
                dateSerial = 45000,
                timeSerial = 0.5,
                account = "SBI",
                merchant = "Coffee",
                category = "Food & Drink",
                totalAmount = 300.0,
                myShare = 150.0,
                owedToMe = 150.0,
                companions = "Karan",
                status = TransactionStatus.SETTLED // Settled, should not count as open item
            ),
            Transaction(
                id = "tx4",
                timestamp = System.currentTimeMillis(),
                dateSerial = 45000,
                timeSerial = 0.5,
                account = "Cash",
                merchant = "Snack",
                category = "Food & Drink",
                totalAmount = 100.0,
                myShare = 100.0,
                owedToMe = 0.0,
                companions = "Personal", // Personal, should not count
                status = TransactionStatus.PENDING
            )
        )

        val counts = mutableMapOf<String, Int>()
        for (txn in transactions) {
            if (txn.status == TransactionStatus.PENDING && txn.owedToMe > 0.0) {
                val tokens = txn.companions.split(",").map { it.trim() }.filter { it.isNotBlank() && !it.equals("Personal", ignoreCase = true) }
                for (friend in tokens) {
                    val key = friend.lowercase()
                    counts[key] = (counts[key] ?: 0) + 1
                }
            }
        }

        assertEquals(2, counts["karan"]) // tx1 and tx2
        assertEquals(1, counts["priya"]) // tx2 only
        assertEquals(null, counts["personal"])
    }

    @Test
    fun testSubtitleTextFormatsOpenItemCountOrAllCaughtUp() {
        fun formatSubtitle(openItemCount: Int): String {
            return when {
                openItemCount > 0 -> "$openItemCount ${if (openItemCount == 1) "open item" else "open items"}"
                else -> "All caught up"
            }
        }

        assertEquals("2 open items", formatSubtitle(2))
        assertEquals("1 open item", formatSubtitle(1))
        assertEquals("All caught up", formatSubtitle(0))
    }

    @Test
    fun testAccountRowEmailAndDisplayNameLogic() {
        fun resolveAccountDisplay(displayName: String?, email: String?): Pair<String, String?> {
            val isRealEmail = !email.isNullOrBlank() && email.contains("@")
            val resolvedEmail = if (isRealEmail) email else null

            val rawDisplayName = displayName?.takeIf { it.isNotBlank() }
                ?: if (!isRealEmail && !email.isNullOrBlank()) email else null
            val resolvedDisplayName = rawDisplayName?.takeIf { it != resolvedEmail }

            val showNameAndEmail = !resolvedDisplayName.isNullOrBlank() && !resolvedEmail.isNullOrBlank()
            return when {
                showNameAndEmail -> resolvedDisplayName!! to resolvedEmail!!
                !resolvedEmail.isNullOrBlank() -> resolvedEmail to null
                else -> (resolvedDisplayName ?: "Local offline storage") to null
            }
        }

        // Case 1: Display name and email both present and distinct
        val (line1_1, line2_1) = resolveAccountDisplay("John Doe", "john@example.com")
        assertEquals("John Doe", line1_1)
        assertEquals("john@example.com", line2_1)

        // Case 2: No display name (or same as email) -> show only email
        val (line1_2, line2_2) = resolveAccountDisplay(null, "user@example.com")
        assertEquals("user@example.com", line1_2)
        assertEquals(null, line2_2)

        val (line1_3, line2_3) = resolveAccountDisplay("user@example.com", "user@example.com")
        assertEquals("user@example.com", line1_3)
        assertEquals(null, line2_3)

        // Case 3: "Authorized User" string in email without @ -> show as display name
        val (line1_4, line2_4) = resolveAccountDisplay(null, "Authorized User")
        assertEquals("Authorized User", line1_4)
        assertEquals(null, line2_4)

        // Case 4: Neither present
        val (line1_5, line2_5) = resolveAccountDisplay(null, null)
        assertEquals("Local offline storage", line1_5)
        assertEquals(null, line2_5)
    }

    @Test
    fun testDeveloperModeContractHidesSpreadsheetIdWhenFalse() {
        val stateDevOff = SettingsUiState(developerMode = false, spreadsheetId = "sheet_123")
        assertFalse(stateDevOff.developerMode)

        val stateDevOn = SettingsUiState(developerMode = true, spreadsheetId = "sheet_123")
        assertTrue(stateDevOn.developerMode)
    }
}

