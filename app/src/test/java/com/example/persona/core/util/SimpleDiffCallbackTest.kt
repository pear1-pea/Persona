package com.example.persona.core.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

class SimpleDiffCallbackTest {

    data class Item(val id: Int, val name: String)

    private val callback = SimpleDiffCallback<Item>(
        areItemsSame = { a, b -> a.id == b.id },
        areContentsSame = { a, b -> a == b }
    )

    @Test
    fun `areItemsTheSame returns true for same id`() {
        val result = callback.areItemsTheSame(Item(1, "A"), Item(1, "B"))
        assert(result)
    }

    @Test
    fun `areItemsTheSame returns false for different id`() {
        val result = callback.areItemsTheSame(Item(1, "A"), Item(2, "A"))
        assert(!result)
    }

    @Test
    fun `areContentsTheSame returns true for equal items`() {
        val result = callback.areContentsTheSame(Item(1, "A"), Item(1, "A"))
        assert(result)
    }

    @Test
    fun `areContentsTheSame returns false for different items`() {
        val result = callback.areContentsTheSame(Item(1, "A"), Item(1, "B"))
        assert(!result)
    }

    @Test
    fun `getChangePayload returns null when no payloadProvider`() {
        val result = callback.getChangePayload(Item(1, "A"), Item(1, "B"))
        assertEquals(null, result)
    }

    @Test
    fun `getChangePayload invokes provider when set`() {
        val callbackWithPayload = SimpleDiffCallback<Item>(
            areItemsSame = { a, b -> a.id == b.id },
            areContentsSame = { a, b -> a == b },
            payloadProvider = { old, new -> "changed: ${old.name} -> ${new.name}" }
        )

        val result = callbackWithPayload.getChangePayload(Item(1, "A"), Item(1, "B"))
        assertEquals("changed: A -> B", result)
    }
}
