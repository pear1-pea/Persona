package com.example.persona.core.ai.prompt

interface Tokenizer {
    fun countTokens(text: String): Int

    fun truncateToTokens(text: String, maxTokens: Int): String {
        if (text.isEmpty() || maxTokens <= 0) return ""
        if (countTokens(text) <= maxTokens) return text

        var low = 0
        var high = text.codePointCount(0, text.length)
        var best = 0
        while (low <= high) {
            val middle = (low + high) ushr 1
            val end = text.offsetByCodePoints(0, middle)
            if (countTokens(text.substring(0, end)) <= maxTokens) {
                best = middle
                low = middle + 1
            } else {
                high = middle - 1
            }
        }
        return text.substring(0, text.offsetByCodePoints(0, best))
    }
}

object ConservativeTokenizer : Tokenizer {
    override fun countTokens(text: String): Int {
        var tokenCount = 0
        var asciiRunLength = 0
        var index = 0

        fun flushAsciiRun() {
            if (asciiRunLength > 0) {
                tokenCount += (asciiRunLength + ASCII_CHARS_PER_TOKEN - 1) / ASCII_CHARS_PER_TOKEN
                asciiRunLength = 0
            }
        }

        while (index < text.length) {
            val codePoint = text.codePointAt(index)
            val charCount = Character.charCount(codePoint)
            if (codePoint.isAsciiWord()) {
                asciiRunLength++
            } else {
                flushAsciiRun()
                tokenCount++
            }
            index += charCount
        }
        flushAsciiRun()
        return tokenCount
    }

    private fun Int.isAsciiWord(): Boolean {
        return this in 'a'.code..'z'.code ||
            this in 'A'.code..'Z'.code ||
            this in '0'.code..'9'.code ||
            this == '_'.code
    }

    private const val ASCII_CHARS_PER_TOKEN = 4
}
