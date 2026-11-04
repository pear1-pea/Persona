package com.example.persona.features.creation

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.viewModelScope
import com.example.persona.core.auth.AuthIdentity
import com.example.persona.core.auth.AuthManager
import com.example.persona.core.base.BaseViewModel
import com.example.persona.data.repository.CloudChatRepository
import com.example.persona.domain.repository.PersonaRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import javax.inject.Inject

data class CreationDraft(
    val editingId: String? = null,
    val name: String = "",
    val story: String = "",
    val traitOptions: List<String> = emptyList(),
    val traits: List<String> = emptyList(),
    val isPublic: Boolean = true
)

enum class CreationOperation { GENERATING, SAVING, DELETING }
enum class CreationError { INVALID_PROFILE, GENERATION_FAILED, GENERATION_TIMEOUT, SAVE_FAILED, DELETE_FAILED }

data class CreationUiState(
    val draft: CreationDraft? = null,
    val operation: CreationOperation? = null,
    val completed: Boolean = false,
    val error: CreationError? = null
)

@HiltViewModel
class CreatePersonaViewModel @Inject constructor(
    private val repository: PersonaRepository,
    private val cloudRepository: CloudChatRepository,
    private val authManager: AuthManager,
    private val savedStateHandle: SavedStateHandle = SavedStateHandle()
) : BaseViewModel() {
    private var identity = authManager.identity.value
    private var operationJob: Job? = null
    private val _uiState = MutableStateFlow(CreationUiState(
        draft = restoreDraft(),
        completed = savedStateHandle.get<String>("creation_owner") == identity.ownerId &&
            savedStateHandle.get<Boolean>("creation_completed") == true
    ))
    val uiState = _uiState.asStateFlow()

    init {
        val restoredOwner = savedStateHandle.get<String>("creation_owner")
        if (restoredOwner != null && restoredOwner != identity.ownerId) clearDraft()
        viewModelScope.launch {
            authManager.identity.collect { synchronizeIdentity() }
        }
    }

    private fun synchronizeIdentity(): Boolean {
        val current = authManager.identity.value
        if (current === identity) return true
        operationJob?.cancel()
        identity = current
        clearDraft()
        return false
    }

    private fun clearDraft() {
        savedStateHandle.keys().filter { it.startsWith("creation_") }.forEach {
            savedStateHandle.remove<Any>(it)
        }
        _uiState.value = CreationUiState(draft = CreationDraft())
        retainDraft(CreationDraft())
    }

    fun initializeDraft(draft: CreationDraft) {
        if (synchronizeIdentity() && _uiState.value.draft == null) updateDraft(draft)
    }

    fun updateDraft(draft: CreationDraft) {
        if (!isAvailable()) return
        retainDraft(draft)
    }

    private fun retainDraft(draft: CreationDraft) {
        savedStateHandle["creation_owner"] = identity.ownerId
        savedStateHandle["creation_id"] = draft.editingId
        savedStateHandle["creation_name"] = draft.name
        savedStateHandle["creation_story"] = draft.story
        savedStateHandle["creation_options"] = ArrayList(draft.traitOptions)
        savedStateHandle["creation_traits"] = ArrayList(draft.traits)
        savedStateHandle["creation_public"] = draft.isPublic
        _uiState.value = _uiState.value.copy(draft = draft)
    }

    private fun restoreDraft(): CreationDraft? {
        if (!savedStateHandle.contains("creation_name")) return null
        if (savedStateHandle.get<String>("creation_owner") != identity.ownerId) return CreationDraft()
        return CreationDraft(
            editingId = savedStateHandle["creation_id"],
            name = savedStateHandle["creation_name"] ?: "",
            story = savedStateHandle["creation_story"] ?: "",
            traitOptions = savedStateHandle.get<ArrayList<String>>("creation_options")?.toList().orEmpty(),
            traits = savedStateHandle.get<ArrayList<String>>("creation_traits")?.toList().orEmpty(),
            isPublic = savedStateHandle["creation_public"] ?: true
        )
    }

    fun acknowledgeError() {
        _uiState.value = _uiState.value.copy(error = null)
    }

    fun generateAI(keywords: String) {
        runOperation(CreationOperation.GENERATING, CreationError.GENERATION_FAILED) { captured ->
            val profile = withTimeoutOrNull(60000L) {
                generateValidatedProfile(keywords, captured)
            } ?: throw GenerationTimeoutException()
            authManager.requireIdentity(captured)
            currentCoroutineContext().ensureActive()
            val draft = _uiState.value.draft ?: CreationDraft()
            retainDraft(draft.copy(
                name = profile.name,
                story = profile.backstory,
                traitOptions = profile.traits,
                traits = profile.traits
            ))
        }
    }

    private suspend fun generateValidatedProfile(keywords: String, captured: AuthIdentity): GeneratedPersonaDto {
        val boundedKeywords = keywords.take(2000)
        val constraints = "Return only a JSON object with exactly name (nonblank string, max 120 characters), " +
            "backstory (nonblank string, max 12000 characters), traits (1-20 nonblank strings, max 80 characters each)."
        repeat(2) { attempt ->
            authManager.requireIdentity(captured)
            currentCoroutineContext().ensureActive()
            val request = if (attempt == 0) boundedKeywords else
                "The previous result was invalid. Generate a replacement. $constraints Theme: $boundedKeywords"
            val raw = cloudRepository.generatePersonaProfile(request)
            authManager.requireIdentity(captured)
            currentCoroutineContext().ensureActive()
            try {
                return PersonaCreationValidator.parse(raw)
            } catch (error: InvalidPersonaProfileException) {
                if (attempt == 1) throw error
            }
        }
        throw InvalidPersonaProfileException()
    }

    fun savePersona(id: String?, name: String, story: String, traits: List<String>, isPublic: Boolean) {
        if (!isAvailable()) return
        val profile = try {
            PersonaCreationValidator.validate(name, story, traits)
        } catch (error: InvalidPersonaProfileException) {
            _uiState.value = _uiState.value.copy(error = CreationError.INVALID_PROFILE)
            return
        }
        runOperation(CreationOperation.SAVING, CreationError.SAVE_FAILED) { captured ->
            if (id == null) {
                repository.addPersona(profile.name, profile.traits, profile.backstory, isPublic)
            } else {
                repository.updatePersona(id, profile.name, profile.traits, profile.backstory, isPublic)
            }
            complete(captured)
        }
    }

    fun deletePersona(id: String) {
        runOperation(CreationOperation.DELETING, CreationError.DELETE_FAILED) { captured ->
            repository.deletePersona(id)
            complete(captured)
        }
    }

    private suspend fun complete(captured: AuthIdentity) {
        authManager.requireIdentity(captured)
        currentCoroutineContext().ensureActive()
        savedStateHandle["creation_owner"] = captured.ownerId
        savedStateHandle["creation_completed"] = true
        _uiState.value = _uiState.value.copy(completed = true)
    }

    private fun isAvailable(): Boolean = synchronizeIdentity() &&
        _uiState.value.operation == null && !_uiState.value.completed

    private fun runOperation(
        operation: CreationOperation,
        failure: CreationError,
        block: suspend (AuthIdentity) -> Unit
    ) {
        if (!isAvailable()) return
        val captured = identity
        _uiState.value = _uiState.value.copy(operation = operation, error = null)
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
                val reason = when (error) {
                    is InvalidPersonaProfileException -> CreationError.INVALID_PROFILE
                    is GenerationTimeoutException -> CreationError.GENERATION_TIMEOUT
                    else -> failure
                }
                _uiState.value = _uiState.value.copy(error = reason)
            }
        })
    }

    private class GenerationTimeoutException : IllegalStateException()
}
