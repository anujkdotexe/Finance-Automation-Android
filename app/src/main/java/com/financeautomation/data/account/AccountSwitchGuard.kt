package com.financeautomation.data.account

import com.financeautomation.domain.repository.PreferencesRepository

/**
 * What is stored on this phone for one Google account, and how it is wiped. The Room version lives in
 * [RoomAccountDataStore]; tests use a fake.
 */
interface AccountDataStore {
    /** Entries that have not reached the Google Sheet yet (real queue items, not config pushes). */
    suspend fun pendingCount(): Int

    /** True when any transaction, income entry or queue item is stored. */
    suspend fun hasLocalData(): Boolean

    /** Deletes every per-account row and preference in one transaction. App level settings stay. */
    suspend fun wipe()
}

enum class SwitchDecision { PROCEED, CONFIRM_SWITCH }

data class SwitchEvaluation(val decision: SwitchDecision, val pendingCount: Int)

/**
 * Keeps one account's ledger from leaking into another's.
 *
 * The ledger owner email is remembered. Signing in with the same email (ignoring case) changes nothing. A different
 * email needs the user's confirmation before anything is synced; confirming stops background work, wipes the previous
 * account's data, records the new owner and restarts the work. A plain sign out never calls this and wipes nothing.
 */
class AccountSwitchGuard(
    private val preferences: PreferencesRepository,
    private val store: AccountDataStore,
    private val stopBackgroundWork: () -> Unit = {},
    private val startBackgroundWork: suspend () -> Unit = {},
    /** Runs after a successful switch: refresh widgets and clear notifications that show the old account's figures. */
    private val afterSwitch: suspend () -> Unit = {}
) {

    suspend fun evaluate(newEmail: String): SwitchEvaluation {
        val owner = preferences.getPreference(KEY_LEDGER_OWNER)
        // Without an owner, the account that was signed in before the last sign out counts as the owner
        val known = owner?.takeIf { it.isNotBlank() } ?: preferences.getPreference(KEY_LAST_EMAIL)
        val decision = if (owner.isNullOrBlank() && !known.isNullOrBlank()) {
            decide(known, newEmail, hasData = { store.hasLocalData() })
        } else {
            decide(owner, newEmail, hasData = { store.hasLocalData() })
        }
        val pending = if (decision == SwitchDecision.CONFIRM_SWITCH) store.pendingCount() else 0
        return SwitchEvaluation(decision, pending)
    }

    /** Called after the user confirmed: nothing of the previous account may remain or be pushed with the new token. */
    suspend fun switchTo(newEmail: String) {
        stopBackgroundWork()
        try {
            store.wipe()
            // Only after the wipe worked: a failed wipe leaves the previous owner in place
            preferences.setPreference(KEY_LEDGER_OWNER, newEmail.trim(), isDirty = false)
            preferences.setPreference(KEY_LAST_EMAIL, newEmail.trim(), isDirty = false)
        } finally {
            // The work stopped above always comes back, whether or not the wipe succeeded
            startBackgroundWork()
        }
        afterSwitch()
    }

    /** Records the owner when sign in was allowed without a wipe (first account, or the same account again). */
    suspend fun recordOwner(email: String) {
        val owner = preferences.getPreference(KEY_LEDGER_OWNER)
        if (owner.isNullOrBlank() || owner.equals(email.trim(), ignoreCase = true)) {
            preferences.setPreference(KEY_LEDGER_OWNER, email.trim(), isDirty = false)
            preferences.setPreference(KEY_LAST_EMAIL, email.trim(), isDirty = false)
        }
    }

    /** An install from before this feature has a signed in account but no owner; adopt it so a switch is noticed. */
    suspend fun adoptSignedInAccount() {
        if (!preferences.getPreference(KEY_LEDGER_OWNER).isNullOrBlank()) return
        val email = preferences.getGoogleAccountEmailSync()
        if (!email.isNullOrBlank()) recordOwner(email)
    }

    companion object {
        const val KEY_LEDGER_OWNER: String = "ledger_owner_email"

        /** The account that was signed in before the last sign out; kept through sign out, forgotten by a wipe. */
        const val KEY_LAST_EMAIL: String = "last_signed_in_email"

        /** Pure rule: no owner and no data is a first sign in; the same email is fine; anything else asks first. */
        suspend fun decide(owner: String?, newEmail: String, hasData: suspend () -> Boolean): SwitchDecision = when {
            owner.isNullOrBlank() -> if (hasData()) SwitchDecision.CONFIRM_SWITCH else SwitchDecision.PROCEED
            owner.trim().equals(newEmail.trim(), ignoreCase = true) -> SwitchDecision.PROCEED
            else -> SwitchDecision.CONFIRM_SWITCH
        }
    }
}
