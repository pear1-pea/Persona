package com.example.persona.features.memory

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.viewModelScope
import com.example.persona.core.auth.AuthIdentity
import com.example.persona.core.auth.AuthManager
import com.example.persona.core.base.BaseViewModel
import com.example.persona.domain.model.Memory
import com.example.persona.domain.model.MemoryType
import com.example.persona.domain.model.MessageStatus
import com.example.persona.domain.repository.ChatRepository
import com.example.persona.domain.repository.MemoryRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class MemoryViewModel @Inject constructor(
    private val repository: MemoryRepository,
    private val authManager: AuthManager,
    private val savedStateHandle: SavedStateHandle,
    private val chatRepository: ChatRepository
) : BaseViewModel() {
    private var identity = authManager.identity.value
    private var operationJob: Job? = null
    private var observeJob: Job? = null
    private var sourceJob: Job? = null
    private var sourceVersion = 0L
    private var draftVersion = 0L
    private var observationVersion = 0L
    private val _uiState = MutableStateFlow(MemoryUiState(
        personaId = savedStateHandle[PERSONA],
        personaName = savedStateHandle[NAME] ?: "",
        draft = restoreDraft()
    ))
    val uiState = _uiState.asStateFlow()

    init {
        if (savedStateHandle.get<String>(OWNER) != identity.ownerId) retainDraft(null)
        if (savedStateHandle.get<Boolean>(INITIALIZED) == true) observeMemories()
        viewModelScope.launch {
            authManager.identity.collect { synchronizeIdentity() }
        }
    }

    fun initialize(
        personaId: String?,
        personaName: String,
        sourceMessageId: String? = null
    ) {
        if (!synchronizeIdentity() || savedStateHandle.get<Boolean>(INITIALIZED) == true) return
        savedStateHandle[INITIALIZED] = true
        savedStateHandle[PERSONA] = personaId
        savedStateHandle[NAME] = personaName
        _uiState.value = _uiState.value.copy(personaId = personaId, personaName = personaName)
        if (personaId.isNullOrBlank()) {
            _uiState.value = _uiState.value.copy(error = MemoryError.INVALID_PERSONA)
            return
        }
        if (!sourceMessageId.isNullOrBlank()) prefillSource(personaId, sourceMessageId)
        observeMemories()
    }

    private fun prefillSource(personaId: String, sourceMessageId: String) {
        retainDraft(MemoryDraft(sourceMessageId = sourceMessageId))
        val captured = identity
        val version = draftVersion
        sourceJob = launchCatching(block = {
            authManager.withIdentity(captured) {
                val message = chatRepository.getMessageById(sourceMessageId)
                requireCurrent(captured)
                check(message?.isFromUser == true && message.status == MessageStatus.NORMAL &&
                    message.personaId == personaId) { "Source message is unavailable" }
                if (draftVersion == version) {
                    retainDraft(MemoryDraft(content = message.content, sourceMessageId = sourceMessageId))
                }
            }
        }, onError = { error ->
            if (error is CancellationException) throw error
            if (synchronizeIdentity() && identity === captured && draftVersion == version) {
                _uiState.value = _uiState.value.copy(error = MemoryError.SOURCE_FAILED)
            }
        })
    }

    fun newMemory() {
        if (!available() || _uiState.value.pendingDelete != null || _uiState.value.draft != null) return
        retainDraft(MemoryDraft())
    }

    fun editMemory(id: String) {
        if (!available() || _uiState.value.pendingDelete != null || _uiState.value.draft != null) return
        val memory = visibleMemory(id) ?: return
        retainDraft(MemoryDraft(
            id = memory.id,
            revision = memory.revision,
            type = memory.type,
            content = memory.content,
            shared = memory.personaId == null,
            cloudAllowed = memory.cloudAllowed,
            sourceMessageId = memory.sourceMessageId,
            expiresAt = memory.expiresAt
        ))
    }

    fun updateDraft(draft: MemoryDraft) {
        if (!available()) return
        val current = _uiState.value.draft ?: return
        // Identity, provenance and CAS metadata cannot be changed by form input.
        retainDraft(current.copy(type = draft.type, content = draft.content,
            shared = draft.shared, cloudAllowed = draft.cloudAllowed))
    }

    fun cancelDraft() {
        if (!available()) return
        retainDraft(null)
    }

    fun save() {
        if (!available()) return
        val draft = _uiState.value.draft ?: return
        if (!draft.valid) {
            _uiState.value = _uiState.value.copy(error = MemoryError.INVALID_CONTENT)
            return
        }
        val personaId = _uiState.value.personaId ?: return
        runOperation(MemoryOperation.SAVING, MemoryError.SAVE_FAILED) {
            repository.save(
                personaId = if (draft.shared) null else personaId,
                type = draft.type,
                content = draft.content,
                cloudAllowed = draft.cloudAllowed,
                sourceMessageId = draft.sourceMessageId,
                id = draft.id,
                expectedRevision = draft.revision,
                expiresAt = draft.expiresAt
            )
            requireCurrent(it)
            retainDraft(null)
        }
    }

    fun requestDelete(id: String) {
        if (!available() || _uiState.value.draft != null || _uiState.value.pendingDelete != null) return
        val memory = visibleMemory(id) ?: return
        _uiState.value = _uiState.value.copy(pendingDelete = memory)
    }

    fun cancelDelete() {
        if (!available()) return
        _uiState.value = _uiState.value.copy(pendingDelete = null)
    }

    fun confirmDelete(target: Memory) {
        if (!available() || _uiState.value.pendingDelete != target) return
        runOperation(MemoryOperation.DELETING, MemoryError.DELETE_FAILED) {
            repository.delete(target.id, target.revision)
            requireCurrent(it)
            _uiState.value = _uiState.value.copy(pendingDelete = null)
        }
    }

    fun acknowledgeError() {
        if (!synchronizeIdentity()) return
        _uiState.value = _uiState.value.copy(error = null)
    }

    fun retryLoading() {
        if (!available()) return
        observeMemories()
    }

    fun showSource(messageId: String) {
        if (!available() || _uiState.value.pendingDelete != null) return
        val draft = _uiState.value.draft
        if (draft?.sourceMessageId != messageId &&
            _uiState.value.memories.none { it.sourceMessageId == messageId }) return
        closeSource()
        val captured = identity
        val version = sourceVersion
        _uiState.value = _uiState.value.copy(source = MemorySource(messageId, loading = true))
        sourceJob = launchCatching(block = {
            authManager.withIdentity(captured) {
                val message = chatRepository.getMessageById(messageId)?.takeIf {
                    it.id == messageId && it.isFromUser &&
                        it.status == MessageStatus.NORMAL
                }
                requireCurrent(captured)
                if (version == sourceVersion) {
                    _uiState.value = _uiState.value.copy(source = MemorySource(messageId, message?.content))
                }
            }
        }, onError = { error ->
            if (error is CancellationException) throw error
            if (synchronizeIdentity() && identity === captured && version == sourceVersion) {
                _uiState.value = _uiState.value.copy(source = MemorySource(messageId),
                    error = MemoryError.SOURCE_FAILED)
            }
        })
    }

    fun closeSource() {
        sourceJob?.cancel()
        sourceVersion++
        _uiState.value = _uiState.value.copy(source = null)
    }

    private fun visibleMemory(id: String): Memory? = _uiState.value.memories.firstOrNull {
        it.id == id && it.ownerId == identity.ownerId && !it.deleted &&
            (it.personaId == null || it.personaId == _uiState.value.personaId)
    }

    private fun available(): Boolean = synchronizeIdentity() &&
        !_uiState.value.personaId.isNullOrBlank() && _uiState.value.operation == null

    private fun synchronizeIdentity(): Boolean {
        val current = authManager.identity.value
        if (identity === current) return true
        operationJob?.cancel()
        observeJob?.cancel()
        closeSource()
        identity = current
        _uiState.value = MemoryUiState(
            personaId = _uiState.value.personaId,
            personaName = _uiState.value.personaName
        )
        retainDraft(null)
        observeMemories()
        return false
    }

    private suspend fun requireCurrent(captured: AuthIdentity) {
        currentCoroutineContext().ensureActive()
        authManager.requireIdentity(captured)
    }

    private fun observeMemories() {
        observeJob?.cancel()
        val version = ++observationVersion
        val captured = identity
        val personaId = _uiState.value.personaId?.takeIf { it.isNotBlank() } ?: return
        _uiState.value = _uiState.value.copy(loading = true)
        observeJob = launchCatching(block = {
            authManager.withIdentity(captured) {
                repository.observe(personaId).collect { memories ->
                    requireCurrent(captured)
                    if (version == observationVersion) {
                        _uiState.value = _uiState.value.copy(
                            memories = memories.filter { it.ownerId == captured.ownerId && !it.deleted &&
                                (it.personaId == null || it.personaId == personaId) },
                            loading = false
                        )
                    }
                }
            }
        }, onError = { error ->
            if (error is CancellationException) throw error
            if (synchronizeIdentity() && identity === captured && version == observationVersion) {
                _uiState.value = _uiState.value.copy(loading = false, error = MemoryError.LOAD_FAILED)
            }
        })
    }

    private fun runOperation(
        operation: MemoryOperation,
        failure: MemoryError,
        block: suspend (AuthIdentity) -> Unit
    ) {
        if (!available()) return
        val captured = identity
        _uiState.value = _uiState.value.copy(operation = operation)
        operationJob = launchCatching(block = {
            try {
                authManager.withIdentity(captured) { block(captured) }
            } finally {
                if (synchronizeIdentity() && identity === captured) {
                    _uiState.value = _uiState.value.copy(operation = null)
                }
            }
        }, onError = { error ->
            if (error is CancellationException) throw error
            if (synchronizeIdentity() && identity === captured) {
                _uiState.value = _uiState.value.copy(error = failure)
            }
        })
    }

    private fun retainDraft(draft: MemoryDraft?) {
        draftVersion++
        savedStateHandle[OWNER] = identity.ownerId
        savedStateHandle[DRAFT] = draft != null
        val values = mapOf(
            "id" to draft?.id, "revision" to draft?.revision, "type" to draft?.type?.name,
            "content" to draft?.content, "shared" to draft?.shared,
            "cloud" to draft?.cloudAllowed, "source" to draft?.sourceMessageId,
            "expires" to draft?.expiresAt
        )
        values.forEach { (key, value) ->
            if (value == null) savedStateHandle.remove<Any>(PREFIX + key)
            else savedStateHandle[PREFIX + key] = value
        }
        _uiState.value = _uiState.value.copy(draft = draft)
    }

    private fun restoreDraft(): MemoryDraft? {
        if (savedStateHandle.get<String>(OWNER) != identity.ownerId ||
            savedStateHandle.get<Boolean>(DRAFT) != true) return null
        val type = savedStateHandle.get<String>(PREFIX + "type")
        return MemoryDraft(
            id = savedStateHandle[PREFIX + "id"],
            revision = savedStateHandle[PREFIX + "revision"],
            type = MemoryType.entries.firstOrNull { it.name == type } ?: MemoryType.PREFERENCE,
            content = savedStateHandle[PREFIX + "content"] ?: "",
            shared = savedStateHandle[PREFIX + "shared"] ?: false,
            cloudAllowed = savedStateHandle[PREFIX + "cloud"] ?: false,
            sourceMessageId = savedStateHandle[PREFIX + "source"],
            expiresAt = savedStateHandle[PREFIX + "expires"]
        )
    }

    private companion object {
        const val PREFIX = "memory_draft_"
        const val DRAFT = "memory_has_draft"
        const val OWNER = "memory_owner"
        const val PERSONA = "memory_persona"
        const val NAME = "memory_persona_name"
        const val INITIALIZED = "memory_initialized"
    }
}
