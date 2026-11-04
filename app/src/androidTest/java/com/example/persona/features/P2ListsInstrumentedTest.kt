package com.example.persona.features

import android.content.Intent
import android.view.View
import android.widget.CompoundButton
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.TextView
import androidx.appcompat.widget.SearchView
import androidx.fragment.app.Fragment
import androidx.lifecycle.ViewModelProvider
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.example.persona.R
import com.example.persona.data.local.AppDatabase
import com.example.persona.data.local.entity.PersonaEntity
import com.example.persona.domain.model.Persona
import com.example.persona.features.chat.ChatActivity
import com.example.persona.features.chat.ChatViewModel
import com.example.persona.features.creation.CreatePersonaActivity
import com.example.persona.features.discovery.DiscoveryFragment
import com.example.persona.features.feed.FeedFragment
import com.example.persona.features.profile.ProfileFragment
import com.google.android.material.chip.ChipGroup
import java.util.UUID
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class P2ListsInstrumentedTest {
    @Test(timeout = 120_000L)
    fun offlineCreateRendersInRealListFragmentsAndCardOpensCorrectChat() = runBlocking<Unit> {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        check(context.packageName == "com.example.persona.validation")
        val name = "P2 lists ${UUID.randomUUID()}"
        val database = Room.databaseBuilder(context, AppDatabase::class.java, "persona_db_v2").build()
        val controlId = "p2-list-control-${UUID.randomUUID()}"
        database.personaDao().insertPersona(PersonaEntity(controlId, "Public list control", "", "",
            "Synthetic positive control", "OFFLINE_USER", true))
        val activities = mutableListOf<android.app.Activity>()
        fun launch(): CreatePersonaActivity {
            val monitor = instrumentation.addMonitor(CreatePersonaActivity::class.java.name, null, false)
            return try {
                instrumentation.runOnMainSync {
                    context.startActivity(Intent(context, CreatePersonaActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                }
                (checkNotNull(monitor.waitForActivityWithTimeout(10_000L)) as CreatePersonaActivity).also { activities += it }
            } finally { instrumentation.removeMonitor(monitor) }
        }
        suspend fun awaitUi(condition: () -> Boolean) = withTimeout(15_000L) {
            var ready = false
            while (!ready) {
                instrumentation.runOnMainSync { ready = condition() }
                if (!ready) delay(50L)
            }
        }
        fun rows(activity: CreatePersonaActivity, id: Int): List<Persona> {
            val adapter = activity.findViewById<RecyclerView>(id)?.adapter as? ListAdapter<*, *> ?: return emptyList()
            return adapter.currentList.filterIsInstance<Persona>()
        }
        try {
            val creation = launch()
            instrumentation.runOnMainSync {
                creation.findViewById<EditText>(R.id.etName).setText(name)
                creation.findViewById<EditText>(R.id.etBackstory).setText("只用于列表与导航验收的虚构资料。")
                creation.findViewById<ChipGroup>(R.id.chipGroupTraits).getChildAt(0).performClick()
                creation.findViewById<CompoundButton>(R.id.switchPublic).isChecked = true
                creation.findViewById<View>(R.id.btnCreate).performClick()
            }
            val persona = withTimeout(15_000L) {
                var found = database.personaDao().getPersonasByCreator("OFFLINE_USER").find { it.persona.name == name }
                while (found == null) {
                    delay(50L)
                    found = database.personaDao().getPersonasByCreator("OFFLINE_USER").find { it.persona.name == name }
                }
                found.persona
            }
            // A Hilt activity hosts the production fragments without fabricating a login.
            // This is offline fragment/navigation acceptance, not MainActivity/auth acceptance.
            val host = launch()
            val containerId = View.generateViewId()
            instrumentation.runOnMainSync { host.setContentView(FrameLayout(host).apply { id = containerId }) }
            fun show(fragment: Fragment) = instrumentation.runOnMainSync {
                host.supportFragmentManager.beginTransaction().replace(containerId, fragment).commitNow()
            }
            show(FeedFragment())
            awaitUi { rows(host, R.id.rvFeed).any { it.id == persona.id && it.name == name } }
            show(DiscoveryFragment())
            awaitUi { rows(host, R.id.rvDiscovery).any { it.id == persona.id } }
            instrumentation.runOnMainSync { host.findViewById<SearchView>(R.id.svDiscovery).setQuery(name, false) }
            awaitUi { rows(host, R.id.rvDiscovery).map { it.id } == listOf(persona.id) }
            val monitor = instrumentation.addMonitor(ChatActivity::class.java.name, null, false)
            lateinit var chat: ChatActivity
            try {
                awaitUi { host.findViewById<RecyclerView>(R.id.rvDiscovery).findViewHolderForAdapterPosition(0) != null }
                instrumentation.runOnMainSync {
                    host.findViewById<RecyclerView>(R.id.rvDiscovery).findViewHolderForAdapterPosition(0)!!.itemView.performClick()
                }
                chat = checkNotNull(monitor.waitForActivityWithTimeout(10_000L)) as ChatActivity
                activities += chat
            } finally { instrumentation.removeMonitor(monitor) }
            lateinit var chatModel: ChatViewModel
            instrumentation.runOnMainSync { chatModel = ViewModelProvider(chat)[ChatViewModel::class.java] }
            awaitUi { chatModel.currentPersona.value?.id == persona.id }
            instrumentation.runOnMainSync { chat.finish() }
            instrumentation.waitForIdleSync()
            show(ProfileFragment())
            awaitUi { rows(host, R.id.rvMyPersonas).any { it.id == persona.id } }
            val editMonitor = instrumentation.addMonitor(CreatePersonaActivity::class.java.name, null, false)
            try {
                var position = -1
                instrumentation.runOnMainSync {
                    position = rows(host, R.id.rvMyPersonas).indexOfFirst { it.id == persona.id }
                    host.findViewById<RecyclerView>(R.id.rvMyPersonas).scrollToPosition(position)
                }
                awaitUi { host.findViewById<RecyclerView>(R.id.rvMyPersonas).findViewHolderForAdapterPosition(position) != null }
                instrumentation.runOnMainSync {
                    assertTrue(host.findViewById<RecyclerView>(R.id.rvMyPersonas)
                        .findViewHolderForAdapterPosition(position)!!.itemView.performLongClick())
                }
                val editor = checkNotNull(editMonitor.waitForActivityWithTimeout(10_000L)) as CreatePersonaActivity
                activities += editor
                instrumentation.runOnMainSync {
                    assertEquals(name, editor.findViewById<EditText>(R.id.etName).text.toString())
                    editor.findViewById<EditText>(R.id.etName).setText("$name edited")
                    editor.findViewById<CompoundButton>(R.id.switchPublic).isChecked = false
                    editor.findViewById<View>(R.id.btnCreate).performClick()
                }
            } finally { instrumentation.removeMonitor(editMonitor) }
            awaitUi { rows(host, R.id.rvMyPersonas).any { it.id == persona.id && it.name == "$name edited" && !it.isPublic } }
            val all = database.personaDao().getPersonasByCreator("OFFLINE_USER").map { it.persona }
            val counts = context.getString(R.string.product_persona_counts, all.size,
                all.count { it.isPublic }, all.count { !it.isPublic })
            awaitUi { host.findViewById<TextView>(R.id.tvPersonaCounts).text.toString() == counts }
            assertEquals(1, all.count { it.id == persona.id })
            show(DiscoveryFragment())
            awaitUi { rows(host, R.id.rvDiscovery).any { it.id == controlId } &&
                rows(host, R.id.rvDiscovery).none { it.id == persona.id } }
            instrumentation.runOnMainSync { host.findViewById<SearchView>(R.id.svDiscovery).setQuery(name, false) }
            awaitUi { rows(host, R.id.rvDiscovery).none { it.id == persona.id } }
        } finally {
            instrumentation.runOnMainSync { activities.asReversed().forEach { it.finish() } }
            database.personaDao().getPersonasByCreator("OFFLINE_USER").filter { it.persona.name.startsWith(name) }
                .forEach { database.personaDao().deletePersona(it.persona.id, "OFFLINE_USER") }
            database.personaDao().deletePersona(controlId, "OFFLINE_USER")
            database.close()
        }
    }
}
