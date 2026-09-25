package com.budjetame.android.ui.transactions

import com.budjetame.android.data.api.TransactionDto
import com.budjetame.android.data.api.TransactionType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test

/**
 * The in-memory Undo buffer (issue #58): max 3 entries, newest first,
 * drop oldest on overflow. The test matrix mirrors the [AmountInputTest]
 * pattern — rows are validated through the public interface alone.
 *
 * Seam: pure unit — the class holds TransactionDtos and is never mocked.
 */
class UndoBufferTest {

    private lateinit var buffer: UndoBuffer

    @Before
    fun setUp() {
        buffer = UndoBuffer()
    }

    @Test
    fun `starts empty`() {
        assertEquals(0, buffer.size())
        assertNull(buffer.newest())
        assertNull(buffer.oldest())
    }

    @Test
    fun `insert stores the transaction as the only entry`() {
        buffer.insert(tx(1))

        assertEquals(1, buffer.size())
        assertEquals(1, buffer.newest()?.id)
        assertEquals(1, buffer.oldest()?.id)
    }

    @Test
    fun `insert adds at the front -- newest first`() {
        buffer.insert(tx(1))
        buffer.insert(tx(2))

        assertEquals(2, buffer.size())
        assertEquals(2, buffer.newest()?.id)
        assertEquals(1, buffer.oldest()?.id)
    }

    @Test
    fun `insert caps at three entries`() {
        buffer.insert(tx(1))
        buffer.insert(tx(2))
        buffer.insert(tx(3))

        assertEquals(3, buffer.size())
        assertEquals(listOf(3, 2, 1), buffer.all().map { it.id })
    }

    @Test
    fun `insert drops the oldest when overflowing`() {
        buffer.insert(tx(1))
        buffer.insert(tx(2))
        buffer.insert(tx(3))
        buffer.insert(tx(4))

        assertEquals(3, buffer.size())
        assertEquals(listOf(4, 3, 2), buffer.all().map { it.id })
    }

    @Test
    fun `insert drops oldest repeatedly on overflow`() {
        buffer.insert(tx(1))
        buffer.insert(tx(2))
        buffer.insert(tx(3))
        buffer.insert(tx(4))
        buffer.insert(tx(5))

        assertEquals(3, buffer.size())
        assertEquals(listOf(5, 4, 3), buffer.all().map { it.id })
    }

    @Test
    fun `remove by id removes only that entry`() {
        buffer.insert(tx(1))
        buffer.insert(tx(2))
        buffer.insert(tx(3))

        buffer.remove(2)

        assertEquals(2, buffer.size())
        assertEquals(listOf(3, 1), buffer.all().map { it.id })
    }

    @Test
    fun `remove by id on non-existent id is a no-op`() {
        buffer.insert(tx(1))

        buffer.remove(999)

        assertEquals(1, buffer.size())
    }

    @Test
    fun `remove by id from empty buffer is a no-op`() {
        buffer.remove(1)

        assertEquals(0, buffer.size())
    }

    @Test
    fun `clear removes all entries`() {
        buffer.insert(tx(1))
        buffer.insert(tx(2))
        buffer.insert(tx(3))

        buffer.clear()

        assertEquals(0, buffer.size())
    }

    @Test
    fun `find by id returns the transaction or null`() {
        buffer.insert(tx(1))
        buffer.insert(tx(2))
        buffer.insert(tx(3))

        assertEquals(2, buffer.find(2)?.id)
        assertNull(buffer.find(999))
    }

    private fun tx(id: Int) = TransactionDto(
        id = id,
        type = TransactionType.EXPENSE,
        amount = "10.00",
        date = "2026-08-01",
        wallet_id = 1,
        created_at = "2026-08-01T10:00:00Z",
    )
}