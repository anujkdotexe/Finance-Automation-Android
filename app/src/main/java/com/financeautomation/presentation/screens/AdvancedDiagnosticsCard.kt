package com.financeautomation.presentation.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.BugReport
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import com.financeautomation.R
import com.financeautomation.core.utils.Diagnostics
import com.financeautomation.presentation.theme.FinanceTheme
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Settings card displaying the in-memory redacted diagnostics log (Phase 7.4).
 */
@Composable
fun AdvancedDiagnosticsCard(
    modifier: Modifier = Modifier
) {
    var records by remember { mutableStateOf(Diagnostics.getRecords()) }
    val dateFormat = remember { SimpleDateFormat("HH:mm:ss", Locale.getDefault()) }

    SettingsCard(
        title = stringResource(R.string.health_advanced_title),
        subtitle = stringResource(R.string.health_advanced_subtitle),
        icon = Icons.Rounded.BugReport,
        modifier = modifier
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = stringResource(R.string.diagnostics_events_count, records.size),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Row {
                OutlinedButton(
                    onClick = {
                        records = Diagnostics.getRecords()
                    },
                    shape = FinanceTheme.shapes.small,
                    modifier = Modifier.height(FinanceTheme.sizes.buttonHeightSmall)
                ) {
                    Text(stringResource(R.string.action_refresh), style = MaterialTheme.typography.labelSmall)
                }
                Spacer(modifier = Modifier.padding(horizontal = FinanceTheme.spacing.xs))
                OutlinedButton(
                    onClick = {
                        Diagnostics.clear()
                        records = emptyList()
                    },
                    shape = FinanceTheme.shapes.small,
                    modifier = Modifier.height(FinanceTheme.sizes.buttonHeightSmall)
                ) {
                    Text(stringResource(R.string.action_clear), style = MaterialTheme.typography.labelSmall)
                }
            }
        }

        Spacer(modifier = Modifier.height(FinanceTheme.spacing.s))

        if (records.isEmpty()) {
            Text(
                text = stringResource(R.string.diagnostics_empty),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(vertical = FinanceTheme.spacing.s)
            )
        } else {
            Surface(
                color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
                shape = FinanceTheme.shapes.card,
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = FinanceTheme.sizes.diagnosticsHeight)
            ) {
                LazyColumn(modifier = Modifier.padding(FinanceTheme.spacing.s)) {
                    items(records.reversed()) { record ->
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = FinanceTheme.spacing.xs)
                        ) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(
                                    text = dateFormat.format(Date(record.timestamp)),
                                    style = MaterialTheme.typography.labelSmall,
                                    fontFamily = FontFamily.Monospace,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                                Spacer(modifier = Modifier.padding(horizontal = FinanceTheme.spacing.xs))
                                Text(
                                    text = record.tag,
                                    style = MaterialTheme.typography.labelSmall,
                                    fontWeight = FontWeight.Bold,
                                    color = MaterialTheme.colorScheme.primary
                                )
                            }
                            Text(
                                text = record.reason,
                                style = MaterialTheme.typography.bodySmall,
                                fontFamily = FontFamily.Monospace,
                                color = MaterialTheme.colorScheme.onSurface
                            )
                        }
                    }
                }
            }
        }
    }
}
