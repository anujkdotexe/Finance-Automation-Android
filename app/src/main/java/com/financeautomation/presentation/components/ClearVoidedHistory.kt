package com.financeautomation.presentation.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import com.financeautomation.FinanceApplication
import com.financeautomation.R
import com.financeautomation.core.model.Result
import com.financeautomation.domain.model.VoidedHistory
import com.financeautomation.domain.repository.TransactionRepository
import com.financeautomation.presentation.theme.FinanceTheme
import kotlinx.coroutines.launch

/**
 * Pure state holder managing the "Clear voided history" state transitions without Compose dependencies.
 */
class ClearVoidedHistoryStateHolder(
    private val repository: TransactionRepository
) {
    var history: VoidedHistory? = null
        private set
    var result: VoidedHistory? = null
        private set
    var failed: Boolean = false
        private set

    suspend fun load(): VoidedHistory {
        val h = repository.previewVoidedHistory()
        history = h
        return h
    }

    suspend fun confirmClear(): Boolean {
        return when (val r = repository.clearVoidedHistory()) {
            is Result.Success -> {
                result = r.data
                failed = false
                true
            }
            else -> {
                failed = true
                false
            }
        }
    }
}

/**
 * Owns the whole "Clear voided history" flow: loads the counts when [show] turns true, asks first with the counts in
 * plain words, deletes on confirm, then reports what was deleted and what was skipped. The Google Sheet is never changed.
 */
@Composable
fun ClearVoidedHistoryHost(show: Boolean, onDismiss: () -> Unit) {
    if (!show) return
    val context = LocalContext.current
    val repository = remember { (context.applicationContext as FinanceApplication).appContainer.transactionRepository }
    val scope = rememberCoroutineScope()
    val stateHolder = remember(repository) { ClearVoidedHistoryStateHolder(repository) }
    var history by remember { mutableStateOf<VoidedHistory?>(null) }
    var result by remember { mutableStateOf<VoidedHistory?>(null) }
    var failed by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        history = stateHolder.load()
    }

    val done = result
    val counts = history
    when {
        done != null -> AlertDialog(
            onDismissRequest = onDismiss,
            title = { Text(stringResource(R.string.clear_voided_title), fontWeight = FontWeight.Bold) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(FinanceTheme.spacing.s)) {
                    Text(stringResource(R.string.clear_voided_done, done.transactions, done.inflows))
                    if (done.skippedPending > 0) Text(stringResource(R.string.clear_voided_skipped, done.skippedPending))
                }
            },
            confirmButton = {
                Button(onClick = onDismiss, modifier = Modifier.defaultMinSize(minHeight = FinanceTheme.sizes.minTouchTarget)) {
                    Text(stringResource(R.string.action_close))
                }
            }
        )
        counts != null -> AlertDialog(
            onDismissRequest = onDismiss,
            title = { Text(stringResource(R.string.clear_voided_title), fontWeight = FontWeight.Bold) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(FinanceTheme.spacing.s)) {
                    if (counts.deletable > 0) {
                        Text(stringResource(R.string.clear_voided_counts, counts.transactions, counts.inflows))
                        Text(stringResource(R.string.clear_voided_warning))
                    } else {
                        Text(stringResource(R.string.clear_voided_nothing))
                    }
                    if (counts.skippedPending > 0) Text(stringResource(R.string.clear_voided_skipped, counts.skippedPending))
                    if (failed) Text(stringResource(R.string.clear_voided_failed), color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = {
                OutlinedButton(onClick = onDismiss, modifier = Modifier.defaultMinSize(minHeight = FinanceTheme.sizes.minTouchTarget)) {
                    Text(stringResource(R.string.action_cancel))
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        scope.launch {
                            if (stateHolder.confirmClear()) {
                                result = stateHolder.result
                            } else {
                                failed = stateHolder.failed
                            }
                        }
                    },
                    enabled = counts.deletable > 0,
                    modifier = Modifier.defaultMinSize(minHeight = FinanceTheme.sizes.minTouchTarget)
                ) { Text(stringResource(R.string.clear_voided_action)) }
            }
        )
    }
}
