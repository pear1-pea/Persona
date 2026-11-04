package com.example.persona.features

import android.content.Intent
import android.os.Handler
import android.os.HandlerThread
import android.view.FrameMetrics
import android.widget.TextView
import androidx.lifecycle.ViewModelProvider
import androidx.recyclerview.widget.RecyclerView
import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.example.persona.R
import com.example.persona.data.local.AppDatabase
import com.example.persona.data.local.entity.MessageEntity
import com.example.persona.data.local.entity.PersonaEntity
import com.example.persona.domain.model.MessageStatus
import com.example.persona.features.chat.ChatActivity
import com.example.persona.features.chat.ChatAdapter
import com.example.persona.features.chat.ChatViewModel
import com.example.persona.features.chat.StreamingMessage
import com.google.gson.Gson
import java.io.File
import java.util.UUID
import java.util.concurrent.CopyOnWriteArrayList
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class P2LongReplyInstrumentedTest {
    @Test(timeout = 120_000L)
    fun longMarkdownStreamsFinalizesAndRemainsScrollable() = runBlocking<Unit> {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        check(context.packageName == "com.example.persona.validation")
        val id = "p2-long-${UUID.randomUUID()}"
        val replyId = "$id-reply"
        val payload = (1..45).joinToString("\n\n") { index ->
            "## 合成片段 $index\n\n```kotlin\nval stream = flowOf(\"茶 & <标题> 😀\")\n```\n\n" +
                "| 字段 | 值 |\n| --- | --- |\n| 索引 | $index |\n\n- 列表 α\n- 特殊字符：& < > 😀\n"
        } + "\n\nP2_FINAL_SENTINEL"
        val database = Room.databaseBuilder(context, AppDatabase::class.java, "persona_db_v2").build()
        database.personaDao().insertPersona(PersonaEntity(id, "长文本合成验收", "", "", "Fixture", "OFFLINE_USER", false))
        database.messageDao().insertMessage(MessageEntity(replyId, id, "OFFLINE_USER", "Initial", false, 1L, "GENERATING"))
        val monitor = instrumentation.addMonitor(ChatActivity::class.java.name, null, false)
        val frames = CopyOnWriteArrayList<Long>()
        val updates = mutableListOf<Long>()
        val handlerThread = HandlerThread("p2-frame-receipt").apply { start() }
        var activity: ChatActivity? = null
        try {
            instrumentation.runOnMainSync {
                context.startActivity(Intent(context, ChatActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    .putExtra("PERSONA_ID", id))
            }
            activity = checkNotNull(monitor.waitForActivityWithTimeout(10_000L)) as ChatActivity
            lateinit var adapter: ChatAdapter
            lateinit var recycler: RecyclerView
            lateinit var model: ChatViewModel
            instrumentation.runOnMainSync {
                recycler = activity!!.findViewById(R.id.rvChatMessages)
                adapter = recycler.adapter as ChatAdapter
                model = ViewModelProvider(activity!!)[ChatViewModel::class.java]
            }
            suspend fun awaitUi(condition: () -> Boolean) = withTimeout(15_000L) {
                var ready = false
                while (!ready) {
                    instrumentation.runOnMainSync { ready = condition() }
                    if (!ready) delay(50L)
                }
            }
            awaitUi { model.currentPersona.value?.id == id && recycler.findViewHolderForAdapterPosition(0) != null }
            val listener = android.view.Window.OnFrameMetricsAvailableListener { _, metrics, _ ->
                frames += metrics.getMetric(FrameMetrics.TOTAL_DURATION)
            }
            instrumentation.runOnMainSync {
                activity!!.window.addOnFrameMetricsAvailableListener(listener, Handler(handlerThread.looper))
            }
            try {
                for (end in 1..40) {
                    val text = payload.take(payload.length * end / 40)
                    instrumentation.runOnMainSync {
                        val start = System.nanoTime()
                        adapter.updateStreamingMessage(StreamingMessage(replyId, text, MessageStatus.GENERATING))
                        updates += System.nanoTime() - start
                    }
                    delay(100L)
                }
                awaitUi {
                    recycler.findViewHolderForAdapterPosition(0)?.itemView?.findViewById<TextView>(R.id.tvContent)
                        ?.text?.toString() == payload
                }
                instrumentation.runOnMainSync {
                    adapter.updateStreamingMessage(StreamingMessage(replyId, payload, MessageStatus.NORMAL))
                }
                database.messageDao().updateMessageContent(replyId, "OFFLINE_USER", payload, "NORMAL")
                instrumentation.runOnMainSync { adapter.refresh() }
                awaitUi {
                    val text = recycler.findViewHolderForAdapterPosition(0)?.itemView?.findViewById<TextView>(R.id.tvContent)?.text
                    text != null && text.contains("P2_FINAL_SENTINEL") && !text.contains("```kotlin")
                }
                instrumentation.runOnMainSync { recycler.scrollBy(0, 2_000) }
                delay(300L)
                assertEquals(payload, database.messageDao().getMessageById(replyId, "OFFLINE_USER")?.content)
                assertTrue(frames.isNotEmpty())
                val sorted = frames.sorted()
                val report = mapOf("kind" to "debug_long_markdown_ui_observation", "synthetic_only" to true,
                    "characters" to payload.length, "stream_updates" to updates.size, "frame_samples" to frames.size,
                    "frame_total_p50_ns" to sorted[sorted.size / 2],
                    "frame_total_p95_ns" to sorted[((sorted.size - 1) * 0.95).toInt()],
                    "update_call_max_ns" to updates.maxOrNull(),
                    "device_refresh_rate_hz" to activity!!.windowManager.defaultDisplay.refreshRate,
                    "baseline_comparison" to "not_run", "release_performance_claim" to false,
                    "final_markdown_rendered" to true, "persisted_text_exact" to true)
                val directory = File(context.filesDir, "p2-evaluation").apply { mkdirs() }
                val output = File(directory, "long-reply-$id.json")
                check(output.createNewFile())
                output.writeText(Gson().toJson(report))
            } finally {
                instrumentation.runOnMainSync { activity!!.window.removeOnFrameMetricsAvailableListener(listener) }
            }
        } finally {
            instrumentation.runOnMainSync { activity?.finish() }
            instrumentation.removeMonitor(monitor)
            handlerThread.quitSafely()
            handlerThread.join(5_000L)
            database.personaDao().deletePersona(id, "OFFLINE_USER")
            database.close()
        }
    }
}
