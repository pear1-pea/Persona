package com.example.persona.features

import android.content.Intent
import androidx.lifecycle.ViewModelProvider
import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.example.persona.data.local.AppDatabase
import com.example.persona.data.local.entity.MessageEntity
import com.example.persona.data.local.entity.PersonaEntity
import com.example.persona.features.chat.ChatActivity
import com.example.persona.features.chat.ChatViewModel
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ChatProcessRecoveryInstrumentedTest {
    @Test
    fun prepareOrVerifyInterruptedReplyAcrossProcesses() = runBlocking<Unit> {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val phase = InstrumentationRegistry.getArguments().getString("p2RecoveryPhase")
        assumeTrue(phase == "prepare" || phase == "verify")
        val context = instrumentation.targetContext
        check(context.packageName == "com.example.persona.validation")
        val database = Room.databaseBuilder(context, AppDatabase::class.java, "persona_db_v2").build()
        val personaId = "p2-process-recovery-fixture"
        val owner = "OFFLINE_USER"
        var activity: ChatActivity? = null
        try {
            if (phase == "prepare") {
                database.personaDao().deletePersona(personaId, owner)
                database.personaDao().insertPersona(PersonaEntity(personaId, "Recovery fixture", "", "", "Fixture", owner))
                database.messageDao().insertMessage(MessageEntity("p2-partial", personaId, owner,
                    "Retained partial reply", false, 1L, "GENERATING"))
                database.messageDao().insertMessage(MessageEntity("p2-thinking", personaId, owner,
                    "正在思考...", false, 2L, "GENERATING"))
                assertEquals(2, database.messageDao().getRecentMessagesByPersonaId(personaId, owner, 10)
                    .count { it.status == "GENERATING" })
            } else {
                val monitor = instrumentation.addMonitor(ChatActivity::class.java.name, null, false)
                instrumentation.runOnMainSync {
                    context.startActivity(Intent(context, ChatActivity::class.java)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK).putExtra("PERSONA_ID", personaId))
                }
                try {
                    activity = checkNotNull(monitor.waitForActivityWithTimeout(10_000L)) as ChatActivity
                } finally {
                    instrumentation.removeMonitor(monitor)
                }
                lateinit var viewModel: ChatViewModel
                instrumentation.runOnMainSync { viewModel = ViewModelProvider(activity!!)[ChatViewModel::class.java] }
                withTimeout(15_000L) { while (viewModel.currentPersona.value?.id != personaId) delay(100) }
                val messages = database.messageDao().getRecentMessagesByPersonaId(personaId, owner, 10)
                assertEquals(2, messages.size)
                assertEquals(setOf("STOPPED"), messages.map { it.status }.toSet())
                assertEquals("Retained partial reply", messages.single { it.id == "p2-partial" }.content)
                assertEquals("已停止生成", messages.single { it.id == "p2-thinking" }.content)
                database.personaDao().deletePersona(personaId, owner)
            }
        } finally {
            activity?.let { opened -> instrumentation.runOnMainSync { opened.finish() } }
            database.close()
        }
    }
}
