package com.budjetame.android.ui.undo

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.budjetame.android.R
import com.budjetame.android.data.api.RecurringCostDto
import com.budjetame.android.data.api.RecurringIncomeDto
import com.budjetame.android.data.api.TransactionDto
import com.budjetame.android.data.api.WalletDto
import com.budjetame.android.data.recurringcost.RecurringCostGateway
import com.budjetame.android.data.recurringincome.RecurringIncomeGateway
import com.budjetame.android.data.transaction.TransactionGateway
import com.budjetame.android.data.wallet.WalletGateway
import com.budjetame.android.ui.common.UiText
import com.budjetame.android.ui.common.serverMessageOr
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.flow.updateAndGet
import kotlinx.coroutines.launch

/** The kinds of deletion the Undo stack can reverse (web ADR-0034). */
enum class UndoKind { TRANSACTION, WALLET, RECURRING_COST, RECURRING_INCOME }

/**
 * A deleted entity tagged with its kind: the replay payload the kind's
 * undo endpoint takes back (web ADR-0031 / ADR-0034). The payload is the
 * row the client already held when the user deleted it for a Transaction
 * (its delete answers only the Cash warning), and the row the delete
 * answered for a Wallet or a Recurring definition.
 */
sealed interface DeletedEntity {
    val kind: UndoKind

    /** The entity's id, unique within its kind. */
    val id: Int

    /** The headline the entry opens with (the web's undoToast copy). */
    val headline: UiText

    /** What the entry names beside its kind's headline, or null. */
    val label: String?

    /** A deleted Transaction: replayed to `POST /transactions/undo`, it
     * comes back with its original id, date and Recurring pin. */
    data class Transaction(val row: TransactionDto) : DeletedEntity {
        override val kind: UndoKind get() = UndoKind.TRANSACTION
        override val id: Int get() = row.id
        override val headline: UiText get() = UiText.of(R.string.undo_transaction_deleted)
        override val label: String? get() = row.description?.takeIf { it.isNotBlank() }
    }

    /** A deleted Wallet: replayed to `POST /wallets/undo`, it comes back
     * with its original id, still frozen. */
    data class Wallet(val row: WalletDto) : DeletedEntity {
        override val kind: UndoKind get() = UndoKind.WALLET
        override val id: Int get() = row.id
        override val headline: UiText get() = UiText.of(R.string.undo_wallet_deleted)
        override val label: String get() = row.name
    }

    /** A deleted Recurring Cost: replayed to `POST /recurring-costs/undo`,
     * it comes back with its original id, still frozen with its freeze
     * date. */
    data class RecurringCost(val row: RecurringCostDto) : DeletedEntity {
        override val kind: UndoKind get() = UndoKind.RECURRING_COST
        override val id: Int get() = row.id
        override val headline: UiText get() = UiText.of(R.string.undo_recurring_cost_deleted)
        override val label: String get() = row.name
    }

    /** A deleted Recurring Income: the Recurring Cost's mirror. */
    data class RecurringIncome(val row: RecurringIncomeDto) : DeletedEntity {
        override val kind: UndoKind get() = UndoKind.RECURRING_INCOME
        override val id: Int get() = row.id
        override val headline: UiText get() = UiText.of(R.string.undo_recurring_income_deleted)
        override val label: String get() = row.name
    }
}

/** Where a screen reports a delete: the entity enters the shell's Undo
 * stack and its entry appears on every tab. */
fun interface UndoSink {
    fun pushDeleted(deleted: DeletedEntity)

    companion object {
        /** A sink that drops every delete — a screen rendered without the
         * shell (tests, previews). */
        val None = UndoSink { }
    }
}

