package com.financeautomation.core.utils

import android.content.Context
import android.os.Build
import android.os.Bundle
import com.financeautomation.BuildConfig
import com.google.firebase.FirebaseApp
import com.google.firebase.analytics.FirebaseAnalytics
import com.google.firebase.analytics.FirebaseAnalytics.ConsentStatus
import com.google.firebase.analytics.FirebaseAnalytics.ConsentType

/**
 * P2-G: Privacy-first, opt-in anonymous analytics gateway.
 *
 * Strict Privacy Guarantees:
 * - Disabled by default. Only active when the user explicitly opts in via Settings.
 * - Inert when Firebase is not initialized (e.g. google-services.json absent or unconfigured).
 * - Advertising ID and Ad Storage are permanently denied and removed from the manifest.
 * - Strict allow-list of events, parameter keys, and parameter values.
 * - NEVER sends: transaction amounts, payee/merchant names, user-typed names (categories, accounts,
 *   friends, trips, subscriptions, notes), account numbers, emails, spreadsheet IDs, file names,
 *   notification/SMS message text, free text of any kind, or user/device IDs.
 * - Maximum parameter value length is 20 characters.
 */
object AnalyticsManager {

    private const val PREFS_NAME = "analytics_config"
    private const val KEY_OPT_IN = "analytics_opt_in"
    private const val MAX_PARAM_LENGTH = 20

    @Volatile
    private var appContext: Context? = null

    /**
     * Fixed allow-list of user properties.
     */
    val ALLOWED_USER_PROPERTIES = setOf(
        "app_version",
        "build_type",
        "android_version",
        "brand",
        "model"
    )

    /**
     * Fixed allow-list of events and their permitted parameter keys.
     */
    val ALLOWED_EVENTS: Map<String, Set<String>> = mapOf(
        UsageEvents.EVENT_ONBOARDING_STEP to setOf("step_id", "status"),
        UsageEvents.EVENT_SETUP_HEALTH_ISSUE to setOf("item_id"),
        UsageEvents.EVENT_OEM_ROUTE_SHOWN to setOf("route_id"),
        UsageEvents.EVENT_CAPTURE_FUNNEL_DAY to setOf("count"),
        UsageEvents.EVENT_SYNC_RESULT to setOf("error_kind", "count"),
        UsageEvents.EVENT_FEATURE_USED to setOf("feature_name"),
        UsageEvents.EVENT_PAYMENT_APP_SEEN to setOf("app_id", "count")
    )

    /**
     * Permitted string values for enum-style parameters.
     */
    val ALLOWED_PARAM_VALUES: Map<String, Set<String>> = mapOf(
        "status" to setOf("done", "skipped"),
        "step_id" to setOf("sign_in", "workbook", "accounts", "permissions"),
        "feature_name" to setOf(
            "trips",
            "subscriptions",
            "custom_category_added",
            "custom_account_added",
            "repair_workbook",
            "export_csv",
            "split_mode_personal",
            "split_mode_equal",
            "split_mode_custom"
        )
    )

    internal var testOptInOverride: Boolean? = null
    internal var testEventListener: ((eventName: String, params: Map<String, Any>?) -> Unit)? = null

    internal fun resetForTesting() {
        testOptInOverride = null
        testEventListener = null
    }

    private fun isFirebaseAvailable(context: Context?): Boolean {
        val ctx = context ?: appContext ?: return false
        return try {
            FirebaseApp.getApps(ctx).isNotEmpty()
        } catch (_: Exception) {
            false
        }
    }

    fun isOptedIn(context: Context? = null): Boolean {
        testOptInOverride?.let { return it }
        val ctx = context ?: appContext ?: return false
        val prefs = ctx.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        return prefs.getBoolean(KEY_OPT_IN, false)
    }

    fun setOptIn(context: Context, enabled: Boolean) {
        appContext = context.applicationContext
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        prefs.edit().putBoolean(KEY_OPT_IN, enabled).apply()

        if (isFirebaseAvailable(context)) {
            try {
                val analytics = FirebaseAnalytics.getInstance(context)
                if (enabled) {
                    analytics.setConsent(
                        mapOf(
                            ConsentType.ANALYTICS_STORAGE to ConsentStatus.GRANTED,
                            ConsentType.AD_STORAGE to ConsentStatus.DENIED,
                            ConsentType.AD_USER_DATA to ConsentStatus.DENIED,
                            ConsentType.AD_PERSONALIZATION to ConsentStatus.DENIED
                        )
                    )
                    analytics.setAnalyticsCollectionEnabled(true)
                    populateUserProperties(context)
                } else {
                    analytics.setAnalyticsCollectionEnabled(false)
                    analytics.resetAnalyticsData()
                    analytics.setConsent(
                        mapOf(
                            ConsentType.ANALYTICS_STORAGE to ConsentStatus.DENIED,
                            ConsentType.AD_STORAGE to ConsentStatus.DENIED,
                            ConsentType.AD_USER_DATA to ConsentStatus.DENIED,
                            ConsentType.AD_PERSONALIZATION to ConsentStatus.DENIED
                        )
                    )
                }
            } catch (e: Exception) {
                Diagnostics.record("AnalyticsManager", "setOptIn: ${e.javaClass.simpleName}")
            }
        }
    }

