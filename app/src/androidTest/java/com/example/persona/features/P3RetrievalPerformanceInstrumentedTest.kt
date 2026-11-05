package com.example.persona.features

import android.content.ContextWrapper
import android.content.SharedPreferences
import android.os.Build
import android.os.PowerManager
import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.example.persona.core.auth.AuthManager
import com.example.persona.core.auth.SessionTokenStore
import com.example.persona.data.local.AppDatabase
import com.example.persona.data.local.entity.MemoryEntity
import com.example.persona.data.remote.AuthApi
import com.example.persona.data.repository.RoomMemoryRepository
import com.google.gson.Gson
import java.io.File
import java.lang.reflect.Proxy
import java.util.UUID
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class P3RetrievalPerformanceInstrumentedTest {
    @Test(timeout = 180_000L)
    fun fixedThousandRowCorpusMeetsDeclaredRetrievalBudget() = runBlocking<Unit> {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val args = InstrumentationRegistry.getArguments()
        assumeTrue(args.getString("runP3Perf") == "true")
        check(context.packageName == "com.example.persona.validation")
        if (Build.VERSION.SDK_INT >= 29) {
            check((context.getSystemService(android.content.Context.POWER_SERVICE) as PowerManager)
                .currentThermalStatus < PowerManager.THERMAL_STATUS_SEVERE)
        }
        val name = "p3-perf-${UUID.randomUUID()}"
        val database = Room.databaseBuilder(context, AppDatabase::class.java, name).build()
        val scoped = object : ContextWrapper(context) {
            override fun getSharedPreferences(key: String, mode: Int): SharedPreferences =
                super.getSharedPreferences("$name-$key", mode)
        }
        val api = AuthApi::class.java.cast(Proxy.newProxyInstance(AuthApi::class.java.classLoader,
            arrayOf(AuthApi::class.java)) { _, _, _ -> error("Network is forbidden") })
        val auth = AuthManager(api, SessionTokenStore(scoped), database.personaDao())
        val repository = RoomMemoryRepository(database.memoryDao(), auth)
        val owner = auth.identity.value.ownerId
        // Freeze these acceptance budgets before measuring this independent corpus.
        val p95BudgetMs = 250.0
        val retainedHeapBudgetBytes = 16L * 1024L * 1024L
        val report = linkedMapOf<String, Any?>("rows" to 1001, "warmups" to 5, "samples" to 30,
            "p95_budget_ms" to p95BudgetMs, "retained_java_heap_budget_bytes" to retainedHeapBudgetBytes,
            "source_revision" to args.getString("p3SourceRevision"), "status" to "running")
        val output = File(context.filesDir, "p3-performance/observations.json")
        try {
            for (i in 1..1000) database.memoryDao().insert(MemoryEntity("n-$i", owner, "p", "FACT",
                "无关测试条目，项目编号 $i", createdAt = 1, updatedAt = i.toLong()))
            database.memoryDao().insert(MemoryEntity("target", owner, "p", "PREFERENCE",
                "我喜欢古典音乐", createdAt = 1, updatedAt = 0))
            repeat(5) { assertEquals(listOf("target"), repository.selectForPrompt("p", false, "音乐").map { it.id }) }
            val runtime = Runtime.getRuntime()
            runtime.gc()
            delay(500)
            val before = runtime.totalMemory() - runtime.freeMemory()
            val samples = (1..30).map {
                val start = System.nanoTime()
                assertEquals(listOf("target"), repository.selectForPrompt("p", false, "音乐").map { it.id })
                (System.nanoTime() - start) / 1_000_000.0
            }.sorted()
            runtime.gc()
            delay(500)
            val retained = (runtime.totalMemory() - runtime.freeMemory() - before).coerceAtLeast(0)
            report["p50_ms"] = samples[14]
            report["p95_ms"] = samples[28]
            report["retained_java_heap_bytes"] = retained
            report["samples_ms"] = samples
            assertTrue("Retrieval p95 exceeded the declared budget", samples[28] <= p95BudgetMs)
            assertTrue("Retained Java heap exceeded the declared budget", retained <= retainedHeapBudgetBytes)
            report["status"] = "passed"
        } finally {
            output.parentFile?.mkdirs()
            output.writeText(Gson().toJson(report))
            database.close()
            context.deleteDatabase(name)
        }
    }
}
