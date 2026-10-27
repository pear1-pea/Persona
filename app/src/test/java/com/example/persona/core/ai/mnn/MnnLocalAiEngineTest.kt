package com.example.persona.core.ai.mnn

import com.example.persona.core.ai.Backend
import com.example.persona.core.ai.EngineState
import com.example.persona.core.ai.GenerationEvent
import com.example.persona.core.ai.GenerationParams
import com.example.persona.core.ai.GenerationSession
import com.example.persona.core.ai.InstalledModel
import com.example.persona.core.ai.LocalModelFailureType
import com.example.persona.core.ai.ModelFamilies
import com.example.persona.core.ai.PromptFormats
import com.example.persona.core.ai.prompt.PromptAdapterRegistry
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class MnnLocalAiEngineTest {

    @get:Rule
    val temporaryFolder = TemporaryFolder()

    private lateinit var modelDir: File
    private lateinit var model: InstalledModel
    private val sessions = mutableListOf<FakeNativeSession>()

    @Before
    fun setUp() {
        modelDir = temporaryFolder.newFolder("model")
        modelDir.resolve("config.json").writeText("{}")
        model = InstalledModel(
            id = "qwen2.5-0.5b-instruct-mnn",
            name = "Qwen2.5 0.5B Instruct",
            version = "1.0.0",
            modelDir = modelDir.absolutePath,
            backend = Backend.MNN,
            family = ModelFamilies.QWEN2_5,
            promptFormat = PromptFormats.QWEN_CHATML_TEXT,
            contextWindow = 4_096
        )
    }

    @Test
    fun `initialize loads a model and reports ready`() = runTest {
        val engine = newEngine()

        assertTrue(engine.initialize(model))
        assertEquals(EngineState.Ready, engine.state.value)
        assertEquals(1, sessions.single().loadCalls)
    }

    @Test
    fun `initialize reuses an already loaded model`() = runTest {
        val engine = newEngine()

        assertTrue(engine.initialize(model))
        assertTrue(engine.initialize(model))

        assertEquals(1, sessions.single().loadCalls)
    }

    @Test
    fun `initialize closes the previous session when the model changes`() = runTest {
        val engine = newEngine()
        assertTrue(engine.initialize(model))
        val firstSession = sessions.single()

        val otherDir = temporaryFolder.newFolder("other-model")
        otherDir.resolve("config.json").writeText("{}")
        val other = model.copy(id = "other", modelDir = otherDir.absolutePath)
        assertTrue(engine.initialize(other))

        assertEquals(1, firstSession.closeCalls)
        assertEquals(2, sessions.size)
        assertEquals(1, sessions.last().loadCalls)
    }

    @Test
    fun `load failure yields error state`() = runTest {
        val engine = newEngine(loadResult = false)

        assertFalse(engine.initialize(model))
        assertTrue(engine.state.value is EngineState.Error)
        assertEquals(
            LocalModelFailureType.TRANSIENT,
            (engine.state.value as EngineState.Error).type
        )
        assertEquals(1, sessions.single().loadCalls)
    }

    @Test
    fun `missing config yields error without creating a native session`() = runTest {
        val engine = newEngine()
        modelDir.resolve("config.json").delete()

        assertFalse(engine.initialize(model))
        assertTrue(engine.state.value is EngineState.Error)
        assertEquals(
            LocalModelFailureType.CORRUPT,
            (engine.state.value as EngineState.Error).type
        )
        assertTrue(sessions.isEmpty())
    }

    @Test
    fun `non mnn backend is rejected`() = runTest {
        val engine = newEngine()

        assertFalse(engine.initialize(model.copy(backend = Backend.CLOUD)))
        assertTrue(engine.state.value is EngineState.Error)
        assertEquals(
            LocalModelFailureType.INCOMPATIBLE,
            (engine.state.value as EngineState.Error).type
        )
        assertTrue(sessions.isEmpty())
    }

    @Test
    fun `smoke test emits a token and resets the session`() = runTest {
        val engine = newEngine(tokens = listOf("pong"))
        assertTrue(engine.initialize(model))

        assertTrue(engine.smokeTest(model))

        assertEquals(1, sessions.single().generateCalls)
        assertEquals(1, sessions.single().resetCalls)
    }

    @Test
    fun `smoke test fails when the model is not ready`() = runTest {
        val engine = newEngine()

        assertFalse(engine.smokeTest(model))
    }

    @Test
    fun `smoke test fails when generation emits no output`() = runTest {
        val engine = newEngine(tokens = emptyList())
        assertTrue(engine.initialize(model))

        assertFalse(engine.smokeTest(model))
    }

    @Test
    fun `stream response emits deltas and completion`() = runBlocking {
        val engine = newEngine(tokens = listOf("hello", " world"))
        assertTrue(engine.initialize(model))

        val events = withTimeout(5_000) {
            engine.streamResponse(
                session = GenerationSession(),
                prompt = "hi",
                params = GenerationParams(maxTokens = 4)
            ).toList()
        }

        assertEquals(listOf("hello", " world"), events.filterIsInstance<GenerationEvent.Delta>().map { it.text })
        assertTrue(events.any { it is GenerationEvent.Completed })
        assertEquals(1, sessions.single().generateCalls)
    }

    @Test
    fun `stream response reports a native failure`() = runBlocking {
        val engine = newEngine(tokens = listOf("partial"))
        assertTrue(engine.initialize(model))
        sessions.single().generateError = IllegalStateException("boom")

        val events = withTimeout(5_000) {
            engine.streamResponse(
                session = GenerationSession(),
                prompt = "hi",
                params = GenerationParams(maxTokens = 4)
            ).toList()
        }

        val failed = events.filterIsInstance<GenerationEvent.Failed>().single()
        assertEquals(Backend.MNN, failed.backend)
        assertTrue(engine.state.value is EngineState.Error)
    }

    @Test
    fun `stream response refuses to run before initialization`() = runBlocking {
        val engine = newEngine()

        val events = withTimeout(5_000) {
            engine.streamResponse(
                session = GenerationSession(),
                prompt = "hi",
                params = GenerationParams(maxTokens = 4)
            ).toList()
        }

        assertTrue(events.single() is GenerationEvent.Failed)
    }

    @Test
    fun `release closes the active session`() = runTest {
        val engine = newEngine()
        assertTrue(engine.initialize(model))

        engine.release()

        assertEquals(1, sessions.single().closeCalls)
        assertEquals(EngineState.Idle, engine.state.value)
    }

    private fun newEngine(
        loadResult: Boolean = true,
        tokens: List<String> = listOf("hello")
    ): MnnLocalAiEngine {
        return MnnLocalAiEngine(
            promptAdapterRegistry = PromptAdapterRegistry(),
            sessionFactory = NativeSessionFactory {
                FakeNativeSession(loadResult = loadResult, tokens = tokens).also {
                    sessions.add(it)
                }
            }
        )
    }

    private class FakeNativeSession(
        private val loadResult: Boolean,
        private val tokens: List<String>
    ) : NativeMnnRuntimeSession {
        var loadCalls = 0
        var closeCalls = 0
        var resetCalls = 0
        var generateCalls = 0
        var generateError: Throwable? = null

        override fun load(modelConfigPath: String): Boolean {
            loadCalls += 1
            return loadResult
        }

        override fun countTokens(text: String): Int = text.length

        override fun reset() {
            resetCalls += 1
        }

        override fun generate(
            payload: com.example.persona.core.ai.prompt.NativePromptPayload,
            params: GenerationParams,
            onToken: (String) -> Boolean
        ) {
            generateCalls += 1
            generateError?.let { throw it }
            tokens.forEach { token -> onToken(token) }
        }

        override fun stop() = Unit

        override fun close() {
            closeCalls += 1
        }
    }
}
