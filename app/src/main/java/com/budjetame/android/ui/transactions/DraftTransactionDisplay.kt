package com.budjetame.android.ui.transactions

import com.budjetame.android.data.api.DraftTransactionDto
import com.budjetame.android.data.api.TransactionType
import com.budjetame.android.util.Money
import java.util.Locale

/**
 * Presentation-only logic for the pinned Drafts block (ADR-0036 in the web
 * repo, ticket #67), ported from the web's draftTransactions.ts: a Draft
 * reads like a ledger row, and differs only in what may still be missing.
 */

/** The row's amount, signed like a ledger row's; unsigned when the type is
 * unknown, and null when the Draft has no amount — the row marks it. */
fun draftSignedAmount(draft: DraftTransactionDto, locale: Locale = Locale.US): String? {
    val amount = Money.formatEuros(draft.amount ?: return null, locale)
    return when (draft.type) {
        TransactionType.EXPENSE -> "-$amount"
        TransactionType.INCOME -> "+$amount"
        else -> amount
    }
}

/** True when the Draft's Wallet side is a Transfer's two legs rather than
 * one Wallet: the type says so, or — type unknown — a leg was caught. */
fun draftIsTransfer(draft: DraftTransactionDto): Boolean {
    if (draft.type != null) return draft.type == TransactionType.TRANSFER
    return draft.source_wallet_id != null || draft.destination_wallet_id != null
}

/** The row's bold line: the Category and the Description when caught, else
 * the type word, else "Draft transaction" when not even the type is. */
fun draftTitle(draft: DraftTransactionDto, categoryName: String?): String {
    val parts = listOfNotNull(categoryName, descriptionText(draft.description))
    if (parts.isNotEmpty()) return parts.joinToString(" · ")
    return when (draft.type) {
        null -> "Draft transaction"
        TransactionType.INCOME -> "Income"
        TransactionType.TRANSFER -> "Transfer"
        else -> "Expense"
    }
}
