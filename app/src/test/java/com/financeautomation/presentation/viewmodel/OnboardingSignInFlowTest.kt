package com.financeautomation.presentation.viewmodel

import com.financeautomation.core.model.Result
import com.financeautomation.data.remote.AuthResult
import com.financeautomation.data.remote.AuthorizationSource
import com.financeautomation.data.remote.GoogleAuthTokenProvider
import com.financeautomation.data.remote.AuthExpiredException
import com.financeautomation.data.remote.GoogleDriveClient
import com.financeautomation.data.remote.GoogleSheetsClient
import com.financeautomation.data.remote.TokenProvider
import com.financeautomation.data.remote.WorkbookResolver
import com.financeautomation.testutil.SyncHarness
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import okhttp3.OkHttpClient
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/** The Setup page's sign in path: a cancelled picker does nothing, and an account is stored only after Google accepted it. */
@OptIn(ExperimentalCoroutinesApi::class)
class OnboardingSignInFlowTest {

    private val dispatcher = StandardTestDispatcher()

    @Before fun setUp() = Dispatchers.setMain(dispatcher)
    @After fun tearDown() = Dispatchers.resetMain()

    private class FakeAuth(var result: AuthResult) : AuthorizationSource {
        val requestedFor = mutableListOf<String?>()
        override suspend fun requestAuthorization(isForeground: Boolean, accountEmail: String?): AuthResult {
            requestedFor += accountEmail
            return result
        }
    }

    private val tokens = object : TokenProvider {
        override fun getAccessToken(): String = "t"
        override fun refreshAccessToken(): String = "t"
    }
    private val h = SyncHarness()

    private val resolver = object : WorkbookResolver(
        GoogleDriveClient(OkHttpClient(), tokens), GoogleSheetsClient(OkHttpClient(), tokens), h.prefs
    ) {
        override suspend fun resolveForYear(year: Int, forceValidateTabs: Boolean, createBackup: Boolean): Result<String> = Result.Success("sheet1")
    }

    private fun vm(auth: AuthorizationSource): OnboardingViewModel {
        h.googleEmail = null
        return OnboardingViewModel(
            googleAuthTokenProvider = auth, workbookResolver = resolver, preferencesRepository = h.prefs, ioDispatcher = dispatcher
        )
    }

    @Test
    fun aCancelledPickerDoesNothingAndShowsNoError() = runTest(dispatcher) {
        val auth = FakeAuth(AuthResult.Failed(AuthExpiredException(GoogleAuthTokenProvider.NO_ACCOUNT_MESSAGE)))
        val model = vm(auth)
        advanceUntilIdle()
        model.startSignIn() // what a picker result without an account would do if it were wrongly forwarded
        advanceUntilIdle()
        assertTrue("no request was made", auth.requestedFor.isEmpty())
        assertEquals(StepState.NOT_STARTED, model.uiState.value.step1State)
        assertNull(model.uiState.value.step1Error)
    }

    @Test
    fun theNoAccountAnswerIsNeverShownAsRawText() = runTest(dispatcher) {
        val auth = FakeAuth(AuthResult.Failed(AuthExpiredException(GoogleAuthTokenProvider.NO_ACCOUNT_MESSAGE)))
        val model = vm(auth)
        model.onGoogleAccountDetails("me@example.com", null, null)
        model.startSignIn()
        advanceUntilIdle()
        assertEquals(listOf<String?>("me@example.com"), auth.requestedFor)
        assertNull(model.uiState.value.step1Error)
        assertEquals(StepState.NOT_STARTED, model.uiState.value.step1State)
    }

    @Test
    fun theAccountIsStoredOnlyAfterGoogleAcceptedIt() = runTest(dispatcher) {
        val auth = FakeAuth(AuthResult.Failed(java.io.IOException("consent revoked")))
        val model = vm(auth)
        model.onGoogleAccountDetails("me@example.com", "Me", null)
        model.startSignIn()
        advanceUntilIdle()
        assertNull("a failed authorization stores nothing", h.googleEmail)
        assertEquals(StepState.ERROR, model.uiState.value.step1State)

        auth.result = AuthResult.Token("tok")
        model.startSignIn()
        advanceUntilIdle()
        assertEquals("me@example.com", h.googleEmail)
        assertEquals(StepState.COMPLETED, model.uiState.value.step1State)
    }

    @Test
    fun consentIsAskedForTheSameAccountAndNothingIsStoredUntilItIsGiven() = runTest(dispatcher) {
        val pending = android.app.PendingIntent::class.java
        val auth = FakeAuth(AuthResult.Failed(java.io.IOException("needs consent")))
        val model = vm(auth)
        model.onGoogleAccountDetails("me@example.com", null, null)
        model.startSignIn()
        advanceUntilIdle()
        assertNull(h.googleEmail)
        // consent denied
        model.onConsentResult(false)
        assertNull(h.googleEmail)
        // consent given, then the token arrives
        auth.result = AuthResult.Token("tok")
        model.onConsentResult(true)
        advanceUntilIdle()
        assertEquals("me@example.com", h.googleEmail)
        assertEquals(listOf<String?>("me@example.com", "me@example.com"), auth.requestedFor)
        assertTrue(pending != null)
    }

    @Test
    fun startSignIn_whenCandidateIsClearedOrCancelled_doesNotCrash() = runTest(dispatcher) {
        val auth = FakeAuth(AuthResult.Token("tok"))
        val model = vm(auth)
        // Candidate cleared or cancelled
        model.onGoogleAccountDetails("", null, null)
        model.startSignIn()
        advanceUntilIdle()
        assertTrue("No auth requested when candidate is cleared/blank", auth.requestedFor.isEmpty())
        assertNull(h.googleEmail)
    }

    @Test
    fun theSetupPageOnlyStartsSignInWhenThePickerReturnedAnAccount() {
        val screen = java.io.File("src/main/java/com/financeautomation/presentation/screens/OnboardingScreen.kt").readText()
        val launcher = screen.substringAfter("val googleSignInLauncher").substringBefore("LaunchedEffect(viewModel)")
        assertTrue(launcher.substringAfter("if (account != null) {").substringBefore("} catch").contains("viewModel.startSignIn()"))
        assertTrue("a different account stays one tap away", screen.contains("R.string.onboarding_use_other_account"))
    }
}
