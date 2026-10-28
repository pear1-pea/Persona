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
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class MnnLocalAiEngineTest {

    @Test
    fun `slow collector receives terminal after a full native delta buffer`() = runBlocking {
        for (fails in listOf(false, true)) {
            val engine = newEngine(tokens = List(65) { "word" })
            assertTrue(engine.initialize(model))
            if (fails) sessions.last().afterTokensError = IllegalStateException("injected after output")
            val events = mutableListOf<GenerationEvent>()
            withTimeout(5_000) {
                engine.streamResponse(GenerationSession(), "hello").collect { event ->
                    events += event
                    if (events.size == 1) kotlinx.coroutines.delay(300)
                }
            }
            assertEquals(65, events.count { it is GenerationEvent.Delta })
            assertEquals(1, events.count { it is GenerationEvent.Completed || it is GenerationEvent.Failed })
            assertEquals(fails, events.last() is GenerationEvent.Failed)
            engine.release()
        }
    }

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
    fun `failed initialization retains the same session when cleanup fails`() = runTest {
        for (loadError in listOf(null, IllegalStateException("tokenizer invalid"))) {
            val closeError = IllegalStateException("close failed")
            val engine = newEngine(loadResult = false, loadError = loadError, closeError = closeError)

            assertOriginalFailure(closeError, runCatching { engine.initialize(model) }.exceptionOrNull())
            val native = sessions.last()
            val failure = engine.state.value as EngineState.Error
            assertEquals(
                if (loadError == null) LocalModelFailureType.TRANSIENT else LocalModelFailureType.CORRUPT,
                failure.type
            )
            assertEquals(1, native.loadCalls)
            assertEquals(1, native.closeCalls)

            native.closeError = null
            engine.release()

            assertEquals(2, native.closeCalls)
            assertEquals(EngineState.Idle, engine.state.value)
        }
    }

    @Test
    fun `release during load does not lose a later cleanup failure`() = runTest {
        val native = FakeNativeSession(loadResult = false, tokens = emptyList())
        val closeError = IllegalStateException("close failed")
        val engine = MnnLocalAiEngine(PromptAdapterRegistry(), NativeSessionFactory { native })
        native.onLoad = {
            engine.release()
            assertEquals(1, native.closeCalls)
            native.closeError = closeError
        }

        assertOriginalFailure(closeError, runCatching { engine.initialize(model) }.exceptionOrNull())
        assertTrue(engine.state.value is EngineState.Error)
        assertEquals(2, native.closeCalls)

        native.closeError = null
        engine.release()

        assertEquals(3, native.closeCalls)
        assertEquals(EngineState.Idle, engine.state.value)
    }

    @Test
    fun `successful initialization cleanup clears ownership and preserves the load error`() = runTest {
        for (loadError in listOf(null, IllegalStateException("tokenizer invalid"))) {
            val engine = newEngine(loadResult = false, loadError = loadError)

            assertFalse(engine.initialize(model))
            val native = sessions.last()
            assertEquals(
                if (loadError == null) LocalModelFailureType.TRANSIENT else LocalModelFailureType.CORRUPT,
                (engine.state.value as EngineState.Error).type
            )
            assertEquals(1, native.closeCalls)

            engine.release()

            assertEquals(1, native.closeCalls)
        }
    }

    @Test
    fun `initialization cleanup preserves the original cancellation`() = runTest {
        for (closeError in listOf(null, IllegalStateException("close failed"))) {
            val cancellation = CancellationException("load cancelled")
            val engine = newEngine(loadError = cancellation, closeError = closeError)

            assertOriginalFailure(cancellation, runCatching { engine.initialize(model) }.exceptionOrNull())
            val native = sessions.last()
            assertEquals(1, native.closeCalls)
            if (closeError == null) {
                assertEquals(EngineState.Idle, engine.state.value)
                assertTrue(cancellation.suppressed.isEmpty())
            } else {
                assertSame(closeError, cancellation.suppressed.single())
                assertEquals(
                    LocalModelFailureType.TRANSIENT,
                    (engine.state.value as EngineState.Error).type
                )
            }

            native.closeError = null
            engine.release()

            assertEquals(if (closeError == null) 1 else 2, native.closeCalls)
            assertEquals(EngineState.Idle, engine.state.value)
        }
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
    fun `a request stopped before collection never invokes native generation`() = runBlocking<Unit> {
        val engine = newEngine(tokens = listOf("must not generate"))
        assertTrue(engine.initialize(model))
        val request = GenerationSession()
        engine.stopGeneration(request)

        val events = withTimeout(5_000) {
            engine.streamResponse(request, "hi").toList()
        }

        assertTrue(events.single() is GenerationEvent.Stopped)
        assertEquals(0, sessions.single().generateCalls)
    }

    @Test
    fun `stop during prompt planning prevents native generation`() = runBlocking<Unit> {
        val engine = newEngine(tokens = listOf("must not generate"))
        assertTrue(engine.initialize(model))
        val request = GenerationSession()
        sessions.single().onCountTokens = { engine.stopGeneration(request) }

        val events = withTimeout(5_000) {
            engine.streamResponse(request, "hi").toList()
        }

        assertEquals(
            com.example.persona.core.ai.StopReason.USER_REQUESTED,
            (events.single() as GenerationEvent.Stopped).reason
        )
        assertEquals(0, sessions.single().generateCalls)
    }

    @Test
    fun `release closes the active session`() = runTest {
        val engine = newEngine()
        assertTrue(engine.initialize(model))

        engine.release()

        assertEquals(1, sessions.single().closeCalls)
        assertEquals(EngineState.Idle, engine.state.value)
    }

    @Test
    fun `release failure retains the native session so closing can be retried`() = runTest {
        val engine = newEngine()
        assertTrue(engine.initialize(model))
        val native = sessions.single()
        native.closeError = IllegalStateException("close failed")

        assertTrue(runCatching { engine.release() }.isFailure)
        assertTrue(engine.state.value is EngineState.Error)
        native.closeError = null
        engine.release()

        assertEquals(2, native.closeCalls)
        assertEquals(EngineState.Idle, engine.state.value)
    }

    private fun assertOriginalFailure(expected: Throwable, actual: Throwable?) {
        assertEquals(expected.javaClass, actual?.javaClass)
        assertEquals(expected.message, actual?.message)
        assertTrue(generateSequence(actual) { it.cause }.any { it === expected })
    }

    private fun newEngine(
        loadResult: Boolean = true,
        tokens: List<String> = listOf("hello"),
        loadError: Throwable? = null,
        closeError: Throwable? = null
    ): MnnLocalAiEngine {
        return MnnLocalAiEngine(
            promptAdapterRegistry = PromptAdapterRegistry(),
            sessionFactory = NativeSessionFactory {
                FakeNativeSession(loadResult = loadResult, tokens = tokens).also {
                    it.loadError = loadError
                    it.closeError = closeError
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
        var afterTokensError: Throwable? = null
        var loadError: Throwable? = null
        var closeError: Throwable? = null
        var onLoad: (() -> Unit)? = null
        var onCountTokens: (() -> Unit)? = null

        override fun load(modelConfigPath: String): Boolean {
            loadCalls += 1
            onLoad?.invoke()
            loadError?.let { throw it }
            return loadResult
        }

        override fun countTokens(text: String): Int {
            onCountTokens?.invoke()
            return text.length
        }

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
            afterTokensError?.let { throw it }
        }

        override fun stop() = Unit

        override fun close() {
            closeCalls += 1
            closeError?.let { throw it }
        }
    }
}
