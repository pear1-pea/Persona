package com.example.persona.data.repository

import com.example.persona.core.ai.Backend
import com.example.persona.core.ai.GenerationErrorType
import com.example.persona.core.ai.GenerationEvent
import com.example.persona.core.auth.AuthTokenProvider
import com.example.persona.data.remote.BackendConfig
import com.example.persona.data.remote.CloudChatApi
import com.example.persona.data.remote.CloudGenerationException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.mock
import org.mockito.kotlin.never
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
import retrofit2.Callback
import retrofit2.Response
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

@kotlinx.coroutines.ExperimentalCoroutinesApi
class CloudChatRepositoryTest {

    private val api: CloudChatApi = mock()
    private val authTokenProvider: AuthTokenProvider = mock()
    private lateinit var repo: CloudChatRepository

    @Before
    fun setUp() {
        whenever(authTokenProvider.currentToken()).thenReturn("test-token")
        repo = CloudChatRepository(
            api,
            BackendConfig("https://example.test", "deepseek-v4-flash"),
            authTokenProvider
        )
    }

    @Test
    fun `streamResponse emits NotConfigured failure when backend is not configured`() = runTest {
        val unconfiguredRepo = CloudChatRepository(
            api,
            BackendConfig("", "", isConfigured = false),
            authTokenProvider
        )

        val events = unconfiguredRepo.streamResponse("system", "user").toList()

        val failure = events.single() as GenerationEvent.Failed
        assertEquals(Backend.CLOUD, failure.backend)
        assertEquals(GenerationErrorType.NOT_CONFIGURED, failure.error.type)
        verify(api, never()).streamChat(any())
    }

    @Test
    fun `streamResponse emits Network failure when request fails`() = runTest {
        val call: retrofit2.Call<okhttp3.ResponseBody> = mock()
        val cause = RuntimeException("Network error")
        whenever(api.streamChat(any())).thenReturn(call)
        whenever(call.enqueue(any())).thenAnswer {
            it.getArgument<Callback<okhttp3.ResponseBody>>(0).onFailure(call, cause)
            Unit
        }

        val events = repo.streamResponse("system", "user").toList()

        val failure = events.single() as GenerationEvent.Failed
        assertEquals(GenerationErrorType.NETWORK, failure.error.type)
        assertTrue(generateSequence(failure.error.cause) { it?.cause }.any { it === cause })
    }

    @Test
    fun `streamResponse propagates cancellation when request is cancelled`() = runTest {
        val call: retrofit2.Call<okhttp3.ResponseBody> = mock()
        whenever(api.streamChat(any())).thenReturn(call)
        whenever(call.enqueue(any())).thenAnswer {
            it.getArgument<Callback<okhttp3.ResponseBody>>(0)
                .onFailure(call, CancellationException("user stopped"))
            Unit
        }

        val thrown = runCatching {
            repo.streamResponse("system", "user").toList()
        }.exceptionOrNull()

        assertTrue(thrown is CancellationException)
        assertTrue(thrown !is CloudGenerationException.Network)
    }

    @Test
    fun `streamResponse cancels retrofit call when collector is cancelled`() = runTest {
        val call: retrofit2.Call<okhttp3.ResponseBody> = mock()
        val executeStarted = CountDownLatch(1)
        whenever(api.streamChat(any())).thenReturn(call)
        whenever(call.enqueue(any())).thenAnswer {
            executeStarted.countDown()
            Unit
        }

        val job = launch(start = CoroutineStart.LAZY) {
            repo.streamResponse("system", "user").collect()
        }
        job.start()
        runCurrent()
        assertTrue(executeStarted.await(5, TimeUnit.SECONDS))

        job.cancel()
        verify(call, org.mockito.kotlin.timeout(1_000)).cancel()
        job.join()
    }

    @Test
    fun `streamResponse emits SSE content and completion`() = runTest {
        val call: retrofit2.Call<okhttp3.ResponseBody> = mock()
        val body = """
            data: {"choices":[{"delta":{"content":"\u4f60\u597d"}}]}

            data: [DONE]
        """.trimIndent().toResponseBody("text/event-stream".toMediaTypeOrNull())
        whenever(api.streamChat(any())).thenReturn(call)
        whenever(call.enqueue(any())).thenAnswer {
            it.getArgument<Callback<okhttp3.ResponseBody>>(0)
                .onResponse(call, Response.success(body))
            Unit
        }

        val events = repo.streamResponse("system", "user").toList()

        assertEquals(GenerationEvent.Delta("\u4f60\u597d"), events.first())
        assertTrue(events.last() is GenerationEvent.Completed)
    }

    @Test
    fun `streamResponse emits HttpError failure when response is unsuccessful`() = runTest {
        val call: retrofit2.Call<okhttp3.ResponseBody> = mock()
        val body = "nope".toResponseBody("text/plain".toMediaTypeOrNull())
        whenever(api.streamChat(any())).thenReturn(call)
        whenever(call.enqueue(any())).thenAnswer {
            it.getArgument<Callback<okhttp3.ResponseBody>>(0)
                .onResponse(call, Response.error(402, body))
            Unit
        }

        val events = repo.streamResponse("system", "user").toList()

        val failure = events.single() as GenerationEvent.Failed
        assertEquals(GenerationErrorType.HTTP, failure.error.type)
        val cause = failure.error.cause as CloudGenerationException.HttpError
        assertEquals(402, cause.code)
    }
}
