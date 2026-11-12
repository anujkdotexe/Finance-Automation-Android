package com.financeautomation.presentation.overlay

import org.junit.Assert.assertEquals
import org.junit.Test

class SettlementMappingTest {

    @Test
    fun testMapSettlementToExtras_owedToMe_mapsToIncomeFlowWithFriend() {
        val result = FlowAuthGate.mapSettlementToExtras(personName = "Alice", owedToMe = true)
        assertEquals("INCOME", result.flow)
        assertEquals("Alice", result.preselectedFriend)
    }

    @Test
    fun testMapSettlementToExtras_iOwe_mapsToRepaidFlowWithFriend() {
        val result = FlowAuthGate.mapSettlementToExtras(personName = "Bob", owedToMe = false)
        assertEquals("REPAID", result.flow)
        assertEquals("Bob", result.preselectedFriend)
    }
}

