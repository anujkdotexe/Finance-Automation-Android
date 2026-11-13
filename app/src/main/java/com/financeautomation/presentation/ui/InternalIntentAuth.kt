package com.financeautomation.presentation.ui

import android.app.PendingIntent
import android.content.Context
import android.content.Intent

/**
 * Marks intents that this app sends to its own exported components. The marker is a PendingIntent created by this
 * app; another app cannot create one whose creator package is ours, so [isInternal] cannot be forged.
 */
object InternalIntentAuth {

    const val EXTRA: String = "com.financeautomation.extra.INTERNAL_AUTH"

    /** Adds the marker to [intent] and returns it. */
    fun attach(context: Context, intent: Intent): Intent {
        val token = PendingIntent.getActivity(
            context,
            0,
            Intent(),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        return intent.putExtra(EXTRA, token)
    }

    /** True when [intent] carries a marker created by [ownPackage]. */
    fun isInternal(intent: Intent?, ownPackage: String): Boolean {
        if (intent == null) return false
        val token = try {
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) {
                intent.getParcelableExtra(EXTRA, PendingIntent::class.java)
            } else {
                @Suppress("DEPRECATION")
                intent.getParcelableExtra(EXTRA) as? PendingIntent
            }
        } catch (e: Exception) {
            null
        }
        return token?.creatorPackage == ownPackage
    }
}
