package com.example.persona.core.ai.telemetry

import com.example.persona.core.ai.Backend
import com.example.persona.core.ai.GenerationErrorType
import com.example.persona.core.ai.StopReason
import com.google.gson.stream.JsonReader
import com.google.gson.stream.JsonToken
import com.google.gson.stream.JsonWriter
import java.io.IOException
import java.io.StringReader
import java.io.StringWriter

internal data class JournalState(
    val observedAtEpochMs: Long,
    val attempts: List<InferenceAttempt>,
    val clearEpochMs: Long? = null
)

/** Explicit wire names avoid Gson reflection, unsafe nulls and obfuscation-dependent schemas. */
internal object JournalJson {
    private val stringFields = setOf(
        "requestHash", "task", "backend", "modelFingerprint", "outcome", "errorType", "stopReason",
        "contractStatus", "trigger"
    )
    private val numberFields = setOf(
        "attempt", "startedAtEpochMs", "totalLatencyMs", "firstTokenLatencyMs", "outputTokens",
        "nativeInputTokens", "nativeOutputTokens", "prefillMicros", "decodeMicros", "samplingMicros"
    )
    private val nullableFields = setOf(
        "modelFingerprint", "firstTokenLatencyMs", "outputTokens", "nativeInputTokens",
        "nativeOutputTokens", "prefillMicros", "decodeMicros", "samplingMicros", "errorType", "stopReason"
    )
    private val integer = Regex("-?(0|[1-9][0-9]*)")

    fun read(json: String): JournalState {
        try {
            validateLexemes(json)
            return JsonReader(StringReader(json)).use { reader ->
                reader.isLenient = false
                var observed: Long? = null
                var attempts: List<InferenceAttempt>? = null
                var cleared: Long? = null
                val seen = mutableSetOf<String>()
                reader.beginObject()
                while (reader.hasNext()) {
                    val name = reader.nextName()
                    require(seen.add(name))
                    when (name) {
                        "observedAtEpochMs" -> observed = number(reader).toLong().also { require(it >= 0) }
                        "clearEpochMs" -> cleared = number(reader).toLong().also { require(it >= 0) }
                        "attempts" -> {
                            val rows = mutableListOf<InferenceAttempt>()
                            reader.beginArray()
                            while (reader.hasNext()) rows.add(readAttempt(reader))
                            reader.endArray()
                            attempts = rows
                        }
                        else -> throw IllegalArgumentException()
                    }
                }
                reader.endObject()
                require(reader.peek() == JsonToken.END_DOCUMENT)
                val observedTime = requireNotNull(observed)
                require(cleared == null || cleared <= observedTime)
                JournalState(observedTime, requireNotNull(attempts), cleared)
            }
        } catch (_: IOException) {
            throw IOException("Invalid journal document")
        } catch (_: IllegalArgumentException) {
            throw IOException("Invalid journal document")
        } catch (_: IllegalStateException) {
            throw IOException("Invalid journal document")
        }
    }

    private fun validateLexemes(json: String) {
        // Gson 2.8.x accepts mixed-case null and nonstandard string escapes even when non-lenient.
        var quoted = false
        var index = 0
        while (index < json.length) {
            val char = json[index++]
            if (!quoted) {
                if (char == '"') quoted = true
                else require(char in "{}[]:, \t\r\n-0123456789nul")
            } else when (char) {
                '"' -> quoted = false
                '\\' -> {
                    require(index < json.length)
                    val escaped = json[index++]
                    if (escaped == 'u') {
                        require(index + 4 <= json.length)
                        repeat(4) {
                            val digit = json[index++]
                            require(digit in '0'..'9' || digit in 'a'..'f' || digit in 'A'..'F')
                        }
                    } else require(escaped in "\"\\/bfnrt")
                }
                else -> require(char >= ' ')
            }
        }
        require(!quoted)
    }

