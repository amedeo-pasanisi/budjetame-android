package com.budjetame.android.ui.undo

import com.budjetame.android.data.api.ApiException
import com.budjetame.android.data.api.TransactionDto
import com.budjetame.android.data.api.TransactionType
import com.budjetame.android.ui.common.english
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The shell's Undo stack (ADR-0031 / ADR-0034 in the web repo, ticket #58)
 * at its own seam: kind-tagged entries pushed by the screens, at most three
 * newest first, each expiring 10 s after its delete on a virtual clock,
 * Dismiss, clear, and the Undo handler that replays an entry by its kind.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class UndoStackTest {

    private fun row(id: Int, description: String? = "Coffee") = TransactionDto(
        id = id,
        type = TransactionType.EXPENSE,
        amount = "3.50",
        date = "2026-08-01",
        wallet_id = 1,
        description = description,
        created_at = "2026-08-01T10:00:00Z",
    )

    private fun deleted(id: Int, description: String? = "Coffee") =
        DeletedEntity.Transaction(row(id, description))

    /** A stack on the test's virtual clock; [replay] answers each Undo. */
    private fun TestScope.stack(
        replay: suspend (DeletedEntity) -> DeletedEntity = { it },
    ): UndoStack = UndoStack(
        scope = backgroundScope,
        clock = { testScheduler.currentTime },
        replay = replay,
    )

    private fun UndoStack.ids(): List<Int> =
        state.value.entries.map { (it.deleted as DeletedEntity.Transaction).row.id }

    @Test
    fun `a pushed delete becomes a kind-tagged entry with its label and a full countdown`() = runTest {
        val undo = stack()

        undo.pushDeleted(deleted(7, "Coffee"))
        runCurrent()

        val entry = undo.state.value.entries.single()
        assertEquals(UndoKind.TRANSACTION, entry.deleted.kind)
        assertEquals("Coffee", entry.deleted.label)
        assertEquals(10, undo.state.value.remainingSeconds(entry))
    }

    @Test
    fun `a blank description leaves the entry without a label`() = runTest {
        val undo = stack()

        undo.pushDeleted(deleted(1, "  "))
        undo.pushDeleted(deleted(2, null))

        assertEquals(listOf(null, null), undo.state.value.entries.map { it.deleted.label })
    }

    @Test
    fun `entries stack newest first and a fourth retires the oldest`() = runTest {
        val undo = stack()

        undo.pushDeleted(deleted(1))
        undo.pushDeleted(deleted(2))
        undo.pushDeleted(deleted(3))
        assertEquals(listOf(3, 2, 1), undo.ids())

        undo.pushDeleted(deleted(4))
        assertEquals(listOf(4, 3, 2), undo.ids())
    }

    @Test
    fun `each entry counts down and expires 10 seconds after its own delete`() = runTest {
        val undo = stack()

        undo.pushDeleted(deleted(1))
        runCurrent()
        advanceTimeBy(4_000)
        runCurrent()
        undo.pushDeleted(deleted(2))
        runCurrent()
        assertEquals(6, undo.state.value.remainingSeconds(undo.state.value.entries.last()))

        advanceTimeBy(5_999)
        runCurrent()
        assertEquals(listOf(2, 1), undo.ids())
        assertEquals(1, undo.state.value.remainingSeconds(undo.state.value.entries.last()))

        advanceTimeBy(1)
        runCurrent()
        assertEquals(listOf(2), undo.ids())
        assertEquals(4, undo.state.value.remainingSeconds(undo.state.value.entries.single()))

        advanceTimeBy(4_000)
        runCurrent()
        assertTrue(undo.state.value.entries.isEmpty())
    }

    @Test
    fun `dismiss removes just that entry`() = runTest {
        val undo = stack()
        undo.pushDeleted(deleted(1))
        undo.pushDeleted(deleted(2))

        undo.dismiss(undo.state.value.entries.last().key)

        assertEquals(listOf(2), undo.ids())
    }

    @Test
    fun `clear forfeits every pending undo`() = runTest {
        val undo = stack()
        undo.pushDeleted(deleted(1))
        undo.pushDeleted(deleted(2))

        undo.clear()

        assertTrue(undo.state.value.entries.isEmpty())
    }

    @Test
    fun `undo replays the held entity and hands the restored one back`() = runTest {
        val replayed = mutableListOf<DeletedEntity>()
        val undo = stack(replay = { replayed += it; it })
        val held = deleted(5, "Rent")
        undo.pushDeleted(held)
        undo.pushDeleted(deleted(6))

        undo.undo(undo.state.value.entries.last().key)
        runCurrent()

        assertEquals(listOf<DeletedEntity>(held), replayed)
        assertEquals(listOf(6), undo.ids())
        assertEquals(held, undo.restored.value?.entity)
    }

    @Test
    fun `a failed undo shows the server's detail on that entry and restores nothing`() = runTest {
        val undo = stack(replay = {
            throw ApiException(422, "The Occurrence was already paid.", message = "422")
        })
        undo.pushDeleted(deleted(5))

        undo.undo(undo.state.value.entries.single().key)
        runCurrent()

        val entry = undo.state.value.entries.single()
        assertEquals("The Occurrence was already paid.", entry.error?.english())
        assertEquals(false, entry.undoing)
        assertNull(undo.restored.value)
    }

    @Test
    fun `a failure without a detail falls back to the generic message`() = runTest {
        val undo = stack(replay = { throw java.io.IOException("offline") })
        undo.pushDeleted(deleted(5))

        undo.undo(undo.state.value.entries.single().key)
        runCurrent()

        assertEquals("Could not undo.", undo.state.value.entries.single().error?.english())
    }

    @Test
    fun `a failure at the end of the window keeps its message readable for a few seconds`() = runTest {
        val answer = CompletableDeferred<DeletedEntity>()
        val undo = stack(replay = { answer.await() })
        undo.pushDeleted(deleted(5))
        runCurrent()

        advanceTimeBy(9_500)
        runCurrent()
        undo.undo(undo.state.value.entries.single().key)
        advanceTimeBy(1_000)
        runCurrent()
        answer.completeExceptionally(ApiException(403, "Wallet not found", message = "403"))
        runCurrent()

        advanceTimeBy(UNDO_ERROR_GRACE_MILLIS - 1)
        runCurrent()
        assertEquals("Wallet not found", undo.state.value.entries.single().error?.english())

        advanceTimeBy(1)
        runCurrent()
        assertTrue(undo.state.value.entries.isEmpty())
    }

    @Test
    fun `an entry whose undo is in flight does not expire under it`() = runTest {
        val answer = CompletableDeferred<DeletedEntity>()
        val undo = stack(replay = { answer.await() })
        val held = deleted(5)
        undo.pushDeleted(held)
        runCurrent()

        advanceTimeBy(9_000)
        runCurrent()
        undo.undo(undo.state.value.entries.single().key)
        runCurrent()
        assertTrue(undo.state.value.entries.single().undoing)

        advanceTimeBy(2_000)
        runCurrent()
        assertEquals(listOf(5), undo.ids())

        answer.complete(held)
        runCurrent()
        assertTrue(undo.state.value.entries.isEmpty())
        assertEquals(held, undo.restored.value?.entity)
    }
}
