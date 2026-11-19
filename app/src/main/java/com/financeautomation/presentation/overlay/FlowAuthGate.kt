package com.financeautomation.presentation.overlay

data class SettlementFlowExtras(
    val flow: String,
    val preselectedFriend: String
)

/**
 * Pure Kotlin gate validating flow selection authorizations (Item 4).
 * Completely instrumentation-free and testable in JVM unit tests.
 */
object FlowAuthGate {

    /**
     * Resolves the target flow screen from the raw flow name. Unknown or blank names give the expense sheet.
     * Callers must already be trusted: the sheet activity is not exported, and the exported EntryActivity
     * removes the flow for outside callers before it reaches here.
     */
    fun resolveScreen(rawFlow: String?): OverlayFlowScreen {
        if (rawFlow.isNullOrBlank()) {
            return OverlayFlowScreen.EXPENSE
        }
        return when (rawFlow.uppercase()) {
            "MENU", "CHOOSE_ACTION" -> OverlayFlowScreen.MENU
            "INCOME", "INCOME_RETURN", "RETURN" -> OverlayFlowScreen.INCOME_RETURN
            "FRIEND_PAID", "PAYABLE" -> OverlayFlowScreen.FRIEND_PAID
            "REPAID", "REPAID_FRIEND" -> OverlayFlowScreen.REPAID_FRIEND
            "TRIP", "TRIP_MODE" -> OverlayFlowScreen.TRIP_MODE
            else -> OverlayFlowScreen.EXPENSE
        }
    }

    /**
     * Maps companion name and settlement direction (owed to me vs I owe)
     * to flow extras for ModalBottomSheetActivity.
     */
    fun mapSettlementToExtras(personName: String, owedToMe: Boolean): SettlementFlowExtras {
        val flow = if (owedToMe) "INCOME" else "REPAID"
        return SettlementFlowExtras(
            flow = flow,
            preselectedFriend = personName
        )
    }
}
