package com.financeautomation.presentation

import com.financeautomation.presentation.overlay.FlowAuthGate
import com.financeautomation.presentation.overlay.OverlayFlowScreen
import com.financeautomation.presentation.ui.EntryIntentValidator
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The sheet activity is not exported, so a flow started from inside the app is used as given (no debug flag, no
 * caller package, no token needed). Outside callers can only reach it through EntryActivity, which drops the flow.
 */
class ModalBottomSheetFlowGateTest {

    @Test
    fun everySpeedDialFlowOpensItsOwnScreenWithoutAnyDebugFlag() {
        assertEquals(OverlayFlowScreen.MENU, FlowAuthGate.resolveScreen("MENU"))
        assertEquals(OverlayFlowScreen.INCOME_RETURN, FlowAuthGate.resolveScreen("INCOME"))
        assertEquals(OverlayFlowScreen.INCOME_RETURN, FlowAuthGate.resolveScreen("INCOME_RETURN"))
        assertEquals(OverlayFlowScreen.FRIEND_PAID, FlowAuthGate.resolveScreen("FRIEND_PAID"))
        assertEquals(OverlayFlowScreen.REPAID_FRIEND, FlowAuthGate.resolveScreen("REPAID"))
        assertEquals(OverlayFlowScreen.TRIP_MODE, FlowAuthGate.resolveScreen("TRIP"))
    }

    @Test
    fun settlementTapsOpenTheirScreens() {
        val owedToMe = FlowAuthGate.mapSettlementToExtras("Asha", owedToMe = true)
        val iOwe = FlowAuthGate.mapSettlementToExtras("Asha", owedToMe = false)
        assertEquals(OverlayFlowScreen.INCOME_RETURN, FlowAuthGate.resolveScreen(owedToMe.flow))
        assertEquals(OverlayFlowScreen.REPAID_FRIEND, FlowAuthGate.resolveScreen(iOwe.flow))
    }

    @Test
    fun unknownOrBlankFlowFallsBackToExpense() {
        assertEquals(OverlayFlowScreen.EXPENSE, FlowAuthGate.resolveScreen(null))
        assertEquals(OverlayFlowScreen.EXPENSE, FlowAuthGate.resolveScreen(""))
        assertEquals(OverlayFlowScreen.EXPENSE, FlowAuthGate.resolveScreen("DROP_TABLE"))
    }

    @Test
    fun outsideCallerOfEntryActivityStillGetsTheExpenseScreen() {
        for (flow in listOf("MENU", "INCOME_RETURN", "FRIEND_PAID", "REPAID", "TRIP")) {
            val target = EntryIntentValidator.restrictForCaller(
                isInternal = false,
                validatedCaptureId = "cap-1",
                validatedFlow = EntryIntentValidator.validateFlow(flow)
            )
            assertEquals(null, target.flow)
            assertEquals(OverlayFlowScreen.EXPENSE, FlowAuthGate.resolveScreen(target.flow))
        }
    }

    @Test
    fun internalCallerOfEntryActivityKeepsItsFlow() {
        val target = EntryIntentValidator.restrictForCaller(
            isInternal = true,
            validatedCaptureId = null,
            validatedFlow = EntryIntentValidator.validateFlow("friend_paid")
        )
        assertEquals(OverlayFlowScreen.FRIEND_PAID, FlowAuthGate.resolveScreen(target.flow))
    }
}
