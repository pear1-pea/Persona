package com.example.persona.data.remote

import java.io.IOException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SseDataReaderTest {
    @Test
    fun `joins data lines and ignores heartbeat and event metadata`() {
        val reader = SseDataReader(": keepalive\r\nevent: chunk\r\ndata: {\r\ndata: \"choices\": []}\r\n\r\ndata: [DONE]\n".reader().buffered())
        assertEquals("{\n\"choices\": []}", reader.readData())
        assertEquals("[DONE]", reader.readData())
        assertNull(reader.readData())
    }

    @Test(expected = IOException::class)
    fun `oversized frame is refused before parsing`() {
        SseDataReader(("data: " + "x".repeat(1_048_577)).reader().buffered()).readData()
    }
}
