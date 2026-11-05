package com.example.persona.benchmark

import com.example.persona.core.memory.MemoryRanker
import com.example.persona.domain.model.Memory
import com.example.persona.domain.model.MemoryType
import com.google.gson.Gson
import java.io.File

private data class CorpusRow(
    val id: String = "", val content: String = "", val updatedAt: Long = 0,
    val ownerId: String = "owner", val personaId: String? = "p",
    val cloudAllowed: Boolean = false, val deleted: Boolean = false, val expiresAt: Long? = null
)
private data class RetrievalCase(
    val id: String = "", val query: String = "", val expected: List<String> = emptyList(),
    val rows: List<CorpusRow> = emptyList(),
    val cloud: Boolean = false
)

private fun budget(rows: List<Memory>): List<String> {
    var characters = 0
    return rows.take(4).filter { row ->
        (characters + row.content.length <= 1200).also { if (it) characters += row.content.length }
    }.map { it.id }
}

private fun hostTimings(rows: List<Memory>, query: String, warmup: Int, repetitions: Int): Map<String, Any> {
    var checksum = 0L
    repeat(warmup) {
        checksum += MemoryRanker.rank(rows, query).size
    }
    val samples = LongArray(repetitions)
    repeat(repetitions) { index ->
        val start = System.nanoTime()
        val ranked = MemoryRanker.rank(rows, query)
        samples[index] = System.nanoTime() - start
        checksum += ranked.size
    }
    val sorted = samples.sorted()
    fun percentile(percent: Int): Long = sorted[((repetitions.toLong() * percent + 99) / 100 - 1).toInt()]
    return mapOf(
        "scope" to "Host JVM MemoryRanker.rank only, including query/content tokenization and sorting",
        "warmup_iterations" to warmup, "measured_iterations" to repetitions,
        "candidate_count" to rows.size, "candidate_content_utf16_units" to rows.sumOf { it.content.length },
        "elapsed_ns" to samples.toList(), "min_ns" to sorted.first(), "max_ns" to sorted.last(),
        "p50_ns" to percentile(50), "p95_ns" to percentile(95), "p99_ns" to percentile(99),
        "result_count_checksum" to checksum
    )
}

fun main(args: Array<String>) {
    require(args.size == 3) { "Expected samples path, warmup iterations and measured iterations" }
    val warmup = args[1].toInt()
    val repetitions = args[2].toInt()
    require(warmup >= 0 && repetitions > 0)
    val gson = Gson()
    val cases = gson.fromJson(File(args[0]).readText(), Array<RetrievalCase>::class.java)
    val results = cases.map { case ->
        val visible = case.rows.filter {
            it.ownerId == "owner" && (it.personaId == "p" || it.personaId == null) &&
                !it.deleted && (it.expiresAt == null || it.expiresAt > 1000) &&
                (!case.cloud || it.cloudAllowed) && it.content.isNotBlank() && it.content.length <= 400
        }.map { row ->
            Memory(row.id, row.ownerId, row.personaId, MemoryType.FACT, row.content,
                createdAt = 1, updatedAt = row.updatedAt, cloudAllowed = row.cloudAllowed)
        }.sortedWith(compareByDescending<Memory> { it.updatedAt }.thenBy { it.id })
        mapOf(
            "id" to case.id, "query" to case.query, "expected" to case.expected,
            "recent" to budget(visible), "ranker" to budget(MemoryRanker.rank(visible, case.query)),
            "host_timings" to hostTimings(visible, case.query, warmup, repetitions),
            "queryTerms" to MemoryRanker.terms(case.query).sorted(),
            "candidates" to visible.map {
                mapOf("id" to it.id, "content" to it.content, "terms" to MemoryRanker.terms(it.content).sorted())
            }
        )
    }
    println(gson.toJson(results))
}
