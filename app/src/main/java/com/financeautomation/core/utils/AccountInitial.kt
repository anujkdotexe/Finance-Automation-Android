package com.financeautomation.core.utils

/**
 * Returns the first letter of [email] uppercased for the avatar circle, or "?" when blank or missing.
 */
fun accountInitial(email: String?): String {
    val first = email?.trim()?.firstOrNull() ?: return "?"
    return first.uppercaseChar().toString()
}
