package com.javdc.tussapp.data.remote.api.interceptor

import com.javdc.tussapp.data.remote.datasource.TokenDataSource
import kotlinx.coroutines.runBlocking
import okhttp3.Interceptor
import okhttp3.Request
import okhttp3.Response

class OAuthInterceptor(private val tokenDataSource: TokenDataSource): Interceptor {

    override fun intercept(chain: Interceptor.Chain): Response {
        val originalRequest = chain.request()

        // No token stored, or it has expired: get a new one before the request instead of
        // waiting for a 401 (which costs an extra round trip per request).
        val token = tokenDataSource.getAccessToken() ?: renewToken(failedToken = null)

        val response = chain.proceed(originalRequest.withToken(token))

        if (response.code == 401) {
            val newToken = renewToken(failedToken = token) ?: return response
            response.close()
            return chain.proceed(originalRequest.withToken(newToken))
        }

        return response
    }

    /**
     * Only one thread renews the token at a time. On app start several requests are launched in
     * parallel (dates, cards, notices...); without this lock each of them made its own login request.
     * Threads that were waiting reuse the token obtained by the first one.
     */
    private fun renewToken(failedToken: String?): String? = synchronized(lock) {
        val current = tokenDataSource.getAccessToken()
        if (current != null && current != failedToken) {
            current
        } else {
            runBlocking { tokenDataSource.refreshToken() }
        }
    }

    private fun Request.withToken(token: String?): Request =
        if (token.isNullOrBlank()) this
        else newBuilder().header("Authorization", "Bearer $token").build()

    private companion object {
        // Shared by all instances (Hilt doesn't scope the interceptor as a singleton)
        private val lock = Any()
    }

}
