package com.example.persona.data.repository

import com.example.persona.core.ai.EdgeAiEngine
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.mock
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever

class HybridAiRepositoryTest {

    private val cloudRepository: CloudChatRepository = mock()
    private val edgeEngine: EdgeAiEngine = mock()
    private lateinit var repo: HybridAiRepository

    @Before
    fun setUp() {
        repo = HybridAiRepository(cloudRepository, edgeEngine)
    }

    @Test
    fun `streamResponse with CLOUD delegates to CloudChatRepository`() = runTest {
        val expectedFlow: Flow<String> = flowOf("Hello", " World")
        whenever(cloudRepository.streamResponse(any(), any())).thenReturn(expectedFlow)

        val result = repo.streamResponse(HybridAiRepository.Mode.CLOUD, "sys", "user")

        val tokens = result.toList()
        assertEquals(listOf("Hello", " World"), tokens)
        verify(cloudRepository).streamResponse("sys", "user")
    }

    @Test
    fun `streamResponse with EDGE delegates to EdgeAiEngine`() = runTest {
        val expectedFlow: Flow<String> = flowOf("Edge", "Response")
        whenever(edgeEngine.generateResponse(any(), any())).thenReturn(expectedFlow)

        val result = repo.streamResponse(HybridAiRepository.Mode.EDGE, "sys", "user")

        val tokens = result.toList()
        assertEquals(listOf("Edge", "Response"), tokens)
        verify(edgeEngine).generateResponse("sys", "user")
    }

    @Test
    fun `initEdgeModel calls edgeEngine initModel`() = runTest {
        repo.initEdgeModel()
        verify(edgeEngine).initModel()
    }

    @Test
    fun `evaluateComplexity delegates to edgeEngine`() = runTest {
        whenever(edgeEngine.routeComplexity("test")).thenReturn(0.8f)

        val score = repo.evaluateComplexity("test")

        assertEquals(0.8f, score)
        verify(edgeEngine).routeComplexity("test")
    }
}
