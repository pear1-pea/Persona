package com.example.persona.data.remote

import java.io.BufferedReader
import java.io.IOException

internal class SseDataReader(private val reader: BufferedReader) {
    fun readData(): String? {
        val data = StringBuilder()
        var hasData = false
        while (true) {
            val line = reader.readLine() ?: return if (hasData) data.toString() else null
            if (line.isEmpty()) {
                if (hasData) return data.toString()
                continue
            }
            if (!line.startsWith("data:")) continue
            val value = line.substring(5).removePrefix(" ")
            if (data.length + value.length + 1 > 1_048_576) throw IOException("SSE event exceeds limit")
            if (hasData) data.append('\n')
            data.append(value)
            hasData = true
        }
    }
}
