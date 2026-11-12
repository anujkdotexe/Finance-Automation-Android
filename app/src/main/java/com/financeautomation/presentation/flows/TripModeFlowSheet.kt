package com.financeautomation.presentation.flows

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Flight
import androidx.compose.material.icons.rounded.FlightTakeoff
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import com.financeautomation.R
import com.financeautomation.domain.model.TripTag
import com.financeautomation.presentation.theme.FinanceTheme

/**
 * Flow sheet for managing Trip Mode (Phase 5.6 point 5).
 * Enables starting a new active trip tag or ending the current one.
 */
@Composable
fun TripModeFlowSheet(
    activeTag: TripTag?,
    onBack: () -> Unit,
    onDismiss: () -> Unit,
    onStartTrip: (tagName: String) -> Unit,
    onEndTrip: () -> Unit,
    onKeyboardFocusChanged: (Boolean) -> Unit,
    modifier: Modifier = Modifier
) {
    var tagNameText by remember { mutableStateOf("") }
    val scrollState = rememberScrollState()
    val focusManager = LocalFocusManager.current

    LaunchedEffect(scrollState.isScrollInProgress) {
        if (scrollState.isScrollInProgress) {
            focusManager.clearFocus()
        }
    }

    Column(
        modifier = modifier
            .fillMaxWidth()
            .imePadding()
            .padding(horizontal = FinanceTheme.spacing.l, vertical = FinanceTheme.spacing.m)
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null
            ) { focusManager.clearFocus() }
            .verticalScroll(scrollState),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        // Grabber Pill
        Box(
            modifier = Modifier
                .width(FinanceTheme.sizes.grabberWidth)
                .height(FinanceTheme.sizes.grabberHeight)
                .clip(CircleShape)
                .background(FinanceTheme.colors.outline.copy(alpha = 0.4f))
        )

        Spacer(modifier = Modifier.height(FinanceTheme.spacing.m))

        // Header Row
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Surface(
                shape = CircleShape,
                color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f),
                modifier = Modifier
                    .size(FinanceTheme.sizes.icon28)
                    .clickable { onBack() }
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(
                        imageVector = Icons.AutoMirrored.Rounded.ArrowBack,
                        contentDescription = stringResource(R.string.cd_back),
                        tint = FinanceTheme.colors.onSurfaceVariant,
                        modifier = Modifier.size(FinanceTheme.sizes.iconSmall)
                    )
                }
            }

            Text(
                text = stringResource(R.string.action_menu_trip_mode_title),
                style = FinanceTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                color = FinanceTheme.colors.onSurface
            )

            Surface(
                shape = CircleShape,
                color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f),
                modifier = Modifier
                    .size(FinanceTheme.sizes.icon28)
                    .clickable { onDismiss() }
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(
                        imageVector = Icons.Rounded.Close,
                        contentDescription = stringResource(R.string.cd_close),
                        tint = FinanceTheme.colors.onSurfaceVariant,
                        modifier = Modifier.size(FinanceTheme.sizes.iconSmall)
                    )
                }
            }
        }

        Spacer(modifier = Modifier.height(FinanceTheme.spacing.m))

        if (activeTag != null) {
            // Active Trip Display
            Surface(
                shape = FinanceTheme.shapes.dialog,
                color = FinanceTheme.colors.primaryContainer.copy(alpha = 0.4f),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(
                    modifier = Modifier.padding(FinanceTheme.spacing.l),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Icon(
                        imageVector = Icons.Rounded.Flight,
                        contentDescription = null,
                        tint = FinanceTheme.colors.primary,
                        modifier = Modifier.size(FinanceTheme.sizes.buttonHeightMedium)
                    )
                    Spacer(modifier = Modifier.height(FinanceTheme.spacing.s))
                    Text(
                        text = stringResource(R.string.prompt_active_trip_tag),
                        style = FinanceTheme.typography.labelLarge,
                        color = FinanceTheme.colors.onSurfaceVariant
                    )
                    Text(
                        text = activeTag.name,
                        style = FinanceTheme.typography.headlineSmall,
                        fontWeight = FontWeight.Bold,
                        color = FinanceTheme.colors.onSurface
                    )
                    Spacer(modifier = Modifier.height(FinanceTheme.spacing.xs))
                    Text(
                        text = stringResource(R.string.trip_active_desc),
                        style = FinanceTheme.typography.bodySmall,
                        color = FinanceTheme.colors.onSurfaceVariant
                    )
                }
            }

            Spacer(modifier = Modifier.height(FinanceTheme.spacing.xl))

            // Action Buttons for Active Trip
            Button(
                onClick = onEndTrip,
                colors = ButtonDefaults.buttonColors(
                    containerColor = FinanceTheme.colors.error,
                    contentColor = MaterialTheme.colorScheme.onError
                ),
                modifier = Modifier
                    .fillMaxWidth()
                    .height(FinanceTheme.sizes.buttonHeightLarge),
                shape = FinanceTheme.shapes.large
            ) {
                Text(
                    text = stringResource(R.string.btn_end_trip),
                    style = FinanceTheme.typography.labelLarge,
                    fontWeight = FontWeight.Bold
                )
            }

            Spacer(modifier = Modifier.height(FinanceTheme.spacing.s))

            OutlinedButton(
                onClick = onDismiss,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(FinanceTheme.sizes.buttonHeightLarge),
                shape = FinanceTheme.shapes.large
            ) {
                Text(
                    text = stringResource(R.string.action_cancel),
                    style = FinanceTheme.typography.labelLarge,
                    fontWeight = FontWeight.Medium
                )
            }
        } else {
            // No Active Trip - Start Trip Prompt
            Surface(
                shape = FinanceTheme.shapes.dialog,
                color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(
                    modifier = Modifier.padding(FinanceTheme.spacing.l),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Icon(
                        imageVector = Icons.Rounded.FlightTakeoff,
                        contentDescription = null,
                        tint = FinanceTheme.colors.primary,
                        modifier = Modifier.size(FinanceTheme.sizes.buttonHeightMedium)
                    )
                    Spacer(modifier = Modifier.height(FinanceTheme.spacing.s))
                    Text(
                        text = stringResource(R.string.btn_start_trip),
                        style = FinanceTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        color = FinanceTheme.colors.onSurface
                    )
                    Spacer(modifier = Modifier.height(FinanceTheme.spacing.xs))
                    Text(
                        text = stringResource(R.string.trip_active_desc),
                        style = FinanceTheme.typography.bodySmall,
                        color = FinanceTheme.colors.onSurfaceVariant
                    )
                }
            }

            Spacer(modifier = Modifier.height(FinanceTheme.spacing.l))

            OutlinedTextField(
                value = tagNameText,
                onValueChange = { tagNameText = it },
                label = { Text(stringResource(R.string.prompt_enter_trip_tag)) },
                keyboardOptions = KeyboardOptions(
                    capitalization = KeyboardCapitalization.Words,
                    imeAction = ImeAction.Done
                ),
                keyboardActions = KeyboardActions(
                    onDone = { focusManager.clearFocus() }
                ),
                singleLine = true,
                modifier = Modifier
                    .fillMaxWidth()
                    .onFocusChanged { onKeyboardFocusChanged(it.isFocused) },
                shape = FinanceTheme.shapes.large
            )

            Spacer(modifier = Modifier.height(FinanceTheme.spacing.m))

            Button(
                onClick = { onStartTrip(tagNameText.trim()) },
                enabled = tagNameText.isNotBlank(),
                modifier = Modifier
                    .fillMaxWidth()
                    .height(FinanceTheme.sizes.buttonHeightLarge),
                shape = FinanceTheme.shapes.large
            ) {
                Text(
                    text = stringResource(R.string.btn_start_trip),
                    style = FinanceTheme.typography.labelLarge,
                    fontWeight = FontWeight.Bold
                )
            }
        }

        Spacer(modifier = Modifier.height(FinanceTheme.spacing.s))
    }
}
