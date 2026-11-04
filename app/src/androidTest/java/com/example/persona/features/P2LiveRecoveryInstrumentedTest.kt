package com.example.persona.features

import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.EditText
import android.widget.TextView
import androidx.lifecycle.ViewModelProvider
import androidx.recyclerview.widget.RecyclerView
import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.example.persona.R
import com.example.persona.core.auth.AuthManager
import com.example.persona.core.auth.SessionTokenStore
import com.example.persona.data.local.AppDatabase
import com.example.persona.data.remote.dto.CreatePersonaRequest
import com.example.persona.di.NetworkModule
import com.example.persona.features.chat.ChatActivity
import com.example.persona.features.chat.ChatAdapter
import com.example.persona.features.chat.ChatViewModel
import com.google.gson.Gson
import com.google.gson.JsonObject
import java.io.File
import java.util.UUID
import kotlinx.coroutines.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class P2LiveRecoveryInstrumentedTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext
    private val directory get() = File(context.filesDir, "p2-live-recovery")

    private fun admission() {
        val args = InstrumentationRegistry.getArguments()
        assumeTrue(args.getString("runP2LiveRecovery") == "true")
        check(context.packageName == "com.example.persona.validation")
        check(NetworkModule.provideBackendConfig().baseUrl == args.getString("p2QaBackendUrl"))
    }

    @Test(timeout = 120_000L)
    fun prepareActualCloudGenerationForHostTermination() = runBlocking<Unit> {
        admission()
        directory.mkdirs()
        val state = File(directory, "state.json")
        check(!state.exists())
        val database = Room.databaseBuilder(context, AppDatabase::class.java, "persona_db_v2").build()
        val auth = auth(database)
        check(auth.identity.value.token == null)
        val password = "Persona-${UUID.randomUUID()}!"
        var activity: ChatActivity? = null
        var handedToHost = false
        try {
            auth.signUp("p2-recovery-${UUID.randomUUID()}@example.test", password)
            val config = NetworkModule.provideBackendConfig()
            val api = NetworkModule.providePersonaBackendApi(NetworkModule.provideRetrofit(
                NetworkModule.provideOkHttpClient(auth, config), config))
            val persona = api.createPersona(CreatePersonaRequest("Live recovery fixture", listOf("Synthetic"),
                "A synthetic character for live process recovery acceptance.", false))
            activity = launch(persona.id)
            val model = model(activity)
            awaitUi { model.currentPersona.value?.id == persona.id }
            instrumentation.runOnMainSync {
                activity!!.findViewById<EditText>(R.id.etMessage).setText(
                    "@cloud 请生成一份三十项的合成测试清单，每项写两句完整中文，并包含一个 Kotlin 代码示例。直接开始，不要总结。")
                activity!!.findViewById<View>(R.id.btnSend).performClick()
            }
            val checkpoint = withTimeout(30_000L) {
                var row = database.messageDao().getRecentMessagesByPersonaId(persona.id, auth.identity.value.ownerId, 2)
                    .firstOrNull { !it.isFromUser && it.status == "GENERATING" && it.content != "正在思考..." }
                while (row == null) {
                    delay(20L)
                    row = database.messageDao().getRecentMessagesByPersonaId(persona.id, auth.identity.value.ownerId, 2)
                        .firstOrNull { !it.isFromUser && it.status == "GENERATING" && it.content != "正在思考..." }
                }
                row
            }
            check(model.isGenerating.value && checkpoint.content.isNotBlank())
            state.writeText(Gson().toJson(mapOf("persona_id" to persona.id, "owner_id" to auth.identity.value.ownerId,
                "message_id" to checkpoint.id, "partial" to checkpoint.content,
                "synthetic_account_password" to password, "actual_ui_generation_started" to true)))
            handedToHost = true
            instrumentation.sendStatus(2, Bundle().apply { putString("stream", "P2_LIVE_RECOVERY_READY\n") })
            // The host must force-stop only this validation package after receiving READY.
            delay(60_000L)
            handedToHost = false
            error("Host did not terminate the prepared validation process")
        } finally {
            if (!handedToHost) {
                instrumentation.runOnMainSync { activity?.finish() }
                if (auth.identity.value.token != null) auth.deleteAccount(password)
                database.close()
            }
        }
    }

    @Test(timeout = 240_000L)
    fun verifyStoppedVisibleReplyAndSuccessfulUiResendAfterRealProcessDeath() = runBlocking<Unit> {
        admission()
        val state = Gson().fromJson(File(directory, "state.json").readText(), JsonObject::class.java)
        val database = Room.databaseBuilder(context, AppDatabase::class.java, "persona_db_v2").build()
        val auth = auth(database)
        val personaId = state["persona_id"].asString
        val ownerId = state["owner_id"].asString
        check(auth.identity.value.ownerId == ownerId && auth.identity.value.token != null)
        var activity: ChatActivity? = null
        val report = linkedMapOf<String, Any?>("kind" to "actual_cloud_ui_process_recovery", "synthetic_only" to true)
        try {
            report["stage"] = "checking_interrupted_row"
            val beforeOpen = database.messageDao().getMessageById(state["message_id"].asString, ownerId)
            report["before_open_status"] = beforeOpen?.status
            check(beforeOpen?.status == "GENERATING") { "Host must interrupt an active generation" }
            activity = launch(personaId)
            val model = model(activity)
            report["stage"] = "loading_persona"
            awaitUi { model.currentPersona.value?.id == personaId }
            report["stage"] = "reading_recovered_checkpoint"
            val recovered = checkNotNull(database.messageDao().getMessageById(state["message_id"].asString, ownerId))
            report["recovered_characters"] = recovered.content.length
            report["recovered_status"] = recovered.status
            report["checkpoint_only_markdown_syntax"] = recovered.content.all { it.isWhitespace() || it in "#*_-`>~[]()" }
            check(recovered.status == "STOPPED" && recovered.content.startsWith(state["partial"].asString))
            lateinit var recycler: RecyclerView
            lateinit var adapter: ChatAdapter
            instrumentation.runOnMainSync {
                recycler = activity!!.findViewById(R.id.rvChatMessages)
                adapter = recycler.adapter as ChatAdapter
            }
            report["stage"] = "loading_recovered_page"
            awaitUi { adapter.snapshot().items.any { it.id == recovered.id } }
            val position = adapter.snapshot().items.indexOfFirst { it.id == recovered.id }
            instrumentation.runOnMainSync { recycler.scrollToPosition(position) }
            report["stage"] = "waiting_for_visible_recovered_text"
            awaitUi {
                !recycler.findViewHolderForAdapterPosition(position)?.itemView
                    ?.findViewById<TextView>(R.id.tvContent)?.text.isNullOrBlank()
            }
            report["stage"] = "checking_no_automatic_resend"
            delay(750L)
            check(!model.isGenerating.value)
            check(database.messageDao().getRecentMessagesByPersonaId(personaId, ownerId, 10).size == 2)
            report.putAll(mapOf("actual_generation_interrupted" to true, "checkpoint_retained" to true,
                "visible_recovered_reply" to true, "no_automatic_resend" to true))
            val attempts = mutableListOf<Map<String, Any?>>()
            report["resend_attempts"] = attempts
            var succeeded = false
            for (attempt in 1..3) {
                report["stage"] = "resend_$attempt"
                instrumentation.runOnMainSync {
                    activity!!.findViewById<EditText>(R.id.etMessage).setText("@cloud 只用一句中文确认：恢复后可以再次聊天。")
                    activity!!.findViewById<View>(R.id.btnSend).performClick()
                    check(model.isGenerating.value)
                }
                withTimeout(60_000L) { while (model.isGenerating.value) delay(100L) }
                val final = database.messageDao().getRecentMessagesByPersonaId(personaId, ownerId, 2).first { !it.isFromUser }
                val httpCode = Regex("HTTP +([0-9]{3})").find(final.content)?.groupValues?.get(1)?.toInt()
                attempts += mapOf("attempt" to attempt, "status" to final.status, "http_error" to httpCode,
                    "private_failure_text" to final.content.takeIf { final.status != "NORMAL" })
                if (final.status == "NORMAL" && final.content.isNotBlank()) {
                    succeeded = true
                    break
                }
                check(final.status == "FAILED" && httpCode == 429) { "Unexpected resend failure" }
                // Simulate a bounded user retry after an explicitly displayed rate/concurrency error.
                if (attempt < 3) delay(attempt * 2_000L)
            }
            check(succeeded) { "The user retry did not recover" }
            report["first_resend_succeeded"] = attempts.size == 1
            report["ui_resend_completed"] = true
            report["stage"] = "completed"
            report["status"] = "passed"
        } catch (error: Throwable) {
            report["status"] = "failed"
            report["failure_class"] = error.javaClass.simpleName
            report["failure_location"] = error.stackTrace.take(5).map { it.toString() }
            throw IllegalStateException("Live process recovery failed; inspect private diagnostics")
        } finally {
            instrumentation.runOnMainSync { activity?.finish() }
            try {
                auth.deleteAccount(state["synthetic_account_password"].asString)
                report["synthetic_account_deleted"] = true
            } catch (error: Throwable) { report["account_cleanup_failure_class"] = error.javaClass.simpleName }
            database.close()
            File(directory, "observations.json").writeText(Gson().toJson(report))
            File(directory, "state.json").delete()
        }
    }

    private fun auth(database: AppDatabase): AuthManager {
        val config = NetworkModule.provideBackendConfig()
        return AuthManager(NetworkModule.provideAuthApi(NetworkModule.provideAuthRetrofit(config)),
            SessionTokenStore(context), database.personaDao())
    }

    private fun launch(personaId: String): ChatActivity {
        val monitor = instrumentation.addMonitor(ChatActivity::class.java.name, null, false)
        return try {
            instrumentation.runOnMainSync {
                context.startActivity(Intent(context, ChatActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    .putExtra("PERSONA_ID", personaId))
            }
            checkNotNull(monitor.waitForActivityWithTimeout(15_000L)) as ChatActivity
        } finally { instrumentation.removeMonitor(monitor) }
    }

    private fun model(activity: ChatActivity): ChatViewModel {
        lateinit var model: ChatViewModel
        instrumentation.runOnMainSync { model = ViewModelProvider(activity)[ChatViewModel::class.java] }
        return model
    }

    private suspend fun awaitUi(condition: () -> Boolean) = withTimeout(20_000L) {
        var ready = false
        while (!ready) {
            instrumentation.runOnMainSync { ready = condition() }
            if (!ready) delay(50L)
        }
    }
}
