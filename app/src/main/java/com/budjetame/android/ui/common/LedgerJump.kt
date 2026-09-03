package com.budjetame.android.ui.common

/**
 * A ledger jump (ADR-0004, web issue #90): a Wallet or Category row's
 * whole-surface tap asks the Transactions tab to open its ledger already
 * filtered to exactly that entity — the previous filters, search, and open
 * Filters panel all reset with the jump, and a Frozen Wallet's history
 * lands read-only. The request is shell state: it is held pending until the
 * Transactions screen applies it (seeding a first-ever visit's initial
 * fetch, or replacing the filters of an already-alive ViewModel) and
 * consumes it exactly once.
 */
sealed interface LedgerJump {
    /** Filter the ledger to one Wallet (its type section row, frozen or
     * not — a frozen one lands on the read-only banner). */
    data class Wallet(val walletId: Int) : LedgerJump

    /** Filter the ledger to one Category (its Expenses or Incomes row). */
    data class Category(val categoryId: Int) : LedgerJump
}