    fun initAtAppStart(context: Context) {
        appContext = context.applicationContext
        val optedIn = isOptedIn(context)
        if (isFirebaseAvailable(context)) {
            try {
                val analytics = FirebaseAnalytics.getInstance(context)
                if (optedIn) {
                    analytics.setConsent(
                        mapOf(
                            ConsentType.ANALYTICS_STORAGE to ConsentStatus.GRANTED,
                            ConsentType.AD_STORAGE to ConsentStatus.DENIED,
                            ConsentType.AD_USER_DATA to ConsentStatus.DENIED,
                            ConsentType.AD_PERSONALIZATION to ConsentStatus.DENIED
                        )
                    )
                    analytics.setAnalyticsCollectionEnabled(true)
                    populateUserProperties(context)
                } else {
                    analytics.setConsent(
                        mapOf(
                            ConsentType.ANALYTICS_STORAGE to ConsentStatus.DENIED,
                            ConsentType.AD_STORAGE to ConsentStatus.DENIED,
                            ConsentType.AD_USER_DATA to ConsentStatus.DENIED,
                            ConsentType.AD_PERSONALIZATION to ConsentStatus.DENIED
                        )
                    )
                    analytics.setAnalyticsCollectionEnabled(false)
                }
            } catch (e: Exception) {
                Diagnostics.record("AnalyticsManager", "initAtAppStart: ${e.javaClass.simpleName}")
            }
        }
    }

    private fun populateUserProperties(context: Context) {
        setUserProperty(context, "app_version", BuildConfig.VERSION_NAME)
        setUserProperty(context, "build_type", BuildConfig.BUILD_TYPE)
        setUserProperty(context, "brand", Build.BRAND)
        setUserProperty(context, "model", Build.MODEL)
        setUserProperty(context, "android_version", Build.VERSION.SDK_INT.toString())
    }

    fun setUserProperty(context: Context? = null, name: String, value: String) {
        if (!ALLOWED_USER_PROPERTIES.contains(name)) return
        val ctx = context ?: appContext ?: return
        if (!isOptedIn(ctx) || !isFirebaseAvailable(ctx)) return
        if (value.length > MAX_PARAM_LENGTH) return

        try {
            FirebaseAnalytics.getInstance(ctx).setUserProperty(name, value)
        } catch (e: Exception) {
            Diagnostics.record("AnalyticsManager", "setUserProperty: ${e.javaClass.simpleName}")
        }
    }

    /**
     * Logs an allow-listed anonymous event. Drops all unpermitted events, keys, or values.
     * Returns true if the event was accepted and submitted to Firebase, false if dropped.
     */
    fun logEvent(context: Context? = null, eventName: String, params: Map<String, Any>? = null): Boolean {
        val allowedKeys = ALLOWED_EVENTS[eventName] ?: return false

        val ctx = context ?: appContext
        if (!isOptedIn(ctx)) return false

        if (params != null) {
            for ((key, value) in params) {
                if (!allowedKeys.contains(key)) return false

                when (value) {
                    is Number -> {}
                    is String -> {
                        if (value.length > MAX_PARAM_LENGTH) return false
                        val allowedVals = ALLOWED_PARAM_VALUES[key]
                        if (allowedVals != null && !allowedVals.contains(value)) return false
                    }
                    else -> return false
                }
            }
        }

        testEventListener?.invoke(eventName, params)

        if (ctx == null || !isFirebaseAvailable(ctx)) return true

        val bundle = Bundle()
        if (params != null) {
            for ((key, value) in params) {
                when (value) {
                    is Number -> bundle.putLong(key, value.toLong())
                    is String -> bundle.putString(key, value)
                }
            }
        }

        return try {
            FirebaseAnalytics.getInstance(ctx).logEvent(eventName, bundle)
            true
        } catch (e: Exception) {
            Diagnostics.record("AnalyticsManager", "logEvent: ${e.javaClass.simpleName}")
            false
        }
    }
}

/**
 * Typed identifiers for the fixed allow-list of anonymous usage events.
 */
object UsageEvents {
    const val EVENT_ONBOARDING_STEP = "onboarding_step"
    const val EVENT_SETUP_HEALTH_ISSUE = "setup_health_issue"
    const val EVENT_OEM_ROUTE_SHOWN = "oem_route_shown"
    const val EVENT_CAPTURE_FUNNEL_DAY = "capture_funnel_day"
    const val EVENT_SYNC_RESULT = "sync_result"
    const val EVENT_FEATURE_USED = "feature_used"
    const val EVENT_PAYMENT_APP_SEEN = "payment_app_seen"
}
