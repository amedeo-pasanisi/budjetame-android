package com.budjetame.android.ui.recurringincomes

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CalendarMonth
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuAnchorType
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.budjetame.android.data.api.IntervalUnit
import com.budjetame.android.ui.recurringcosts.INTERVAL_UNIT_OPTIONS
import com.budjetame.android.ui.recurringcosts.intervalUnitLabel
import com.budjetame.android.ui.recurringcosts.yearOverrideIncomplete
import com.budjetame.android.util.Dates
import java.time.Instant

// The web app's Tailwind palette, ported for the year-pair hint and the
// delete confirmation.
private val AMBER_600 = Color(0xFFD97706)
private val RED_200 = Color(0xFFFECACA)
private val RED_600 = Color(0xFFDC2626)

/**
 * The create/edit/delete Recurring Income form inside an AlertDialog (web
 * issue #60), mirroring the Recurring Cost form (ADR-0011). Create and edit
 * share one modal, like the Wallets and Categories forms: Name, Amount, the
 * interval (every N days/weeks/months/years), an optional start date (unset
 * defaults to the creation date), and the due-date override that follows
 * the interval unit — a day-of-month for months, a month+day for years,
 * nothing for days/weeks (ADR-0010 in the web repo) — plus the tap-again
 * delete confirmation. The definition itself never carries a Wallet or a
 * Category: they are chosen when a linked Income is recorded.
 */
