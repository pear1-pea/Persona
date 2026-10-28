package com.example.persona.di

import com.example.persona.core.auth.AuthTokenProvider
import com.example.persona.data.remote.BackendConfig
import java.io.IOException
import okhttp3.Interceptor
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.mock
import org.mockito.kotlin.never
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever

class NetworkPolicyTest {
    private val config = BackendConfig("https://backend.example/", "model", true)
    private val tokenProvider = object : AuthTokenProvider {
        var token: String? = "new-session"
        override fun currentToken() = token
        override suspend fun refreshToken() = token
        override fun invalidateSession() { token = null }
    }

    @Test
    fun `auth origin mismatch is refused before sending credentials`() {
        val client = NetworkModule.provideAuthOkHttpClient(config)
        val chain: Interceptor.Chain = mock()
        whenever(chain.request()).thenReturn(Request.Builder().url("https://other.example/api/auth/login").build())
        try {
            client.interceptors.single().intercept(chain)
            throw AssertionError("Expected origin rejection")
        } catch (_: IOException) {
            verify(chain, never()).proceed(any())
        }
    }

    @Test
    fun `old unauthorized response cannot invalidate a new session`() {
        val client = NetworkModule.provideOkHttpClient(tokenProvider, config)
        val request = Request.Builder().url("https://backend.example/api/chat/completions")
            .header("Authorization", "Bearer old-session").build()
        val response = Response.Builder().request(request).protocol(Protocol.HTTP_1_1)
            .code(401).message("Unauthorized").build()
        client.authenticator.authenticate(null, response)
        assertEquals("new-session", tokenProvider.token)
    }

    @Test
    fun `bound old request is rejected instead of switching credentials`() {
        val client = NetworkModule.provideOkHttpClient(tokenProvider, config)
        val chain: Interceptor.Chain = mock()
        whenever(chain.request()).thenReturn(Request.Builder().url("https://backend.example/api/chat/completions")
            .header("Authorization", "Bearer old-session").build())
        try {
            client.interceptors.first().intercept(chain)
            throw AssertionError("Expected identity rejection")
        } catch (_: IOException) {
            verify(chain, never()).proceed(any())
        }
    }

    @Test
    fun `auth and stream clients have finite budgets and reject redirects`() {
        val auth = NetworkModule.provideAuthOkHttpClient(config)
        val stream = NetworkModule.provideSseOkHttpClient(NetworkModule.provideOkHttpClient(tokenProvider, config))
        for (client in listOf(auth, stream)) {
            assertFalse(client.followRedirects)
            assertFalse(client.followSslRedirects)
            assertTrue(client.connectTimeoutMillis > 0)
            assertTrue(client.readTimeoutMillis > 0)
            assertTrue(client.callTimeoutMillis > client.readTimeoutMillis)
        }
    }
}
