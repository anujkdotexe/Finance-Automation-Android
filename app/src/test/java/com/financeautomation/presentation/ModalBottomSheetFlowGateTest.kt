package com.financeautomation.presentation

import com.financeautomation.presentation.overlay.FlowAuthGate
import com.financeautomation.presentation.overlay.OverlayFlowScreen
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ModalBottomSheetFlowGateTest {

    private val ownPackage = "com.financeautomation"

    @Test
    fun externalCallerInReleaseBuildCannotSelectArbitraryFlow() {
        // Case 1: External caller (callingPackage is null, typical for startActivity from outside)
        val isAuthNull = FlowAuthGate.isAuthorized(
            hasAuthToken = false,
            callingPackage = null,
            ownPackageName = ownPackage,
            isDebug = false
        )
        assertFalse("External caller with null callingPackage must not be authorized in release", isAuthNull)
        val screenNull = FlowAuthGate.resolveScreen("MENU", isAuthNull)
        assertEquals("Must fall back to EXPENSE flow", OverlayFlowScreen.EXPENSE, screenNull)

        // Case 2: External caller with explicit spoofed/attacker package
        val isAuthAttacker = FlowAuthGate.isAuthorized(
            hasAuthToken = false,
            callingPackage = "com.attacker.app",
            ownPackageName = ownPackage,
            isDebug = false
        )
        assertFalse("External attacker app must not be authorized in release", isAuthAttacker)
        val screenAttacker = FlowAuthGate.resolveScreen("INCOME_RETURN", isAuthAttacker)
        assertEquals("Must fall back to EXPENSE flow", OverlayFlowScreen.EXPENSE, screenAttacker)
    }

    @Test
    fun internalCallerInReleaseBuildCanSelectFlow() {
        // Case 1: Verified internal package name
        val isAuthInternal = FlowAuthGate.isAuthorized(
            hasAuthToken = false,
            callingPackage = ownPackage,
            ownPackageName = ownPackage,
            isDebug = false
        )
        assertTrue("Internal app caller must be authorized", isAuthInternal)
        val screenInternal = FlowAuthGate.resolveScreen("MENU", isAuthInternal)
        assertEquals(OverlayFlowScreen.MENU, screenInternal)

        // Case 2: Verified internal token (e.g. from internal PendingIntent)
        val isAuthToken = FlowAuthGate.isAuthorized(
            hasAuthToken = true,
            callingPackage = null,
            ownPackageName = ownPackage,
            isDebug = false
        )
        assertTrue("Caller with verified internal token must be authorized", isAuthToken)
        val screenToken = FlowAuthGate.resolveScreen("FRIEND_PAID", isAuthToken)
        assertEquals(OverlayFlowScreen.FRIEND_PAID, screenToken)
    }

    @Test
    fun debugBuildAllowsFlowSelectionForAdbAndDevelopment() {
        val isAuthDebug = FlowAuthGate.isAuthorized(
            hasAuthToken = false,
            callingPackage = null,
            ownPackageName = ownPackage,
            isDebug = true
        )
        assertTrue("Debug build allows flow selection for adb shell and dev testing", isAuthDebug)

        assertEquals(OverlayFlowScreen.INCOME_RETURN, FlowAuthGate.resolveScreen("INCOME_RETURN", isAuthDebug))
        assertEquals(OverlayFlowScreen.MENU, FlowAuthGate.resolveScreen("MENU", isAuthDebug))
        assertEquals(OverlayFlowScreen.FRIEND_PAID, FlowAuthGate.resolveScreen("FRIEND_PAID", isAuthDebug))
        assertEquals(OverlayFlowScreen.REPAID_FRIEND, FlowAuthGate.resolveScreen("REPAID_FRIEND", isAuthDebug))
        assertEquals(OverlayFlowScreen.TRIP_MODE, FlowAuthGate.resolveScreen("TRIP_MODE", isAuthDebug))
    }
}
