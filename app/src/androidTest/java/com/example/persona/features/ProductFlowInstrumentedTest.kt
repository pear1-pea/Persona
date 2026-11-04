package com.example.persona.features

import android.app.Activity
import android.content.Intent
import android.widget.EditText
import android.widget.CompoundButton
import android.widget.TextView
import androidx.lifecycle.ViewModelProvider
import androidx.recyclerview.widget.RecyclerView
import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.example.persona.R
import com.example.persona.data.local.AppDatabase
import com.example.persona.data.local.entity.PersonaEntity
import com.example.persona.data.local.entity.MessageEntity
import com.example.persona.domain.model.MessageStatus
import com.example.persona.features.chat.ChatActivity
import com.example.persona.features.chat.ChatAdapter
import com.example.persona.features.chat.ChatViewModel
import com.example.persona.features.chat.StreamingMessage
import com.example.persona.features.creation.CreatePersonaActivity
import com.example.persona.features.creation.CreatePersonaViewModel
import com.google.android.material.chip.ChipGroup
import java.util.UUID
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ProductFlowInstrumentedTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext

    @Test
    fun creationDraftSurvivesRecreationAndSavesOnceToRoom() = runBlocking {
        check(context.packageName == "com.example.persona.validation")
        val name = "P2 fixture ${UUID.randomUUID()}"
        val database = Room.databaseBuilder(context, AppDatabase::class.java, "persona_db_v2").build()
        var activity = launch(CreatePersonaActivity::class.java)
        try {
            lateinit var original: CreatePersonaViewModel
            instrumentation.runOnMainSync {
                original = ViewModelProvider(activity)[CreatePersonaViewModel::class.java]
                activity.findViewById<EditText>(R.id.etName).setText(name)
                activity.findViewById<EditText>(R.id.etBackstory).setText("A synthetic character for UI acceptance.")
                activity.findViewById<ChipGroup>(R.id.chipGroupTraits).getChildAt(0).performClick()
                activity.findViewById<CompoundButton>(R.id.switchPublic).isChecked = true
            }
            activity = recreate(activity)
            instrumentation.runOnMainSync {
                assertSame(original, ViewModelProvider(activity)[CreatePersonaViewModel::class.java])
                assertEquals(name, activity.findViewById<EditText>(R.id.etName).text.toString())
                assertEquals(1, activity.findViewById<ChipGroup>(R.id.chipGroupTraits).checkedChipIds.size)
                assertTrue(activity.findViewById<CompoundButton>(R.id.switchPublic).isChecked)
                activity.findViewById<android.view.View>(R.id.btnCreate).performClick()
            }
            withTimeout(15_000L) {
                while (database.personaDao().getPersonasByCreator("OFFLINE_USER").none { it.persona.name == name }) delay(100)
            }
            assertEquals(1, database.personaDao().getPersonasByCreator("OFFLINE_USER").count { it.persona.name == name })
        } finally {
            instrumentation.runOnMainSync { activity.finish() }
            database.personaDao().getPersonasByCreator("OFFLINE_USER")
                .filter { it.persona.name == name }
                .forEach { database.personaDao().deletePersona(it.persona.id, "OFFLINE_USER") }
            database.close()
        }
    }

    @Test
    fun streamingOverlayRemainsVisibleWhenAnOlderRoomCheckpointArrives() = runBlocking {
        check(context.packageName == "com.example.persona.validation")
        val id = "p2-display-${UUID.randomUUID()}"
        val messageId = "$id-reply"
        val database = Room.databaseBuilder(context, AppDatabase::class.java, "persona_db_v2").build()
        database.personaDao().insertPersona(PersonaEntity(id, "P2 display", "", "", "Fixture", "OFFLINE_USER"))
        val activity = launch(ChatActivity::class.java, id)
        try {
            lateinit var viewModel: ChatViewModel
            lateinit var recycler: RecyclerView
            lateinit var adapter: ChatAdapter
            instrumentation.runOnMainSync {
                viewModel = ViewModelProvider(activity)[ChatViewModel::class.java]
                recycler = activity.findViewById(R.id.rvChatMessages)
                adapter = recycler.adapter as ChatAdapter
            }
            withTimeout(15_000L) { while (viewModel.currentPersona.value?.id != id) delay(100) }
            database.messageDao().insertMessage(MessageEntity(messageId, id, "OFFLINE_USER", "Checkpoint", false, 1L, "GENERATING"))
            // The fixture writes through a separate Room instance; request its new page explicitly.
            instrumentation.runOnMainSync { adapter.refresh() }
            fun visibleText() = recycler.findViewHolderForAdapterPosition(0)?.itemView
                ?.findViewById<TextView>(R.id.tvContent)?.text?.toString()
            awaitUi { visibleText() == "Checkpoint" }
            instrumentation.runOnMainSync {
                adapter.updateStreamingMessage(StreamingMessage(messageId, "Latest visible text", MessageStatus.GENERATING))
            }
            awaitUi { visibleText() == "Latest visible text" }
            database.messageDao().updateMessageContent(messageId, "OFFLINE_USER", "Later checkpoint", "GENERATING")
            instrumentation.runOnMainSync { adapter.refresh() }
            awaitUi { adapter.snapshot().items.singleOrNull()?.content == "Later checkpoint" }
            awaitUi { visibleText() == "Latest visible text" }
            instrumentation.runOnMainSync {
                adapter.updateStreamingMessage(StreamingMessage(messageId, "Completed reply", MessageStatus.NORMAL))
            }
            database.messageDao().updateMessageContent(messageId, "OFFLINE_USER", "Completed reply", "NORMAL")
            instrumentation.runOnMainSync { adapter.refresh() }
            awaitUi { adapter.snapshot().items.singleOrNull()?.content == "Completed reply" && visibleText() == "Completed reply" }
        } finally {
            instrumentation.runOnMainSync { activity.finish() }
            database.personaDao().deletePersona(id, "OFFLINE_USER")
            database.close()
        }
    }

    @Test
    fun chatRecreationRetainsViewModelDraftAndLocalOnlyChoice() = runBlocking {
        check(context.packageName == "com.example.persona.validation")
        val id = "p2-chat-${UUID.randomUUID()}"
        val database = Room.databaseBuilder(context, AppDatabase::class.java, "persona_db_v2").build()
        database.personaDao().insertPersona(PersonaEntity(id, "P2 chat", "", "", "Fixture", "OFFLINE_USER"))
        var activity = launch(ChatActivity::class.java, id)
        try {
            lateinit var original: ChatViewModel
            instrumentation.runOnMainSync { original = ViewModelProvider(activity)[ChatViewModel::class.java] }
            withTimeout(15_000L) { while (original.currentPersona.value?.id != id) delay(100) }
            instrumentation.runOnMainSync {
                activity.findViewById<EditText>(R.id.etMessage).setText("Unsent private draft")
                activity.findViewById<CompoundButton>(R.id.switchLocalOnly).isChecked = true
            }
            activity = recreate(activity)
            instrumentation.runOnMainSync {
                assertSame(original, ViewModelProvider(activity)[ChatViewModel::class.java])
                assertEquals(id, original.currentPersona.value?.id)
                assertEquals("Unsent private draft", activity.findViewById<EditText>(R.id.etMessage).text.toString())
                assertTrue(activity.findViewById<CompoundButton>(R.id.switchLocalOnly).isChecked)
            }
        } finally {
            instrumentation.runOnMainSync { activity.finish() }
            database.personaDao().deletePersona(id, "OFFLINE_USER")
            database.close()
        }
    }

    private suspend fun awaitUi(condition: () -> Boolean) = withTimeout(10_000L) {
        var ready = false
        while (!ready) {
            instrumentation.runOnMainSync { ready = condition() }
            if (!ready) delay(50)
        }
    }

    private fun <T : Activity> launch(type: Class<T>, personaId: String? = null): T {
        val monitor = instrumentation.addMonitor(type.name, null, false)
        instrumentation.runOnMainSync {
            context.startActivity(Intent(context, type).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK).apply {
                personaId?.let { putExtra("PERSONA_ID", it) }
            })
        }
        return try {
            type.cast(checkNotNull(monitor.waitForActivityWithTimeout(10_000L)))!!
        } finally {
            instrumentation.removeMonitor(monitor)
            instrumentation.waitForIdleSync()
        }
    }

    private fun <T : Activity> recreate(activity: T): T {
        val monitor = instrumentation.addMonitor(activity.javaClass.name, null, false)
        instrumentation.runOnMainSync { activity.recreate() }
        return try {
            @Suppress("UNCHECKED_CAST")
            (checkNotNull(monitor.waitForActivityWithTimeout(10_000L)) as T)
        } finally {
            instrumentation.removeMonitor(monitor)
            instrumentation.waitForIdleSync()
        }
    }
}
