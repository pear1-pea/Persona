package com.example.persona.features.feed

import android.util.Log
import androidx.lifecycle.viewModelScope
import com.example.persona.core.auth.AuthManager
import com.example.persona.core.base.BaseViewModel
import com.example.persona.domain.model.Persona
import com.example.persona.domain.repository.PersonaRepository
import com.google.firebase.crashlytics.ktx.crashlytics
import com.google.firebase.ktx.Firebase
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import javax.inject.Inject

private const val TAG = "FeedViewModel"

@HiltViewModel
class FeedViewModel @Inject constructor(
    private val repository: PersonaRepository,
    private val authManager: AuthManager
) : BaseViewModel() {

    // Use StateFlow to manage UI state
    private val _uiState = MutableStateFlow<List<Persona>>(emptyList())
    val uiState: StateFlow<List<Persona>> = _uiState

    init {
        // Load personas regardless of login state
        loadPersonas()
    }

    private fun loadPersonas() {
        launchCatching(
            block = {
                val allPersonas = repository.getPersonas()
                _uiState.value = allPersonas
            },
            onError = { error ->
                Log.e(TAG, "Error loading personas", error)
                Firebase.crashlytics.recordException(error)
                emitError("Failed to load personas: ${error.localizedMessage}")
            }
        )
    }
}