package com.example.persona.features.feed

import androidx.lifecycle.viewModelScope
import com.example.persona.core.auth.AuthManager
import com.example.persona.core.base.BaseViewModel
import com.example.persona.domain.model.Persona
import com.example.persona.domain.repository.PersonaRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class FeedViewModel @Inject constructor(
    private val repository: PersonaRepository,
    private val authManager: AuthManager
) : BaseViewModel() {

    private val _uiState = MutableStateFlow<List<Persona>>(emptyList())
    val uiState: StateFlow<List<Persona>> = _uiState.asStateFlow()

    private var refreshJob: Job? = null
    private var refreshVersion = 0L
    private var listIdentity = authManager.identity.value

    init {
        viewModelScope.launch(start = CoroutineStart.UNDISPATCHED) {
            authManager.identity.collect {
                refresh()
            }
        }
    }

    fun refresh() {
        val version = ++refreshVersion
        val identity = authManager.identity.value
        if (listIdentity !== identity) {
            listIdentity = identity
            _uiState.value = emptyList()
        }
        refreshJob?.cancel()
        refreshJob = launchCatching(
            block = {
                authManager.withIdentity(identity) {
                    val personas = repository.getPersonas()
                    currentCoroutineContext().ensureActive()
                    authManager.requireIdentity(identity)
                    if (version == refreshVersion) _uiState.value = personas
                }
            },
            onError = { error ->
                if (error is CancellationException) throw error
                if (version == refreshVersion && authManager.identity.value === identity) {
                    throw error
                }
            }
        )
    }
}
