package com.example.persona.data.repository

import com.example.persona.core.ai.Backend
import com.example.persona.core.ai.ChatMessage
import com.example.persona.core.ai.EngineState
import com.example.persona.core.ai.GenerationSession
import com.example.persona.core.ai.InstalledModel
import com.example.persona.core.ai.LocalAiEngine
import com.example.persona.core.ai.LocalModelManager
import com.example.persona.core.ai.ModelFamilies
import com.example.persona.core.ai.PromptFormats
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.mockito.kotlin.atLeastOnce
import org.mockito.kotlin.any
import org.mockito.kotlin.eq
import org.mockito.kotlin.mock
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever

class HybridAiRepositoryTest {

    private val cloudRepository: CloudChatRepository = mock()
    private val localEngine: LocalAiEngine = mock()
    private val localModelManager: LocalModelManager = mock()
    private val engineState = MutableStateFlow<EngineState>(EngineState.Ready)
    private val modelState = MutableStateFlow<InstalledModel?>(sampleModel())
    private lateinit var repository: HybridAiRepository

    @Before
    fun setUp() {
        whenever(localEngine.state).thenReturn(engineState)
        whenever(localModelManager.currentModel).thenReturn(modelState)
        repository = HybridAiRepository(cloudRepository, localEngine, localModelManager)
    }

    @Test
    fun `cloud mode delegates to CloudChatRepository`() = runTest {
        whenever(cloudRepository.streamResponse(any(), any(), any())).thenReturn(flowOf("Hello", " World"))

        val tokens = repository.streamResponse(
            mode = HybridAiRepository.Mode.CLOUD,
            session = GenerationSession(),
            systemPrompt = "system",
            userMessage = "user"
        ).toList()

        assertEquals(listOf("Hello", " World"), tokens)
        assertEquals(HybridAiRepository.Mode.CLOUD, repository.activeMode.value)
        verify(cloudRepository).streamResponse("system", "user", emptyList())
    }

    @Test
    fun `ready loaded local engine is selected when local mode is enabled`() = runTest {
        whenever(localEngine.initialize(sampleModel())).thenReturn(true)
        repository.selectModeForGeneration(forceCloud = false)

        assertEquals(HybridAiRepository.Mode.LOCAL, repository.selectMode(false, true))
    }

    @Test
    fun `cloud is selected when local engine is not ready`() {
        engineState.value = EngineState.Idle

        assertEquals(HybridAiRepository.Mode.CLOUD, repository.selectMode(false, true))
    }

    @Test
    fun `cloud is selected when no local model is selected`() {
        modelState.value = null

        assertEquals(HybridAiRepository.Mode.CLOUD, repository.selectMode(false, true))
    }

    @Test
    fun `force cloud bypasses ready local engine`() {
        assertEquals(HybridAiRepository.Mode.CLOUD, repository.selectMode(true, true))
    }

    @Test
    fun `generation mode initializes selected local model before choosing local`() = runTest {
        engineState.value = EngineState.Idle
        whenever(localEngine.initialize(sampleModel())).thenReturn(true)

        val mode = repository.selectModeForGeneration(forceCloud = false)

        assertEquals(HybridAiRepository.Mode.LOCAL, mode)
        assertEquals(HybridAiRepository.Mode.LOCAL, repository.activeMode.value)
        verify(localEngine).initialize(sampleModel())
    }

    @Test
    fun `generation mode falls back to cloud when selected local model fails to initialize`() = runTest {
        engineState.value = EngineState.Idle
        whenever(localEngine.initialize(sampleModel())).thenReturn(false)

        val mode = repository.selectModeForGeneration(forceCloud = false)

        assertEquals(HybridAiRepository.Mode.CLOUD, mode)
        assertEquals(HybridAiRepository.Mode.CLOUD, repository.activeMode.value)
        verify(localEngine).initialize(sampleModel())
    }

    @Test
    fun `generation mode falls back to cloud when selected local model initialization throws`() = runTest {
        engineState.value = EngineState.Idle
        whenever(localEngine.initialize(sampleModel())).thenThrow(IllegalStateException("bad model"))

        val mode = repository.selectModeForGeneration(forceCloud = false)

        assertEquals(HybridAiRepository.Mode.CLOUD, mode)
        assertEquals(HybridAiRepository.Mode.CLOUD, repository.activeMode.value)
        verify(localEngine).initialize(sampleModel())
    }

