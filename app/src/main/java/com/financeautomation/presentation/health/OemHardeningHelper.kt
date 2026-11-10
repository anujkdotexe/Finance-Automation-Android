package com.financeautomation.presentation.health

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import com.financeautomation.R
import java.util.Locale

/**
 * OEM brand classification used strictly to select user instruction strings and candidate intents (Phase 7.3).
 * Never alters accounting, database, parsing, or background processing logic.
 */
enum class OemBrand {
    SAMSUNG,
    XIAOMI,
    OPPO,
    VIVO,
    ONEPLUS,
    OTHER
}

data class OemInfo(
    val brand: OemBrand,
    val brandNameRes: Int,
    val instructionRes: Int
)

/**
 * Manages device-specific background activity and autostart candidate intents with resolve-then-fallback execution.
 */
object OemHardeningHelper {

    fun detectBrand(): OemBrand {
        val manufacturer = Build.MANUFACTURER.lowercase(Locale.ROOT)
        val brand = Build.BRAND.lowercase(Locale.ROOT)
        return when {
            manufacturer.contains("samsung") || brand.contains("samsung") -> OemBrand.SAMSUNG
            manufacturer.contains("xiaomi") || brand.contains("xiaomi") ||
                    manufacturer.contains("redmi") || brand.contains("redmi") ||
                    manufacturer.contains("poco") || brand.contains("poco") -> OemBrand.XIAOMI
            manufacturer.contains("oppo") || brand.contains("oppo") ||
                    manufacturer.contains("realme") || brand.contains("realme") -> OemBrand.OPPO
            manufacturer.contains("vivo") || brand.contains("vivo") ||
                    manufacturer.contains("iqoo") || brand.contains("iqoo") -> OemBrand.VIVO
            manufacturer.contains("oneplus") || brand.contains("oneplus") -> OemBrand.ONEPLUS
            else -> OemBrand.OTHER
        }
    }

    fun getOemInfo(): OemInfo {
        return when (detectBrand()) {
            OemBrand.SAMSUNG -> OemInfo(
                brand = OemBrand.SAMSUNG,
                brandNameRes = R.string.oem_brand_samsung,
                instructionRes = R.string.oem_instructions_samsung
            )
            OemBrand.XIAOMI -> OemInfo(
                brand = OemBrand.XIAOMI,
                brandNameRes = R.string.oem_brand_xiaomi,
                instructionRes = R.string.oem_instructions_xiaomi
            )
            OemBrand.OPPO -> OemInfo(
                brand = OemBrand.OPPO,
                brandNameRes = R.string.oem_brand_oppo,
                instructionRes = R.string.oem_instructions_oppo
            )
            OemBrand.VIVO -> OemInfo(
                brand = OemBrand.VIVO,
                brandNameRes = R.string.oem_brand_vivo,
                instructionRes = R.string.oem_instructions_vivo
            )
            OemBrand.ONEPLUS -> OemInfo(
                brand = OemBrand.ONEPLUS,
                brandNameRes = R.string.oem_brand_oneplus,
                instructionRes = R.string.oem_instructions_oneplus
            )
            OemBrand.OTHER -> OemInfo(
                brand = OemBrand.OTHER,
                brandNameRes = R.string.oem_brand_default,
                instructionRes = R.string.oem_instructions_default
            )
        }
    }

