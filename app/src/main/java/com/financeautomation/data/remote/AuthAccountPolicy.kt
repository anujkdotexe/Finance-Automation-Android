package com.financeautomation.data.remote

/**
 * Which Google account the sync token is for. It is always the account the app shows (the stored email); with no
 * stored email nothing is authorized, so a signed out app can never silently sync with Play Services' last account.
 */
class AuthAccountPolicy(private val emailSource: suspend () -> String?) {

    sealed interface Decision {
        data object NoAccount : Decision
        data class Use(val email: String) : Decision
    }

    suspend fun decide(): Decision {
        val email = emailSource()?.trim()
        return if (email.isNullOrEmpty()) Decision.NoAccount else Decision.Use(email)
    }

    companion object {
        /** A cached token is reusable only for the account it was issued to. */
        fun cacheMatches(cachedFor: String?, email: String): Boolean = cachedFor != null && cachedFor.equals(email, ignoreCase = true)
    }
}
