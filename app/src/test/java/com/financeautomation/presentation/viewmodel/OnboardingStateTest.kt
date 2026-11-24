package com.financeautomation.presentation.viewmodel

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** How the Setup page follows the saved sign-in and workbook, so it never shows a stale "Connected". */
class OnboardingStateTest {

    private val fresh = OnboardingUiState()

    @Test
    fun signedOutShowsSignInAsTheStepThatNeedsAttention() {
        val s = fresh.withPreferences(email = null, sheetId = null)
        assertEquals(StepState.NOT_STARTED, s.step1State)
        assertEquals(StepState.NOT_STARTED, s.step2State)
        assertTrue(s.needsSignIn)
        assertFalse(s.isReady)
        assertNull(s.googleAccountEmail)
    }

    @Test
    fun signingOutAfterBeingFullySetUpResetsTheStaleConnectedState() {
        val ready = fresh.withPreferences("a@example.com", "sheet1")
        assertTrue(ready.isReady)
        val afterSignOut = ready.withPreferences(email = null, sheetId = null)
        assertTrue("the Sign in step must come back", afterSignOut.needsSignIn)
        assertFalse(afterSignOut.isReady)
        assertEquals("", afterSignOut.spreadsheetId)
    }

    @Test
    fun signedInWithoutAWorkbookNeedsTheWorkbookStepNotSignIn() {
        val s = fresh.withPreferences("a@example.com", sheetId = null)
        assertEquals(StepState.COMPLETED, s.step1State)
        assertEquals(StepState.NOT_STARTED, s.step2State)
        assertFalse(s.needsSignIn)
        assertTrue(s.needsWorkbook)
        assertFalse(s.isReady)
    }

    @Test
    fun signedInWithAWorkbookIsReadyToContinue() {
        val s = fresh.withPreferences("a@example.com", "sheet1")
        assertTrue(s.isReady)
        assertEquals("sheet1", s.spreadsheetId)
        assertEquals("a@example.com", s.googleAccountEmail)
        assertFalse(s.needsSignIn)
        assertFalse(s.needsWorkbook)
    }

    @Test
    fun aWorkbookBeingCreatedOrFailedIsNotOverwrittenByTheSavedValues() {
        val working = fresh.copy(step1State = StepState.COMPLETED, step2State = StepState.IN_PROGRESS)
            .withPreferences("a@example.com", sheetId = null)
        assertEquals(StepState.IN_PROGRESS, working.step2State)
        val failed = working.copy(step2State = StepState.ERROR, step2Error = UiText.dynamic("boom"))
            .withPreferences("a@example.com", sheetId = null)
        assertEquals(StepState.ERROR, failed.step2State)
    }

    @Test
    fun aSignInInProgressOrFailedKeepsItsStateWhileSignedOut() {
        val inProgress = fresh.copy(step1State = StepState.IN_PROGRESS).withPreferences(null, null)
        assertEquals(StepState.IN_PROGRESS, inProgress.step1State)
        val failed = fresh.copy(step1State = StepState.ERROR, step1Error = UiText.dynamic("denied")).withPreferences(null, null)
        assertEquals(StepState.ERROR, failed.step1State)
        assertTrue(failed.needsSignIn)
    }

    @Test
    fun anErrorClearsOnceTheStepCompletes() {
        val errored = fresh.copy(step1State = StepState.ERROR, step1Error = UiText.dynamic("denied"))
        val ok = errored.withPreferences("a@example.com", "sheet1")
        assertEquals(StepState.COMPLETED, ok.step1State)
        assertNull(ok.step1Error)
    }


}
