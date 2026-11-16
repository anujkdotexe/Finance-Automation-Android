package com.financeautomation.data.account

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/** Small account switch edges: leftover exports, the persistent alert, the sign out timeout and the wording. */
class AccountHousekeepingTest {

    @get:Rule val folder = TemporaryFolder()

    @Test
    fun theWipeDeletesTheCsvExportsOfThePreviousAccount() {
        val exports = File(folder.root, "exports").also { it.mkdirs() }
        File(exports, "march.csv").writeText("a,b")
        File(folder.root, "other.txt").writeText("keep")
        AccountCacheCleaner.clearExports(folder.root)
        assertFalse(File(exports, "march.csv").exists())
        assertTrue("unrelated cache files stay", File(folder.root, "other.txt").exists())
    }

    @Test
    fun nothingToClearIsFine() {
        AccountCacheCleaner.clearExports(folder.root)
        AccountCacheCleaner.clearExports(null)
    }

    @Test
    fun theStoreCleansTheExportsAsPartOfTheWipe() {
        val src = File("src/main/java/com/financeautomation/data/account/RoomAccountDataStore.kt").readText()
        assertTrue(src.contains("AccountCacheCleaner.clearExports(cacheDir)"))
    }

    @Test
    fun onlyTheAppsFinanceNotificationsAreCancelledNotThePersistentAlert() {
        assertEquals(listOf(1, 2), AccountNotifications.idsToCancel(listOf(1, 9001, 2), keep = setOf(9001)))
        val container = File("src/main/java/com/financeautomation/di/AppContainer.kt").readText()
        assertFalse(container.contains("NotificationManagerCompat.from(applicationContext).cancelAll()"))
        assertTrue(container.contains(".idsToCancel("))
    }

    @Test
    fun signOutGiveUpWaitingAfterATimeout() {
        val provider = File("src/main/java/com/financeautomation/data/remote/GoogleAuthTokenProvider.kt").readText()
        val signOut = provider.substringAfter("suspend fun signOutGoogle()")
        assertTrue(signOut.contains("withTimeoutOrNull("))
    }

    @Test
    fun aSwitchWithNoLocalDataIsAPlainConfirmationWithoutTheDataRemovedWarning() = runBlocking {
        val prompt = AccountSwitchPrompt("b@example.com", null, null, pendingCount = 0, hasLocalData = false)
        assertFalse(prompt.warnsAboutRemovedData)
        assertTrue(AccountSwitchPrompt("b@example.com", null, null, pendingCount = 0, hasLocalData = true).warnsAboutRemovedData)
        val dialog = File("src/main/java/com/financeautomation/presentation/components/AccountSwitchDialog.kt").readText()
        assertTrue(dialog.contains("prompt.warnsAboutRemovedData"))
        assertNotNull(dialog)
    }
}
