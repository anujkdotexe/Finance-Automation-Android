package com.financeautomation.service

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.merge
import kotlinx.coroutines.flow.onEach

/**
 * Pure debouncer for ledger changes that coalesces rapid updates from transactions
 * and inflows flows before triggering a widget refresh.
 */
@OptIn(FlowPreview::class)
class LedgerChangeDebouncer(
    val debounceMs: Long = 1000L
) {
    /**
     * Combines transactions and inflows flows into a single debounced flow.
     */
    fun createDebouncedFlow(
        transactionsFlow: Flow<Any?>,
        inflowsFlow: Flow<Any?>
    ): Flow<Unit> {
        return merge(transactionsFlow, inflowsFlow)
            .debounce(debounceMs)
            .map { Unit }
    }

    /**
     * Binds to the flows and executes [onLedgerChanged] on [scope] after the debounce interval.
     */
    fun bind(
        scope: CoroutineScope,
        transactionsFlow: Flow<Any?>,
        inflowsFlow: Flow<Any?>,
        onLedgerChanged: suspend () -> Unit
    ): Job {
        return createDebouncedFlow(transactionsFlow, inflowsFlow)
            .onEach { onLedgerChanged() }
            .launchIn(scope)
    }
}
