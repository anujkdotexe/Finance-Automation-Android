package com.financeautomation.presentation.flows

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import com.financeautomation.R
import com.financeautomation.domain.model.Companion
import com.financeautomation.presentation.theme.FinanceTheme

/**
 * Flow sheet for logging money borrowed from friends (Phase 5.6 point 3).
 * Records a payable obligation in Table 3 of the master ledger.
 */
@Composable
fun FriendPaidFlowSheet(
    companions: List<Companion>,
    onBack: () -> Unit,
    onDismiss: () -> Unit,
    onConfirm: (amount: Double, creditor: String, whatFor: String) -> Unit,
    onKeyboardFocusChanged: (Boolean) -> Unit,
    modifier: Modifier = Modifier
) {
    var amountText by remember { mutableStateOf("") }
    var creditorName by remember { mutableStateOf("") }
    var whatForText by remember { mutableStateOf("") }

    val scrollState = rememberScrollState()

    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = FinanceTheme.spacing.l, vertical = FinanceTheme.spacing.m)
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
                text = stringResource(R.string.action_menu_friend_paid_title),
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

        // Amount Input Field
        OutlinedTextField(
            value = amountText,
            onValueChange = { input ->
                if (input.isEmpty() || input.matches(Regex("""^\d*(\.\d{0,2})?$"""))) {
                    amountText = input
                }
            },
            label = { Text(stringResource(R.string.prompt_amount_i_owe)) },
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
            singleLine = true,
            modifier = Modifier
                .fillMaxWidth()
                .onFocusChanged { onKeyboardFocusChanged(it.isFocused) },
            shape = FinanceTheme.shapes.large
        )

        Spacer(modifier = Modifier.height(FinanceTheme.spacing.m))

        // Who Paid (Companion chips + text input)
        Text(
            text = stringResource(R.string.prompt_who_paid_for_you),
            style = FinanceTheme.typography.labelMedium,
            color = FinanceTheme.colors.onSurfaceVariant,
            modifier = Modifier.fillMaxWidth()
        )
        Spacer(modifier = Modifier.height(FinanceTheme.spacing.xs))
        if (companions.isNotEmpty()) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(FinanceTheme.spacing.s)
            ) {
                companions.take(6).forEach { companion ->
                    FilterChip(
                        selected = (creditorName.equals(companion.name, ignoreCase = true)),
                        onClick = { creditorName = companion.name },
                        label = { Text(companion.name) }
                    )
                }
            }
            Spacer(modifier = Modifier.height(FinanceTheme.spacing.xs))
        }

        OutlinedTextField(
            value = creditorName,
            onValueChange = { creditorName = it },
            label = { Text(stringResource(R.string.hint_friends_name)) },
            keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Words),
            singleLine = true,
            modifier = Modifier
                .fillMaxWidth()
                .onFocusChanged { onKeyboardFocusChanged(it.isFocused) },
            shape = FinanceTheme.shapes.large
        )

        Spacer(modifier = Modifier.height(FinanceTheme.spacing.m))

        // What For Field
        OutlinedTextField(
            value = whatForText,
            onValueChange = { whatForText = it },
            label = { Text(stringResource(R.string.prompt_what_was_this_for)) },
            keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences),
            singleLine = true,
            modifier = Modifier
                .fillMaxWidth()
                .onFocusChanged { onKeyboardFocusChanged(it.isFocused) },
            shape = FinanceTheme.shapes.large
        )

        Spacer(modifier = Modifier.height(FinanceTheme.spacing.l))

        // Confirm Button
        val parsedAmount = amountText.toDoubleOrNull() ?: 0.0
        val isValid = parsedAmount > 0.0 && creditorName.isNotBlank() && whatForText.isNotBlank()

        Button(
            onClick = {
                onConfirm(parsedAmount, creditorName.trim(), whatForText.trim())
            },
            enabled = isValid,
            modifier = Modifier
                .fillMaxWidth()
                .height(FinanceTheme.sizes.buttonHeightLarge),
            shape = FinanceTheme.shapes.large
        ) {
            Icon(
                imageVector = Icons.Rounded.Check,
                contentDescription = null,
                modifier = Modifier.size(FinanceTheme.sizes.icon18)
            )
            Spacer(modifier = Modifier.width(FinanceTheme.spacing.s))
            Text(
                text = stringResource(R.string.btn_record_debt),
                style = FinanceTheme.typography.labelLarge,
                fontWeight = FontWeight.Bold
            )
        }

        Spacer(modifier = Modifier.height(FinanceTheme.spacing.s))
    }
}
