package com.example.persona.core.ai

import com.example.persona.testutil.ModelPackageFixtures
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class LocalModelValidatorTest {

    @get:Rule
    val temporaryFolder = TemporaryFolder()

    private val validator = LocalModelValidator(SymlinkChecker { false })

    @Test
    fun `valid package is ready`() {
        val dir = packageDir()

        val result = validate(dir)

        assertTrue(result is ModelScanResult.Ready)
        assertEquals(ModelPackageFixtures.DEFAULT_ID, (result as ModelScanResult.Ready).model.id)
    }

    @Test
    fun `missing manifest is not installed`() {
        val dir = packageDir()
        dir.resolve("manifest.json").delete()

        assertEquals(ModelScanResult.NotInstalled, validate(dir))
    }

    @Test
    fun `missing ready marker is not installed`() {
        val dir = packageDir(ready = false)

        assertEquals(ModelScanResult.NotInstalled, validate(dir))
    }

    @Test
    fun `changed file hash is corrupted`() {
        val dir = packageDir()
        val entry = dir.resolve(ModelPackageFixtures.DEFAULT_ENTRY)
        entry.writeText("x".repeat(entry.readText().length))

        val result = validate(dir)

        assertTrue(result is ModelScanResult.Corrupted)
        assertTrue((result as ModelScanResult.Corrupted).reason.contains("SHA256"))
    }

    @Test
    fun `unsupported abi is rejected`() {
        val dir = packageDir()

        val result = validator.validate(dir, ModelPackageFixtures.deviceProfile(abi = "x86_64")).result

        assertTrue(result is ModelScanResult.Unsupported)
    }

    @Test
    fun `entry mismatch with config is corrupted`() {
        val dir = packageDir(configEntry = "other.mnn")

        val result = validate(dir)

        assertTrue(result is ModelScanResult.Corrupted)
        assertTrue((result as ModelScanResult.Corrupted).reason.contains("entry"))
    }

    @Test
    fun `path traversal in manifest files is rejected`() {
        val dir = packageDir(
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

        val result = validate(dir)

        assertTrue(result is ModelScanResult.Corrupted)
        assertTrue((result as ModelScanResult.Corrupted).reason.contains("files.path"))
    }

    @Test
    fun `context window above supported maximum is rejected`() {
        val dir = packageDir(contextWindow = 1_000_001)

        val result = validate(dir)

        assertTrue(result is ModelScanResult.Unsupported)
    }

    @Test
    fun `min sdk above device sdk is rejected`() {
        val dir = packageDir(minSdk = 40)

        val result = validate(dir)

        assertTrue(result is ModelScanResult.Unsupported)
    }

    @Test
    fun `files list must cover tokenizer`() {
        val dir = packageDir(
            files = { root ->
                ModelPackageFixtures.defaultFiles(root)
                    .filterNot { it.path == ModelPackageFixtures.DEFAULT_TOKENIZER }
                    .toMutableList()
            }
        )

        val result = validate(dir)

        assertTrue(result is ModelScanResult.Corrupted)
        assertTrue((result as ModelScanResult.Corrupted).reason.contains("必要文件"))
    }

    @Test
    fun `symlink model directory is rejected`() {
        val dir = packageDir()
        val symlinkAwareValidator = LocalModelValidator(SymlinkChecker { it == dir })

        val result = symlinkAwareValidator.validate(dir, ModelPackageFixtures.deviceProfile()).result

        assertTrue(result is ModelScanResult.Corrupted)
        assertTrue((result as ModelScanResult.Corrupted).reason.contains("symbolic link"))
    }

    @Test
    fun `duplicate manifest file path is rejected`() {
        val dir = packageDir(
            files = { root ->
                ModelPackageFixtures.defaultFiles(root).also { files ->
                    files += files.first()
                }
            }
        )

        val result = validate(dir)

        assertTrue(result is ModelScanResult.Corrupted)
        assertTrue((result as ModelScanResult.Corrupted).reason.contains("重复路径"))
    }

    @Test
    fun `unsupported schema version is rejected`() {
        val dir = packageDir(schemaVersion = 99)

        val result = validate(dir)

        assertTrue(result is ModelScanResult.Unsupported)
    }

    @Test
    fun `unsupported backend is rejected`() {
        val dir = packageDir(backend = "LLAMA")

        val result = validate(dir)

        assertTrue(result is ModelScanResult.Unsupported)
    }

    @Test
    fun `unsupported family is rejected`() {
        val dir = packageDir(family = "UNKNOWN")

        val result = validate(dir)

        assertTrue(result is ModelScanResult.Unsupported)
    }

    @Test
    fun `invalid hash format is rejected`() {
        val dir = packageDir(
            files = { root ->
                ModelPackageFixtures.defaultFiles(root).also { files ->
                    files[0] = files[0].copy(sha256 = "not-a-hash")
                }
            }
        )

        val result = validate(dir)

        assertTrue(result is ModelScanResult.Corrupted)
        assertTrue((result as ModelScanResult.Corrupted).reason.contains("sha256"))
    }

    @Test
    fun `missing manifest file size is rejected`() {
        val dir = packageDir(
            files = { root ->
                ModelPackageFixtures.defaultFiles(root).also { files ->
                    files[0] = files[0].copy(size = null)
                }
            }
        )

        val result = validate(dir)

        assertTrue(result is ModelScanResult.Corrupted)
    }

    private fun packageDir(
        ready: Boolean = true,
        configEntry: String = ModelPackageFixtures.DEFAULT_ENTRY,
        contextWindow: Int = 32_768,
        minSdk: Int = 24,
        backend: String = "MNN",
        family: String = ModelFamilies.QWEN2_5,
        schemaVersion: Int = 1,
        files: ((File) -> MutableList<LocalModelManifestFile>)? = null
    ): File {
        val dir = temporaryFolder.newFolder()
        return ModelPackageFixtures.writePackage(
            root = dir,
            ready = ready,
            configEntry = configEntry,
            contextWindow = contextWindow,
            minSdk = minSdk,
            backend = backend,
            family = family,
            schemaVersion = schemaVersion,
            files = files
        )
    }

    private fun validate(dir: File): ModelScanResult {
        return validator.validate(dir, ModelPackageFixtures.deviceProfile()).result
    }
}
