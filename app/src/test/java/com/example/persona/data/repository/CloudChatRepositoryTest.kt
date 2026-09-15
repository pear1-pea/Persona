package com.example.persona.data.repository

import com.example.persona.data.remote.CloudGenerationException
import com.example.persona.data.remote.DeepSeekApi
import com.example.persona.data.remote.DeepSeekConfig
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.runCurrent
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

    private val api: DeepSeekApi = mock()
    private lateinit var repo: CloudChatRepository

    @Before
    fun setUp() {
        repo = CloudChatRepository(api, DeepSeekConfig("sk-test", "deepseek-v4-flash"))
    }

    @Test
    fun `streamResponse fails with NotConfigured when api key is blank`() = runTest {
        val unconfiguredRepo = CloudChatRepository(api, DeepSeekConfig("", "deepseek-v4-flash"))

        val thrown = runCatching {
            unconfiguredRepo.streamResponse("system", "user").toList()
        }.exceptionOrNull()

        assertTrue(thrown is CloudGenerationException.NotConfigured)
        verify(api, never()).streamChat(any())
    }

    @Test
    fun `streamResponse fails with Network when execute throws`() = runTest {
        val call: retrofit2.Call<okhttp3.ResponseBody> = mock()
        val cause = RuntimeException("Network error")
        whenever(api.streamChat(any())).thenReturn(call)
        whenever(call.enqueue(any())).thenAnswer {
            it.getArgument<Callback<okhttp3.ResponseBody>>(0).onFailure(call, cause)
            Unit
        }

        val thrown = runCatching {
            repo.streamResponse("system", "user").toList()
        }.exceptionOrNull()

        assertTrue(thrown is CloudGenerationException.Network)
        // Coroutine stacktrace recovery copies the exception, so the original
        // cause can sit deeper than one level down.
        assertTrue(generateSequence(thrown) { it.cause }.any { it === cause })
    }

    @Test
    fun `streamResponse propagates cancellation when execute is cancelled`() = runTest {
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
    fun `streamResponse emits SSE content and stops at done marker`() = runTest {
        val call: retrofit2.Call<okhttp3.ResponseBody> = mock()
        val body = """
            data: {"choices":[{"delta":{"content":"你好"}}]}

            data: [DONE]
        """.trimIndent().toResponseBody("text/event-stream".toMediaTypeOrNull())
        whenever(api.streamChat(any())).thenReturn(call)
        whenever(call.enqueue(any())).thenAnswer {
            it.getArgument<Callback<okhttp3.ResponseBody>>(0)
                .onResponse(call, Response.success(body))
            Unit
        }

        assertEquals(
            listOf("你好"),
            repo.streamResponse("system", "user").toList()
        )
    }

    @Test
    fun `streamResponse fails with HttpError when response is unsuccessful`() = runTest {
        val call: retrofit2.Call<okhttp3.ResponseBody> = mock()
        val body = "nope".toResponseBody("text/plain".toMediaTypeOrNull())
        whenever(api.streamChat(any())).thenReturn(call)
        whenever(call.enqueue(any())).thenAnswer {
            it.getArgument<Callback<okhttp3.ResponseBody>>(0)
                .onResponse(call, Response.error(402, body))
            Unit
        }

        val thrown = runCatching {
            repo.streamResponse("system", "user").toList()
        }.exceptionOrNull()

        assertTrue(thrown is CloudGenerationException.HttpError)
        assertEquals(402, (thrown as CloudGenerationException.HttpError).code)
    }
}
