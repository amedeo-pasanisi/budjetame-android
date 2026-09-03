package com.budjetame.android.ui.transactions

import com.budjetame.android.data.api.RecurringCostDto
import com.budjetame.android.data.api.RecurringIncomeDto
import com.budjetame.android.data.api.TransactionDto
import com.budjetame.android.data.api.TransactionType
import com.budjetame.android.data.api.WalletDto
import com.budjetame.android.util.Money

/**
 * Presentation-only logic for the ledger rows and the filter chrome
 * (ticket #19, web issue #92 / ticket #35), ported from the web app's
 * transactions.ts + TransactionsScreen: the description-led row title, the
 * signed amount, the frozen-wallet read-only determination (the edit/delete
 * entry points ticket #20 wires honor it), the filter options' labels, and
 * the filtered chips line's chips.
 */

/** Expense/Income show signs; a Transfer and an Opening Balance never do. */
fun signedAmount(transaction: TransactionDto): String = when (transaction.type) {
    TransactionType.EXPENSE -> "-€${transaction.amount}"
    TransactionType.INCOME -> "+€${transaction.amount}"
    else -> "€${transaction.amount}"
}

/** The Description as display text: trimmed, or null when missing or blank —
 * a whitespace-only Description counts as blank (CONTEXT.md). */
fun descriptionText(description: String?): String? {
    val trimmed = description?.trim().orEmpty()
    return trimmed.ifEmpty { null }
}

/**
 * The ledger row's bold identifying line: an Opening Balance keeps its fixed
 * label; otherwise the Category leads (it implies the type) — an Expense
 * with no Category is labeled "Uncategorized" (ticket #19) — then the whole
 * Description, falling back to the type word only when neither exists.
 */
fun transactionTitle(transaction: TransactionDto, categoryName: String?): String {
    if (transaction.type == TransactionType.OPENING_BALANCE) return "Opening balance"
    val description = descriptionText(transaction.description)
    val parts = buildList {
        when {
            categoryName != null -> add(categoryName)
            transaction.type == TransactionType.EXPENSE -> add("Uncategorized")
        }
        if (description != null) add(description)
    }
    if (parts.isNotEmpty()) return parts.joinToString(" · ")
    return when (transaction.type) {
        TransactionType.EXPENSE -> "Expense"
        TransactionType.INCOME -> "Income"
        else -> "Transfer"
    }
}

fun hasLocation(transaction: TransactionDto): Boolean =
    transaction.latitude != null && transaction.longitude != null

/**
 * True when the Transaction sits on a Frozen Wallet and is therefore
 * read-only. A Transfer is frozen when either leg is frozen — a Wallet can
 * freeze after the Transfer exists, so the check must cover both legs. An
 * unknown Wallet counts as frozen (no entry points into absent data).
 */
fun isOnFrozenWallet(transaction: TransactionDto, wallets: List<WalletDto>): Boolean {
    if (transaction.type == TransactionType.TRANSFER) {
        val source = wallets.find { it.id == transaction.source_wallet_id }
        val destination = wallets.find { it.id == transaction.destination_wallet_id }
        return source == null || source.frozen || destination == null || destination.frozen
    }
    val wallet = wallets.find { it.id == transaction.wallet_id }
    return wallet == null || wallet.frozen
}

/**
 * Whether the row offers the edit/delete entry points (ticket #20 wires
 * them): an Opening Balance never does, and neither does a Transaction on a
 * frozen Wallet — including either leg of a Transfer.
 */
fun isEditable(transaction: TransactionDto, wallets: List<WalletDto>): Boolean =
    transaction.type != TransactionType.OPENING_BALANCE && !isOnFrozenWallet(transaction, wallets)

/**
 * The filtered line's chip keys (web issue #92, ticket #35): the five
 * panel filters collapse into at most four chips — the date range's From
 * and To merge into one — and each key routes its chip's ✕ to the single
 * filter (or the date pair) it clears.
 */
enum class FilterChipKey { WALLET, CATEGORY, DATES, RECURRING }

/** One filtered-line chip: which filter it stands for and its label. */
data class FilterChipSpec(val key: FilterChipKey, val label: String)

