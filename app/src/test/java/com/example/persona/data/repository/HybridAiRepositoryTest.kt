package com.example.persona.data.repository

import com.example.persona.core.ai.Backend
import com.example.persona.core.ai.ChatMessage
import com.example.persona.core.ai.DeviceProfile
import com.example.persona.core.ai.EngineState
import com.example.persona.core.ai.GenerationError
import com.example.persona.core.ai.GenerationErrorType
import com.example.persona.core.ai.GenerationEvent
import com.example.persona.core.ai.GenerationParams
import com.example.persona.core.ai.GenerationMetrics
import com.example.persona.core.ai.GenerationSession
import com.example.persona.core.ai.HybridRouter
import com.example.persona.core.ai.InstalledModel
import com.example.persona.core.ai.LocalAiEngine
import com.example.persona.core.ai.LocalModelFailure
import com.example.persona.core.ai.LocalModelFailureType
import com.example.persona.core.ai.LocalModelManager
import com.example.persona.core.ai.ModelAdmission
import com.example.persona.core.ai.ModelAdmissionReport
import com.example.persona.core.ai.ModelFamilies
import com.example.persona.core.ai.NetworkState
import com.example.persona.core.ai.PromptFormats
import com.example.persona.core.ai.RoutingContextProvider
import com.example.persona.core.ai.ThermalStatus
import com.example.persona.core.ai.PrivacyLevel
import com.example.persona.core.ai.TaskComplexity
import com.example.persona.core.ai.Route
import com.example.persona.core.ai.RoutingDecision
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withContext
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.After
import org.junit.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.atLeastOnce
import org.mockito.kotlin.eq
import org.mockito.kotlin.inOrder
import org.mockito.kotlin.mock
import org.mockito.kotlin.never
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever

class HybridAiRepositoryTest {

    private val cloudRepository: CloudChatRepository = mock()
    private val localEngine: LocalAiEngine = mock()
    private val localModelManager: LocalModelManager = mock()
    private val routingContextProvider: RoutingContextProvider = mock()
    private val router = HybridRouter()
    private val engineState = MutableStateFlow<EngineState>(EngineState.Ready)
    private val modelState = MutableStateFlow<InstalledModel?>(sampleModel())
    private val admissionState = MutableStateFlow<ModelAdmissionReport?>(
        ModelAdmissionReport(ModelAdmission.SUPPORTED)
    )
    private lateinit var repository: HybridAiRepository
    private val observerScope = TestScope()

    @Before
    fun setUp() {
        whenever(localEngine.state).thenReturn(engineState)
        runBlocking {
            whenever(localEngine.initialize(any())).thenReturn(true)
            whenever(localEngine.smokeTest(any())).thenReturn(true)
            whenever(localModelManager.clearCurrentModel()).thenAnswer {
                modelState.value = null
                Unit
            }
        }
        whenever(localModelManager.currentModel).thenReturn(modelState)
        whenever(localModelManager.currentAdmission).thenReturn(admissionState)
        whenever(localModelManager.deviceCapability()).thenReturn(deviceProfile())
        whenever(localModelManager.recentFailureCount(any())).thenReturn(0)
        whenever(localModelManager.recentFailure(any())).thenReturn(null)
        whenever(routingContextProvider.networkState()).thenReturn(NetworkState.UNMETERED)
        repository = newRepository(StandardTestDispatcher(observerScope.testScheduler))
    }

    private fun newRepository(dispatcher: CoroutineDispatcher) = HybridAiRepository(
            cloudRepository,
            localEngine,
            localModelManager,
            router,
            routingContextProvider,
            observerScope.backgroundScope,
            dispatcher
        )

    @After
    fun tearDown() {
        observerScope.cancel()
    }

    private fun runTest(block: suspend TestScope.() -> Unit) = observerScope.runTest(testBody = block)

