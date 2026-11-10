package com.financeautomation.data.remote

/**
 * Interface providing OAuth access tokens for Google REST APIs.
 */
interface TokenProvider {
    /**
     * Returns the currently active access token, or null if unauthenticated.
     */
    fun getAccessToken(): String?

    /**
     * Clears the stale token, executes silent refresh, and returns a fresh access token, or null on failure.
     */
    fun refreshAccessToken(): String?
}
