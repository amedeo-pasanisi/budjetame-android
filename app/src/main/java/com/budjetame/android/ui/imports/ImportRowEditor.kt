package com.budjetame.android.ui.imports

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
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuAnchorType
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
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
import com.budjetame.android.data.api.CategoryDto
import com.budjetame.android.data.api.CategoryType
import com.budjetame.android.data.api.ImportRowDto
import com.budjetame.android.data.api.ImportRowInput
import com.budjetame.android.data.api.TransactionType
import com.budjetame.android.data.api.WalletDto
import com.budjetame.android.ui.transactions.ADD_CATEGORY_OPTION
import com.budjetame.android.ui.transactions.ADD_WALLET_OPTION
import com.budjetame.android.ui.transactions.TypeSelector
import com.budjetame.android.ui.transactions.WalletFieldTarget
import com.budjetame.android.ui.transactions.activeWallets
import com.budjetame.android.ui.transactions.categoryFilterLabel
import com.budjetame.android.ui.transactions.matchingCategories
import com.budjetame.android.ui.transactions.spendableWallets
import com.budjetame.android.util.Dates
import java.time.Instant

/**
 * The Verification row editor (web issue #46, ticket #26): any Preview row —
 * ready, duplicate, or problem — opens this dialog prefilled with its
 * fields. It reuses the Transaction form's shape (type picker, amount/date
 * grid, the wallet/category vs From/To cascade, description, location) but
 * edits *names*, not the Transaction form's resolved entities: the row's
 * Wallet and Category are names that the re-validation endpoint resolves
 * server-side. Save sends the edited fields and closes — the row's status
 * flips inline in the list behind, re-validated (POST /import/validate-row)
 * as it is saved. Cancel and the backdrop dismiss abandon the edit without
 * changing the row. The Wallet and Category selects list the Account's
 * existing entities of the kind the row can use: the non-Contact active
 * Wallets for an Expense/Income's Wallet field, all active Wallets for a
 * Transfer's From/To, Categories of the row's type — a current name that
 * matches nothing stays visible as the field's raw text, since the file's
 * value is kept until the user changes it (the re-validation decides
 * whether the name resolves).
 *
 * The selects carry the inline-create sentinels (ADR-0013/0014, ticket
 * #27): picking "New wallet…"/"New category…" — always last, never a value —
 * stacks the entity's create form on this editor, prefilled with the
 * field's name when it matches nothing (the missing name from the file);
 * when that form saves, the created entity's name is reported back through
 * `walletToSelect`/`categoryToSelect` and the exact originating field
 * selects it, the rest of the editor untouched.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ImportRowEditor(
    row: ImportRowDto,
    wallets: List<WalletDto>,
    categories: List<CategoryDto>,
    saving: Boolean,
    error: String?,
    onSave: (ImportRowInput) -> Unit,
    onClose: () -> Unit,
    /** Inline entity creation (ADR-0013): opens the Wallet create form,
     * hosted by the screen, prefilled with the field's missing name; the
     * target is the field whose sentinel was picked. */
    onAddWallet: (target: WalletFieldTarget, prefillName: String) -> Unit,
    /** The freshly created Wallet the screen reports back, with the field
     * whose sentinel was picked: that exact field selects it by name,
     * leaving the rest of the editor untouched. */
    walletToSelect: RowWalletToSelect?,
    /** Inline entity creation (ADR-0013): opens the Category create form,
     * locked to the row's current type and prefilled with the field's
     * missing name. */
    onAddCategory: (lockedType: CategoryType, prefillName: String) -> Unit,
    /** The freshly created Category's name for the Category field. */
    categoryToSelect: String?,
) {
    var type by remember(row.row) { mutableStateOf(rowEditorStartType(row.type)) }
    var amount by remember(row.row) { mutableStateOf(row.amount ?: "") }
    var date by remember(row.row) { mutableStateOf(row.date ?: "") }
    var wallet by remember(row.row) { mutableStateOf(row.wallet.orEmpty()) }
    var sourceWallet by remember(row.row) { mutableStateOf(row.source_wallet.orEmpty()) }
    var destinationWallet by remember(row.row) { mutableStateOf(row.destination_wallet.orEmpty()) }
    var category by remember(row.row) { mutableStateOf(row.category.orEmpty()) }
    var description by remember(row.row) { mutableStateOf(row.description.orEmpty()) }
    var latitude by remember(row.row) { mutableStateOf(row.latitude.orEmpty()) }
    var longitude by remember(row.row) { mutableStateOf(row.longitude.orEmpty()) }

    val isTransfer = type == TransactionType.TRANSFER
    // Frozen Wallets never resolve for an import (the validation rejects
    // them), so the selects offer only active ones; an Expense/Income's
    // Wallet field lists the non-Contact ones — Contact Wallets move money
    // only via Transfers (ADR-0017) — while a Transfer's From/To list all
    // four types.
    val transferWallets = activeWallets(wallets)
    val spendable = spendableWallets(wallets)
    val categoryType = if (isTransfer) TransactionType.EXPENSE else type
    val matchingCategories = matchingCategories(categories, categoryType)
    // The Category create form's lock while the row is an Expense or an
    // Income (the Transfer branch renders no Category field at all): the
    // created Category always fits the row being edited.
    val rowCategoryLock = if (type == TransactionType.INCOME) {
        CategoryType.INCOME
    } else {
        CategoryType.EXPENSE
    }

    val canSave = canSaveEditedRow(
        type = type,
        amount = amount,
        date = date,
        wallet = wallet,
        sourceWallet = sourceWallet,
        destinationWallet = destinationWallet,
    )

    // Inline entity creation (ADR-0013, ticket #27): when the screen's
    // inner Wallet modal saves, it reports the new Wallet's name here so
    // the exact field whose sentinel was picked selects it — nothing else
    // in the editor moves.
    LaunchedEffect(walletToSelect) {
        val pick = walletToSelect ?: return@LaunchedEffect
        when (pick.target) {
            WalletFieldTarget.WALLET -> wallet = pick.name
            WalletFieldTarget.SOURCE -> sourceWallet = pick.name
            WalletFieldTarget.DESTINATION -> destinationWallet = pick.name
        }
    }
    // The Category field's inline creation, same contract: the new
    // Category's name lands in the Category field, the only field that
    // changes.
    LaunchedEffect(categoryToSelect) {
        if (categoryToSelect != null) {
            category = categoryToSelect
        }
    }

    AlertDialog(
        onDismissRequest = { if (!saving) onClose() },
        title = { Text("Edit row ${row.row}", fontWeight = FontWeight.SemiBold) },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState()),
            ) {
                TypeSelector(selected = type, enabled = true, onSelect = { type = it })

                Row(
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    modifier = Modifier.padding(top = 12.dp),
                ) {
                    OutlinedTextField(
                        value = amount,
                        onValueChange = { amount = it },
                        label = { Text("Amount (€)") },
                        placeholder = { Text("0.00") },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                        singleLine = true,
                        modifier = Modifier
                            .weight(1f)
                            .testTag("im-amount"),
                    )
                    EditorDateField(
                        value = date,
                        onSelect = { date = it },
                        modifier = Modifier.weight(1f),
                    )
                }

                if (isTransfer) {
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                        modifier = Modifier.padding(top = 12.dp),
                    ) {
                        NameSelectField(
                            label = "From",
                            value = sourceWallet,
                            options = transferWallets.map { it.name to it.name },
                            onChange = { sourceWallet = it },
                            tag = "im-source",
                            onAdd = { prefill -> onAddWallet(WalletFieldTarget.SOURCE, prefill) },
                            modifier = Modifier.weight(1f),
                        )
                        NameSelectField(
                            label = "To",
                            value = destinationWallet,
                            options = transferWallets.map { it.name to it.name },
                            onChange = { destinationWallet = it },
                            tag = "im-destination",
                            onAdd = { prefill -> onAddWallet(WalletFieldTarget.DESTINATION, prefill) },
                            modifier = Modifier.weight(1f),
                        )
                    }
                } else {
                    Column(modifier = Modifier.padding(top = 12.dp)) {
                        NameSelectField(
                            label = "Wallet",
                            value = wallet,
                            options = spendable.map { it.name to it.name },
                            onChange = { wallet = it },
                            tag = "im-wallet",
                            onAdd = { prefill -> onAddWallet(WalletFieldTarget.WALLET, prefill) },
                            modifier = Modifier.fillMaxWidth(),
                        )
                        Text(
                            text = "Contact wallets only move money through transfers.",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(top = 4.dp),
                        )
                    }
                }

                if (isTransfer) {
                    Text(
                        text = "Transfers never carry a category.",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 12.dp),
                    )
                } else {
                    CategoryNameSelectField(
                        value = category,
                        categories = matchingCategories,
                        onChange = { category = it },
                        onAdd = { prefill -> onAddCategory(rowCategoryLock, prefill) },
                        modifier = Modifier.padding(top = 12.dp),
                    )
                }

                OutlinedTextField(
                    value = description,
                    onValueChange = { description = it.take(DESCRIPTION_MAX_LENGTH) },
                    label = { Text("Description") },
                    placeholder = { Text("Optional note") },
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 12.dp),
                )

                Row(
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    modifier = Modifier.padding(top = 12.dp),
                ) {
                    OutlinedTextField(
                        value = latitude,
                        onValueChange = { latitude = it },
                        label = { Text("Latitude") },
                        placeholder = { Text("Optional") },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                        singleLine = true,
                        modifier = Modifier
                            .weight(1f)
                            .testTag("im-latitude"),
                    )
                    OutlinedTextField(
                        value = longitude,
                        onValueChange = { longitude = it },
                        label = { Text("Longitude") },
                        placeholder = { Text("Optional") },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                        singleLine = true,
                        modifier = Modifier
                            .weight(1f)
                            .testTag("im-longitude"),
                    )
                }

                error?.let {
                    Text(
                        text = it,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                        modifier = Modifier.padding(top = 12.dp),
                    )
                }
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    onSave(
                        editedRowInput(
                            rowNumber = row.row,
                            type = type,
                            amount = amount,
                            date = date,
                            wallet = wallet,
                            sourceWallet = sourceWallet,
                            destinationWallet = destinationWallet,
                            category = category,
                            description = description,
                            latitude = latitude,
                            longitude = longitude,
                        ),
                    )
                },
                enabled = canSave && !saving,
            shape = RoundedCornerShape(8.dp), contentPadding = PaddingValues(horizontal = 12.dp)) {
                Text(if (saving) "Saving…" else "Save")
            }
        },
        dismissButton = {
            OutlinedButton(onClick = onClose, enabled = !saving, shape = RoundedCornerShape(8.dp), contentPadding = PaddingValues(horizontal = 12.dp)) {
                Text("Cancel")
            }
        },
    )
}

