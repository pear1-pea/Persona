package com.example.persona.evaluation

import com.google.gson.Gson
import com.google.gson.GsonBuilder
import com.google.gson.JsonObject
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.text.ParsePosition
import java.text.SimpleDateFormat
import java.util.Locale

internal data class M5Bundle(
    val split: String,
    val datasetSha256: String,
    val implementationRevision: String,
    val cases: List<M5Case>
)

internal data class M5Case(
    val id: String,
    val owner: String,
    val persona: String,
    val cloud: Boolean,
    val asOf: Long,
    val question: String,
    val memories: List<M5Memory>,
    val events: List<M5LifecycleEvent>
) {
    val hasEvents: Boolean get() = events.isNotEmpty()

    fun unsupportedReason(): String? = when {
        hasEvents -> "Request lifecycle and executor transitions are outside this component adapter"
        memories.any { !it.isExplicitManualSnapshot } ->
            "Automatic candidate confirmation/provenance has no production entry point"
        else -> null
    }

    fun lifecycleUnsupportedReason(): String? = when {
        memories.any { !it.isExplicitManualSnapshot } ->
            "Automatic candidate confirmation/provenance has no production entry point"
        events.any { it.action == M5LifecycleAction.EXECUTOR && it.executor == "local" } ->
            "Cloud-to-local automatic fallback is not implemented by the application"
        else -> null
    }
}

internal data class M5Memory(
    val ref: String,
    val id: String,
    val owner: String,
    val persona: String?,
    val type: String,
    val content: String,
    val sourceMessageId: String?,
    val createdAt: Long,
    val updatedAt: Long,
    val expiresAt: Long?,
    val cloudAllowed: Boolean,
    val revision: Int,
    val deleted: Boolean,
    val isExplicitManualSnapshot: Boolean
) {
    // Encode UTF-16 units so fixture ID order survives owner-qualified physical IDs.
    val physicalId: String get() = id.map { "%04x".format(Locale.ROOT, it.code) }.joinToString("") +
        "!" + M5Fixtures.sha256(owner.toByteArray(StandardCharsets.UTF_8))
}

internal object M5Fixtures {
    const val DATASET_VERSION = "m5-zh-explicit-v2"
    const val DATASET_SHA256 = "3cda2cbd2964c3de2ac17325a822cda1aeb24dc554cae562bcd444641d5fc48e"
    const val ADAPTER_VERSION = "room-and-lifecycle-boundary-v4"

    fun observationJson(report: Map<String, Any?>): String = GsonBuilder().serializeNulls().create().toJson(report)

