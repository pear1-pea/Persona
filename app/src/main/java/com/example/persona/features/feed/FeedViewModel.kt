package com.example.persona.features.feed

import com.example.persona.domain.repository.PersonaRepository
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.persona.core.base.BaseViewModel
import com.example.persona.domain.model.Persona
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class FeedViewModel @Inject constructor(
    private val repository: PersonaRepository
) : BaseViewModel() {

    // Use StateFlow to manage UI state
    private val _uiState = MutableStateFlow<List<Persona>>(emptyList())
    val uiState: StateFlow<List<Persona>> = _uiState

    init {
        loadPersonas()
    }

    private fun loadPersonas() {
        launchCatching {
            _uiState.value = repository.getPersonas()
        }
    }
}