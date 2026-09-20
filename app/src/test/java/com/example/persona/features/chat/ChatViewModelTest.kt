package com.example.persona.features.chat

import androidx.arch.core.executor.testing.InstantTaskExecutorRule
import com.example.persona.core.ai.Backend
import com.example.persona.core.ai.EngineState
import com.example.persona.core.ai.GenerationError
import com.example.persona.core.ai.GenerationErrorType
import com.example.persona.core.ai.GenerationEvent
import com.example.persona.core.ai.GenerationMetrics
import com.example.persona.core.ai.Route
import com.example.persona.core.ai.RoutingDecision
import com.example.persona.data.repository.HybridAiRepository
import com.example.persona.domain.model.Message
import com.example.persona.domain.model.MessageStatus
import com.example.persona.domain.model.Persona
import com.example.persona.domain.repository.ChatRepository
import com.example.persona.domain.repository.PersonaRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.anyOrNull
import org.mockito.kotlin.doAnswer
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever

@kotlinx.coroutines.ExperimentalCoroutinesApi
class ChatViewModelTest {
    @get:Rule
    val instantTaskExecutorRule = InstantTaskExecutorRule()

    private lateinit var mainDispatcher: TestDispatcher
    private val hybridRepository: HybridAiRepository = mock()
    private val personaRepository: PersonaRepository = mock()
    private val chatRepository: ChatRepository = mock()
    private val activeMode = MutableStateFlow(HybridAiRepository.Mode.CLOUD)
    private val localEngineState = MutableStateFlow<EngineState>(EngineState.Idle)
    private val persona = Persona(
        id = "persona-1",
        name = "Persona",
        avatarUrl = "",
        postImageUrl = "",
        traits = listOf("calm"),
        backstory = "A test persona",
        creatorId = "user-1"
    )

    @Before
    fun setUp() {
        mainDispatcher = StandardTestDispatcher()
        kotlinx.coroutines.Dispatchers.setMain(mainDispatcher)
        whenever(hybridRepository.activeMode).thenReturn(activeMode)
        whenever(hybridRepository.localEngineState).thenReturn(localEngineState)
    }

    @After
    fun tearDown() {
        kotlinx.coroutines.Dispatchers.resetMain()
    }

    @Test
    fun `stopping generation persists already emitted partial content`() = runTest {
        val updates = mutableListOf<Pair<String, MessageStatus>>()
        configureBaseMocks(
            stream = flow {
                emit(delta("partial"))
                awaitCancellation()
            }
        )
        recordUpdates(updates)

        val viewModel = createViewModel()
        viewModel.loadPersonaInfo(persona.id)
        mainDispatcher.scheduler.advanceUntilIdle()

        viewModel.sendMessage("hello")
        mainDispatcher.scheduler.runCurrent()
        assertTrue(viewModel.isGenerating.value)

        viewModel.stopGenerating()
        mainDispatcher.scheduler.advanceUntilIdle()

        assertEquals("partial", updates.last().first)
        assertEquals(MessageStatus.STOPPED, updates.last().second)
        assertFalse(viewModel.isGenerating.value)
    }

    @Test
    fun `stream cancellation persists partial content as stopped`() = runTest {
        val updates = mutableListOf<Pair<String, MessageStatus>>()
        configureBaseMocks(
            stream = flow {
                emit(delta("partial"))
                throw CancellationException("stream cancelled")
            }
        )
        recordUpdates(updates)

        val viewModel = createViewModel()
        viewModel.loadPersonaInfo(persona.id)
        mainDispatcher.scheduler.advanceUntilIdle()

        viewModel.sendMessage("hello")
        mainDispatcher.scheduler.advanceUntilIdle()

        assertEquals("partial", updates.last().first)
        assertEquals(MessageStatus.STOPPED, updates.last().second)
    }

    @Test
    fun `cloud failure appends error after partial content`() = runTest {
        val updates = mutableListOf<Pair<String, MessageStatus>>()
        configureBaseMocks(
            stream = flow {
                emit(delta("partial"))
                emit(
                    GenerationEvent.Failed(
                        backend = Backend.CLOUD,
                        error = GenerationError(GenerationErrorType.NETWORK, "backend offline"),
                        partialText = "partial"
                    )
                )
            }
        )
        recordUpdates(updates)

        val viewModel = createViewModel()
        viewModel.loadPersonaInfo(persona.id)
        mainDispatcher.scheduler.advanceUntilIdle()

        viewModel.sendMessage("hello")
        mainDispatcher.scheduler.advanceUntilIdle()

        val (finalContent, finalStatus) = updates.last()
        assertTrue(finalContent.startsWith("partial"))
        assertTrue(finalContent.contains("backend offline"))
        assertEquals(MessageStatus.FAILED, finalStatus)
    }

