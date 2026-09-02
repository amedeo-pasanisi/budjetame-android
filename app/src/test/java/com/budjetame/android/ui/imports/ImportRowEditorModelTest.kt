package com.budjetame.android.ui.imports

import com.budjetame.android.data.api.ImportRowInput
import com.budjetame.android.data.api.TransactionType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The Verification row editor's draft rules (ImportRowEditorModel.kt),
 * ported from the web app's ImportRowModal.tsx: the cleaned-value
 * normalization, the mandatory-fields gate on Save, and the wire input's
 * type-shaped fields. */
class ImportRowEditorModelTest {

    @Test
    fun `blank fields clean to null and non-blank ones to trimmed values`() {
        assertNull(cleanedImportField(""))
        assertNull(cleanedImportField("   "))
        assertEquals("Food", cleanedImportField("  Food  "))
    }

    @Test
    fun `save needs a positive amount, a date, and the type's wallets`() {
        val base = canSaveEditedRow(
            type = TransactionType.EXPENSE,
            amount = "12.50",
            date = "2026-08-01",
            wallet = "Checking",
            sourceWallet = "",
            destinationWallet = "",
        )
        assertTrue(base)
        assertFalse(
            canSaveEditedRow(
                TransactionType.EXPENSE, "0", "2026-08-01", "Checking", "", "",
            ),
        )
        assertFalse(
            canSaveEditedRow(
                TransactionType.EXPENSE, "12.50", "", "Checking", "", "",
            ),
        )
        assertFalse(
            canSaveEditedRow(
                TransactionType.EXPENSE, "12.50", "2026-08-01", "  ", "", "",
            ),
        )
    }

    @Test
    fun `a transfer needs two distinct wallets`() {
        assertTrue(
            canSaveEditedRow(
                TransactionType.TRANSFER, "12.50", "2026-08-01", "", "Checking", "Cash",
            ),
        )
        assertFalse(
            canSaveEditedRow(
                TransactionType.TRANSFER, "12.50", "2026-08-01", "", "Checking", "",
            ),
        )
        // The web's gate compares the raw names (case-sensitively): two
        // spellings of one Wallet pass the gate and the re-validation then
        // rejects the row — the resolution is the backend's.
        assertTrue(
            canSaveEditedRow(
                TransactionType.TRANSFER, "12.50", "2026-08-01", "", "Checking", "checking",
            ),
        )
        assertFalse(
            canSaveEditedRow(
                TransactionType.TRANSFER, "12.50", "2026-08-01", "", "Checking", "Checking",
            ),
        )
    }

    @Test
    fun `an expense or income ignores the transfer legs on save`() {
        assertTrue(
            canSaveEditedRow(
                TransactionType.INCOME, "12.50", "2026-08-01", "Checking", "Cash", "Cash",
            ),
        )
    }

    @Test
    fun `the wire input carries only the type's fields, cleaned`() {
        val input = editedRowInput(
            rowNumber = 4,
            type = TransactionType.EXPENSE,
            amount = " 12.50 ",
            date = "2026-08-01",
            wallet = "Checking",
            sourceWallet = "Cash",
            destinationWallet = "Cash",
            category = "Food",
            description = "  coffee  ",
            latitude = "45.46",
            longitude = "",
        )
        assertEquals(
            ImportRowInput(
                row = 4,
                type = TransactionType.EXPENSE,
                amount = "12.50",
                date = "2026-08-01",
                wallet = "Checking",
                category = "Food",
                description = "coffee",
                latitude = "45.46",
            ),
            input,
        )
    }

    @Test
    fun `a transfer sends its legs and never a wallet or category`() {
        val input = editedRowInput(
            rowNumber = 6,
            type = TransactionType.TRANSFER,
            amount = "12.50",
            date = "2026-08-01",
            wallet = "Checking",
            sourceWallet = "Cash",
            destinationWallet = "Checking",
            category = "Food",
            description = "",
            latitude = "",
            longitude = "",
        )
        assertNull(input.wallet)
        assertNull(input.category)
        assertEquals("Cash", input.source_wallet)
        assertEquals("Checking", input.destination_wallet)
        // A blank description travels as null — blank matches missing
        // (ADR-0006).
        assertNull(input.description)
    }

    @Test
    fun `a row with no type opens the editor as an expense`() {
        assertEquals(TransactionType.EXPENSE, rowEditorStartType(null))
        assertEquals(TransactionType.EXPENSE, rowEditorStartType(TransactionType.EXPENSE))
        assertEquals(TransactionType.INCOME, rowEditorStartType(TransactionType.INCOME))
        assertEquals(TransactionType.TRANSFER, rowEditorStartType(TransactionType.TRANSFER))
    }
}
