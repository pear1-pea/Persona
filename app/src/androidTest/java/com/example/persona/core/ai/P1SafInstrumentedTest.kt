package com.example.persona.core.ai

import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import android.os.ParcelFileDescriptor
import android.provider.DocumentsContract
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.SdkSuppress
import androidx.test.platform.app.InstrumentationRegistry
import com.google.gson.Gson
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.security.MessageDigest
import java.util.UUID

@RunWith(AndroidJUnit4::class)
@SdkSuppress(minSdkVersion = 29)
class P1SafInstrumentedTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context get() = instrumentation.targetContext
    private val resolver get() = context.contentResolver
    private lateinit var authority: String
    private lateinit var control: Uri
    private lateinit var source: File
    private lateinit var models: File
    private lateinit var installer: ModelPackageInstaller
    private lateinit var validator: LocalModelValidator
    private var providerReady = false

    @Before
    @Suppress("DEPRECATION")
    fun prepare() {
        assumeTrue("Opt in with runP1Saf=true",
            InstrumentationRegistry.getArguments().getString("runP1Saf") == "true")
        check(context.packageName == "com.example.persona.validation") { "Requires isolated QA APK" }
        val providers = context.packageManager.getPackageInfo(context.packageName,
            PackageManager.GET_PROVIDERS).providers.orEmpty()
        authority = providers.single { it.name == SafTestDocumentsProvider::class.java.name }.authority
        control = Uri.parse("content://$authority")
        providerReady = true
        resetProvider()
        val runId = UUID.randomUUID().toString()
        source = File(context.filesDir, "p1-saf-source-$runId").apply { check(mkdir()) }
        models = File(checkNotNull(context.getExternalFilesDir(null)), "p1-saf-models-$runId")
            .apply { check(mkdir()) }
        validator = LocalModelValidator(AndroidSymlinkChecker)
        installer = ModelPackageInstaller(context, validator, AndroidSymlinkChecker)
    }

    @After
    fun cleanup() {
        try {
            if (providerReady) resetProvider()
        } finally {
            try {
                try {
                    if (::source.isInitialized) removeOwned(source, context.filesDir, "p1-saf-source-")
                } finally {
                    if (::models.isInitialized) removeOwned(models,
                        checkNotNull(context.getExternalFilesDir(null)), "p1-saf-models-")
                }
            } finally {
                providerReady = false
            }
        }
    }

    @Test(timeout = 60_000L)
    fun unknownSizeTreeCopiesThroughContentResolver() = runBlocking {
        tinyPackage()
        val tree = expose(source, unknownSizes = true)
        installer.availableStorageBytes = { 1024L * 1024L }

        val result = install(tree)

        assertTrue(result.toString(), result is ModelPackageInstallResult.Success)
        assertEquals(source.walkTopDown().count { it.isFile }, stats().getInt("opens"))
        assertInstalledMatches(source, (result as ModelPackageInstallResult.Success).model)
        assertNoPartialInstall()
    }

    @Test(timeout = 60_000L)
    fun knownSizeTreeRejectsInsufficientSpaceBeforeOpeningPayloads() = runBlocking {
        tinyPackage()
        val tree = expose(source)
        installer.availableStorageBytes = { 128L * 1024L }

        val result = install(tree)

        assertTrue(result is ModelPackageInstallResult.Failure)
        assertTrue((result as ModelPackageInstallResult.Failure).reason.contains("空间不足"))
        assertEquals(0, stats().getInt("opens"))
        assertNoPartialInstall()
    }

    @Test(timeout = 60_000L)
    fun providerFailureRemovesPartialCopyAndLeavesSourceUntouched() = runBlocking {
        tinyPackage()
        val originalHash = sha256(source.resolve("llm.mnn.weight"))
        val tree = expose(source, failName = "llm.mnn.weight")

        val result = install(tree)

        assertTrue(result is ModelPackageInstallResult.Failure)
        assertTrue(stats().getInt("opens") >= 2)
        assertFalse(models.resolve("p1-saf-fixture").exists())
        assertEquals(originalHash, sha256(source.resolve("llm.mnn.weight")))
        assertNoPartialInstall()
    }

    @Test(timeout = 60_000L)
    fun unknownSizeStreamChecksCapacityAfterCopyStarts() = runBlocking {
        tinyPackage()
        val tree = expose(source, unknownSizes = true)
        var checks = 0
        installer.availableStorageBytes = { if (++checks <= 2) Long.MAX_VALUE else 0L }

        val result = install(tree)

        assertTrue(result is ModelPackageInstallResult.Failure)
        assertTrue((result as ModelPackageInstallResult.Failure).reason.contains("空间不足"))
        assertTrue(stats().getInt("opens") > 0)
        assertFalse(models.resolve("p1-saf-fixture").exists())
        assertNoPartialInstall()
    }

    @Test(timeout = 60_000L)
    fun cancellationCleansPartialCopyWhileRecoveryPreservesActiveTransaction() = runBlocking {
        tinyPackage(weightBytes = 2 * 1024 * 1024)
        val tree = expose(source, slowName = "llm.mnn.weight", unknownSizes = true)
        val copy = async { install(tree) }
        try {
            withTimeout(15_000L) {
                while (stats().getLong("streamedBytes") < 8192L) delay(10)
            }
            val active = models.resolve(".staging").listFiles().orEmpty().single()
            val orphan = models.resolve(".staging/${UUID.randomUUID()}").apply { check(mkdir()) }
            orphan.resolve("partial").writeText("interrupted")
            val otherInstaller = ModelPackageInstaller(context, validator, AndroidSymlinkChecker)

            assertEquals(1, otherInstaller.recoverStaging(models))
            assertTrue("Active copy must keep its lease", active.isDirectory)
            assertFalse(orphan.exists())
            withTimeout(10_000L) { copy.cancelAndJoin() }

            assertTrue(copy.isCancelled)
            assertFalse(models.resolve("p1-saf-fixture").exists())
            assertTrue(source.resolve("llm.mnn.weight").isFile)
            assertNoPartialInstall()
        } finally {
            copy.cancelAndJoin()
        }
    }

    @Test(timeout = 1_800_000L)
    fun realPrivatePackagesCopyThroughSaf() = runBlocking {
        val arguments = InstrumentationRegistry.getArguments()
        val sourceRoot = File(context.filesDir, "model-sources").canonicalFile
        check(sourceRoot.parentFile == context.filesDir.canonicalFile)
        for (argument in listOf("primarySource" to "primary", "secondarySource" to "secondary")) {
            val directory = File(sourceRoot, arguments.getString(argument.first) ?: argument.second).canonicalFile
            check(directory.isDirectory && directory.path.startsWith(sourceRoot.path + File.separator)) {
                "Provision validated primary/secondary in QA private files/model-sources"
            }
            check(directory.walkTopDown().none { AndroidSymlinkChecker.isSymbolicLink(it) })
            resetProvider()
            val tree = expose(directory)

            val result = withTimeout(840_000L) { install(tree) }

            assertTrue(result.toString(), result is ModelPackageInstallResult.Success)
            assertInstalledMatches(directory, (result as ModelPackageInstallResult.Success).model)
            assertTrue(stats().getInt("opens") >= 4)
            assertNoPartialInstall()
            Log.i("P1SafAcceptance", "SAF copied ${argument.second}: model=${result.model.id}, files=${stats().getInt("opens")}")
        }
    }

    private suspend fun install(tree: Uri) = installer.install(tree, models,
        LocalModelManager(context, validator, installer).deviceCapability(), { 0 })

    private fun expose(directory: File, unknownSizes: Boolean = false, slowName: String? = null,
        failName: String? = null): Uri {
        directory.walkTopDown().forEach { file ->
            val relative = if (file == directory) "" else file.relativeTo(directory).invariantSeparatorsPath
            val id = if (relative.isEmpty()) "root" else "root/$relative"
            val data = Bundle().apply {
                putString("name", if (relative.isEmpty()) "root" else file.name)
                if (relative.isNotEmpty()) putString("parent", id.substringBeforeLast('/'))
                if (file.isFile && !unknownSizes) putLong("size", file.length())
                putBoolean("slow", file.name == slowName)
                putBoolean("fail", file.name == failName)
            }
            if (file.isFile) {
                ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY).use { descriptor ->
                    data.putParcelable("descriptor", descriptor)
                    checkNotNull(resolver.call(control, "persona.register", id, data))
                }
            } else {
                checkNotNull(resolver.call(control, "persona.register", id, data))
            }
        }
        return DocumentsContract.buildTreeDocumentUri(authority, "root")
    }

    private fun resetProvider() { checkNotNull(resolver.call(control, "persona.reset", null, null)) }
    private fun stats() = checkNotNull(resolver.call(control, "persona.stats", null, null))

    private fun tinyPackage(weightBytes: Int = 128 * 1024) {
        source.resolve("config.json").writeText("""{"llm_model":"llm.mnn","llm_weight":"llm.mnn.weight"}""")
        source.resolve("llm.mnn").writeText("graph")
        source.resolve("llm.mnn.weight").writeBytes(ByteArray(weightBytes) { (it % 251).toByte() })
        source.resolve("tokenizer.txt").writeText("430 3\n!\n")
        source.resolve("assets/nested").apply { check(mkdirs()) }.resolve("vocab.txt").writeText("nested\n")
        val manifest = LocalModelManifest(schemaVersion = 1, id = "p1-saf-fixture", name = "SAF fixture",
            version = "1", backend = "MNN", family = ModelFamilies.QWEN2_5,
            promptFormat = PromptFormats.QWEN_CHATML_TEXT, entry = "llm.mnn", tokenizer = "tokenizer.txt",
            contextWindow = 2048, minRamGb = 0, minSdk = 24, requiredAbis = listOf("arm64-v8a"),
            files = source.walkTopDown().filter(File::isFile).map {
                LocalModelManifestFile(it.relativeTo(source).invariantSeparatorsPath, "file", it.length(), sha256(it))
            }.toList())
        source.resolve("manifest.json").writeText(Gson().toJson(manifest))
    }

    private fun assertInstalledMatches(original: File, model: InstalledModel) {
        val target = File(model.modelDir)
        assertEquals(models.canonicalFile, target.canonicalFile.parentFile)
        original.walkTopDown().filter { it.isFile && it.name != "READY" }.forEach {
            assertEquals(it.name, sha256(it), sha256(target.resolve(it.relativeTo(original))))
        }
        assertTrue(target.resolve("READY").isFile)
        assertTrue(validator.validate(target,
            LocalModelManager(context, validator, installer).deviceCapability()).result is ModelScanResult.Ready)
    }

    private fun assertNoPartialInstall() {
        assertTrue(models.resolve(".staging").listFiles().orEmpty().isEmpty())
    }

    private fun removeOwned(directory: File, parent: File, prefix: String) {
        check(context.packageName == "com.example.persona.validation")
        check(directory.canonicalFile.parentFile == parent.canonicalFile && directory.name.startsWith(prefix))
        check(directory.deleteRecursively())
    }

    private fun sha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buffer = ByteArray(1024 * 1024)
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                digest.update(buffer, 0, count)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it.toInt() and 0xff) }
    }
}
