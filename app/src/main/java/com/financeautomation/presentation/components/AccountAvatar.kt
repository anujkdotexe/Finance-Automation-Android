package com.financeautomation.presentation.components

import android.content.res.Configuration
import android.graphics.BitmapFactory
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.financeautomation.R
import com.financeautomation.domain.model.ThemeMode
import com.financeautomation.core.utils.accountInitial
import com.financeautomation.data.remote.ProfileAvatarManager
import com.financeautomation.presentation.theme.FinanceAutomationTheme
import com.financeautomation.presentation.theme.FinanceTheme
import java.io.File

/**
 * 36dp circular avatar displaying the Google profile picture when available,
 * or fallback to the first letter of the Google account.
 * Maintains minimum 48dp interactive touch target.
 */
@Composable
fun AccountAvatar(
    email: String?,
    modifier: Modifier = Modifier,
    onClick: (() -> Unit)? = null
) {
    val context = LocalContext.current
    val initial = accountInitial(email)

    val avatarBitmap = remember(email) {
        val file = File(context.filesDir, ProfileAvatarManager.AVATAR_FILE_NAME)
        if (file.exists() && file.length() > 0L) {
            try {
                BitmapFactory.decodeFile(file.absolutePath)?.asImageBitmap()
            } catch (_: Exception) {
                null
            }
        } else {
            null
        }
    }

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
        if (avatarBitmap != null) {
            Image(
                bitmap = avatarBitmap,
                contentDescription = stringResource(R.string.cd_profile_avatar),
                contentScale = ContentScale.Crop,
                modifier = Modifier
                    .size(FinanceTheme.sizes.avatarSmall)
                    .clip(CircleShape)
            )
        } else {
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
}

@Preview(name = "AccountAvatar - Light", showBackground = true)
@Composable
private fun AccountAvatarPreviewLight() {
    FinanceAutomationTheme(themeMode = ThemeMode.LIGHT) {
        AccountAvatar(email = "user@example.com", onClick = {})
    }
}

@Preview(name = "AccountAvatar - Dark", uiMode = Configuration.UI_MODE_NIGHT_YES, showBackground = true)
@Composable
private fun AccountAvatarPreviewDark() {
    FinanceAutomationTheme(themeMode = ThemeMode.DARK) {
        AccountAvatar(email = "alex@gmail.com", onClick = {})
    }
}

@Preview(name = "AccountAvatar - Font Scale 1.3", fontScale = 1.3f, showBackground = true)
@Composable
private fun AccountAvatarPreviewScale() {
    FinanceAutomationTheme(themeMode = ThemeMode.LIGHT) {
        AccountAvatar(email = "anuj@example.com", onClick = {})
    }
}
