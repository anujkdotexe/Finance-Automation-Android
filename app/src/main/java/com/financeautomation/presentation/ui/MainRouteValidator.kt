package com.financeautomation.presentation.ui

/**
 * Validates deep link URIs and extra_route parameters targeting [MainActivity].
 * Enforces a strict whitelist of known application screen routes to protect against
 * arbitrary screen navigation, deep-link hijacking, and malicious parameter injection (H2).
 */
object MainRouteValidator {

    val ALLOWED_ROUTES: Set<String> = setOf(
        AppRoutes.DASHBOARD,
        AppRoutes.NEEDS_REVIEW,
        AppRoutes.TRANSACTIONS,
        AppRoutes.ACCOUNTS,
        AppRoutes.CATEGORIES,
        AppRoutes.PEOPLE_TRIPS,
        AppRoutes.SUBSCRIPTIONS,
        AppRoutes.INSIGHTS,
        AppRoutes.CAPTURE_PERMISSIONS,
        AppRoutes.SYNC_BACKUP,
        AppRoutes.PREFERENCES,
        AppRoutes.DIAGNOSTICS,
        AppRoutes.ABOUT,
        AppRoutes.ONBOARDING,
        AppRoutes.NOTIFICATION_ACCESS,
        AppRoutes.ERROR_API_NOT_ENABLED,
        AppRoutes.ERROR_PERMISSION_NOT_GRANTED,
        AppRoutes.ERROR_RATE_LIMITED
    )

    private const val DEEP_LINK_SCHEME = "financeautomation"

    /**
     * Validates a candidate route identifier against the known screen routes.
     * Returns the validated route name if accepted, or null if unknown or malformed.
     */
    fun validateRoute(candidate: String?): String? {
        if (candidate.isNullOrBlank()) return null
        val trimmed = candidate.trim()
        return if (trimmed in ALLOWED_ROUTES) trimmed else null
    }

    /**
     * Resolves and validates an intent's route from extra_route or a financeautomation:// URI string.
     * Ignores any query parameters, payload arguments, or unknown route names.
     */
    fun resolveRoute(extraRoute: String?, dataUriString: String?): String? {
        // 1. Extra route takes precedence if valid
        val fromExtra = validateRoute(extraRoute)
        if (fromExtra != null) {
            return fromExtra
        }

        // 2. Validate deep link URI
        if (!dataUriString.isNullOrBlank()) {
            val schemePrefix = "$DEEP_LINK_SCHEME://"
            if (dataUriString.startsWith(schemePrefix, ignoreCase = true)) {
                val withoutScheme = dataUriString.substring(schemePrefix.length)
                // Strip query parameters and fragment
                val pathOnly = withoutScheme.split('?', '#').firstOrNull { it.isNotBlank() } ?: return null
                val segments = pathOnly.split('/').filter { it.isNotBlank() }
                val targetRoute = if (segments.isNotEmpty() && segments[0].equals("route", ignoreCase = true)) {
                    segments.getOrNull(1)
                } else {
                    segments.firstOrNull()
                }
                return validateRoute(targetRoute)
            }
        }

        return null
    }
}

