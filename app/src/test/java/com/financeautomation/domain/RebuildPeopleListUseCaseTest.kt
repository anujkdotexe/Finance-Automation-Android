package com.financeautomation.domain

import com.financeautomation.domain.model.LedgerDebit
import com.financeautomation.domain.model.LedgerPayable
import com.financeautomation.domain.model.LedgerSnapshot
import com.financeautomation.domain.usecase.RebuildPeopleListUseCase
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RebuildPeopleListUseCaseTest {

    private val useCase = RebuildPeopleListUseCase()

    @Test
    fun testRebuildPeopleListFiltersOutTransactionAndPayableIds() {
        val debits = listOf(
            LedgerDebit(
                tab = "Nov26",
                row = 4,
                uuid = "txn_001",
                dateSerial = 46330,
                timeSerial = 0.5,
                account = "SBI",
                item = "Breakfast",
                tripTag = null,
                category = "Food & Drink",
                total = 100.0,
                companions = "Personal",
                myShare = 100.0,
                owed = 0.0,
                status = "Personal"
            ),
            LedgerDebit(
                tab = "Nov26",
                row = 5,
                uuid = "txn_002",
                dateSerial = 46331,
                timeSerial = 0.5,
                account = "SBI",
                item = "Dinner",
                tripTag = null,
                category = "Food & Drink",
                total = 300.0,
                companions = "Alice, Bob, Txn_e71bbd73607f",
                myShare = 100.0,
                owed = 200.0,
                status = "Pending"
            ),
            LedgerDebit(
                tab = "Nov26",
                row = 6,
                uuid = "txn_003",
                dateSerial = 46332,
                timeSerial = 0.5,
                account = "SBI",
                item = "Lunch",
                tripTag = null,
                category = "Food & Drink",
                total = 150.0,
                companions = "Charlie, txn_a1b2c3d4e5f6, Nov26!Row5",
                myShare = 75.0,
                owed = 75.0,
                status = "Pending"
            )
        )

        val payables = listOf(
            LedgerPayable(
                tab = "Nov26",
                row = 4,
                uuid = "pay_001",
                dateIncurredSerial = 46334,
                timeSerial = 0.5,
                paidBy = "David, pay_96a6d13d385c, inf_beefcafe1234",
                whatFor = "Cab",
                amountOwed = 100.0,
                dateRepaidSerial = null,
                status = "Pending"
            )
        )

        val snapshot = LedgerSnapshot(
            debits = debits,
            payables = payables
        )

        val result = useCase.invoke(snapshot, currentMonthTab = "Nov26")

        // Valid people should be present
        assertTrue("Alice must be included", result.allNames.contains("Alice"))
        assertTrue("Bob must be included", result.allNames.contains("Bob"))
        assertTrue("Charlie must be included", result.allNames.contains("Charlie"))
        assertTrue("David must be included", result.allNames.contains("David"))

        // Invalid IDs and references must be strictly excluded
        assertFalse("Txn_e71bbd73607f must NEVER be a companion", result.allNames.contains("Txn_e71bbd73607f"))
        assertFalse("txn_a1b2c3d4e5f6 must NEVER be a companion", result.allNames.any { it.startsWith("txn_", ignoreCase = true) })
        assertFalse("pay_96a6d13d385c must NEVER be a companion", result.allNames.any { it.startsWith("pay_", ignoreCase = true) })
        assertFalse("inf_beefcafe1234 must NEVER be a companion", result.allNames.any { it.startsWith("inf_", ignoreCase = true) })
        assertFalse("Nov26!Row5 must NEVER be a companion", result.allNames.any { it.contains("!") })
        assertFalse("Personal must NEVER be a companion", result.allNames.contains("Personal"))

        assertEquals(4, result.allNames.size)
    }

    @Test
    fun testIsValidPersonTokenHelper() {
        assertTrue(RebuildPeopleListUseCase.isValidPersonToken("Alice"))
        assertTrue(RebuildPeopleListUseCase.isValidPersonToken("Bob Smith"))
        assertTrue(RebuildPeopleListUseCase.isValidPersonToken("Dan K"))

        assertFalse(RebuildPeopleListUseCase.isValidPersonToken("Personal"))
        assertFalse(RebuildPeopleListUseCase.isValidPersonToken("Txn_e71bbd73607f"))
        assertFalse(RebuildPeopleListUseCase.isValidPersonToken("txn_e71bbd73607f"))
        assertFalse(RebuildPeopleListUseCase.isValidPersonToken("pay_96a6d13d385c"))
        assertFalse(RebuildPeopleListUseCase.isValidPersonToken("inf_0123456789ab"))
        assertFalse(RebuildPeopleListUseCase.isValidPersonToken("Nov26!Row5"))
        assertFalse(RebuildPeopleListUseCase.isValidPersonToken("   "))
    }
}
