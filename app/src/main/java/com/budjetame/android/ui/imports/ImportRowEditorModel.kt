package com.budjetame.android.ui.imports

import com.budjetame.android.data.api.ImportRowInput
import com.budjetame.android.data.api.TransactionType
import com.budjetame.android.ui.transactions.parseAmount

/**
 * The Verification row editor's draft rules (ticket #26, web issue #46),
 * ported from the web app's ImportRowModal.tsx: the cleaned-value
 * normalization (blank fields travel as null — a blank description matches
 * a missing one, ADR-0006), the mandatory-fields gate on Save, and the
 * wire input built from the edited fields. The editor edits *names*, not
 * resolved entities: the row's Wallet and Category are names the
 * re-validation endpoint resolves server-side. Pure, so the fiddly bits get
 * direct JVM tests.
 */

/** A blank or whitespace-only field travels as null, like the Preview's
 * resolution does (the backend treats it as missing). */
fun cleanedImportField(value: String): String? = value.trim().ifEmpty { null }

/**
 * The mandatory-fields gate on Save (the web editor's `canSave`): a
 * strictly positive amount, a date, and the Wallet(s) the type needs — a
 * Transfer needs two distinct Wallets. The row's Wallet fields are *names*,
 * so a set field may still name an unknown Wallet — that is what the
 * re-validation decides, not this gate.
 */
fun canSaveEditedRow(
    type: TransactionType,
    amount: String,
    date: String,
    wallet: String,
    sourceWallet: String,
    destinationWallet: String,
): Boolean {
    if (date.isEmpty() || parseAmount(amount) == null) return false
    return if (type == TransactionType.TRANSFER) {
        cleanedImportField(sourceWallet) != null &&
            cleanedImportField(destinationWallet) != null &&
            cleanedImportField(sourceWallet) != cleanedImportField(destinationWallet)
    } else {
        cleanedImportField(wallet) != null
    }
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
