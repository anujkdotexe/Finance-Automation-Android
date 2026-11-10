package com.financeautomation.presentation.health

import android.content.Context
import android.content.Intent
import android.os.Build
import com.financeautomation.R
import com.financeautomation.data.repository.OemRoutesRepository
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
 * Manages device-specific background activity and autostart candidate intents with resolve-then-fallback execution (Phase 9.2).
 * Reads routes dynamically from assets/oem_routes.json via OemRoutesRepository.
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
        return OemRoutesRepository.resolveCandidateIntents(context).map { it.intent }
    }

    fun canResolve(context: Context, intent: Intent): Boolean {
        return OemRoutesRepository.canResolve(context, intent)
    }

    fun resolveCandidateIntent(context: Context): Intent {
        return OemRoutesRepository.resolveFirstWorkingIntent(context)
    }
}
