package com.example.persona.core.util

import org.junit.Assert.assertTrue
import org.junit.Test

class UsernameGeneratorTest {

    @Test
    fun `generate returns non-empty string`() {
        val name = UsernameGenerator.generate()
        assertTrue(name.isNotEmpty())
    }

    @Test
    fun `generate starts with uppercase letter`() {
        val name = UsernameGenerator.generate()
        assertTrue(name.first().isUpperCase())
    }

    @Test
    fun `generate contains no whitespace`() {
        val name = UsernameGenerator.generate()
        assertTrue(!name.contains(" "))
    }

    @Test
    fun `generate produces varied results`() {
        val names = (1..20).map { UsernameGenerator.generate() }
        val unique = names.distinct()
        assertTrue(unique.size > 1)
    }

    @Test
    fun `generate result is alphanumeric only`() {
        val name = UsernameGenerator.generate()
        assertTrue(name.all { it.isLetterOrDigit() })
    }
}