    fun read(bytes: ByteArray, expectedDigest: String, expectedRevision: String): M5Bundle {
        require(expectedDigest.matches(Regex("[a-f0-9]{64}")) && sha256(bytes) == expectedDigest) {
            "Fixture bundle digest mismatch"
        }
        val root = Gson().fromJson(String(bytes, StandardCharsets.UTF_8), JsonObject::class.java)
        require(root.text("bundle_version") == "m5-room-adapter-input-v1")
        require(root.text("dataset_version") == DATASET_VERSION)
        require(root.text("dataset_sha256") == DATASET_SHA256)
        val revision = root.text("implementation_revision")
        require(revision.matches(Regex("sha256:[a-f0-9]{64}")) && revision == expectedRevision)
        val snapshot = root.getAsJsonObject("source_snapshot")
        require(snapshot.entrySet().isNotEmpty())
        snapshot.entrySet().forEach { (path, hash) ->
            require(!path.startsWith("/") && !path.contains('\\') && !path.contains(':'))
            require(path.split('/').none { it in setOf("", ".", "..") })
            require(hash.asString.matches(Regex("[a-f0-9]{64}")))
        }
        val treeDigest = MessageDigest.getInstance("SHA-256")
        snapshot.entrySet().sortedWith { left, right ->
            val a = left.key.toByteArray(StandardCharsets.UTF_8)
            val b = right.key.toByteArray(StandardCharsets.UTF_8)
            var order = 0
            for (index in 0 until minOf(a.size, b.size)) {
                order = (a[index].toInt() and 255).compareTo(b[index].toInt() and 255)
                if (order != 0) break
            }
            if (order == 0) a.size.compareTo(b.size) else order
        }.forEach { (path, hash) ->
            treeDigest.update(path.toByteArray(StandardCharsets.UTF_8))
            treeDigest.update(0.toByte())
            treeDigest.update(hash.asString.chunked(2).map { it.toInt(16).toByte() }.toByteArray())
        }
        require("sha256:" + treeDigest.digest().joinToString("") { "%02x".format(Locale.ROOT, it) } == revision) {
            "Source snapshot digest mismatch"
        }
        val split = root.text("split")
        val count = when (split) { "dev" -> 18; "heldout" -> 8; "all" -> 26; else -> error("Invalid split") }
        val cases = root.getAsJsonArray("cases").map { value ->
            val row = value.asJsonObject
            require(!row.has("expected") && !row.has("rubric")) { "Oracle/rubric must not enter adapter input" }
            require(row.text("dataset_version") == DATASET_VERSION && row.get("synthetic").asBoolean)
            require(row.text("track") == "deterministic")
            require(row.text("split") in if (split == "all") setOf("dev", "heldout") else setOf(split))
            require(row.text("id").startsWith(row.text("split") + "-"))
            val context = row.getAsJsonObject("context")
            val executor = context.text("executor")
            require(executor in setOf("local", "cloud"))
            val messages = row.getAsJsonArray("messages").map { it.asJsonObject }
            val question = messages.last { it.text("role") == "user" }.text("content")
            val memories = row.getAsJsonArray("memories").map { memory(it.asJsonObject) }
            require(memories.map { it.ref }.distinct().size == memories.size)
            require(memories.map { Triple(it.owner, it.id, it.revision) }.distinct().size == memories.size)
            M5Case(row.text("id"), context.text("owner"), context.text("persona"), executor == "cloud",
                timestamp(context.text("as_of")), question, memories,
                row.getAsJsonArray("events").map { M5LifecycleEvent.read(it.asJsonObject) })
        }
        require(cases.size == count && cases.map { it.id }.distinct().size == count)
        return M5Bundle(split, DATASET_SHA256, revision, cases)
    }

    fun sha256(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256").digest(bytes)
        .joinToString("") { "%02x".format(Locale.ROOT, it) }

    fun timestamp(value: String): Long {
        val position = ParsePosition(0)
        val date = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ssXXX", Locale.ROOT).apply { isLenient = false }
            .parse(value, position)
        require(date != null && position.index == value.length) { "Invalid fixture timestamp" }
        return date.time
    }

    fun relativeTime(value: Long, asOf: Long, now: Long): Long =
        Math.addExact(now, Math.subtractExact(value, asOf))

    internal fun memory(row: JsonObject): M5Memory {
        val source = row.getAsJsonObject("save_source")
        val kind = row.text("kind").uppercase(Locale.ROOT)
        require(kind in setOf("PREFERENCE", "FACT", "GOAL", "RELATIONSHIP"))
        val version = row.get("version").asInt
        require(version > 0)
        return M5Memory(row.text("ref"), row.text("memory_id"), row.text("owner"), row.nullableText("persona"),
            kind, row.text("content"), row.nullableText("source_message_id"),
            timestamp(row.text("created_at")), timestamp(row.text("updated_at")),
            row.nullableText("expires_at")?.let(::timestamp), row.get("cloud_allowed").asBoolean,
            version, row.get("deleted").asBoolean,
            source.text("mode") == "manual" && source.get("confirmed").asBoolean &&
                row.nullableText("source_message_id") == null)
    }

    private fun JsonObject.text(key: String): String = get(key).asString
    private fun JsonObject.nullableText(key: String): String? = get(key).takeUnless { it.isJsonNull }?.asString
}
