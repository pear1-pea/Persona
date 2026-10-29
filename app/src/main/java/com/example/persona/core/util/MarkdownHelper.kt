package com.example.persona.core.util

import android.content.Context
import android.text.Spanned
import android.text.SpannableString
import android.text.style.ClickableSpan
import android.widget.TextView
import dagger.hilt.android.qualifiers.ApplicationContext
import io.noties.markwon.Markwon
import io.noties.markwon.ext.strikethrough.StrikethroughPlugin
import io.noties.markwon.ext.tables.TablePlugin
import io.noties.markwon.ext.tasklist.TaskListPlugin
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class MarkdownHelper @Inject constructor(
    @ApplicationContext private val context: Context
) {

    // Lazy initialize Markwon instance
    private val markwon: Markwon by lazy {
        Markwon.builder(context)
            .usePlugin(TablePlugin.create(context))
            .usePlugin(StrikethroughPlugin.create())
            .usePlugin(TaskListPlugin.create(context))
            .build()
    }

    fun setMarkdown(textView: TextView, markdown: String) {
        markwon.setParsedMarkdown(textView, parse(markdown))
        textView.linksClickable = false
        textView.movementMethod = null
    }

    fun parse(markdown: String): Spanned {
        return SpannableString(markwon.toMarkdown(markdown)).apply {
            getSpans(0, length, ClickableSpan::class.java).forEach(::removeSpan)
        }
    }
}
