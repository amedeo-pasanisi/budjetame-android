package com.budjetame.android.ui.common

/**
 * A ledger jump (ADR-0004, web issue #90, extended to the Recurring cards
 * by web ADR-0026 / ticket #46): a Wallet, Category, or Recurring
 * definition row's whole-surface tap asks the Transactions tab to open its
 * ledger already filtered to exactly that entity — the previous filters,
 * search, and open Filters panel all reset with the jump, and a Frozen
 * Wallet's history lands read-only. The request is shell state: it is held
 * pending until the Transactions screen applies it (seeding a first-ever
 * visit's initial fetch, or replacing the filters of an already-alive
 * ViewModel) and consumes it exactly once.
 */
sealed interface LedgerJump {
    /** Filter the ledger to one Wallet (its type section row, frozen or
     * not — a frozen one lands on the read-only banner). */
    data class Wallet(val walletId: Int) : LedgerJump

    /** Filter the ledger to one Category (its Expenses or Incomes row). */
    data class Category(val categoryId: Int) : LedgerJump

    /** Filter the ledger to one Recurring Cost — its linked Transactions
     * (the Recurring screen's card main tap, web ADR-0026). A Recurring
     * Cost and a Recurring Income may share an id, so the kinds stay
     * separate: each maps to its own recurring filter kind. */
    data class RecurringCost(val recurringCostId: Int) : LedgerJump

    /** Filter the ledger to one Recurring Income — its linked Transactions
     * (the Recurring screen's card main tap, web ADR-0026). */
    data class RecurringIncome(val recurringIncomeId: Int) : LedgerJump
}
