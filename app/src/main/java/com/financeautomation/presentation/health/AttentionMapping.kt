package com.financeautomation.presentation.health

import androidx.annotation.StringRes
import com.financeautomation.R
import com.financeautomation.core.utils.SyncErrorKind
import com.financeautomation.presentation.viewmodel.HealthCheckId

/**
 * One vocabulary for every place the app says "this needs you": the Setup page, the permission rows, Sync and backup,
 * the sync status lines, failed queue cards, Diagnostics and the app lock row. The mapping from a problem to its
 * words and its one fixing action lives here, in plain Kotlin, so every screen says the same thing.
 */
enum class AttentionState { OK, NEEDS_ATTENTION, IN_PROGRESS }

/** What can be wrong. */
enum class AttentionIssue {
    SIGN_IN,
    GOOGLE_AUTHORIZATION,
    WORKBOOK,
    NOTIFICATION_ACCESS,
    OVERLAY,
    USAGE_ACCESS,
    BATTERY,
    POST_NOTIFICATIONS,
    ACCOUNTS,
    TOKEN_EXPIRED,
    SYNC_FAILING,
    RATE_LIMITED,
    APP_LOCK
}

/** The one thing a button does to fix it: a system settings screen, a permission prompt or an in-app screen. */
enum class AttentionAction {
    SIGN_IN,
    RECONNECT_GOOGLE,
    SET_UP_WORKBOOK,
    RETRY_SYNC,
    OPEN_NOTIFICATION_ACCESS,
    OPEN_OVERLAY_SETTINGS,
    OPEN_USAGE_ACCESS,
    OPEN_BATTERY_SETTINGS,
    REQUEST_POST_NOTIFICATIONS,
    ADD_ACCOUNT,
    OPEN_SECURITY_SETTINGS
}

/** Everything a card needs to draw one state of one issue. [reason] and [actionLabel] are null unless attention is needed. */
data class AttentionContent(
    val issue: AttentionIssue,
    val state: AttentionState,
    @StringRes val title: Int,
    @StringRes val doneText: Int,
    @StringRes val reason: Int?,
    @StringRes val actionLabel: Int?,
    val action: AttentionAction?
)

private data class IssueText(
    @StringRes val title: Int,
    @StringRes val done: Int,
    @StringRes val reason: Int,
    @StringRes val actionLabel: Int,
    val action: AttentionAction
)

private fun textFor(issue: AttentionIssue): IssueText = when (issue) {
    AttentionIssue.SIGN_IN -> IssueText(R.string.attn_sign_in_title, R.string.attn_sign_in_done, R.string.attn_sign_in_reason, R.string.attn_sign_in_action, AttentionAction.SIGN_IN)
    AttentionIssue.GOOGLE_AUTHORIZATION -> IssueText(R.string.attn_auth_title, R.string.attn_auth_done, R.string.attn_auth_reason, R.string.attn_auth_action, AttentionAction.RECONNECT_GOOGLE)
    AttentionIssue.WORKBOOK -> IssueText(R.string.attn_workbook_title, R.string.attn_workbook_done, R.string.attn_workbook_reason, R.string.attn_workbook_action, AttentionAction.SET_UP_WORKBOOK)
    AttentionIssue.NOTIFICATION_ACCESS -> IssueText(R.string.attn_notif_access_title, R.string.attn_notif_access_done, R.string.attn_notif_access_reason, R.string.attn_notif_access_action, AttentionAction.OPEN_NOTIFICATION_ACCESS)
    AttentionIssue.OVERLAY -> IssueText(R.string.attn_overlay_title, R.string.attn_overlay_done, R.string.attn_overlay_reason, R.string.attn_overlay_action, AttentionAction.OPEN_OVERLAY_SETTINGS)
    AttentionIssue.USAGE_ACCESS -> IssueText(R.string.attn_usage_title, R.string.attn_usage_done, R.string.attn_usage_reason, R.string.attn_usage_action, AttentionAction.OPEN_USAGE_ACCESS)
    AttentionIssue.BATTERY -> IssueText(R.string.attn_battery_title, R.string.attn_battery_done, R.string.attn_battery_reason, R.string.attn_battery_action, AttentionAction.OPEN_BATTERY_SETTINGS)
    AttentionIssue.POST_NOTIFICATIONS -> IssueText(R.string.attn_post_notif_title, R.string.attn_post_notif_done, R.string.attn_post_notif_reason, R.string.attn_post_notif_action, AttentionAction.REQUEST_POST_NOTIFICATIONS)
    AttentionIssue.ACCOUNTS -> IssueText(R.string.attn_accounts_title, R.string.attn_accounts_done, R.string.attn_accounts_reason, R.string.attn_accounts_action, AttentionAction.ADD_ACCOUNT)
    AttentionIssue.TOKEN_EXPIRED -> IssueText(R.string.attn_token_title, R.string.attn_token_done, R.string.attn_token_reason, R.string.attn_token_action, AttentionAction.RECONNECT_GOOGLE)
    AttentionIssue.SYNC_FAILING -> IssueText(R.string.attn_sync_title, R.string.attn_sync_done, R.string.attn_sync_reason, R.string.attn_sync_action, AttentionAction.RETRY_SYNC)
    AttentionIssue.RATE_LIMITED -> IssueText(R.string.attn_rate_title, R.string.attn_rate_done, R.string.attn_rate_reason, R.string.attn_rate_action, AttentionAction.RETRY_SYNC)
    AttentionIssue.APP_LOCK -> IssueText(R.string.attn_lock_title, R.string.attn_lock_done, R.string.attn_lock_reason, R.string.attn_lock_action, AttentionAction.OPEN_SECURITY_SETTINGS)
}

