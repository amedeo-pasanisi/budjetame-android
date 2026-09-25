package com.budjetame.android.ui.transactions

import com.budjetame.android.data.api.TransactionDto

/**
 * An in-memory buffer for deleted Transactions (issue #58): holds at most
 * [MAX_SIZE] entries, newest first. When a new entry overflows, the oldest
 * (last in the list) is silently dropped. The buffer is ViewModel-scoped
 * and is lost on process death — the 10-second Snackbar window is the only
 * undo opportunity.
 *
 * Mutating operations are not thread-safe: the ViewModel serialises all
 * accesses through its own coroutine scope.
 */
class UndoBuffer(private val maxSize: Int = MAX_SIZE) {

    private val inner = mutableListOf<TransactionDto>()

    /** Insert [tx] at the front (newest first), dropping the oldest if
     * the buffer would overflow. */
    fun insert(tx: TransactionDto) {
        inner.add(0, tx)
        if (inner.size > maxSize) {
            inner.removeAt(inner.lastIndex)
        }
    }

    /** Remove the entry with [id], if present. No-op when absent. */
    fun remove(id: Int) {
        inner.removeAll { it.id == id }
    }

    /** Remove all entries. */
    fun clear() {
        inner.clear()
    }

    /** Find the entry with [id], or null. */
    fun find(id: Int): TransactionDto? = inner.firstOrNull { it.id == id }

    /** The newest entry, or null when empty. */
    fun newest(): TransactionDto? = inner.firstOrNull()

    /** The oldest entry, or null when empty. */
    fun oldest(): TransactionDto? = inner.lastOrNull()

    /** All entries, newest first. The returned list is a snapshot. */
    fun all(): List<TransactionDto> = inner.toList()

    /** The number of entries currently held. */
    fun size(): Int = inner.size

    companion object {
        const val MAX_SIZE = 3
    }
}