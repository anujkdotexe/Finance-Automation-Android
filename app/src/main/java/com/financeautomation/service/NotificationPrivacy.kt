package com.financeautomation.service

import android.app.Notification
import android.content.Context
import androidx.core.app.NotificationCompat
import com.financeautomation.FinanceApplication
import com.financeautomation.R
import kotlinx.coroutines.CancellationException

/**
 * Lock screen privacy for notifications that carry a payee or an amount. With the app lock on they are marked
 * private and get a public version that says only what happened; with the app lock off the full text stays.
 */
object NotificationPrivacy {

    data class Policy(val visibility: Int, val usePublicVersion: Boolean)

    fun policy(appLockOn: Boolean): Policy =
        if (appLockOn) Policy(NotificationCompat.VISIBILITY_PRIVATE, true)
        else Policy(NotificationCompat.VISIBILITY_PUBLIC, false)

    /** Reads the app lock setting; when it cannot be read the notification stays private. */
    suspend fun resolveAppLockOn(read: suspend () -> Boolean): Boolean = try {
        read()
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        true
    }

    /** Whether the app lock is on, read through the app container. */
    suspend fun isAppLockOn(context: Context): Boolean = resolveAppLockOn {
        val container = (context.applicationContext as FinanceApplication).appContainer
        container.ensureAppLockStarted()
        container.appLockManager.isEnabled
    }

    /**
     * Applies the policy to [builder]. [publicTitle] and [publicText] are what the lock screen shows when the
     * notification is private; they must not contain a payee or an amount.
     */
    fun apply(
        builder: NotificationCompat.Builder,
        context: Context,
        channelId: String,
        appLockOn: Boolean,
        publicTitle: String,
        publicText: String = context.getString(R.string.notif_public_open_to_view)
    ): NotificationCompat.Builder {
        val p = policy(appLockOn)
        builder.setVisibility(p.visibility)
        if (p.usePublicVersion) {
            val publicVersion: Notification = NotificationCompat.Builder(context, channelId)
                .setSmallIcon(R.drawable.ic_launcher_monochrome)
                .setContentTitle(publicTitle)
                .setContentText(publicText)
                .build()
            builder.setPublicVersion(publicVersion)
        }
        return builder
    }
}
