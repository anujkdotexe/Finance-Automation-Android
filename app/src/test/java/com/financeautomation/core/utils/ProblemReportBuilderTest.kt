package com.financeautomation.core.utils

import com.google.firebase.Timestamp
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.UUID

/**
 * Unit tests for ProblemReportPayload, ProblemReportFields allow-list,
 * and compliance with firebase/firestore.rules.
 */
class ProblemReportBuilderTest {

    @Test
    fun validPayloadProducesAllRequiredFields() {
        val payload = ProblemReportPayload(
            schemaVersion = 1,
            appVersion = "1.0.0-beta",
            versionCode = 567,
            deviceModel = "Google Pixel 8",
            androidSdk = 34,
            installId = UUID.randomUUID().toString(),
            screen = "Diagnostics",
            reasonCode = "user_report",
            counters = "error_network=2; error_timeout=1",
            text = "Encountered sync timeout when backing up"
        )

        val firestoreMap = payload.toFirestoreData(isServerTimestamp = false)

        assertEquals(1, firestoreMap[ProblemReportFields.FIELD_SCHEMA_VERSION])
        assertTrue(firestoreMap[ProblemReportFields.FIELD_CREATED_AT] is Timestamp)
        assertEquals("1.0.0-beta", firestoreMap[ProblemReportFields.FIELD_APP_VERSION])
        assertEquals(567, firestoreMap[ProblemReportFields.FIELD_VERSION_CODE])
        assertEquals("Google Pixel 8", firestoreMap[ProblemReportFields.FIELD_DEVICE_MODEL])
        assertEquals(34, firestoreMap[ProblemReportFields.FIELD_ANDROID_SDK])
        assertEquals(36, (firestoreMap[ProblemReportFields.FIELD_INSTALL_ID] as String).length)
        assertEquals("Diagnostics", firestoreMap[ProblemReportFields.FIELD_SCREEN])
        assertEquals("user_report", firestoreMap[ProblemReportFields.FIELD_REASON_CODE])
        assertEquals("error_network=2; error_timeout=1", firestoreMap[ProblemReportFields.FIELD_COUNTERS])
        assertEquals("Encountered sync timeout when backing up", firestoreMap[ProblemReportFields.FIELD_TEXT])

        assertTrue(firestoreRulesSimulation(firestoreMap))
    }

    @Test
    fun nullOrBlankCountersIsOmittedFromMap() {
        val payload = ProblemReportPayload(
            schemaVersion = 1,
            appVersion = "1.0.0",
            versionCode = 100,
            deviceModel = "Samsung Galaxy S24",
            androidSdk = 34,
            installId = UUID.randomUUID().toString(),
            screen = "Settings",
            reasonCode = "feedback",
            counters = null,
            text = "Testing null counters"
        )

        val firestoreMap = payload.toFirestoreData(isServerTimestamp = false)
        assertFalse(firestoreMap.containsKey(ProblemReportFields.FIELD_COUNTERS))
        assertTrue(firestoreRulesSimulation(firestoreMap))
    }

    @Test
    fun extraForbiddenKeysAreNeverIncluded() {
        val payload = ProblemReportPayload(
            schemaVersion = 1,
            appVersion = "1.0.0",
            versionCode = 100,
            deviceModel = "Device",
            androidSdk = 33,
            installId = UUID.randomUUID().toString(),
            screen = "Diagnostics",
            reasonCode = "test",
            counters = null,
            text = "Safe description"
        )

        val firestoreMap = payload.toFirestoreData(isServerTimestamp = false)

        // Verify none of the forbidden concepts exist as keys in the map
        for (forbidden in ProblemReportFields.FORBIDDEN_WORDS) {
            assertFalse("Disallowed word key '$forbidden' must never exist in Firestore map", firestoreMap.containsKey(forbidden))
        }

        // Verify map only contains keys in ALLOWED_FIELDS
        for (key in firestoreMap.keys) {
            assertTrue("Key '$key' must be in ALLOWED_FIELDS", key in ProblemReportFields.ALLOWED_FIELDS)
        }
    }

