package com.example.persona.core.ai

import android.system.Os
import android.system.OsConstants
import java.io.File

/**
 * Test seam for symlink checks. Android's Os.lstat is not available on the JVM,
 * so unit tests provide a fake implementation.
 */
fun interface SymlinkChecker {
    fun isSymbolicLink(file: File): Boolean
}

object AndroidSymlinkChecker : SymlinkChecker {
    override fun isSymbolicLink(file: File): Boolean {
        return runCatching {
            val mode = Os.lstat(file.path).st_mode
            mode and OsConstants.S_IFMT == OsConstants.S_IFLNK
        }.getOrDefault(false)
    }
}
