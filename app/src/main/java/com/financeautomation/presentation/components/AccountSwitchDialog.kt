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
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import com.financeautomation.R
import com.financeautomation.data.account.AccountSwitchPrompt
import com.financeautomation.presentation.theme.FinanceTheme

/** Asks before another Google account replaces the data on this phone. Cancel stays signed out. */
@Composable
fun AccountSwitchDialog(prompt: AccountSwitchPrompt, onConfirm: () -> Unit, onCancel: () -> Unit) {
    AlertDialog(
        onDismissRequest = onCancel,
        title = { Text(stringResource(R.string.account_switch_title), fontWeight = FontWeight.Bold) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(FinanceTheme.spacing.s)) {
                Text(
                    stringResource(
                        if (prompt.warnsAboutRemovedData) R.string.account_switch_message else R.string.account_switch_message_plain
                    )
                )
                if (prompt.pendingCount > 0) {
                    Text(
                        text = stringResource(R.string.account_switch_pending, prompt.pendingCount),
                        color = MaterialTheme.colorScheme.error
                    )
                }
            }
        },
        dismissButton = {
            OutlinedButton(onClick = onCancel, modifier = Modifier.defaultMinSize(minHeight = FinanceTheme.sizes.minTouchTarget)) {
                Text(
                    text = stringResource(R.string.action_cancel),
                    maxLines = 2,
                    textAlign = androidx.compose.ui.text.style.TextAlign.Center
                )
            }
        },
        confirmButton = {
            Button(onClick = onConfirm, modifier = Modifier.defaultMinSize(minHeight = FinanceTheme.sizes.minTouchTarget)) {
                Text(
                    text = stringResource(R.string.account_switch_confirm),
                    maxLines = 2,
                    textAlign = androidx.compose.ui.text.style.TextAlign.Center
                )
            }
        }
    )
}
