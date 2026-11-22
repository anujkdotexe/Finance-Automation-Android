package com.financeautomation.presentation.screens.drawer

import com.financeautomation.core.constants.CategoryConstants

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.CalendarToday
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Movie
import androidx.compose.material.icons.rounded.NotificationsActive
import androidx.compose.material.icons.rounded.Subscriptions
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import com.financeautomation.R
import com.financeautomation.core.utils.formatRupeesAuto
import com.financeautomation.domain.model.Subscription
import com.financeautomation.domain.model.SubscriptionFrequency
import com.financeautomation.presentation.components.AmountEntry
import com.financeautomation.presentation.components.AmountField
import com.financeautomation.presentation.components.ButtonPair
import com.financeautomation.presentation.components.NotesField
import com.financeautomation.presentation.theme.FinanceShapes
import com.financeautomation.presentation.theme.FinanceTheme

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun AddSubscriptionSheet(
    accounts: List<String>,
    onDismiss: () -> Unit,
    onSave: (name: String, amount: Double?, frequency: SubscriptionFrequency, account: String, remind: Boolean, note: String, anchorEpochDay: Long?) -> Unit,
    modifier: Modifier = Modifier,
    /** Opens the sheet filled in from a detected repeating payment. */
    prefill: com.financeautomation.domain.usecase.RecurringSuggestion? = null,
    /** Opens the sheet for editing an existing subscription. */
    existing: Subscription? = null
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    var name by rememberSaveable { mutableStateOf(existing?.name ?: prefill?.name.orEmpty()) }
    var amountText by rememberSaveable {
        mutableStateOf(
            existing?.amount?.let { com.financeautomation.core.utils.formatAmountInput(it) }
                ?: prefill?.let { com.financeautomation.core.utils.formatAmountInput(it.amount) }
                ?: ""
        )
    }
    val amountEntry = AmountEntry(amountText)
    var isFixedAmount by rememberSaveable { mutableStateOf(existing?.let { it.amount != null } ?: true) }
    var selectedFrequency by rememberSaveable { mutableStateOf(existing?.frequency ?: prefill?.frequency ?: SubscriptionFrequency.MONTHLY) }
    val defaultAccount = existing?.account?.takeIf { it.isNotBlank() }
        ?: prefill?.account?.takeIf { it in accounts }
        ?: accounts.firstOrNull()
        ?: CategoryConstants.ACCOUNT_OTHER
    var selectedAccount by rememberSaveable { mutableStateOf(defaultAccount) }
    var remindMe by rememberSaveable { mutableStateOf(existing?.remind ?: true) }
    var noteText by rememberSaveable { mutableStateOf(existing?.note.orEmpty()) }

    val todayEpochDay = remember { java.time.LocalDate.now(java.time.ZoneId.of("Asia/Kolkata")).toEpochDay() }
    var anchorDateEpochDay by rememberSaveable {
        mutableStateOf<Long?>(existing?.anchorEpochDay ?: prefill?.anchorEpochDay ?: todayEpochDay)
    }
    var showDatePicker by remember { mutableStateOf(false) }

    val nextDueEpochDay = remember(anchorDateEpochDay, selectedFrequency) {
        anchorDateEpochDay?.let { anchor ->
            val anchorDate = java.time.LocalDate.ofEpochDay(anchor)
            when (selectedFrequency) {
                SubscriptionFrequency.WEEKLY -> {
                    var d = anchorDate
                    while (d.toEpochDay() < todayEpochDay) {
                        d = d.plusWeeks(1)
                    }
                    d.toEpochDay()
                }
                SubscriptionFrequency.MONTHLY -> {
                    var d = anchorDate
                    while (d.toEpochDay() < todayEpochDay) {
                        d = d.plusMonths(1)
                    }
                    d.toEpochDay()
                }
                SubscriptionFrequency.YEARLY -> {
                    var d = anchorDate
                    while (d.toEpochDay() < todayEpochDay) {
                        d = d.plusYears(1)
                    }
                    d.toEpochDay()
                }
            }
        }
    }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = FinanceTheme.colors.surfaceContainerLowest,
        shape = FinanceShapes.sheet,
        modifier = modifier
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = FinanceTheme.spacing.l)
                .padding(bottom = FinanceTheme.spacing.l)
                .imePadding()
        ) {
            // Header
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = if (existing != null) stringResource(R.string.sub_edit_title) else stringResource(R.string.sub_add_title),
                    style = FinanceTheme.typography.titleLarge,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurface
                )
                IconButton(onClick = onDismiss) {
                    Icon(
                        imageVector = Icons.Rounded.Close,
                        contentDescription = stringResource(R.string.action_cancel),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            Spacer(modifier = Modifier.height(FinanceTheme.spacing.m))

            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f, fill = false)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(FinanceTheme.spacing.l)
            ) {
                // 1. NAME
                Column(verticalArrangement = Arrangement.spacedBy(FinanceTheme.spacing.xs)) {
                    Text(
                        text = stringResource(R.string.sub_add_name_label),
                        style = FinanceTheme.typography.labelSmall,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    OutlinedTextField(
                        value = name,
                        onValueChange = {
                            name = com.financeautomation.core.utils.InputPolicy.cleanName(it)
                                .take(com.financeautomation.core.utils.InputPolicy.MAX_NAME_LENGTH)
                        },
                        placeholder = { Text(stringResource(R.string.sub_add_name_placeholder)) },
                        trailingIcon = {
                            Icon(
                                imageVector = Icons.Rounded.Subscriptions,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.size(FinanceTheme.sizes.iconMedium)
                            )
                        },
                        singleLine = true,
                        shape = FinanceShapes.medium,
                        modifier = Modifier.fillMaxWidth()
                    )
                }

                // 2. AMOUNT
                Surface(
                    color = FinanceTheme.colors.surfaceContainerLowest,
                    shape = FinanceShapes.large,
                    shadowElevation = FinanceTheme.sizes.shadowElevationSmall,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(FinanceTheme.spacing.m),
                        verticalArrangement = Arrangement.spacedBy(FinanceTheme.spacing.s)
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = stringResource(R.string.sub_add_amount_label),
                                style = FinanceTheme.typography.labelSmall,
                                fontWeight = FontWeight.SemiBold,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            Text(
                                text = stringResource(R.string.currency_inr),
                                style = FinanceTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.secondary
                            )
                        }

                        if (isFixedAmount) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(FinanceTheme.spacing.xs)
                            ) {
                                Text(
                                    text = "₹",
                                    style = FinanceTheme.typography.amountHero,
                                    color = MaterialTheme.colorScheme.primary
                                )
                                AmountField(
                                    value = amountText,
                                    onValueChange = { amountText = it },
                                    modifier = Modifier.weight(1f)
                                )
                            }
                        }

                        // Segmented toggle
                        Surface(
                            color = FinanceTheme.colors.surfaceContainer,
                            shape = FinanceShapes.small,
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(FinanceTheme.spacing.xs),
                                horizontalArrangement = Arrangement.spacedBy(FinanceTheme.spacing.xs)
                            ) {
                                Surface(
                                    color = if (isFixedAmount) FinanceTheme.colors.surfaceContainerLowest else FinanceTheme.colors.surfaceContainer,
                                    shape = FinanceShapes.small,
                                    shadowElevation = if (isFixedAmount) FinanceTheme.sizes.shadowElevationSmall else FinanceTheme.spacing.none,
                                    modifier = Modifier
                                        .weight(1f)
                                        .defaultMinSize(minHeight = FinanceTheme.sizes.buttonHeightSmall)
                                        .clip(FinanceShapes.small)
                                        .clickable { isFixedAmount = true }
                                ) {
                                    Box(contentAlignment = Alignment.Center, modifier = Modifier.padding(FinanceTheme.spacing.xs)) {
                                        Text(
                                            text = stringResource(R.string.sub_add_fixed),
                                            style = FinanceTheme.typography.labelMedium,
                                            fontWeight = if (isFixedAmount) FontWeight.SemiBold else FontWeight.Medium,
                                            color = if (isFixedAmount) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                    }
                                }
                                Surface(
                                    color = if (!isFixedAmount) FinanceTheme.colors.surfaceContainerLowest else FinanceTheme.colors.surfaceContainer,
                                    shape = FinanceShapes.small,
                                    shadowElevation = if (!isFixedAmount) FinanceTheme.sizes.shadowElevationSmall else FinanceTheme.spacing.none,
                                    modifier = Modifier
                                        .weight(1f)
                                        .defaultMinSize(minHeight = FinanceTheme.sizes.buttonHeightSmall)
                                        .clip(FinanceShapes.small)
                                        .clickable { isFixedAmount = false }
                                ) {
                                    Box(contentAlignment = Alignment.Center, modifier = Modifier.padding(FinanceTheme.spacing.xs)) {
                                        Text(
                                            text = stringResource(R.string.sub_add_variable),
                                            style = FinanceTheme.typography.labelMedium,
                                            fontWeight = if (!isFixedAmount) FontWeight.SemiBold else FontWeight.Medium,
                                            color = if (!isFixedAmount) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                    }
                                }
                            }
                        }
                    }
                }

                // 3. FREQUENCY
                Column(verticalArrangement = Arrangement.spacedBy(FinanceTheme.spacing.xs)) {
                    Text(
                        text = stringResource(R.string.sub_add_freq_label),
                        style = FinanceTheme.typography.labelSmall,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    FlowRow(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(FinanceTheme.spacing.s),
                        verticalArrangement = Arrangement.spacedBy(FinanceTheme.spacing.xs)
                    ) {
                        listOf(
                            SubscriptionFrequency.WEEKLY to stringResource(R.string.sub_add_freq_weekly),
                            SubscriptionFrequency.MONTHLY to stringResource(R.string.sub_add_freq_monthly),
                            SubscriptionFrequency.YEARLY to stringResource(R.string.sub_add_freq_yearly)
                        ).forEach { (freq, label) ->
                            val isSelected = selectedFrequency == freq
                            FilterChip(
                                selected = isSelected,
                                onClick = { selectedFrequency = freq },
                                label = { Text(label) },
                                leadingIcon = if (isSelected) {
                                    {
                                        Icon(
                                            imageVector = Icons.Rounded.Check,
                                            contentDescription = null,
                                            modifier = Modifier.size(FinanceTheme.sizes.iconSmall)
                                        )
                                    }
                                } else null,
                                colors = FilterChipDefaults.filterChipColors(
                                    selectedContainerColor = MaterialTheme.colorScheme.primaryContainer,
                                    selectedLabelColor = MaterialTheme.colorScheme.onPrimaryContainer
                                )
                            )
                        }
                    }
                }

                // 4. NEXT DUE DATE
                Column(verticalArrangement = Arrangement.spacedBy(FinanceTheme.spacing.xs)) {
                    Text(
                        text = stringResource(R.string.sub_add_due_date_label),
                        style = FinanceTheme.typography.labelSmall,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Surface(
                        color = FinanceTheme.colors.surfaceContainerLowest,
                        shape = FinanceShapes.large,
                        shadowElevation = FinanceTheme.sizes.shadowElevationSmall,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .defaultMinSize(minHeight = FinanceTheme.sizes.minTouchTarget)
                                .clickable { showDatePicker = true }
                                .padding(horizontal = FinanceTheme.spacing.m, vertical = FinanceTheme.spacing.s),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(FinanceTheme.spacing.s)
                            ) {
                                Icon(
                                    imageVector = Icons.Rounded.CalendarToday,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.primary,
                                    modifier = Modifier.size(FinanceTheme.sizes.iconMedium)
                                )
                                if (nextDueEpochDay != null) {
                                    val formattedDate = remember(nextDueEpochDay) {
                                        java.time.LocalDate.ofEpochDay(nextDueEpochDay)
                                            .format(java.time.format.DateTimeFormatter.ofPattern("d MMM uuuu", java.util.Locale.US))
                                    }
                                    Text(
                                        text = formattedDate,
                                        style = FinanceTheme.typography.bodyMedium,
                                        fontWeight = FontWeight.Medium,
                                        color = MaterialTheme.colorScheme.onSurface
                                    )
                                } else {
                                    Text(
                                        text = stringResource(R.string.sub_add_pick_start_date),
                                        style = FinanceTheme.typography.bodyMedium,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                            }
                            if (nextDueEpochDay != null) {
                                val daysUntil = nextDueEpochDay - todayEpochDay
                                Surface(
                                    color = MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.6f),
                                    shape = FinanceShapes.small
                                ) {
                                    val pillText = when {
                                        daysUntil == 0L -> stringResource(R.string.sub_add_due_today)
                                        daysUntil > 0L -> stringResource(R.string.sub_add_due_in_days, daysUntil.toInt())
                                        else -> ""
                                    }
                                    if (pillText.isNotEmpty()) {
                                        Text(
                                            text = pillText,
                                            style = FinanceTheme.typography.labelSmall,
                                            color = MaterialTheme.colorScheme.secondary,
                                            modifier = Modifier.padding(horizontal = FinanceTheme.spacing.s, vertical = FinanceTheme.spacing.xxs)
                                        )
                                    }
                                }
                            }
                        }
                    }
                }

                // 5. PAID FROM ACCOUNT
                Column(verticalArrangement = Arrangement.spacedBy(FinanceTheme.spacing.xs)) {
                    Text(
                        text = stringResource(R.string.sub_add_paid_from_label),
                        style = FinanceTheme.typography.labelSmall,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    FlowRow(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(FinanceTheme.spacing.s),
                        verticalArrangement = Arrangement.spacedBy(FinanceTheme.spacing.xs)
                    ) {
                        val baseAccounts = accounts.ifEmpty { listOf(CategoryConstants.ACCOUNT_OTHER) }
                        // Keep the saved account as a chip when it is no longer in the list, so Save does not change it.
                        val availableAccounts = existing?.account
                            ?.takeIf { it.isNotBlank() && it !in baseAccounts }
                            ?.let { baseAccounts + it }
                            ?: baseAccounts
                        availableAccounts.forEach { acc ->
                            val isSelected = selectedAccount == acc
                            FilterChip(
                                selected = isSelected,
                                onClick = { selectedAccount = acc },
                                label = { Text(acc) },
                                leadingIcon = if (isSelected) {
                                    {
                                        Icon(
                                            imageVector = Icons.Rounded.Check,
                                            contentDescription = null,
                                            modifier = Modifier.size(FinanceTheme.sizes.iconSmall)
                                        )
                                    }
                                } else null,
                                colors = FilterChipDefaults.filterChipColors(
                                    selectedContainerColor = MaterialTheme.colorScheme.secondaryContainer,
                                    selectedLabelColor = MaterialTheme.colorScheme.onSecondaryContainer
                                )
                            )
                        }
                    }
                }

                // 6. CATEGORY
                Column(verticalArrangement = Arrangement.spacedBy(FinanceTheme.spacing.xs)) {
                    Text(
                        text = stringResource(R.string.sub_add_category_label),
                        style = FinanceTheme.typography.labelSmall,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Surface(
                        color = FinanceTheme.colors.surfaceContainerLowest,
                        shape = FinanceShapes.large,
                        shadowElevation = FinanceTheme.sizes.shadowElevationSmall,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .defaultMinSize(minHeight = FinanceTheme.sizes.minTouchTarget)
                                .padding(horizontal = FinanceTheme.spacing.m, vertical = FinanceTheme.spacing.s),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(FinanceTheme.spacing.m)
                        ) {
                            Box(
                                modifier = Modifier
                                    .size(FinanceTheme.sizes.icon32)
                                    .clip(CircleShape)
                                    .background(MaterialTheme.colorScheme.secondaryContainer),
                                contentAlignment = Alignment.Center
                            ) {
                                Icon(
                                    imageVector = Icons.Rounded.Movie,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.onSecondaryContainer,
                                    modifier = Modifier.size(FinanceTheme.sizes.icon18)
                                )
                            }
                            Text(
                                text = stringResource(R.string.nav_subscriptions_title),
                                style = FinanceTheme.typography.bodyMedium,
                                fontWeight = FontWeight.Medium,
                                color = MaterialTheme.colorScheme.onSurface
                            )
                        }
                    }
                }

                // 7. REMIND ME
                Surface(
                    color = FinanceTheme.colors.surfaceContainerLowest,
                    shape = FinanceShapes.large,
                    shadowElevation = FinanceTheme.sizes.shadowElevationSmall,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .defaultMinSize(minHeight = FinanceTheme.sizes.minTouchTarget)
                            .padding(horizontal = FinanceTheme.spacing.m, vertical = FinanceTheme.spacing.s),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Row(
                            modifier = Modifier.weight(1f),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(FinanceTheme.spacing.m)
                        ) {
                            Box(
                                modifier = Modifier
                                    .size(FinanceTheme.sizes.icon32)
                                    .clip(CircleShape)
                                    .background(FinanceTheme.colors.surfaceContainerHigh),
                                contentAlignment = Alignment.Center
                            ) {
                                Icon(
                                    imageVector = Icons.Rounded.NotificationsActive,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.primary,
                                    modifier = Modifier.size(FinanceTheme.sizes.icon18)
                                )
                            }
                            Text(
                                text = stringResource(R.string.sub_add_remind_label),
                                style = FinanceTheme.typography.titleMedium,
                                color = MaterialTheme.colorScheme.onSurface
                            )
                        }

                        Switch(
                            checked = remindMe,
                            onCheckedChange = { remindMe = it },
                            colors = SwitchDefaults.colors(
                                checkedThumbColor = MaterialTheme.colorScheme.onPrimary,
                                checkedTrackColor = MaterialTheme.colorScheme.primary
                            )
                        )
                    }
                }

                // 8. NOTES
                NotesField(
                    value = noteText,
                    onValueChange = { noteText = it },
                    modifier = Modifier.fillMaxWidth()
                )

            }

            Spacer(modifier = Modifier.height(FinanceTheme.spacing.m))

            // Action Button Pair (pinned below the scrolling form, above the keyboard via imePadding)
            ButtonPair(
                outlinedText = stringResource(R.string.action_cancel),
                onOutlinedClick = onDismiss,
                filledText = stringResource(R.string.action_save),
                onFilledClick = {
                    if (name.isNotBlank()) {
                        val amt = if (isFixedAmount) amountEntry.amountOrZero.takeIf { amountEntry.canSave } else null
                        onSave(name.trim(), amt, selectedFrequency, selectedAccount, remindMe, noteText, anchorDateEpochDay)
                    }
                },
                filledEnabled = name.isNotBlank() && (!isFixedAmount || amountEntry.canSave),
                modifier = Modifier.fillMaxWidth()
            )
        }
    }

    if (showDatePicker) {
        val datePickerState = rememberDatePickerState(
            initialSelectedDateMillis = anchorDateEpochDay?.let { it * 86400000L } ?: System.currentTimeMillis()
        )
        DatePickerDialog(
            onDismissRequest = { showDatePicker = false },
            confirmButton = {
                TextButton(
                    onClick = {
                        datePickerState.selectedDateMillis?.let { millis ->
                            anchorDateEpochDay = java.time.Instant.ofEpochMilli(millis)
                                .atZone(java.time.ZoneId.of("UTC"))
                                .toLocalDate()
                                .toEpochDay()
                        }
                        showDatePicker = false
                    }
                ) { Text(stringResource(R.string.action_confirm)) }
            },
            dismissButton = {
                TextButton(onClick = { showDatePicker = false }) {
                    Text(stringResource(R.string.action_cancel))
                }
            }
        ) {
            DatePicker(state = datePickerState)
        }
    }
}

