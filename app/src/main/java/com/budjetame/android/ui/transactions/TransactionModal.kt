package com.budjetame.android.ui.transactions

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
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
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.budjetame.android.data.api.CategoryDto
import com.budjetame.android.data.api.TransactionType
import com.budjetame.android.data.api.WalletDto
import com.budjetame.android.util.Dates
import com.budjetame.android.util.Money
import java.time.Instant

private val AMBER_50 = Color(0xFFFFFBEB)
private val AMBER_700 = Color(0xFFB45309)
private val RED_200 = Color(0xFFFECACA)
private val RED_600 = Color(0xFFDC2626)

/**
 * The create/edit/delete Transaction form inside an AlertDialog (ticket #20):
 * the Expense/Income/Transfer write path with the type-specific rules
 * (Transfer's distinct From/To Wallets and no Category; Contact Wallets only
 * on an Expense, ADR-0017), the Europe/Rome-defaulted date, the Cash
 * negative-balance preview, and the tap-again delete confirmation. The
 * post-write warning flag from the API surfaces as the screen's banner.
 */
@Composable
fun TransactionModal(
    modal: TransactionsViewModel.ModalState,
    wallets: List<WalletDto>,
    categories: List<CategoryDto>,
    onTypeChange: (TransactionType) -> Unit,
    onAmountChange: (String) -> Unit,
    onDateChange: (String) -> Unit,
    onWalletChange: (Int) -> Unit,
    onSourceWalletChange: (Int) -> Unit,
    onDestinationWalletChange: (Int) -> Unit,
    onCategoryChange: (Int?) -> Unit,
    onDescriptionChange: (String) -> Unit,
    onSubmit: () -> Unit,
    onDelete: () -> Unit,
    onClose: () -> Unit,
) {
    val editing = modal.isEditing
    AlertDialog(
        onDismissRequest = { if (!modal.busy) onClose() },
        title = { Text(if (editing) "Edit transaction" else "New transaction") },
        text = {
            TransactionForm(
                modal = modal,
                wallets = wallets,
                categories = categories,
                onTypeChange = onTypeChange,
                onAmountChange = onAmountChange,
                onDateChange = onDateChange,
                onWalletChange = onWalletChange,
                onSourceWalletChange = onSourceWalletChange,
                onDestinationWalletChange = onDestinationWalletChange,
                onCategoryChange = onCategoryChange,
                onDescriptionChange = onDescriptionChange,
                onDelete = onDelete,
            )
        },
        confirmButton = {
            Button(onClick = onSubmit, enabled = modal.canSubmit) {
                Text(
                    when {
                        modal.submitting -> "Saving…"
                        editing -> "Save"
                        else -> "Save transaction"
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

/** The form body, split out so Compose UI tests can drive it directly. */
@Composable
internal fun TransactionForm(
    modal: TransactionsViewModel.ModalState,
    wallets: List<WalletDto>,
    categories: List<CategoryDto>,
    onTypeChange: (TransactionType) -> Unit,
    onAmountChange: (String) -> Unit,
    onDateChange: (String) -> Unit,
    onWalletChange: (Int) -> Unit,
    onSourceWalletChange: (Int) -> Unit,
    onDestinationWalletChange: (Int) -> Unit,
    onCategoryChange: (Int?) -> Unit,
    onDescriptionChange: (String) -> Unit,
    onDelete: () -> Unit,
) {
    val editing = modal.isEditing

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .verticalScroll(rememberScrollState()),
    ) {
        TypeSelector(
            selected = modal.type,
            enabled = !editing,
            onSelect = onTypeChange,
        )

        Row(
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            modifier = Modifier.padding(top = 12.dp),
        ) {
            OutlinedTextField(
                value = modal.amount,
                onValueChange = onAmountChange,
                label = { Text("Amount (€)") },
                placeholder = { Text("0.00") },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                singleLine = true,
                modifier = Modifier
                    .weight(1f)
                    .testTag("tx-amount"),
            )
            FormDateField(
                value = modal.date,
                onSelect = onDateChange,
                modifier = Modifier.weight(1f),
            )
        }

        if (modal.isTransfer) {
            TransferWalletFields(
                wallets = activeWallets(wallets),
                sourceWalletId = modal.sourceWalletId,
                destinationWalletId = modal.destinationWalletId,
                enabled = !editing,
                onSourceChange = onSourceWalletChange,
                onDestinationChange = onDestinationWalletChange,
                modifier = Modifier.padding(top = 12.dp),
            )
        } else {
            SingleWalletField(
                wallets = if (modal.type == TransactionType.EXPENSE) {
                    activeWallets(wallets)
                } else {
                    spendableWallets(wallets)
                },
                type = modal.type,
                value = modal.walletId,
                enabled = !editing,
                onChange = onWalletChange,
                modifier = Modifier.padding(top = 12.dp),
            )
        }

        BalancePreview(modal = modal, wallets = wallets)

        if (modal.isTransfer) {
            Text(
                text = "Transfers never carry a category.",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 12.dp),
            )
        } else {
            CategoryField(
                categories = matchingCategories(categories, modal.type),
                value = modal.categoryId,
                onChange = onCategoryChange,
                modifier = Modifier.padding(top = 12.dp),
            )
        }

        OutlinedTextField(
            value = modal.description,
            onValueChange = onDescriptionChange,
            label = { Text("Description") },
            placeholder = { Text("Optional note") },
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 12.dp),
        )

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
}

@Composable
private fun TypeSelector(
    selected: TransactionType,
    enabled: Boolean,
    onSelect: (TransactionType) -> Unit,
) {
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        listOf(
            TransactionType.EXPENSE,
            TransactionType.INCOME,
            TransactionType.TRANSFER,
        ).forEach { type ->
            val isSelected = type == selected
            if (isSelected) {
                Button(
                    onClick = { onSelect(type) },
                    enabled = enabled,
                    modifier = Modifier.weight(1f),
                ) {
                    Text(transactionTypeLabel(type))
                }
            } else {
                OutlinedButton(
                    onClick = { onSelect(type) },
                    enabled = enabled,
                    modifier = Modifier.weight(1f),
                ) {
                    Text(transactionTypeLabel(type))
                }
            }
        }
    }
}

/** The type picker's word — "Expense", "Income", or "Transfer". */
private fun transactionTypeLabel(type: TransactionType): String = when (type) {
    TransactionType.EXPENSE -> "Expense"
    TransactionType.INCOME -> "Income"
    TransactionType.TRANSFER -> "Transfer"
    TransactionType.OPENING_BALANCE -> "Opening balance"
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SingleWalletField(
    wallets: List<WalletDto>,
    type: TransactionType,
    value: Int?,
    enabled: Boolean,
    onChange: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    var expanded by remember { mutableStateOf(false) }
    Column(modifier = modifier) {
        ExposedDropdownMenuBox(
            expanded = expanded,
            onExpandedChange = { if (enabled) expanded = it },
        ) {
            OutlinedTextField(
                value = value?.let { id -> wallets.find { it.id == id }?.let(::walletOptionLabel) } ?: "",
                onValueChange = {},
                readOnly = true,
                enabled = enabled,
                singleLine = true,
                label = { Text("Wallet") },
                placeholder = { Text("Select a wallet") },
                trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded) },
                modifier = Modifier
                    .fillMaxWidth()
                    .menuAnchor(ExposedDropdownMenuAnchorType.PrimaryNotEditable)
                    .testTag("tx-wallet"),
            )
            ExposedDropdownMenu(
                expanded = expanded,
                onDismissRequest = { expanded = false },
            ) {
                wallets.forEach { wallet ->
                    DropdownMenuItem(
                        text = { Text(walletOptionLabel(wallet)) },
                        onClick = {
                            onChange(wallet.id)
                            expanded = false
                        },
                    )
                }
            }
        }
        Text(
            text = if (type == TransactionType.EXPENSE) {
                "An expense on a contact wallet means the contact paid for this."
            } else {
                "Incomes can't be recorded on contact wallets."
            },
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 4.dp),
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun TransferWalletFields(
    wallets: List<WalletDto>,
    sourceWalletId: Int?,
    destinationWalletId: Int?,
    enabled: Boolean,
    onSourceChange: (Int) -> Unit,
    onDestinationChange: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        modifier = modifier,
    ) {
        WalletSelectField(
            label = "From",
            wallets = wallets,
            value = sourceWalletId,
            enabled = enabled,
            onChange = onSourceChange,
            tag = "tx-source",
            modifier = Modifier.weight(1f),
        )
        WalletSelectField(
            label = "To",
            wallets = wallets,
            value = destinationWalletId,
            enabled = enabled,
            onChange = onDestinationChange,
            tag = "tx-destination",
            modifier = Modifier.weight(1f),
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun WalletSelectField(
    label: String,
    wallets: List<WalletDto>,
    value: Int?,
    enabled: Boolean,
    onChange: (Int) -> Unit,
    tag: String,
    modifier: Modifier = Modifier,
) {
    var expanded by remember { mutableStateOf(false) }
    ExposedDropdownMenuBox(
        expanded = expanded,
        onExpandedChange = { if (enabled) expanded = it },
        modifier = modifier,
    ) {
        OutlinedTextField(
            value = value?.let { id -> wallets.find { it.id == id }?.let(::walletOptionLabel) } ?: "",
            onValueChange = {},
            readOnly = true,
            enabled = enabled,
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
            wallets.forEach { wallet ->
                DropdownMenuItem(
                    text = { Text(walletOptionLabel(wallet)) },
                    onClick = {
                        onChange(wallet.id)
                        expanded = false
                    },
                )
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun CategoryField(
    categories: List<CategoryDto>,
    value: Int?,
    onChange: (Int?) -> Unit,
    modifier: Modifier = Modifier,
) {
    var expanded by remember { mutableStateOf(false) }
    ExposedDropdownMenuBox(
        expanded = expanded,
        onExpandedChange = { expanded = it },
        modifier = modifier,
    ) {
        OutlinedTextField(
            value = value?.let { id -> categories.find { it.id == id } }
                ?.let { categoryFilterLabel(it.name, it.icon) } ?: "None",
            onValueChange = {},
            readOnly = true,
            singleLine = true,
            label = { Text("Category") },
            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded) },
            modifier = Modifier
                .fillMaxWidth()
                .menuAnchor(ExposedDropdownMenuAnchorType.PrimaryNotEditable)
                .testTag("tx-category"),
        )
        ExposedDropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false },
        ) {
            DropdownMenuItem(
                text = { Text("None") },
                onClick = {
                    onChange(null)
                    expanded = false
                },
            )
            categories.forEach { category ->
                DropdownMenuItem(
                    text = { Text(categoryFilterLabel(category.name, category.icon)) },
                    onClick = {
                        onChange(category.id)
                        expanded = false
                    },
                )
            }
        }
    }
}

@Composable
private fun BalancePreview(
    modal: TransactionsViewModel.ModalState,
    wallets: List<WalletDto>,
) {
    val amount = parseAmount(modal.amount)
    val editedAmount = modal.editing?.let { parseAmount(it.amount) }

    if (modal.isTransfer) {
        val source = modal.sourceWalletId?.let { id -> wallets.find { it.id == id } }
        val destination = modal.destinationWalletId?.let { id -> wallets.find { it.id == id } }
        val projection = if (source != null && destination != null && amount != null) {
            projectTransfer(source.balance, destination.balance, amount, editedAmount)
        } else {
            null
        }
        if (projection != null && source != null && destination != null) {
            BalancePreviewCard(
                lines = listOf(
                    previewLine(source.name, projection.source.before, projection.source.after),
                    previewLine(destination.name, projection.destination.before, projection.destination.after),
                ),
                warn = isCashNegativeWarning(source, projection.source.after),
            )
        }
    } else {
        val wallet = modal.walletId?.let { id -> wallets.find { it.id == id } }
        val projection = if (wallet != null && amount != null) {
            projectBalance(wallet.balance, modal.type, amount, editedAmount)
        } else {
            null
        }
        if (projection != null && wallet != null) {
            BalancePreviewCard(
                lines = listOf(previewLine(wallet.name, projection.before, projection.after)),
                warn = isCashNegativeWarning(wallet, projection.after),
            )
        }
    }
}

private fun previewLine(name: String, before: java.math.BigDecimal, after: java.math.BigDecimal): String =
    "$name: ${Money.formatEuros(before.toPlainString())} → ${Money.formatEuros(after.toPlainString())}"

@Composable
private fun BalancePreviewCard(
    lines: List<String>,
    warn: Boolean,
) {
    Surface(
        shape = RoundedCornerShape(12.dp),
        color = MaterialTheme.colorScheme.surfaceVariant,
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 12.dp),
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            lines.forEach { line ->
                Text(
                    text = line,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (warn) {
                Text(
                    text = "⚠ This will make your Cash wallet negative.",
                    style = MaterialTheme.typography.bodySmall,
                    color = AMBER_700,
                    modifier = Modifier.padding(top = 4.dp),
                )
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun FormDateField(
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
                disabledTextColor = MaterialTheme.colorScheme.onSurface,
                disabledBorderColor = MaterialTheme.colorScheme.outline,
                disabledLabelColor = MaterialTheme.colorScheme.onSurfaceVariant,
                disabledTrailingIconColor = MaterialTheme.colorScheme.onSurfaceVariant,
                disabledContainerColor = Color.Transparent,
            ),
            modifier = Modifier.fillMaxWidth(),
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

@Composable
private fun DeleteSection(
    modal: TransactionsViewModel.ModalState,
    onDelete: () -> Unit,
) {
    HorizontalDivider(modifier = Modifier.padding(vertical = 16.dp))
    Text(
        text = "Delete transaction",
        style = MaterialTheme.typography.bodyMedium,
        fontWeight = FontWeight.Medium,
    )
    Text(
        text = "This permanently removes the transaction and updates the wallet balance.",
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
                else -> "Delete transaction"
            },
        )
    }
}

private const val MILLIS_PER_DAY = 86_400_000L
