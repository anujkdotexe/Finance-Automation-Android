package com.financeautomation.data.account

import java.io.File

/** Removes what an account left in the app cache. */
object AccountCacheCleaner {
    /** Deletes the CSV exports folder of the previous account or of a signed out user (the files are shared only for a short time anyway). */
    fun clearExports(cacheDir: File?) {
        val exports = File(cacheDir ?: return, "exports")
        if (exports.isDirectory) exports.listFiles()?.forEach { it.delete() }
    }
}

/** Which posted notifications an account switch cancels: all of the app's own, except the ones that must stay. */
object AccountNotifications {
    fun idsToCancel(postedIds: List<Int>, keep: Set<Int>): List<Int> = postedIds.filter { it !in keep }
}
