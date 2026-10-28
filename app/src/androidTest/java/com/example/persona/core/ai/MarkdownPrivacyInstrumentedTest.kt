package com.example.persona.core.ai

import android.text.style.ClickableSpan
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.example.persona.core.util.MarkdownHelper
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class MarkdownPrivacyInstrumentedTest {
    @Test
    fun generatedMarkdownHasNoOutboundActionsOrImageLoaders() {
        val helper = MarkdownHelper(InstrumentationRegistry.getInstrumentation().targetContext)
        val parsed = helper.parse("[external](https://example.invalid/private) ![image](https://example.invalid/tracker) <img src=\"file:///private\">")
        assertEquals(0, parsed.getSpans(0, parsed.length, ClickableSpan::class.java).size)
        assertTrue(parsed.getSpans(0, parsed.length, Any::class.java).none {
            it.javaClass.name.contains("AsyncDrawable")
        })
    }
}
