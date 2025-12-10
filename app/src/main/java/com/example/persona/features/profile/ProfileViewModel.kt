package com.example.persona.features.profile

import com.example.persona.core.base.BaseViewModel
import com.example.persona.domain.model.Persona
import com.example.persona.domain.repository.PersonaRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.inject.Inject

@HiltViewModel
class ProfileViewModel @Inject constructor(
    private val repository: PersonaRepository
) : BaseViewModel() {

    private val _myPersonas = MutableStateFlow<List<Persona>>(emptyList())
    val myPersonas: StateFlow<List<Persona>> = _myPersonas.asStateFlow()

    init {
        loadMyPersonas()
    }

    fun loadMyPersonas() {
        launchCatching(block = {
            val newList = repository.getMyPersonas()
            _myPersonas.value = newList
        })
    }
}