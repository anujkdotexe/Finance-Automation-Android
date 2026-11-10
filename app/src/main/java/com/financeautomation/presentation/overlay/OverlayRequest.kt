package com.financeautomation.presentation.overlay

/**
 * Types of UI requests handled by OverlayController.
 */
sealed interface OverlayRequest {
    /**
     * Shows the quick log sheet for a pending capture from notification interception.
     */
    data class Capture(val captureId: String) : OverlayRequest

    /**
     * Shows the Choose Action menu (Expense, Income/Return, Friend Paid For Me, Repaid a Friend, Trip Mode).
     */
    data object ChooseAction : OverlayRequest

    /**
     * Shows the Quick Log sheet directly for manual expense entry.
     */
    data object ManualExpense : OverlayRequest

    /**
     * Self-test simulation of the Quick Log sheet with sample data.
     */
    data object SelfTest : OverlayRequest

    /**
     * Direct launch of a specific flow from the Choose Action suite.
     */
    data class SpecificFlow(val flowType: String) : OverlayRequest
}

