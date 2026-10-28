package com.example.persona.data.repository

import android.util.Log
import com.example.persona.core.ai.ChatMessage
import com.example.persona.core.ai.Backend
import com.example.persona.core.ai.EngineState
import com.example.persona.core.ai.GenerationError
import com.example.persona.core.ai.GenerationErrorType
import com.example.persona.core.ai.GenerationEvent
import com.example.persona.core.ai.GenerationParams
import com.example.persona.core.ai.GenerationSession
import com.example.persona.core.ai.InstalledModel
import com.example.persona.core.ai.LocalAiEngine
import com.example.persona.core.ai.LocalModelFailureType
import com.example.persona.core.ai.LocalModelManager
import com.example.persona.core.ai.ModelScanReport
import com.example.persona.core.ai.PrivacyLevel
import com.example.persona.core.ai.Route
import com.example.persona.core.ai.RoutingContextProvider
import com.example.persona.core.ai.RoutingDecision
import com.example.persona.core.ai.RoutingInput
import com.example.persona.core.ai.HybridRouter
import com.example.persona.core.ai.TaskComplexity
import com.example.persona.core.ai.StopReason
import com.example.persona.core.ai.classifyLocalModelFailure
import com.example.persona.core.ai.evaluateModelAdmission
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class HybridAiRepository internal constructor(
    private val cloudRepository: CloudChatRepository,
    private val localAiEngine: LocalAiEngine,
    private val localModelManager: LocalModelManager,
    private val router: HybridRouter,
    private val routingContextProvider: RoutingContextProvider,
    private val routeScope: CoroutineScope,
    private val modelDispatcher: CoroutineDispatcher = Dispatchers.IO
) {
    @Inject
    constructor(
        cloudRepository: CloudChatRepository,
        localAiEngine: LocalAiEngine,
        localModelManager: LocalModelManager,
        router: HybridRouter,
        routingContextProvider: RoutingContextProvider
    ) : this(
        cloudRepository,
        localAiEngine,
        localModelManager,
        router,
        routingContextProvider,
        CoroutineScope(SupervisorJob() + Dispatchers.Default)
    )

    enum class Mode { CLOUD, LOCAL }

    val localEngineState = localAiEngine.state
    private val _activeMode = MutableStateFlow(Mode.CLOUD)
    val activeMode = _activeMode.asStateFlow()
    private val _lastRoutingDecision = MutableStateFlow<RoutingDecision?>(null)
    val lastRoutingDecision = _lastRoutingDecision.asStateFlow()

    private val routeLock = Any()
    private val modelOperationMutex = Mutex()

    @Volatile
    private var selectedModelDir: String? = localModelManager.currentModel.value?.modelDir

    @Volatile
    private var loadedModelDir: String? = null

    @Volatile
    private var activeGeneration: LocalGeneration? = null

    init {
        routeScope.launch {
            try {
                localModelManager.currentModel.collect {
                    withModelOperation {
                        handleCurrentModelChanged(localModelManager.currentModel.value)
                    }
                }
            } catch (error: CancellationException) {
                throw error
            } catch (error: Throwable) {
                Log.e(TAG, "Failed to observe current model changes", error)
            }
        }
    }

    suspend fun initializeLocalModel(): Boolean {
        return withModelOperation {
            localModelManager.refreshInstalledModels()
            val model = localModelManager.currentModel.value ?: run {
                releaseLocalRoute()
                return@withModelOperation false
            }
            if (loadedModelDir == model.modelDir && localAiEngine.state.value == EngineState.Ready) {
                true
            } else {
                initializeSelectedModel(model)
            }
        }
    }

    suspend fun refreshLocalModels(): List<ModelScanReport> = withModelOperation {
        val reports = localModelManager.refreshModelReports()
        handleCurrentModelChanged(localModelManager.currentModel.value)
        reports
    }

    suspend fun selectLocalModel(modelDir: String): InstalledModel? = withModelOperation {
        val selected = localModelManager.selectModel(modelDir) ?: return@withModelOperation null
        releaseLocalRoute()
        selectedModelDir = selected.modelDir
        selected
    }

    suspend fun clearLocalModel() = withModelOperation {
        releaseLocalRoute()
        localModelManager.clearCurrentModel()
        selectedModelDir = null
    }

    suspend fun deleteLocalModel(modelDir: String): Boolean = withModelOperation {
        try {
            val targetDir = java.io.File(modelDir).canonicalPath
            val current = localModelManager.currentModel.value
            val isCurrent = current?.modelDir?.let { java.io.File(it).canonicalPath } == targetDir
            val isLoaded = loadedModelDir?.let { java.io.File(it).canonicalPath } == targetDir
            if (isCurrent || isLoaded) {
                releaseLocalRoute()
                if (isCurrent) {
                    localModelManager.clearCurrentModel()
                }
                selectedModelDir = localModelManager.currentModel.value?.modelDir
            }
            localModelManager.deleteModel(modelDir)
        } catch (error: CancellationException) {
            throw error
        } catch (error: Throwable) {
            Log.e(TAG, "Failed to delete local model: $modelDir", error)
            false
        }
    }

    private suspend fun <T> withModelOperation(block: suspend () -> T): T {
        return withContext(modelDispatcher) {
            modelOperationMutex.withLock { block() }
        }
    }

    fun selectMode(forceCloud: Boolean, localEnabled: Boolean = true): Mode {
        return if (!forceCloud &&
            localEnabled &&
            localModelManager.currentModel.value != null &&
            localAiEngine.state.value == EngineState.Ready &&
            loadedModelDir == localModelManager.currentModel.value?.modelDir
        ) {
            Mode.LOCAL
        } else {
            Mode.CLOUD
        }
    }

    suspend fun selectModeForGeneration(forceCloud: Boolean, localEnabled: Boolean = true): Mode {
        val decision = selectRouteForGeneration(
            forceCloud = forceCloud,
            localOnly = false,
            privacyLevel = PrivacyLevel.NORMAL,
            taskComplexity = TaskComplexity.NORMAL,
            localEnabled = localEnabled
        )
        return decision.toMode()
    }

    suspend fun selectRouteForGeneration(
        forceCloud: Boolean,
        localOnly: Boolean = false,
        privacyLevel: PrivacyLevel,
        taskComplexity: TaskComplexity,
        localEnabled: Boolean = true
    ): RoutingDecision {
        val profile = localModelManager.deviceCapability()
        val model = localModelManager.currentModel.value
        val admission = if (model == null) com.example.persona.core.ai.ModelAdmission.BLOCKED
            else modelAdmission(model).status
        val retryBlocked = model?.let { localModelManager.recentFailure(it.id) }
            ?.isReadyToRetry(System.currentTimeMillis()) == false
        var decision = router.decide(
            RoutingInput(
                forceCloud = forceCloud,
                localOnly = localOnly,
                localAdmission = admission,
                privacyLevel = privacyLevel,
                taskComplexity = taskComplexity,
                networkState = routingContextProvider.networkState(),
                batteryPercent = profile.batteryPercent,
                thermalStatus = profile.thermalStatus,
                powerSaveMode = profile.powerSaveMode,
                localRetryBlocked = retryBlocked,
                localEnabled = localEnabled
            )
        )

        if (decision.isExecutable && decision.isLocalPreferred) {
            if (!ensureLocalReady()) {
                decision = if (decision.route == Route.LOCAL) {
                    decision.copy(
                        isExecutable = false,
                        reasons = decision.reasons + "本地 runtime 未通过初始化或 smoke test"
                    )
                } else {
                    decision.copy(
                        route = Route.CLOUD,
                        reasons = decision.reasons + "本地 runtime 未通过初始化或 smoke test，改用云端"
                    )
                }
            }
        }
        _lastRoutingDecision.value = decision
        Log.i(TAG, "Routing decision: route=${decision.route}, reasons=${decision.reasons.joinToString(";")}")
        if (!decision.isLocalPreferred) {
            _activeMode.value = Mode.CLOUD
        }
        return decision
    }

    fun stopGeneration(session: GenerationSession) {
        session.requestStop(StopReason.USER_REQUESTED)
        localAiEngine.stopGeneration(session)
    }

    fun streamResponse(
        mode: Mode,
        session: GenerationSession,
        systemPrompt: String,
        userMessage: String,
        history: List<ChatMessage> = emptyList(),
        route: Route = Route.LOCAL_THEN_CLOUD,
        params: GenerationParams = GenerationParams()
    ): Flow<GenerationEvent> = flow {
        session.stopReason?.let {
            emit(GenerationEvent.Stopped(it, ""))
            return@flow
        }
        val authorization = session.routingDecision
        if (authorization?.isExecutable == false ||
            (mode == Mode.CLOUD && authorization?.cloudAllowed == false) ||
            (mode == Mode.LOCAL && authorization != null && !authorization.isLocalPreferred)
        ) {
            emit(GenerationEvent.Failed(
                backend = if (mode == Mode.LOCAL) Backend.MNN else Backend.CLOUD,
                error = GenerationError(GenerationErrorType.MODEL_NOT_READY,
                    authorization?.reasons.orEmpty().joinToString("；").ifBlank { "当前约束下没有可用的执行路径" }),
                partialText = ""
            ))
            return@flow
        }
        emitAll(streamAuthorizedResponse(mode, session, systemPrompt, userMessage, history, route, params))
    }

    private fun streamAuthorizedResponse(
        mode: Mode,
        session: GenerationSession,
        systemPrompt: String,
        userMessage: String,
        history: List<ChatMessage>,
        route: Route,
        params: GenerationParams
    ): Flow<GenerationEvent> = when (mode) {
        Mode.CLOUD -> flow {
            _activeMode.value = Mode.CLOUD
            emitAll(cloudRepository.streamResponseWithParams(systemPrompt, userMessage, history, params))
        }
        Mode.LOCAL -> flow {
            session.stopReason?.let { reason ->
                emit(GenerationEvent.Stopped(reason, ""))
                return@flow
            }
            var registration: LocalGeneration? = null
            try {
                val generation = withModelOperation {
                    if (activeGeneration != null || !ensureLocalReadyLocked()) {
                        null
                    } else {
                        localModelManager.currentModel.value?.let { model ->
                            LocalGeneration(session, model).also {
                                registration = it
                                synchronized(routeLock) { activeGeneration = it }
                                _activeMode.value = Mode.LOCAL
                            }
                        }
                    }
                }
                if (generation == null) {
                    emit(
                        GenerationEvent.Failed(
                            backend = Backend.MNN,
                            error = GenerationError(
                                type = GenerationErrorType.MODEL_NOT_READY,
                                message = "本地模型不可用或仍有生成任务"
                            ),
                            partialText = ""
                        )
                    )
                    return@flow
                }
                generation.stopReason?.let { reason ->
                    emit(GenerationEvent.Stopped(reason, ""))
                    return@flow
                }
                val localPartial = StringBuilder()
                var localTerminal = false
                var terminalDelivered = false
                val localFailure = try {
                    var failure: GenerationEvent.Failed? = null
                    localAiEngine.streamResponse(
                        session = session,
                        prompt = userMessage,
                        history = listOf(ChatMessage("system", systemPrompt)) + history,
                        params = params
                    ).collect { event ->
                        if (localTerminal) return@collect
                        val stopReason = generation.stopReason
                        if (stopReason != null) {
                            localTerminal = true
                            emit(GenerationEvent.Stopped(stopReason, localPartial.toString()))
                            terminalDelivered = true
                            return@collect
                        }
                        when (event) {
                            is GenerationEvent.Delta -> localPartial.append(event.text)
                            is GenerationEvent.Failed -> {
                                failure = event
                                localTerminal = true
                            }
                            is GenerationEvent.Completed -> {
                                localTerminal = true
                                localModelManager.recordLocalLoadSuccess(generation.model)
                            }
                            is GenerationEvent.Stopped -> localTerminal = true
                        }
                        if (event !is GenerationEvent.Failed) {
                            emit(event)
                            terminalDelivered = event is GenerationEvent.Completed || event is GenerationEvent.Stopped
                        }
                    }
                    failure ?: if (!localTerminal) {
                        GenerationEvent.Failed(
                            backend = Backend.MNN,
                            error = GenerationError(
                                type = GenerationErrorType.MODEL_RUNTIME,
                                message = "本地 AI 流提前结束"
                            ),
                            partialText = localPartial.toString()
                        )
                    } else {
                        null
                    }
                } catch (error: CancellationException) {
                    throw error
                } catch (error: Throwable) {
                    Log.e(TAG, "Local generation failed; falling back to cloud", error)
                    GenerationEvent.Failed(
                        backend = Backend.MNN,
                        error = GenerationError(
                            type = GenerationErrorType.MODEL_RUNTIME,
                            message = error.message ?: "本地 AI 生成失败",
                            cause = error
                        ),
                        partialText = localPartial.toString()
                    )
                }
                val stopReason = generation.stopReason
                if (stopReason != null && !terminalDelivered) {
                    emit(GenerationEvent.Stopped(stopReason, localPartial.toString()))
                } else if (!terminalDelivered && localFailure != null && stopReason == null &&
                    route == Route.LOCAL_THEN_CLOUD && session.routingDecision?.cloudAllowed != false &&
                    routingContextProvider.networkState() !in setOf(
                        com.example.persona.core.ai.NetworkState.OFFLINE,
                        com.example.persona.core.ai.NetworkState.METERED
                    )) {
                    Log.e(TAG, "Local generation failed; falling back to cloud")
                    generation.model.takeUnless { localFailure.error.type == GenerationErrorType.INPUT_BUDGET_EXCEEDED }?.let { model ->
                        val cause = localFailure.error.cause
                            ?: IllegalStateException(localFailure.error.message)
                        localModelManager.recordLocalLoadFailure(
                            model,
                            cause,
                            classifyLocalModelFailure(cause)
                        )
                    }
                    _activeMode.value = Mode.CLOUD
                    emit(localFailure.copy(fallbackPlanned = true))
                    currentCoroutineContext().ensureActive()
                    if (session.stopReason != null) return@flow
                    emitAll(cloudRepository.streamResponseWithParams(systemPrompt, userMessage, history, params))
                } else if (!terminalDelivered && localFailure != null && stopReason == null) {
                    generation.model.takeUnless { localFailure.error.type == GenerationErrorType.INPUT_BUDGET_EXCEEDED }?.let { model ->
                        val cause = localFailure.error.cause
                            ?: IllegalStateException(localFailure.error.message)
                        localModelManager.recordLocalLoadFailure(
                            model,
                            cause,
                            classifyLocalModelFailure(cause)
                        )
                    }
                    emit(localFailure)
                }
            } finally {
                synchronized(routeLock) {
                    if (registration != null && activeGeneration === registration) {
                        activeGeneration = null
                    }
                }
            }
        }
    }

    private suspend fun ensureLocalReady(): Boolean {
        return withModelOperation { ensureLocalReadyLocked() }
    }

    private suspend fun ensureLocalReadyLocked(): Boolean {
        var model = localModelManager.currentModel.value
        if (model == null) {
            localModelManager.refreshInstalledModels()
            model = localModelManager.currentModel.value
        }

        if (model == null) {
            releaseLocalRoute()
            return false
        }
        if (!modelAdmission(model).isSelectable) return false

        if (localAiEngine.state.value == EngineState.Ready && loadedModelDir == model.modelDir) {
            _activeMode.value = Mode.LOCAL
            return true
        }

        if (loadedModelDir != null && loadedModelDir != model.modelDir) {
            releaseLocalRoute()
        }

        return initializeSelectedModel(model)
    }

    private suspend fun initializeSelectedModel(model: InstalledModel): Boolean {
        if (!modelAdmission(model).isSelectable) return false
        if (loadedModelDir != null) {
            releaseLocalRoute()
        }
        val recentFailure = localModelManager.recentFailure(model.id)
        if (recentFailure != null) {
            val now = System.currentTimeMillis()
            if (recentFailure.isPermanent) {
                Log.i(TAG, "Local model has a permanent failure: ${model.id}, type=${recentFailure.type}")
                localAiEngine.release()
                loadedModelDir = null
                _activeMode.value = Mode.CLOUD
                return false
            }
            if (!recentFailure.isReadyToRetry(now)) {
                Log.i(
                    TAG,
                    "Local model retry is backing off: ${model.id}, " +
                        "type=${recentFailure.type}, retryAt=${recentFailure.retryAtMillis()}"
                )
                localAiEngine.release()
                loadedModelDir = null
                _activeMode.value = Mode.CLOUD
                return false
            }
        }
        selectedModelDir = model.modelDir
        loadedModelDir = model.modelDir
        val initialized = try {
            localAiEngine.initialize(model)
        } catch (error: CancellationException) {
            releaseAfterCancellation(error)
            throw error
        } catch (error: Throwable) {
            Log.e(TAG, "Local model initialization failed: ${model.id}", error)
            localModelManager.recordLocalLoadFailure(
                model,
                error,
                classifyLocalModelFailure(error)
            )
            releaseLocalRoute()
            return false
        }

        if (!initialized) {
            val errorState = localAiEngine.state.value as? EngineState.Error
            val reason = errorState?.reason
            val type = errorState?.type
                ?: classifyLocalModelFailure(reason?.let { IllegalStateException(it) })
            localModelManager.recordLocalLoadFailure(
                model,
                reason?.let { IllegalStateException(it) },
                type
            )
            releaseLocalRoute()
            return false
        }

        val smokePassed = try {
            localAiEngine.smokeTest(model)
        } catch (error: CancellationException) {
            releaseAfterCancellation(error)
            throw error
        } catch (error: Throwable) {
            Log.e(TAG, "Local model smoke test failed: ${model.id}", error)
            false
        }

        if (smokePassed) {
            loadedModelDir = model.modelDir
            _activeMode.value = Mode.LOCAL
        } else {
            releaseLocalRoute()
            localModelManager.recordLocalLoadFailure(
                model,
                IllegalStateException("本地模型 smoke test 失败"),
                LocalModelFailureType.TRANSIENT
            )
        }
        return smokePassed
    }

    private fun modelAdmission(model: InstalledModel) = evaluateModelAdmission(
        profile = localModelManager.deviceCapability(), requiredAbis = model.requiredAbis,
        minSdk = model.minSdk, minRamGb = model.minRamGb, modelSizeBytes = model.totalSizeBytes,
        contextWindow = model.contextWindow, reservedOutputTokens = 512,
        recentFailureCount = localModelManager.recentFailureCount(model.id)
    )

    private fun handleCurrentModelChanged(model: InstalledModel?) {
        val newModelDir = model?.modelDir
        synchronized(routeLock) {
            if (newModelDir == selectedModelDir) return
            selectedModelDir = newModelDir
        }

        val shouldRelease = loadedModelDir != null ||
            activeGeneration != null ||
            localAiEngine.state.value == EngineState.Ready

        if (shouldRelease) {
            releaseLocalRoute()
        } else {
            _activeMode.value = Mode.CLOUD
        }
    }

    private fun releaseLocalRoute() {
        val sessionToStop = synchronized(routeLock) {
            val generation = activeGeneration
            generation?.session?.requestStop(StopReason.ENGINE_RELEASED)
            activeGeneration = null
            generation?.session
        }
        _activeMode.value = Mode.CLOUD
        try {
            sessionToStop?.let(localAiEngine::stopGeneration)
        } finally {
            localAiEngine.release()
        }
        loadedModelDir = null
    }

    private fun releaseAfterCancellation(cancellation: CancellationException) {
        try {
            releaseLocalRoute()
        } catch (cleanupError: Throwable) {
            if (cleanupError !== cancellation) cancellation.addSuppressed(cleanupError)
        }
    }

    private companion object {
        const val TAG = "HybridAiRepository"
    }

    private class LocalGeneration(val session: GenerationSession, val model: InstalledModel) {
        val stopReason: StopReason?
            get() = session.stopReason
    }

    private fun RoutingDecision.toMode(): Mode {
        return if (isLocalPreferred) Mode.LOCAL else Mode.CLOUD
    }
}
