package com.financeautomation.core.utils

import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import com.financeautomation.BuildConfig
import java.util.Locale

/**
 * OEM background activity discovery scan (Phase 9.4, Plan 4.4).
 * Scans installed vendor packages and exported power/startup activities to assist
 * in learning new ROM targets without collecting user or financial data.
 * Compiled and executed exclusively in debug and beta builds.
 */
object DiscoveryScanner {

    private val VENDOR_PREFIXES = listOf(
        "com.oplus.",
        "com.coloros.",
        "com.oppo.",
        "com.miui.",
        "com.xiaomi.",
        "com.samsung.android.",
        "com.vivo.",
        "com.iqoo.",
        "com.oneplus.",
        "com.huawei.",
        "com.hihonor.",
        "com.transsion."
    )

    private val TARGET_KEYWORDS = listOf(
        "autostart",
        "startup",
        "launch",
        "battery",
        "power",
        "permission",
        "protect",
        "sleep",
        "optimi",
        "bgstart",
        "associate"
    )

    private const val MAX_SCAN_RESULTS = 200

    fun scan(context: Context): List<String> {
        if (!BuildConfig.ENABLE_DISCOVERY_SCAN) {
            return emptyList()
        }

        val results = mutableListOf<String>()
        try {
            val pm = context.packageManager ?: return emptyList()
            val flags = PackageManager.GET_ACTIVITIES
            val packages = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                pm.getInstalledPackages(PackageManager.PackageInfoFlags.of(flags.toLong()))
            } else {
                @Suppress("DEPRECATION")
                pm.getInstalledPackages(flags)
            }

            for (pkg in packages) {
                val pkgName = pkg.packageName ?: continue
                val isVendor = VENDOR_PREFIXES.any { pkgName.startsWith(it, ignoreCase = true) }
                if (!isVendor) continue

                val activities = pkg.activities ?: continue
                for (activity in activities) {
                    if (!activity.exported) continue
                    val actName = activity.name ?: continue
                    val actLower = actName.lowercase(Locale.ROOT)
                    val matchesKeyword = TARGET_KEYWORDS.any { actLower.contains(it) }
                    if (matchesKeyword) {
                        results.add("$pkgName/$actName")
                        if (results.size >= MAX_SCAN_RESULTS) {
                            return results
                        }
                    }
                }
            }
        } catch (e: Exception) {
            Diagnostics.record("DiscoveryScanner", "scan error: ${e.javaClass.simpleName}")
        }
        return results
    }
}

