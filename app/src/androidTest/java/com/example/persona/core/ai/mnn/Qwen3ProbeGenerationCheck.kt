package com.example.persona.core.ai.mnn

import java.util.Locale

internal object Qwen3ProbeGenerationCheck {
    const val CALLBACK_STOP_LIMIT_MS = 5000L

    fun markerLeak(raw: String): Boolean {
        val lower = raw.lowercase(Locale.ROOT)
        return "<think" in lower || "</think" in lower || "<|" in raw || "<eop>" in lower
    }

    fun verify(raw: String, callbacks: Int, inputTokens: Long, referenceTokens: Int,
        cancelFirst: Boolean, callbackStopLatencyMs: Long?) {
        require(inputTokens == referenceTokens.toLong())
        if (cancelFirst) {
            require(callbacks == 1 && callbackStopLatencyMs != null && callbackStopLatencyMs in 0..CALLBACK_STOP_LIMIT_MS)
        } else {
            require(callbacks > 0 && raw.isNotBlank() && !markerLeak(raw))
        }
    }
}
