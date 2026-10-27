package com.example.persona.core.ai

import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

sealed interface ModelPackageInstallResult {
    data class Success(val model: InstalledModel) : ModelPackageInstallResult
    data class Failure(val reason: String, val cause: Throwable? = null) : ModelPackageInstallResult
}

@Singleton
class ModelPackageInstaller @Inject constructor(
    @ApplicationContext private val context: Context,
    private val validator: LocalModelValidator,
    private val symlinkChecker: SymlinkChecker
) {
    suspend fun install(
        sourceTree: Uri,
        modelsRoot: File,
        profile: DeviceProfile,
        recentFailureCount: (String) -> Int
    ): ModelPackageInstallResult = withContext(Dispatchers.IO) {
        installWithStaging(modelsRoot, profile, recentFailureCount) { staging ->
            copyTree(sourceTree, staging)
        }
    }

    /**
     * Installs a package that already exists on the local file system.
     *
     * SAF imports are copied into the same staging directory first, so this path
     * exercises the same validation, READY marker and atomic rename logic.
     */
    internal suspend fun installFromDirectory(
        sourceDirectory: File,
        modelsRoot: File,
        profile: DeviceProfile,
        recentFailureCount: (String) -> Int = { 0 }
    ): ModelPackageInstallResult = withContext(Dispatchers.IO) {
        installWithStaging(modelsRoot, profile, recentFailureCount) { staging ->
            copyDirectory(sourceDirectory, staging)
        }
    }

    private fun installWithStaging(
        modelsRoot: File,
        profile: DeviceProfile,
        recentFailureCount: (String) -> Int,
        copyIntoStaging: (File) -> Unit
    ): ModelPackageInstallResult {
        val staging = File(modelsRoot, ".staging/${UUID.randomUUID()}")
        return try {
            prepareStaging(modelsRoot, staging)
            copyIntoStaging(staging)
            installStaged(staging, modelsRoot, profile, recentFailureCount)
        } catch (error: CancellationException) {
            throw error
        } catch (error: Throwable) {
            ModelPackageInstallResult.Failure(error.message ?: "模型安装失败", error)
        } finally {
            if (staging.exists()) staging.deleteRecursively()
        }
    }

    private fun prepareStaging(modelsRoot: File, staging: File) {
        modelsRoot.mkdirs()
        check(modelsRoot.isDirectory) { "无法创建 models 目录" }
        check(!symlinkChecker.isSymbolicLink(modelsRoot)) { "models 目录不能是 symbolic link" }
        val stagingRoot = File(modelsRoot, ".staging")
        check(!stagingRoot.exists() || !symlinkChecker.isSymbolicLink(stagingRoot)) {
            "models staging 目录不能是 symbolic link"
        }
        check(staging.mkdirs()) { "无法创建模型 staging 目录" }
    }

    private fun installStaged(
        staging: File,
        modelsRoot: File,
        profile: DeviceProfile,
        recentFailureCount: (String) -> Int
    ): ModelPackageInstallResult {
        val preflight = validator.validate(
            modelDir = staging,
            profile = profile,
            requireReadyMarker = false,
            recentFailureCount = recentFailureCount
        )
        val model = (preflight.result as? ModelScanResult.Ready)?.model
            ?: return preflight.failureResult()

        File(staging, LocalModelValidator.READY_MARKER).writeText("ready\n")
        val finalCheck = validator.validate(
            modelDir = staging,
            profile = profile,
            requireReadyMarker = true,
            recentFailureCount = recentFailureCount
        )
        val checkedModel = (finalCheck.result as? ModelScanResult.Ready)?.model
            ?: return finalCheck.failureResult()

        val target = File(modelsRoot, checkedModel.id)
        check(!target.exists() && !symlinkChecker.isSymbolicLink(target)) {
            "模型已安装：${checkedModel.id}"
        }
        check(staging.renameTo(target)) { "模型目录无法原子落盘" }
        return ModelPackageInstallResult.Success(
            checkedModel.copy(
                modelDir = target.canonicalPath,
                manifestPath = File(target, LocalModelValidator.MANIFEST_FILE).canonicalPath
            )
        )
    }

    private fun copyDirectory(sourceDirectory: File, targetRoot: File) {
        check(sourceDirectory.isDirectory) { "模型源目录不存在" }
        check(!symlinkChecker.isSymbolicLink(sourceDirectory)) {
            "模型源目录不能是 symbolic link"
        }
        val sourceRoot = sourceDirectory.canonicalFile
        val targetCanonicalRoot = targetRoot.canonicalFile
        sourceRoot.walkTopDown().forEach { source ->
            if (source == sourceRoot) return@forEach
            check(!symlinkChecker.isSymbolicLink(source)) {
                "模型包不能包含 symbolic link：${source.name}"
            }
            val relative = source.relativeTo(sourceRoot)
            val target = File(targetCanonicalRoot, relative.path)
            val targetPath = target.canonicalPath
            val rootPrefix = targetCanonicalRoot.path + File.separator
            check(targetPath.startsWith(rootPrefix)) {
                "模型包包含非法路径：${relative.path}"
            }
            if (source.isDirectory) {
                check(target.mkdirs() || target.isDirectory) {
                    "无法创建目录：${relative.path}"
                }
            } else {
                target.parentFile?.mkdirs()
                check(!target.exists()) { "模型包包含重复文件名：${relative.path}" }
                source.inputStream().use { input ->
                    FileOutputStream(target).use { output ->
                        input.copyTo(output, DEFAULT_BUFFER_SIZE)
                    }
                }
            }
        }
    }

    private fun copyTree(sourceTree: Uri, targetRoot: File) {
        val rootId = DocumentsContract.getTreeDocumentId(sourceTree)
            ?: throw IOException("无法读取 SAF tree document id")
        copyChildren(sourceTree, rootId, targetRoot, mutableSetOf())
    }

    private fun copyChildren(
        treeUri: Uri,
        parentId: String,
        targetDirectory: File,
        visitedDocumentIds: MutableSet<String>
    ) {
        check(visitedDocumentIds.add(parentId)) { "模型包目录存在循环引用" }
        val childrenUri = DocumentsContract.buildChildDocumentsUriUsingTree(treeUri, parentId)
        val projection = arrayOf(
            DocumentsContract.Document.COLUMN_DOCUMENT_ID,
            DocumentsContract.Document.COLUMN_DISPLAY_NAME,
            DocumentsContract.Document.COLUMN_MIME_TYPE
        )
        context.contentResolver.query(childrenUri, projection, null, null, null)?.use { cursor ->
            val idIndex = cursor.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_DOCUMENT_ID)
            val nameIndex = cursor.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_DISPLAY_NAME)
            val typeIndex = cursor.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_MIME_TYPE)
            while (cursor.moveToNext()) {
                val documentId = cursor.getString(idIndex)
                val name = cursor.getString(nameIndex).requireSafeDocumentName()
                val mimeType = cursor.getString(typeIndex)
                val documentUri = DocumentsContract.buildDocumentUriUsingTree(treeUri, documentId)
                val target = File(targetDirectory, name)
                if (mimeType == DocumentsContract.Document.MIME_TYPE_DIR) {
                    check(!target.exists()) { "模型包包含重复文件名：$name" }
                    check(target.mkdirs()) { "无法创建目录：$name" }
                    copyChildren(treeUri, documentId, target, visitedDocumentIds)
                } else {
                    check(!target.exists()) { "模型包包含重复文件名：$name" }
                    copyFile(documentUri, target)
                }
            }
        } ?: throw IOException("无法读取模型包目录")
    }

    private fun copyFile(source: Uri, target: File) {
        context.contentResolver.openInputStream(source)?.use { input ->
            FileOutputStream(target).use { output ->
                input.copyTo(output, DEFAULT_BUFFER_SIZE)
            }
        } ?: throw IOException("无法读取模型文件：$source")
    }

    private fun String?.requireSafeDocumentName(): String {
        val value = this?.trim().orEmpty()
        check(value.isNotEmpty() && value != "." && value != "..") { "模型包包含非法文件名" }
        check('/' !in value && '\\' !in value) { "模型包包含非法路径" }
        check(value.none { it.code < 0x20 || it.code == 0x7f }) { "模型包包含非法文件名" }
        return value
    }

    private fun ModelScanReport.failureResult(): ModelPackageInstallResult.Failure {
        val reason = when (val scan = result) {
            ModelScanResult.NotInstalled -> "模型包缺少 manifest 或必要文件"
            is ModelScanResult.Corrupted -> scan.reason
            is ModelScanResult.Unsupported -> scan.reason
            is ModelScanResult.Failed -> scan.reason
            is ModelScanResult.Ready -> "模型包未通过安装校验"
        }
        return ModelPackageInstallResult.Failure(reason)
    }
}