/**
 * The merged date chip's label (web issue #92): "From X" and "To Y"
 * alone, "X – Y" when both bounds are set (one chip, one ✕, one
 * date-range filter), null when neither is. The dates are the filter's
 * raw API-day strings, exactly as the web renders its date inputs'
 * values.
 */
fun filterDateChipLabel(fromDate: String?, toDate: String?): String? = when {
    fromDate != null && toDate != null -> "$fromDate – $toDate"
    fromDate != null -> "From $fromDate"
    toDate != null -> "To $toDate"
    else -> null
}

/**
 * The filtered chips line's chips (web issue #92, ticket #35), in the
 * web's order — wallet, category, the merged date range, the recurring
 * definition — labelled with the plain names read from the already-loaded
 * lists (the balance/frozen marks belong to the panel options). A set
 * filter whose entity the lists do not know (still loading, or vanished)
 * shows no chip — the line's visibility is the five filters' own count,
 * like the web.
 */
fun activeFilterChips(state: TransactionsViewModel.UiState): List<FilterChipSpec> = buildList {
    state.filterWalletId?.let { id ->
        state.wallets.find { it.id == id }?.name?.let { name ->
            add(FilterChipSpec(FilterChipKey.WALLET, name))
        }
    }
    state.filterCategoryId?.let { id ->
        state.categories.find { it.id == id }?.name?.let { name ->
            add(FilterChipSpec(FilterChipKey.CATEGORY, name))
        }
    }
    filterDateChipLabel(state.filterFromDate, state.filterToDate)?.let { label ->
        add(FilterChipSpec(FilterChipKey.DATES, label))
    }
    state.filterRecurring?.let { selection ->
        // Each kind resolves against its own list — a Recurring Cost and a
        // Recurring Income may share a name — the same lookup the
        // recurring select's collapsed field does.
        val name = when (selection.kind) {
            RecurringFilterKind.COST -> state.recurringCosts.find { it.id == selection.id }?.name
            RecurringFilterKind.INCOME -> state.recurringIncomes.find { it.id == selection.id }?.name
        }
        if (name != null) add(FilterChipSpec(FilterChipKey.RECURRING, name))
    }
}

/** The Wallet name for a row's subtitle; unknown → "Frozen wallet" (the web app). */
fun walletName(wallets: List<WalletDto>, walletId: Int?): String =
    walletId?.let { id -> wallets.find { it.id == id }?.name } ?: "Frozen wallet"

/** The row's wallet line: "Source → Destination" for a Transfer, else the Wallet. */
fun walletLabel(transaction: TransactionDto, wallets: List<WalletDto>): String =
    if (transaction.type == TransactionType.TRANSFER) {
        "${walletName(wallets, transaction.source_wallet_id)} → ${walletName(wallets, transaction.destination_wallet_id)}"
    } else {
        walletName(wallets, transaction.wallet_id)
    }

/** A Wallet option in the filter bar: "Name · Frozen (€balance)" — the
 * "· Frozen" mark is the filter bar's promise to include frozen wallets. */
fun walletFilterLabel(wallet: WalletDto): String {
    val name = if (wallet.frozen) "${wallet.name} · Frozen" else wallet.name
    return "$name (${Money.formatEuros(wallet.balance)})"
}

/** A Category option in the filter bar: the icon leads when set. */
fun categoryFilterLabel(name: String, icon: String?): String =
    if (icon.isNullOrBlank()) name else "$icon $name"

/**
 * The Recurring filter's collapsed field text (web issue #86): "All
 * transactions" when no definition is picked — the web select's first
 * option — else the picked definition's name, resolved against the list of
 * its own kind (a Recurring Cost and a Recurring Income may share a name).
 * A pick whose definition is gone from the refreshed lists reads empty
 * (the pick itself is kept, like the web's held select value, until the
 * user clears it).
 */
fun recurringFilterLabel(
    selection: RecurringFilter?,
    costs: List<RecurringCostDto>,
    incomes: List<RecurringIncomeDto>,
): String {
    if (selection == null) return "All transactions"
    // Each kind resolves against its own list: the two DTO types share no
    // common id-holding interface, so the lookup branches by kind.
    return when (selection.kind) {
        RecurringFilterKind.COST -> costs.find { it.id == selection.id }?.name
        RecurringFilterKind.INCOME -> incomes.find { it.id == selection.id }?.name
    } ?: ""
}
