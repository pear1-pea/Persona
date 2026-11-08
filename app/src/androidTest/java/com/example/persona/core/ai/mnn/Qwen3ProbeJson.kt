package com.example.persona.core.ai.mnn

import com.google.gson.JsonArray
import com.google.gson.JsonElement
import com.google.gson.JsonNull
import com.google.gson.JsonObject
import com.google.gson.JsonPrimitive
import com.google.gson.stream.JsonReader
import com.google.gson.stream.JsonToken
import java.io.StringReader
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.security.MessageDigest

internal object Qwen3ProbeJson {
    const val MAX_BYTES = 4 * 1024 * 1024
    private val number = Regex("-?(0|[1-9][0-9]*)(\\.[0-9]+)?([eE][+-]?[0-9]+)?")

    fun readFile(root: File, path: String): ByteArray = safeInput {
        val file = Qwen3ProbePackage.child(root, path)
        file.inputStream().use { input ->
            val output = ByteArrayOutputStream()
            val buffer = ByteArray(8192)
            var total = 0
            while (true) {
                val count = input.read(buffer, 0, minOf(buffer.size, MAX_BYTES + 1 - total))
                if (count < 0) break
                total += count
                require(total <= MAX_BYTES)
                output.write(buffer, 0, count)
            }
            require(file.absoluteFile == file.canonicalFile && file.isFile)
            output.toByteArray()
        }
    }

    private fun <T> safeInput(action: () -> T): T = try { action() }
        catch (_: Exception) { throw IllegalArgumentException("Qwen3ProbeInputFailure") }

    fun sha(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256")
        .digest(bytes).joinToString("") { "%02x".format(it) }

    fun parse(bytes: ByteArray): JsonElement = safeInput {
        require(bytes.size <= MAX_BYTES)
        val text = Charsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes)).toString()
        checkStrings(text)
        JsonReader(StringReader(text)).use { reader ->
            reader.isLenient = false
            val result = read(reader, 0)
            require(reader.peek() == JsonToken.END_DOCUMENT)
            result
        }
    }

    private fun read(reader: JsonReader, depth: Int): JsonElement {
        require(depth <= 20)
        return when (reader.peek()) {
            JsonToken.BEGIN_OBJECT -> JsonObject().apply {
                reader.beginObject()
                while (reader.hasNext()) {
                    val key = reader.nextName()
                    require(!has(key))
                    add(key, read(reader, depth + 1))
                }
                reader.endObject()
            }
            JsonToken.BEGIN_ARRAY -> JsonArray().apply {
                reader.beginArray()
                while (reader.hasNext()) add(read(reader, depth + 1))
                reader.endArray()
            }
            JsonToken.STRING -> JsonPrimitive(reader.nextString())
            JsonToken.NUMBER -> {
                val raw = reader.nextString()
                require(number.matches(raw))
                JsonPrimitive(LexicalNumber(raw))
            }
            JsonToken.BOOLEAN -> JsonPrimitive(reader.nextBoolean())
            JsonToken.NULL -> { reader.nextNull(); JsonNull.INSTANCE }
            else -> throw IllegalArgumentException()
        }
    }

    private class LexicalNumber(private val raw: String) : Number() {
        override fun toString() = raw
        override fun toByte() = raw.toByte()
        override fun toShort() = raw.toShort()
        override fun toInt() = raw.toInt()
        override fun toLong() = raw.toLong()
        override fun toFloat() = raw.toFloat()
        override fun toDouble() = raw.toDouble()
    }

    private fun checkStrings(text: String) {
        var quoted = false
        var index = 0
        while (index < text.length) {
            val char = text[index]
            if (quoted) {
                require(char >= ' ')
                if (char == '\\') {
                    require(++index < text.length)
                    val escape = text[index]
                    require(escape in "\"\\/bfnrtu")
                    if (escape == 'u') {
                        require(index + 4 < text.length)
                        require((1..4).all { text[index + it] in "0123456789abcdefABCDEF" })
                        index += 4
                    }
                } else if (char == '"') quoted = false
            } else {
                if (char == '"') quoted = true
                else if (char !in "{}[]:, \t\r\n") {
                    val start = index
                    while (index < text.length && text[index] !in "{}[]:,\" \t\r\n") index++
                    val literal = text.substring(start, index)
                    require(literal in setOf("null", "true", "false") || number.matches(literal))
                    index--
                }
            }
            index++
        }
        require(!quoted)
    }
}

internal fun JsonElement.fields(vararg keys: String): JsonObject {
    require(isJsonObject)
    return asJsonObject.also { require(it.keySet() == keys.toSet()) }
}

internal fun JsonElement.string(): String {
    require(isJsonPrimitive && asJsonPrimitive.isString)
    return asString.also { value ->
        var index = 0
        while (index < value.length) {
            val char = value[index]
            if (char.isHighSurrogate()) {
                require(index + 1 < value.length && value[++index].isLowSurrogate())
            } else require(!char.isLowSurrogate())
            index++
        }
    }
}

internal fun JsonElement.integer(): Int {
    require(isJsonPrimitive && asJsonPrimitive.isNumber)
    require(Regex("0|[1-9][0-9]*").matches(asString))
    return asString.toInt()
}

internal fun JsonElement.array(): List<JsonElement> {
    require(isJsonArray)
    return asJsonArray.toList()
}
