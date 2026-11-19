package com.financeautomation.presentation.screens.drawer

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.automirrored.rounded.OpenInNew
import androidx.compose.material.icons.rounded.BugReport
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material.icons.rounded.Notifications
import androidx.compose.material.icons.rounded.Security
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import com.financeautomation.R
import com.financeautomation.presentation.theme.FinanceShapes
import com.financeautomation.presentation.theme.FinanceTheme

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PrivacyPolicyScreen(
    onNavigateBack: () -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val scrollState = rememberScrollState()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(text = stringResource(R.string.privacy_title)) },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Rounded.ArrowBack,
                            contentDescription = stringResource(R.string.cd_back)
                        )
                    }
                }
            )
        },
        modifier = modifier
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .verticalScroll(scrollState)
                .padding(horizontal = FinanceTheme.spacing.l, vertical = FinanceTheme.spacing.m),
            verticalArrangement = Arrangement.spacedBy(FinanceTheme.spacing.l)
        ) {
            PrivacySectionCard(
                icon = Icons.Rounded.Security,
                title = stringResource(R.string.privacy_section_data_title),
                description = stringResource(R.string.privacy_section_data_desc)
            )

            PrivacySectionCard(
                icon = Icons.Rounded.Notifications,
                title = stringResource(R.string.privacy_section_notifications_title),
                description = stringResource(R.string.privacy_section_notifications_desc)
            )

            PrivacySectionCard(
                icon = Icons.Rounded.Lock,
                title = stringResource(R.string.privacy_section_permissions_title),
                description = stringResource(R.string.privacy_section_permissions_desc)
            )

            PrivacySectionCard(
                icon = Icons.Rounded.Security,
                title = stringResource(R.string.privacy_section_diagnostics_title),
                description = stringResource(R.string.privacy_section_diagnostics_desc)
            )

            PrivacySectionCard(
                icon = Icons.Rounded.BugReport,
                title = stringResource(R.string.privacy_section_reports_title),
                description = stringResource(R.string.privacy_section_reports_desc)
            )

            OutlinedButton(
                onClick = { openExternalLink(context, "https://financeautomation.app/privacy") },
                modifier = Modifier
                    .fillMaxWidth()
                    .defaultMinSize(minHeight = FinanceTheme.sizes.minTouchTarget)
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(FinanceTheme.spacing.s)
                ) {
                    Text(
                        text = stringResource(R.string.privacy_open_online),
                        maxLines = 2,
                        textAlign = androidx.compose.ui.text.style.TextAlign.Center
                    )
                    Icon(
                        imageVector = Icons.AutoMirrored.Rounded.OpenInNew,
                        contentDescription = null
                    )
                }
            }

            com.financeautomation.presentation.components.DeveloperFooter()

            Spacer(modifier = Modifier.height(FinanceTheme.spacing.l))
        }
    }
}

@Composable
private fun PrivacySectionCard(
    icon: ImageVector,
    title: String,
    description: String,
    modifier: Modifier = Modifier
) {
    Surface(
        color = FinanceTheme.colors.surfaceContainerLow,
        shape = FinanceShapes.large,
        shadowElevation = FinanceTheme.sizes.shadowElevationSmall,
        modifier = modifier.fillMaxWidth()
    ) {
        Column(
            modifier = Modifier.padding(FinanceTheme.spacing.l),
            verticalArrangement = Arrangement.spacedBy(FinanceTheme.spacing.s)
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(FinanceTheme.spacing.s)
            ) {
                Icon(
                    imageVector = icon,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary
                )
                Text(
                    text = title,
                    style = FinanceTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurface
                )
            }
            Text(
                text = description,
                style = FinanceTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

internal fun openExternalLink(context: Context, url: String) {
    launchExternalLink(
        url = url,
        startActivity = { context.startActivity(it) },
        onError = { Toast.makeText(context, context.getString(R.string.err_open_link_failed), Toast.LENGTH_SHORT).show() }
    )
}

internal fun launchExternalLink(
    url: String,
    startActivity: (Intent) -> Unit,
    onError: () -> Unit
) {
    try {
        val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url)).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        startActivity(intent)
    } catch (_: Exception) {
        onError()
    }
}