    @Test
    fun fieldConstraintsAreStrictlyEnforced() {
        val overlyLongAppVer = "a".repeat(100)
        val overlyLongModel = "m".repeat(200)
        val overlyLongScreen = "s".repeat(150)
        val overlyLongReason = "r".repeat(150)
        val overlyLongCounters = "c".repeat(3000)
        val overlyLongText = "t".repeat(5000)

        val payload = ProblemReportPayload(
            schemaVersion = 1,
            appVersion = overlyLongAppVer,
            versionCode = 2_000_000, // exceeds 999999
            deviceModel = overlyLongModel,
            androidSdk = 150, // exceeds 99
            installId = UUID.randomUUID().toString(),
            screen = overlyLongScreen,
            reasonCode = overlyLongReason,
            counters = overlyLongCounters,
            text = overlyLongText
        )

        val firestoreMap = payload.toFirestoreData(isServerTimestamp = false)

        val appVer = firestoreMap[ProblemReportFields.FIELD_APP_VERSION] as String
        assertTrue(appVer.length <= 32)

        val versionCode = firestoreMap[ProblemReportFields.FIELD_VERSION_CODE] as Int
        assertTrue(versionCode in 1..999999)

        val model = firestoreMap[ProblemReportFields.FIELD_DEVICE_MODEL] as String
        assertTrue(model.length <= 64)

        val sdk = firestoreMap[ProblemReportFields.FIELD_ANDROID_SDK] as Int
        assertTrue(sdk in 28..99)

        val screen = firestoreMap[ProblemReportFields.FIELD_SCREEN] as String
        assertTrue(screen.length <= 64)

        val reason = firestoreMap[ProblemReportFields.FIELD_REASON_CODE] as String
        assertTrue(reason.length <= 64)

        val counters = firestoreMap[ProblemReportFields.FIELD_COUNTERS] as String
        assertTrue(counters.length <= 1500)

        val text = firestoreMap[ProblemReportFields.FIELD_TEXT] as String
        assertTrue(text.length <= 2000)

        assertTrue(firestoreRulesSimulation(firestoreMap))
    }

    @Test
    fun sensitiveFinancialDataInTextIsScrubbed() {
        val payload = ProblemReportPayload(
            schemaVersion = 1,
            appVersion = "1.0.0",
            versionCode = 100,
            deviceModel = "Test Device",
            androidSdk = 34,
            installId = UUID.randomUUID().toString(),
            screen = "Diagnostics",
            reasonCode = "leak_test",
            counters = null,
            text = "Transaction of Rs 500.00 to merchant@upi failed for a/c 123456789012"
        )

        val firestoreMap = payload.toFirestoreData(isServerTimestamp = false)
        val text = firestoreMap[ProblemReportFields.FIELD_TEXT] as String

        assertFalse("Raw rupee amounts must not appear unscrubbed", text.contains("500.00"))
        assertFalse("Raw 12-digit account numbers must not appear unscrubbed", text.contains("123456789012"))
    }

    @Test
    fun previewTextFormatsCleanly() {
        val installUuid = UUID.randomUUID().toString()
        val payload = ProblemReportPayload(
            schemaVersion = 1,
            appVersion = "1.0.0",
            versionCode = 567,
            deviceModel = "Samsung Galaxy",
            androidSdk = 34,
            installId = installUuid,
            screen = "Diagnostics",
            reasonCode = "test_run",
            counters = "c1=10; c2=20",
            text = "User typed message here"
        )

        val preview = payload.toPlainText()
        assertTrue(preview.contains("Schema Version: 1"))
        assertTrue(preview.contains("App Version: 1.0.0 (567)"))
        assertTrue(preview.contains("Device Model: Samsung Galaxy"))
        assertTrue(preview.contains("Android SDK: 34"))
        assertTrue(preview.contains("Install ID: $installUuid"))
        assertTrue(preview.contains("Screen: Diagnostics"))
        assertTrue(preview.contains("Reason Code: test_run"))
        assertTrue(preview.contains("Counters:\nc1=10; c2=20"))
        assertTrue(preview.contains("User Notes:\nUser typed message here"))
    }

    @Test
    fun firestoreRulesSimulationRejectsMalformedPayloads() {
        val validInstallId = UUID.randomUUID().toString()
        val baseMap = mutableMapOf<String, Any>(
            "schemaVersion" to 1,
            "createdAt" to Timestamp.now(),
            "appVersion" to "1.0.0",
            "versionCode" to 100,
            "deviceModel" to "Pixel 7",
            "androidSdk" to 34,
            "installId" to validInstallId,
            "screen" to "Diagnostics",
            "reasonCode" to "bug",
            "text" to "Sample bug text"
        )
        assertTrue("Base map must pass rules", firestoreRulesSimulation(baseMap))

        // Violation 1: invalid schemaVersion != 1
        val v1 = HashMap(baseMap).apply { put("schemaVersion", 2) }
        assertFalse(firestoreRulesSimulation(v1))

        // Violation 2: missing required field (e.g. installId)
        val v2 = HashMap(baseMap).apply { remove("installId") }
        assertFalse(firestoreRulesSimulation(v2))

        // Violation 3: extra disallowed key
        val v3 = HashMap(baseMap).apply { put("extraKey", "malicious") }
        assertFalse(firestoreRulesSimulation(v3))

        // Violation 4: installId not 36 chars
        val v4 = HashMap(baseMap).apply { put("installId", "short-id") }
        assertFalse(firestoreRulesSimulation(v4))

        // Violation 5: androidSdk < 28
        val v5 = HashMap(baseMap).apply { put("androidSdk", 27) }
        assertFalse(firestoreRulesSimulation(v5))

        // Violation 6: versionCode >= 1000000
        val v6 = HashMap(baseMap).apply { put("versionCode", 1_000_000) }
        assertFalse(firestoreRulesSimulation(v6))

        // Violation 7: text > 2000 chars
        val v7 = HashMap(baseMap).apply { put("text", "x".repeat(2001)) }
        assertFalse(firestoreRulesSimulation(v7))

        // Violation 8: counters > 1500 chars
        val v8 = HashMap(baseMap).apply { put("counters", "c".repeat(1501)) }
        assertFalse(firestoreRulesSimulation(v8))
    }

