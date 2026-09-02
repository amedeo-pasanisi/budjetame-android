package com.budjetame.android.ui.imports

import com.budjetame.android.data.api.ImportRowDto
import com.budjetame.android.data.api.ImportRowStatus
import com.budjetame.android.data.api.ImportRowInput
import com.budjetame.android.data.api.TransactionType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** The Draft's wire-input mapping (ImportViewModel.kt's helpers): which
 * rows are sendable, how a blank description travels (as null — a blank
 * description matches a missing one, ADR-0006), and which rows form an
 * edited row's in-file Duplicate context. */
class ImportRowMappingTest {

    private fun row(
        rowNumber: Int,
        type: TransactionType? = TransactionType.EXPENSE,
        date: String? = "2026-08-01",
        amount: String? = "12.50",
        description: String? = null,
    ) = ImportRowDto(
        row = rowNumber,
        status = ImportRowStatus.ERROR,
        type = type,
        date = date,
        amount = amount,
        description = description,
    )

    @Test
    fun `a row is sendable only with a type, date, and amount`() {
        assertEquals(
            ImportRowInput(row = 2, type = TransactionType.EXPENSE, amount = "12.50", date = "2026-08-01"),
            rowInput(row(2)),
        )
        // A parse-error row may lack any of the three: no key anywhere.
        assertNull(rowInput(row(2, type = null)))
        assertNull(rowInput(row(2, date = null)))
        assertNull(rowInput(row(2, amount = null)))
    }

    @Test
    fun `a blank or whitespace description travels as null, exactly like a missing one`() {
        val blank = rowInput(row(2, description = ""))
        val missing = rowInput(row(2, description = null))
        val whitespace = rowInput(row(2, description = "   "))
        assertEquals(missing, blank)
        assertEquals(missing, whitespace)
        assertNull(blank!!.description)
        // A real description passes through trimmed.
        assertEquals("coffee", rowInput(row(2, description = "  coffee  "))!!.description)
    }

    @Test
    fun `an edited row's context is the sendable rows that precede it, in order`() {
        val rows = listOf(
            row(2, description = "coffee"),
            row(3, date = null), // parse-error row: no key
            row(4, amount = "5.00", description = "  lunch "),
            row(5),
        )
        val earlier = earlierRowInputs(rows, beforeRow = 5)
        assertEquals(listOf(2, 4), earlier.map { it.row })
        assertEquals("coffee", earlier[0].description)
        // The draft rows carry their edits, so the context does too.
        assertEquals("lunch", earlier[1].description)
    }

    @Test
    fun `an income and a transfer row keep their own type on the wire`() {
        assertEquals(
            ImportRowInput(row = 2, type = TransactionType.INCOME, amount = "12.50", date = "2026-08-01"),
            rowInput(row(2, type = TransactionType.INCOME)),
        )
        assertEquals(
            ImportRowInput(row = 2, type = TransactionType.TRANSFER, amount = "12.50", date = "2026-08-01"),
            rowInput(row(2, type = TransactionType.TRANSFER)),
        )
    }
}
