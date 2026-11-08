package com.example.persona.core.ai.mnn

import org.junit.Assert.assertTrue
import org.junit.Test

class Qwen3ProbeGenerationCheckTest {
    private fun verify(raw: String = "蓝色", callbacks: Int = 1, input: Long = 42,
        cancel: Boolean = false, latency: Long? = null) =
        Qwen3ProbeGenerationCheck.verify(raw, callbacks, input, 42, cancel, latency)

    private fun rejected(block: () -> Unit) {
        var failed = false
        try { block() } catch (_: IllegalArgumentException) { failed = true }
        assertTrue("Expected native observation rejection", failed)
    }

    @Test fun normalAndRecoveryRequireCallbackAndVisibleOutput() {
        verify()
        rejected { verify(callbacks = 0) }
        rejected { verify(raw = "") }
        rejected { verify(raw = " \n\t") }
    }

    @Test fun normalAndRecoveryRejectThinkAndChatControlLeakage() {
        for (raw in listOf("<think>reason</think>蓝色", "</think>蓝色", "<THINK>reason", "蓝色<|im_end|>",
            "<|im_start|>assistant\n蓝色", "蓝色<|endoftext|>", "蓝色<eop>")) {
            rejected { verify(raw = raw) }
        }
    }

    @Test fun everyGenerationMustMatchInspectedInputTokenCount() {
        rejected { verify(input = 41) }
        rejected { verify(input = 43, cancel = true, latency = 1) }
        verify(cancel = true, latency = 1)
    }

    @Test fun callbackCancellationRequiresFirstCallbackAndBoundedReturn() {
        verify(raw = "<thi", cancel = true, latency = 5000)
        verify(raw = "", cancel = true, latency = 0)
        rejected { verify(callbacks = 0, cancel = true, latency = 1) }
        rejected { verify(callbacks = 2, cancel = true, latency = 1) }
        rejected { verify(cancel = true, latency = 5001) }
        rejected { verify(cancel = true, latency = -1) }
        rejected { verify(cancel = true) }
    }
}
