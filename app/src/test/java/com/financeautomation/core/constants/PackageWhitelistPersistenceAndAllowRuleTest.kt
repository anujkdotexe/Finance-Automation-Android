package com.financeautomation.core.constants

import com.financeautomation.domain.model.ThemeMode
import com.financeautomation.domain.repository.PreferencesRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.util.concurrent.ConcurrentHashMap

class PackageWhitelistPersistenceAndAllowRuleTest {

    private class TestPreferencesRepository : PreferencesRepository {
        val store = ConcurrentHashMap<String, String>()

        override fun getSpreadsheetId(): Flow<String?> = flowOf(store[PreferencesRepository.KEY_SPREADSHEET_ID])
        override suspend fun getSpreadsheetIdSync(): String? = store[PreferencesRepository.KEY_SPREADSHEET_ID]
        override suspend fun setSpreadsheetId(id: String) { store[PreferencesRepository.KEY_SPREADSHEET_ID] = id }
        override fun getSpreadsheetIdForYear(year: Int): Flow<String?> = flowOf(store[PreferencesRepository.keySpreadsheetIdForYear(year)])
        override suspend fun getSpreadsheetIdForYearSync(year: Int): String? = store[PreferencesRepository.keySpreadsheetIdForYear(year)]
        override suspend fun setSpreadsheetIdForYear(year: Int, id: String) { store[PreferencesRepository.keySpreadsheetIdForYear(year)] = id }
        override fun getDefaultAccount(): Flow<String> = flowOf("Canara")
        override suspend fun getDefaultAccountSync(): String = "Canara"
        override suspend fun setDefaultAccount(account: String) {}
        override fun getActiveTrip(): Flow<String?> = flowOf(null)
        override suspend fun setActiveTrip(trip: String?) {}
        override fun getGoogleAccountEmail(): Flow<String?> = flowOf(null)
        override suspend fun getGoogleAccountEmailSync(): String? = null
        override suspend fun setGoogleAccountEmail(email: String?) {}
        override fun getSettleDelaySeconds(): Flow<Int> = flowOf(120)
        override suspend fun getSettleDelaySecondsSync(): Int = 120
        override suspend fun setSettleDelaySeconds(seconds: Int) {}
        override suspend fun getOrCreateDeviceId(): String = "device_test"
        override suspend fun getDeviceIdSync(): String? = "device_test"
        override fun getDeveloperMode(): Flow<Boolean> = flowOf(false)
        override suspend fun getDeveloperModeSync(): Boolean = false
        override suspend fun setDeveloperMode(enabled: Boolean) {}
        override fun getThemeMode(): Flow<ThemeMode> = flowOf(ThemeMode.SYSTEM)
        override suspend fun getThemeModeSync(): ThemeMode = ThemeMode.SYSTEM
        override suspend fun setThemeMode(mode: ThemeMode) {}
        override suspend fun getPreference(key: String): String? = store[key]
        override suspend fun setPreference(key: String, value: String?, isDirty: Boolean) {
            if (value != null) store[key] = value else store.remove(key)
        }
        override suspend fun deletePreference(key: String) { store.remove(key) }
        override suspend fun clearKeysWithPrefix(prefix: String) {
            store.keys.filter { it.startsWith(prefix) }.forEach { store.remove(it) }
        }
    }

    private lateinit var testRepo: TestPreferencesRepository

    @Before
    fun setUp() {
        PackageWhitelist.resetForTesting()
        testRepo = TestPreferencesRepository()
        PackageWhitelist.init(testRepo)
    }

    @After
    fun tearDown() {
        PackageWhitelist.resetForTesting()
    }

