package com.example.persona.core.ai.telemetry

import java.io.File
import java.io.IOException

internal object JournalCommit {
    fun replace(temporary: File, committed: File) {
        val files = try {
            Class.forName("java.nio.file.Files")
        } catch (_: ClassNotFoundException) {
            null
        }
        if (files == null) {
            // Android 24/25 have POSIX rename but not the java.nio.file API.
            if (!temporary.renameTo(committed)) throw IOException("Cannot replace journal")
            return
        }
        try {
            // Reflection preserves the API-24 path without linking API-26-only types.
            val path = Class.forName("java.nio.file.Path")
            val option = Class.forName("java.nio.file.CopyOption")
            val standard = Class.forName("java.nio.file.StandardCopyOption")
            val options = java.lang.reflect.Array.newInstance(option, 2)
            java.lang.reflect.Array.set(options, 0, standard.getField("ATOMIC_MOVE").get(null))
            java.lang.reflect.Array.set(options, 1, standard.getField("REPLACE_EXISTING").get(null))
            val toPath = File::class.java.getMethod("toPath")
            files.getMethod("move", path, path, options.javaClass)
                .invoke(null, toPath.invoke(temporary), toPath.invoke(committed), options)
        } catch (_: Exception) {
            // Never replace a failed atomic move with delete-then-rename.
            throw IOException("Cannot replace journal")
        }
    }
}
