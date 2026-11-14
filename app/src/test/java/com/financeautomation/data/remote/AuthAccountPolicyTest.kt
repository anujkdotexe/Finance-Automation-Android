package com.financeautomation.data.remote

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** The sync token must belong to the account the app shows, and sign out must really sign out. */
class AuthAccountPolicyTest {

    @Test
    fun noStoredEmailMeansNoAuthorizationAtAll() = runBlocking {
        assertEquals(AuthAccountPolicy.Decision.NoAccount, AuthAccountPolicy { null }.decide())
        assertEquals(AuthAccountPolicy.Decision.NoAccount, AuthAccountPolicy { "   " }.decide())
    }

    @Test
    fun theStoredEmailIsTheAccountToAuthorize() = runBlocking {
        assertEquals(AuthAccountPolicy.Decision.Use("me@example.com"), AuthAccountPolicy { " me@example.com " }.decide())
    }

    @Test
    fun aCachedTokenIsOnlyReusedForTheSameAccount() {
        assertTrue(AuthAccountPolicy.cacheMatches("Me@Example.com", "me@example.com"))
        assertFalse(AuthAccountPolicy.cacheMatches("a@example.com", "b@example.com"))
        assertFalse(AuthAccountPolicy.cacheMatches(null, "b@example.com"))
    }

    private val provider = File("src/main/java/com/financeautomation/data/remote/GoogleAuthTokenProvider.kt").readText()

    @Test
    fun theAuthorizationRequestNamesTheAccountAndRefusesWithoutOne() {
        val request = provider.substringAfter("suspend fun requestAuthorization(").substringBefore("authClient.authorize")
        assertTrue(request.contains("setAccount(Account(email, \"com.google\"))"))
        assertTrue(request.contains("AuthAccountPolicy.Decision.NoAccount"))
        assertTrue("no prompt and no notification when signed out", request.substringAfter("NoAccount").substringBefore("val request").contains("AuthExpiredException"))
    }

    @Test
    fun aRealSignOutSignsTheGoogleClientOutAndForgetsTheToken() {
        assertTrue(provider.contains("GoogleSignIn.getClient("))
        assertTrue(provider.contains(".signOut()"))
        val coordinator = File("src/main/java/com/financeautomation/presentation/viewmodel/SettingsSyncCoordinator.kt").readText()
        assertTrue(coordinator.substringAfter("fun onGoogleSignOut").substringBefore("fun autoProvisionMasterSheet").contains("signOutGoogle()"))
        assertTrue("cancelling a switch also signs the picker's account out", coordinator.substringAfter("fun cancelAccountSwitch").substringBefore("private suspend fun continueSignIn").contains("signOutGoogle()"))
    }

    @Test
    fun theSyncAndBackupScreenShowsOnlyTheStoredEmail() {
        val screen = File("src/main/java/com/financeautomation/presentation/screens/drawer/SyncBackupScreen.kt").readText()
        assertFalse(screen.contains("getLastSignedInAccount"))
    }

    @Test
    fun theContainerGivesTheProviderTheStoredEmail() {
        val container = File("src/main/java/com/financeautomation/di/AppContainer.kt").readText()
        assertTrue(container.contains("emailProvider = { preferencesRepository.getGoogleAccountEmailSync() }"))
    }
}
