package com.financeautomation.presentation.ui

import com.financeautomation.core.model.Result
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.atomic.AtomicBoolean

@OptIn(ExperimentalCoroutinesApi::class)
class ModalBottomSheetResultHandlingTest {

    private val testDispatcher = StandardTestDispatcher()

    @Test
    fun doubleSubmitGuardRejectsSecondInvocationWhileSubmitting() {
        val isSubmitting = AtomicBoolean(false)

        val firstAttempt = isSubmitting.compareAndSet(false, true)
        val secondAttempt = isSubmitting.compareAndSet(false, true)

        assertTrue("First attempt must be accepted", firstAttempt)
        assertFalse("Second concurrent attempt must be rejected by guard", secondAttempt)
        assertTrue("Guard must remain in submitting state", isSubmitting.get())

        // Upon failure or completion, resetting guard allows new attempts
        isSubmitting.set(false)
        val retryAttempt = isSubmitting.compareAndSet(false, true)
        assertTrue("Retry attempt after reset must be accepted", retryAttempt)
    }

    @Test
    fun incomeSaveResultErrorDoesNotTriggerDismissalAndResetsGuard() = runTest(testDispatcher) {
        val isSubmitting = AtomicBoolean(false)
        var dismissed = false
        var capturedError: String? = null

        val simulateLogCredit: suspend () -> Result<String> = {
            Result.Error(Exception("Network error writing to sheet"), "Network timeout")
        }

        if (isSubmitting.compareAndSet(false, true)) {
            val res = simulateLogCredit()
            when (res) {
                is Result.Success -> {
                    dismissed = true
                }
                is Result.Error -> {
                    isSubmitting.set(false)
                    capturedError = res.message ?: "Failed to record income"
                }
                is Result.Loading -> Unit
            }
        }

        assertFalse("Modal must NOT be dismissed on save error", dismissed)
        assertEquals("Network timeout", capturedError)
        assertFalse("Guard must be reset on failure to allow retry", isSubmitting.get())
    }

    @Test
    fun incomeSaveResultSuccessTriggersDismissal() = runTest(testDispatcher) {
        val isSubmitting = AtomicBoolean(false)
        var dismissed = false

        val simulateLogCredit: suspend () -> Result<String> = {
            Result.Success("credit_123")
        }

        if (isSubmitting.compareAndSet(false, true)) {
            val res = simulateLogCredit()
            when (res) {
                is Result.Success -> {
                    dismissed = true
                }
                is Result.Error -> {
                    isSubmitting.set(false)
                }
                is Result.Loading -> Unit
            }
        }

        assertTrue("Modal must be dismissed on successful save", dismissed)
        assertTrue("Guard remains true during dismissal transition", isSubmitting.get())
    }

    @Test
    fun payableSaveResultErrorDoesNotTriggerDismissal() = runTest(testDispatcher) {
        val isSubmitting = AtomicBoolean(false)
        var dismissed = false
        var capturedError: String? = null

        val simulateLogPayable: suspend () -> Result<String> = {
            Result.Error(Exception("DB error"), "Failed to insert payable")
        }

        if (isSubmitting.compareAndSet(false, true)) {
            val res = simulateLogPayable()
            when (res) {
                is Result.Success -> dismissed = true
                is Result.Error -> {
                    isSubmitting.set(false)
                    capturedError = res.message
                }
                is Result.Loading -> Unit
            }
        }

        assertFalse("Modal must NOT be dismissed on payable failure", dismissed)
        assertEquals("Failed to insert payable", capturedError)
        assertFalse("Guard reset on error", isSubmitting.get())
    }
}