    fun getCandidateIntents(context: Context): List<Intent> {
        val packageName = context.packageName
        return when (detectBrand()) {
            OemBrand.SAMSUNG -> listOf(
                Intent().setComponent(ComponentName("com.samsung.android.lool", "com.samsung.android.sm.battery.ui.BatteryActivity")),
                Intent().setComponent(ComponentName("com.samsung.android.lool", "com.samsung.android.sm.ui.battery.BatteryActivity")),
                Intent().setComponent(ComponentName("com.samsung.android.lool", "com.samsung.android.sm.battery.ui.BatteryAppDetailActivity")).apply {
                    putExtra("package_name", packageName)
                },
                Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)
            )
            OemBrand.XIAOMI -> listOf(
                Intent().setComponent(ComponentName("com.miui.securitycenter", "com.miui.permcenter.autostart.AutoStartManagementActivity")),
                Intent("miui.intent.action.APP_PERM_EDITOR").apply {
                    putExtra("extra_pkgname", packageName)
                },
                Intent().setComponent(ComponentName("com.miui.securitycenter", "com.miui.permcenter.permissions.PermissionsEditorActivity")).apply {
                    putExtra("extra_pkgname", packageName)
                },
                Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)
            )
            OemBrand.OPPO -> listOf(
                Intent().setComponent(ComponentName("com.oplus.battery", "com.oplus.startupapp.view.AssociateStartActivity")),
                Intent().setComponent(ComponentName("com.oplus.battery", "com.oplus.powermanager.fuelgaue.PowerUsageModelActivity")),
                Intent().setComponent(ComponentName("com.oplus.securitypermission", "com.oplusos.securitypermission.appcontrolcenter.view.AppControlCenterActivity")),
                Intent().setComponent(ComponentName("com.oplus.safecenter", "com.oplus.safecenter.permission.AppPermissionControlActivity")),
                Intent().setComponent(ComponentName("com.coloros.safecenter", "com.coloros.safecenter.permission.startup.StartupAppListActivity")),
                Intent().setComponent(ComponentName("com.oppo.safe", "com.oppo.safe.permission.startup.StartupAppListActivity")),
                Intent().setComponent(ComponentName("com.coloros.safecenter", "com.coloros.safecenter.startupapp.StartupAppListActivity")),
                Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)
            )
            OemBrand.VIVO -> listOf(
                Intent().setComponent(ComponentName("com.vivo.permissionmanager", "com.vivo.permissionmanager.activity.BgStartUpManagerActivity")),
                Intent().setComponent(ComponentName("com.iqoo.secure", "com.iqoo.secure.ui.phoneoptimize.BgStartUpManager")),
                Intent().setComponent(ComponentName("com.vivo.permissionmanager", "com.vivo.permissionmanager.activity.PurviewTabActivity")),
                Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)
            )
            OemBrand.ONEPLUS -> listOf(
                Intent().setComponent(ComponentName("com.oplus.battery", "com.oplus.startupapp.view.AssociateStartActivity")),
                Intent().setComponent(ComponentName("com.oplus.battery", "com.oplus.powermanager.fuelgaue.PowerUsageModelActivity")),
                Intent().setComponent(ComponentName("com.oplus.securitypermission", "com.oplusos.securitypermission.appcontrolcenter.view.AppControlCenterActivity")),
                Intent().setComponent(ComponentName("com.oplus.safecenter", "com.oplus.safecenter.permission.AppPermissionControlActivity")),
                Intent().setComponent(ComponentName("com.oneplus.security", "com.oneplus.security.chainlaunch.view.ChainLaunchAppListActivity")),
                Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)
            )
            OemBrand.OTHER -> listOf(
                Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)
            )
        }
    }

    fun canResolve(context: Context, intent: Intent): Boolean {
        val pm = context.packageManager ?: return false
        val resolveInfo = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            pm.resolveActivity(intent, PackageManager.ResolveInfoFlags.of(0L))
        } else {
            @Suppress("DEPRECATION")
            pm.resolveActivity(intent, 0)
        }
        return resolveInfo != null
    }

    fun resolveCandidateIntent(context: Context): Intent {
        val candidates = getCandidateIntents(context)
        for (candidate in candidates) {
            if (canResolve(context, candidate)) {
                return candidate.apply { addFlags(Intent.FLAG_ACTIVITY_NEW_TASK) }
            }
        }
        return Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
            data = Uri.parse("package:${context.packageName}")
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
    }
}

