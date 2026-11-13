package com.financeautomation.presentation.screens.settings

import android.content.Intent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.window.Dialog
import com.financeautomation.R
import com.financeautomation.data.local.FinanceDatabase
import com.financeautomation.data.repository.CompatibilityReportCollector
import com.financeautomation.presentation.components.PrimaryButton
import com.financeautomation.presentation.components.SecondaryButton
import com.financeautomation.presentation.screens.SetupHealthIntents
import com.financeautomation.presentation.theme.FinanceTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Preview dialog for system compatibility and missed-payment telemetry reports (Phase 9.4).
 */
@Composable
fun CompatibilityReportDialog(
    isMissedPayment: Boolean = false,
    onDismiss: () -> Unit
) {
    val context = LocalContext.current
    var userNote by remember { mutableStateOf("") }
    var includeGoogleErrors by remember { mutableStateOf(false) }
    var reportText by remember { mutableStateOf<String?>(null) }
    var isLoading by remember { mutableStateOf(true) }

    val title = if (isMissedPayment) {
        stringResource(R.string.report_missed_payment_title)
    } else {
        stringResource(R.string.report_title)
    }

    LaunchedEffect(userNote, isMissedPayment, includeGoogleErrors) {
        isLoading = true
        withContext(Dispatchers.IO) {
            val dao = try {
                FinanceDatabase.getInstance(context).pipelineEventDao()
            } catch (e: Exception) {
                null
            }
            reportText = CompatibilityReportCollector.collectReport(
                context = context,
                pipelineEventDao = dao,
                candidateFeedback = SetupHealthIntents.getOemFeedback(),
                isMissedPaymentReport = isMissedPayment,
                userNote = userNote.ifBlank { null },
                includeGoogleErrors = includeGoogleErrors
            )
            isLoading = false
        }
    }

    Dialog(onDismissRequest = onDismiss) {
        Card(
            shape = FinanceTheme.shapes.dialog,
            colors = CardDefaults.cardColors(
                containerColor = FinanceTheme.colors.surface
            ),
            modifier = Modifier
                .fillMaxWidth()
                .padding(FinanceTheme.spacing.m)
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(FinanceTheme.spacing.l),
                verticalArrangement = Arrangement.spacedBy(FinanceTheme.spacing.m)
            ) {
                Text(
                    text = title,
                    style = FinanceTheme.typography.titleMedium,
                    color = FinanceTheme.colors.onSurface
                )

                Column(verticalArrangement = Arrangement.spacedBy(FinanceTheme.spacing.xs)) {
                    Text(
                        text = stringResource(R.string.report_user_note_label),
                        style = FinanceTheme.typography.labelMedium,
                        color = FinanceTheme.colors.onSurfaceVariant
                    )
                    OutlinedTextField(
                        value = userNote,
                        onValueChange = { userNote = it },
                        placeholder = { Text(stringResource(R.string.report_user_note_placeholder), style = FinanceTheme.typography.bodyMedium) },
                        shape = FinanceTheme.shapes.card,
                        modifier = Modifier.fillMaxWidth()
                    )
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = stringResource(R.string.report_include_google_errors),
                        style = FinanceTheme.typography.bodyMedium,
                        color = FinanceTheme.colors.onSurface
                    )
                    Switch(
                        checked = includeGoogleErrors,
                        onCheckedChange = { includeGoogleErrors = it }
                    )
                }

                // Scrollable preview box
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = FinanceTheme.sizes.previewMaxHeight)
                        .background(
                            color = FinanceTheme.colors.surfaceVariant,
                            shape = FinanceTheme.shapes.small
                        )
                        .padding(FinanceTheme.spacing.m)
                        .verticalScroll(rememberScrollState()),
                    contentAlignment = if (isLoading && reportText == null) Alignment.Center else Alignment.TopStart
                ) {
                    if (isLoading && reportText == null) {
                        CircularProgressIndicator(modifier = Modifier.padding(FinanceTheme.spacing.m))
                    } else {
                        Text(
                            text = reportText.orEmpty(),
                            style = FinanceTheme.typography.bodySmall,
                            color = FinanceTheme.colors.onSurfaceVariant
                        )
                    }
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(FinanceTheme.spacing.m)
                ) {
                    SecondaryButton(
                        text = stringResource(R.string.action_close),
                        onClick = onDismiss,
                        modifier = Modifier.weight(1f)
                    )
                    PrimaryButton(
                        text = stringResource(R.string.report_action_share),
                        onClick = {
                            val shareIntent = Intent(Intent.ACTION_SEND).apply {
                                type = "text/plain"
                                putExtra(Intent.EXTRA_SUBJECT, title)
                                putExtra(Intent.EXTRA_TEXT, reportText.orEmpty())
                            }
                            context.startActivity(
                                Intent.createChooser(shareIntent, title)
                            )
                        },
                        modifier = Modifier.weight(1f)
                    )
                }
            }
        }
    }
}
