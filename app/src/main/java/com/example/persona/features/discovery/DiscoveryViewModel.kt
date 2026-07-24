package com.example.persona.features.discovery

import com.example.persona.core.base.BaseViewModel
import com.example.persona.domain.repository.PersonaRepository
import com.example.persona.domain.model.Persona
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.inject.Inject

@HiltViewModel
class DiscoveryViewModel @Inject constructor(
    private val repository: PersonaRepository
) : BaseViewModel() {

    private var allPersonas: List<Persona> = emptyList()

    private val _uiState = MutableStateFlow<List<Persona>>(emptyList())
    val uiState: StateFlow<List<Persona>> = _uiState.asStateFlow()

    private var currentCategory: String = "All"
    private var currentQuery: String = ""

    init {
        loadAllPersonas()
    }

    private fun loadAllPersonas() {
        launchCatching(block = {
            allPersonas = repository.getPersonas()
            applyFilters()
        })
    }


    fun filterByCategory(category: String) {
        currentCategory = category
        applyFilters()
    }

    fun searchPersonas(query: String) {
        currentQuery = query
        applyFilters()
    }

    // recompute results from allPersonas whenever filters change
    private fun applyFilters() {
        var result = allPersonas

        if (currentCategory != "All") {
            result = result.filter { persona ->
                persona.traits.any { it.equals(currentCategory, ignoreCase = true) }
            }
        }

        if (currentQuery.isNotEmpty()) {
            result = result.filter { persona ->
                persona.name.contains(currentQuery, ignoreCase = true) ||
                        persona.backstory.contains(currentQuery, ignoreCase = true)
            }
        }

        _uiState.value = result
    }
}