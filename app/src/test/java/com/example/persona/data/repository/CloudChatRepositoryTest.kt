package com.example.persona.data.repository

import com.example.persona.data.remote.DeepSeekApi
import com.example.persona.data.remote.DeepSeekConfig
import kotlinx.coroutines.flow.single
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.mock
import org.mockito.kotlin.never
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever

class CloudChatRepositoryTest {

    private val api: DeepSeekApi = mock()
    private lateinit var repo: CloudChatRepository

    @Before
    fun setUp() {
        repo = CloudChatRepository(api, DeepSeekConfig("sk-test", "deepseek-v4-flash"))
    }

    @Test
    fun `streamResponse emits configuration message when api key is blank`() = runTest {
        val unconfiguredRepo = CloudChatRepository(api, DeepSeekConfig("", "deepseek-v4-flash"))

        val result = unconfiguredRepo.streamResponse("system", "user").single()

        assertEquals("Error: DeepSeek 未配置 API Key，请在 local.properties 填写 DEEPSEEK_API_KEY。", result)
        verify(api, never()).streamChat(any())
    }

    @Test
    fun `streamResponse emits error message when execute throws`() = runTest {
        val call: retrofit2.Call<okhttp3.ResponseBody> = mock()
        whenever(api.streamChat(any())).thenReturn(call)
        whenever(call.execute()).thenThrow(RuntimeException("Network error"))

        val result = repo.streamResponse("system", "user").single()
        assertEquals("Error: DeepSeek 云端连接失败，请稍后再试。", result)
    }
}