/** One pending undo opportunity. */
data class UndoEntry(
    val deleted: DeletedEntity,
    /** The clock time of the delete; the window runs from here. */
    val createdAtMillis: Long,
    /** True while this entry's Undo request is in flight. */
    val undoing: Boolean = false,
    /** The last failed Undo's message (the server's `detail` through the
     * error catalogue when it had one), shown on the entry; null = none. */
    val error: UiText? = null,
) {
    /** The entry's identity: ids are unique only within a kind. */
    val key: String get() = keyOf(deleted)

    val deadlineMillis: Long get() = createdAtMillis + UNDO_WINDOW_MILLIS
}

/** The stack as the shell renders it: entries newest first, and the clock
 * reading the countdowns are computed against. */
data class UndoState(
    val entries: List<UndoEntry> = emptyList(),
    val nowMillis: Long = 0L,
) {
    /** Whole seconds left in [entry]'s window, rounded up: 10 at the
     * delete, 1 in its last second. */
    fun remainingSeconds(entry: UndoEntry): Int {
        val left = entry.deadlineMillis - nowMillis
        return if (left <= 0) 0 else ((left + 999) / 1000).toInt()
    }
}

/** What an Undo brought back. `seq` makes every restore a new value, so
 * two undos of the same entity in a row both reach the owning screen. */
data class RestoredEntity(val entity: DeletedEntity, val seq: Int)

/** At most this many entries are pending at once, across every kind. */
const val MAX_UNDO = 3

/** The undo window. */
const val UNDO_WINDOW_MILLIS = 10_000L

/** The message a failed Undo shows when the server gave no `detail`. */
val UNDO_ERROR: UiText = UiText.of(R.string.undo_error)

/** A failed Undo's entry stays at least this long after the failure, so
 * its message can be read even when the window was about to close. */
const val UNDO_ERROR_GRACE_MILLIS = 3_000L

private fun keyOf(deleted: DeletedEntity): String = "${deleted.kind}:${deleted.id}"

/**
 * The app-wide Undo stack (web ADR-0031, lifted to the app shell by web
 * ADR-0034; ticket #58): one in-memory stack of deleted entities, at most
 * [MAX_UNDO] newest first, each with its own [UNDO_WINDOW_MILLIS] window.
 * Screens only report what they deleted ([pushDeleted]); the shell owns
 * the stack, renders it on every tab, and runs the Undo handler, which
 * replays an entry through its kind's undo endpoint ([replay] dispatches
 * by kind). A success removes the entry and publishes the restored entity
 * on [restored] — the write's data-version bump revalidates every screen
 * (ADR-0002); a failure leaves the entry with its message and restores
 * nothing (the server's undo is all-or-nothing). [clear] forfeits every
 * pending undo — a Restore (ticket #60) replaces the whole state.
 *
 * The stack is lost on process death: accepted for a 10-second window.
 */
