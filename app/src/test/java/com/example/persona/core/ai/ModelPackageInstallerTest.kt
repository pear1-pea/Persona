package com.example.persona.core.ai

import android.content.Context
import com.example.persona.testutil.ModelPackageFixtures
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.mockito.kotlin.mock
import java.io.File

class ModelPackageInstallerTest {

    @get:Rule
    val temporaryFolder = TemporaryFolder()

    private val validator = LocalModelValidator(SymlinkChecker { false })
    private val installer = ModelPackageInstaller(
        context = mock<Context>(),
        validator = validator,
        symlinkChecker = SymlinkChecker { false }
    )

    @Test
    fun `valid directory package is installed atomically`() = runTest {
        val source = temporaryFolder.newFolder("source")
        ModelPackageFixtures.writePackage(source, ready = false)
        val modelsRoot = temporaryFolder.newFolder("models")

        val result = installer.installFromDirectory(
            sourceDirectory = source,
            modelsRoot = modelsRoot,
            profile = ModelPackageFixtures.deviceProfile()
        )

        assertTrue(result is ModelPackageInstallResult.Success)
        val model = (result as ModelPackageInstallResult.Success).model
        val target = File(modelsRoot, ModelPackageFixtures.DEFAULT_ID)
        assertEquals(target.canonicalPath, model.modelDir)
        assertTrue(File(target, LocalModelValidator.READY_MARKER).isFile)
        assertTrue(File(target, LocalModelValidator.MANIFEST_FILE).isFile)
        assertFalse(File(modelsRoot, ".staging").listFiles().orEmpty().any { it.isDirectory })
    }

    @Test
    fun `invalid package fails and leaves no target`() = runTest {
        val source = temporaryFolder.newFolder("invalid-source")
        ModelPackageFixtures.writePackage(source, ready = false)
        source.resolve("manifest.json").delete()
        val modelsRoot = temporaryFolder.newFolder("invalid-models")

        val result = installer.installFromDirectory(
            sourceDirectory = source,
            modelsRoot = modelsRoot,
            profile = ModelPackageFixtures.deviceProfile()
        )

        assertTrue(result is ModelPackageInstallResult.Failure)
        assertFalse(File(modelsRoot, ModelPackageFixtures.DEFAULT_ID).exists())
        assertFalse(File(modelsRoot, ".staging").listFiles().orEmpty().any { it.isDirectory })
    }

    @Test
    fun `existing target is not overwritten`() = runTest {
        val source = temporaryFolder.newFolder("existing-source")
        ModelPackageFixtures.writePackage(source, ready = false)
        val modelsRoot = temporaryFolder.newFolder("existing-models")
        val existing = File(modelsRoot, ModelPackageFixtures.DEFAULT_ID).apply {
            mkdirs()
            resolve("keep.txt").writeText("keep")
        }

        val result = installer.installFromDirectory(
            sourceDirectory = source,
            modelsRoot = modelsRoot,
            profile = ModelPackageFixtures.deviceProfile()
        )

        assertTrue(result is ModelPackageInstallResult.Failure)
        assertTrue((result as ModelPackageInstallResult.Failure).reason.contains("已安装"))
        assertEquals("keep", existing.resolve("keep.txt").readText())
    }

    @Test
    fun `symlink source directory is rejected`() = runTest {
        val source = temporaryFolder.newFolder("symlink-source")
        ModelPackageFixtures.writePackage(source, ready = false)
        val modelsRoot = temporaryFolder.newFolder("symlink-models")
        val symlinkAwareInstaller = ModelPackageInstaller(
            context = mock<Context>(),
            validator = validator,
            symlinkChecker = SymlinkChecker { it == source }
        )

        val result = symlinkAwareInstaller.installFromDirectory(
            sourceDirectory = source,
            modelsRoot = modelsRoot,
            profile = ModelPackageFixtures.deviceProfile()
        )

        assertTrue(result is ModelPackageInstallResult.Failure)
        assertTrue((result as ModelPackageInstallResult.Failure).reason.contains("symbolic link"))
    }

    @Test
    fun `path traversal in manifest is rejected`() = runTest {
        val source = temporaryFolder.newFolder("traversal-source")
        ModelPackageFixtures.writePackage(
            root = source,
            ready = false,
            files = { root ->
                ModelPackageFixtures.defaultFiles(root).also { files ->
                    files += LocalModelManifestFile(
                        path = "../escape",
                        role = "model",
                        size = 0L,
                        sha256 = "0".repeat(64)
                    )
                }
            }
        )
        val modelsRoot = temporaryFolder.newFolder("traversal-models")

        val result = installer.installFromDirectory(
            sourceDirectory = source,
            modelsRoot = modelsRoot,
            profile = ModelPackageFixtures.deviceProfile()
        )

        assertTrue(result is ModelPackageInstallResult.Failure)
        assertFalse(File(modelsRoot, ModelPackageFixtures.DEFAULT_ID).exists())
    }
}
