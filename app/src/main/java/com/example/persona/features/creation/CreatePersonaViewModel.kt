package com.example.persona.features.creation

import android.util.Log
import androidx.lifecycle.viewModelScope
import com.example.persona.core.base.BaseViewModel
import com.example.persona.data.repository.CloudChatRepository
import com.example.persona.domain.repository.PersonaRepository
import com.google.gson.Gson
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

sealed class CreationEvent {
    object Loading : CreationEvent()
    data class Generated(val name: String, val story: String, val traits: List<String>) : CreationEvent()
    object Success : CreationEvent()
    object Error : CreationEvent()
}

@HiltViewModel
class CreatePersonaViewModel @Inject constructor(
    private val repository: PersonaRepository,
    private val cloudRepository: CloudChatRepository
) : BaseViewModel() {

    private val _event = MutableSharedFlow<CreationEvent>()
    val event = _event.asSharedFlow()

    private val gson = Gson()

    // AI auto-generate
    fun generateAI(keywords: String) {
        launchCatching(block = {
            _event.emit(CreationEvent.Loading)

            try {
                // Call cloud API
                val jsonString = cloudRepository.generatePersonaProfile(keywords)

                Log.d("CreatePersonaVM", "AI Raw Response: $jsonString")

                val cleanJson = jsonString
                    .replace("```json", "")
                    .replace("```", "")
                    .trim()

                val dto = gson.fromJson(cleanJson, GeneratedPersonaDto::class.java)

                _event.emit(CreationEvent.Generated(dto.name, dto.backstory, dto.traits))
            }catch (e:Exception){
                _event.emit(CreationEvent.Error)
                throw e
            }
        }
        )
    }

    // Save persona
    fun createPersona(name: String, story: String, traits: List<String>) {
        if (name.isBlank()) return

        launchCatching (block = {
            repository.addPersona(name, traits, story)
            _event.emit(CreationEvent.Success)
        }
        )
    }
}