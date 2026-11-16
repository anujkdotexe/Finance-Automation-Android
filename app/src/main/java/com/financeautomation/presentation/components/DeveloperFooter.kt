package com.financeautomation.presentation.components

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import com.financeautomation.R
import com.financeautomation.presentation.theme.FinanceTheme

/**
 * Common footer component rendering open-source licensing, developer attribution,
 * and external developer contacts (email, GitHub, Portfolio, LinkedIn) with safe intent handling.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun DeveloperFooter(
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current

    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(vertical = FinanceTheme.spacing.m),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(FinanceTheme.spacing.s)
    ) {
        Text(
            text = stringResource(R.string.developer_credit),
            style = FinanceTheme.typography.bodyMedium,
            fontWeight = FontWeight.Medium,
            color = MaterialTheme.colorScheme.onSurface,
            textAlign = TextAlign.Center
        )

        Text(
            text = stringResource(R.string.developer_license),
            style = FinanceTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center
        )

        Spacer(modifier = Modifier.height(FinanceTheme.spacing.xs))

        FlowRow(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(FinanceTheme.spacing.s, Alignment.CenterHorizontally),
            verticalArrangement = Arrangement.spacedBy(FinanceTheme.spacing.xs)
        ) {
            val email = stringResource(R.string.developer_email)
            val githubUrl = stringResource(R.string.developer_github_url)
            val portfolioUrl = stringResource(R.string.developer_portfolio_url)
            val linkedinUrl = stringResource(R.string.developer_linkedin_url)

            AppChip(
                text = stringResource(R.string.developer_email_label),
                selected = false,
                onClick = { openEmail(context, email) }
            )

            AppChip(
                text = stringResource(R.string.developer_github_label),
                selected = false,
                onClick = { openBrowser(context, githubUrl) }
            )

            AppChip(
                text = stringResource(R.string.developer_portfolio_label),
                selected = false,
                onClick = { openBrowser(context, portfolioUrl) }
            )

            AppChip(
                text = stringResource(R.string.developer_linkedin_label),
                selected = false,
                onClick = { openBrowser(context, linkedinUrl) }
            )
        }
    }
}

internal fun openBrowser(context: Context, url: String) {
    com.financeautomation.presentation.screens.drawer.openExternalLink(context, url)
}

internal fun openEmail(context: Context, email: String) {
    launchEmailIntent(
        email = email,
        startActivity = { context.startActivity(it) },
        onError = { Toast.makeText(context, context.getString(R.string.err_open_email_failed), Toast.LENGTH_SHORT).show() }
    )
}

internal fun launchEmailIntent(
    email: String,
    startActivity: (Intent) -> Unit,
    onError: () -> Unit
) {
    try {
        val intent = Intent(Intent.ACTION_SENDTO).apply {
            data = Uri.parse("mailto:$email")
            putExtra(Intent.EXTRA_SUBJECT, "Finance Automation Feedback")
            flags = Intent.FLAG_ACTIVITY_NEW_TASK
        }
        startActivity(intent)
    } catch (_: Exception) {
        onError()
    }
}