@Composable
fun RecurringIncomesModal(
    modal: RecurringIncomeModalState,
    onNameChange: (String) -> Unit,
    onAmountChange: (String) -> Unit,
    onIntervalValueChange: (String) -> Unit,
    onIntervalUnitChange: (IntervalUnit) -> Unit,
    onStartDateChange: (String) -> Unit,
    onDueDayChange: (Int?) -> Unit,
    onDueMonthChange: (Int?) -> Unit,
    onSubmit: () -> Unit,
    onDelete: () -> Unit,
    onClose: () -> Unit,
) {
    val editing = modal.editing

    AlertDialog(
        onDismissRequest = { if (!modal.busy) onClose() },
        title = { Text(if (editing) "Edit recurring income" else "New recurring income") },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState()),
            ) {
                OutlinedTextField(
                    value = modal.name,
                    onValueChange = onNameChange,
                    label = { Text("Name") },
                    placeholder = { Text("e.g. Salary") },
                    singleLine = true,
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("ri-name"),
                )

                OutlinedTextField(
                    value = modal.amount,
                    onValueChange = onAmountChange,
                    label = { Text("Amount (€)") },
                    placeholder = { Text("0.00") },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                    singleLine = true,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 12.dp)
                        .testTag("ri-amount"),
                )

                Row(
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    modifier = Modifier.padding(top = 12.dp),
                ) {
                    OutlinedTextField(
                        value = modal.intervalValue,
                        onValueChange = onIntervalValueChange,
                        label = { Text("Repeats every") },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        singleLine = true,
                        modifier = Modifier
                            .weight(1f)
                            .testTag("ri-interval"),
                    )
                    IntervalUnitField(
                        value = modal.intervalUnit,
                        onSelect = onIntervalUnitChange,
                        modifier = Modifier.weight(1.2f),
                    )
                }

                StartDateField(
                    value = modal.startDate,
                    onSelect = onStartDateChange,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 12.dp),
                )
                Text(
                    text = "The first occurrence. Unset means today.",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 4.dp),
                )

                if (modal.intervalUnit == IntervalUnit.MONTHS) {
                    DueField(
                        label = "Due day (optional)",
                        value = modal.dueDay,
                        options = (1..31).toList(),
                        onSelect = onDueDayChange,
                        tag = "ri-due-day",
                        caption = "Due on this day of the month instead of the occurrence date. " +
                            "Days 29–31 fall on the last day of shorter months.",
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = 12.dp),
                    )
                } else if (modal.intervalUnit == IntervalUnit.YEARS) {
                    Text(
                        text = "Due date (optional)",
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.Medium,
                        modifier = Modifier.padding(top = 12.dp),
                    )
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                        modifier = Modifier.padding(top = 8.dp),
                    ) {
                        DueField(
                            label = "Due month",
                            value = modal.dueMonth,
                            options = (1..12).toList(),
                            onSelect = onDueMonthChange,
                            tag = "ri-due-month",
                            modifier = Modifier.weight(1f),
                        )
                        DueField(
                            label = "Due day",
                            value = modal.dueDay,
                            options = (1..31).toList(),
                            onSelect = onDueDayChange,
                            tag = "ri-due-day",
                            modifier = Modifier.weight(1f),
                        )
                    }
                    if (yearOverrideIncomplete(modal.dueDay, modal.dueMonth)) {
                        Text(
                            text = "Pick both the month and the day, or leave both unset.",
                            style = MaterialTheme.typography.labelSmall,
                            color = AMBER_600,
                            modifier = Modifier.padding(top = 4.dp),
                        )
                    }
                    Text(
                        text = "Due on this month and day of each year. " +
                            "Days 29–31 fall on the last day of shorter months.",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 4.dp),
                    )
                }

                modal.error?.let { error ->
                    Text(
                        text = error,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                        modifier = Modifier.padding(top = 12.dp),
                    )
                }

                if (editing) {
                    DeleteSection(modal = modal, onDelete = onDelete)
                }
            }
        },
        confirmButton = {
            Button(onClick = onSubmit, enabled = modal.canSubmit) {
                Text(
                    when {
                        modal.submitting -> "Saving…"
                        editing -> "Save"
                        else -> "Create recurring income"
                    },
                )
            }
        },
        dismissButton = {
            TextButton(onClick = onClose, enabled = !modal.busy) {
                Text("Cancel")
            }
        },
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun IntervalUnitField(
    value: IntervalUnit,
    onSelect: (IntervalUnit) -> Unit,
    modifier: Modifier = Modifier,
) {
    var expanded by remember { mutableStateOf(false) }
    ExposedDropdownMenuBox(
        expanded = expanded,
        onExpandedChange = { expanded = it },
        modifier = modifier,
    ) {
        OutlinedTextField(
            value = intervalUnitLabel(value),
            onValueChange = {},
            readOnly = true,
            label = { Text("Unit") },
            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded) },
            modifier = Modifier
                .fillMaxWidth()
                .menuAnchor(ExposedDropdownMenuAnchorType.PrimaryNotEditable)
                .testTag("ri-unit"),
        )
        ExposedDropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false },
        ) {
            INTERVAL_UNIT_OPTIONS.forEach { unit ->
                DropdownMenuItem(
                    text = { Text(intervalUnitLabel(unit)) },
                    onClick = {
                        onSelect(unit)
                        expanded = false
                    },
                )
            }
        }
    }
}

