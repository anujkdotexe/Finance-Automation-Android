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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.financeautomation.domain.model.Account
import com.financeautomation.domain.model.Companion

/**
 * Open debit row representation for the Friend Settlement bill picker.
 */
data class OpenDebtItem(
    val uuid: String,
    val description: String,
    val amountOwed: Double,
    val tabName: String,
    val person: String = ""
) {
    val displayLabel: String
        get() = if (person.isNotBlank()) {
            "$description (₹${amountOwed.toInt()} - $person) [$tabName]"
        } else {
            "$description (₹${amountOwed.toInt()}) [$tabName]"
        }
}

/**
 * Flow sheet for logging Income / Return / Friend Settlement (Phase 5.6 point 2).
 */
@Composable
fun IncomeReturnFlowSheet(
    accounts: List<Account>,
    companions: List<Companion>,
    openDebts: List<OpenDebtItem>,
    onPersonChanged: (String) -> Unit,
    onBack: () -> Unit,
    onDismiss: () -> Unit,
    onConfirm: (amount: Double, account: String, inflowType: String, settler: String, reason: String, explicitRefs: List<String>) -> Unit,
    onKeyboardFocusChanged: (Boolean) -> Unit,
    modifier: Modifier = Modifier
) {
    var amountText by remember { mutableStateOf("") }
    val defaultAccount = accounts.firstOrNull { it.isDefault }?.name ?: accounts.firstOrNull()?.name ?: "Cash"
    var selectedAccount by remember { mutableStateOf(defaultAccount) }

    val inflowTypes = listOf("Friend Settlement", "Salary/Stipend", "Allowance", "Other")
    var selectedInflowType by remember { mutableStateOf(inflowTypes[0]) }

    var settlerName by remember { mutableStateOf("") }
    var reasonText by remember { mutableStateOf("") }

    // Bill picker state for Friend Settlement
    var autoSettleAll by remember { mutableStateOf(true) }
    val selectedDebtIds = remember { mutableStateListOf<String>() }

    val scrollState = rememberScrollState()

    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 20.dp, vertical = 12.dp)
            .verticalScroll(scrollState),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        // Grabber Pill
        Box(
            modifier = Modifier
                .width(36.dp)
                .height(4.dp)
                .clip(CircleShape)
                .background(MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f))
        )

        Spacer(modifier = Modifier.height(14.dp))

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
                    .size(28.dp)
                    .clickable { onBack() }
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(
                        imageVector = Icons.AutoMirrored.Rounded.ArrowBack,
                        contentDescription = "Back",
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(16.dp)
                    )
                }
            }

            Text(
                text = "Income / Return",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onSurface
            )

            Surface(
                shape = CircleShape,
                color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f),
                modifier = Modifier
                    .size(28.dp)
                    .clickable { onDismiss() }
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(
                        imageVector = Icons.Rounded.Close,
                        contentDescription = "Dismiss",
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(16.dp)
                    )
                }
            }
        }

        Spacer(modifier = Modifier.height(14.dp))

        // Amount Input Field
        OutlinedTextField(
            value = amountText,
            onValueChange = { input ->
                if (input.isEmpty() || input.matches(Regex("""^\d*(\.\d{0,2})?$"""))) {
                    amountText = input
                }
            },
            label = { Text("Amount (Rs.)") },
            placeholder = { Text("0.00") },
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
            singleLine = true,
            modifier = Modifier
                .fillMaxWidth()
                .onFocusChanged { onKeyboardFocusChanged(it.isFocused) },
            shape = RoundedCornerShape(16.dp)
        )

        Spacer(modifier = Modifier.height(12.dp))

        // Inflow Type Filter Chips
        Text(
            text = "Inflow Type",
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.fillMaxWidth()
        )
        Spacer(modifier = Modifier.height(6.dp))
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            inflowTypes.forEach { type ->
                FilterChip(
                    selected = (selectedInflowType == type),
                    onClick = { selectedInflowType = type },
                    label = { Text(type) },
                    colors = FilterChipDefaults.filterChipColors(
                        selectedContainerColor = MaterialTheme.colorScheme.primaryContainer,
                        selectedLabelColor = MaterialTheme.colorScheme.onPrimaryContainer
                    )
                )
            }
        }

        Spacer(modifier = Modifier.height(12.dp))

        // Account Chips
        Text(
            text = "Receiving Account",
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.fillMaxWidth()
        )
        Spacer(modifier = Modifier.height(6.dp))
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            accounts.forEach { account ->
                FilterChip(
                    selected = (selectedAccount == account.name),
                    onClick = { selectedAccount = account.name },
                    label = { Text(account.name) },
                    colors = FilterChipDefaults.filterChipColors(
                        selectedContainerColor = MaterialTheme.colorScheme.primaryContainer,
                        selectedLabelColor = MaterialTheme.colorScheme.onPrimaryContainer
                    )
                )
            }
        }

        Spacer(modifier = Modifier.height(12.dp))

        // Who Sent It (Companion chips + text input)
        Text(
            text = if (selectedInflowType == "Friend Settlement") "Who Settled (Friend)" else "Sender / Source",
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.fillMaxWidth()
        )
        Spacer(modifier = Modifier.height(6.dp))
        if (companions.isNotEmpty()) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                companions.take(6).forEach { companion ->
                    FilterChip(
                        selected = (settlerName.equals(companion.name, ignoreCase = true)),
                        onClick = {
                            settlerName = companion.name
                            onPersonChanged(companion.name)
                        },
                        label = { Text(companion.name) }
                    )
                }
            }
            Spacer(modifier = Modifier.height(6.dp))
        }

        OutlinedTextField(
            value = settlerName,
            onValueChange = {
                settlerName = it
                onPersonChanged(it)
            },
            label = { Text("Name") },
            placeholder = { Text("e.g. Rohit, Rohit Kumar, Employer") },
            keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Words),
            singleLine = true,
            modifier = Modifier
                .fillMaxWidth()
                .onFocusChanged { onKeyboardFocusChanged(it.isFocused) },
            shape = RoundedCornerShape(16.dp)
        )

        Spacer(modifier = Modifier.height(12.dp))

        // Bill Picker for Friend Settlement (if open debts exist for this person)
        if (selectedInflowType == "Friend Settlement" && openDebts.isNotEmpty()) {
            OpenDebtPickerSection(
                openDebts = openDebts,
                autoSettleAll = autoSettleAll,
                selectedDebtIds = selectedDebtIds,
                onToggleAutoSettle = { checked ->
                    autoSettleAll = checked
                    if (checked) selectedDebtIds.clear()
                }
            )
        }

        // Reason Field
        OutlinedTextField(
            value = reasonText,
            onValueChange = { reasonText = it },
            label = { Text("Reason / Note") },
            placeholder = { Text("e.g. Dinner settlement, October salary") },
            keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences),
            singleLine = true,
            modifier = Modifier
                .fillMaxWidth()
                .onFocusChanged { onKeyboardFocusChanged(it.isFocused) },
            shape = RoundedCornerShape(16.dp)
        )

        Spacer(modifier = Modifier.height(18.dp))

        // Confirm & Save Button
        val parsedAmount = amountText.toDoubleOrNull() ?: 0.0
        val isValid = parsedAmount > 0.0 && (selectedInflowType != "Friend Settlement" || settlerName.isNotBlank())

        Button(
            onClick = {
                val refs = if (autoSettleAll) emptyList() else selectedDebtIds.toList()
                val effectiveReason = reasonText.ifBlank {
                    if (selectedInflowType == "Friend Settlement") "Settlement from $settlerName" else selectedInflowType
                }
                onConfirm(
                    parsedAmount,
                    selectedAccount,
                    selectedInflowType,
                    settlerName.trim(),
                    effectiveReason,
                    refs
                )
            },
            enabled = isValid,
            modifier = Modifier
                .fillMaxWidth()
                .height(52.dp),
            shape = RoundedCornerShape(16.dp)
        ) {
            Icon(
                imageVector = Icons.Rounded.Check,
                contentDescription = null,
                modifier = Modifier.size(18.dp)
            )
            Spacer(modifier = Modifier.width(8.dp))
            Text(
                text = "Record Income",
                style = MaterialTheme.typography.labelLarge,
                fontWeight = FontWeight.Bold
            )
        }

        Spacer(modifier = Modifier.height(10.dp))
    }
}

