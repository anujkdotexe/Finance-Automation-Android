package com.financeautomation.presentation.screens.drawer

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowRight
import androidx.compose.material.icons.rounded.BugReport
import androidx.compose.material.icons.rounded.CloudSync
import androidx.compose.material.icons.rounded.Code
import androidx.compose.material.icons.rounded.CurrencyRupee
import androidx.compose.material.icons.rounded.FilterAlt
import androidx.compose.material.icons.rounded.Policy
import androidx.compose.material.icons.rounded.VerifiedUser
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.core.app.NotificationManagerCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import com.financeautomation.BuildConfig
import com.financeautomation.R
import com.financeautomation.presentation.components.LinkButton
import com.financeautomation.presentation.theme.FinanceShapes
import com.financeautomation.presentation.theme.FinanceTheme
import com.financeautomation.presentation.viewmodel.SettingsUiState
import com.financeautomation.presentation.viewmodel.SettingsViewModel

@Composable
fun AboutScreen(
    viewModel: SettingsViewModel,
    state: SettingsUiState,
    onNavigateToPrivacy: () -> Unit = {},
    onNavigateToLicences: () -> Unit = {},
    onReportProblem: (() -> Unit)? = null,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val scrollState = rememberScrollState()
    var showProblemReportDialog by remember { mutableStateOf(false) }

    // Notification access decides whether capture can run; checked again whenever the screen resumes.
    val lifecycleOwner = LocalLifecycleOwner.current
    var captureOn by remember {
        mutableStateOf(NotificationManagerCompat.getEnabledListenerPackages(context).contains(context.packageName))
    }
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                captureOn = NotificationManagerCompat.getEnabledListenerPackages(context).contains(context.packageName)
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(scrollState)
            .padding(horizontal = FinanceTheme.spacing.l, vertical = FinanceTheme.spacing.m),
        verticalArrangement = Arrangement.spacedBy(FinanceTheme.spacing.l)
    ) {
        // App Identity Mark
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = FinanceTheme.spacing.m),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Box(
                modifier = Modifier
                    .size(FinanceTheme.sizes.buttonHeight)
                    .clip(FinanceShapes.card)
                    .background(MaterialTheme.colorScheme.primary),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = Icons.Rounded.CurrencyRupee,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onPrimary,
                    modifier = Modifier.size(FinanceTheme.sizes.icon28)
                )
            }

            Spacer(modifier = Modifier.height(FinanceTheme.spacing.m))

            Text(
                text = stringResource(R.string.about_app_name),
                style = FinanceTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onSurface
            )

            Text(
                text = stringResource(R.string.about_version_label, BuildConfig.VERSION_NAME, BuildConfig.VERSION_CODE),
                style = FinanceTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier
                    .clip(FinanceShapes.small)
                    .clickable { viewModel.onVersionTapped() }
                    .padding(FinanceTheme.spacing.xs)
            )

            Spacer(modifier = Modifier.height(FinanceTheme.spacing.xs))

            Surface(
                color = if (captureOn) MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.errorContainer,
                shape = FinanceShapes.pill
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = FinanceTheme.spacing.m, vertical = FinanceTheme.spacing.xs),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(FinanceTheme.spacing.xs)
                ) {
                    Box(
                        modifier = Modifier
                            .size(FinanceTheme.spacing.compact)
                            .clip(CircleShape)
                            .background(if (captureOn) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error)
                    )
                    Text(
                        text = stringResource(if (captureOn) R.string.about_engine_active else R.string.about_capture_off),
                        style = FinanceTheme.typography.labelSmall,
                        fontWeight = FontWeight.Medium,
                        color = if (captureOn) MaterialTheme.colorScheme.onSecondaryContainer else MaterialTheme.colorScheme.onErrorContainer
                    )
                }
            }
        }

        // Trust & Privacy Card
        Surface(
            color = FinanceTheme.colors.surfaceContainerLow,
            shape = FinanceShapes.large,
            shadowElevation = FinanceTheme.sizes.shadowElevationSmall,
            modifier = Modifier.fillMaxWidth()
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(FinanceTheme.spacing.m),
                verticalAlignment = Alignment.Top,
                horizontalArrangement = Arrangement.spacedBy(FinanceTheme.spacing.m)
            ) {
                Box(
                    modifier = Modifier
                        .size(FinanceTheme.sizes.avatarSmall)
                        .clip(CircleShape)
                        .background(FinanceTheme.colors.surfaceContainer),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = Icons.Rounded.VerifiedUser,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(FinanceTheme.sizes.iconMedium)
                    )
                }
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = stringResource(R.string.about_privacy_title),
                        style = FinanceTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.primary
                    )
                    Text(
                        text = stringResource(R.string.about_privacy_desc),
                        style = FinanceTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                }
            }
        }

        // Integrity Metric Chips
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(FinanceTheme.spacing.m)
        ) {
            // Metric 1: SMS filter
            Surface(
                color = FinanceTheme.colors.surfaceContainerLowest,
                shape = FinanceShapes.large,
                shadowElevation = FinanceTheme.sizes.shadowElevationSmall,
                modifier = Modifier.weight(1f)
            ) {
                Column(
                    modifier = Modifier.padding(FinanceTheme.spacing.m),
                    verticalArrangement = Arrangement.spacedBy(FinanceTheme.spacing.xs)
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = stringResource(R.string.about_metric_sms_filter),
                            style = FinanceTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Icon(
                            imageVector = Icons.Rounded.FilterAlt,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(FinanceTheme.sizes.iconSmall)
                        )
                    }
                    Text(
                        text = stringResource(R.string.about_metric_sms_val),
                        style = FinanceTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    Text(
                        text = stringResource(R.string.about_metric_sms_sub),
                        style = FinanceTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            // Metric 2: Storage
            Surface(
                color = FinanceTheme.colors.surfaceContainerLowest,
                shape = FinanceShapes.large,
                shadowElevation = FinanceTheme.sizes.shadowElevationSmall,
                modifier = Modifier.weight(1f)
            ) {
                Column(
                    modifier = Modifier.padding(FinanceTheme.spacing.m),
                    verticalArrangement = Arrangement.spacedBy(FinanceTheme.spacing.xs)
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = stringResource(R.string.about_metric_storage),
                            style = FinanceTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Icon(
                            imageVector = Icons.Rounded.CloudSync,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(FinanceTheme.sizes.iconSmall)
                        )
                    }
                    Text(
                        text = stringResource(R.string.about_metric_storage_val),
                        style = FinanceTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    Text(
                        text = stringResource(R.string.about_metric_storage_sub),
                        style = FinanceTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }

        // Navigational Links Card rendered from shared getAboutNavigationItems()
        Surface(
            color = FinanceTheme.colors.surfaceContainerLowest,
            shape = FinanceShapes.large,
            shadowElevation = FinanceTheme.sizes.shadowElevationSmall,
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(modifier = Modifier.fillMaxWidth()) {
                val items = getAboutNavigationItems().filter { !it.requiresDeveloperMode || state.developerMode }
                val reportProblemAction = onReportProblem ?: { showProblemReportDialog = true }

                items.forEachIndexed { index, item ->
                    if (index > 0) {
                        HorizontalDivider(
                            color = FinanceTheme.colors.surfaceContainerLow,
                            modifier = Modifier.padding(horizontal = FinanceTheme.spacing.m)
                        )
                    }
                    val onClick = resolveAboutItemAction(
                        itemId = item.id,
                        onNavigateToPrivacy = onNavigateToPrivacy,
                        onNavigateToLicences = onNavigateToLicences,
                        onReportProblem = reportProblemAction
                    )
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .defaultMinSize(minHeight = FinanceTheme.sizes.minTouchTarget)
                            .clip(FinanceShapes.medium)
                            .clickable(onClick = onClick)
                            .padding(horizontal = FinanceTheme.spacing.m, vertical = FinanceTheme.spacing.s),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(FinanceTheme.spacing.m)
                        ) {
                            Box(
                                modifier = Modifier
                                    .size(FinanceTheme.sizes.icon32)
                                    .clip(CircleShape)
                                    .background(FinanceTheme.colors.surfaceContainerLow),
                                contentAlignment = Alignment.Center
                            ) {
                                Icon(
                                    imageVector = item.icon,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.primary,
                                    modifier = Modifier.size(FinanceTheme.sizes.icon18)
                                )
                            }
                            Text(
                                text = stringResource(item.titleRes),
                                style = FinanceTheme.typography.titleMedium,
                                fontWeight = FontWeight.Medium,
                                color = MaterialTheme.colorScheme.onSurface
                            )
                        }
                        Icon(
                            imageVector = Icons.AutoMirrored.Rounded.KeyboardArrowRight,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.size(FinanceTheme.sizes.iconMedium)
                        )
                    }
                }
            }
        }

        // Footer note
        Text(
            text = stringResource(R.string.about_footer),
            style = FinanceTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = FinanceTheme.spacing.m)
        )

        // Developer attribution, MIT license, and verified contacts
        com.financeautomation.presentation.components.DeveloperFooter()
    }

    if (showProblemReportDialog) {
        ProblemReportDialog(
            onDismiss = { showProblemReportDialog = false },
            initialScreen = "About"
        )
    }
}

data class AboutNavigationItem(
    val id: String,
    val titleRes: Int,
    val icon: androidx.compose.ui.graphics.vector.ImageVector,
    val requiresDeveloperMode: Boolean = false
)

fun getAboutNavigationItems(): List<AboutNavigationItem> = listOf(
    AboutNavigationItem("privacy", R.string.about_link_privacy, Icons.Rounded.Policy, requiresDeveloperMode = false),
    AboutNavigationItem("licences", R.string.about_link_licences, Icons.Rounded.Code, requiresDeveloperMode = false),
    AboutNavigationItem("report_problem", R.string.diag_report_problem, Icons.Rounded.BugReport, requiresDeveloperMode = false)
)

fun resolveAboutItemAction(
    itemId: String,
    onNavigateToPrivacy: () -> Unit,
    onNavigateToLicences: () -> Unit,
    onReportProblem: () -> Unit
): () -> Unit = when (itemId) {
    "privacy" -> onNavigateToPrivacy
    "licences" -> onNavigateToLicences
    "report_problem" -> onReportProblem
    else -> ({})
}
