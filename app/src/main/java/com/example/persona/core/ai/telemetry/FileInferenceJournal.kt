package com.example.persona.core.ai.telemetry

import kotlinx.coroutines.CopyableThrowable
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.nio.ByteBuffer

/** Use one instance per private journal directory; this mutex is process-local. */
class FileInferenceJournal(
    private val directory: File,
    private val clock: () -> Long = { System.currentTimeMillis() },
    private val retentionMs: Long = 7L * 24 * 60 * 60 * 1000,
    private val maxRecords: Int = 256,
    private val committer: (File, File) -> Unit = JournalCommit::replace
) : InferenceTelemetry {
    private val mutex = Mutex()
    private val file = File(directory, "inference-attempts.json")
    private var observedAtEpochMs = 0L
    private var clearEpochMs: Long? = null

    init {
        require(retentionMs > 0 && maxRecords > 0) { "Journal limits must be positive" }
    }

    override suspend fun record(attempt: InferenceAttempt) {
        // Reconstruct to validate even instances created by reflection-based deserializers.
        val checked = attempt.copy()
        update { state, now ->
            if (state.clearEpochMs != null && checked.startedAtEpochMs <= state.clearEpochMs) {
                return@update state
            }
            require(checked.startedAtEpochMs <= now) { "Future attempts are not accepted" }
            if (state.observedAtEpochMs - checked.startedAtEpochMs >= retentionMs) {
                state
            } else {
                state.copy(attempts = (state.attempts.filterNot {
                    it.requestHash == checked.requestHash && it.attempt == checked.attempt
                } + checked).takeLast(maxRecords))
            }
        }
    }

    override suspend fun recordContract(requestHash: String, attempt: Int, status: ContractStatus) {
        require(isDigest(requestHash) && attempt >= 0) { "Invalid attempt key" }
        update { state, _ ->
            state.copy(attempts = state.attempts.map {
                if (it.requestHash == requestHash && it.attempt == attempt) {
                    it.copy(contractStatus = status)
                } else {
                    it
                }
            })
        }
    }

    suspend fun snapshot(): List<InferenceAttempt> = update { state, _ -> state }.attempts

    suspend fun exportJson(): String = withContext(Dispatchers.IO) { JournalJson.export(snapshot()) }

    suspend fun clear() = access {
        val now = clock()
        require(now >= 0) { "Journal clock must be nonnegative" }
        removeAbandonedWrites()
        // Recover corrupt documents, but retain a valid persisted watermark on clock rollback.
        val previous = try { read() } catch (_: IOException) { JournalState(0, emptyList()) }
        val watermark = maxOf(observedAtEpochMs, previous.observedAtEpochMs, now)
        save(JournalState(watermark, emptyList(), watermark))
        observedAtEpochMs = watermark
        clearEpochMs = watermark
        Unit
    }

    private suspend fun update(
        transform: (JournalState, Long) -> JournalState
    ): JournalState = access {
        val now = clock()
        require(now >= 0) { "Journal clock must be nonnegative" }
        val previous = read()
        val observed = maxOf(observedAtEpochMs, previous.observedAtEpochMs, now)
        val cleared = listOfNotNull(clearEpochMs, previous.clearEpochMs).maxOrNull()
        val current = JournalState(observed, previous.attempts.filter {
            // Equal millisecond starts are conservatively excluded after clear.
            (cleared == null || it.startedAtEpochMs > cleared) &&
                it.startedAtEpochMs <= now && observed - it.startedAtEpochMs < retentionMs
        }.takeLast(maxRecords), cleared)
        val next = transform(current, now)
        val committed = if (next != previous) save(next) else next
        observedAtEpochMs = committed.observedAtEpochMs
        clearEpochMs = committed.clearEpochMs
        committed
    }

    private suspend fun <T> access(block: suspend () -> T): T = withContext(Dispatchers.IO) {
        try {
            mutex.withLock {
                currentCoroutineContext().ensureActive()
                block()
            }
        } catch (_: IOException) {
            // Platform IO exceptions may contain private paths or untrusted document fragments.
            throw JournalStorageException()
        } catch (_: SecurityException) {
            throw JournalStorageException()
        }
    }

    private fun read(): JournalState {
        removeAbandonedWrites()
        if (!file.exists()) return JournalState(0, emptyList())
        if (!file.isFile || file.length() > MAX_FILE_BYTES) throw IOException("Invalid journal size")
        // Bound the stream too: another writer could grow the file after the length check.
        val bytes = ByteArray(MAX_FILE_BYTES + 1)
        var count = 0
        FileInputStream(file).use { input ->
            while (count < bytes.size) {
                val read = input.read(bytes, count, bytes.size - count)
                if (read < 0) break
                count += read
            }
        }
        if (count > MAX_FILE_BYTES) throw IOException("Invalid journal size")
        val json = Charsets.UTF_8.newDecoder().decode(ByteBuffer.wrap(bytes, 0, count)).toString()
        return JournalJson.read(json)
    }

    private fun removeAbandonedWrites() {
        directory.listFiles { entry ->
            entry.name.startsWith("inference-attempts-") && entry.name.endsWith(".tmp")
        }?.forEach {
            if (!it.delete()) throw IOException("Cannot remove abandoned journal write")
        }
    }

    private suspend fun save(state: JournalState): JournalState {
        var bounded = state
        var bytes = JournalJson.write(bounded).toByteArray(Charsets.UTF_8)
        while (bytes.size > MAX_FILE_BYTES && bounded.attempts.isNotEmpty()) {
            bounded = bounded.copy(attempts = bounded.attempts.drop(1))
            bytes = JournalJson.write(bounded).toByteArray(Charsets.UTF_8)
        }
        if (!directory.isDirectory && !directory.mkdirs()) throw IOException("Cannot create journal directory")
        val temporary = File.createTempFile("inference-attempts-", ".tmp", directory)
        try {
            FileOutputStream(temporary).use {
                it.write(bytes)
                it.fd.sync()
            }
            currentCoroutineContext().ensureActive()
            committer(temporary, file)
        } finally {
            temporary.delete()
        }
        return bounded
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    private class JournalStorageException : IOException("Journal storage unavailable"),
        CopyableThrowable<JournalStorageException> {
        // Stacktrace recovery must not attach even a sanitized exception as a cause.
        override fun createCopy(): JournalStorageException? = null
    }

    private companion object {
        const val MAX_FILE_BYTES = 512 * 1024
    }
}
