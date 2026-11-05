package com.example.persona.features

import android.app.Activity
import android.content.Intent
import android.view.View
import android.widget.CompoundButton
import android.widget.EditText
import androidx.appcompat.widget.SearchView
import androidx.lifecycle.ViewModelProvider
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.example.persona.MainActivity
import com.example.persona.R
import com.example.persona.core.ai.*
import com.example.persona.core.ai.mnn.MnnLocalAiEngine
import com.example.persona.core.ai.mnn.NativeMnnSession
import com.example.persona.core.ai.mnn.NativeSessionFactory
import com.example.persona.core.ai.prompt.PromptAdapterRegistry
import com.example.persona.core.auth.SessionTokenStore
import com.example.persona.data.local.AppDatabase
import com.example.persona.data.local.entity.MemoryEntity
import com.example.persona.data.repository.CloudChatRepository
import com.example.persona.data.repository.HybridAiRepository
import com.example.persona.data.repository.RemotePersonaRepository
import com.example.persona.data.repository.RoomMemoryRepository
import com.example.persona.data.repository.RoomPersonaRepository
import com.example.persona.data.repository.SwitchingPersonaRepository
import com.example.persona.di.NetworkModule
import com.example.persona.domain.model.Persona
import com.example.persona.domain.model.MessageStatus
import com.example.persona.evaluation.M5ActualChatRound
import com.example.persona.evaluation.M5Fixtures
import com.example.persona.evaluation.M5LlmFixtures
import com.example.persona.features.auth.AuthActivity
import com.example.persona.features.chat.ChatActivity
import com.example.persona.features.chat.ChatViewModel
import com.example.persona.features.creation.CreatePersonaActivity
import com.google.android.material.bottomnavigation.BottomNavigationView
import com.google.android.material.chip.ChipGroup
import com.google.gson.GsonBuilder
import java.io.File
import java.util.UUID
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class P2OnlineAcceptanceInstrumentedTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext
    private val activities = mutableListOf<Activity>()

    @Test(timeout = 900_000L)
    fun actualRegistrationCreationDiscoveryAndCloudProbeUseIsolatedBackend() = runBlocking<Unit> {
        val args = InstrumentationRegistry.getArguments()
        assumeTrue(args.getString("runP2Online") == "true")
        check(context.packageName == "com.example.persona.validation")
        val config = NetworkModule.provideBackendConfig()
        check(config.isConfigured && config.baseUrl == args.getString("p2QaBackendUrl"))
        val runId = checkNotNull(args.getString("p2QaRunId"))
        check(runId.matches(Regex("[a-z0-9-]{1,40}")))
        val directory = File(context.filesDir, "p2-online/$runId")
        val output = File(directory, "observations.json")
        check(!output.exists())
        val bytes = File(directory, "bundle.json").readBytes()
        val bundle = M5LlmFixtures.read(bytes, checkNotNull(args.getString("m5BundleSha256")),
            checkNotNull(args.getString("m5SourceRevision")))
        check(bundle.split == "heldout")
        val case = bundle.cases.single { it.id == "heldout-12" }
        val records = mutableListOf<Map<String, Any?>>()
        val report = linkedMapOf<String, Any?>("kind" to "actual_cloud_subjective_probe",
            "synthetic_data_only" to true, "dataset_version" to M5Fixtures.DATASET_VERSION,
            "dataset_sha256" to M5Fixtures.DATASET_SHA256, "split" to "heldout",
            "case_id" to case.id, "source_revision" to bundle.revision,
            "backend_url" to config.baseUrl, "model_id" to config.modelId,
            "provider_revision" to "unsupported", "parameters" to GenerationParams(),
            "records" to records, "execution_status" to "running", "human_review" to "pending",
            "quick_tunnel_official_sse_support" to false)
        val password = "Persona-${UUID.randomUUID()}!"
        var main: MainActivity? = null
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        var hybrid: HybridAiRepository? = null
        try {
            val auth = launch(AuthActivity::class.java) {
                context.startActivity(Intent(context, AuthActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            }
            awaitUi { auth.findViewById<View>(R.id.btn_go_to_sign_up) != null }
            instrumentation.runOnMainSync { auth.findViewById<View>(R.id.btn_go_to_sign_up).performClick() }
            awaitUi { auth.findViewById<View>(R.id.et_confirm_password) != null }
            main = launch(MainActivity::class.java) {
                auth.findViewById<EditText>(R.id.et_email).setText("p2-ui-${UUID.randomUUID()}@example.test")
                auth.findViewById<EditText>(R.id.et_password).setText(password)
                auth.findViewById<EditText>(R.id.et_confirm_password).setText(password)
                auth.findViewById<View>(R.id.btn_sign_up).performClick()
            }
            val dashboard = main
            check(dashboard.authManager.identity.value.token != null)
            report["actual_registration"] = "passed"
            instrumentation.runOnMainSync { dashboard.findViewById<BottomNavigationView>(R.id.bottom_nav).selectedItemId = R.id.nav_profile }
            awaitUi { dashboard.findViewById<View>(R.id.cardCreate) != null }
            val creation = launch(CreatePersonaActivity::class.java) { dashboard.findViewById<View>(R.id.cardCreate).performClick() }
            instrumentation.runOnMainSync {
                creation.findViewById<EditText>(R.id.etName).setText("P2 online fixture")
                creation.findViewById<EditText>(R.id.etBackstory).setText("Synthetic online navigation acceptance.")
                creation.findViewById<ChipGroup>(R.id.chipGroupTraits).getChildAt(0).performClick()
                creation.findViewById<CompoundButton>(R.id.switchPublic).isChecked = true
                creation.findViewById<View>(R.id.btnCreate).performClick()
            }
            awaitUi { rows(dashboard, R.id.rvMyPersonas).any { it.name == "P2 online fixture" } }
            instrumentation.runOnMainSync { dashboard.findViewById<BottomNavigationView>(R.id.bottom_nav).selectedItemId = R.id.nav_feed }
            awaitUi { rows(dashboard, R.id.rvFeed).any { it.name == "P2 online fixture" } }
            instrumentation.runOnMainSync { dashboard.findViewById<BottomNavigationView>(R.id.bottom_nav).selectedItemId = R.id.nav_discovery }
            awaitUi { rows(dashboard, R.id.rvDiscovery).any { it.name == "P2 online fixture" } }
            instrumentation.runOnMainSync { dashboard.findViewById<SearchView>(R.id.svDiscovery).setQuery("P2 online fixture", false) }
            awaitUi { rows(dashboard, R.id.rvDiscovery).size == 1 &&
                dashboard.findViewById<RecyclerView>(R.id.rvDiscovery).findViewHolderForAdapterPosition(0) != null }
            val chat = launch(ChatActivity::class.java) {
                dashboard.findViewById<RecyclerView>(R.id.rvDiscovery).findViewHolderForAdapterPosition(0)!!.itemView.performClick()
            }
            lateinit var chatModel: ChatViewModel
            instrumentation.runOnMainSync { chatModel = ViewModelProvider(chat)[ChatViewModel::class.java] }
            awaitUi { chatModel.currentPersona.value?.name == "P2 online fixture" }
            report["actual_create_feed_discovery_chat_navigation"] = "passed"
            instrumentation.runOnMainSync {
                chat.findViewById<EditText>(R.id.etMessage).setText("@cloud 合成界面取消测试")
                chat.findViewById<View>(R.id.btnSend).performClick()
                check(chatModel.isGenerating.value)
                chat.findViewById<View>(R.id.btnSend).performClick()
                check(!chatModel.isGenerating.value)
                chat.findViewById<EditText>(R.id.etMessage).setText("@cloud 这是一条合成界面连通性测试，请简短回应。")
                chat.findViewById<View>(R.id.btnSend).performClick()
                check(chatModel.isGenerating.value)
            }
            withTimeout(180_000L) { while (chatModel.isGenerating.value) delay(100L) }
            val database = Room.databaseBuilder(context, AppDatabase::class.java, "persona_db_v2").build()
            try {
                val personaId = checkNotNull(chatModel.currentPersona.value).id
                val reply = database.messageDao().getRecentMessagesByPersonaId(personaId,
                    dashboard.authManager.identity.value.ownerId, 2).first { !it.isFromUser }
                check(reply.status == MessageStatus.NORMAL.name && reply.content.isNotBlank())
                report["ui_send_cancel_before_dispatch_and_resend"] = "passed"
            } finally { database.close() }

            val client = NetworkModule.provideOkHttpClient(dashboard.authManager, config)
            val sse = NetworkModule.provideSseOkHttpClient(client)
            val transport = NetworkModule.provideCloudChatApi(NetworkModule.provideSseRetrofit(sse, config))
            val cloudCalls = java.util.concurrent.atomic.AtomicInteger()
            val api = object : com.example.persona.data.remote.CloudChatApi by transport {
                override fun streamChat(request: com.example.persona.data.remote.dto.ChatRequest,
                    authorization: String?): retrofit2.Call<okhttp3.ResponseBody> {
                    cloudCalls.incrementAndGet()
                    return transport.streamChat(request, authorization)
                }
            }
            val cloud = CloudChatRepository(api, config, dashboard.authManager)
            val validator = LocalModelValidator(AndroidSymlinkChecker)
            val installer = ModelPackageInstaller(context, validator, AndroidSymlinkChecker)
            val manager = LocalModelManager(context, validator, installer)
            val engine = MnnLocalAiEngine(PromptAdapterRegistry(), NativeSessionFactory { NativeMnnSession() })
            val network = RoutingContextProvider(context)
            val repository = HybridAiRepository(cloud, engine, manager, HybridRouter(), network::networkState, scope)
            hybrid = repository
            for (repetition in 1..3) {
                val observation = M5ActualChatRound(instrumentation).run(case, repository, dashboard.authManager)
                records += observation + mapOf("case_id" to case.id, "repetition" to repetition)
                check(observation["executor"] == "CLOUD")
                check((observation["authorized_evidence_refs"] as List<*>).isEmpty())
            }
            check(records.all { it["status"] == "completed" }) { "Cloud probe has failed generations" }
            val cleanupDatabase = Room.databaseBuilder(context, AppDatabase::class.java, "persona_db_v2").build()
            try {
                val ownerId = dashboard.authManager.identity.value.ownerId
                val personaId = checkNotNull(chatModel.currentPersona.value).id
                val memoryRepository = RoomMemoryRepository(cleanupDatabase.memoryDao(), dashboard.authManager)
                val curator = com.example.persona.data.repository.RoomMemoryCurationRepository(
                    cleanupDatabase, dashboard.authManager, memoryRepository, repository)
                val sourceId = "p3-cloud-source-${UUID.randomUUID()}"
                cleanupDatabase.messageDao().insertMessage(com.example.persona.data.local.entity.MessageEntity(
                    sourceId, personaId, ownerId, "我喜欢简短的技术解释。", true, System.currentTimeMillis()))
                curator.configure(true, false)
                val before = cloudCalls.get()
                check(runCatching { curator.proposeWithModel(personaId, sourceId, true) }.isFailure)
                check(cloudCalls.get() == before)
                curator.configure(true, true)
                val started = android.os.SystemClock.elapsedRealtime()
                val extraction = runCatching { curator.proposeWithModel(personaId, sourceId, true) }
                check(cloudCalls.get() == before + 1)
                val candidates = curator.observe(personaId).first()
                check(candidates.all { "我喜欢简短的技术解释。".contains(it.content) })
                if (candidates.isNotEmpty()) {
                    val saved = curator.confirm(candidates.first().id, candidates.first().content, candidates.first().type)
                    check(!saved.cloudAllowed && saved.ownerId == ownerId)
                    memoryRepository.delete(saved.id, saved.revision)
                }
                curator.configure(true, false)
                check(runCatching { curator.proposeWithModel(personaId, sourceId, true) }.isFailure)
                check(cloudCalls.get() == before + 1)
                report["p3_real_cloud_extraction"] = mapOf(
                    "calls" to 1, "status" to if (extraction.isSuccess) "completed" else "rejected",
                    "failure_class" to extraction.exceptionOrNull()?.javaClass?.simpleName,
                    "candidates" to candidates.map { mapOf("type" to it.type.name, "content" to it.content) },
                    "duration_ms" to (android.os.SystemClock.elapsedRealtime() - started),
                    "independent_authorization_and_revocation" to "passed")
                val memoryId = "p2-delete-${UUID.randomUUID()}"
                val otherId = "$memoryId-other"
                val now = System.currentTimeMillis()
                cleanupDatabase.memoryDao().insert(MemoryEntity(memoryId, ownerId, personaId,
                    "FACT", "Synthetic owned memory", createdAt = now, updatedAt = now))
                cleanupDatabase.memoryDao().insert(MemoryEntity(otherId, "synthetic-other-owner", personaId,
                    "FACT", "Synthetic isolation control", createdAt = now, updatedAt = now))
                val remote = RemotePersonaRepository(NetworkModule.providePersonaBackendApi(
                    NetworkModule.provideRetrofit(client, config)))
                val store = SessionTokenStore(context)
                val personas = SwitchingPersonaRepository(
                    RoomPersonaRepository(cleanupDatabase.personaDao(), dashboard.authManager),
                    remote, dashboard.authManager,
                    RoomMemoryRepository(cleanupDatabase.memoryDao(), dashboard.authManager), store
                )
                personas.deletePersona(personaId)
                check(remote.getPersonaById(personaId) == null)
                val removed = checkNotNull(cleanupDatabase.memoryDao().findOwned(memoryId, ownerId))
                check(removed.deleted && removed.content.isEmpty())
                check(cleanupDatabase.memoryDao().findOwned(otherId, "synthetic-other-owner")?.deleted == false)
                check(personaId !in store.pendingPersonaDeletions(ownerId))
                report["actual_owned_persona_delete_and_memory_cleanup"] = "passed"
                report["persona_deletion_preserved_other_owner_memory"] = true
            } finally { cleanupDatabase.close() }
            report["execution_status"] = "completed"
        } catch (error: Throwable) {
            report["execution_status"] = "failed"
            report["failure_class"] = error.javaClass.simpleName
            report["failure_location"] = error.stackTrace.take(6).map { it.toString() }
            throw IllegalStateException("Online acceptance failed; inspect private diagnostics")
        } finally {
            hybrid?.clearLocalModel()
            scope.cancel()
            val auth = main?.authManager
            if (auth?.identity?.value?.token != null) {
                try {
                    auth.deleteAccount(password)
                    report["synthetic_account_deleted"] = true
                } catch (error: Throwable) { report["account_cleanup_failure_class"] = error.javaClass.simpleName }
            }
            instrumentation.runOnMainSync { activities.asReversed().forEach { it.finish() } }
            directory.mkdirs()
            check(output.createNewFile())
            output.writeText(GsonBuilder().serializeNulls().create().toJson(report))
        }
    }

    private suspend fun awaitUi(condition: () -> Boolean) = withTimeout(30_000L) {
        var ready = false
        while (!ready) {
            instrumentation.runOnMainSync { ready = condition() }
            if (!ready) delay(50L)
        }
    }

    private fun rows(activity: Activity, id: Int): List<Persona> =
        (activity.findViewById<RecyclerView>(id)?.adapter as? ListAdapter<*, *>)
            ?.currentList?.filterIsInstance<Persona>().orEmpty()

    private fun <T : Activity> launch(type: Class<T>, action: () -> Unit): T {
        val monitor = instrumentation.addMonitor(type.name, null, false)
        return try {
            instrumentation.runOnMainSync(action)
            type.cast(checkNotNull(monitor.waitForActivityWithTimeout(30_000L)))!!.also { activities += it }
        } finally { instrumentation.removeMonitor(monitor) }
    }
}
