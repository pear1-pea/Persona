package com.example.persona.evaluation

import com.google.gson.Gson
import com.google.gson.JsonObject
import java.security.MessageDigest
import org.junit.Assert.*
import org.junit.Test

class M5FixturesTest {
    @Test
    fun unsupportedObservationRetainsExplicitNullInTheWireFormat() {
        val row = mapOf("case_id" to "dev-09", "status" to "unsupported", "actual" to null,
            "reason" to "Request lifecycle not wired")
        val json = M5Fixtures.observationJson(mapOf("observations" to listOf(row)))
        val actualRow = Gson().fromJson(json, JsonObject::class.java).getAsJsonArray("observations")[0].asJsonObject
        assertTrue(actualRow.has("actual"))
        assertTrue(actualRow["actual"].isJsonNull)
        assertEquals("unsupported", actualRow["status"].asString)
    }

    @Test
    fun sourceSnapshotCannotChangeWithoutChangingDeclaredRevision() {
        val root = fixture()
        root.getAsJsonObject("source_snapshot").addProperty("app/src/main/Changed.kt", "b".repeat(64))
        assertRejected(root)
    }

    @Test
    fun expectedAnswersAndDuplicateCasesAreRejected() {
        val root = fixture()
        root.getAsJsonArray("cases")[0].asJsonObject.add("expected", JsonObject())
        assertRejected(root)
        val duplicate = fixture()
        duplicate.getAsJsonArray("cases")[1].asJsonObject.addProperty("id", "dev-01")
        assertRejected(duplicate)
    }

    @Test
    fun digestAndRevisionAreBoundToTheInputBytes() {
        val root = fixture()
        val bytes = root.toString().toByteArray()
        assertThrows(IllegalArgumentException::class.java) {
            M5Fixtures.read(bytes, "0".repeat(64), root["implementation_revision"].asString)
        }
        assertThrows(IllegalArgumentException::class.java) {
            M5Fixtures.read(bytes, M5Fixtures.sha256(bytes), "sha256:" + "0".repeat(64))
        }
        assertEquals(18, read(root).cases.size)
    }

    @Test
    fun sourcePathsCannotEscapeTheDeclaredTree() {
        val root = fixture()
        root.getAsJsonObject("source_snapshot").addProperty("../local.properties", "b".repeat(64))
        assertRejected(root)
    }

    @Test
    fun utf8SourceOrderingMatchesThePythonSnapshotProtocol() {
        val root = fixture()
        val snapshot = JsonObject().apply {
            addProperty("app/src/main/测试.kt", "a".repeat(64))
            addProperty("app/src/main/😀.kt", "b".repeat(64))
            addProperty("app/src/main/\uE000.kt", "c".repeat(64))
        }
        root.add("source_snapshot", snapshot)
        root.addProperty("implementation_revision", "sha256:73c70370e6599f304c3d90ad04ba03fbddba4b9a97f4d4f4b6987034f238401e")
        assertEquals(18, read(root).cases.size)
    }

    @Test
    fun qualifiedIdsKeepFixtureOrderingAndSeparateOwners() {
        fun memory(id: String, owner: String) = M5Memory("ref", id, owner, "p", "FACT", "fact",
            null, 0L, 0L, null, false, 1, false, true)
        val ids = listOf("a", "a-", "a:", "aa", "Ω", "😀", "\uE000")
        assertEquals(ids.sorted(), ids.sortedBy { memory(it, "owner").physicalId })
        assertNotEquals(memory("same", "one").physicalId, memory("same", "two").physicalId)
    }

    @Test
    fun relativeClockPreservesExpiryBoundaryWithoutChangingDeviceClock() {
        val asOf = M5Fixtures.timestamp("2026-10-29T08:00:00Z")
        assertEquals(asOf, M5Fixtures.timestamp("2026-10-29T09:00:00+01:00"))
        assertEquals(100L, M5Fixtures.relativeTime(asOf, asOf, 100L))
        assertEquals(1100L, M5Fixtures.relativeTime(asOf + 1000L, asOf, 100L))
        assertThrows(IllegalArgumentException::class.java) { M5Fixtures.timestamp("2026-02-30T08:00:00Z") }
        assertThrows(IllegalArgumentException::class.java) { M5Fixtures.timestamp("2026-10-29T08:00:00") }
    }

    @Test
    fun unmappedLifecycleIsUnsupportedRatherThanInventingAnObservation() {
        val root = fixture()
        root.getAsJsonArray("cases")[0].asJsonObject.getAsJsonArray("events").add(JsonObject().apply {
            addProperty("action", "cancel")
        })
        val cases = read(root).cases
        assertNotNull(cases.first().unsupportedReason())
        assertNull(cases.last().unsupportedReason())
        assertEquals(M5LifecycleAction.CANCEL, cases.first().events.single().action)
        assertNull(cases.first().lifecycleUnsupportedReason())
    }

    @Test
    fun lifecycleDecoderRejectsUnknownActionsAndNeverClaimsCloudToLocalIsImplemented() {
        val unknown = fixture()
        unknown.getAsJsonArray("cases")[0].asJsonObject.getAsJsonArray("events").add(JsonObject().apply {
            addProperty("action", "invented-auto-upload")
        })
        assertRejected(unknown)
        val executor = fixture()
        executor.getAsJsonArray("cases")[0].asJsonObject.getAsJsonArray("events").add(JsonObject().apply {
            addProperty("action", "executor"); addProperty("value", "local")
        })
        assertNotNull(read(executor).cases.first().lifecycleUnsupportedReason())
    }

    private fun read(root: JsonObject): M5Bundle {
        val bytes = root.toString().toByteArray()
        return M5Fixtures.read(bytes, M5Fixtures.sha256(bytes), root["implementation_revision"].asString)
    }

    private fun assertRejected(root: JsonObject) {
        assertThrows(IllegalArgumentException::class.java) { read(root) }
    }

    private fun fixture(): JsonObject {
        val path = "app/src/main/Fixture.kt"
        val fileDigest = "a".repeat(64)
        val tree = MessageDigest.getInstance("SHA-256")
        tree.update(path.toByteArray())
        tree.update(0.toByte())
        tree.update(ByteArray(32) { 0xaa.toByte() })
        val revision = "sha256:" + tree.digest().joinToString("") { "%02x".format(it) }
        val cases = (1..18).map { index -> mapOf(
            "id" to "dev-%02d".format(index), "split" to "dev", "dataset_version" to M5Fixtures.DATASET_VERSION,
            "track" to "deterministic", "synthetic" to true,
            "context" to mapOf("owner" to "owner", "persona" to "persona", "executor" to "local",
                "as_of" to "2026-10-29T08:00:00Z"),
            "messages" to listOf(mapOf("role" to "user", "content" to "synthetic question")),
            "memories" to emptyList<Any>(), "events" to emptyList<Any>()
        ) }
        return Gson().toJsonTree(mapOf("bundle_version" to "m5-room-adapter-input-v1",
            "dataset_version" to M5Fixtures.DATASET_VERSION, "dataset_sha256" to M5Fixtures.DATASET_SHA256,
            "split" to "dev", "implementation_revision" to revision,
            "source_snapshot" to mapOf(path to fileDigest), "cases" to cases)).asJsonObject
    }
}
