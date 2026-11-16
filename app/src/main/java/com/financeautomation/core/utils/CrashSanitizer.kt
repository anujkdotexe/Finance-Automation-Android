package com.financeautomation.core.utils

/**
 * An uncaught exception rebuilt without any message text. Exception messages can contain amounts, payees, account
 * names or notification text, so only the original class name and the stack frames are kept. Pure Kotlin.
 */
class SanitizedCrashException(originalClassName: String, cause: Throwable? = null) :
    RuntimeException(originalClassName, cause)

object CrashSanitizer {

    private const val MAX_CAUSE_DEPTH = 5

    /** Returns a copy of [throwable] that keeps the class names and stack frames but none of the messages. */
    fun sanitize(throwable: Throwable): Throwable = rebuild(throwable, 0)

    private fun rebuild(throwable: Throwable, depth: Int): Throwable {
        val cause = throwable.cause
        val sanitizedCause = if (cause != null && cause !== throwable && depth < MAX_CAUSE_DEPTH) {
            rebuild(cause, depth + 1)
        } else {
            null
        }
        val copy = SanitizedCrashException(throwable.javaClass.name, sanitizedCause)
        copy.stackTrace = throwable.stackTrace
        return copy
    }
}
