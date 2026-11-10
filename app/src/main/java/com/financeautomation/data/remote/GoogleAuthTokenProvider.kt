package com.financeautomation.data.remote

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat
import com.financeautomation.R
import com.financeautomation.core.utils.Diagnostics
import com.google.android.gms.auth.GoogleAuthUtil
import com.google.android.gms.auth.api.identity.AuthorizationRequest
import com.google.android.gms.auth.api.identity.AuthorizationResult
import com.google.android.gms.auth.api.identity.Identity
import com.google.android.gms.common.api.Scope
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlin.coroutines.resume

/**
 * Sealed result type representing asynchronous Google Identity authorization outcomes.
 */
sealed class AuthResult {
    data class Token(val token: String) : AuthResult()
    data class NeedsConsent(val pendingIntent: PendingIntent) : AuthResult()
    data class Failed(val exception: Throwable) : AuthResult()
}

/**
 * Modern TokenProvider implementation backed by Google Identity authorization API.
 * Requests scope drive.file only, caches tokens in memory for at most 50 minutes,
 * handles asynchronous authorization, and posts a single low-priority reconnect notification when background consent expires.
 */
class GoogleAuthTokenProvider(
    private val context: Context,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO
) : TokenProvider {

    @Volatile
    private var cachedToken: String? = null

    @Volatile
    private var tokenExpiresAt: Long = 0L

    private val authClient = Identity.getAuthorizationClient(context)

    /**
     * Executes asynchronous Google Identity authorization on [ioDispatcher].
     * Returns cached token if still valid (under 50 minutes).
     * In foreground, returns [AuthResult.NeedsConsent] with the pending intent if consent is needed.
     * In background ([isForeground] = false), posts a single low-priority notification without launching UI,
     * returning [AuthResult.Failed].
     */
    suspend fun requestAuthorization(isForeground: Boolean = true): AuthResult = withContext(ioDispatcher) {
        val now = System.currentTimeMillis()
        val existing = cachedToken
        if (!existing.isNullOrBlank() && now < tokenExpiresAt) {
            return@withContext AuthResult.Token(existing)
        }

        val request = AuthorizationRequest.builder()
            .setRequestedScopes(listOf(Scope(SCOPE_DRIVE_FILE)))
            .build()

        try {
            val result: AuthorizationResult = suspendCancellableCoroutine { continuation ->
                authClient.authorize(request)
                    .addOnSuccessListener { authResult ->
                        if (continuation.isActive) {
                            continuation.resume(authResult)
                        }
                    }
                    .addOnFailureListener { exception ->
                        if (continuation.isActive) {
                            continuation.resumeWith(kotlin.Result.failure(exception))
                        }
                    }
            }

            if (result.hasResolution()) {
                val resolution = result.pendingIntent
                if (isForeground && resolution != null) {
                    AuthResult.NeedsConsent(resolution)
                } else {
                    postReconnectNotification()
                    AuthResult.Failed(AuthExpiredException("Google Drive consent required"))
                }
            } else {
                val freshToken = result.accessToken
                if (!freshToken.isNullOrBlank()) {
                    cachedToken = freshToken
                    tokenExpiresAt = System.currentTimeMillis() + TOKEN_TTL_MILLIS
                    AuthResult.Token(freshToken)
                } else {
                    val pendingIntent = result.pendingIntent
                    if (isForeground && pendingIntent != null) {
                        AuthResult.NeedsConsent(pendingIntent)
                    } else {
                        postReconnectNotification()
                        AuthResult.Failed(AuthExpiredException("No access token returned by authorization client"))
                    }
                }
            }
        } catch (e: Exception) {
            if (!isForeground) {
                postReconnectNotification()
            }
            AuthResult.Failed(e)
        }
    }

    override fun getAccessToken(): String? {
        val now = System.currentTimeMillis()
        val existing = cachedToken
        if (!existing.isNullOrBlank() && now < tokenExpiresAt) {
            return existing
        }
        return runBlocking(ioDispatcher) {
            when (val res = requestAuthorization(isForeground = false)) {
                is AuthResult.Token -> res.token
                else -> null
            }
        }
    }

    override fun refreshAccessToken(): String? {
        val staleToken = cachedToken
        cachedToken = null
        tokenExpiresAt = 0L
        return runBlocking(ioDispatcher) {
            if (!staleToken.isNullOrBlank()) {
                try {
                    // GoogleAuthUtil.clearToken is used here because AuthorizationClient in the bundled Play Services Auth library
                    // (v21.3.0) does not provide a clearToken method. GoogleAuthUtil.clearToken directly invalidates the cached
                    // OAuth token in Google Play Services, ensuring subsequent authorization requests fetch a fresh token.
                    GoogleAuthUtil.clearToken(context, staleToken)
                } catch (e: Exception) {
                    Diagnostics.record("GoogleAuthTokenProvider", "${e.javaClass.simpleName}: clearToken")
                }
            }
            when (val res = requestAuthorization(isForeground = false)) {
                is AuthResult.Token -> res.token
                else -> {
                    postReconnectNotification()
                    null
                }
            }
        }
    }

    fun clearCache() {
        cachedToken = null
        tokenExpiresAt = 0L
    }

    private fun postReconnectNotification() {
        try {
            val notificationManager = context.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager ?: return

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                val channel = NotificationChannel(
                    CHANNEL_AUTH_ALERTS,
                    context.getString(R.string.notification_channel_auth_name),
                    NotificationManager.IMPORTANCE_LOW
                ).apply {
                    description = context.getString(R.string.notification_channel_auth_desc)
                }
                notificationManager.createNotificationChannel(channel)
            }

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                val active = notificationManager.activeNotifications
                if (active.any { it.id == NOTIFICATION_ID_AUTH_RECONNECT }) {
                    return
                }
            }

            val launchIntent = context.packageManager.getLaunchIntentForPackage(context.packageName)?.apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            } ?: return

            val pendingIntent = PendingIntent.getActivity(
                context,
                0,
                launchIntent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )

            val notification = NotificationCompat.Builder(context, CHANNEL_AUTH_ALERTS)
                .setSmallIcon(android.R.drawable.stat_notify_sync)
                .setContentTitle(context.getString(R.string.notif_auth_reconnect_title))
                .setContentText(context.getString(R.string.notif_auth_reconnect_text))
                .setPriority(NotificationCompat.PRIORITY_LOW)
                .setCategory(NotificationCompat.CATEGORY_STATUS)
                .setContentIntent(pendingIntent)
                .setAutoCancel(true)
                .build()

            notificationManager.notify(NOTIFICATION_ID_AUTH_RECONNECT, notification)
        } catch (e: Exception) {
            Diagnostics.record("GoogleAuthTokenProvider", "${e.javaClass.simpleName}: postReconnectNotification")
        }
    }

    companion object {
        const val SCOPE_DRIVE_FILE: String = "https://www.googleapis.com/auth/drive.file"
        const val NOTIFICATION_ID_AUTH_RECONNECT: Int = 9001
        const val CHANNEL_AUTH_ALERTS: String = "channel_auth_alerts"
        const val TOKEN_TTL_MILLIS: Long = 50 * 60 * 1000L // 50 minutes
    }
}
