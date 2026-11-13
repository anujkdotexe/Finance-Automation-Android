package com.financeautomation.presentation.health

import com.financeautomation.core.utils.SyncErrorKind
import com.financeautomation.presentation.viewmodel.HealthCheckId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Every issue in every state says what is wrong, why, and offers exactly one fixing action only when needed. */
class AttentionMappingTest {

    @Test
    fun anIssueNeedingAttentionHasAReasonAndOneAction() {
        for (issue in AttentionIssue.values()) {
            val c = attentionContent(issue, AttentionState.NEEDS_ATTENTION)
            assertNotNull("$issue reason", c.reason)
            assertNotNull("$issue action label", c.actionLabel)
            assertNotNull("$issue action", c.action)
        }
    }

    @Test
    fun anOkIssueShowsADoneStateWithNoReasonOrAction() {
        for (issue in AttentionIssue.values()) {
            val c = attentionContent(issue, AttentionState.OK)
            assertNull(c.reason)
            assertNull(c.actionLabel)
            assertNull(c.action)
        }
    }

    @Test
    fun anIssueInProgressShowsNoActionToPress() {
        for (issue in AttentionIssue.values()) {
            val c = attentionContent(issue, AttentionState.IN_PROGRESS)
            assertNull(c.action)
            assertNull(c.reason)
        }
    }

    @Test
    fun eachProblemPointsAtItsOwnFix() {
        fun action(i: AttentionIssue) = attentionContent(i, AttentionState.NEEDS_ATTENTION).action
        assertEquals(AttentionAction.SIGN_IN, action(AttentionIssue.SIGN_IN))
        assertEquals(AttentionAction.SET_UP_WORKBOOK, action(AttentionIssue.WORKBOOK))
        assertEquals(AttentionAction.RECONNECT_GOOGLE, action(AttentionIssue.TOKEN_EXPIRED))
        assertEquals(AttentionAction.RETRY_SYNC, action(AttentionIssue.SYNC_FAILING))
        assertEquals(AttentionAction.RETRY_SYNC, action(AttentionIssue.RATE_LIMITED))
        assertEquals(AttentionAction.OPEN_NOTIFICATION_ACCESS, action(AttentionIssue.NOTIFICATION_ACCESS))
        assertEquals(AttentionAction.OPEN_OVERLAY_SETTINGS, action(AttentionIssue.OVERLAY))
        assertEquals(AttentionAction.OPEN_USAGE_ACCESS, action(AttentionIssue.USAGE_ACCESS))
        assertEquals(AttentionAction.OPEN_BATTERY_SETTINGS, action(AttentionIssue.BATTERY))
        assertEquals(AttentionAction.REQUEST_POST_NOTIFICATIONS, action(AttentionIssue.POST_NOTIFICATIONS))
        assertEquals(AttentionAction.ADD_ACCOUNT, action(AttentionIssue.ACCOUNTS))
        assertEquals(AttentionAction.OPEN_SECURITY_SETTINGS, action(AttentionIssue.APP_LOCK))
    }

    @Test
    fun theStateIsChosenFromWhatIsKnown() {
        assertEquals(AttentionState.OK, attentionState(ok = true, working = true))
        assertEquals(AttentionState.IN_PROGRESS, attentionState(ok = false, working = true))
        assertEquals(AttentionState.NEEDS_ATTENTION, attentionState(ok = false))
    }

    @Test
    fun everyHealthRowAndEverySyncErrorMapsToAnIssue() {
        assertNull(issueFor(HealthCheckId.OEM_BACKGROUND))
        for (id in HealthCheckId.values().filter { it != HealthCheckId.OEM_BACKGROUND }) assertNotNull(id.name, issueFor(id))
        assertEquals(AttentionIssue.TOKEN_EXPIRED, issueFor(SyncErrorKind.AUTH_EXPIRED))
        assertEquals(AttentionIssue.RATE_LIMITED, issueFor(SyncErrorKind.RATE_LIMITED))
        assertEquals(AttentionIssue.WORKBOOK, issueFor(SyncErrorKind.SHEET_CHANGED))
        for (k in SyncErrorKind.values()) assertNotNull(k.name, issueFor(k))
    }

    @Test
    fun continueNeedsEveryRequiredStepDone() {
        assertTrue(canContinue(listOf(AttentionState.OK, AttentionState.OK)))
        assertFalse(canContinue(listOf(AttentionState.OK, AttentionState.NEEDS_ATTENTION)))
        assertFalse(canContinue(listOf(AttentionState.OK, AttentionState.IN_PROGRESS)))
    }
}
