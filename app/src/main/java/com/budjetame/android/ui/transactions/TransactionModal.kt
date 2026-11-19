package com.budjetame.android.ui.transactions

import androidx.annotation.StringRes
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
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
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.budjetame.android.R
import com.budjetame.android.data.api.CategoryDto
import com.budjetame.android.data.api.RecurringCostDto
import com.budjetame.android.data.api.RecurringIncomeDto
import com.budjetame.android.data.api.TransactionType
import com.budjetame.android.data.api.WalletDto
import com.budjetame.android.data.transaction.LatLng
import com.budjetame.android.data.transaction.Place
import com.budjetame.android.data.transaction.formatLocation
import com.budjetame.android.data.transaction.mapLink
import com.budjetame.android.ui.common.AccountLocaleResources
import com.budjetame.android.ui.common.LocalDisplayLocale
import com.budjetame.android.ui.common.fieldErrorSupportingText
import com.budjetame.android.ui.common.localized
import com.budjetame.android.ui.common.parseAmountInput
import com.budjetame.android.ui.maps.MapPickerDialog
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
 * on an Expense, ADR-0017; enforced by submit-and-validate, ADR-0009 — Save
 * is never gated on them, an invalid Save renders each Field Error under
 * its own field), the Europe/Rome-defaulted date, the Cash
 * negative-balance preview, and the single-tap delete (its Undo lives in
 * the shell's stack, ticket #58). The post-write warning flag from the API
 * surfaces as the screen's banner. The
 * Wallet and Category selects carry the inline-create sentinels (ADR-0013):
 * `onAddWallet` reports which field's sentinel was picked (the created
 * Wallet is auto-selected into exactly that field), `onAddCategory` opens
 * the Category create form locked to this form's type — the selects
 * themselves never change value on a sentinel pick. An Expense also carries
 * the Recurring Cost link picker (web issue #57): `recurringCosts` is the
 * definitions list it offers, `onRecurringCostChange` applies the pick — a
 * pick pays the definition's oldest Unpaid Occurrence on save, None unlinks.
 * An Income carries the mirror picker (web issue #61): `recurringIncomes`
 * and `onRecurringIncomeChange`, the same contract on the income side.
 * A Transfer carries one of the two pickers only while its chosen pair
 * qualifies (web issue #99 / ADR-0027): money in from a Contact Wallet
 * offers Recurring Incomes, money out to a Contact Wallet offers Recurring
 * Costs — own↔own and Contact↔Contact transfers never carry either.
 */
@Composable
fun TransactionModal(
    modal: TransactionsViewModel.ModalState,
    wallets: List<WalletDto>,
    categories: List<CategoryDto>,
    recurringCosts: List<RecurringCostDto> = emptyList(),
    recurringIncomes: List<RecurringIncomeDto> = emptyList(),
    onTypeChange: (TransactionType) -> Unit,
    onAmountChange: (String) -> Unit,
    onDateChange: (String) -> Unit,
    onWalletChange: (Int) -> Unit,
    onSourceWalletChange: (Int) -> Unit,
    onDestinationWalletChange: (Int) -> Unit,
    onCategoryChange: (Int?) -> Unit,
    onRecurringCostChange: (Int?) -> Unit = {},
    onRecurringIncomeChange: (Int?) -> Unit = {},
    onDescriptionChange: (String) -> Unit,
    onAddWallet: (WalletFieldTarget) -> Unit = {},
    onAddCategory: () -> Unit = {},
    onSubmit: () -> Unit,
    onDelete: () -> Unit,
    onClose: () -> Unit,
    // --- Location (ticket #29) ---
    onLocationPick: (LatLng, Place?) -> Unit = { _, _ -> },
    onRemoveLocation: () -> Unit = {},
    onUseMyLocation: () -> Unit = {},
    onOpenLocationPicker: () -> Unit = {},
    onCloseLocationPicker: () -> Unit = {},
    /** Opens the built maps link (CONTEXT.md: built client-side, never
     * stored as text) in the system browser/maps app. */
    onOpenMapLink: (String) -> Unit = {},
    /** The map picker behind the provider seam (ADR-0004): the default is
     * the real seam dialog; tests swap in a fake picker. */
    mapPicker: @Composable (
        position: LatLng?,
        onPick: (LatLng, Place?) -> Unit,
        onCancel: () -> Unit,
    ) -> Unit = { position, onPick, onCancel ->
        MapPickerDialog(position = position, onPick = onPick, onCancel = onCancel)
    },
) {
    val editing = modal.isEditing
    AlertDialog(
        onDismissRequest = { if (!modal.busy) onClose() },
        // Its own window: each slot re-applies the Account Locale (ADR-0010).
        title = localized {
            Text(stringResource(if (editing) R.string.transaction_form_title_edit else R.string.transaction_form_title_new))
        },
        text = localized {
            TransactionForm(
                modal = modal,
                wallets = wallets,
                categories = categories,
                recurringCosts = recurringCosts,
                recurringIncomes = recurringIncomes,
                onTypeChange = onTypeChange,
                onAmountChange = onAmountChange,
                onDateChange = onDateChange,
                onWalletChange = onWalletChange,
                onSourceWalletChange = onSourceWalletChange,
                onDestinationWalletChange = onDestinationWalletChange,
                onCategoryChange = onCategoryChange,
                onRecurringCostChange = onRecurringCostChange,
                onRecurringIncomeChange = onRecurringIncomeChange,
                onDescriptionChange = onDescriptionChange,
                onAddWallet = onAddWallet,
                onAddCategory = onAddCategory,
                onRemoveLocation = onRemoveLocation,
                onUseMyLocation = onUseMyLocation,
                onOpenLocationPicker = onOpenLocationPicker,
                onOpenMapLink = onOpenMapLink,
                onDelete = onDelete,
            )
        },
        confirmButton = localized {
            Button(onClick = onSubmit, enabled = modal.canSave, shape = RoundedCornerShape(8.dp), contentPadding = PaddingValues(horizontal = 12.dp)) {
                Text(
                    stringResource(
                        when {
                            modal.submitting -> R.string.common_saving
                            editing -> R.string.common_save
                            else -> R.string.transaction_form_save_transaction
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

    // The map picker hosts its own window (the seam's dialog) above the
    // form; composed after the AlertDialog so it renders on top. A pick or
    // the Cancel closes it through the callbacks — the form draft stays.
    if (modal.showingPicker) {
        mapPicker(modal.location, onLocationPick, onCloseLocationPicker)
    }
}

/** The form body, split out so Compose UI tests can drive it directly. */
@Composable
internal fun TransactionForm(
    modal: TransactionsViewModel.ModalState,
    wallets: List<WalletDto>,
    categories: List<CategoryDto>,
    recurringCosts: List<RecurringCostDto> = emptyList(),
    recurringIncomes: List<RecurringIncomeDto> = emptyList(),
    onTypeChange: (TransactionType) -> Unit,
    onAmountChange: (String) -> Unit,
    onDateChange: (String) -> Unit,
    onWalletChange: (Int) -> Unit,
    onSourceWalletChange: (Int) -> Unit,
    onDestinationWalletChange: (Int) -> Unit,
    onCategoryChange: (Int?) -> Unit,
    onRecurringCostChange: (Int?) -> Unit = {},
    onRecurringIncomeChange: (Int?) -> Unit = {},
    onDescriptionChange: (String) -> Unit,
    onAddWallet: (WalletFieldTarget) -> Unit = {},
    onAddCategory: () -> Unit = {},
    onDelete: () -> Unit,
    onRemoveLocation: () -> Unit = {},
    onUseMyLocation: () -> Unit = {},
    onOpenLocationPicker: () -> Unit = {},
    onOpenMapLink: (String) -> Unit = {},
) {
    val editing = modal.isEditing

    // The recurring-link pickers' qualification (web issue #99 / ADR-0027):
    // a Transfer shows the Recurring Cost select only while the chosen pair
    // is exactly one own Wallet and one Contact Wallet with the Contact as
    // destination — money out to a tracked person, the mirror of a linked
    // Expense — and the Recurring Income select only while the Contact
    // Wallet is the source — money in from a tracked person, the mirror of
    // a linked Income. Own↔own, Contact↔Contact, and a missing leg never
    // qualify.
    val sourceWallet = modal.sourceWalletId?.let { id -> wallets.find { it.id == id } }
    val destinationWallet = modal.destinationWalletId?.let { id -> wallets.find { it.id == id } }
    val transferQualifiesForCost =
        modal.isTransfer && transferCostLinkQualifies(sourceWallet, destinationWallet)
    val transferQualifiesForIncome =
        modal.isTransfer && transferIncomeLinkQualifies(sourceWallet, destinationWallet)

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
            val amountError = modal.fieldErrors[TransactionFields.AMOUNT]
            OutlinedTextField(
                value = modal.amount,
                onValueChange = onAmountChange,
                label = { Text(stringResource(R.string.transaction_form_amount)) },
                placeholder = { Text(stringResource(R.string.transaction_form_amount_placeholder)) },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                singleLine = true,
                isError = amountError != null,
                supportingText = fieldErrorSupportingText(amountError),
                modifier = Modifier
                    .weight(1f)
                    .testTag("tx-amount"),
            )
            FormDateField(
                value = modal.date,
                error = modal.fieldErrors[TransactionFields.DATE],
                onSelect = onDateChange,
                modifier = Modifier.weight(1f),
            )
        }

        if (modal.isTransfer) {
            TransferWalletFields(
                wallets = activeWallets(wallets),
                sourceWalletId = modal.sourceWalletId,
                destinationWalletId = modal.destinationWalletId,
                sourceError = modal.fieldErrors[TransactionFields.SOURCE],
                destinationError = modal.fieldErrors[TransactionFields.DESTINATION],
                enabled = !editing,
                onSourceChange = onSourceWalletChange,
                onDestinationChange = onDestinationWalletChange,
                onAddWallet = onAddWallet,
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
                value = modal.walletId?.let { id -> wallets.find { it.id == id } },
                error = modal.fieldErrors[TransactionFields.WALLET],
                enabled = !editing,
                onChange = onWalletChange,
                onAdd = { onAddWallet(WalletFieldTarget.WALLET) },
                modifier = Modifier.padding(top = 12.dp),
            )
        }

        BalancePreview(modal = modal, wallets = wallets)

        if (modal.isTransfer) {
            Text(
                text = stringResource(R.string.transaction_form_transfer_no_category),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 12.dp),
            )
        } else {
            CategoryField(
                categories = matchingCategories(categories, modal.type),
                value = modal.categoryId,
                onChange = onCategoryChange,
                onAdd = onAddCategory,
                modifier = Modifier.padding(top = 12.dp),
            )
        }

        if (modal.type == TransactionType.EXPENSE || transferQualifiesForCost) {
            RecurringCostField(
                costs = recurringCosts,
                value = modal.recurringCostId,
                occurrenceDate = payingOccurrenceDate(
                    storedLinkId = modal.editing?.recurring_cost_id,
                    storedPin = modal.editing?.occurrence_date,
                    pickedId = modal.recurringCostId,
                    costs = recurringCosts,
                ),
                onChange = onRecurringCostChange,
                modifier = Modifier.padding(top = 12.dp),
            )
        } else if (modal.type == TransactionType.INCOME || transferQualifiesForIncome) {
            RecurringIncomeField(
                incomes = recurringIncomes,
                value = modal.recurringIncomeId,
                occurrenceDate = payingIncomeOccurrenceDate(
                    storedLinkId = modal.editing?.recurring_income_id,
                    storedPin = modal.editing?.occurrence_date,
                    pickedId = modal.recurringIncomeId,
                    incomes = recurringIncomes,
                ),
                onChange = onRecurringIncomeChange,
                modifier = Modifier.padding(top = 12.dp),
            )
        }

        OutlinedTextField(
            value = modal.description,
            onValueChange = onDescriptionChange,
            label = { Text(stringResource(R.string.transaction_form_description)) },
            placeholder = { Text(stringResource(R.string.transaction_form_description_placeholder)) },
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 12.dp),
        )

        LocationSection(
            location = modal.location,
            place = modal.place,
            locating = modal.locating,
            gpsError = modal.gpsError,
            showingPicker = modal.showingPicker,
            onRemove = onRemoveLocation,
            onUseMyLocation = onUseMyLocation,
            onOpenPicker = onOpenLocationPicker,
            onOpenMapLink = onOpenMapLink,
            modifier = Modifier.padding(top = 12.dp),
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

/**
 * The Expense/Income/Transfer picker, shared by the Transaction modal and
 * the Import row editor (ticket #36): the buttons keep their natural
 * width and sit in a FlowRow with 8dp spacing, so a label can never be
 * squeezed below one line — when the three do not fit the dialog, whole
 * buttons wrap onto further lines instead of breaking mid-word. The
 * selected type stays the filled Button; the others stay outlined.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun TypeSelector(
    selected: TransactionType,
    enabled: Boolean,
    onSelect: (TransactionType) -> Unit,
) {
    FlowRow(
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
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
                shape = RoundedCornerShape(8.dp), contentPadding = PaddingValues(horizontal = 12.dp)) {
                    Text(stringResource(transactionTypeLabelRes(type)))
                }
            } else {
                OutlinedButton(
                    onClick = { onSelect(type) },
                    enabled = enabled,
                shape = RoundedCornerShape(8.dp), contentPadding = PaddingValues(horizontal = 12.dp)) {
                    Text(stringResource(transactionTypeLabelRes(type)))
                }
            }
        }
    }
}

/** The type picker's word — "Expense", "Income", or "Transfer" — as a
 * string resource in the Account's Locale (ADR-0010). */
@StringRes
internal fun transactionTypeLabelRes(type: TransactionType): Int = when (type) {
    TransactionType.EXPENSE -> R.string.transaction_form_type_expense
    TransactionType.INCOME -> R.string.transaction_form_type_income
    TransactionType.TRANSFER -> R.string.transaction_form_type_transfer
    TransactionType.OPENING_BALANCE -> R.string.transaction_form_type_opening_balance
}

/** The single-Wallet select an Expense/Income moves money through, with the
 * inline "New wallet…" sentinel (ADR-0013): the option always sits last and
 * never becomes the field's value — picking it reverts the select and opens
 * the create form. An Expense may create a Contact Wallet (consumption the
 * contact paid for); an Income's sentinel locks Contact out (ADR-0017). */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SingleWalletField(
    wallets: List<WalletDto>,
    type: TransactionType,
    value: WalletDto?,
    error: String?,
    enabled: Boolean,
    onChange: (Int) -> Unit,
    onAdd: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val locale = LocalDisplayLocale.current
    // Resolved here: the menu is its own window (ADR-0010).
    val newWallet = stringResource(R.string.transaction_form_new_wallet)
    var expanded by remember { mutableStateOf(false) }
    Column(modifier = modifier) {
        ExposedDropdownMenuBox(
            expanded = expanded,
            onExpandedChange = { if (enabled) expanded = it },
        ) {
            OutlinedTextField(
                // The selection shows even when the list does not offer it:
                // a Contact Wallet kept from an Expense switched to Income
                // stays visible for its Field Error to point at (ADR-0009).
                value = value?.let { walletOptionLabel(it, locale) } ?: "",
                onValueChange = {},
                readOnly = true,
                enabled = enabled,
                singleLine = true,
                label = { Text(stringResource(R.string.transaction_form_wallet)) },
                placeholder = { Text(stringResource(R.string.transaction_form_wallet_placeholder)) },
                isError = error != null,
                supportingText = fieldErrorSupportingText(error),
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
                        text = { Text(walletOptionLabel(wallet, locale)) },
                        onClick = {
                            onChange(wallet.id)
                            expanded = false
                        },
                    )
                }
                DropdownMenuItem(
                    text = { Text(newWallet) },
                    onClick = {
                        // Revert-on-pick: the sentinel never becomes the
                        // field's value; it only opens the create form.
                        onAdd()
                        expanded = false
                    },
                )
            }
        }
        Text(
            text = stringResource(
                if (type == TransactionType.EXPENSE) {
                    R.string.transaction_form_wallet_contact_explanation
                } else {
                    R.string.transaction_form_wallet_income_no_contact
                },
            ),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 4.dp),
        )
    }
}

/** The From/To Wallet selects a Transfer moves money between, each with the
 * inline "New wallet…" sentinel (ADR-0013) reporting the exact field whose
 * sentinel was picked. Both allow all four types — Contact included — since
 * Transfers are where Contact Wallets belong. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun TransferWalletFields(
    wallets: List<WalletDto>,
    sourceWalletId: Int?,
    destinationWalletId: Int?,
    sourceError: String?,
    destinationError: String?,
    enabled: Boolean,
    onSourceChange: (Int) -> Unit,
    onDestinationChange: (Int) -> Unit,
    onAddWallet: (WalletFieldTarget) -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        modifier = modifier,
    ) {
        WalletSelectField(
            label = stringResource(R.string.transaction_form_from),
            wallets = wallets,
            value = sourceWalletId,
            error = sourceError,
            enabled = enabled,
            onChange = onSourceChange,
            onAdd = { onAddWallet(WalletFieldTarget.SOURCE) },
            tag = "tx-source",
            modifier = Modifier.weight(1f),
        )
        WalletSelectField(
            label = stringResource(R.string.transaction_form_to),
            wallets = wallets,
            value = destinationWalletId,
            error = destinationError,
            enabled = enabled,
            onChange = onDestinationChange,
            onAdd = { onAddWallet(WalletFieldTarget.DESTINATION) },
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
    error: String?,
    enabled: Boolean,
    onChange: (Int) -> Unit,
    onAdd: () -> Unit,
    tag: String,
    modifier: Modifier = Modifier,
) {
    val locale = LocalDisplayLocale.current
    // Resolved here: the menu is its own window (ADR-0010).
    val newWallet = stringResource(R.string.transaction_form_new_wallet)
    var expanded by remember { mutableStateOf(false) }
    ExposedDropdownMenuBox(
        expanded = expanded,
        onExpandedChange = { if (enabled) expanded = it },
        modifier = modifier,
    ) {
        OutlinedTextField(
            value = value?.let { id -> wallets.find { it.id == id }?.let { walletOptionLabel(it, locale) } } ?: "",
            onValueChange = {},
            readOnly = true,
            enabled = enabled,
            singleLine = true,
            label = { Text(label) },
            placeholder = { Text(stringResource(R.string.transaction_form_select)) },
            isError = error != null,
            supportingText = fieldErrorSupportingText(error),
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
                    text = { Text(walletOptionLabel(wallet, locale)) },
                    onClick = {
                        onChange(wallet.id)
                        expanded = false
                    },
                )
            }
            DropdownMenuItem(
                text = { Text(newWallet) },
                onClick = {
                    onAdd()
                    expanded = false
                },
            )
        }
    }
}

/** The Category select an Expense/Income carries (Transfers never do), with
 * the inline "New category…" sentinel (ADR-0013): it always sits last, after
 * None, and never becomes the field's value — picking it opens the create
 * form locked to this field's type. The select stays live while editing,
 * like the web app's. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun CategoryField(
    categories: List<CategoryDto>,
    value: Int?,
    onChange: (Int?) -> Unit,
    onAdd: () -> Unit,
    modifier: Modifier = Modifier,
) {
    // Resolved here: the menu is its own window (ADR-0010).
    val none = stringResource(R.string.transaction_form_none)
    val newCategory = stringResource(R.string.transaction_form_new_category)
    var expanded by remember { mutableStateOf(false) }
    ExposedDropdownMenuBox(
        expanded = expanded,
        onExpandedChange = { expanded = it },
        modifier = modifier,
    ) {
        OutlinedTextField(
            value = value?.let { id -> categories.find { it.id == id } }
                ?.let { categoryFilterLabel(it.name, it.icon) } ?: none,
            onValueChange = {},
            readOnly = true,
            singleLine = true,
            label = { Text(stringResource(R.string.transaction_form_category)) },
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
                text = { Text(none) },
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
            DropdownMenuItem(
                text = { Text(newCategory) },
                onClick = {
                    onAdd()
                    expanded = false
                },
            )
        }
    }
}

/**
 * The Recurring Cost select an Expense carries (web issue #57) — and a
 * Transfer whose destination is a Contact Wallet (web issue #99 /
 * ADR-0027): money out to a tracked person pays a Recurring Cost like an
 * Expense does. Income never renders it, and a Transfer shows it only
 * while the chosen pair qualifies. Picking a
 * cost signs the Occurrence the helper names as paid on save: the stored
 * pin when the form is editing the very link already on the row (which must
 * never be reassigned by a mere date edit), else the selected definition's
 * oldest Unpaid Occurrence — the one a new link pays. The None option
 * unlinks (freeing the Occurrence on save). The field is hidden until there
 * is a definition to pick or a link to drop — an empty picker with nothing
 * but None would be noise.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun RecurringCostField(
    costs: List<RecurringCostDto>,
    value: Int?,
    occurrenceDate: String?,
    onChange: (Int?) -> Unit,
    modifier: Modifier = Modifier,
) {
    if (costs.isEmpty() && value == null) return
    // Resolved here: the menu is its own window (ADR-0010).
    val none = stringResource(R.string.transaction_form_none)
    var expanded by remember { mutableStateOf(false) }
    Column(modifier = modifier) {
        ExposedDropdownMenuBox(
            expanded = expanded,
            onExpandedChange = { expanded = it },
        ) {
            OutlinedTextField(
                value = value?.let { id -> costs.find { it.id == id }?.name } ?: none,
                onValueChange = {},
                readOnly = true,
                singleLine = true,
                label = { Text(stringResource(R.string.transaction_form_recurring_cost)) },
                trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded) },
                modifier = Modifier
                    .fillMaxWidth()
                    .menuAnchor(ExposedDropdownMenuAnchorType.PrimaryNotEditable)
                    .testTag("tx-recurring-cost"),
            )
            ExposedDropdownMenu(
                expanded = expanded,
                onDismissRequest = { expanded = false },
            ) {
                DropdownMenuItem(
                    text = { Text(none) },
                    onClick = {
                        onChange(null)
                        expanded = false
                    },
                )
                costs.forEach { cost ->
                    DropdownMenuItem(
                        text = { Text(cost.name) },
                        onClick = {
                            onChange(cost.id)
                            expanded = false
                        },
                    )
                }
            }
        }
        if (value != null && occurrenceDate != null) {
            Text(
                text = stringResource(R.string.transaction_form_pays_occurrence, occurrenceDate),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 4.dp),
            )
        }
    }
}

/**
 * The Recurring Income select an Income carries (web issue #61), the mirror
 * of the Recurring Cost select — and a Transfer whose source is a Contact
 * Wallet (web issue #99 / ADR-0027): money in from a tracked person
 * receives a Recurring Income like an Income does. Expense never renders
 * it, and a Transfer shows it only while the chosen pair qualifies.
 * Picking an income signs the Occurrence the
 * helper names as paid on save: the stored pin when the form is editing the
 * very link already on the row (which must never be reassigned by a mere
 * date edit), else the selected definition's oldest Unpaid Occurrence — the
 * one a new link pays. The None option unlinks (freeing the Occurrence on
 * save). The field is hidden until there is a definition to pick or a link
 * to drop — an empty picker with nothing but None would be noise.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun RecurringIncomeField(
    incomes: List<RecurringIncomeDto>,
    value: Int?,
    occurrenceDate: String?,
    onChange: (Int?) -> Unit,
    modifier: Modifier = Modifier,
) {
    if (incomes.isEmpty() && value == null) return
    // Resolved here: the menu is its own window (ADR-0010).
    val none = stringResource(R.string.transaction_form_none)
    var expanded by remember { mutableStateOf(false) }
    Column(modifier = modifier) {
        ExposedDropdownMenuBox(
            expanded = expanded,
            onExpandedChange = { expanded = it },
        ) {
            OutlinedTextField(
                value = value?.let { id -> incomes.find { it.id == id }?.name } ?: none,
                onValueChange = {},
                readOnly = true,
                singleLine = true,
                label = { Text(stringResource(R.string.transaction_form_recurring_income)) },
                trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded) },
                modifier = Modifier
                    .fillMaxWidth()
                    .menuAnchor(ExposedDropdownMenuAnchorType.PrimaryNotEditable)
                    .testTag("tx-recurring-income"),
            )
            ExposedDropdownMenu(
                expanded = expanded,
                onDismissRequest = { expanded = false },
            ) {
                DropdownMenuItem(
                    text = { Text(none) },
                    onClick = {
                        onChange(null)
                        expanded = false
                    },
                )
                incomes.forEach { income ->
                    DropdownMenuItem(
                        text = { Text(income.name) },
                        onClick = {
                            onChange(income.id)
                            expanded = false
                        },
                    )
                }
            }
        }
        if (value != null && occurrenceDate != null) {
            Text(
                text = stringResource(R.string.transaction_form_pays_occurrence, occurrenceDate),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 4.dp),
            )
        }
    }
}

@Composable
private fun BalancePreview(
    modal: TransactionsViewModel.ModalState,
    wallets: List<WalletDto>,
) {
    val locale = LocalDisplayLocale.current
    val amount = parseAmountInput(modal.amount)
    val editedAmount = modal.editing?.let { parseAmountInput(it.amount) }

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
                    previewLine(source.name, projection.source.before, projection.source.after, locale),
                    previewLine(destination.name, projection.destination.before, projection.destination.after, locale),
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
                lines = listOf(previewLine(wallet.name, projection.before, projection.after, locale)),
                warn = isCashNegativeWarning(wallet, projection.after),
            )
        }
    }
}

private fun previewLine(
    name: String,
    before: java.math.BigDecimal,
    after: java.math.BigDecimal,
    locale: java.util.Locale,
): String =
    "$name: ${Money.formatEuros(before.toPlainString(), locale)} → ${Money.formatEuros(after.toPlainString(), locale)}"

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
                    text = "⚠ " + stringResource(R.string.transaction_form_negative_cash_warning),
                    style = MaterialTheme.typography.bodySmall,
                    color = AMBER_700,
                    modifier = Modifier.padding(top = 4.dp),
                )
            }
        }
    }
}

/**
 * The Add/Change location and "Use my location" button pair (ticket #37):
 * the two keep their natural width inside a FlowRow with 8dp spacing, so
 * they share one row at a dialog's width and a label is never squeezed
 * below one line by a cramped half-row — when the pair does not fit at a
 * larger font scale, whole buttons wrap onto further lines instead of
 * breaking mid-word. As the safety net at extreme scales — where even
 * one button cannot fit its label on a single line — each label centers
 * itself across its lines rather than hugging the left. The open button reads
 * "Add location", or "Change location" once a location is attached; the
 * GPS button reads "Locating…" and disables while the lookup runs. The
 * test tags tx-location-open and tx-location-gps ride the buttons for the
 * location section's UI tests.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun LocationButtons(
    location: LatLng?,
    locating: Boolean,
    onOpenPicker: () -> Unit,
    onUseMyLocation: () -> Unit,
    modifier: Modifier = Modifier,
) {
    FlowRow(
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
        modifier = modifier,
    ) {
        OutlinedButton(
            onClick = onOpenPicker,
            modifier = Modifier.testTag("tx-location-open"),
        shape = RoundedCornerShape(8.dp), contentPadding = PaddingValues(horizontal = 12.dp)) {
            Text(
                text = stringResource(
                    if (location != null) R.string.transaction_form_location_change else R.string.transaction_form_location_add,
                ),
                textAlign = TextAlign.Center,
            )
        }
        OutlinedButton(
            onClick = onUseMyLocation,
            enabled = !locating,
            modifier = Modifier.testTag("tx-location-gps"),
        shape = RoundedCornerShape(8.dp), contentPadding = PaddingValues(horizontal = 12.dp)) {
            Text(
                text = stringResource(
                    if (locating) R.string.transaction_form_location_locating else R.string.transaction_form_location_use_gps,
                ),
                textAlign = TextAlign.Center,
            )
        }
    }
}

/**
 * The Transaction form's Location section (ticket #29), mirroring the web
 * form's block: the attached location's chip — the Place's name when the
 * location carries one, else the coordinates — with the client-built maps
 * link (place_id → name → coordinates, never stored as text) and the
 * Remove action, then the Add/Change location and "Use my location"
 * buttons (the LocationButtons pair, ticket #37) with the locating state
 * and the inline GPS failure line. While the map picker dialog is open
 * the buttons give way to it, like the web's inline picker area.
 */
@Composable
private fun LocationSection(
    location: LatLng?,
    place: Place?,
    locating: Boolean,
    gpsError: String?,
    showingPicker: Boolean,
    onRemove: () -> Unit,
    onUseMyLocation: () -> Unit,
    onOpenPicker: () -> Unit,
    onOpenMapLink: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier.fillMaxWidth()) {
        Text(
            text = stringResource(R.string.transaction_form_location),
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.Medium,
        )
        if (location != null) {
            Surface(
                shape = RoundedCornerShape(12.dp),
                color = MaterialTheme.colorScheme.surfaceVariant,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 4.dp),
            ) {
                Column(modifier = Modifier.padding(start = 12.dp, end = 4.dp, top = 4.dp, bottom = 0.dp)) {
                    Text(
                        text = "📍 ${place?.name ?: formatLocation(location)}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 4.dp, start = 4.dp),
                    )
                    Row(modifier = Modifier.fillMaxWidth()) {
                        TextButton(
                            onClick = { onOpenMapLink(mapLink(location, place)) },
                            contentPadding = ButtonDefaults.TextButtonContentPadding,
                            modifier = Modifier.testTag("tx-location-link"),
                        ) {
                            Text(stringResource(R.string.transaction_form_location_open_maps))
                        }
                        TextButton(
                            onClick = onRemove,
                            colors = ButtonDefaults.textButtonColors(
                                contentColor = RED_600,
                            ),
                            contentPadding = ButtonDefaults.TextButtonContentPadding,
                            modifier = Modifier.testTag("tx-location-remove"),
                        ) {
                            Text(stringResource(R.string.transaction_form_location_remove))
                        }
                    }
                }
            }
        } else {
            Text(
                text = stringResource(R.string.transaction_form_location_none),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 4.dp),
            )
        }
        if (!showingPicker) {
            LocationButtons(
                location = location,
                locating = locating,
                onOpenPicker = onOpenPicker,
                onUseMyLocation = onUseMyLocation,
                modifier = Modifier.padding(top = 8.dp),
            )
            gpsError?.let { error ->
                Text(
                    text = error,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
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
    error: String?,
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
            label = { Text(stringResource(R.string.transaction_form_date)) },
            trailingIcon = { Icon(Icons.Filled.CalendarMonth, contentDescription = null) },
            // Only a Draft that caught no date can leave it empty (ticket
            // #67); the field is disabled so the tap reaches the picker, and
            // the disabled colors carry the error state themselves.
            isError = error != null,
            supportingText = fieldErrorSupportingText(error),
            colors = OutlinedTextFieldDefaults.colors(
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
                .testTag("tx-date"),
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
                    Text(stringResource(R.string.transaction_form_date_ok))
                }
            },
            dismissButton = localized {
                TextButton(onClick = { pickerOpen = false }) { Text(stringResource(R.string.common_cancel)) }
            },
        ) {
            AccountLocaleResources { DatePicker(state = pickerState) }
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
        text = stringResource(R.string.transaction_form_delete),
        style = MaterialTheme.typography.bodyMedium,
        fontWeight = FontWeight.Medium,
    )
    Text(
        text = stringResource(R.string.transaction_form_delete_description),
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(top = 4.dp),
    )
    Button(
        onClick = onDelete,
        enabled = !modal.busy,
        colors = ButtonDefaults.buttonColors(containerColor = Color.Transparent, contentColor = RED_600),
        border = BorderStroke(1.dp, RED_200),
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 12.dp),
    shape = RoundedCornerShape(8.dp), contentPadding = PaddingValues(horizontal = 12.dp)) {
        Text(stringResource(if (modal.deleting) R.string.common_deleting else R.string.transaction_form_delete))
    }
}

private const val MILLIS_PER_DAY = 86_400_000L