class UndoStack(
    private val scope: CoroutineScope,
    private val clock: () -> Long,
    private val replay: suspend (DeletedEntity) -> DeletedEntity,
) : UndoSink {

    private val _state = MutableStateFlow(UndoState(nowMillis = clock()))
    val state: StateFlow<UndoState> = _state.asStateFlow()

    private val _restored = MutableStateFlow<RestoredEntity?>(null)

    /** The entity the last successful Undo restored, or null. */
    val restored: StateFlow<RestoredEntity?> = _restored.asStateFlow()

    private var ticker: Job? = null
    private var seq = 0

    /** Report a delete: the entity's entry goes on top; a fourth pending
     * entry retires the oldest. Deleting the same entity again (after an
     * Undo brought it back) replaces its old entry. */
    override fun pushDeleted(deleted: DeletedEntity) {
        val now = clock()
        val key = keyOf(deleted)
        _state.update { state ->
            val rest = state.entries.filterNot { it.key == key }
            UndoState(
                entries = (listOf(UndoEntry(deleted, now)) + rest).take(MAX_UNDO),
                nowMillis = now,
            )
        }
        // Restart the ticker so the new entry's seconds tick on its own
        // boundaries.
        startTicker()
    }

    /** Undo the entry with [key]: replay it through its kind's endpoint. */
    fun undo(key: String) {
        val entry = _state.value.entries.firstOrNull { it.key == key } ?: return
        if (entry.undoing) return
        updateEntry(key) { it.copy(undoing = true, error = null) }
        scope.launch {
            try {
                val back = replay(entry.deleted)
                _state.update { state -> state.copy(entries = state.entries.filterNot { it.key == key }) }
                seq += 1
                _restored.value = RestoredEntity(back, seq)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                val message = failure.serverMessageOr(UNDO_ERROR)
                // A failure near or past the window's end still leaves
                // its message on screen long enough to read.
                val readableFrom = clock() + UNDO_ERROR_GRACE_MILLIS - UNDO_WINDOW_MILLIS
                updateEntry(key) {
                    it.copy(
                        undoing = false,
                        error = message,
                        createdAtMillis = maxOf(it.createdAtMillis, readableFrom),
                    )
                }
                startTicker()
            }
        }
    }

    /** Dismiss the entry with [key]: its undo is forfeited. */
    fun dismiss(key: String) {
        _state.update { state -> state.copy(entries = state.entries.filterNot { it.key == key }) }
    }

    /** Forfeit every pending undo (a Restore replaced the whole state). */
    fun clear() {
        ticker?.cancel()
        ticker = null
        _state.update { UndoState(nowMillis = clock()) }
    }

    private fun updateEntry(key: String, change: (UndoEntry) -> UndoEntry) {
        _state.update { state ->
            state.copy(entries = state.entries.map { if (it.key == key) change(it) else it })
        }
    }

    /** One loop while anything is pending: re-read the clock, drop the
     * entries whose window closed (never one whose Undo is in flight),
     * and sleep until the next whole second of any entry's countdown. */
    private fun startTicker() {
        ticker?.cancel()
        ticker = scope.launch {
            while (true) {
                val now = clock()
                val state = _state.updateAndGet { current ->
                    UndoState(
                        entries = current.entries.filter { it.undoing || it.deadlineMillis > now },
                        nowMillis = now,
                    )
                }
                if (state.entries.isEmpty()) break
                delay(state.entries.minOf { nextTick(it.deadlineMillis - now) })
            }
        }
    }

    private fun nextTick(untilDeadline: Long): Long {
        if (untilDeadline <= 0) return 1000
        val rest = untilDeadline % 1000
        return if (rest == 0L) 1000 else rest
    }
}

/** The Undo handler's dispatch by kind: each kind's undo endpoint
 * re-inserts the very same entity and answers it. */
fun undoReplayer(
    transactions: TransactionGateway,
    wallets: WalletGateway,
    recurringCosts: RecurringCostGateway,
    recurringIncomes: RecurringIncomeGateway,
): suspend (DeletedEntity) -> DeletedEntity = { deleted ->
    when (deleted) {
        is DeletedEntity.Transaction ->
            DeletedEntity.Transaction(transactions.undoTransaction(deleted.row))
        is DeletedEntity.Wallet ->
            DeletedEntity.Wallet(wallets.undoWalletDelete(deleted.row))
        is DeletedEntity.RecurringCost ->
            DeletedEntity.RecurringCost(recurringCosts.undoRecurringCostDelete(deleted.row))
        is DeletedEntity.RecurringIncome ->
            DeletedEntity.RecurringIncome(recurringIncomes.undoRecurringIncomeDelete(deleted.row))
    }
}

/** Holds the shell's [UndoStack] in the Activity's ViewModel store: it
 * survives tab switches and configuration changes, and goes with the
 * store on sign-out (ADR-0003). */
class UndoStackViewModel(
    transactions: TransactionGateway,
    wallets: WalletGateway,
    recurringCosts: RecurringCostGateway,
    recurringIncomes: RecurringIncomeGateway,
) : ViewModel() {
    val stack = UndoStack(
        scope = viewModelScope,
        clock = System::currentTimeMillis,
        replay = undoReplayer(transactions, wallets, recurringCosts, recurringIncomes),
    )
}