    @Test
    fun `a ready model is not charged its weights twice when memory falls after loading`() = runTest {
        modelState.value = sampleModel().copy(totalSizeBytes = 700_000_000L)
        whenever(localModelManager.deviceCapability()).thenReturn(deviceProfile().copy(availableMemBytes = 1_100_000_000L))
        assertTrue(repository.initializeLocalModel())
        whenever(localModelManager.deviceCapability()).thenReturn(deviceProfile().copy(availableMemBytes = 500_000_000L))
        whenever(localEngine.streamResponse(any(), any(), any(), any())).thenReturn(flowOf(delta("local"), completed()))
        val decision = repository.selectRouteForGeneration(false, false, PrivacyLevel.NORMAL, TaskComplexity.SIMPLE)
        assertTrue(decision.isLocalPreferred)
        val session = GenerationSession().apply { bindRoutingDecision(decision) }
        val events = repository.streamResponse(HybridAiRepository.Mode.LOCAL, session, "rules", "hello",
            route = decision.route).toList()
        assertEquals(listOf("local"), events.deltaTexts())
        verify(cloudRepository, never()).streamResponseWithParams(any(), any(), any(), any())
    }

    @Test
    fun `execution admission failure reaches cloud only when fallback remains authorized`() = runTest {
        assertTrue(repository.initializeLocalModel())
        whenever(localModelManager.deviceCapability()).thenReturn(deviceProfile().copy(availableMemBytes = 32_000_000L))
        whenever(cloudRepository.streamResponseWithParams(any(), any(), any(), any())).thenReturn(flowOf(delta("cloud"), completed()))
        val session = GenerationSession().apply {
            bindRoutingDecision(RoutingDecision(Route.LOCAL_THEN_CLOUD, emptyList(), cloudAllowed = true))
        }
        val events = repository.streamResponse(HybridAiRepository.Mode.LOCAL, session, "rules", "hello").toList()
        assertTrue((events.first() as GenerationEvent.Failed).fallbackPlanned)
        assertEquals(listOf("cloud"), events.deltaTexts())
        verify(localEngine, never()).streamResponse(any(), any(), any(), any())
    }

    @Test
    fun `execution admission failure cannot upload private input`() = runTest {
        assertTrue(repository.initializeLocalModel())
        whenever(localModelManager.deviceCapability()).thenReturn(deviceProfile().copy(availableMemBytes = 32_000_000L))
        val session = GenerationSession().apply { bindRoutingDecision(RoutingDecision(Route.LOCAL, emptyList())) }
        val events = repository.streamResponse(HybridAiRepository.Mode.LOCAL, session, "rules", "private", route = Route.LOCAL).toList()
        assertFalse((events.single() as GenerationEvent.Failed).fallbackPlanned)
        verify(cloudRepository, never()).streamResponseWithParams(any(), any(), any(), any())
    }

    @Test
    fun `unavailable private request never initializes or reaches cloud`() = runTest {
        modelState.value = null
        val decision = repository.selectRouteForGeneration(true, true, PrivacyLevel.PRIVATE, TaskComplexity.SIMPLE)
        assertFalse(decision.isExecutable)
        val session = GenerationSession().apply { bindRoutingDecision(decision) }
        val events = repository.streamResponse(HybridAiRepository.Mode.CLOUD, session, "rules", "private").toList()
        assertTrue(events.single() is GenerationEvent.Failed)
        verify(localEngine, never()).initialize(any())
        verify(cloudRepository, never()).streamResponseWithParams(any(), any(), any(), any())
    }

    @Test
    fun `local only policy cannot be replaced by an inconsistent fallback route`() = runTest {
        val session = GenerationSession().apply { bindRoutingDecision(RoutingDecision(Route.LOCAL, emptyList())) }
        whenever(localEngine.streamResponse(any(), any(), any(), any())).thenReturn(flowOf(
            GenerationEvent.Failed(Backend.MNN, GenerationError(GenerationErrorType.MODEL_RUNTIME, "failed"), "")
        ))
        val events = repository.streamResponse(HybridAiRepository.Mode.LOCAL, session, "rules", "private",
            route = Route.LOCAL_THEN_CLOUD).toList()
        assertTrue(events.last() is GenerationEvent.Failed)
        verify(cloudRepository, never()).streamResponseWithParams(any(), any(), any(), any())
    }

