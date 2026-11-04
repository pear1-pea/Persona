package com.example.persona.features.creation

import com.google.gson.Gson
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class PersonaCreationValidatorTest {
    private val gson = Gson()
    private val valid = GeneratedPersonaDto("Nova", "A space explorer.", listOf("Kind"))

    @Test
    fun `accepts raw and whole fenced objects and normalizes whitespace`() {
        val json = gson.toJson(valid.copy(name = " Nova ", traits = listOf(" Kind ")))
        listOf(json, "```json\n$json\n```", "```\r\n$json\r\n```").forEach {
            assertEquals(valid, PersonaCreationValidator.parse(it))
        }
    }

    @Test
    fun `rejects absent null and coerced fields`() {
        val invalid = listOf(
            "null", "[]", "{}", "\"text\"",
            """{"backstory":"story","traits":["Kind"]}""",
            """{"name":"Nova","traits":["Kind"]}""",
            """{"name":"Nova","backstory":"story"}""",
            """{"name":null,"backstory":"story","traits":["Kind"]}""",
            """{"name":7,"backstory":"story","traits":["Kind"]}""",
            """{"name":{},"backstory":"story","traits":["Kind"]}""",
            """{"name":"Nova","backstory":null,"traits":["Kind"]}""",
            """{"name":"Nova","backstory":false,"traits":["Kind"]}""",
            """{"name":"Nova","backstory":[],"traits":["Kind"]}""",
            """{"name":"Nova","backstory":"story","traits":null}""",
            """{"name":"Nova","backstory":"story","traits":"Kind"}""",
            """{"name":"Nova","backstory":"story","traits":[null]}""",
            """{"name":"Nova","backstory":"story","traits":[42]}""",
            """{"name":"Nova","backstory":"story","traits":[true]}""",
            """{"name":"Nova","backstory":"story","traits":[[]]}""",
            """{"name":"Nova","backstory":"story","traits":[{}]}"""
        )
        invalid.forEach(::assertInvalid)
    }

    @Test
    fun `rejects malformed lenient duplicate extra and trailing data`() {
        val json = gson.toJson(valid)
        listOf(
            "", "{", "{name:'Nova',backstory:'story',traits:['Kind']}",
            "/*comment*/$json", "$json $json", "$json trailing", "Here is your persona: $json",
            json.replace("\"name\":\"Nova\"", "\"name\":\"Nova\",\"name\":\"Other\""),
            json.replace("\"name\":\"Nova\"", "\"name\":\"Nova\",\"\\u006eame\":\"Other\""),
            json.dropLast(1) + ",\"creatorId\":\"someone\"}",
            json.replace("Nova", "No\nva"),
            json.replace("Nova", "No\\'va"),
            json.replace("Nova", "No\\\nva"),
            json.replace("Nova", "\\uQQQQ"),
            "```json\n$json\n``` trailing"
        ).forEach(::assertInvalid)
    }

    @Test
    fun `enforces backend length boundaries and required traits`() {
        val boundary = GeneratedPersonaDto("x".repeat(120), "x".repeat(12000), List(20) { "x".repeat(80) })
        assertEquals(boundary, PersonaCreationValidator.parse(gson.toJson(boundary)))
        listOf(
            valid.copy(name = " "), valid.copy(backstory = "\n\t"),
            valid.copy(traits = emptyList()), valid.copy(traits = listOf(" ")),
            boundary.copy(name = "x".repeat(121)), boundary.copy(backstory = "x".repeat(12001)),
            boundary.copy(traits = List(21) { "Kind" }), boundary.copy(traits = listOf("x".repeat(81))),
            valid.copy(name = "😀".repeat(61))
        ).forEach { assertInvalid(gson.toJson(it)) }
        assertInvalid(" ".repeat(100001))
    }

    @Test
    fun `accepts valid escaped strings and arbitrary field order`() {
        val json = """{"traits":["\u004bind"],"backstory":"Line one\nLine two \"quoted\" \\ slash /","name":"\u004eova"}"""
        assertEquals(
            GeneratedPersonaDto("Nova", "Line one\nLine two \"quoted\" \\ slash /", listOf("Kind")),
            PersonaCreationValidator.parse(json)
        )
    }

    private fun assertInvalid(json: String) {
        assertThrows(InvalidPersonaProfileException::class.java) { PersonaCreationValidator.parse(json) }
    }
}
