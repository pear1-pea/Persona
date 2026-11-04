package com.example.persona.features

import android.content.Intent
import android.view.View
import android.widget.EditText
import androidx.lifecycle.ViewModelProvider
import androidx.recyclerview.widget.RecyclerView
import androidx.room.Room
import androidx.test.espresso.Espresso.onView
import androidx.test.espresso.action.ViewActions.click
import androidx.test.espresso.assertion.ViewAssertions.matches
import androidx.test.espresso.matcher.ViewMatchers.withId
import androidx.test.espresso.matcher.ViewMatchers.withText
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.example.persona.R
import com.example.persona.data.local.AppDatabase
import com.example.persona.data.local.entity.MessageEntity
import com.example.persona.data.local.entity.PersonaEntity
import com.example.persona.features.chat.ChatActivity
import com.example.persona.features.chat.ChatViewModel
import com.example.persona.features.memory.MemoryActivity
import com.example.persona.features.memory.MemoryViewModel
import java.util.UUID
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.hamcrest.Matchers.containsString
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class P2MemoryActionsInstrumentedTest {
    @Test(timeout = 120_000L)
    fun chatLongPressOpensSourceBackedMemoryAndButtonsEditAndDelete() = runBlocking<Unit> {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        check(context.packageName == "com.example.persona.validation")
        val id = "p2-actions-${UUID.randomUUID()}"
        val database = Room.databaseBuilder(context, AppDatabase::class.java, "persona_db_v2").build()
        database.personaDao().insertPersona(PersonaEntity(id, "合成记忆操作", "", "", "虚构书屋管理员", "OFFLINE_USER", false))
        database.messageDao().insertMessage(MessageEntity("$id-user", id, "OFFLINE_USER", "合成测试：我偏好简短解释。", true, 1L))
        val chatMonitor = instrumentation.addMonitor(ChatActivity::class.java.name, null, false)
        var chat: ChatActivity? = null
        var memory: MemoryActivity? = null
        try {
            instrumentation.runOnMainSync {
                context.startActivity(Intent(context, ChatActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    .putExtra("PERSONA_ID", id))
            }
            chat = checkNotNull(chatMonitor.waitForActivityWithTimeout(10_000L)) as ChatActivity
            lateinit var chatModel: ChatViewModel
            lateinit var recycler: RecyclerView
            instrumentation.runOnMainSync {
                chatModel = ViewModelProvider(chat!!)[ChatViewModel::class.java]
                recycler = chat!!.findViewById(R.id.rvChatMessages)
            }
            suspend fun awaitUi(condition: () -> Boolean) = withTimeout(15_000L) {
                var ready = false
                while (!ready) {
                    instrumentation.runOnMainSync { ready = condition() }
                    if (!ready) delay(50L)
                }
            }
            awaitUi { chatModel.currentPersona.value?.id == id &&
                recycler.findViewHolderForAdapterPosition(0) != null }
            val monitor = instrumentation.addMonitor(MemoryActivity::class.java.name, null, false)
            try {
                instrumentation.runOnMainSync {
                    assertTrue(recycler.findViewHolderForAdapterPosition(0)!!.itemView.performLongClick())
                }
                memory = checkNotNull(monitor.waitForActivityWithTimeout(10_000L)) as MemoryActivity
            } finally { instrumentation.removeMonitor(monitor) }
            lateinit var model: MemoryViewModel
            instrumentation.runOnMainSync { model = ViewModelProvider(memory!!)[MemoryViewModel::class.java] }
            awaitUi { model.uiState.value.draft?.sourceMessageId == "$id-user" && !model.uiState.value.loading }
            instrumentation.runOnMainSync { memory!!.findViewById<View>(R.id.btnDraftSource).performClick() }
            awaitUi { model.uiState.value.source?.content != null }
            onView(withId(android.R.id.message)).check(matches(withText(containsString("合成测试：我偏好简短解释。"))))
            onView(withText(R.string.memory_source_close)).perform(click())
            instrumentation.runOnMainSync {
                memory!!.findViewById<EditText>(R.id.etContent).setText("我偏好简短解释。")
                memory!!.findViewById<View>(R.id.btnSave).performClick()
            }
            awaitUi { model.uiState.value.memories.size == 1 && model.uiState.value.draft == null }
            onView(withText(R.string.memory_edit)).perform(click())
            awaitUi { model.uiState.value.draft?.revision == 1 }
            instrumentation.runOnMainSync {
                memory!!.findViewById<EditText>(R.id.etContent).setText("我现在偏好详细解释。")
                memory!!.findViewById<View>(R.id.btnSave).performClick()
            }
            awaitUi { model.uiState.value.memories.singleOrNull()?.revision == 2 && model.uiState.value.draft == null }
            val saved = database.memoryDao().selectForPrompt("OFFLINE_USER", id, false, System.currentTimeMillis()).single()
            assertEquals("$id-user", saved.sourceMessageId)
            assertFalse(saved.cloudAllowed)
            onView(withText(R.string.memory_delete)).perform(click())
            onView(withId(android.R.id.button1)).perform(click())
            awaitUi { model.uiState.value.memories.isEmpty() && model.uiState.value.pendingDelete == null }
            val deleted = checkNotNull(database.memoryDao().findOwned(saved.id, "OFFLINE_USER"))
            assertTrue(deleted.deleted)
            assertEquals("", deleted.content)
            assertNull(deleted.sourceMessageId)
        } finally {
            instrumentation.runOnMainSync { memory?.finish(); chat?.finish() }
            instrumentation.removeMonitor(chatMonitor)
            database.memoryDao().deleteByPersona("OFFLINE_USER", id)
            database.personaDao().deletePersona(id, "OFFLINE_USER")
            database.close()
        }
    }
}
