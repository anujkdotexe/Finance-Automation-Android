package com.financeautomation.domain.utils

import com.financeautomation.core.model.TransactionStatus
import com.financeautomation.domain.model.Transaction
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AccountingUtilsTest {

    private fun createTxn(
        id: String = "txn_1",
        totalAmount: Double,
        myShare: Double,
        owedToMe: Double,
        companions: String,
        status: TransactionStatus = TransactionStatus.PENDING
    ): Transaction {
        return Transaction(
            id = id,
            timestamp = 1731500000000L,
            dateSerial = 46330,
            timeSerial = 0.5,
            account = "UPI",
            merchant = "Cafe",
            category = "Food & Drink",
            totalAmount = totalAmount,
            myShare = myShare,
            owedToMe = owedToMe,
            companions = companions,
            status = status
        )
    }

    @Test
    fun testCustomSplit_aliceAndBobUnequalShares_givesOneRowLabelledAliceBob_withFullOwedAmount() {
        // Custom split: total 300, myShare 150, owedToMe 150, companions "Alice, Bob".
        // Expected equal share would have been 100.
        val txn = createTxn(
            totalAmount = 300.0,
            myShare = 150.0,
            owedToMe = 150.0,
            companions = "Alice, Bob"
        )

        val receivables = AccountingUtils.calculateReceivablesForTransaction(txn)

        // Must NOT name any row "Others"
        assertFalse(
            "No row should ever be named Others",
            receivables.any { it.displayName.equals("Others", ignoreCase = true) || it.key.equals("Others", ignoreCase = true) }
        )

        // Must attribute full owed amount to one combined row labelled "Alice, Bob"
        assertEquals(1, receivables.size)
        val groupShare = receivables.first()
        assertEquals("Alice, Bob", groupShare.displayName)
        assertEquals("alice, bob", groupShare.key)
        assertEquals(150.0, groupShare.amount, 0.001)
    }

    @Test
    fun testEqualSplit_stillDividesAcrossCompanions() {
        // Equal split: total 300, myShare 100, owedToMe 200, companions "Alice, Bob".
        val txn = createTxn(
            totalAmount = 300.0,
            myShare = 100.0,
            owedToMe = 200.0,
            companions = "Alice, Bob"
        )

        val receivables = AccountingUtils.calculateReceivablesForTransaction(txn)

        assertEquals(2, receivables.size)
        val alice = receivables.find { it.key == "alice" }
        val bob = receivables.find { it.key == "bob" }
        assertTrue(alice != null)
        assertTrue(bob != null)
        assertEquals("Alice", alice!!.displayName)
        assertEquals(100.0, alice.amount, 0.001)
        assertEquals("Bob", bob!!.displayName)
        assertEquals(100.0, bob.amount, 0.001)
    }

    @Test
    fun testPersonalCase_withOwedAboveZero_inventsNoPerson() {
        val txn = createTxn(
            totalAmount = 100.0,
            myShare = 50.0,
            owedToMe = 50.0,
            companions = "Personal"
        )

        val receivables = AccountingUtils.calculateReceivablesForTransaction(txn)
        assertTrue("Personal case with owed above zero should return empty list and invent no person", receivables.isEmpty())
    }
}
