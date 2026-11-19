package com.budjetame.android.ui.transactions

import com.budjetame.android.data.api.DraftTransactionDto
import com.budjetame.android.data.api.TransactionType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** How a Draft Transaction reads in its pinned row (ticket #67): the same
 * columns as a ledger row, with what the Assistant did not catch missing. */
class DraftTransactionDisplayTest {

    private fun draft(
        type: TransactionType? = TransactionType.EXPENSE,
        amount: String? = "30.00",
        sourceWalletId: Int? = null,
        destinationWalletId: Int? = null,
        description: String? = null,
    ) = DraftTransactionDto(
        id = 1,
        type = type,
        amount = amount,
        source_wallet_id = sourceWalletId,
        destination_wallet_id = destinationWalletId,
        description = description,
        created_at = "2026-11-19T09:00:00Z",
    )

    @Test
    fun `the amount is signed like a ledger row's, unsigned without a type, null when missing`() {
        assertEquals("-€30.00", draftSignedAmount(draft()))
        assertEquals("+€30.00", draftSignedAmount(draft(type = TransactionType.INCOME)))
        assertEquals("€30.00", draftSignedAmount(draft(type = TransactionType.TRANSFER)))
        assertEquals("€30.00", draftSignedAmount(draft(type = null)))
        assertNull(draftSignedAmount(draft(amount = null)))
    }

    @Test
    fun `a draft reads as a transfer by its type or, type unknown, by a caught leg`() {
        assertTrue(draftIsTransfer(draft(type = TransactionType.TRANSFER)))
        assertTrue(draftIsTransfer(draft(type = null, destinationWalletId = 2)))
        assertFalse(draftIsTransfer(draft(type = null)))
        assertFalse(draftIsTransfer(draft(type = TransactionType.EXPENSE, sourceWalletId = 2)))
    }

    @Test
    fun `the title leads with the category and description, else the type word`() {
        assertEquals("Food · Esselunga", draftTitle(draft(description = " Esselunga "), "Food"))
        assertEquals("Esselunga", draftTitle(draft(description = "Esselunga"), null))
        assertEquals("Income", draftTitle(draft(type = TransactionType.INCOME), null))
        assertEquals("Draft transaction", draftTitle(draft(type = null, description = "  "), null))
    }
}
