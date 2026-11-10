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
import com.financeautomation.core.utils.Diagnostics
import com.financeautomation.domain.model.Companion
import com.financeautomation.presentation.theme.FinanceTheme
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.time.format.DateTimeParseException

/**
 * Flow sheet for recording debt repayments to friends (Phase 5.6 point 4).
 * Reconciles open payables in Table 3 via RepaymentWaterfallUseCase.
 */
@Composable
fun RepaidFriendFlowSheet(
    companions: List<Companion>,
    onBack: () -> Unit,
    onDismiss: () -> Unit,
    onConfirm: (amount: Double, person: String, timestamp: Long) -> Unit,
    onKeyboardFocusChanged: (Boolean) -> Unit,
    modifier: Modifier = Modifier
) {
    var amountText by remember { mutableStateOf("") }
    var personName by remember { mutableStateOf("") }
    val todayFormatted = remember { LocalDate.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd")) }
    var dateText by remember { mutableStateOf(todayFormatted) }

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
                text = stringResource(R.string.action_menu_repaid_friend_title),
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

        // Who Did You Repay (Companion chips + text input)
        Text(
            text = stringResource(R.string.prompt_who_did_you_repay),
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
                        selected = (personName.equals(companion.name, ignoreCase = true)),
                        onClick = { personName = companion.name },
                        label = { Text(companion.name) }
                    )
                }
            }
            Spacer(modifier = Modifier.height(FinanceTheme.spacing.xs))
        }

        OutlinedTextField(
            value = personName,
            onValueChange = { personName = it },
            label = { Text(stringResource(R.string.hint_friends_name)) },
            keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Words),
            singleLine = true,
            modifier = Modifier
                .fillMaxWidth()
                .onFocusChanged { onKeyboardFocusChanged(it.isFocused) },
            shape = FinanceTheme.shapes.large
        )

        Spacer(modifier = Modifier.height(FinanceTheme.spacing.m))

        // Amount Input Field (Mandatory)
        OutlinedTextField(
            value = amountText,
            onValueChange = { input ->
                if (input.isEmpty() || input.matches(Regex("""^\d*(\.\d{0,2})?$"""))) {
                    amountText = input
                }
            },
            label = { Text(stringResource(R.string.prompt_enter_amount)) },
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
            singleLine = true,
            modifier = Modifier
                .fillMaxWidth()
                .onFocusChanged { onKeyboardFocusChanged(it.isFocused) },
            shape = FinanceTheme.shapes.large
        )

        Spacer(modifier = Modifier.height(FinanceTheme.spacing.m))

        // Date Field (Defaults to today)
        OutlinedTextField(
            value = dateText,
            onValueChange = { dateText = it },
            label = { Text(stringResource(R.string.prompt_date_repaid)) },
            singleLine = true,
            modifier = Modifier
                .fillMaxWidth()
                .onFocusChanged { onKeyboardFocusChanged(it.isFocused) },
            shape = FinanceTheme.shapes.large
        )

        Spacer(modifier = Modifier.height(FinanceTheme.spacing.l))

        // Confirm Button
        val parsedAmount = amountText.toDoubleOrNull() ?: 0.0
        val isValid = parsedAmount > 0.0 && personName.isNotBlank()

        Button(
            onClick = {
                val timestamp = try {
                    LocalDate.parse(dateText).atStartOfDay(java.time.ZoneId.systemDefault()).toInstant().toEpochMilli()
                } catch (e: DateTimeParseException) {
                    Diagnostics.record("RepaidFriendFlowSheet", "parseDate: ${e.javaClass.simpleName}")
                    System.currentTimeMillis()
                }
                onConfirm(parsedAmount, personName.trim(), timestamp)
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
                text = stringResource(R.string.btn_record_repayment),
                style = FinanceTheme.typography.labelLarge,
                fontWeight = FontWeight.Bold
            )
        }

        Spacer(modifier = Modifier.height(FinanceTheme.spacing.s))
    }
}
