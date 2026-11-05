package com.example.persona.evaluation

import android.content.Context
import android.content.ContextWrapper
import android.content.SharedPreferences
import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.example.persona.core.ai.prompt.MemoryPrompt
import com.example.persona.core.ai.NetworkState
import com.example.persona.core.auth.AuthManager
import com.example.persona.core.auth.SessionTokenStore
import com.example.persona.core.auth.StoredSession
import com.example.persona.data.local.AppDatabase
import com.example.persona.data.local.entity.MemoryEntity
import com.example.persona.data.remote.AuthApi
import com.example.persona.data.repository.RoomMemoryRepository
import com.example.persona.di.DatabaseModule
import com.google.gson.JsonParser
import java.io.File
import java.lang.reflect.Proxy
import java.security.MessageDigest
import java.util.UUID
import kotlinx.coroutines.runBlocking
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class M5RoomAdapterInstrumentedTest {
    @Test(timeout = 180_000L)
    fun exportRealRoomRepositoryAndPromptBoundaries() = runBlocking<Unit> {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val args = InstrumentationRegistry.getArguments()
        assumeTrue(args.getString("runM5RoomAdapter") == "true")
        val withLifecycle = args.getString("runM5Lifecycle") == "true"
        val controlledNetwork = args.getString("runM5ControlledNetwork") == "true"
        check(!controlledNetwork || withLifecycle)
        val context = instrumentation.targetContext
        check(context.packageName == "com.example.persona.validation") { "Isolated validation package required" }
        val runId = checkNotNull(args.getString("m5RunId"))
        check(runId.matches(Regex("[a-z0-9-]{1,40}")))
        val directory = File(context.filesDir, "m5-eval/$runId").canonicalFile
        check(directory.parentFile == File(context.filesDir, "m5-eval").canonicalFile)
        val output = File(directory, "observations.json")
        check(!output.exists()) { "Never overwrite earlier observations" }
        val input = File(directory, "bundle.json")
        check(input.length() in 1..2_000_000L)
        val inputBytes = input.readBytes()
        val bundle = M5Fixtures.read(inputBytes,
            checkNotNull(args.getString("m5BundleSha256")), checkNotNull(args.getString("m5SourceRevision")))
        val rows = mutableListOf<Map<String, Any?>>()
        val report = linkedMapOf<String, Any?>(
            "dataset_version" to M5Fixtures.DATASET_VERSION, "dataset_sha256" to bundle.datasetSha256,
            "implementation_revision" to bundle.implementationRevision,
            "adapter_version" to M5Fixtures.ADAPTER_VERSION,
            "adapter_scope" to if (withLifecycle) "room_and_request_lifecycle" else "room_repository_prompt",
            "executor_transport" to if (withLifecycle) "controlled_synthetic" else "not_executed",
            "network_state_mode" to if (controlledNetwork) "controlled_fallback_with_metered_denial" else "physical",
            "evidence_kind" to "application_instrumentation",
            "evidence_ref" to "files/m5-eval/$runId/observations.json",
            "input_bundle_sha256" to M5Fixtures.sha256(inputBytes),
            "target_apk_sha256" to hashFile(File(context.applicationInfo.sourceDir)),
            "test_apk_sha256" to hashFile(File(instrumentation.context.applicationInfo.sourceDir)),
            "source_binding" to "declared_snapshot_requires_host_build_record",
            "prompt_version" to MemoryPrompt.VERSION, "split" to bundle.split,
            "llm_quality" to "not_run", "observations" to rows
        )
        try {
            for (case in bundle.cases) {
                report["active_case"] = case.id
                if (withLifecycle && case.hasEvents) {
                    val fallback = case.events.any { it.action == M5LifecycleAction.EXECUTOR && it.executor == "cloud" }
                    val runner = M5LifecycleRunner(instrumentation)
                    if (controlledNetwork && fallback) {
                        val control = runner.observe(context, case, NetworkState.METERED, meteredDenial = true)
                        check(control["status"] == "observed") { "Metered denial control did not execute" }
                        report["metered_denial_control"] = mapOf("status" to "passed", "scope" to "control_only",
                            "lifecycle" to control["lifecycle"])
                    }
                    rows += runner.observe(context, case,
                        if (controlledNetwork && fallback) NetworkState.UNMETERED else null)
                    continue
                }
                val reason = case.unsupportedReason()
                if (reason != null) {
                    rows += mapOf("case_id" to case.id, "status" to "unsupported", "actual" to null, "reason" to reason)
                } else {
                    rows += observe(context, case)
                }
            }
            report["execution_status"] = "completed"
        } catch (error: Throwable) {
            report["execution_status"] = "failed"
            report["failure_class"] = error.javaClass.simpleName
            report["failure_location"] = error.stackTrace.take(8).map { it.toString() }
            throw IllegalStateException("M5 component adapter failed; inspect private evidence")
        } finally {
            output.parentFile?.mkdirs()
            check(output.createNewFile())
            output.writeText(M5Fixtures.observationJson(report), Charsets.UTF_8)
        }
    }

    private suspend fun observe(context: Context, case: M5Case): Map<String, Any?> {
        val scopedContext = FixtureContext(context)
        val store = SessionTokenStore(scopedContext)
        val database = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java)
            .addCallback(DatabaseModule.memoryIntegrityCallback).build()
        try {
            store.save(StoredSession("synthetic-offline-adapter-token", case.owner, "fixture@example.invalid"))
            val auth = AuthManager(blockedApi(), store, database.personaDao())
            check(auth.identity.value.ownerId == case.owner)
            val dao = database.memoryDao()
            val snapshots = case.memories.groupBy { it.owner to it.id }.values.map { versions ->
                versions.maxBy { it.revision }
            }
            val byId = snapshots.associateBy { it.physicalId }
            check(byId.size == snapshots.size)
            val now = System.currentTimeMillis()
            for (memory in snapshots) {
                dao.insert(MemoryEntity(
                    id = memory.physicalId, ownerId = memory.owner, personaId = memory.persona,
                    type = memory.type, content = memory.content, sourceMessageId = memory.sourceMessageId,
                    createdAt = M5Fixtures.relativeTime(memory.createdAt, case.asOf, now),
                    updatedAt = M5Fixtures.relativeTime(memory.updatedAt, case.asOf, now),
                    expiresAt = memory.expiresAt?.let { M5Fixtures.relativeTime(it, case.asOf, now) },
                    cloudAllowed = memory.cloudAllowed, revision = memory.revision, deleted = memory.deleted
                ))
            }
            val candidates = dao.selectForPrompt(case.owner, case.persona, case.cloud, System.currentTimeMillis())
            val selected = RoomMemoryRepository(dao, auth).selectForPrompt(case.persona, case.cloud)
            val body = MemoryPrompt.groundQuestion(case.question, selected)
            val quotedFacts = JsonParser().parse(body.substringAfter("已保存资料 JSON：\n")
                .substringBefore("\n\n当前用户问题：\n")).asJsonArray.map { it.asString }
            check(quotedFacts == selected.map { it.content })
            check(body.endsWith("\n\n当前用户问题：\n" + case.question))
            // These are component observations, not executor dispatch or model output.
            return mapOf("case_id" to case.id, "status" to "observed",
                "boundary_scope" to "room_repository_prompt", "actual" to mapOf(
                "candidates" to candidates.map { checkNotNull(byId[it.id]).ref },
                "injected" to selected.map { checkNotNull(byId[it.id]).ref },
                "rejected_writes" to emptyList<String>()
            ))
        } finally {
            database.close()
            store.clear()
            scopedContext.clearFixturePreferences()
        }
    }

    private fun blockedApi(): AuthApi = checkNotNull(AuthApi::class.java.cast(Proxy.newProxyInstance(
        AuthApi::class.java.classLoader, arrayOf(AuthApi::class.java)
    ) { _, _, _ -> error("Network API is forbidden in the component adapter") }))

    private fun hashFile(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buffer = ByteArray(64 * 1024)
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                digest.update(buffer, 0, count)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    private class FixtureContext(base: Context) : ContextWrapper(base) {
        private val prefix = "m5-fixture-${UUID.randomUUID()}-"
        private val preferences = mutableListOf<SharedPreferences>()
        override fun getSharedPreferences(name: String, mode: Int): SharedPreferences =
            super.getSharedPreferences(prefix + name, mode).also { preferences += it }

        fun clearFixturePreferences() {
            preferences.forEach { check(it.edit().clear().commit()) }
        }
    }
}
