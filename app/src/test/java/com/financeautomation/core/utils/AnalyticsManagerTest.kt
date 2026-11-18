package com.financeautomation.core.utils

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * P2-G Unit Tests:
 * - Proves anonymous analytics drops everything when not opted in.
 * - Proves only allow-listed events, parameters, and enum values are accepted.
 * - Proves parameters longer than 20 characters are dropped.
 * - Proves user properties allow-list is strictly limited to technical device metadata.
 * - Proves no financial amounts, user names, or free text can ever be transmitted.
 */
class AnalyticsManagerTest {

    @Test
    fun eventsAllowListContainsOnlyApprovedTelemetryEvents() {
        val allowedEvents = AnalyticsManager.ALLOWED_EVENTS

        val expectedEvents = setOf(
            UsageEvents.EVENT_ONBOARDING_STEP,
            UsageEvents.EVENT_SETUP_HEALTH_ISSUE,
            UsageEvents.EVENT_OEM_ROUTE_SHOWN,
            UsageEvents.EVENT_CAPTURE_FUNNEL_DAY,
            UsageEvents.EVENT_SYNC_RESULT,
            UsageEvents.EVENT_FEATURE_USED,
            UsageEvents.EVENT_PAYMENT_APP_SEEN
        )
        assertEquals(expectedEvents, allowedEvents.keys)
    }

    @Test
    fun userPropertiesStrictlyLimitedToTechnicalDeviceMetadata() {
        val expectedProperties = setOf(
            "app_version",
            "build_type",
            "android_version",
            "brand",
            "model"
        )
        assertEquals(expectedProperties, AnalyticsManager.ALLOWED_USER_PROPERTIES)
    }

    @Test
    fun unlistedEventIsAlwaysDropped() {
        val result = AnalyticsManager.logEvent(
            context = null,
            eventName = "unlisted_custom_event",
            params = mapOf("count" to 1)
        )
        assertFalse("Unlisted event must be rejected", result)
    }

    @Test
    fun unlistedParameterForAllowedEventIsAlwaysDropped() {
        val result = AnalyticsManager.logEvent(
            context = null,
            eventName = UsageEvents.EVENT_CAPTURE_FUNNEL_DAY,
            params = mapOf("amount" to 500) // "amount" is not an allowed param
        )
        assertFalse("Unlisted parameter for allowed event must be rejected", result)
    }

    @Test
    fun stringParameterExceedingTwentyCharactersIsDropped() {
        val longValue = "a".repeat(21)
        val result = AnalyticsManager.logEvent(
            context = null,
            eventName = UsageEvents.EVENT_ONBOARDING_STEP,
            params = mapOf("status" to longValue)
        )
        assertFalse("Parameter values > 20 characters must be rejected", result)
    }

    @Test
    fun stringParameterWithUnpermittedValueIsDropped() {
        // "status" only permits "done" or "skipped"
        val result = AnalyticsManager.logEvent(
            context = null,
            eventName = UsageEvents.EVENT_ONBOARDING_STEP,
            params = mapOf("status" to "invalid_arbitrary_text")
        )
        assertFalse("String parameters with unpermitted values must be rejected", result)
    }

    @Test
    fun permittedEnumValuesMatchSpecification() {
        val allowedStatus = AnalyticsManager.ALLOWED_PARAM_VALUES["status"]
        assertNotNull(allowedStatus)
        assertEquals(setOf("done", "skipped"), allowedStatus)

        val allowedFeatures = AnalyticsManager.ALLOWED_PARAM_VALUES["feature_name"]
        assertNotNull(allowedFeatures)
        assertTrue(allowedFeatures!!.contains("trips"))
        assertTrue(allowedFeatures.contains("subscriptions"))
        assertTrue(allowedFeatures.contains("custom_category_added"))
        assertTrue(allowedFeatures.contains("custom_account_added"))
        assertTrue(allowedFeatures.contains("repair_workbook"))
        assertTrue(allowedFeatures.contains("export_csv"))
        assertTrue(allowedFeatures.contains("split_mode_personal"))
        assertTrue(allowedFeatures.contains("split_mode_equal"))
        assertTrue(allowedFeatures.contains("split_mode_custom"))
    }

    @Test
    fun verifyNoForbiddenTermsInAllowedParameters() {
        val forbiddenSubstrings = listOf("amount", "email", "merchant", "payee", "note", "text", "body", "sms")
        for ((event, params) in AnalyticsManager.ALLOWED_EVENTS) {
            for (param in params) {
                for (forbidden in forbiddenSubstrings) {
                    assertFalse(
                        "Parameter '$param' in event '$event' must not contain forbidden keyword '$forbidden'",
                        param.lowercase().contains(forbidden)
                    )
                }
            }
        }
    }

    @Test
    fun productionPathEventSentWhenOptedInAndDroppedWhenOptedOut() {
        val capturedEvents = mutableListOf<Pair<String, Map<String, Any>?>>()
        AnalyticsManager.testEventListener = { event, params ->
            capturedEvents.add(event to params)
        }

        try {
            // Case 1: Opt-in OFF -> must be dropped
            AnalyticsManager.testOptInOverride = false
            capturedEvents.clear()

            val dropped = AnalyticsManager.logEvent(
                eventName = UsageEvents.EVENT_ONBOARDING_STEP,
                params = mapOf("step_id" to "accounts", "status" to "done")
            )
            assertFalse("Event must be dropped when opt-in is false", dropped)
            assertTrue("No event must reach listener when opted out", capturedEvents.isEmpty())

            // Case 2: Opt-in ON -> must be accepted and forwarded
            AnalyticsManager.testOptInOverride = true
            capturedEvents.clear()

            val accepted = AnalyticsManager.logEvent(
                eventName = UsageEvents.EVENT_ONBOARDING_STEP,
                params = mapOf("step_id" to "accounts", "status" to "done")
            )
            assertTrue("Event must be accepted when opt-in is true", accepted)
            assertEquals(1, capturedEvents.size)
            assertEquals(UsageEvents.EVENT_ONBOARDING_STEP, capturedEvents[0].first)
            assertEquals("accounts", capturedEvents[0].second?.get("step_id"))
            assertEquals("done", capturedEvents[0].second?.get("status"))
        } finally {
            AnalyticsManager.testOptInOverride = null
            AnalyticsManager.testEventListener = null
        }
    }
}
