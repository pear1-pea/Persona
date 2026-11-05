package com.example.persona.features.memory

import com.example.persona.domain.model.Memory
import com.example.persona.domain.model.MemoryCandidate
import com.example.persona.domain.model.MemoryPreferences
import com.example.persona.domain.model.MemoryType
import com.example.persona.domain.model.Message
import java.text.ParsePosition
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

// Dates expire at midnight UTC. An empty input explicitly selects permanent storage.
fun parseMemoryExpiry(input: String): Long? {
    if (input.isEmpty()) return null
    require(Regex("\\d{4}-\\d{2}-\\d{2}").matches(input))
    val position = ParsePosition(0)
    val date = memoryDateFormat().parse(input, position)
    require(date != null && position.index == input.length)
    return date.time
}

fun formatMemoryExpiry(value: Long?): String = value?.let { memoryDateFormat().format(Date(it)) }.orEmpty()

private fun memoryDateFormat() = SimpleDateFormat("yyyy-MM-dd", Locale.ROOT).apply {
    isLenient = false
    timeZone = TimeZone.getTimeZone("UTC")
}

data class CurationDraft(
    val candidateId: String,
    val content: String,
    val type: MemoryType,
    val shared: Boolean = false,
    val cloudAllowed: Boolean = false,
    val expiryInput: String = "",
    val replaceId: String? = null,
    val expectedRevision: Int? = null,
    val resolutionChosen: Boolean = false
) {
    val valid: Boolean get() = content.isNotBlank() && content.length <= 400 &&
        runCatching { parseMemoryExpiry(expiryInput)?.let { it > System.currentTimeMillis() } ?: true }
            .getOrDefault(false)
}

enum class CurationOperation { CONFIGURING, SELECTING, EXTRACTING, CONFIRMING, IGNORING, SUMMARIZING, SOURCES }
enum class CurationError { INVALID_PERSONA, LOAD, OPERATION, INVALID_DRAFT, SOURCE, NO_SUMMARY }

data class CurationSource(val messageId: String, val content: String?)

data class MemoryCurationUiState(
    val personaId: String? = null,
    val personaName: String = "",
    val preferences: MemoryPreferences = MemoryPreferences(),
    val loading: Boolean = true,
    val messages: List<Message> = emptyList(),
    val selectedMessageId: String? = null,
    val candidates: List<MemoryCandidate> = emptyList(),
    val memories: List<Memory> = emptyList(),
    val draft: CurationDraft? = null,
    val conflicts: List<Memory> = emptyList(),
    val conflictsLoaded: Boolean = false,
    val sources: List<CurationSource>? = null,
    val operation: CurationOperation? = null,
    val error: CurationError? = null
) {
    val summaries: List<Memory> get() = memories.filter { it.id.startsWith("summary-") }
    val possibleConflicts: List<Memory> get() = (conflicts + memories).distinctBy { it.id }.filter {
        it.type == draft?.type && !it.deleted && !it.id.startsWith("summary-") &&
            (it.expiresAt == null || it.expiresAt > System.currentTimeMillis())
    }
}
