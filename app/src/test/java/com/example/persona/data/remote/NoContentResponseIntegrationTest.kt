package com.example.persona.data.remote

import com.example.persona.core.auth.AuthManager
import com.example.persona.core.auth.SessionTokenStore
import com.example.persona.core.auth.StoredSession
import com.example.persona.data.local.dao.PersonaDao
import com.example.persona.data.repository.RemotePersonaRepository
import kotlinx.coroutines.test.runTest
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.*
import org.junit.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.mock
import org.mockito.kotlin.never
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
import retrofit2.HttpException
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory

class NoContentResponseIntegrationTest {
    private fun retrofit(status: Int): Retrofit {
        val client = OkHttpClient.Builder().addInterceptor { chain ->
            okhttp3.Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1)
                .code(status).message("Synthetic transport")
                .body("".toResponseBody()).build()
        }.build()
        return Retrofit.Builder().baseUrl("https://no-content.example.invalid/")
            .client(client).addConverterFactory(GsonConverterFactory.create()).build()
    }

    private fun manager(retrofit: Retrofit): Triple<AuthManager, SessionTokenStore, PersonaDao> {
        val store: SessionTokenStore = mock()
        whenever(store.read()).thenReturn(StoredSession("synthetic-token", "synthetic-owner", "fixture@example.invalid"))
        val dao: PersonaDao = mock()
        return Triple(AuthManager(retrofit.create(AuthApi::class.java), store, dao), store, dao)
    }

    @Test
    fun `real Retrofit 204 account deletion completes local cleanup`() = runTest {
        val (auth, store, dao) = manager(retrofit(204))
        auth.deleteAccount("synthetic-password")
        assertNull(auth.currentToken())
        verify(store).markAccountDeleted("synthetic-owner")
        verify(dao).deleteAccountData("synthetic-owner")
        verify(store).completeAccountDeletion("synthetic-owner")
    }

    @Test
    fun `real Retrofit 204 session revocation succeeds without a body`() = runTest {
        val (auth, _, _) = manager(retrofit(204))
        auth.revokeOtherSessions()
        assertEquals("synthetic-token", auth.currentToken())
    }

    @Test
    fun `real Retrofit 204 logout succeeds without a body`() = runTest {
        val response = retrofit(204).create(AuthApi::class.java).logout("Bearer synthetic-token")
        response.requireSuccessfulNoContent()
    }

    @Test
    fun `real Retrofit 204 persona deletion succeeds without a body`() = runTest {
        val repository = RemotePersonaRepository(retrofit(204).create(PersonaBackendApi::class.java))
        repository.deletePersona("synthetic-persona")
    }

    @Test
    fun `real Retrofit rejected deletion retains the local account and data`() = runTest {
        val (auth, store, dao) = manager(retrofit(401))
        val error = runCatching { auth.deleteAccount("synthetic-password") }.exceptionOrNull()
        assertTrue(error is HttpException && error.code() == 401)
        assertEquals("synthetic-token", auth.currentToken())
        verify(store, never()).markAccountDeleted(any())
        verify(dao, never()).deleteAccountData(any())
    }

    @Test
    fun `real Retrofit rejected persona deletion remains an HTTP failure`() = runTest {
        val repository = RemotePersonaRepository(retrofit(404).create(PersonaBackendApi::class.java))
        val error = runCatching { repository.deletePersona("synthetic-persona") }.exceptionOrNull()
        assertTrue(error is HttpException && error.code() == 404)
    }
}
