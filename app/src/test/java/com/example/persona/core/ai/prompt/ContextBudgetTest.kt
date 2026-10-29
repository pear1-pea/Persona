package com.example.persona.core.ai.prompt

import com.example.persona.core.ai.ChatMessage
import com.example.persona.core.ai.GenerationParams
import com.example.persona.testutil.ModelPackageFixtures
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.Rule
import com.example.persona.core.ai.LocalModelValidator
import com.example.persona.core.ai.ModelScanResult
import com.example.persona.core.ai.SymlinkChecker

class ContextBudgetTest {
    @get:Rule val temporary = TemporaryFolder()

    @Test(expected = ContextBudgetExceeded::class)
    fun `local context refuses to truncate the current user or system rules`() {
        val folder = ModelPackageFixtures.writePackage(temporary.newFolder(), contextWindow = 128)
        val model = (LocalModelValidator(SymlinkChecker { false })
            .validate(folder, ModelPackageFixtures.deviceProfile()).result as ModelScanResult.Ready).model
        ContextPlanner.plan(model, listOf(ChatMessage("system", "Rules")), "字".repeat(200),
            GenerationParams(maxTokens = 32), ConservativeTokenizer)
    }

    @Test(expected = ContextBudgetExceeded::class)
    fun `cloud context rejects oversized hard constraints`() {
        CloudContextPlanner.plan("s".repeat(12_001), "question", emptyList())
    }

    @Test
    fun `cloud history removes whole turns and preserves hard constraints`() {
        val history = (1..20).flatMap { listOf(ChatMessage("user", "u$it"), ChatMessage("assistant", "a$it")) }
        val planned = CloudContextPlanner.plan("rules", "current", history)
        assertEquals(ChatMessage("system", "rules"), planned.first())
        assertEquals(ChatMessage("user", "current"), planned.last())
        assertTrue(planned.size <= 24)
        assertEquals(0, (planned.size - 2) % 2)
        assertEquals("u20", planned[planned.lastIndex - 2].content)
        assertEquals("a20", planned[planned.lastIndex - 1].content)
    }
}
