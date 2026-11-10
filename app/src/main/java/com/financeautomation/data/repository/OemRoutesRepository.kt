package com.financeautomation.data.repository

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import com.financeautomation.core.utils.Diagnostics
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.util.Locale

@Serializable
data class OemCandidateConfig(
    val component: String? = null,
    val action: String? = null,
    val packageExtra: String? = null
)

@Serializable
data class OemRouteMatch(
    val manufacturer: List<String> = emptyList(),
    val brand: List<String> = emptyList(),
    val minSdk: Int = 28
)

@Serializable
data class OemRouteConfig(
    val id: String,
    val match: OemRouteMatch,
    val candidates: List<OemCandidateConfig>,
    val instructionsKey: String
)

@Serializable
data class OemRoutesFile(
    val schema: Int,
    val routes: List<OemRouteConfig>
)

data class CandidateResolutionResult(
    val candidate: OemCandidateConfig,
    val intent: Intent,
    val resolves: Boolean
)

/**
 * Repository that parses, validates, and resolves OEM background routes from assets/oem_routes.json.
 * Falls back to standard system battery settings upon missing or malformed configuration (Phase 9.2).
 */
object OemRoutesRepository {

    private const val ROUTES_ASSET_PATH = "oem_routes.json"
    private val json = Json { ignoreUnknownKeys = true }

    private val genericFallbackRoute = OemRouteConfig(
        id = "generic_fallback",
        match = OemRouteMatch(minSdk = 28),
        candidates = listOf(
            OemCandidateConfig(action = Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)
        ),
        instructionsKey = "oem_instructions_default"
    )

    fun loadRoutes(context: Context): List<OemRouteConfig> {
        return try {
            val content = context.assets.open(ROUTES_ASSET_PATH).bufferedReader().use { it.readText() }
            val parsed = json.decodeFromString<OemRoutesFile>(content)
            if (parsed.routes.isNotEmpty()) parsed.routes else listOf(genericFallbackRoute)
        } catch (e: Exception) {
            Diagnostics.record("OemRoutesRepository", "Failed to load routes asset: ${e.javaClass.simpleName}")
            listOf(genericFallbackRoute)
        }
    }

    fun findActiveRoute(context: Context): OemRouteConfig {
        val routes = loadRoutes(context)
        val currentManufacturer = Build.MANUFACTURER.lowercase(Locale.ROOT)
        val currentBrand = Build.BRAND.lowercase(Locale.ROOT)
        val currentSdk = Build.VERSION.SDK_INT

        for (route in routes) {
            val match = route.match
            if (currentSdk < match.minSdk) continue

            val manufacturerMatch = match.manufacturer.isEmpty() || match.manufacturer.any {
                currentManufacturer.contains(it.lowercase(Locale.ROOT))
            }
            val brandMatch = match.brand.isEmpty() || match.brand.any {
                currentBrand.contains(it.lowercase(Locale.ROOT))
            }

            if (manufacturerMatch && brandMatch && (match.manufacturer.isNotEmpty() || match.brand.isNotEmpty())) {
                return route
            }
        }
        return routes.find { it.id == "generic_fallback" } ?: genericFallbackRoute
    }

    fun buildIntent(context: Context, candidate: OemCandidateConfig): Intent {
        val intent = Intent()
        if (!candidate.action.isNullOrBlank()) {
            intent.action = candidate.action
        }
        if (!candidate.component.isNullOrBlank()) {
            val parts = candidate.component.split("/")
            if (parts.size == 2) {
                intent.component = ComponentName(parts[0], parts[1])
            }
        }
        if (!candidate.packageExtra.isNullOrBlank()) {
            intent.putExtra(candidate.packageExtra, context.packageName)
        }
        return intent
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

    fun resolveCandidateIntents(context: Context): List<CandidateResolutionResult> {
        val route = findActiveRoute(context)
        return route.candidates.map { candidate ->
            val intent = buildIntent(context, candidate)
            CandidateResolutionResult(
                candidate = candidate,
                intent = intent,
                resolves = canResolve(context, intent)
            )
        }
    }

    fun resolveFirstWorkingIntent(context: Context): Intent {
        val candidates = resolveCandidateIntents(context)
        val working = candidates.firstOrNull { it.resolves }
        if (working != null) {
            return working.intent.apply { addFlags(Intent.FLAG_ACTIVITY_NEW_TASK) }
        }
        return Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
            data = Uri.parse("package:${context.packageName}")
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
    }
}

