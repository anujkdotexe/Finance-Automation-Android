package com.financeautomation.presentation.screens.drawer

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.ErrorOutline
import androidx.compose.material.icons.rounded.WarningAmber
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.financeautomation.R
import com.financeautomation.core.utils.PrivacyScrubber
import com.financeautomation.core.utils.ProblemReportManager
import com.financeautomation.core.utils.ProblemReportPayload
import com.financeautomation.presentation.theme.FinanceShapes
import com.financeautomation.presentation.theme.FinanceTheme
import kotlinx.coroutines.launch

/**
 * Dialog for submitting a user-initiated problem report to Firestore.
 * Complies with AGENTS.md section 2B item 3 and firebase/firestore.rules.
 *
 * Strict Privacy Guarantees:
 * - Shows exact technical payload in plaintext before submission.
 * - Requires explicit user tap on Send; never sends automatically or in background.
 * - Contains no financial amounts, payees, account digits, spreadsheet IDs, or message text.
 * - Offline or missing Firebase failure retains the typed text and displays a clear message.
 */
@Composable
fun ProblemReportDialog(
    onDismiss: () -> Unit,
    initialScreen: String = "Diagnostics",
    initialReasonCode: String = "user_report",
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()
    var userText by rememberSaveable { mutableStateOf("") }
    var isSubmitting by remember { mutableStateOf(false) }
    var errorMessage by remember { mutableStateOf<String?>(null) }
    var isSuccess by remember { mutableStateOf(false) }
    var submitJob by remember { mutableStateOf<kotlinx.coroutines.Job?>(null) }

    val currentPayload = remember(userText, initialScreen, initialReasonCode) {
        ProblemReportManager.buildPayload(
            context = context,
            screen = initialScreen,
            reasonCode = initialReasonCode,
            userText = userText
        )
    }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Surface(
            shape = FinanceShapes.large,
            color = MaterialTheme.colorScheme.surface,
            modifier = modifier
                .fillMaxWidth(0.95f)
                .padding(vertical = FinanceTheme.spacing.l)
        ) {
            Column(
                modifier = Modifier
                    .padding(FinanceTheme.spacing.l)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(FinanceTheme.spacing.m)
            ) {
                // Header
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = stringResource(R.string.problem_report_title),
                        style = FinanceTheme.typography.titleLarge,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    IconButton(
                        onClick = onDismiss,
                        enabled = !isSubmitting
                    ) {
                        Icon(
                            imageVector = Icons.Rounded.Close,
                            contentDescription = stringResource(R.string.cd_close)
                        )
                    }
                }

                if (isSuccess) {
                    // Success View
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = FinanceTheme.spacing.l),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(FinanceTheme.spacing.m)
                    ) {
                        Icon(
                            imageVector = Icons.Rounded.CheckCircle,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(48.dp)
                        )
                        Text(
                            text = stringResource(R.string.problem_report_success),
                            style = MaterialTheme.typography.bodyLarge,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                        Spacer(modifier = Modifier.height(FinanceTheme.spacing.m))
                        Button(
                            onClick = onDismiss,
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text(text = stringResource(R.string.problem_report_close))
                        }
                    }
                } else {
                    // Warning Banner
                    Surface(
                        color = MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.4f),
                        shape = FinanceShapes.small,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Row(
                            modifier = Modifier.padding(FinanceTheme.spacing.m),
                            verticalAlignment = Alignment.Top,
                            horizontalArrangement = Arrangement.spacedBy(FinanceTheme.spacing.s)
                        ) {
                            Icon(
                                imageVector = Icons.Rounded.WarningAmber,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.error,
                                modifier = Modifier.size(FinanceTheme.sizes.iconMedium)
                            )
                            Text(
                                text = stringResource(R.string.problem_report_warning),
                                style = FinanceTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onErrorContainer
                            )
                        }
                    }

                    // Editable User Description Field
                    Column(verticalArrangement = Arrangement.spacedBy(FinanceTheme.spacing.xs)) {
                        Text(
                            text = stringResource(R.string.problem_report_desc_label),
                            style = FinanceTheme.typography.labelMedium,
                            fontWeight = FontWeight.SemiBold,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                        OutlinedTextField(
                            value = userText,
                            onValueChange = { input ->
                                if (input.length <= 2000) {
                                    userText = input
                                    errorMessage = null
                                }
                            },
                            placeholder = {
                                Text(
                                    text = stringResource(R.string.problem_report_desc_placeholder),
                                    style = FinanceTheme.typography.bodyMedium
                                )
                            },
                            enabled = !isSubmitting,
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(120.dp),
                            maxLines = 5
                        )
                        Text(
                            text = "${userText.length} / 2000",
                            style = FinanceTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.align(Alignment.End)
                        )
                    }

                    // Error Banner (if submission failed)
                    if (errorMessage != null) {
                        Surface(
                            color = MaterialTheme.colorScheme.errorContainer,
                            shape = FinanceShapes.small,
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Row(
                                modifier = Modifier.padding(FinanceTheme.spacing.m),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(FinanceTheme.spacing.s)
                            ) {
                                Icon(
                                    imageVector = Icons.Rounded.ErrorOutline,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.error
                                )
                                Text(
                                    text = errorMessage.orEmpty(),
                                    style = FinanceTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onErrorContainer
                                )
                            }
                        }
                    }

                    // Payload Preview Section
                    Column(verticalArrangement = Arrangement.spacedBy(FinanceTheme.spacing.xs)) {
                        Text(
                            text = stringResource(R.string.problem_report_preview_title),
                            style = FinanceTheme.typography.labelMedium,
                            fontWeight = FontWeight.SemiBold,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                        Surface(
                            color = FinanceTheme.colors.surfaceContainerLow,
                            shape = FinanceShapes.small,
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(160.dp)
                                .border(1.dp, MaterialTheme.colorScheme.outlineVariant, FinanceShapes.small)
                        ) {
                            SelectionContainer {
                                Text(
                                    text = currentPayload.toPlainText(),
                                    style = FinanceTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier
                                        .padding(FinanceTheme.spacing.m)
                                        .verticalScroll(rememberScrollState())
                                )
                            }
                        }
                    }

                    // Action Buttons
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(FinanceTheme.spacing.m),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        OutlinedButton(
                            onClick = {
                                submitJob?.cancel()
                                onDismiss()
                            },
                            enabled = true,
                            modifier = Modifier
                                .weight(1f)
                                .defaultMinSize(minHeight = FinanceTheme.sizes.minTouchTarget)
                        ) {
                            Text(text = stringResource(R.string.action_cancel))
                        }

                        Button(
                            onClick = {
                                if (isSubmitting) return@Button
                                isSubmitting = true
                                errorMessage = null
                                submitJob = coroutineScope.launch {
                                    val result = ProblemReportManager.submitReport(context, currentPayload)
                                    isSubmitting = false
                                    result.fold(
                                        onSuccess = {
                                            isSuccess = true
                                        },
                                        onFailure = { error ->
                                            val msg = error.localizedMessage?.ifBlank { null }
                                                ?: error.message?.ifBlank { null }
                                                ?: error.javaClass.simpleName
                                            errorMessage = context.getString(
                                                R.string.problem_report_error_prefix
                                            ) + PrivacyScrubber.scrub(msg)
                                        }
                                    )
                                }
                            },
                            enabled = !isSubmitting,
                            modifier = Modifier
                                .weight(1f)
                                .defaultMinSize(minHeight = FinanceTheme.sizes.minTouchTarget)
                        ) {
                            if (isSubmitting) {
                                CircularProgressIndicator(
                                    modifier = Modifier.size(FinanceTheme.sizes.iconMedium),
                                    strokeWidth = 2.dp,
                                    color = MaterialTheme.colorScheme.onPrimary
                                )
                            } else {
                                Text(text = stringResource(R.string.problem_report_send_button))
                            }
                        }
                    }
                }
            }
        }
    }
}
