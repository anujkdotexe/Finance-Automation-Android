package com.financeautomation.presentation.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import com.financeautomation.FinanceApplication
import com.financeautomation.R
import com.financeautomation.data.local.FinanceDatabase
import com.financeautomation.data.local.StorageResetNotice
import com.financeautomation.presentation.theme.FinanceAutomationTheme
import com.financeautomation.presentation.theme.FinanceTheme
import kotlinx.coroutines.CancellationException
import java.io.File

/**
 * Shows [content] only when the encrypted database is open. While it cannot be opened the user sees a blocking
 * "Storage unavailable, try again" screen, so no save is ever reported while nothing can be stored.
 * Also tells the user once if local data had to be reset.
 */
@Composable
fun StorageGate(app: FinanceApplication, content: @Composable () -> Unit) {
    var attempt by remember { mutableIntStateOf(0) }
    // null = still checking, true = open, false = unavailable
    var available by remember { mutableStateOf<Boolean?>(null) }
    var showResetNotice by remember { mutableStateOf(false) }

    LaunchedEffect(attempt) {
        available = null
        available = try {
            app.appContainer.awaitDatabase()
            true
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            false
        }
        if (available == true) {
            showResetNotice = StorageResetNotice(File(app.filesDir, FinanceDatabase.RESET_NOTICE_FILE)).consume()
        }
    }

    when (available) {
        true -> {
            content()
            if (showResetNotice) {
                FinanceAutomationTheme {
                    AlertDialog(
                        onDismissRequest = { showResetNotice = false },
                        title = { Text(stringResource(R.string.storage_reset_title)) },
                        text = { Text(stringResource(R.string.storage_reset_body)) },
                        confirmButton = {
                            TextButton(onClick = { showResetNotice = false }) {
                                Text(stringResource(R.string.storage_reset_ok))
                            }
                        }
                    )
                }
            }
        }
        false -> FinanceAutomationTheme {
            Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                StorageUnavailableContent(onRetry = { attempt++ })
            }
        }
        null -> FinanceAutomationTheme {
            Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                Box(Modifier.fillMaxSize())
            }
        }
    }
}

@Composable
fun StorageUnavailableContent(onRetry: () -> Unit) {
    Box(modifier = Modifier.fillMaxSize().padding(FinanceTheme.spacing.l), contentAlignment = Alignment.Center) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(FinanceTheme.spacing.m)
        ) {
            Text(
                text = stringResource(R.string.storage_unavailable_title),
                style = MaterialTheme.typography.titleLarge,
                textAlign = TextAlign.Center
            )
            Text(
                text = stringResource(R.string.storage_unavailable_body),
                style = MaterialTheme.typography.bodyMedium,
                textAlign = TextAlign.Center
            )
            Button(onClick = onRetry) { Text(stringResource(R.string.storage_unavailable_retry)) }
        }
    }
}
