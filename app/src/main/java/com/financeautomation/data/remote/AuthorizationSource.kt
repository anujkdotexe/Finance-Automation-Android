package com.financeautomation.data.remote

/** The part of Google authorization the Setup page needs; the real one is [GoogleAuthTokenProvider]. */
interface AuthorizationSource {
    /**
     * Asks for access to the Drive file scope. [accountEmail] names the account explicitly, so a picked account can be
     * authorized before it is stored; null uses the stored account.
     */
    suspend fun requestAuthorization(isForeground: Boolean = true, accountEmail: String? = null): AuthResult
}