    @Test
    fun `local fallback failure persists local failure then cloud failure`() = runTest {
        val updates = mutableListOf<Pair<String, MessageStatus>>()
        configureBaseMocks(
            route = Route.LOCAL_THEN_CLOUD,
            stream = flow {
                emit(delta("partial local"))
                emit(
                    GenerationEvent.Failed(
                        backend = Backend.MNN,
                        error = GenerationError(GenerationErrorType.MODEL_RUNTIME, "native stopped"),
                        partialText = "partial local"
                    )
                )
                emit(delta("cloud fallback"))
                emit(
                    GenerationEvent.Failed(
                        backend = Backend.CLOUD,
                        error = GenerationError(GenerationErrorType.NETWORK, "backend offline"),
                        partialText = "cloud fallback"
                    )
                )
            }
        )
        recordUpdates(updates)

        val viewModel = createViewModel()
        viewModel.loadPersonaInfo(persona.id)
        mainDispatcher.scheduler.advanceUntilIdle()

        viewModel.sendMessage("hello")
        mainDispatcher.scheduler.advanceUntilIdle()

        assertTrue(updates.size >= 2)
        assertTrue(
            updates.any { it.first.contains("partial local") && it.second == MessageStatus.FAILED }
        )
        assertTrue(updates.last().first.startsWith("cloud fallback"))
        assertEquals(MessageStatus.FAILED, updates.last().second)
    }

    @Test
    fun `generation error messages are excluded from the next local history`() = runTest {
        val errorMessage = Message(
            id = "error-message",
            personaId = persona.id,
            content = "cloud generation failed",
            isFromUser = false,
            status = MessageStatus.FAILED
        )
        val capturedHistories = mutableListOf<List<com.example.persona.core.ai.ChatMessage>>()
        configureBaseMocks(
            stream = flowOf(delta("reply"), completed()),
            recentMessages = listOf(errorMessage)
        )
        whenever(
            hybridRepository.streamResponse(
                any(), any(), any(), any(), any(), any()
            )
        ).thenAnswer { invocation ->
            capturedHistories += invocation.getArgument<List<com.example.persona.core.ai.ChatMessage>>(4)
            flowOf(delta("reply"), completed())
        }

        val viewModel = createViewModel()
        viewModel.loadPersonaInfo(persona.id)
        mainDispatcher.scheduler.advanceUntilIdle()
        viewModel.sendMessage("hello")
        mainDispatcher.scheduler.advanceUntilIdle()

        assertEquals(1, capturedHistories.size)
        assertTrue(capturedHistories.single().isEmpty())
    }

    private suspend fun configureBaseMocks(
        stream: Flow<GenerationEvent>,
        recentMessages: List<Message> = emptyList(),
        route: Route = Route.CLOUD
    ) {
        whenever(hybridRepository.initializeLocalModel()).thenReturn(false)
        whenever(
            hybridRepository.selectRouteForGeneration(any(), any(), any(), any(), anyOrNull(), any())
        ).thenReturn(RoutingDecision(route, emptyList()))
        whenever(personaRepository.getPersonaById(persona.id)).thenReturn(persona)
        whenever(chatRepository.saveMessage(any(), any())).thenReturn(Unit)
        whenever(chatRepository.getRecentMessages(any(), any())).thenReturn(recentMessages)
        whenever(chatRepository.getMessagesStream(any())).thenReturn(emptyFlow())
        whenever(
            hybridRepository.streamResponse(any(), any(), any(), any(), any(), any())
        ).thenReturn(stream)
    }

    private suspend fun recordUpdates(updates: MutableList<Pair<String, MessageStatus>>) {
        doAnswer {
            updates += it.getArgument<String>(1) to it.getArgument(2)
            Unit
        }.whenever(chatRepository).updateMessageContent(any(), any(), any())
    }

    private fun createViewModel(): ChatViewModel {
        return ChatViewModel(hybridRepository, personaRepository, chatRepository)
    }

    private fun delta(text: String): GenerationEvent = GenerationEvent.Delta(text)

    private fun completed(): GenerationEvent = GenerationEvent.Completed(
        GenerationMetrics(
            firstTokenLatencyMs = null,
            totalLatencyMs = 0L,
            outputTokens = 0,
            tokensPerSecond = 0.0
        )
    )
}