/** A "None or pick one number" select — the due-day and due-month fields. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun DueField(
    label: String,
    value: Int?,
    options: List<Int>,
    onSelect: (Int?) -> Unit,
    tag: String,
    modifier: Modifier = Modifier,
    caption: String? = null,
) {
    var expanded by remember { mutableStateOf(false) }
    Column(modifier = modifier) {
        ExposedDropdownMenuBox(
            expanded = expanded,
            onExpandedChange = { expanded = it },
        ) {
            OutlinedTextField(
                value = value?.toString() ?: "None",
                onValueChange = {},
                readOnly = true,
                singleLine = true,
                label = { Text(label) },
                trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded) },
                modifier = Modifier
                    .fillMaxWidth()
                    .menuAnchor(ExposedDropdownMenuAnchorType.PrimaryNotEditable)
                    .testTag(tag),
            )
            ExposedDropdownMenu(
                expanded = expanded,
                onDismissRequest = { expanded = false },
            ) {
                DropdownMenuItem(
                    text = { Text("None") },
                    onClick = {
                        onSelect(null)
                        expanded = false
                    },
                )
                options.forEach { option ->
                    DropdownMenuItem(
                        text = { Text(option.toString()) },
                        onClick = {
                            onSelect(option)
                            expanded = false
                        },
                    )
                }
            }
        }
        if (caption != null) {
            Text(
                text = caption,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 4.dp),
            )
        }
    }
}

/** The optional start date as a tappable field opening a Material date
 * picker; "Clear" un-sets it (unset means the creation date). */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun StartDateField(
    value: String,
    onSelect: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    var pickerOpen by remember { mutableStateOf(false) }
    Box(modifier = modifier) {
        OutlinedTextField(
            value = value,
            onValueChange = {},
            readOnly = true,
            enabled = false,
            singleLine = true,
            label = { Text("Start date (optional)") },
            trailingIcon = { Icon(Icons.Filled.CalendarMonth, contentDescription = null) },
            colors = OutlinedTextFieldDefaults.colors(
                // Disabled but styled like an enabled field: the tap goes to
                // the overlay, not to the field.
                disabledTextColor = MaterialTheme.colorScheme.onSurface,
                disabledBorderColor = MaterialTheme.colorScheme.outline,
                disabledLabelColor = MaterialTheme.colorScheme.onSurfaceVariant,
                disabledTrailingIconColor = MaterialTheme.colorScheme.onSurfaceVariant,
                disabledContainerColor = Color.Transparent,
            ),
            modifier = Modifier
                .fillMaxWidth()
                .testTag("ri-start"),
        )
        Box(
            modifier = Modifier
                .matchParentSize()
                .clickable { pickerOpen = true },
        )
    }

    if (pickerOpen) {
        val initialMillis = runCatching {
            Dates.parseApiDay(value).toEpochDay() * MILLIS_PER_DAY
        }.getOrNull()
        val pickerState = rememberDatePickerState(initialSelectedDateMillis = initialMillis)
        DatePickerDialog(
            onDismissRequest = { pickerOpen = false },
            confirmButton = {
                TextButton(
                    onClick = {
                        pickerState.selectedDateMillis?.let { millis ->
                            val day = Instant.ofEpochMilli(millis).atZone(Dates.rome).toLocalDate()
                            onSelect(Dates.toApiDay(day))
                        }
                        pickerOpen = false
                    },
                ) {
                    Text("OK")
                }
            },
            dismissButton = {
                Row {
                    if (value.isNotEmpty()) {
                        TextButton(
                            onClick = {
                                onSelect("")
                                pickerOpen = false
                            },
                        ) {
                            Text("Clear")
                        }
                    }
                    TextButton(onClick = { pickerOpen = false }) { Text("Cancel") }
                }
            },
        ) {
            DatePicker(state = pickerState)
        }
    }
}

@Composable
private fun DeleteSection(
    modal: RecurringIncomeModalState,
    onDelete: () -> Unit,
) {
    HorizontalDivider(modifier = Modifier.padding(vertical = 16.dp))
    Text(
        text = "Delete recurring income",
        style = MaterialTheme.typography.bodyMedium,
        fontWeight = FontWeight.Medium,
    )
    Text(
        text = "Its linked incomes stay — as ordinary incomes.",
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(top = 4.dp),
    )
    Button(
        onClick = onDelete,
        enabled = !modal.busy,
        colors = if (modal.confirmingDelete) {
            ButtonDefaults.buttonColors(containerColor = RED_600, contentColor = Color.White)
        } else {
            ButtonDefaults.buttonColors(containerColor = Color.Transparent, contentColor = RED_600)
        },
        border = if (modal.confirmingDelete) null else BorderStroke(1.dp, RED_200),
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 12.dp),
    ) {
        Text(
            when {
                modal.deleting -> "Deleting…"
                modal.confirmingDelete -> "Tap again to confirm"
                else -> "Delete recurring income"
            },
        )
    }
}

private const val MILLIS_PER_DAY = 86_400_000L
