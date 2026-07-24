package com.example.persona.core.ai

import android.content.Context
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.withContext
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import javax.inject.Inject
import javax.inject.Singleton

sealed class DownloadState {
    data class Progress(val percent: Int) : DownloadState()
    object Success : DownloadState()
    data class Failure(val message: String) : DownloadState()
}

@Singleton
class ModelDownloadManager @Inject constructor(
    @dagger.hilt.android.qualifiers.ApplicationContext private val context: Context
) {
    private val modelName = "gemma-2b-it-cpu-int4.bin"

    fun isModelAvailable(): Boolean = File(context.filesDir, modelName).exists()

    fun downloadModel(url: String): Flow<DownloadState> = flow {
        withContext(Dispatchers.IO) {
            val dest = File(context.filesDir, modelName)
            val tmp = File(context.filesDir, "$modelName.tmp")
            try {
                val connection = URL(url).openConnection() as HttpURLConnection
                connection.connect()
                val totalBytes = connection.contentLengthLong
                var downloadedBytes = 0L
                var lastEmittedPercent = -1

                connection.inputStream.use { input ->
                    tmp.outputStream().use { output ->
                        val buffer = ByteArray(8192)
                        var read: Int
                        while (input.read(buffer).also { read = it } != -1) {
                            output.write(buffer, 0, read)
                            downloadedBytes += read
                            if (totalBytes > 0) {
                                val percent = (downloadedBytes * 100 / totalBytes).toInt()
                                if (percent != lastEmittedPercent) {
                                    lastEmittedPercent = percent
                                    emit(DownloadState.Progress(percent))
                                }
                            }
                        }
                    }
                }
                tmp.renameTo(dest)
                emit(DownloadState.Success)
            } catch (e: Exception) {
                Log.e("ModelDownloadManager", "Download failed", e)
                tmp.delete()
                emit(DownloadState.Failure(e.message ?: "Unknown error"))
            }
        }
    }
}