    @Test
    fun `generation mode force cloud bypasses local initialization`() = runTest {
        val mode = repository.selectModeForGeneration(forceCloud = true)

        assertEquals(HybridAiRepository.Mode.CLOUD, mode)
        assertEquals(HybridAiRepository.Mode.CLOUD, repository.activeMode.value)
        verify(localEngine, org.mockito.kotlin.never()).initialize(any())
    }

    @Test
    fun `stop generation delegates the same session to local engine`() {
        val session = GenerationSession()

        repository.stopGeneration(session)

        verify(localEngine).stopGeneration(session)
    }

    @Test
    fun `local mode streams from local engine`() = runTest {
        whenever(localEngine.streamResponse(any(), any(), any(), any())).thenReturn(flowOf("Local", " reply"))
        val history = listOf(ChatMessage("assistant", "older reply"))

        val tokens = repository.streamResponse(
            mode = HybridAiRepository.Mode.LOCAL,
            session = GenerationSession(),
            systemPrompt = "system",
            userMessage = "user",
            history = history
        ).toList()

        assertEquals(listOf("Local", " reply"), tokens)
        assertEquals(HybridAiRepository.Mode.LOCAL, repository.activeMode.value)
        verify(localEngine).streamResponse(
            any(),
            eq("user"),
            eq(listOf(ChatMessage("system", "system")) + history),
            any()
        )
    }

    @Test
    fun `local mode falls back to cloud when local fails before emitting`() = runTest {
        whenever(localEngine.streamResponse(any(), any(), any(), any())).thenReturn(flow { throw IllegalStateException("no model") })
        whenever(cloudRepository.streamResponse(any(), any(), any())).thenReturn(flowOf("cloud fallback"))

        val tokens = repository.streamResponse(
            mode = HybridAiRepository.Mode.LOCAL,
            session = GenerationSession(),
            systemPrompt = "system",
            userMessage = "user"
        ).toList()

        assertEquals(listOf("cloud fallback"), tokens)
        assertEquals(HybridAiRepository.Mode.CLOUD, repository.activeMode.value)
        verify(cloudRepository).streamResponse("system", "user", emptyList())
    }

    @Test
    fun `local mode announces fallback to cloud when local fails after emitting`() = runTest {
        whenever(localEngine.streamResponse(any(), any(), any(), any())).thenReturn(flow {
            emit("partial local")
            throw IllegalStateException("native stopped")
        })
        whenever(cloudRepository.streamResponse(any(), any(), any())).thenReturn(flowOf("cloud fallback"))

        val tokens = repository.streamResponse(
            mode = HybridAiRepository.Mode.LOCAL,
            session = GenerationSession(),
            systemPrompt = "system",
            userMessage = "user"
        ).toList()

        assertEquals(
            listOf("partial local", "\n\n[本地 AI 生成中断，已切换到云端继续。]\n", "cloud fallback"),
            tokens
        )
        assertEquals(HybridAiRepository.Mode.CLOUD, repository.activeMode.value)
        verify(cloudRepository).streamResponse("system", "user", emptyList())
    }

    @Test
    fun `local cancellation is propagated without cloud fallback`() = runTest {
        whenever(localEngine.streamResponse(any(), any(), any(), any())).thenReturn(flow {
            throw CancellationException("user stopped generation")
        })

        val thrown = runCatching {
            repository.streamResponse(
                mode = HybridAiRepository.Mode.LOCAL,
                session = GenerationSession(),
                systemPrompt = "system",
                userMessage = "user"
            ).toList()
        }.exceptionOrNull()

        assertEquals(CancellationException::class, thrown?.javaClass?.kotlin)
        assertEquals(HybridAiRepository.Mode.LOCAL, repository.activeMode.value)
        verify(cloudRepository, org.mockito.kotlin.never()).streamResponse(any(), any(), any())
    }

