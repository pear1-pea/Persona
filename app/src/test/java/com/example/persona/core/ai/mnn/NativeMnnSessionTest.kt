package com.example.persona.core.ai.mnn

import com.example.persona.BuildConfig
import com.example.persona.core.ai.prompt.NativeMessage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class NativeMnnSessionTest {
    @Test
    fun `inspection requires a debug build before inspecting input`() {
        assertInvalid(listOf(NativeMessage("private sentinel", "private sentinel")))
    }

    @Test
    fun `inspection rejects empty and oversized message lists`() {
        assertInvalid(emptyList())
        assertInvalid(List(129) { NativeMessage("user", "x") })
    }

    @Test
    fun `inspection rejects empty content without echoing input`() {
        assertInvalid(listOf(NativeMessage("system", "private sentinel"), NativeMessage("user", "")))
    }

    @Test
    fun `inspection rejects unknown roles rather than normalizing them`() {
        for (role in listOf("", "tool", "json", "USER", "user ", "private sentinel")) {
            assertInvalid(listOf(NativeMessage(role, "private sentinel")))
        }
    }

    @Test
    fun `inspection enforces the aggregate utf16 budget including roles`() {
        assertInvalid(listOf(NativeMessage("user", "x".repeat(16_381))))
        assertInvalid(List(2) { NativeMessage("user", "x".repeat(8_189)) })
        assertInvalid(listOf(NativeMessage("user", "\uD83D\uDE00".repeat(8_191))))
    }

    @Test
    fun `inspection accepts the exact utf16 boundary before checking session state`() {
        assertUnloaded(listOf(NativeMessage("user", "x".repeat(16_380))))
        assertUnloaded(listOf(NativeMessage("user", "\uD83D\uDE00".repeat(8_190))))
        assertUnloaded(List(2) { NativeMessage("user", "x".repeat(8_188)) })
    }

    @Test
    fun `inspection accepts all allowed roles and 128 messages before checking session state`() {
        assertUnloaded(listOf("system", "user", "assistant").map { NativeMessage(it, "x") })
        assertUnloaded(List(128) { NativeMessage("user", "x") })
    }

    @Test
    fun `inspection requires a loaded session after close`() {
        val session = NativeMnnSession()
        session.close()
        assertUnloaded(listOf(NativeMessage("user", "x")), session)
    }

    private fun assertInvalid(messages: List<NativeMessage>) {
        val expectedType = if (BuildConfig.DEBUG) IllegalArgumentException::class.java
        else IllegalStateException::class.java
        val error = assertThrows(expectedType) {
            NativeMnnSession().inspectChatPromptForVerification(messages)
        }
        assertEquals(
            if (BuildConfig.DEBUG) "Invalid MNN chat prompt verification input"
            else "MNN chat prompt verification requires a debug build",
            error.message
        )
    }

    private fun assertUnloaded(
        messages: List<NativeMessage>,
        session: NativeMnnSession = NativeMnnSession()
    ) {
        val error = assertThrows(IllegalStateException::class.java) {
            session.inspectChatPromptForVerification(messages)
        }
        assertEquals(
            if (BuildConfig.DEBUG) "MNN session has not been loaded"
            else "MNN chat prompt verification requires a debug build",
            error.message
        )
    }
}