    /**
     * Exact Kotlin replica of isValidReport(d) function from firebase/firestore.rules:
     *
     * function isValidReport(d) {
     *   return d.keys().hasOnly([...])
     *     && d.keys().hasAll([...])
     *     && d.schemaVersion is int && d.schemaVersion == 1
     *     && d.createdAt is timestamp
     *     && d.appVersion is string && d.appVersion.size() >= 1 && d.appVersion.size() <= 32
     *     && d.versionCode is int && d.versionCode >= 1 && d.versionCode < 1000000
     *     && d.deviceModel is string && d.deviceModel.size() >= 1 && d.deviceModel.size() <= 64
     *     && d.androidSdk is int && d.androidSdk >= 28 && d.androidSdk <= 99
     *     && d.installId is string && d.installId.size() == 36
     *     && d.screen is string && d.screen.size() >= 1 && d.screen.size() <= 64
     *     && d.reasonCode is string && d.reasonCode.size() >= 1 && d.reasonCode.size() <= 64
     *     && d.text is string && d.text.size() <= 2000
     *     && (!d.keys().hasAny(['counters'])
     *         || (d.counters is string && d.counters.size() <= 1500));
     * }
     */
    private fun firestoreRulesSimulation(d: Map<String, Any?>): Boolean {
        val allowedKeys = setOf(
            "schemaVersion", "createdAt", "appVersion", "versionCode",
            "deviceModel", "androidSdk", "installId", "screen",
            "reasonCode", "counters", "text"
        )
        val requiredKeys = setOf(
            "schemaVersion", "createdAt", "appVersion", "versionCode",
            "deviceModel", "androidSdk", "installId", "screen",
            "reasonCode", "text"
        )

        if (!allowedKeys.containsAll(d.keys)) return false
        if (!d.keys.containsAll(requiredKeys)) return false

        val schemaVersion = d["schemaVersion"]
        if (schemaVersion !is Int || schemaVersion != 1) return false

        val createdAt = d["createdAt"]
        if (createdAt !is Timestamp) return false

        val appVersion = d["appVersion"]
        if (appVersion !is String || appVersion.isEmpty() || appVersion.length > 32) return false

        val versionCode = d["versionCode"]
        if (versionCode !is Int || versionCode < 1 || versionCode >= 1_000_000) return false

        val deviceModel = d["deviceModel"]
        if (deviceModel !is String || deviceModel.isEmpty() || deviceModel.length > 64) return false

        val androidSdk = d["androidSdk"]
        if (androidSdk !is Int || androidSdk < 28 || androidSdk > 99) return false

        val installId = d["installId"]
        if (installId !is String || installId.length != 36) return false

        val screen = d["screen"]
        if (screen !is String || screen.isEmpty() || screen.length > 64) return false

        val reasonCode = d["reasonCode"]
        if (reasonCode !is String || reasonCode.isEmpty() || reasonCode.length > 64) return false

        val text = d["text"]
        if (text !is String || text.length > 2000) return false

        if (d.containsKey("counters")) {
            val counters = d["counters"]
            if (counters !is String || counters.length > 1500) return false
        }

        return true
    }

    @Test
    fun submitReportTimesOutAfterSpecifiedDurationWithClearError() = kotlinx.coroutines.test.runTest {
        val payload = ProblemReportPayload(
            schemaVersion = 1,
            appVersion = "1.0.0",
            versionCode = 1,
            deviceModel = "Test Phone",
            androidSdk = 34,
            installId = UUID.randomUUID().toString(),
            screen = "About",
            reasonCode = "test_hang",
            counters = null,
            text = "Testing timeout"
        )
        val mockContext = object : android.content.ContextWrapper(null) {}
        val result = ProblemReportManager.submitReport(
            context = mockContext,
            payload = payload,
            timeoutMillis = 100L,
            submitter = {
                kotlinx.coroutines.delay(10_000L)
            }
        )
        assertTrue("Submission should fail when hanging", result.isFailure)
        val exception = result.exceptionOrNull()
        assertTrue("Exception should be TimeoutException", exception is java.util.concurrent.TimeoutException)
        assertTrue(
            "Error message should mention timeout duration",
            exception?.message?.contains("timed out") == true
        )
    }
}
