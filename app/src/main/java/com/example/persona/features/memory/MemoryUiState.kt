package com.example.persona.features.memory

import com.example.persona.domain.model.Memory
import com.example.persona.domain.model.MemoryType

data class MemoryDraft(
    val id: String? = null,
    val revision: Int? = null,
    val type: MemoryType = MemoryType.PREFERENCE,
    val content: String = "",
    val shared: Boolean = false,
    val cloudAllowed: Boolean = false,
    val sourceMessageId: String? = null,
    val expiresAt: Long? = null,
    val expiryInput: String? = null
) {
    val valid: Boolean get() = content.isNotBlank() && content.length in 1..400 &&
        (expiryInput?.let { input -> runCatching {
            parseMemoryExpiry(input)?.let { it > System.currentTimeMillis() } ?: true
        }.getOrDefault(false) } ?: true)
}

enum class MemoryOperation { SAVING, DELETING }
enum class MemoryError { INVALID_PERSONA, INVALID_CONTENT, LOAD_FAILED, SAVE_FAILED, DELETE_FAILED, SOURCE_FAILED }

data class MemorySource(val messageId: String, val content: String? = null, val loading: Boolean = false)

data class MemoryUiState(
    val personaId: String? = null,
    val personaName: String = "",
    val memories: List<Memory> = emptyList(),
    val draft: MemoryDraft? = null,
    val pendingDelete: Memory? = null,
    val source: MemorySource? = null,
    val operation: MemoryOperation? = null,
    val loading: Boolean = false,
    val error: MemoryError? = null
)