    @Test
    fun `initializeLocalModel loads the selected installed model`() = runTest {
        whenever(localModelManager.refreshInstalledModels()).thenReturn(listOf(sampleModel()))
        whenever(localEngine.initialize(sampleModel())).thenReturn(true)

        val initialized = repository.initializeLocalModel()

        assertEquals(true, initialized)
        assertEquals(HybridAiRepository.Mode.LOCAL, repository.activeMode.value)
        verify(localEngine).initialize(sampleModel())
    }

    @Test
    fun `initializeLocalModel keeps cloud mode when local initialization fails`() = runTest {
        whenever(localModelManager.refreshInstalledModels()).thenReturn(listOf(sampleModel()))
        whenever(localEngine.initialize(sampleModel())).thenReturn(false)

        val initialized = repository.initializeLocalModel()

        assertEquals(false, initialized)
        assertEquals(HybridAiRepository.Mode.CLOUD, repository.activeMode.value)
        verify(localEngine).initialize(sampleModel())
    }

    @Test
    fun `initializeLocalModel releases local engine when no model is selected`() = runTest {
        modelState.value = null
        whenever(localModelManager.refreshInstalledModels()).thenReturn(emptyList())

        val initialized = repository.initializeLocalModel()

        assertEquals(false, initialized)
        assertEquals(HybridAiRepository.Mode.CLOUD, repository.activeMode.value)
        verify(localEngine, atLeastOnce()).release()
    }

    @Test
    fun `current model cleared releases loaded engine and switches to cloud`() = runTest {
        whenever(localEngine.initialize(sampleModel())).thenReturn(true)
        repository.selectModeForGeneration(forceCloud = false)

        modelState.value = null

        awaitAssertion {
            verify(localEngine).release()
            assertEquals(HybridAiRepository.Mode.CLOUD, repository.activeMode.value)
        }
    }

    @Test
    fun `current model change releases old engine and next generation loads new model`() = runTest {
        val oldModel = sampleModel()
        val newModel = sampleModel(
            id = "qwen2.5-1.5b-instruct-mnn",
            modelDir = "/models/qwen2.5-1.5b-instruct-mnn"
        )
        whenever(localEngine.initialize(oldModel)).thenReturn(true)
        whenever(localEngine.initialize(newModel)).thenReturn(true)
        repository.selectModeForGeneration(forceCloud = false)

        modelState.value = newModel
        awaitAssertion {
            verify(localEngine).release()
            assertEquals(HybridAiRepository.Mode.CLOUD, repository.activeMode.value)
        }

        val mode = repository.selectModeForGeneration(forceCloud = false)

        assertEquals(HybridAiRepository.Mode.LOCAL, mode)
        verify(localEngine).initialize(newModel)
    }

    @Test
    fun `current model cleared during local generation stops generation and releases engine`() = runTest {
        val session = GenerationSession()
        whenever(localEngine.streamResponse(any(), any(), any(), any())).thenReturn(flow {
            emit("partial")
            awaitCancellation()
        })

        val tokens = mutableListOf<String>()
        val job = backgroundScope.launch {
            repository.streamResponse(
                mode = HybridAiRepository.Mode.LOCAL,
                session = session,
                systemPrompt = "system",
                userMessage = "user"
            ).toList(tokens)
        }

        awaitAssertion {
            assertEquals(listOf("partial"), tokens)
        }
        modelState.value = null

        awaitAssertion {
            verify(localEngine).stopGeneration(session)
            verify(localEngine).release()
            assertEquals(HybridAiRepository.Mode.CLOUD, repository.activeMode.value)
        }
        job.cancel()
    }

    private suspend fun awaitAssertion(assertion: () -> Unit) {
        withTimeout(1_000) {
            while (true) {
                try {
                    assertion()
                    return@withTimeout
                } catch (error: AssertionError) {
                    delay(10)
                }
            }
        }
    }

    private fun sampleModel(
        id: String = "qwen2.5-0.5b-instruct-mnn",
        modelDir: String = "/models/qwen2.5-0.5b-instruct-mnn"
    ) = InstalledModel(
        id = id,
        name = "Qwen2.5 Instruct",
        version = "local",
        modelDir = modelDir,
        backend = Backend.MNN,
        family = ModelFamilies.QWEN2_5,
        promptFormat = PromptFormats.QWEN_CHATML_TEXT,
        contextWindow = 4096
    )
}