    private fun readAttempt(reader: JsonReader): InferenceAttempt {
        val values = mutableMapOf<String, String?>()
        reader.beginObject()
        while (reader.hasNext()) {
            val name = reader.nextName()
            require(name in stringFields || name in numberFields)
            require(!values.containsKey(name))
            values[name] = if (reader.peek() == JsonToken.NULL) {
                require(name in nullableFields)
                reader.nextNull()
                null
            } else if (name in stringFields) {
                require(reader.peek() == JsonToken.STRING)
                reader.nextString()
            } else {
                number(reader)
            }
        }
        reader.endObject()
        fun required(name: String) = requireNotNull(values[name])
        return InferenceAttempt(
            requestHash = required("requestHash"), attempt = required("attempt").toInt(),
            task = AiTaskKind.valueOf(required("task")), backend = Backend.valueOf(required("backend")),
            modelFingerprint = values["modelFingerprint"],
            startedAtEpochMs = required("startedAtEpochMs").toLong(),
            totalLatencyMs = required("totalLatencyMs").toLong(),
            firstTokenLatencyMs = values["firstTokenLatencyMs"]?.toLong(),
            outputTokens = values["outputTokens"]?.toInt(),
            nativeInputTokens = values["nativeInputTokens"]?.toLong(),
            nativeOutputTokens = values["nativeOutputTokens"]?.toLong(),
            prefillMicros = values["prefillMicros"]?.toLong(),
            decodeMicros = values["decodeMicros"]?.toLong(),
            samplingMicros = values["samplingMicros"]?.toLong(),
            outcome = AttemptOutcome.valueOf(required("outcome")),
            errorType = values["errorType"]?.let(GenerationErrorType::valueOf),
            stopReason = values["stopReason"]?.let(StopReason::valueOf),
            contractStatus = values["contractStatus"]?.let(ContractStatus::valueOf) ?: ContractStatus.NOT_EVALUATED,
            trigger = values["trigger"]?.let(AttemptTrigger::valueOf) ?: AttemptTrigger.INITIAL
        )
    }

    private fun number(reader: JsonReader): String {
        require(reader.peek() == JsonToken.NUMBER)
        return reader.nextString().also { require(integer.matches(it)) }
    }

    fun write(state: JournalState): String = encode { writer ->
        writer.beginObject()
        writer.name("observedAtEpochMs").value(state.observedAtEpochMs)
        state.clearEpochMs?.let { writer.name("clearEpochMs").value(it) }
        writer.name("attempts")
        writeAttempts(writer, state.attempts)
        writer.endObject()
    }

    fun export(attempts: List<InferenceAttempt>): String = encode { writeAttempts(it, attempts) }

    private fun encode(write: (JsonWriter) -> Unit): String {
        val output = StringWriter()
        JsonWriter(output).use {
            it.serializeNulls = true
            write(it)
        }
        return output.toString()
    }

    private fun writeAttempts(writer: JsonWriter, attempts: List<InferenceAttempt>) {
        writer.beginArray()
        attempts.forEach {
            writer.beginObject()
            writer.name("requestHash").value(it.requestHash)
            writer.name("attempt").value(it.attempt)
            writer.name("task").value(it.task.name)
            writer.name("backend").value(it.backend.name)
            writer.name("modelFingerprint").value(it.modelFingerprint)
            writer.name("startedAtEpochMs").value(it.startedAtEpochMs)
            writer.name("totalLatencyMs").value(it.totalLatencyMs)
            writer.name("firstTokenLatencyMs").value(it.firstTokenLatencyMs)
            writer.name("outputTokens").value(it.outputTokens)
            writer.name("nativeInputTokens").value(it.nativeInputTokens)
            writer.name("nativeOutputTokens").value(it.nativeOutputTokens)
            writer.name("prefillMicros").value(it.prefillMicros)
            writer.name("decodeMicros").value(it.decodeMicros)
            writer.name("samplingMicros").value(it.samplingMicros)
            writer.name("outcome").value(it.outcome.name)
            writer.name("errorType").value(it.errorType?.name)
            writer.name("stopReason").value(it.stopReason?.name)
            writer.name("contractStatus").value(it.contractStatus.name)
            writer.name("trigger").value(it.trigger.name)
            writer.endObject()
        }
        writer.endArray()
    }
}
