package com.example.persona.features.discovery

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
class DiscoveryViewModel @Inject constructor(
    private val repository: PersonaRepository,
    private val authManager: AuthManager
) : BaseViewModel() {

    private var allPersonas: List<Persona> = emptyList()

    private val _uiState = MutableStateFlow<List<Persona>>(emptyList())
    val uiState: StateFlow<List<Persona>> = _uiState.asStateFlow()

    private val _categories = MutableStateFlow<List<String>>(emptyList())
    val categories: StateFlow<List<String>> = _categories.asStateFlow()

    var selectedCategory: String? = null
        private set
    var searchQuery: String = ""
        private set
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
            allPersonas = emptyList()
            _categories.value = emptyList()
            applyFilters()
        }
        refreshJob?.cancel()
        refreshJob = launchCatching(
            block = {
                authManager.withIdentity(identity) {
                    val personas = repository.getPersonas()
                    currentCoroutineContext().ensureActive()
                    authManager.requireIdentity(identity)
                    if (version == refreshVersion) {
                        allPersonas = personas
                        _categories.value = personas.flatMap { it.traits }
                            .map { it.trim() }
                            .filter { it.isNotEmpty() }
                            .distinctBy { it.lowercase() }
                            .sortedWith(String.CASE_INSENSITIVE_ORDER)
                        applyFilters()
                    }
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

    fun filterByCategory(category: String?) {
        selectedCategory = category
        applyFilters()
    }

    fun searchPersonas(query: String) {
        searchQuery = query
        applyFilters()
    }

    private fun applyFilters() {
        // A filter event can arrive before the identity collector resumes.
        if (listIdentity !== authManager.identity.value) {
            allPersonas = emptyList()
            _categories.value = emptyList()
        }
        var result = allPersonas

        val category = selectedCategory
        if (category != null) {
            result = result.filter { persona ->
                persona.traits.any { it.trim().equals(category, ignoreCase = true) }
            }
        }

        if (searchQuery.isNotEmpty()) {
            result = result.filter { persona ->
                persona.name.contains(searchQuery, ignoreCase = true) ||
                        persona.backstory.contains(searchQuery, ignoreCase = true)
            }
        }

        _uiState.value = result
    }
}
