package com.example.persona.evaluation

import com.google.gson.JsonParser

internal data class M5LlmCase(
    val id: String,
    val owner: String,
    val persona: String,
    val cloud: Boolean,
    val asOf: Long,
    val name: String,
    val backstory: String,
    val messages: List<Pair<String, String>>,
    val memories: List<M5Memory>
)

internal data class M5LlmBundle(val split: String, val revision: String, val cases: List<M5LlmCase>)

internal object M5LlmFixtures {
    const val VERSION = "actual-chat-mnn-batch-v1"

    fun read(bytes: ByteArray, digest: String, revision: String): M5LlmBundle {
        require(digest.matches(Regex("[a-f0-9]{64}")) && M5Fixtures.sha256(bytes) == digest)
        require(revision.matches(Regex("sha256:[a-f0-9]{64}")))
        val root = JsonParser().parse(String(bytes, Charsets.UTF_8)).asJsonObject
        require(root["bundle_version"].asString == VERSION)
        require(root["dataset_version"].asString == M5Fixtures.DATASET_VERSION)
        require(root["dataset_sha256"].asString == M5Fixtures.DATASET_SHA256)
        require(root["implementation_revision"].asString == revision)
        val split = root["split"].asString
        require(split in setOf("dev", "heldout"))
        val cases = root.getAsJsonArray("cases").map { value ->
            val row = value.asJsonObject
            require(!row.has("expected") && !row.has("rubric")) { "Answer material must stay on host" }
            require(row["track"].asString == "llm_subjective" && row["synthetic"].asBoolean)
            require(row["dataset_version"].asString == M5Fixtures.DATASET_VERSION)
            require(row["split"].asString == split && row["id"].asString.startsWith("$split-"))
            require(row.getAsJsonArray("events").size() == 0)
            val context = row.getAsJsonObject("context")
            val executor = context["executor"].asString
            require(executor in setOf("local", "cloud"))
            val profile = row.getAsJsonObject("persona_profile")
            val messages = row.getAsJsonArray("messages").map {
                val message = it.asJsonObject
                val role = message["role"].asString
                val content = message["content"].asString
                require(role in setOf("user", "assistant") && content.isNotBlank())
                role to content
            }
            require(messages.isNotEmpty() && messages.last().first == "user")
            val memories = row.getAsJsonArray("memories").map { M5Fixtures.memory(it.asJsonObject) }
            require(memories.all { it.isExplicitManualSnapshot })
            M5LlmCase(row["id"].asString, context["owner"].asString, context["persona"].asString,
                executor == "cloud", M5Fixtures.timestamp(context["as_of"].asString),
                profile["name"].asString, profile["personaBackstory"].asString, messages, memories)
        }
        val expected = if (split == "dev") (19..28).map { "dev-$it" } else (9..12).map { "heldout-%02d".format(it) }
        require(cases.map { it.id }.toSet() == expected.toSet() && cases.size == expected.size)
        return M5LlmBundle(split, revision, cases)
    }
}
