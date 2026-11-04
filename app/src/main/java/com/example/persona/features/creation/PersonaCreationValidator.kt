package com.example.persona.features.creation

import com.google.gson.stream.JsonReader
import com.google.gson.stream.JsonToken
import java.io.IOException
import java.io.StringReader

class InvalidPersonaProfileException : IllegalArgumentException("Invalid persona profile")

object PersonaCreationValidator {
    const val MAX_NAME = 120
    const val MAX_BACKSTORY = 12000
    const val MAX_TRAITS = 20
    const val MAX_TRAIT = 80
    private const val MAX_JSON = 100000

    fun validate(name: String, backstory: String, traits: List<String>): GeneratedPersonaDto {
        val result = GeneratedPersonaDto(name.trim(), backstory.trim(), traits.map { it.trim() })
        if (result.name.length !in 1..MAX_NAME || result.backstory.length !in 1..MAX_BACKSTORY ||
            result.traits.size !in 1..MAX_TRAITS || result.traits.any { it.length !in 1..MAX_TRAIT }
        ) throw InvalidPersonaProfileException()
        return result
    }

    fun parse(raw: String): GeneratedPersonaDto {
        if (raw.length > MAX_JSON) throw InvalidPersonaProfileException()
        val text = raw.trim().let { value ->
            val prefix = listOf("```json\n", "```json\r\n", "```\n", "```\r\n")
                .firstOrNull { value.startsWith(it) }
            if (prefix != null && value.endsWith("```")) value.removePrefix(prefix).dropLast(3).trim() else value
        }
        checkStringSyntax(text)
        try {
            JsonReader(StringReader(text)).use { reader ->
                reader.isLenient = false
                reader.beginObject()
                val seen = mutableSetOf<String>()
                var name: String? = null
                var backstory: String? = null
                var traits: List<String>? = null
                while (reader.hasNext()) {
                    val key = reader.nextName()
                    if (!seen.add(key)) throw InvalidPersonaProfileException()
                    when (key) {
                        "name" -> name = reader.readString()
                        "backstory" -> backstory = reader.readString()
                        "traits" -> {
                            val values = mutableListOf<String>()
                            reader.beginArray()
                            while (reader.hasNext()) {
                                if (values.size == MAX_TRAITS) throw InvalidPersonaProfileException()
                                values.add(reader.readString())
                            }
                            reader.endArray()
                            traits = values
                        }
                        else -> throw InvalidPersonaProfileException()
                    }
                }
                reader.endObject()
                if (reader.peek() != JsonToken.END_DOCUMENT) throw InvalidPersonaProfileException()
                return validate(
                    name ?: throw InvalidPersonaProfileException(),
                    backstory ?: throw InvalidPersonaProfileException(),
                    traits ?: throw InvalidPersonaProfileException()
                )
            }
        } catch (e: IOException) {
            throw InvalidPersonaProfileException()
        } catch (e: IllegalStateException) {
            throw InvalidPersonaProfileException()
        } catch (e: NumberFormatException) {
            throw InvalidPersonaProfileException()
        }
    }

    private fun JsonReader.readString(): String {
        if (peek() != JsonToken.STRING) throw InvalidPersonaProfileException()
        return nextString()
    }

    // Gson 2.10 legacy strict mode still accepts raw control characters and escaped apostrophes.
    private fun checkStringSyntax(text: String) {
        var quoted = false
        var escaped = false
        for (char in text) {
            if (quoted && char < ' ') throw InvalidPersonaProfileException()
            if (escaped) {
                if (char !in "\"\\/bfnrtu") throw InvalidPersonaProfileException()
                escaped = false
            } else if (quoted && char == '\\') {
                escaped = true
            } else if (char == '"') {
                quoted = !quoted
            }
        }
    }
}
