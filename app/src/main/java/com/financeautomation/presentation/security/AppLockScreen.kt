package com.financeautomation.presentation.security

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.foundation.Image
import com.financeautomation.R
import com.financeautomation.presentation.components.FilledButton
import com.financeautomation.presentation.theme.FinanceTheme

/**
 * The only screen composed while the app is locked: the logo and an Unlock button. No transaction, balance or friend
 * data is read or shown here. [onUnlock] starts the system authentication prompt; it is also started once when the
 * screen first appears.
 */
@Composable
fun AppLockScreen(
    onUnlock: () -> Unit,
    modifier: Modifier = Modifier,
    promptOnShow: Boolean = true
) {
    LaunchedEffect(Unit) {
        if (promptOnShow) onUnlock()
    }
    Box(
        modifier = modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .padding(FinanceTheme.spacing.xl),
        contentAlignment = Alignment.Center
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(FinanceTheme.spacing.l)
        ) {
            Box(
                modifier = Modifier
                    .size(FinanceTheme.sizes.avatarLarge)
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.primary),
                contentAlignment = Alignment.Center
            ) {
                Image(
                    painter = painterResource(R.drawable.ic_launcher_foreground),
                    contentDescription = null,
                    modifier = Modifier.size(FinanceTheme.sizes.avatarLarge)
                )
            }
            Text(
                text = stringResource(R.string.app_lock_title),
                style = FinanceTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurface,
                textAlign = TextAlign.Center
            )
            Text(
                text = stringResource(R.string.app_lock_message),
                style = FinanceTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center
            )
            FilledButton(
                text = stringResource(R.string.app_lock_unlock),
                onClick = onUnlock,
                modifier = Modifier.fillMaxWidth()
            )
        }
    }
}
