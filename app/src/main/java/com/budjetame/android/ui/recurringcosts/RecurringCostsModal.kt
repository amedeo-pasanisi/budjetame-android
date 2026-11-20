package com.budjetame.android.ui.recurringcosts

import com.budjetame.android.ui.common.DeleteSection
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
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.budjetame.android.R
import com.budjetame.android.data.api.IntervalUnit
import com.budjetame.android.data.api.RecurringOccurrenceDto
import com.budjetame.android.ui.common.AccountLocaleResources
import com.budjetame.android.ui.common.FreezeSection
import com.budjetame.android.ui.common.LocalDisplayLocale
import com.budjetame.android.ui.common.UiText
import com.budjetame.android.ui.common.UnfreezeSection
import com.budjetame.android.ui.common.fieldErrorSupportingText
import com.budjetame.android.ui.common.localized
import com.budjetame.android.ui.common.resolve
import com.budjetame.android.ui.recurring.OccurrencesSection
import com.budjetame.android.util.Dates
import com.budjetame.android.util.Money
import java.time.Instant

// The web app's Tailwind palette, ported for the delete confirmation.
/**
 * The create/edit/delete Recurring Cost form inside an AlertDialog (web
 * issue #56). Create and edit share one modal, like the Wallets and
 * Categories forms: Name, Amount, the interval ("Repeats every N
 * days/weeks/months/years" — the unit reads singular when N is 1), and
 * the start date: the first Occurrence, the one date the definition
 * carries (ADR-0024). Left empty at creation it becomes the creation day
 * — so "start today" needs no typing — while editing always shows a date:
 * the date can be changed, never unset.
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
    onFreeze: () -> Unit,
    onUnfreeze: () -> Unit,
    onClose: () -> Unit,
    /** Delete, offered below Unfreeze on a frozen definition (web
     * ADR-0034) when the host passes it. */
    onDelete: (() -> Unit)? = null,
) {
    val locale = LocalDisplayLocale.current
    val editing = modal.editing

    AlertDialog(
        onDismissRequest = { if (!modal.busy) onClose() },
        title = localized {
            Text(stringResource(if (editing) R.string.recurring_costs_title_edit else R.string.recurring_costs_new))
        },
        text = localized {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState()),
            ) {
                if (modal.cost?.frozen == true) {
                    Text(
                        text = modal.name,
                        style = MaterialTheme.typography.bodyLarge,
                        modifier = Modifier.padding(top = 12.dp),
                    )
                    Text(
                        text = stringResource(R.string.recurring_frozen_amount, Money.formatEuros(modal.amount, locale)),
                        style = MaterialTheme.typography.bodyLarge,
                        modifier = Modifier.padding(top = 8.dp),
                    )
                    Text(
                        text = intervalText(
                            parseIntervalValue(modal.intervalValue) ?: 0,
                            modal.intervalUnit,
                        ).resolve(),
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.padding(top = 8.dp),
                    )
                    Text(
                        text = stringResource(R.string.recurring_frozen_start_date, modal.startDate),
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.padding(top = 8.dp),
                    )
                } else {
                    val nameError = modal.fieldErrors[RecurringCostFields.NAME]
                    OutlinedTextField(
                        value = modal.name,
                        onValueChange = onNameChange,
                        label = { Text(stringResource(R.string.recurring_name)) },
                        placeholder = { Text(stringResource(R.string.recurring_costs_name_placeholder)) },
                        singleLine = true,
                        isError = nameError != null,
                        supportingText = fieldErrorSupportingText(nameError),
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("rc-name"),
                    )

                    val amountError = modal.fieldErrors[RecurringCostFields.AMOUNT]
                    OutlinedTextField(
                        value = modal.amount,
                        onValueChange = onAmountChange,
                        label = { Text(stringResource(R.string.recurring_amount)) },
                        placeholder = { Text(stringResource(R.string.recurring_amount_placeholder)) },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                        singleLine = true,
                        isError = amountError != null,
                        supportingText = fieldErrorSupportingText(amountError),
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = 12.dp)
                            .testTag("rc-amount"),
                    )

                    Row(
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                        modifier = Modifier.padding(top = 12.dp),
                    ) {
                        val intervalError = modal.fieldErrors[RecurringCostFields.INTERVAL]
                        OutlinedTextField(
                            value = modal.intervalValue,
                            onValueChange = onIntervalValueChange,
                            label = { Text(stringResource(R.string.recurring_repeats_every)) },
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                            singleLine = true,
                            isError = intervalError != null,
                            supportingText = fieldErrorSupportingText(intervalError),
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
                        error = modal.fieldErrors[RecurringCostFields.START_DATE],
                        onSelect = onStartDateChange,
                        clearable = !editing,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = 12.dp),
                    )
                    if (!editing) {
                        Text(
                            text = stringResource(R.string.recurring_start_hint),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(top = 4.dp),
                        )
                    }

                    OccurrencesSection(
                        occurrences = modal.occurrences,
                        error = modal.occurrencesError,
                        togglingDate = modal.togglingDate,
                        onToggle = onToggleOccurrence,
                    )
                }

                modal.error?.let { error ->
                    Text(
                        text = error.resolve(),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                        modifier = Modifier.padding(top = 12.dp),
                    )
                }

                if (editing) {
                    if (modal.cost?.frozen == true) {
                        UnfreezeSection(
                            isFreezing = modal.freezing,
                            onUnfreeze = onUnfreeze,
                            heading = stringResource(R.string.recurring_costs_unfreeze),
                            description = stringResource(R.string.recurring_unfreeze_description),
                        )
                        onDelete?.let { delete ->
                            DeleteSection(
                                label = stringResource(R.string.recurring_costs_delete),
                                hint = stringResource(R.string.recurring_costs_delete_hint),
                                isDeleting = modal.deleting,
                                enabled = !modal.busy,
                                deleteError = modal.deleteError?.resolve(),
                                onDelete = delete,
                            )
                        }
                    } else {
                        FreezeSection(
                            canFreeze = true,
                            isFreezing = modal.freezing,
                            confirmingFreeze = modal.confirmingFreeze,
                            freezeError = null,
                            onFreeze = onFreeze,
                            heading = stringResource(R.string.recurring_costs_freeze),
                            description = stringResource(R.string.recurring_costs_freeze_description),
                        )
                    }
                }
            }
        },
        confirmButton = localized {
            Button(onClick = onSubmit, enabled = modal.canSave, shape = RoundedCornerShape(8.dp), contentPadding = PaddingValues(horizontal = 12.dp)) {
                Text(
                    stringResource(
                        when {
                            modal.submitting -> R.string.common_saving
                            editing -> R.string.common_save
                            else -> R.string.recurring_costs_create
                        },
                    ),
                )
            }
        },
        dismissButton = localized {
            TextButton(onClick = onClose, enabled = !modal.busy) {
                Text(stringResource(R.string.common_cancel))
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
    val label = intervalUnitLabel(parseIntervalValue(intervalValue), unit).resolve()
    ExposedDropdownMenuBox(
        expanded = expanded,
        onExpandedChange = { expanded = it },
        modifier = modifier,
    ) {
        OutlinedTextField(
            value = label,
            onValueChange = {},
            readOnly = true,
            label = { Text(stringResource(R.string.recurring_unit)) },
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
            // The menu is its own window: re-apply the Account Locale (ADR-0010).
            AccountLocaleResources {
                INTERVAL_UNIT_OPTIONS.forEach { option ->
                    DropdownMenuItem(
                        text = { Text(intervalUnitLabel(parseIntervalValue(intervalValue), option).resolve()) },
                        onClick = {
                            onSelect(option)
                            expanded = false
                        },
                    )
                }
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
    error: UiText?,
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
            label = { Text(stringResource(R.string.recurring_start_date)) },
            trailingIcon = { Icon(Icons.Filled.CalendarMonth, contentDescription = null) },
            isError = error != null,
            supportingText = fieldErrorSupportingText(error),
            colors = OutlinedTextFieldDefaults.colors(
                // Disabled but styled like an enabled field: the tap goes to
                // the overlay, not to the field — so the disabled colors
                // carry the error state themselves.
                disabledTextColor = MaterialTheme.colorScheme.onSurface,
                disabledBorderColor = if (error != null) {
                    MaterialTheme.colorScheme.error
                } else {
                    MaterialTheme.colorScheme.outline
                },
                disabledLabelColor = if (error != null) {
                    MaterialTheme.colorScheme.error
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant
                },
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
            // Its own window (ADR-0010): the buttons and the picker's
            // built-in month/day names follow the Account Locale.
            confirmButton = localized {
                TextButton(
                    onClick = {
                        pickerState.selectedDateMillis?.let { millis ->
                            val day = Instant.ofEpochMilli(millis).atZone(Dates.rome).toLocalDate()
                            onSelect(Dates.toApiDay(day))
                        }
                        pickerOpen = false
                    },
                ) {
                    Text(stringResource(R.string.recurring_date_ok))
                }
            },
            dismissButton = localized {
                Row {
                    if (clearable && value.isNotEmpty()) {
                        TextButton(
                            onClick = {
                                onSelect("")
                                pickerOpen = false
                            },
                        ) {
                            Text(stringResource(R.string.recurring_date_clear))
                        }
                    }
                    TextButton(onClick = { pickerOpen = false }) { Text(stringResource(R.string.common_cancel)) }
                }
            },
        ) {
            AccountLocaleResources { DatePicker(state = pickerState) }
        }
    }
}

private const val MILLIS_PER_DAY = 86_400_000L
