package com.example.persona.data.repository

import com.example.persona.core.ai.Backend
import com.example.persona.core.ai.GenerationErrorType
import com.example.persona.core.ai.GenerationEvent
import com.example.persona.core.ai.GenerationParams
import com.example.persona.data.remote.dto.ChatRequest
import org.mockito.kotlin.argumentCaptor
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.buffer
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
import org.mockito.kotlin.eq
import retrofit2.Callback
import retrofit2.Response
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

@kotlinx.coroutines.ExperimentalCoroutinesApi
class CloudChatRepositoryTest {

    private val api: CloudChatApi = mock()
    private val authTokenProvider: AuthTokenProvider = mock()
    private lateinit var repo: CloudChatRepository

    @Test
    fun `slow collector receives terminal after a full delta buffer`() = runBlocking {
        for (done in listOf(false, true)) {
            val call: retrofit2.Call<okhttp3.ResponseBody> = mock()
            val data = "data: {\"choices\":[{\"delta\":{\"content\":\"a\"}}]}\n\n".repeat(65) +
                if (done) "data: [DONE]\n\n" else ""
            whenever(api.streamChat(any(), any())).thenReturn(call)
            whenever(call.enqueue(any())).thenAnswer {
                it.getArgument<Callback<okhttp3.ResponseBody>>(0).onResponse(call, Response.success(data.toResponseBody()))
                Unit
            }
            val events = mutableListOf<GenerationEvent>()
            withTimeout(5_000) {
                repo.streamResponse("rules", "question").collect { event ->
                    events += event
                    if (events.size == 1) delay(300)
                }
            }
            assertEquals(65, events.count { it is GenerationEvent.Delta })
            assertEquals(1, events.count { it is GenerationEvent.Completed || it is GenerationEvent.Failed })
            assertEquals(done, events.last() is GenerationEvent.Completed)
        }
    }

    @Test
    fun `unconfigured request delivers its failure with a rendezvous buffer`() = runBlocking {
        val unconfigured = CloudChatRepository(api, BackendConfig("", "", false), authTokenProvider)
        val events = withTimeout(5_000) { unconfigured.streamResponse("rules", "question").buffer(0).toList() }
        assertTrue(events.single() is GenerationEvent.Failed)
    }

    @Test
    fun `oversized system rules fail before sending a request`() = runTest {
        val events = repo.streamResponse("x".repeat(12_001), "question").toList()
        assertEquals(GenerationErrorType.INPUT_BUDGET_EXCEEDED, (events.single() as GenerationEvent.Failed).error.type)
        verify(api, never()).streamChat(any(), any())
    }

    @Test
    fun `sampling parameters are transmitted with a bounded output budget`() = runTest {
        val call: retrofit2.Call<okhttp3.ResponseBody> = mock()
        whenever(api.streamChat(any(), any())).thenReturn(call)
        whenever(call.enqueue(any())).thenAnswer {
            it.getArgument<Callback<okhttp3.ResponseBody>>(0).onFailure(call, RuntimeException("offline"))
            Unit
        }
        repo.streamResponseWithParams("rules", "question", emptyList(), GenerationParams(0.2f, 0.7f, 42)).toList()
        val request = argumentCaptor<ChatRequest>()
        verify(api).streamChat(request.capture(), any())
        assertEquals(0.2f, request.firstValue.temperature)
        assertEquals(0.7f, request.firstValue.topP)
        assertEquals(42, request.firstValue.maxTokens)
    }

    @Test
    fun `request with no generation progress times out and cancels transport`() = runBlocking {
        val call: retrofit2.Call<okhttp3.ResponseBody> = mock()
        whenever(api.streamChat(any(), any())).thenReturn(call)
        val timed = CloudChatRepository(api, BackendConfig("https://example.test", "model"), authTokenProvider, 50)
        val events = withTimeout(5_000) { timed.streamResponse("rules", "question").toList() }
        assertEquals(GenerationErrorType.NETWORK, (events.single() as GenerationEvent.Failed).error.type)
        verify(call, org.mockito.kotlin.atLeastOnce()).cancel()
    }

    @Test
    fun `malformed data cannot be turned into success by a done marker`() = runTest {
        val call: retrofit2.Call<okhttp3.ResponseBody> = mock()
        whenever(api.streamChat(any(), any())).thenReturn(call)
        whenever(call.enqueue(any())).thenAnswer {
            it.getArgument<Callback<okhttp3.ResponseBody>>(0).onResponse(call,
                Response.success("data: {broken\n\ndata: [DONE]\n\n".toResponseBody()))
            Unit
        }
        val events = repo.streamResponse("rules", "question").toList()
        assertTrue(events.single() is GenerationEvent.Failed)
    }

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
        verify(api, never()).streamChat(any(), any())
    }

    @Test
    fun `streamResponse emits Network failure when request fails`() = runTest {
        val call: retrofit2.Call<okhttp3.ResponseBody> = mock()
        val cause = RuntimeException("Network error")
        whenever(api.streamChat(any(), any())).thenReturn(call)
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
        whenever(api.streamChat(any(), any())).thenReturn(call)
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
        whenever(api.streamChat(any(), any())).thenReturn(call)
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
        whenever(api.streamChat(any(), any())).thenReturn(call)
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
        whenever(api.streamChat(any(), any())).thenReturn(call)
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

    @Test
    fun `streamResponse passes captured bearer to request`() = runTest {
        val call: retrofit2.Call<okhttp3.ResponseBody> = mock()
        whenever(api.streamChat(any(), any())).thenReturn(call)
        whenever(call.enqueue(any())).thenAnswer {
            it.getArgument<Callback<okhttp3.ResponseBody>>(0).onFailure(call, RuntimeException("offline"))
            Unit
        }
        repo.streamResponse("system", "user").toList()
        verify(api).streamChat(any(), eq("Bearer test-token"))
    }

    @Test
    fun `flow created by old account cannot enqueue as next account`() = runTest {
        val pending = repo.streamResponse("system", "user")
        whenever(authTokenProvider.currentToken()).thenReturn("next-token")
        val error = runCatching { pending.toList() }.exceptionOrNull()
        assertTrue(error is CancellationException)
        verify(api, never()).streamChat(any(), any())
    }

    @Test
    fun `late unauthorized response only invalidates request token`() = runTest {
        val call: retrofit2.Call<okhttp3.ResponseBody> = mock()
        whenever(api.streamChat(any(), any())).thenReturn(call)
        whenever(call.enqueue(any())).thenAnswer {
            whenever(authTokenProvider.currentToken()).thenReturn("next-token")
            it.getArgument<Callback<okhttp3.ResponseBody>>(0).onResponse(
                call, Response.error(401, "expired".toResponseBody())
            )
            Unit
        }
        val error = runCatching { repo.streamResponse("system", "user").toList() }.exceptionOrNull()
        assertTrue(error is CancellationException)
        verify(authTokenProvider).invalidateSession("test-token")
        verify(authTokenProvider, never()).invalidateSession()
        verify(authTokenProvider, never()).invalidateSession("next-token")
    }
}
