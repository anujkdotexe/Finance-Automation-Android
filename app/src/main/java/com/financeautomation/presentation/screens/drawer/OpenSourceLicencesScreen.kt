package com.financeautomation.presentation.screens.drawer

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Code
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import com.financeautomation.R
import com.financeautomation.presentation.theme.FinanceShapes
import com.financeautomation.presentation.theme.FinanceTheme

private data class OpenSourceLibrary(
    val name: String,
    val author: String,
    val licence: String,
    val url: String
)

private val LIBRARIES = listOf(
    OpenSourceLibrary("AndroidX Jetpack & Compose", "Google LLC", "Apache License 2.0", "https://android.googlesource.com/platform/frameworks/support"),
    OpenSourceLibrary("Kotlin & Coroutines", "JetBrains s.r.o.", "Apache License 2.0", "https://github.com/JetBrains/kotlin"),
    OpenSourceLibrary("Room Persistence Library", "Google LLC", "Apache License 2.0", "https://developer.android.com/jetpack/androidx/releases/room"),
    OpenSourceLibrary("SQLCipher for Android", "Zetetic LLC", "BSD License", "https://www.zetetic.net/sqlcipher/"),
    OpenSourceLibrary("OkHttp", "Square, Inc.", "Apache License 2.0", "https://square.github.io/okhttp/"),
    OpenSourceLibrary("Material Components for Android", "Google LLC", "Apache License 2.0", "https://github.com/material-components/material-components-android"),
    OpenSourceLibrary("Google Play Services Auth", "Google LLC", "Android Software Development Kit License", "https://developers.google.com/android/guides/overview")
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun OpenSourceLicencesScreen(
    onNavigateBack: () -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val scrollState = rememberScrollState()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(text = stringResource(R.string.licences_title)) },
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
            Text(
                text = stringResource(R.string.licences_intro),
                style = FinanceTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            Surface(
                color = FinanceTheme.colors.surfaceContainerLow,
                shape = FinanceShapes.large,
                shadowElevation = FinanceTheme.sizes.shadowElevationSmall,
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(modifier = Modifier.fillMaxWidth()) {
                    LIBRARIES.forEachIndexed { index, lib ->
                        LibraryItem(
                            library = lib,
                            onClick = { openExternalLink(context, lib.url) }
                        )
                        if (index < LIBRARIES.lastIndex) {
                            HorizontalDivider(
                                color = FinanceTheme.colors.surfaceContainerLowest,
                                modifier = Modifier.padding(horizontal = FinanceTheme.spacing.m)
                            )
                        }
                    }
                }
            }

            com.financeautomation.presentation.components.DeveloperFooter()
        }
    }
}

@Composable
private fun LibraryItem(
    library: OpenSourceLibrary,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(FinanceTheme.spacing.m),
        verticalArrangement = Arrangement.spacedBy(FinanceTheme.spacing.xs)
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(FinanceTheme.spacing.s)
        ) {
            Icon(
                imageVector = Icons.Rounded.Code,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary
            )
            Text(
                text = library.name,
                style = FinanceTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurface
            )
        }
        Text(
            text = "${library.author} • ${library.licence}",
            style = FinanceTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}
