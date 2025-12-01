package com.example.persona.core.util

import android.content.Context
import android.text.Spanned
import android.widget.TextView
import coil.ImageLoader
import dagger.hilt.android.qualifiers.ApplicationContext
import io.noties.markwon.Markwon
import io.noties.markwon.ext.strikethrough.StrikethroughPlugin
import io.noties.markwon.ext.tables.TablePlugin
import io.noties.markwon.ext.tasklist.TaskListPlugin
import io.noties.markwon.html.HtmlPlugin
import io.noties.markwon.image.coil.CoilImagesPlugin
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
            .usePlugin(HtmlPlugin.create())
            .usePlugin(CoilImagesPlugin.create(context, ImageLoader(context)))
            .build()
    }

    fun setMarkdown(textView: TextView, markdown: String) {
        markwon.setMarkdown(textView, markdown)
    }

    fun parse(markdown: String): Spanned {
        return markwon.toMarkdown(markdown)
    }
}