    @Test
    fun `changed metered network prevents automatic fallback`() = runTest {
        whenever(localEngine.streamResponse(any(), any(), any(), any())).thenReturn(flow {
            whenever(routingContextProvider.networkState()).thenReturn(NetworkState.METERED)
            emit(GenerationEvent.Failed(Backend.MNN, GenerationError(GenerationErrorType.MODEL_RUNTIME, "failed"), ""))
        })
        repository.streamResponse(HybridAiRepository.Mode.LOCAL, GenerationSession(), "rules", "question").toList()
        verify(cloudRepository, never()).streamResponseWithParams(any(), any(), any(), any())
    }

    @Test
    fun `cloud mode delegates to CloudChatRepository`() = runTest {
        whenever(cloudRepository.streamResponseWithParams(any(), any(), any(), any())).thenReturn(
            flowOf(delta("Hello"), delta(" World"), completed())
        )

        val events = repository.streamResponse(
            mode = HybridAiRepository.Mode.CLOUD,
            session = GenerationSession(),
            systemPrompt = "system",
            userMessage = "user"
        ).toList()

        assertEquals(listOf("Hello", " World"), events.deltaTexts())
        assertEquals(HybridAiRepository.Mode.CLOUD, repository.activeMode.value)
        verify(cloudRepository).streamResponseWithParams("system", "user", emptyList(), GenerationParams())
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
        verify(localEngine, never()).initialize(any())
    }

    @Test
    fun `stop generation delegates the same session to local engine`() {
        val session = GenerationSession()

        repository.stopGeneration(session)

        verify(localEngine).stopGeneration(session)
    }

    @Test
    fun `local mode streams from local engine`() = runTest {
        whenever(localEngine.streamResponse(any(), any(), any(), any())).thenReturn(
            flowOf(delta("Local"), delta(" reply"), completed())
        )
        val history = listOf(ChatMessage("assistant", "older reply"))

        val events = repository.streamResponse(
            mode = HybridAiRepository.Mode.LOCAL,
            session = GenerationSession(),
            systemPrompt = "system",
            userMessage = "user",
            history = history
        ).toList()

        assertEquals(listOf("Local", " reply"), events.deltaTexts())
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
        whenever(localEngine.streamResponse(any(), any(), any(), any())).thenReturn(
            flow { throw IllegalStateException("no model") }
        )
        whenever(cloudRepository.streamResponseWithParams(any(), any(), any(), any())).thenReturn(
            flowOf(delta("cloud fallback"), completed())
        )

        val events = repository.streamResponse(
            mode = HybridAiRepository.Mode.LOCAL,
            session = GenerationSession(),
            systemPrompt = "system",
            userMessage = "user"
        ).toList()

        assertEquals(Backend.MNN, (events.first() as GenerationEvent.Failed).backend)
        assertEquals(GenerationErrorType.MODEL_RUNTIME, (events.first() as GenerationEvent.Failed).error.type)
        assertEquals(listOf("cloud fallback"), events.deltaTexts())
        assertEquals(HybridAiRepository.Mode.CLOUD, repository.activeMode.value)
        verify(cloudRepository).streamResponseWithParams("system", "user", emptyList(), GenerationParams())
    }

