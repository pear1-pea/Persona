package com.example.persona.core.ai

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class GenerationParamsTest {
    @Test
    fun defaultsAndGreedySmokeParametersRemainSupported() {
        assertEquals(GenerationParams(0.6f, 0.8f, 256), GenerationParams())
        assertEquals(0f, GenerationParams(0f, 1f, 1).temperature)
        assertEquals(0f, GenerationParams(-0f, Float.MIN_VALUE, Int.MAX_VALUE).temperature, 0f)
    }

    @Test
    fun temperatureRejectsNonFiniteAndOutOfRangeValues() {
        listOf(Float.NaN, Float.POSITIVE_INFINITY, Float.NEGATIVE_INFINITY, -0.01f, 2.00001f)
            .forEach { value ->
                assertThrows(IllegalArgumentException::class.java) { GenerationParams(temperature = value) }
            }
    }

    @Test
    fun topPRequiresFiniteNonEmptyProbabilityRange() {
        listOf(Float.NaN, Float.POSITIVE_INFINITY, Float.NEGATIVE_INFINITY, 0f, -0.01f, 1.01f)
            .forEach { value ->
                assertThrows(IllegalArgumentException::class.java) { GenerationParams(topP = value) }
            }
    }

    @Test
    fun tokenBudgetMustBePositiveIncludingOnCopy() {
        listOf(0, -1, Int.MIN_VALUE).forEach { value ->
            assertThrows(IllegalArgumentException::class.java) { GenerationParams().copy(maxTokens = value) }
        }
    }

    @Test
    fun smallPositiveSamplingValuesAreNotRoundedToZero() {
        val params = GenerationParams(temperature = 0.0000001f, topP = Float.MIN_VALUE, maxTokens = 1)
        assertEquals(0.0000001f, params.temperature)
        assertEquals(Float.MIN_VALUE, params.topP)
        assertEquals(Float.MIN_VALUE, GenerationParams(temperature = Float.MIN_VALUE).temperature)
        assertEquals(2f, GenerationParams(temperature = 2f).temperature)
    }
}
