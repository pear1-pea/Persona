package com.example.persona.data.repository

import com.example.persona.data.remote.VolcApi
import kotlinx.coroutines.flow.single
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever

class CloudChatRepositoryTest {

    private val api: VolcApi = mock()
    private lateinit var repo: CloudChatRepository

    @Before
    fun setUp() {
        repo = CloudChatRepository(api)
    }

    @Test
    fun `streamResponse emits error message when execute throws`() = runTest {
        val call: retrofit2.Call<okhttp3.ResponseBody> = mock()
        whenever(api.streamChat(any())).thenReturn(call)
        whenever(call.execute()).thenThrow(RuntimeException("Network error"))

        val result = repo.streamResponse("system", "user").single()
        assertEquals("Error: AI 脑子短路了...", result)
    }
}
