package com.financeautomation.presentation.ui

/**
 * Decides whether the Setup screen is needed. Pure Kotlin so the cold start behaviour can be tested.
 *
 * Each preference is read from a flow. Until a flow has emitted its first real value the preference is
 * [Pref.Loading], which must never be treated as "empty": that made the Setup screen flash and latch on
 * every cold start.
 */
object SetupGate {

    /** One preference read from a flow: either not yet emitted, or loaded with a value that may be null. */
    sealed interface Pref {
        data object Loading : Pref
        data class Loaded(val value: String?) : Pref
    }

    enum class Decision { LOADING, COMPLETE, INCOMPLETE }

    /**
     * @param email the saved Google account email
     * @param yearSpreadsheetId the spreadsheet id saved for the current year
     * @param legacySpreadsheetId the older single spreadsheet id key; some resolver paths set only this one,
     *  so a set legacy id counts as a spreadsheet when the per-year key is empty
     */
    fun decide(email: Pref, yearSpreadsheetId: Pref, legacySpreadsheetId: Pref): Decision {
        if (email !is Pref.Loaded || yearSpreadsheetId !is Pref.Loaded || legacySpreadsheetId !is Pref.Loaded) {
            return Decision.LOADING
        }
        val hasEmail = !email.value.isNullOrBlank()
        val hasSheet = !yearSpreadsheetId.value.isNullOrBlank() || !legacySpreadsheetId.value.isNullOrBlank()
        return if (hasEmail && hasSheet) Decision.COMPLETE else Decision.INCOMPLETE
    }

    /**
     * Latches once a real incomplete state is seen, so a finished sign in keeps showing Continue instead of
     * jumping away. Loading and complete never latch.
     */
    fun latch(previouslyLatched: Boolean, decision: Decision): Boolean =
        previouslyLatched || decision == Decision.INCOMPLETE
}