    @Test
    fun finding6_perAppSwitchesAndWatchedAppsPersistAcrossProcessRestart() = runBlocking {
        // Step 1: Switch off PhonePe
        PackageWhitelist.setPackageEnabled(PackageWhitelist.PACKAGE_PHONEPE, false)
        assertFalse(PackageWhitelist.isPackageEnabled(PackageWhitelist.PACKAGE_PHONEPE))

        // Step 2: Add a custom watched banking app
        val customBankingApp = "com.custom.mybank"
        PackageWhitelist.addWatchedApp(customBankingApp, "My Bank")
        assertTrue(PackageWhitelist.getWatchedApps().contains(customBankingApp))

        // Save state into repository to ensure store has the state
        PackageWhitelist.saveToPreferences(testRepo)

        // Step 3: Simulate process kill and restart: reset memory state to empty
        PackageWhitelist.resetForTesting()

        // Before loading: default state would say PhonePe is enabled
        assertTrue("Fresh uninitialized state has PhonePe enabled by default", PackageWhitelist.isPackageEnabled(PackageWhitelist.PACKAGE_PHONEPE))
        assertFalse("Fresh uninitialized state does not have custom app", PackageWhitelist.getWatchedApps().contains(customBankingApp))

        // Step 4: Re-initialize from persistent store (as happens in FinanceApplication / NotificationListenerService)
        PackageWhitelist.loadFromPreferences(testRepo)

        // Assert persistent state is restored: PhonePe remains disabled, custom banking app remains watched
        assertFalse("PhonePe must remain disabled after loading from store", PackageWhitelist.isPackageEnabled(PackageWhitelist.PACKAGE_PHONEPE))
        assertTrue("Google Pay remains enabled", PackageWhitelist.isPackageEnabled(PackageWhitelist.PACKAGE_GPAY))
        assertTrue("Watched banking app must be retained after restart", PackageWhitelist.getWatchedApps().contains(customBankingApp))
        assertTrue(PackageWhitelist.isWhitelisted(customBankingApp))
    }

    @Test
    fun finding7_watchAnotherAppEnforcesStrictAllowStyleRuleOn20InventedPackages() {
        val installed20 = listOf(
            // 10 Non-financial / chat / mail / social / browser apps (MUST BE REJECTED)
            "com.viber.voip" to "Viber Messenger",
            "com.slack" to "Slack Work Chat",
            "com.microsoft.teams" to "Microsoft Teams",
            "com.tencent.mm" to "WeChat",
            "com.samsung.android.email.provider" to "Samsung Email",
            "ch.protonmail.android" to "Proton Mail",
            "com.yahoo.mobile.client.android.mail" to "Yahoo Mail",
            "com.facebook.mlite" to "Messenger Lite",
            "com.reddit.frontpage" to "Reddit Social",
            "com.opera.mini.native" to "Opera Mini Browser",

            // 10 Financial / banking / payment / wallet apps (MUST BE ACCEPTED)
            "com.sbi.lotusintouch" to "SBI YONO",
            "com.canarabank.ai1" to "Canara ai1",
            "com.hdfcbank.mobile" to "HDFC MobileBanking",
            "com.icicibank.imobile" to "iMobile ICICI",
            "com.axisbank.mobile" to "Axis Mobile",
            "com.cred.upi.club" to "CRED UPI",
            "com.mycustom.payment" to "Custom Payment",
            "com.fintech.wallet" to "Fast Wallet",
            "com.wealth.manager" to "Wealth Manager",
            "com.invest.groww" to "Groww Invest"
        )

        assertEquals(20, installed20.size)

        val available = PackageWhitelist.getAvailableAppsToWatch(installed20)
        val availablePackageIds = available.map { it.first }.toSet()

        // Verify that NONE of the 10 communication/chat/mail/social apps appear
        val rejected10 = listOf(
            "com.viber.voip",
            "com.slack",
            "com.microsoft.teams",
            "com.tencent.mm",
            "com.samsung.android.email.provider",
            "ch.protonmail.android",
            "com.yahoo.mobile.client.android.mail",
            "com.facebook.mlite",
            "com.reddit.frontpage",
            "com.opera.mini.native"
        )
        for (pkg in rejected10) {
            assertFalse("Non-financial app $pkg must NOT be available for watch", availablePackageIds.contains(pkg))
        }

        // Verify that ALL 10 financial apps DO appear
        val accepted10 = listOf(
            "com.sbi.lotusintouch",
            "com.canarabank.ai1",
            "com.hdfcbank.mobile",
            "com.icicibank.imobile",
            "com.axisbank.mobile",
            "com.cred.upi.club",
            "com.mycustom.payment",
            "com.fintech.wallet",
            "com.wealth.manager",
            "com.invest.groww"
        )
        for (pkg in accepted10) {
            assertTrue("Financial app $pkg must be available for watch", availablePackageIds.contains(pkg))
        }

        // Attempt direct addWatchedApp with chat and email packages: MUST BE REJECTED
        PackageWhitelist.addWatchedApp("com.viber.voip", "Viber Messenger")
        assertFalse(PackageWhitelist.getWatchedApps().contains("com.viber.voip"))
        assertFalse(PackageWhitelist.isWhitelisted("com.viber.voip"))

        PackageWhitelist.addWatchedApp("ch.protonmail.android", "Proton Mail")
        assertFalse(PackageWhitelist.getWatchedApps().contains("ch.protonmail.android"))
        assertFalse(PackageWhitelist.isWhitelisted("ch.protonmail.android"))
    }
}
