package com.financeautomation.presentation

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** R2-21: the Voided label comes from string resources, not from a literal in the composables. */
class VoidLabelSourceTest {

    private fun source(path: String): String {
        val f = File("src/main/java/com/financeautomation/$path").takeIf { it.exists() }
            ?: File("app/src/main/java/com/financeautomation/$path")
        return f.readText()
    }

    @Test
    fun noComposableDisplaysAHardCodedVoided() {
        val chip = source("presentation/components/StatusChip.kt")
        assertTrue(chip.contains("R.string.status_voided"))
        assertTrue("literal Voided in the chip", !chip.contains("\"Voided\""))
        for (path in listOf(
            "presentation/components/TransactionRow.kt", "presentation/screens/ReviewTransactionCard.kt",
            "presentation/screens/TransactionsScreen.kt", "presentation/screens/drawer/TripDetailSheet.kt"
        )) {
            val text = source(path)
            assertTrue("$path passes a literal Voided", !text.contains("status = \"Voided\"") && !text.contains("isVoid) \"Voided\""))
        }
    }
}
