package com.financeautomation.data.remote

import okhttp3.Interceptor
import okhttp3.Response
import java.io.IOException

/**
 * OkHttp Interceptor that attaches the Bearer authorization token,
 * catches HTTP 401 Unauthorized responses, triggers token refresh via [TokenProvider],
 * and retries the request once with the updated Bearer token.
 */
class TokenRecoveryInterceptor(
    private val tokenProvider: TokenProvider? = null
) : Interceptor {

    @Throws(IOException::class)
    override fun intercept(chain: Interceptor.Chain): Response {
        val originalRequest = chain.request()

        val token = tokenProvider?.getAccessToken()
        val requestWithAuth = if (token != null && originalRequest.header("Authorization") == null) {
            originalRequest.newBuilder()
                .header("Authorization", "Bearer $token")
                .build()
        } else {
            originalRequest
        }

        val response = chain.proceed(requestWithAuth)

        if (response.code == 401 && tokenProvider != null) {
            synchronized(this) {
                val freshToken = tokenProvider.refreshAccessToken()
                if (freshToken != null) {
                    response.close()
                    val retryRequest = originalRequest.newBuilder()
                        .header("Authorization", "Bearer $freshToken")
                        .build()
                    return chain.proceed(retryRequest)
                }
            }
        }

        return response
    }
}