    @Test
    fun `local mode emits typed failure before cloud fallback when local fails after emitting`() = runTest {
        whenever(localEngine.streamResponse(any(), any(), any(), any())).thenReturn(flow {
            emit(delta("partial local"))
            throw IllegalStateException("native stopped")
        })
        whenever(cloudRepository.streamResponseWithParams(any(), any(), any(), any())).thenReturn(
            flowOf(delta("cloud fallback"), completed())
        )

        val events = repository.streamResponse(
            mode = HybridAiRepository.Mode.LOCAL,
            session = GenerationSession(),
            systemPrompt = "system",
            userMessage = "user"
        ).toList()

        assertEquals(listOf("partial local", "cloud fallback"), events.deltaTexts())
        val localFailure = events[1] as GenerationEvent.Failed
        assertEquals(Backend.MNN, localFailure.backend)
        assertEquals("partial local", localFailure.partialText)
        assertEquals(HybridAiRepository.Mode.CLOUD, repository.activeMode.value)
    }

    @Test
    fun `cloud fallback failure preserves local output and emits cloud error`() = runTest {
        whenever(localEngine.streamResponse(any(), any(), any(), any())).thenReturn(flow {
            emit(delta("partial local"))
            throw IllegalStateException("native stopped")
        })
        whenever(cloudRepository.streamResponseWithParams(any(), any(), any(), any())).thenReturn(flow {
            emit(
                GenerationEvent.Failed(
                    backend = Backend.CLOUD,
                    error = GenerationError(GenerationErrorType.NETWORK, "offline"),
                    partialText = "partial local"
                )
            )
        })

        val events = repository.streamResponse(
            mode = HybridAiRepository.Mode.LOCAL,
            session = GenerationSession(),
            systemPrompt = "system",
            userMessage = "user"
        ).toList()

        assertEquals(listOf("partial local"), events.deltaTexts())
        assertEquals(Backend.MNN, (events[1] as GenerationEvent.Failed).backend)
        assertEquals(Backend.CLOUD, (events.last() as GenerationEvent.Failed).backend)
        assertEquals(HybridAiRepository.Mode.CLOUD, repository.activeMode.value)
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

        assertTrue(thrown is CancellationException)
        assertEquals(HybridAiRepository.Mode.LOCAL, repository.activeMode.value)
        verify(cloudRepository, never()).streamResponseWithParams(any(), any(), any(), any())
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
    fun `permanent local failure blocks reinitialization`() = runTest {
        whenever(localModelManager.recentFailure(sampleModel().id)).thenReturn(
            LocalModelFailure(
                count = 1,
                lastFailureAtMillis = System.currentTimeMillis(),
                type = LocalModelFailureType.CORRUPT
            )
        )

        val mode = repository.selectModeForGeneration(forceCloud = false)

        assertEquals(HybridAiRepository.Mode.CLOUD, mode)
        verify(localEngine, never()).initialize(any())
    }

    @Test
    fun `transient local failure backs off before retrying`() = runTest {
        whenever(localModelManager.recentFailure(sampleModel().id)).thenReturn(
            LocalModelFailure(
                count = 1,
                lastFailureAtMillis = System.currentTimeMillis(),
                type = LocalModelFailureType.TRANSIENT
            )
        )

        val mode = repository.selectModeForGeneration(forceCloud = false)

        assertEquals(HybridAiRepository.Mode.CLOUD, mode)
        verify(localEngine, never()).initialize(any())
    }

    @Test
    fun `transient local failure retries after backoff`() = runTest {
        whenever(localModelManager.recentFailure(sampleModel().id)).thenReturn(
            LocalModelFailure(
                count = 1,
                lastFailureAtMillis = System.currentTimeMillis() - 60_000L,
                type = LocalModelFailureType.TRANSIENT
            )
        )
        whenever(localEngine.initialize(sampleModel())).thenReturn(true)

        val mode = repository.selectModeForGeneration(forceCloud = false)

        assertEquals(HybridAiRepository.Mode.LOCAL, mode)
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
    fun `model deletion releases the engine before clearing and deleting the model`() = runTest {
        whenever(localModelManager.deleteModel(sampleModel().modelDir)).thenReturn(true)

        val deleted = repository.deleteLocalModel(sampleModel().modelDir)

        assertTrue(deleted)
        val ordered = inOrder(localEngine, localModelManager)
        ordered.verify(localEngine).release()
        ordered.verify(localModelManager).clearCurrentModel()
        ordered.verify(localModelManager).deleteModel(sampleModel().modelDir)
    }

    @Test
    fun `deleting a different model preserves the active runtime`() = runTest {
        whenever(localModelManager.deleteModel("/models/other")).thenReturn(true)
        val deleted = repository.deleteLocalModel("/models/other")

        assertTrue(deleted)
        verify(localEngine, never()).release()
        verify(localModelManager).deleteModel("/models/other")
    }

    @Test
    fun `selecting a model waits until an in flight deletion finishes`() = runBlocking<Unit> {
        repository = newRepository(Dispatchers.IO)
        withTimeout(5_000) {
            val entered = CompletableDeferred<Unit>()
            val finish = CompletableDeferred<Unit>()
            val other = sampleModel(id = "other", modelDir = "/models/other")
            whenever(localModelManager.deleteModel(other.modelDir)).thenAnswer {
                runBlocking {
                    entered.complete(Unit)
                    finish.await()
                    false
                }
            }
            whenever(localModelManager.selectModel(other.modelDir)).thenReturn(other)
            val deletion = async { repository.deleteLocalModel(other.modelDir) }
            try {
                entered.await()
                val selection = async(Dispatchers.IO, start = CoroutineStart.UNDISPATCHED) {
                    repository.selectLocalModel(other.modelDir)
                }
                assertFalse(selection.isCompleted)
                verify(localModelManager, never()).selectModel(other.modelDir)
                finish.complete(Unit)
                assertFalse(deletion.await())
                assertEquals(other, selection.await())
                val ordered = inOrder(localModelManager)
                ordered.verify(localModelManager).deleteModel(other.modelDir)
                ordered.verify(localModelManager).selectModel(other.modelDir)
            } finally {
                finish.complete(Unit)
            }
        }
    }

    @Test
    fun `failed deletion does not leave initialization blocked`() = runTest {
        whenever(localModelManager.deleteModel("/models/other")).thenAnswer {
            throw java.io.IOException("delete failed")
        }

        assertFalse(repository.deleteLocalModel("/models/other"))
        assertTrue(repository.initializeLocalModel())
    }

    @Test
    fun `release failure never proceeds to file deletion`() = runTest {
        repository.initializeLocalModel()
        whenever(localEngine.release()).thenThrow(IllegalStateException("close failed"))

        assertFalse(repository.deleteLocalModel(sampleModel().modelDir))

        verify(localModelManager, never()).clearCurrentModel()
        verify(localModelManager, never()).deleteModel(any())
    }

    @Test
    fun `initialization cancellation survives a cleanup failure`() = runTest {
        val cancellation = CancellationException("cancelled")
        val cleanupError = IllegalStateException("close failed")
        whenever(localEngine.initialize(any())).thenAnswer { throw cancellation }
        whenever(localEngine.release()).thenThrow(cleanupError)

        val failure = runCatching { repository.initializeLocalModel() }.exceptionOrNull()

        assertTrue(failure is CancellationException)
        assertTrue(generateSequence(failure) { it.cause }.any { it === cancellation })
        assertEquals(listOf(cleanupError), cancellation.suppressed.toList())
        verify(localModelManager, never()).recordLocalLoadFailure(any(), any(), any())
    }

    @Test
    fun `two historical failures allow a local probe after backoff`() = runTest {
        whenever(localModelManager.recentFailureCount(sampleModel().id)).thenReturn(2)
        whenever(localModelManager.recentFailure(sampleModel().id)).thenReturn(
            LocalModelFailure(2, System.currentTimeMillis() - 120_000L, LocalModelFailureType.TRANSIENT)
        )

        assertEquals(HybridAiRepository.Mode.LOCAL, repository.selectModeForGeneration(false))
        verify(localEngine).initialize(sampleModel())
        verify(localModelManager, never()).recordLocalLoadSuccess(any())
    }

    @Test
    fun `deactivation prevents a late local failure from starting cloud generation`() = runBlocking<Unit> {
        repository = newRepository(Dispatchers.IO)
        withTimeout(5_000) {
            val entered = CompletableDeferred<Unit>()
            val finish = CompletableDeferred<Unit>()
            whenever(localEngine.streamResponse(any(), any(), any(), any())).thenReturn(flow {
                emit(delta("partial"))
                entered.complete(Unit)
                finish.await()
                throw IllegalStateException("late failure")
            })
            val generation = async {
                repository.streamResponse(
                    HybridAiRepository.Mode.LOCAL, GenerationSession(), "system", "user"
                ).toList()
            }
            try {
                entered.await()
                repository.clearLocalModel()
                finish.complete(Unit)
                val events = generation.await()
                assertEquals(listOf("partial"), events.deltaTexts())
                assertEquals(1, events.filterIsInstance<GenerationEvent.Stopped>().size)
                verify(cloudRepository, never()).streamResponseWithParams(any(), any(), any(), any())
                verify(localModelManager, never()).recordLocalLoadFailure(any(), any(), any())
            } finally {
                finish.complete(Unit)
            }
        }
    }

    @Test
    fun `a second local generation cannot replace the active session`() = runBlocking<Unit> {
        repository = newRepository(Dispatchers.IO)
        withTimeout(5_000) {
            val entered = CompletableDeferred<Unit>()
            val finish = CompletableDeferred<Unit>()
            whenever(localEngine.streamResponse(any(), any(), any(), any())).thenReturn(flow {
                entered.complete(Unit)
                finish.await()
                emit(completed())
            })
            val first = async {
                repository.streamResponse(
                    HybridAiRepository.Mode.LOCAL, GenerationSession(), "system", "first"
                ).toList()
            }
            try {
                entered.await()
                val second = repository.streamResponse(
                    HybridAiRepository.Mode.LOCAL, GenerationSession(), "system", "second"
                ).toList()
                assertEquals(1, second.filterIsInstance<GenerationEvent.Failed>().size)
                verify(localEngine).streamResponse(any(), eq("first"), any(), any())
                verify(localEngine, never()).streamResponse(any(), eq("second"), any(), any())
            } finally {
                finish.complete(Unit)
                first.await()
            }
        }
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
            emit(delta("partial"))
            awaitCancellation()
        })

        val events = mutableListOf<GenerationEvent>()
        val job = backgroundScope.launch {
            repository.streamResponse(
                mode = HybridAiRepository.Mode.LOCAL,
                session = session,
                systemPrompt = "system",
                userMessage = "user"
            ).toList(events)
        }

        awaitAssertion {
            assertEquals(listOf("partial"), events.deltaTexts())
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
        // Observe the real IO worker without advancing the test scheduler's virtual timeout.
        withContext(Dispatchers.Default) {
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
    }

    private fun deviceProfile() = DeviceProfile(
        totalRamBytes = 8_000_000_000L,
        availableMemBytes = 4_000_000_000L,
        appMemoryClassBytes = 512_000_000L,
        nativePssBytes = 100_000_000L,
        freeStorageBytes = 4_000_000_000L,
        abi = "arm64-v8a",
        sdk = 35,
        thermalStatus = ThermalStatus.NOMINAL,
        batteryPercent = 80,
        powerSaveMode = false
    )

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

    private fun delta(text: String): GenerationEvent = GenerationEvent.Delta(text)

    private fun completed(): GenerationEvent = GenerationEvent.Completed(
        GenerationMetrics(
            firstTokenLatencyMs = null,
            totalLatencyMs = 0L,
            outputTokens = 0,
            tokensPerSecond = 0.0
        )
    )

    private fun List<GenerationEvent>.deltaTexts(): List<String> {
        return filterIsInstance<GenerationEvent.Delta>().map { it.text }
    }
}
