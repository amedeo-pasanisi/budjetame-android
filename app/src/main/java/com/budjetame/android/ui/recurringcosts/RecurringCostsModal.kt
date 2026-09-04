package com.budjetame.android.ui.recurringcosts

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
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
import com.budjetame.android.data.api.RecurringOccurrenceDto
import com.budjetame.android.ui.recurring.OccurrencesSection
import com.budjetame.android.util.Dates
import java.time.Instant

// The web app's Tailwind palette, ported for the delete confirmation.
private val RED_200 = Color(0xFFFECACA)
private val RED_600 = Color(0xFFDC2626)

/**
 * The create/edit/delete Recurring Cost form inside an AlertDialog (web
 * issue #56). Create and edit share one modal, like the Wallets and
 * Categories forms: Name, Amount, the interval ("Repeats every N
 * days/weeks/months/years" — the unit reads singular when N is 1), and
 * the start date: the first Occurrence, the one date the definition
 * carries (ADR-0024). Left empty at creation it becomes the creation day
 * — so "start today" needs no typing — while editing always shows a date,
 * and an empty one blocks the save: the date can be changed, never unset.
 * Occurrences repeat on the start date's day from there on (29–31 clamp
 * to the last day of shorter months). The definition itself never carries
 * a Wallet or a Category: they are chosen when a linked Expense is
 * recorded. The due-date override is gone (ADR-0024).
 *
 * Edit mode adds the Occurrences section (web ADR-0026): every non-Paid
 * Occurrence in the read's own order, each with its own Skip/Un-skip —
 * the card Skip/Un-skip button is gone. The section only exists while
 * editing: a definition under creation has no id yet, its first
 * Occurrence is only decided at creation.
 */
@Composable
fun RecurringCostsModal(
    modal: RecurringCostModalState,
    onNameChange: (String) -> Unit,
    onAmountChange: (String) -> Unit,
    onIntervalValueChange: (String) -> Unit,
    onIntervalUnitChange: (IntervalUnit) -> Unit,
    onStartDateChange: (String) -> Unit,
    onToggleOccurrence: (RecurringOccurrenceDto) -> Unit,
    onSubmit: () -> Unit,
    onDelete: () -> Unit,
    onClose: () -> Unit,
) {
    val editing = modal.editing

    AlertDialog(
        onDismissRequest = { if (!modal.busy) onClose() },
        title = { Text(if (editing) "Edit recurring cost" else "New recurring cost") },
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
                    placeholder = { Text("e.g. Rent") },
                    singleLine = true,
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("rc-name"),
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
                        .testTag("rc-amount"),
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
                            .testTag("rc-interval"),
                    )
                    IntervalUnitField(
                        intervalValue = modal.intervalValue,
                        unit = modal.intervalUnit,
                        onSelect = onIntervalUnitChange,
                        modifier = Modifier.weight(1.2f),
                    )
                }

                StartDateField(
                    value = modal.startDate,
                    onSelect = onStartDateChange,
                    // While editing the definition always carries a start
                    // date (ADR-0024): it can be changed, never cleared.
                    clearable = !editing,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 12.dp),
                )
                if (!editing) {
                    Text(
                        text = "The first occurrence. Leave empty to start today.",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 4.dp),
                    )
                }

                if (editing) {
                    OccurrencesSection(
                        occurrences = modal.occurrences,
                        error = modal.occurrencesError,
                        togglingDate = modal.togglingDate,
                        onToggle = onToggleOccurrence,
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
            Button(onClick = onSubmit, enabled = modal.canSubmit, shape = RoundedCornerShape(8.dp), contentPadding = PaddingValues(horizontal = 12.dp)) {
                Text(
                    when {
                        modal.submitting -> "Saving…"
                        editing -> "Save"
                        else -> "Create recurring cost"
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
    intervalValue: String,
    unit: IntervalUnit,
    onSelect: (IntervalUnit) -> Unit,
    modifier: Modifier = Modifier,
) {
    var expanded by remember { mutableStateOf(false) }
    // The unit reads singular when N is 1 — "Repeats every 1 month", like
    // the web form's option labels (ADR-0024).
    val label = intervalUnitLabel(parseIntervalValue(intervalValue), unit)
    ExposedDropdownMenuBox(
        expanded = expanded,
        onExpandedChange = { expanded = it },
        modifier = modifier,
    ) {
        OutlinedTextField(
            value = label,
            onValueChange = {},
            readOnly = true,
            label = { Text("Unit") },
            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded) },
            modifier = Modifier
                .fillMaxWidth()
                .menuAnchor(ExposedDropdownMenuAnchorType.PrimaryNotEditable)
                .testTag("rc-unit"),
        )
        ExposedDropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false },
        ) {
            INTERVAL_UNIT_OPTIONS.forEach { option ->
                DropdownMenuItem(
                    text = { Text(intervalUnitLabel(parseIntervalValue(intervalValue), option)) },
                    onClick = {
                        onSelect(option)
                        expanded = false
                    },
                )
            }
        }
    }
}

/** The start date as a tappable field opening a Material date picker. At
 * creation it is optional — "Clear" un-sets it, and an empty start date
 * means today, the backend setting the creation day. While editing the
 * definition always carries one (ADR-0024): the date can be changed, never
 * unset, so "Clear" is not offered. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun StartDateField(
    value: String,
    onSelect: (String) -> Unit,
    clearable: Boolean,
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
            label = { Text("Start date") },
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
                .testTag("rc-start"),
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
                    if (clearable && value.isNotEmpty()) {
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
    modal: RecurringCostModalState,
    onDelete: () -> Unit,
) {
    HorizontalDivider(modifier = Modifier.padding(vertical = 16.dp))
    Text(
        text = "Delete recurring cost",
        style = MaterialTheme.typography.bodyMedium,
        fontWeight = FontWeight.Medium,
    )
    Text(
        text = "Its linked expenses stay — as ordinary expenses.",
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
    shape = RoundedCornerShape(8.dp), contentPadding = PaddingValues(horizontal = 12.dp)) {
        Text(
            when {
                modal.deleting -> "Deleting…"
                modal.confirmingDelete -> "Tap again to confirm"
                else -> "Delete recurring cost"
            },
        )
    }
}

private const val MILLIS_PER_DAY = 86_400_000L
