package com.example.persona.core.ai

import android.content.Context
import com.example.persona.testutil.ModelPackageFixtures
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.mockito.kotlin.mock
import java.io.File
import java.io.RandomAccessFile
import java.nio.file.Files
import java.util.UUID

class ModelPackageRecoveryTest {
    @get:Rule
    val temporaryFolder = TemporaryFolder()

    private val checker = SymlinkChecker { Files.isSymbolicLink(it.toPath()) }
    private val validator = LocalModelValidator(checker)
    private fun installer() = ModelPackageInstaller(mock<Context>(), validator, checker)
    private fun directory(name: String) = temporaryFolder.newFolder(name).canonicalFile

    @Test
    fun `small package installs with less than 100 MiB free`() = runBlocking {
        val source = ModelPackageFixtures.writePackage(directory("source"), ready = false)
        val root = directory("models")
        val installer = installer().apply { availableStorageBytes = { 1024L * 1024L } }

        val result = installer.installFromDirectory(source, root, ModelPackageFixtures.deviceProfile())

        assertTrue(result.toString(), result is ModelPackageInstallResult.Success)
        assertTrue(root.resolve("${ModelPackageFixtures.DEFAULT_ID}/READY").isFile)
        assertNoStaging(root)
    }

    @Test
    fun `actual package size rejects insufficient capacity above 100 MiB`() = runBlocking {
        val source = ModelPackageFixtures.writePackage(directory("source"), ready = false)
        RandomAccessFile(source.resolve(ModelPackageFixtures.DEFAULT_WEIGHT), "rw").use {
            it.setLength(128L * 1024L * 1024L)
        }
        val root = directory("models")
        val installer = installer().apply { availableStorageBytes = { 110L * 1024L * 1024L } }

        val result = installer.installFromDirectory(source, root, ModelPackageFixtures.deviceProfile())

        assertTrue(result is ModelPackageInstallResult.Failure)
        assertTrue((result as ModelPackageInstallResult.Failure).reason.contains("空间不足"))
        assertFalse(root.resolve(ModelPackageFixtures.DEFAULT_ID).exists())
        assertNoStaging(root)
    }

    @Test
    fun `storage consumed after preflight fails cleanly during copy`() = runBlocking {
        val source = ModelPackageFixtures.writePackage(directory("source"), ready = false)
        val root = directory("models")
        var checks = 0
        val installer = installer().apply {
            availableStorageBytes = { if (++checks <= 2) Long.MAX_VALUE else 0L }
        }

        val result = installer.installFromDirectory(source, root, ModelPackageFixtures.deviceProfile())

        assertTrue(result is ModelPackageInstallResult.Failure)
        assertTrue((result as ModelPackageInstallResult.Failure).reason.contains("空间不足"))
        assertTrue(checks >= 3)
        assertFalse(root.resolve(ModelPackageFixtures.DEFAULT_ID).exists())
        assertTrue(source.resolve(ModelPackageFixtures.DEFAULT_WEIGHT).isFile)
        assertNoStaging(root)
    }

    @Test
    fun `recovery removes interrupted transaction but preserves installed model and unrelated entries`() {
        val root = directory("models")
        val source = ModelPackageFixtures.writePackage(root.resolve("installed"))
        val abandoned = root.resolve(".staging/${UUID.randomUUID()}").apply { mkdirs() }
        abandoned.resolve("partial.weight").writeText("partial")
        val unrelated = root.resolve(".staging/notes").apply { mkdirs() }
        unrelated.resolve("keep").writeText("keep")

        assertEquals(1, installer().recoverStaging(root))

        assertFalse(abandoned.exists())
        assertTrue(source.resolve("READY").isFile)
        assertTrue(unrelated.resolve("keep").isFile)
        assertEquals(0, installer().recoverStaging(root))
    }

    @Test
    fun `recovery skips active transaction from another installer then cleans after lease release`() {
        val root = directory("models")
        val active = root.resolve(".staging/${UUID.randomUUID()}").apply { mkdirs() }
        active.resolve("partial.weight").writeText("active")
        RandomAccessFile(active.resolve(".install.lock"), "rw").use { file ->
            file.channel.lock().use {
                assertEquals(0, installer().recoverStaging(root))
                assertEquals("active", active.resolve("partial.weight").readText())
            }
        }

        assertEquals(1, installer().recoverStaging(root))
        assertFalse(active.exists())
    }

    @Test
    fun `recovery unlinks symlinks without touching their destination`() {
        val root = directory("models")
        val outside = directory("outside")
        outside.resolve("keep").writeText("private")
        val abandoned = root.resolve(".staging/${UUID.randomUUID()}").apply { mkdirs() }
        Files.createSymbolicLink(abandoned.resolve("linked").toPath(), outside.toPath())
        Files.createSymbolicLink(root.resolve(".staging/${UUID.randomUUID()}").toPath(), outside.toPath())

        assertEquals(2, installer().recoverStaging(root))
        assertEquals("private", outside.resolve("keep").readText())
        assertNoStaging(root)
    }

    @Test
    fun `concurrent same-id installs publish exactly one package`() = runBlocking {
        val source = ModelPackageFixtures.writePackage(directory("source"), ready = false)
        val root = directory("models")

        val results = List(2) {
            async(Dispatchers.IO) {
                installer().installFromDirectory(source, root, ModelPackageFixtures.deviceProfile())
            }
        }.awaitAll()

        assertEquals(1, results.count { it is ModelPackageInstallResult.Success })
        assertEquals(1, results.count { it is ModelPackageInstallResult.Failure })
        assertTrue(validator.validate(root.resolve(ModelPackageFixtures.DEFAULT_ID),
            ModelPackageFixtures.deviceProfile()).result is ModelScanResult.Ready)
        assertNoStaging(root)
    }

    @Test
    fun `installer reserved ids cannot replace staging root or escape models root`() = runBlocking {
        listOf(".", "..", ".staging", ".staging.lock").forEachIndexed { index, id ->
            val source = ModelPackageFixtures.writePackage(directory("source-$index"), id = id)
            val root = directory("models-$index")

            assertTrue(installer().installFromDirectory(source, root,
                ModelPackageFixtures.deviceProfile()) is ModelPackageInstallResult.Failure)
            assertNoStaging(root)
        }
    }

    @Test
    fun `cancellation during hashing propagates instead of reporting package failure`() {
        val source = ModelPackageFixtures.writePackage(directory("source"))
        var checks = 0
        try {
            validator.validate(source, ModelPackageFixtures.deviceProfile(), checkCancelled = {
                if (++checks >= 4) throw CancellationException("cancel validation")
            })
            throw AssertionError("Validation cancellation must propagate")
        } catch (_: CancellationException) {
            assertTrue(source.resolve("READY").isFile)
        }
    }

    @Test
    fun `same-size same-mtime mutation still invalidates full file verification`() {
        val source = ModelPackageFixtures.writePackage(directory("source"))
        assertTrue(validator.validate(source, ModelPackageFixtures.deviceProfile()).result is ModelScanResult.Ready)
        val graph = source.resolve(ModelPackageFixtures.DEFAULT_ENTRY)
        val originalTime = graph.lastModified()
        graph.writeText("x".repeat(graph.length().toInt()))
        assertTrue(graph.setLastModified(originalTime))

        assertTrue(validator.validate(source, ModelPackageFixtures.deviceProfile()).result is ModelScanResult.Corrupted)
    }

    private fun assertNoStaging(root: File) {
        assertTrue(root.resolve(".staging").listFiles().orEmpty().isEmpty())
    }
}
