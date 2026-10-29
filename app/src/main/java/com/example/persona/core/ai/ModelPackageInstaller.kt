package com.example.persona.core.ai

import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.RandomAccessFile
import java.nio.channels.FileLock
import java.nio.channels.OverlappingFileLockException
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
    internal var availableStorageBytes: (File) -> Long = { it.usableSpace }

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

    private suspend fun installWithStaging(
        modelsRoot: File,
        profile: DeviceProfile,
        recentFailureCount: (String) -> Int,
        copyIntoStaging: suspend (File) -> Unit
    ): ModelPackageInstallResult {
        val staging = File(modelsRoot, ".staging/${UUID.randomUUID()}")
        var leaseFile: RandomAccessFile? = null
        var lease: FileLock? = null
        var ownsStaging = false
        return try {
            withStagingGate(modelsRoot) {
                recoverUnlockedStaging(modelsRoot)
                check(staging.mkdir()) { "无法创建模型 staging 目录" }
                ownsStaging = true
                leaseFile = RandomAccessFile(File(staging, LEASE_FILE), "rw")
                lease = leaseFile!!.channel.lock()
            }
            copyIntoStaging(staging)
            currentCoroutineContext().ensureActive()
            installStaged(staging, modelsRoot, profile, recentFailureCount) {
                lease?.release()
                lease = null
                leaseFile?.close()
                leaseFile = null
                check(File(staging, LEASE_FILE).delete()) { "无法移除安装租约文件" }
            }
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            ModelPackageInstallResult.Failure(error.message ?: "模型安装失败", error)
        } finally {
            if (ownsStaging) withStagingGate(modelsRoot) {
                lease?.release()
                leaseFile?.close()
                if (staging.exists()) removeWithoutFollowingLinks(staging)
            }
        }
    }

    internal fun recoverStaging(modelsRoot: File): Int = withStagingGate(modelsRoot) {
        recoverUnlockedStaging(modelsRoot)
    }

    private fun <T> withStagingGate(modelsRoot: File, action: () -> T): T = synchronized(STAGING_MONITOR) {
        modelsRoot.mkdirs()
        check(modelsRoot.isDirectory) { "无法创建 models 目录" }
        var ancestor: File? = modelsRoot.absoluteFile
        while (ancestor != null) {
            check(!symlinkChecker.isSymbolicLink(ancestor)) { "models 路径不能包含 symbolic link" }
            ancestor = ancestor.parentFile
        }
        val stagingRoot = File(modelsRoot, ".staging")
        check(!symlinkChecker.isSymbolicLink(stagingRoot)) {
            "models staging 目录不能是 symbolic link"
        }
        val gate = File(modelsRoot, ".staging.lock")
        check(!symlinkChecker.isSymbolicLink(gate)) { "staging lock 路径不安全" }
        RandomAccessFile(gate, "rw").use { file ->
            file.channel.lock().use {
                check(stagingRoot.mkdirs() || stagingRoot.isDirectory) { "无法创建 staging 目录" }
                action()
            }
        }
    }

    private fun recoverUnlockedStaging(modelsRoot: File): Int {
        var removed = 0
        File(modelsRoot, ".staging").listFiles().orEmpty().forEach { transaction ->
            if (!TRANSACTION_NAME.matches(transaction.name)) return@forEach
            if (symlinkChecker.isSymbolicLink(transaction)) {
                if (transaction.delete()) removed++
                return@forEach
            }
            if (!transaction.isDirectory) return@forEach
            val lease = File(transaction, LEASE_FILE)
            if (symlinkChecker.isSymbolicLink(lease)) return@forEach
            RandomAccessFile(lease, "rw").use { file ->
                val lock = try {
                    file.channel.tryLock()
                } catch (_: OverlappingFileLockException) {
                    null
                } ?: return@forEach
                // The staging gate still excludes publishers/recovery while the
                // lease is closed, including on systems that cannot unlink open files.
                lock.use { }
            }
            if (removeWithoutFollowingLinks(transaction)) removed++
        }
        return removed
    }

    private fun removeWithoutFollowingLinks(file: File): Boolean {
        if (!symlinkChecker.isSymbolicLink(file) && file.isDirectory) {
            if (file.listFiles()?.all(::removeWithoutFollowingLinks) != true) return false
        }
        return file.delete()
    }

    private suspend fun installStaged(
        staging: File,
        modelsRoot: File,
        profile: DeviceProfile,
        recentFailureCount: (String) -> Int,
        beforePublish: () -> Unit
    ): ModelPackageInstallResult {
        val installContext = currentCoroutineContext()
        val preflight = validator.validate(
            modelDir = staging,
            profile = profile,
            requireReadyMarker = false,
            recentFailureCount = recentFailureCount,
            checkCancelled = { installContext.ensureActive() }
        )
        if (preflight.result !is ModelScanResult.Ready) return preflight.failureResult()

        currentCoroutineContext().ensureActive()
        requireSpace(staging, METADATA_RESERVE_BYTES)
        File(staging, LocalModelValidator.READY_MARKER).writeText("ready\n")
        val checkedModel = preflight.result.model

        val target = File(modelsRoot, checkedModel.id)
        currentCoroutineContext().ensureActive()
        withStagingGate(modelsRoot) {
            installContext.ensureActive()
            check(!target.exists() && !symlinkChecker.isSymbolicLink(target)) {
                "模型已安装：${checkedModel.id}"
            }
            beforePublish()
            check(staging.renameTo(target)) { "模型目录无法原子落盘" }
        }
        return ModelPackageInstallResult.Success(
            checkedModel.copy(
                modelDir = target.canonicalPath,
                manifestPath = File(target, LocalModelValidator.MANIFEST_FILE).canonicalPath
            )
        )
    }

    private suspend fun copyDirectory(sourceDirectory: File, targetRoot: File) {
        check(sourceDirectory.isDirectory) { "模型源目录不存在" }
        check(!symlinkChecker.isSymbolicLink(sourceDirectory)) {
            "模型源目录不能是 symbolic link"
        }
        val sourceRoot = sourceDirectory.canonicalFile
        val targetCanonicalRoot = targetRoot.canonicalFile
        check(!targetCanonicalRoot.path.startsWith(sourceRoot.path + File.separator)) {
            "安装目录不能位于模型源目录内"
        }
        val files = mutableListOf<Pair<File, File>>()
        sourceRoot.walkTopDown().forEach { source ->
            currentCoroutineContext().ensureActive()
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
                check(!target.exists()) { "模型包包含重复文件名：${relative.path}" }
                files += source to target
            }
        }
        requirePackageSpace(targetRoot, files.map { it.first.length() })
        files.forEach { (source, target) ->
            source.inputStream().use { copyStream(it, target) }
        }
    }

    private suspend fun copyTree(sourceTree: Uri, targetRoot: File) {
        val rootId = DocumentsContract.getTreeDocumentId(sourceTree)
            ?: throw IOException("无法读取 SAF tree document id")
        val files = mutableListOf<SourceDocument>()
        collectChildren(sourceTree, rootId, targetRoot, mutableSetOf(), files)
        requirePackageSpace(targetRoot, files.map { it.size ?: 0L })
        files.forEach { copyFile(it.uri, it.target) }
    }

    private suspend fun collectChildren(
        treeUri: Uri,
        parentId: String,
        targetDirectory: File,
        visitedDocumentIds: MutableSet<String>,
        files: MutableList<SourceDocument>
    ) {
        currentCoroutineContext().ensureActive()
        check(visitedDocumentIds.add(parentId)) { "模型包目录存在循环引用" }
        val childrenUri = DocumentsContract.buildChildDocumentsUriUsingTree(treeUri, parentId)
        val projection = arrayOf(
            DocumentsContract.Document.COLUMN_DOCUMENT_ID,
            DocumentsContract.Document.COLUMN_DISPLAY_NAME,
            DocumentsContract.Document.COLUMN_MIME_TYPE,
            DocumentsContract.Document.COLUMN_SIZE
        )
        context.contentResolver.query(childrenUri, projection, null, null, null)?.use { cursor ->
            val idIndex = cursor.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_DOCUMENT_ID)
            val nameIndex = cursor.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_DISPLAY_NAME)
            val typeIndex = cursor.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_MIME_TYPE)
            val sizeIndex = cursor.getColumnIndex(DocumentsContract.Document.COLUMN_SIZE)
            val names = mutableSetOf<String>()
            while (cursor.moveToNext()) {
                currentCoroutineContext().ensureActive()
                val documentId = cursor.getString(idIndex)
                val name = cursor.getString(nameIndex).requireSafeDocumentName()
                check(names.add(name)) { "模型包包含重复文件名：$name" }
                val mimeType = cursor.getString(typeIndex)
                val documentUri = DocumentsContract.buildDocumentUriUsingTree(treeUri, documentId)
                val target = File(targetDirectory, name)
                if (mimeType == DocumentsContract.Document.MIME_TYPE_DIR) {
                    check(!target.exists()) { "模型包包含重复文件名：$name" }
                    check(target.mkdirs()) { "无法创建目录：$name" }
                    collectChildren(treeUri, documentId, target, visitedDocumentIds, files)
                } else {
                    check(!target.exists()) { "模型包包含重复文件名：$name" }
                    val size = if (sizeIndex < 0 || cursor.isNull(sizeIndex)) null else cursor.getLong(sizeIndex)
                    files += SourceDocument(documentUri, target, size?.takeIf { it >= 0L })
                }
            }
        } ?: throw IOException("无法读取模型包目录")
    }

    private suspend fun copyFile(source: Uri, target: File) {
        currentCoroutineContext().ensureActive()
        context.contentResolver.openInputStream(source)?.use { input ->
            copyStream(input, target)
        } ?: throw IOException("无法读取模型文件：$source")
    }

    private suspend fun copyStream(input: InputStream, target: File) {
        FileOutputStream(target).use { output ->
            val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
            while (true) {
                currentCoroutineContext().ensureActive()
                val count = input.read(buffer)
                currentCoroutineContext().ensureActive()
                if (count < 0) break
                requireSpace(target.parentFile!!, count + METADATA_RESERVE_BYTES)
                output.write(buffer, 0, count)
            }
            output.fd.sync()
        }
    }

    private fun requirePackageSpace(root: File, sizes: List<Long>) {
        val required = sizes.fold(METADATA_RESERVE_BYTES) { total, size ->
            Math.addExact(total, Math.addExact(size, 4096L))
        }
        requireSpace(root, required)
    }

    private fun requireSpace(root: File, required: Long) {
        check(availableStorageBytes(root) >= required) { "安装暂存空间不足，需要至少 $required bytes 可用空间" }
    }

    private fun String?.requireSafeDocumentName(): String {
        val value = this.orEmpty()
        check(value == value.trim() && value != LEASE_FILE) { "模型包包含非法文件名" }
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

    private data class SourceDocument(val uri: Uri, val target: File, val size: Long?)

    private companion object {
        val STAGING_MONITOR = Any()
        val TRANSACTION_NAME = Regex("[0-9a-f]{8}(-[0-9a-f]{4}){3}-[0-9a-f]{12}")
        const val LEASE_FILE = ".install.lock"
        const val METADATA_RESERVE_BYTES = 64L * 1024L
    }
}
