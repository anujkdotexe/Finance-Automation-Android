package com.financeautomation.core.utils

import android.content.Context
import android.os.Build
import com.financeautomation.BuildConfig
import com.google.firebase.FirebaseApp
import com.google.firebase.crashlytics.FirebaseCrashlytics

/**
 * Opt-in Crashlytics and freeze reporter with strict key allow-listing (Phase 9.5).
 * Inert when Firebase is not initialized (e.g. google-services.json absent).
 * Strictly forbids any financial data, merchant names, or alert content.
 */
object CrashlyticsManager {

    private const val PREFS_NAME = "crashlytics_config"
    private const val KEY_OPT_IN = "crashlytics_opt_in"

    @Volatile
    private var appContext: Context? = null

    /**
     * Strict allow-list of custom keys per Beta & Release Plan 4.5 & Phase Guide 9.5.
     * No other key is ever sent to Firebase.
     */
    val ALLOWED_CUSTOM_KEYS = setOf(
        "app_version",
        "build_type",
        "brand",
        "model",
        "android_version",
        "setup_health_summary",
        "trail_funnel_summary"
    )

    private fun isFirebaseAvailable(context: Context?): Boolean {
        val ctx = context ?: appContext ?: return false
        return try {
            FirebaseApp.getApps(ctx).isNotEmpty()
        } catch (e: Exception) {
            false
        }
    }

    fun isOptedIn(context: Context? = null): Boolean {
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
                FirebaseCrashlytics.getInstance().setCrashlyticsCollectionEnabled(enabled)
                if (enabled) {
                    populateBaselineKeys(context)
                }
            } catch (e: Exception) {
                Diagnostics.record("CrashlyticsManager", "setCollectionEnabled: ${e.javaClass.simpleName}")
            }
        }
    }

    fun initAtAppStart(context: Context) {
        appContext = context.applicationContext
        val optedIn = isOptedIn(context)
        if (isFirebaseAvailable(context)) {
            try {
                FirebaseCrashlytics.getInstance().setCrashlyticsCollectionEnabled(optedIn)
                if (optedIn) {
                    populateBaselineKeys(context)
                }
            } catch (e: Exception) {
                Diagnostics.record("CrashlyticsManager", "initAtAppStart: ${e.javaClass.simpleName}")
            }
        }
    }

    private fun populateBaselineKeys(context: Context) {
        setCustomKey(context, "app_version", BuildConfig.VERSION_NAME)
        setCustomKey(context, "build_type", BuildConfig.BUILD_TYPE)
        setCustomKey(context, "brand", Build.BRAND)
        setCustomKey(context, "model", Build.MODEL)
        setCustomKey(context, "android_version", Build.VERSION.SDK_INT.toString())
    }

    /**
     * Sets a custom key strictly matching the allow-list.
     */
    fun setCustomKey(context: Context? = null, key: String, value: String) {
        if (!ALLOWED_CUSTOM_KEYS.contains(key)) return
        val ctx = context ?: appContext ?: return
        if (!isOptedIn(ctx) || !isFirebaseAvailable(ctx)) return

        try {
            FirebaseCrashlytics.getInstance().setCustomKey(key, value)
        } catch (e: Exception) {
            Diagnostics.record("CrashlyticsManager", "setCustomKey: ${e.javaClass.simpleName}")
        }
    }

    /**
     * Non-fatal exception logging: records only exception class and location, NEVER message text.
     */
    fun recordNonFatal(throwable: Throwable, locationTag: String, context: Context? = null) {
        val ctx = context ?: appContext
        if (ctx == null || !isOptedIn(ctx) || !isFirebaseAvailable(ctx)) return

        try {
            val sanitizedException = RuntimeException("${throwable.javaClass.simpleName} at $locationTag")
            sanitizedException.stackTrace = throwable.stackTrace.take(5).toTypedArray()
            FirebaseCrashlytics.getInstance().recordException(sanitizedException)
        } catch (e: Exception) {
            Diagnostics.record("CrashlyticsManager", "recordNonFatal: ${e.javaClass.simpleName}")
        }
    }

    fun recordNonFatal(context: Context, throwable: Throwable, locationTag: String) {
        recordNonFatal(throwable, locationTag, context)
    }

    /**
     * Verification test crash: strictly limited to debug and beta builds.
     */
    fun triggerTestCrash() {
        if (BuildConfig.DEBUG || BuildConfig.BUILD_TYPE == "beta") {
            throw RuntimeException("Crashlytics test crash verification (debug/beta)")
        }
    }
}
