package com.financeautomation.presentation.overlay

/**
 * Pure Kotlin gate validating flow selection authorizations (Item 4).
 * Completely instrumentation-free and testable in JVM unit tests.
 */
object FlowAuthGate {

    /**
     * Determines whether a caller is authorized to choose an arbitrary overlay/sheet flow.
     * External applications in non-debug builds are strictly rejected.
     */
    fun isAuthorized(
        hasAuthToken: Boolean,
        callingPackage: String?,
        ownPackageName: String,
        isDebug: Boolean = com.financeautomation.BuildConfig.DEBUG
    ): Boolean {
        if (isDebug) return true
        if (!callingPackage.isNullOrBlank() && callingPackage == ownPackageName) return true
        return hasAuthToken
    }

    /**
     * Resolves the target flow screen from the raw flow parameter given authorization status.
     */
    fun resolveScreen(
        rawFlow: String?,
        isAuthorized: Boolean
    ): OverlayFlowScreen {
        if (!isAuthorized || rawFlow.isNullOrBlank()) {
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
}