/** The words and the action for [issue] in [state]. Only NEEDS_ATTENTION carries a reason and an action. */
fun attentionContent(issue: AttentionIssue, state: AttentionState): AttentionContent {
    val t = textFor(issue)
    val needs = state == AttentionState.NEEDS_ATTENTION
    return AttentionContent(
        issue = issue,
        state = state,
        title = t.title,
        doneText = t.done,
        reason = if (needs) t.reason else null,
        actionLabel = if (needs) t.actionLabel else null,
        action = if (needs) t.action else null
    )
}

/** OK when [ok], otherwise IN_PROGRESS while [working], otherwise NEEDS_ATTENTION. */
fun attentionState(ok: Boolean, working: Boolean = false): AttentionState = when {
    ok -> AttentionState.OK
    working -> AttentionState.IN_PROGRESS
    else -> AttentionState.NEEDS_ATTENTION
}

/** The issue behind a setup health row; null for rows that are advice, not a requirement. */
fun issueFor(id: HealthCheckId): AttentionIssue? = when (id) {
    HealthCheckId.NOTIFICATION_ACCESS -> AttentionIssue.NOTIFICATION_ACCESS
    HealthCheckId.DISPLAY_OVER_APPS -> AttentionIssue.OVERLAY
    HealthCheckId.USAGE_ACCESS -> AttentionIssue.USAGE_ACCESS
    HealthCheckId.POST_NOTIFICATIONS -> AttentionIssue.POST_NOTIFICATIONS
    HealthCheckId.BATTERY_OPTIMIZATION -> AttentionIssue.BATTERY
    HealthCheckId.GOOGLE_CONNECTION -> AttentionIssue.SIGN_IN
    HealthCheckId.OEM_BACKGROUND -> null
}

/** The issue behind a failed sync, from the kind recorded on the queue item. */
fun issueFor(kind: SyncErrorKind): AttentionIssue = when (kind) {
    SyncErrorKind.AUTH_EXPIRED -> AttentionIssue.TOKEN_EXPIRED
    SyncErrorKind.RATE_LIMITED -> AttentionIssue.RATE_LIMITED
    SyncErrorKind.SHEET_CHANGED -> AttentionIssue.WORKBOOK
    SyncErrorKind.NO_NETWORK, SyncErrorKind.SERVER_BUSY, SyncErrorKind.UNKNOWN,
    SyncErrorKind.PARTLY_SENT, SyncErrorKind.CARRY_CONFLICT -> AttentionIssue.SYNC_FAILING
}

/** Continue is offered only when every required issue is OK. */
fun canContinue(states: Collection<AttentionState>): Boolean = states.all { it == AttentionState.OK }