/**
 * A name-based entity select (web issue #77's ImportEntitySelect, ticket
 * #26 + #27): the editor's fields hold names, not ids, so the select keys
 * its options by name and the field's value is a raw name — one that
 * matches an option case-insensitively renders as that option, one that
 * matches nothing stays visible as the raw text (the file's value, kept
 * until the user changes it). The inline "New wallet…" sentinel (ADR-0013)
 * sits last and never becomes the field's value: picking it opens the
 * Wallet create form — hosted by the screen — prefilled with the field's
 * missing name.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun NameSelectField(
    label: String,
    value: String,
    options: List<Pair<String, String>>,
    onChange: (String) -> Unit,
    tag: String,
    onAdd: (prefillName: String) -> Unit,
    modifier: Modifier = Modifier,
) {
    var expanded by remember { mutableStateOf(false) }
    ExposedDropdownMenuBox(
        expanded = expanded,
        onExpandedChange = { expanded = it },
        modifier = modifier,
    ) {
        OutlinedTextField(
            value = displayedEntityName(value, options),
            onValueChange = {},
            readOnly = true,
            singleLine = true,
            label = { Text(label) },
            placeholder = { Text("Select") },
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
            options.forEach { (name, optionLabel) ->
                DropdownMenuItem(
                    text = { Text(optionLabel) },
                    onClick = {
                        onChange(name)
                        expanded = false
                    },
                )
            }
            DropdownMenuItem(
                text = { Text(ADD_WALLET_OPTION) },
                onClick = {
                    // Revert-on-pick: the sentinel never becomes the field's
                    // value; it opens the Wallet create form, prefilled with
                    // the field's name when it matches no existing Wallet
                    // (the missing name from the file).
                    onAdd(importSentinelPrefill(value, options))
                    expanded = false
                },
            )
        }
    }
}

/** The Category select an Expense/Income row carries (Transfers never do),
 * with the web's leading "None" option (optional field): a name matching an
 * existing Category renders as that option, anything else stays as the raw
 * text. The inline "New category…" sentinel (ADR-0013, ticket #27) sits
 * last and never becomes the field's value: picking it opens the Category
 * create form — hosted by the screen — prefilled with the field's missing
 * name. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun CategoryNameSelectField(
    value: String,
    categories: List<CategoryDto>,
    onChange: (String) -> Unit,
    onAdd: (prefillName: String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val options = categories.map { it.name to categoryFilterLabel(it.name, it.icon) }
    var expanded by remember { mutableStateOf(false) }
    Column(modifier = modifier) {
        ExposedDropdownMenuBox(
            expanded = expanded,
            onExpandedChange = { expanded = it },
        ) {
            OutlinedTextField(
                value = displayedEntityName(value, options),
                onValueChange = {},
                readOnly = true,
                singleLine = true,
                label = { Text("Category") },
                placeholder = { Text("None") },
                trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded) },
                modifier = Modifier
                    .fillMaxWidth()
                    .menuAnchor(ExposedDropdownMenuAnchorType.PrimaryNotEditable)
                    .testTag("im-category"),
            )
            ExposedDropdownMenu(
                expanded = expanded,
                onDismissRequest = { expanded = false },
            ) {
                DropdownMenuItem(
                    text = { Text("None") },
                    onClick = {
                        onChange("")
                        expanded = false
                    },
                )
                options.forEach { (name, optionLabel) ->
                    DropdownMenuItem(
                        text = { Text(optionLabel) },
                        onClick = {
                            onChange(name)
                            expanded = false
                        },
                    )
                }
                DropdownMenuItem(
                    text = { Text(ADD_CATEGORY_OPTION) },
                    onClick = {
                        // Revert-on-pick: the sentinel never becomes the
                        // field's value; it opens the Category create form,
                        // prefilled with the field's name when it matches no
                        // existing Category (the missing name from the file).
                        onAdd(importSentinelPrefill(value, options))
                        expanded = false
                    },
                )
            }
        }
    }
}

/** The collapsed field's text: the current value rendered as its resolved
 * option's label when it case-insensitively matches one (the file may have
 * spelled the entity differently), else the raw name. */
internal fun displayedEntityName(value: String, options: List<Pair<String, String>>): String {
    val trimmed = value.trim()
    val resolved = options.find { (name, _) -> name.equals(trimmed, ignoreCase = true) }
    return resolved?.second ?: value
}

/** The editor's Date field: a read-only field opening a Material date
 * picker, like the Transaction form's (each screen keeps its own copy). */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun EditorDateField(
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
            label = { Text("Date") },
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
                .testTag("im-date"),
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
                TextButton(onClick = { pickerOpen = false }) { Text("Cancel") }
            },
        ) {
            DatePicker(state = pickerState)
        }
    }
}

private const val MILLIS_PER_DAY = 86_400_000L
private const val DESCRIPTION_MAX_LENGTH = 500
