package com.example.persona.features.memory

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.viewModelScope
import com.example.persona.core.auth.AuthIdentity
import com.example.persona.core.auth.AuthManager
import com.example.persona.core.base.BaseViewModel
import com.example.persona.domain.model.MemoryCandidate
import com.example.persona.domain.model.MemoryType
import com.example.persona.domain.model.Message
import com.example.persona.domain.model.MessageStatus
import com.example.persona.domain.repository.ChatRepository
import com.example.persona.domain.repository.MemoryCurationRepository
import com.example.persona.domain.repository.MemoryRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class MemoryCurationViewModel @Inject constructor(
    private val repository: MemoryCurationRepository,
    private val memories: MemoryRepository,
    private val chat: ChatRepository,
    private val auth: AuthManager,
    private val saved: SavedStateHandle
) : BaseViewModel() {
    private var identity = auth.identity.value
    private var operationJob: Job? = null
    private var observationJob: Job? = null
    private var operationVersion = 0L
    private var observationVersion = 0L
    private val sameOwner = saved.get<String>(OWNER) == identity.ownerId
    private val _uiState = MutableStateFlow(MemoryCurationUiState(
        personaId = saved[PERSONA], personaName = saved[NAME] ?: "",
        selectedMessageId = if (sameOwner) saved[SOURCE] else null,
        draft = if (sameOwner) restoreDraft() else null
    ))
    val uiState = _uiState.asStateFlow()

    init {
        if (!sameOwner) clearPrivateState()
        if (saved.get<Boolean>(INITIALIZED) == true) observe()
        viewModelScope.launch { auth.identity.collect { synchronizeIdentity() } }
    }

    fun initialize(personaId: String?, name: String, sourceMessageId: String? = null) {
        if (!synchronizeIdentity() || saved.get<Boolean>(INITIALIZED) == true) return
        saved[INITIALIZED] = true
        saved[PERSONA] = personaId
        saved[NAME] = name
        saved[OWNER] = identity.ownerId
        saved[SOURCE] = sourceMessageId
        _uiState.value = _uiState.value.copy(personaId = personaId, personaName = name,
            selectedMessageId = sourceMessageId)
        observe()
    }

    fun reload() {
        if (!available()) return
        observe()
    }

    fun configure(enabled: Boolean, cloudExtractionAllowed: Boolean) {
        runOperation(CurationOperation.CONFIGURING) { captured ->
            repository.configure(enabled, enabled && cloudExtractionAllowed)
            val preferences = repository.preferences()
            requireCurrent(captured)
            _uiState.value = _uiState.value.copy(preferences = preferences)
        }
    }

    fun selectMessage(id: String) {
        if (!available() || _uiState.value.messages.none { it.id == id }) return
        saved[SOURCE] = id
        _uiState.value = _uiState.value.copy(selectedMessageId = id)
    }

    fun proposeSelected() = extract(model = false, cloud = false)
    fun extractLocal() = extract(model = true, cloud = false)
    fun extractCloud() = extract(model = true, cloud = true)

    private fun extract(model: Boolean, cloud: Boolean) {
        if (!enabled() || (cloud && !_uiState.value.preferences.cloudExtractionAllowed)) return
        val persona = _uiState.value.personaId ?: return
        val source = _uiState.value.selectedMessageId ?: return
        runOperation(CurationOperation.EXTRACTING) { captured ->
            val message = chat.getMessageById(source)
            requireCurrent(captured)
            check(message?.id == source && eligible(message, persona) && (!cloud || !message.localOnly))
            if (model) repository.proposeWithModel(persona, source, cloud)
            else repository.propose(persona, source, explicitOnly = false)
            requireCurrent(captured)
        }
    }

    fun cancelExtraction() {
        if (!synchronizeIdentity() || _uiState.value.operation != CurationOperation.EXTRACTING) return
        operationVersion++
        operationJob?.cancel()
        _uiState.value = _uiState.value.copy(operation = null)
    }

    fun selectCandidate(id: String) {
        if (!enabled()) return
        val candidate = candidate(id) ?: return
        retainDraft(CurationDraft(candidate.id, candidate.content, candidate.type))
        loadConflicts(id)
    }

    private suspend fun refreshConflicts(id: String, captured: AuthIdentity) {
        val conflicts = repository.conflicts(id)
        requireCurrent(captured)
        if (_uiState.value.draft?.candidateId != id) return
        _uiState.value = _uiState.value.copy(conflicts = conflicts.filter {
            it.ownerId == captured.ownerId && !it.deleted &&
                (it.personaId == null || it.personaId == _uiState.value.personaId)
        }, conflictsLoaded = true)
    }

    private fun loadConflicts(id: String) {
        _uiState.value = _uiState.value.copy(conflicts = emptyList(), conflictsLoaded = false)
        runOperation(CurationOperation.SELECTING) { refreshConflicts(id, it) }
    }

    fun updateDraft(content: String, type: MemoryType, shared: Boolean, cloudAllowed: Boolean, expiryInput: String) {
        if (!available()) return
        val current = _uiState.value.draft ?: return
        val resolutionChanged = type != current.type || shared != current.shared
        retainDraft(current.copy(content = content, type = type, shared = shared,
            cloudAllowed = cloudAllowed, expiryInput = expiryInput,
            replaceId = if (resolutionChanged) null else current.replaceId,
            expectedRevision = if (resolutionChanged) null else current.expectedRevision,
            resolutionChosen = !resolutionChanged && current.resolutionChosen))
    }

    fun chooseNew() {
        if (!available()) return
        val draft = _uiState.value.draft ?: return
        retainDraft(draft.copy(replaceId = null, expectedRevision = null, resolutionChosen = true))
    }

    fun chooseReplacement(id: String) {
        if (!available() || !_uiState.value.conflictsLoaded) return
        val draft = _uiState.value.draft ?: return
        val memory = _uiState.value.possibleConflicts.firstOrNull { it.id == id } ?: return
        retainDraft(draft.copy(shared = memory.personaId == null, replaceId = memory.id,
            expectedRevision = memory.revision, resolutionChosen = true))
    }

    fun closeDraft() {
        if (!available()) return
        retainDraft(null)
    }

    fun confirm() {
        if (!enabled()) return
        val draft = _uiState.value.draft ?: return
        if (!draft.valid || !draft.resolutionChosen || !_uiState.value.conflictsLoaded) {
            _uiState.value = _uiState.value.copy(error = CurationError.INVALID_DRAFT)
            return
        }
        if (candidate(draft.candidateId) == null) return
        runOperation(CurationOperation.CONFIRMING) { captured ->
            repository.confirm(draft.candidateId, draft.content, draft.type, draft.shared,
                draft.cloudAllowed, parseMemoryExpiry(draft.expiryInput), draft.replaceId, draft.expectedRevision)
            requireCurrent(captured)
            retainDraft(null)
        }
    }

    fun ignore(id: String) {
        if (!available() || candidate(id) == null) return
        runOperation(CurationOperation.IGNORING) { captured ->
            repository.ignore(id)
            requireCurrent(captured)
            if (_uiState.value.draft?.candidateId == id) retainDraft(null)
        }
    }

    fun summarize() {
        if (!enabled()) return
        val persona = _uiState.value.personaId ?: return
        runOperation(CurationOperation.SUMMARIZING) { captured ->
            val result = repository.summarize(persona)
            requireCurrent(captured)
            if (result == null) _uiState.value = _uiState.value.copy(error = CurationError.NO_SUMMARY)
        }
    }

    fun showCandidateSource(id: String) {
        if (!available()) return
        val candidate = candidate(id) ?: return
        showSources { listOf(candidate.sourceMessageId) }
    }

    fun showSummarySources(id: String) {
        if (!available() || _uiState.value.summaries.none { it.id == id }) return
        showSources { repository.summarySources(id) }
    }

    private fun showSources(ids: suspend () -> List<String>) {
        runOperation(CurationOperation.SOURCES) { captured ->
            val sources = ids().map { id ->
                val message = chat.getMessageById(id)
                requireCurrent(captured)
                CurationSource(id, message?.takeIf {
                    it.id == id && it.isFromUser && it.status == MessageStatus.NORMAL
                }?.content)
            }
            requireCurrent(captured)
            _uiState.value = _uiState.value.copy(sources = sources)
        }
    }

    fun closeSources() { _uiState.value = _uiState.value.copy(sources = null) }
    fun dismissError() { if (synchronizeIdentity()) _uiState.value = _uiState.value.copy(error = null) }

    private fun eligible(message: Message, persona: String) = message.personaId == persona &&
        message.isFromUser && message.status == MessageStatus.NORMAL

    private fun candidate(id: String): MemoryCandidate? = _uiState.value.candidates.firstOrNull {
        it.id == id && it.status == "PENDING" && it.ownerId == identity.ownerId &&
            it.personaId == _uiState.value.personaId
    }

    private fun available() = synchronizeIdentity() && !_uiState.value.loading &&
        !_uiState.value.personaId.isNullOrBlank() && _uiState.value.operation == null
    private fun enabled() = available() && _uiState.value.preferences.enabled

    private fun synchronizeIdentity(): Boolean {
        if (identity === auth.identity.value) return true
        operationVersion++
        operationJob?.cancel()
        observationJob?.cancel()
        identity = auth.identity.value
        clearPrivateState()
        observe()
        return false
    }

    private fun clearPrivateState() {
        saved.keys().filter { it.startsWith(PRIVATE) }.forEach { saved.remove<Any>(it) }
        saved[OWNER] = identity.ownerId
        _uiState.value = MemoryCurationUiState(personaId = _uiState.value.personaId,
            personaName = _uiState.value.personaName)
    }

    private suspend fun requireCurrent(captured: AuthIdentity) {
        currentCoroutineContext().ensureActive()
        auth.requireIdentity(captured)
    }

    private fun observe() {
        observationJob?.cancel()
        val persona = _uiState.value.personaId?.takeIf { it.isNotBlank() }
        if (persona == null) {
            _uiState.value = _uiState.value.copy(loading = false, error = CurationError.INVALID_PERSONA)
            return
        }
        val captured = identity
        val version = ++observationVersion
        _uiState.value = _uiState.value.copy(loading = true)
        observationJob = launchCatching(block = {
            auth.withIdentity(captured) {
                val preferences = repository.preferences()
                val recent = chat.getRecentMessages(persona, 100).filter { eligible(it, persona) }
                val selected = _uiState.value.selectedMessageId?.let { chat.getMessageById(it) }
                requireCurrent(captured)
                _uiState.value = _uiState.value.copy(preferences = preferences, loading = false,
                    messages = (listOfNotNull(selected?.takeIf { eligible(it, persona) }) + recent)
                        .distinctBy { it.id })
                coroutineScope {
                    launch {
                        repository.observe(persona).collect { rows ->
                            requireCurrent(captured)
                            if (version != observationVersion) return@collect
                            _uiState.value = _uiState.value.copy(candidates = rows.filter {
                                it.ownerId == captured.ownerId && it.personaId == persona && it.status == "PENDING"
                            })
                            val draft = _uiState.value.draft
                            if (draft != null && candidate(draft.candidateId) == null) retainDraft(null)
                            else if (draft != null && !_uiState.value.conflictsLoaded && available()) {
                                loadConflicts(draft.candidateId)
                            }
                        }
                    }
                    launch {
                        memories.observe(persona).collect { rows ->
                            requireCurrent(captured)
                            if (version == observationVersion) {
                                _uiState.value = _uiState.value.copy(memories = rows.filter {
                                    it.ownerId == captured.ownerId && !it.deleted &&
                                        (it.personaId == null || it.personaId == persona)
                                })
                            }
                        }
                    }
                }
            }
        }, onError = { error ->
            if (error is CancellationException) throw error
            if (synchronizeIdentity() && identity === captured && version == observationVersion) {
                _uiState.value = _uiState.value.copy(loading = false, error = CurationError.LOAD)
            }
        })
    }

    private fun runOperation(operation: CurationOperation, block: suspend (AuthIdentity) -> Unit) {
        if (!available()) return
        val captured = identity
        val version = ++operationVersion
        _uiState.value = _uiState.value.copy(operation = operation, error = null)
        operationJob = launchCatching(block = {
            try {
                auth.withIdentity(captured) { block(captured) }
            } finally {
                if (synchronizeIdentity() && identity === captured && version == operationVersion) {
                    _uiState.value = _uiState.value.copy(operation = null)
                }
            }
        }, onError = { error ->
            if (error is CancellationException) throw error
            if (synchronizeIdentity() && identity === captured && version == operationVersion) {
                _uiState.value = _uiState.value.copy(error = if (operation == CurationOperation.SOURCES)
                    CurationError.SOURCE else CurationError.OPERATION)
            }
        })
    }

    private fun retainDraft(draft: CurationDraft?) {
        val values = mapOf("candidate" to draft?.candidateId, "content" to draft?.content,
            "type" to draft?.type?.name, "shared" to draft?.shared, "cloud" to draft?.cloudAllowed,
            "expiry" to draft?.expiryInput, "replace" to draft?.replaceId,
            "revision" to draft?.expectedRevision, "resolution" to draft?.resolutionChosen)
        saved[OWNER] = identity.ownerId
        values.forEach { (key, value) ->
            if (value == null) saved.remove<Any>(PRIVATE + key) else saved[PRIVATE + key] = value
        }
        _uiState.value = _uiState.value.copy(draft = draft)
        if (draft == null) _uiState.value = _uiState.value.copy(conflicts = emptyList(), conflictsLoaded = false)
    }

    private fun restoreDraft(): CurationDraft? {
        val id = saved.get<String>(PRIVATE + "candidate") ?: return null
        return CurationDraft(id, saved[PRIVATE + "content"] ?: "",
            MemoryType.entries.firstOrNull { it.name == saved.get<String>(PRIVATE + "type") } ?: MemoryType.FACT,
            shared = saved[PRIVATE + "shared"] ?: false, cloudAllowed = saved[PRIVATE + "cloud"] ?: false,
            expiryInput = saved[PRIVATE + "expiry"] ?: "", replaceId = saved[PRIVATE + "replace"],
            expectedRevision = saved[PRIVATE + "revision"], resolutionChosen = saved[PRIVATE + "resolution"] ?: false)
    }

    override fun onCleared() {
        operationJob?.cancel()
        observationJob?.cancel()
        super.onCleared()
    }

    private companion object {
        const val PRIVATE = "curation_private_"
        const val OWNER = "curation_owner"
        const val SOURCE = PRIVATE + "source"
        const val PERSONA = "curation_persona"
        const val NAME = "curation_name"
        const val INITIALIZED = "curation_initialized"
    }
}
