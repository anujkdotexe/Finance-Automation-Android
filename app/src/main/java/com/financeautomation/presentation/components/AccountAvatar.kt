package com.financeautomation.presentation.components

import android.content.res.Configuration
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.financeautomation.core.utils.accountInitial
import com.financeautomation.presentation.theme.FinanceAutomationTheme
import com.financeautomation.presentation.theme.FinanceTheme

/**
 * 36dp circular avatar displaying the first letter of the Google account.
 * Maintains minimum 48dp interactive touch target.
 */
@Composable
fun AccountAvatar(
    email: String?,
    modifier: Modifier = Modifier,
    onClick: (() -> Unit)? = null
) {
    val initial = accountInitial(email)

    Box(
        modifier = modifier
            .defaultMinSize(minWidth = FinanceTheme.sizes.minTouchTarget, minHeight = FinanceTheme.sizes.minTouchTarget)
            .then(
                if (onClick != null) {
                    Modifier.clickable(role = Role.Button, onClick = onClick)
                } else Modifier
            ),
        contentAlignment = Alignment.Center
    ) {
        Box(
            modifier = Modifier
                .size(FinanceTheme.sizes.avatarSmall)
                .clip(CircleShape)
                .background(MaterialTheme.colorScheme.secondaryContainer),
            contentAlignment = Alignment.Center
        ) {
            Text(
                text = initial,
                style = FinanceTheme.typography.titleSmall.copy(fontWeight = FontWeight.Bold),
                color = MaterialTheme.colorScheme.onSecondaryContainer
            )
        }
    }
}

@Preview(name = "Light", showBackground = true)
@Composable
private fun AccountAvatarPreviewLight() {
    FinanceAutomationTheme(darkTheme = false) {
        AccountAvatar(email = "user@example.com", onClick = {})
    }
}

@Preview(name = "Dark", uiMode = Configuration.UI_MODE_NIGHT_YES, showBackground = true)
@Composable
private fun AccountAvatarPreviewDark() {
    FinanceAutomationTheme(darkTheme = true) {
        AccountAvatar(email = "alex@gmail.com", onClick = {})
    }
}

@Preview(name = "Font Scale 1.3x", fontScale = 1.3f, showBackground = true)
@Composable
private fun AccountAvatarPreviewScale() {
    FinanceAutomationTheme(darkTheme = false) {
        AccountAvatar(email = "anuj@example.com", onClick = {})
    }
}

