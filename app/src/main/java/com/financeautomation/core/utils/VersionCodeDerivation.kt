package com.financeautomation.core.utils

import java.io.File

/**
 * Pure helper for deriving the application's versionCode from git commit count,
 * with a resilient fallback.
 */
object VersionCodeDerivation {

    fun parseCommitCount(output: String?, fallback: Int = 1): Int {
        if (output.isNullOrBlank()) return fallback
        return output.trim().toIntOrNull()?.takeIf { it > 0 } ?: fallback
    }

    fun deriveFromGit(workingDir: File = File("."), fallback: Int = 1): Int {
        return try {
            val process = ProcessBuilder("git", "rev-list", "--count", "HEAD")
                .directory(workingDir)
                .redirectErrorStream(true)
                .start()
            val text = process.inputStream.bufferedReader().readText()
            val exit = process.waitFor()
            if (exit == 0) parseCommitCount(text, fallback) else fallback
        } catch (_: Exception) {
            fallback
        }
    }
}
