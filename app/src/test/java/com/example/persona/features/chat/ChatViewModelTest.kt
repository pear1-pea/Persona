package com.example.persona.features.chat

import androidx.arch.core.executor.testing.InstantTaskExecutorRule
import com.example.persona.core.ai.EngineState
import com.example.persona.data.remote.CloudGenerationException
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
import org.mockito.kotlin.doAnswer
import org.mockito.kotlin.eq
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
                emit("partial")
                awaitCancellation()
            }
        )
        doAnswer {
            updates += it.getArgument<String>(1) to it.getArgument(2)
            Unit
        }.whenever(chatRepository).updateMessageContent(any(), any(), any())

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
    fun `cancelled persistence is retried in non cancellable cleanup`() = runTest {
        val updates = mutableListOf<Pair<String, MessageStatus>>()
        var attempts = 0
        configureBaseMocks(stream = flowOf("partial"))
        doAnswer {
            attempts++
            if (attempts == 1) throw CancellationException("database update cancelled")
            updates += it.getArgument<String>(1) to it.getArgument(2)
            Unit
        }.whenever(chatRepository).updateMessageContent(any(), any(), any())

        val viewModel = createViewModel()
        viewModel.loadPersonaInfo(persona.id)
        mainDispatcher.scheduler.advanceUntilIdle()

        viewModel.sendMessage("hello")
        mainDispatcher.scheduler.advanceUntilIdle()

        assertTrue(attempts >= 2)
        assertEquals("partial", updates.last().first)
        assertEquals(MessageStatus.STOPPED, updates.last().second)
    }

    @Test
    fun `cloud failure appends error after partial content`() = runTest {
        val updates = mutableListOf<Pair<String, MessageStatus>>()
        configureBaseMocks(
            stream = flow {
                emit("partial")
                throw CloudGenerationException.Network(IllegalStateException("offline"))
            }
        )
        doAnswer {
            updates += it.getArgument<String>(1) to it.getArgument(2)
            Unit
        }.whenever(chatRepository).updateMessageContent(any(), any(), any())

        val viewModel = createViewModel()
        viewModel.loadPersonaInfo(persona.id)
        mainDispatcher.scheduler.advanceUntilIdle()

        viewModel.sendMessage("hello")
        mainDispatcher.scheduler.advanceUntilIdle()

        val (finalContent, finalStatus) = updates.last()
        assertTrue(finalContent.startsWith("partial"))
        assertTrue(finalContent.contains("[生成失败]"))
        assertTrue(finalContent.contains("DeepSeek 云端连接失败"))
        assertEquals(MessageStatus.FAILED, finalStatus)
    }

    @Test
    fun `cloud fallback failure keeps local partial content and fallback notice`() = runTest {
        val updates = mutableListOf<Pair<String, MessageStatus>>()
        configureBaseMocks(
            stream = flow {
                emit("partial local")
                emit("\n\n[本地 AI 生成中断，已切换到云端继续。]\n")
                throw CloudGenerationException.Network(IllegalStateException("offline"))
            }
        )
        whenever(hybridRepository.selectModeForGeneration(any(), any()))
            .thenReturn(HybridAiRepository.Mode.LOCAL)
        doAnswer {
            updates += it.getArgument<String>(1) to it.getArgument(2)
            Unit
        }.whenever(chatRepository).updateMessageContent(any(), any(), any())

        val viewModel = createViewModel()
        viewModel.loadPersonaInfo(persona.id)
        mainDispatcher.scheduler.advanceUntilIdle()

        viewModel.sendMessage("hello")
        mainDispatcher.scheduler.advanceUntilIdle()

        val (finalContent, finalStatus) = updates.last()
        assertTrue(finalContent.startsWith("partial local"))
        assertTrue(finalContent.contains("本地 AI 生成中断"))
        assertTrue(finalContent.contains("[生成失败]"))
        assertEquals(MessageStatus.FAILED, finalStatus)
    }

    @Test
    fun `generation error messages are excluded from the next local history`() = runTest {
        val errorMessage = Message(
            id = "error-message",
            personaId = persona.id,
            content = "云端 AI 生成失败",
            isFromUser = false,
            status = MessageStatus.FAILED
        )
        val capturedHistories = mutableListOf<List<com.example.persona.core.ai.ChatMessage>>()
        configureBaseMocks(
            stream = flowOf("reply"),
            recentMessages = listOf(errorMessage)
        )
        whenever(
            hybridRepository.streamResponse(
                any(), any(), any(), any(), any()
            )
        ).thenAnswer { invocation ->
            capturedHistories += invocation.getArgument<List<com.example.persona.core.ai.ChatMessage>>(4)
            flowOf("reply")
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
        stream: Flow<String>,
        recentMessages: List<Message> = emptyList()
    ) {
        whenever(hybridRepository.initializeLocalModel()).thenReturn(false)
        whenever(hybridRepository.selectModeForGeneration(any(), any()))
            .thenReturn(HybridAiRepository.Mode.CLOUD)
        whenever(personaRepository.getPersonaById(persona.id)).thenReturn(persona)
        whenever(chatRepository.getRecentMessages(any(), any())).thenReturn(recentMessages)
        whenever(chatRepository.getMessagesStream(any())).thenReturn(emptyFlow())
        whenever(
            hybridRepository.streamResponse(any(), any(), any(), any(), any())
        ).thenReturn(stream)
    }

    private fun createViewModel(): ChatViewModel {
        return ChatViewModel(hybridRepository, personaRepository, chatRepository)
    }
}
