package com.example.persona.features

import android.content.Intent
import android.view.View
import android.widget.CompoundButton
import android.widget.EditText
import androidx.lifecycle.ViewModelProvider
import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.example.persona.R
import com.example.persona.data.local.AppDatabase
import com.example.persona.data.local.entity.MessageEntity
import com.example.persona.data.local.entity.PersonaEntity
import com.example.persona.features.memory.MemoryActivity
import com.example.persona.features.memory.MemoryViewModel
import java.util.UUID
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class MemoryFlowInstrumentedTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext

    @Test
    fun explicitMemorySurvivesRecreationAndReopenThenEditsAndDeletes() = runBlocking<Unit> {
        check(context.packageName == "com.example.persona.validation")
        val personaId = "memory-ui-${UUID.randomUUID()}"
        val sourceId = "$personaId-source"
        val database = Room.databaseBuilder(context, AppDatabase::class.java, "persona_db_v2").build()
        database.personaDao().insertPersona(PersonaEntity(personaId, "Memory fixture", "", "", "Fixture", "OFFLINE_USER"))
        database.messageDao().insertMessage(MessageEntity(sourceId, personaId, "OFFLINE_USER", "I prefer tea", true, 1L))
        var activity: MemoryActivity? = null
        try {
            activity = launch(personaId, sourceId)
            var model = viewModel(activity!!)
            awaitUi { !model.uiState.value.loading && model.uiState.value.draft != null }
            instrumentation.runOnMainSync {
                activity!!.findViewById<EditText>(R.id.etContent).setText("I prefer short answers")
                assertFalse(activity!!.findViewById<CompoundButton>(R.id.checkShared).isChecked)
                assertFalse(activity!!.findViewById<CompoundButton>(R.id.checkCloud).isChecked)
            }
            activity = recreate(activity!!)
            assertSame(model, viewModel(activity!!))
            instrumentation.runOnMainSync {
                assertEquals("I prefer short answers", activity!!.findViewById<EditText>(R.id.etContent).text.toString())
                activity!!.findViewById<View>(R.id.btnSave).performClick()
                activity!!.findViewById<View>(R.id.btnSave).performClick()
            }
            awaitUi { model.uiState.value.draft == null && model.uiState.value.memories.size == 1 }
            val saved = database.memoryDao().selectForPrompt("OFFLINE_USER", personaId, false, System.currentTimeMillis()).single()
            assertEquals(sourceId, saved.sourceMessageId)
            assertFalse(saved.cloudAllowed)
            assertEquals(personaId, saved.personaId)
            instrumentation.runOnMainSync { activity!!.finish() }
            instrumentation.waitForIdleSync()
            activity = launch(personaId)
            model = viewModel(activity!!)
            awaitUi { model.uiState.value.memories.any { it.id == saved.id } }
            instrumentation.runOnMainSync { model.showSource(sourceId) }
            awaitUi { model.uiState.value.source?.content == "I prefer tea" }
            instrumentation.runOnMainSync {
                model.closeSource()
                model.editMemory(saved.id)
                activity!!.findViewById<EditText>(R.id.etContent).setText("I now prefer detailed answers")
                activity!!.findViewById<View>(R.id.btnSave).performClick()
            }
            awaitUi { model.uiState.value.memories.singleOrNull()?.revision == 2 }
            instrumentation.runOnMainSync {
                model.requestDelete(saved.id)
                model.confirmDelete(checkNotNull(model.uiState.value.pendingDelete))
            }
            awaitUi { model.uiState.value.memories.isEmpty() && model.uiState.value.operation == null }
            assertTrue(database.memoryDao().selectForPrompt("OFFLINE_USER", personaId, false, System.currentTimeMillis()).isEmpty())
            val deleted = checkNotNull(database.memoryDao().findOwned(saved.id, "OFFLINE_USER"))
            assertTrue(deleted.deleted)
            assertEquals("", deleted.content)
            assertEquals(3, deleted.revision)
        } finally {
            instrumentation.runOnMainSync { activity?.finish() }
            database.memoryDao().deleteByPersona("OFFLINE_USER", personaId)
            database.personaDao().deletePersona(personaId, "OFFLINE_USER")
            database.close()
        }
    }

    private fun viewModel(activity: MemoryActivity): MemoryViewModel {
        lateinit var result: MemoryViewModel
        instrumentation.runOnMainSync { result = ViewModelProvider(activity)[MemoryViewModel::class.java] }
        return result
    }

    private suspend fun awaitUi(condition: () -> Boolean) = withTimeout(15_000L) {
        var ready = false
        while (!ready) {
            instrumentation.runOnMainSync { ready = condition() }
            if (!ready) delay(50)
        }
    }

    private fun launch(personaId: String, sourceId: String? = null): MemoryActivity {
        val monitor = instrumentation.addMonitor(MemoryActivity::class.java.name, null, false)
        instrumentation.runOnMainSync {
            context.startActivity(Intent(context, MemoryActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK).apply {
                putExtra("PERSONA_ID", personaId)
                putExtra("PERSONA_NAME", "Memory fixture")
                putExtra("SOURCE_MESSAGE_ID", sourceId)
                putExtra("SOURCE_CONTENT", "I prefer tea")
            })
        }
        return try {
            checkNotNull(monitor.waitForActivityWithTimeout(10_000L)) as MemoryActivity
        } finally {
            instrumentation.removeMonitor(monitor)
            instrumentation.waitForIdleSync()
        }
    }

    private fun recreate(activity: MemoryActivity): MemoryActivity {
        val monitor = instrumentation.addMonitor(MemoryActivity::class.java.name, null, false)
        instrumentation.runOnMainSync { activity.recreate() }
        return try {
            checkNotNull(monitor.waitForActivityWithTimeout(10_000L)) as MemoryActivity
        } finally {
            instrumentation.removeMonitor(monitor)
            instrumentation.waitForIdleSync()
        }
    }
}
