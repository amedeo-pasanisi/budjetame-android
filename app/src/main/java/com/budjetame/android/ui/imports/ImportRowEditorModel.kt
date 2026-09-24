package com.budjetame.android.ui.imports

import com.budjetame.android.data.api.ImportRowDto
import com.budjetame.android.data.api.ImportRowInput
import com.budjetame.android.data.api.TransactionType
import com.budjetame.android.data.api.WalletType
import com.budjetame.android.ui.transactions.NON_CONTACT_WALLET_TYPES
import com.budjetame.android.ui.transactions.WalletFieldTarget
import com.budjetame.android.ui.validation.FieldErrors
import com.budjetame.android.ui.validation.FieldKey
import com.budjetame.android.ui.validation.Messages
import com.budjetame.android.ui.validation.amountErrorMessage
import com.budjetame.android.ui.validation.fieldErrors
import com.budjetame.android.ui.transactions.parseAmount

/**
 * The Verification row editor's draft rules (ticket #26, web issue #46),
 * ported from the web app's ImportRowModal.tsx + ImportEntitySelect.tsx:
 * the cleaned-value normalization (blank fields travel as null — a blank
 * description matches a missing one, ADR-0006), the mandatory-fields gate
 * on Save, the wire input built from the edited fields, and the inline
 * entity-creation rules ticket #27 adds (the sentinel's prefill, the
 * eligibility lock, and the re-validation matching). The editor edits
 * *names*, not resolved entities: the row's Wallet and Category are names
 * the re-validation endpoint resolves server-side. Pure, so the fiddly
 * bits get direct JVM tests.
 */

/** A blank or whitespace-only field travels as null, like the Preview's
 * resolution does (the backend treats it as missing). */
fun cleanedImportField(value: String): String? = value.trim().ifEmpty { null }

/**
 * Validate the Import row editor's fields (ADR-0009): returns a
 * field-key → message map for every broken rule, or an empty map when
 * the row is valid and the Save proceeds. Validation never gates the Save
 * button — Save is always clickable except while an operation is in
 * flight, never because of input.
 */
fun validateImportRow(
    type: TransactionType,
    amount: String,
    date: String,
    wallet: String,
    sourceWallet: String,
    destinationWallet: String,
): FieldErrors {
    val errors = mutableMapOf<String, String>()

    // Amount
    amountErrorMessage(amount)?.let { errors[FieldKey.AMOUNT] = it }

    // Date
    if (date.isEmpty()) {
        errors[FieldKey.DATE] = Messages.DATE_REQUIRED
    }

    // Wallet(s)
    if (type == TransactionType.TRANSFER) {
        if (cleanedImportField(sourceWallet) == null) {
            errors[FieldKey.SOURCE_WALLET] = Messages.TRANSFER_SOURCE_REQUIRED
        }
        if (cleanedImportField(destinationWallet) == null) {
            errors[FieldKey.DESTINATION_WALLET] = Messages.TRANSFER_DESTINATION_REQUIRED
        }
        if (cleanedImportField(sourceWallet) != null && cleanedImportField(destinationWallet) != null &&
            cleanedImportField(sourceWallet) == cleanedImportField(destinationWallet)
        ) {
            errors[FieldKey.SOURCE_WALLET] = Messages.TRANSFER_DISTINCT
        }
    } else {
        if (cleanedImportField(wallet) == null) {
            errors[FieldKey.WALLET] = Messages.WALLET_REQUIRED
        }
    }

    return errors
}

/**
 * The wire input for an edited row: the fields the type can carry, cleaned —
 * a Transfer sends the two legs and never a Wallet or Category (the backend
 * rejects a mismatched shape), an Expense/Income sends its Wallet (plus an
 * optional Category); a blank Description, latitude, or longitude is sent
 * as null.
 */
fun editedRowInput(
    rowNumber: Int,
    type: TransactionType,
    amount: String,
    date: String,
    wallet: String,
    sourceWallet: String,
    destinationWallet: String,
    category: String,
    description: String,
    latitude: String,
    longitude: String,
): ImportRowInput {
    val isTransfer = type == TransactionType.TRANSFER
    return ImportRowInput(
        row = rowNumber,
        type = type,
        amount = amount.trim(),
        date = date,
        wallet = if (isTransfer) null else cleanedImportField(wallet),
        source_wallet = if (isTransfer) cleanedImportField(sourceWallet) else null,
        destination_wallet = if (isTransfer) cleanedImportField(destinationWallet) else null,
        category = if (isTransfer) null else cleanedImportField(category),
        description = cleanedImportField(description),
        latitude = cleanedImportField(latitude),
        longitude = cleanedImportField(longitude),
    )
}

/** The type picker's starting value for a row with no type (a parse-error
 * row): the web editor defaults it to Expense. */
fun rowEditorStartType(type: TransactionType?): TransactionType = when (type) {
    TransactionType.TRANSFER, TransactionType.INCOME -> type
    else -> TransactionType.EXPENSE
}

/**
 * The create form's prefill when an editor select's inline-create sentinel
 * is picked (the web ImportEntitySelect's `pending` rule): the field's
 * current name, trimmed, when it resolves to no existing option — the
 * missing name from the file, carried into the create form so the user
 * confirms it rather than retyping it — else '' (a blank field or one
 * already naming an existing entity starts the create form empty).
 */
fun importSentinelPrefill(value: String, options: List<Pair<String, String>>): String {
    val trimmed = value.trim()
    if (trimmed.isEmpty()) return ""
    val resolved = options.any { (name, _) -> name.equals(trimmed, ignoreCase = true) }
    return if (resolved) "" else trimmed
}

/**
 * The Wallet types an inline row-editor "New wallet…" may create
 * (ADR-0013/0017, web ImportScreen's eligibility lock): the Expense/Income
 * row's Wallet field never creates a Contact Wallet — the editor's Wallet
 * select never offers one (Contact Wallets move money only via Transfers)
 * — so its create form locks to Checking/Credit Card/Cash; a Transfer's
 * From/To, where Contact Wallets belong, are unrestricted.
 */
fun importEditorWalletCreateAllowedTypes(target: WalletFieldTarget): Set<WalletType>? =
    if (target == WalletFieldTarget.WALLET) NON_CONTACT_WALLET_TYPES else null

/** True when the row's wallet-kind field — its Wallet, or a Transfer's
 * source/destination leg — case-insensitively equals the created Wallet's
 * name: the problem rows that waited on that name (web issue #78). */
fun rowReferencesWallet(row: ImportRowDto, name: String): Boolean {
    val needle = name.trim().lowercase()
    fun matches(value: String?) = value != null && value.trim().lowercase() == needle
    return matches(row.wallet) ||
        matches(row.source_wallet) ||
        matches(row.destination_wallet)
}

/** True when the row's Category field case-insensitively equals the created
 * Category's name (web issue #78), the category mirror. */
fun rowReferencesCategory(row: ImportRowDto, name: String): Boolean {
    val needle = name.trim().lowercase()
    return row.category != null && row.category.trim().lowercase() == needle
}
