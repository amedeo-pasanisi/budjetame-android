package com.budjetame.android.ui.imports

import com.budjetame.android.data.api.ImportRowDto
import com.budjetame.android.data.api.ImportRowInput
import com.budjetame.android.data.api.ImportRowStatus
import com.budjetame.android.data.api.TransactionType
import com.budjetame.android.data.api.WalletType
import com.budjetame.android.ui.transactions.WalletFieldTarget
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The Verification row editor's draft rules (ImportRowEditorModel.kt),
 * ported from the web app's ImportRowModal.tsx + ImportEntitySelect.tsx:
 * the cleaned-value normalization, the Field Errors Save reveals, the
 * wire input's type-shaped fields, and the inline entity-creation rules
 * (ticket #27): the sentinel's prefill, the Wallet eligibility lock, and
 * the re-validation matching. */
class ImportRowEditorModelTest {

    @Test
    fun `blank fields clean to null and non-blank ones to trimmed values`() {
        assertNull(cleanedImportField(""))
        assertNull(cleanedImportField("   "))
        assertEquals("Food", cleanedImportField("  Food  "))
    }

    /** An edited row as the editor builds it, valid unless overridden. */
    private fun edited(
        type: TransactionType = TransactionType.EXPENSE,
        amount: String = "12.50",
        date: String = "2026-08-01",
        wallet: String = "Checking",
        sourceWallet: String = "",
        destinationWallet: String = "",
    ): ImportRowInput = editedRowInput(
        rowNumber = 4,
        type = type,
        amount = amount,
        date = date,
        wallet = wallet,
        sourceWallet = sourceWallet,
        destinationWallet = destinationWallet,
        category = "",
        description = "",
        latitude = "",
        longitude = "",
    )

    @Test
    fun `a complete row has no field errors, with either decimal separator`() {
        assertEquals(emptyMap<String, String>(), validateImportRow(edited()))
        assertEquals(emptyMap<String, String>(), validateImportRow(edited(amount = "12,50")))
        assertEquals(emptyMap<String, String>(), validateImportRow(edited(amount = "1.000,45")))
    }

    @Test
    fun `an invalid amount gets the amount input's message`() {
        assertEquals(
            mapOf(ImportRowFields.AMOUNT to "Enter an amount"),
            validateImportRow(edited(amount = "  ")),
        )
        assertEquals(
            mapOf(ImportRowFields.AMOUNT to "Amount must be a positive number"),
            validateImportRow(edited(amount = "0")),
        )
        assertEquals(
            mapOf(ImportRowFields.AMOUNT to "That doesn't look like an amount — use digits and one . or , for decimals"),
            validateImportRow(edited(amount = "12abc")),
        )
    }

    @Test
    fun `an empty date and a missing wallet are each flagged`() {
        assertEquals(
            mapOf(
                ImportRowFields.DATE to "Choose a date",
                ImportRowFields.WALLET to "Choose a wallet.",
            ),
            validateImportRow(edited(date = "", wallet = "  ")),
        )
    }

    @Test
    fun `a transfer needs both legs, and two different ones`() {
        assertEquals(
            mapOf(
                ImportRowFields.SOURCE to "Choose the source wallet.",
                ImportRowFields.DESTINATION to "Choose the destination wallet.",
            ),
            validateImportRow(edited(type = TransactionType.TRANSFER, wallet = "Checking")),
        )
        val same = "Source and destination must be different wallets."
        assertEquals(
            mapOf(ImportRowFields.SOURCE to same, ImportRowFields.DESTINATION to same),
            validateImportRow(
                edited(type = TransactionType.TRANSFER, sourceWallet = " Checking ", destinationWallet = "Checking"),
            ),
        )
        // The names are compared raw (case-sensitively), as on the web: two
        // spellings of one Wallet pass, and the re-validation then rejects
        // the row; the resolution is the backend's.
        assertEquals(
            emptyMap<String, String>(),
            validateImportRow(
                edited(type = TransactionType.TRANSFER, sourceWallet = "Checking", destinationWallet = "checking"),
            ),
        )
    }

    @Test
    fun `an expense or income ignores the transfer legs`() {
        assertEquals(
            emptyMap<String, String>(),
            validateImportRow(
                edited(type = TransactionType.INCOME, sourceWallet = "Cash", destinationWallet = "Cash"),
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

    @Test
    fun `the sentinel's prefill is the field's missing name, else empty`() {
        val options = listOf("Checking" to "Checking", "Cash" to "Cash")
        // A name matching no option (the missing name from the file) is the
        // prefill, trimmed.
        assertEquals("Mystery", importSentinelPrefill(" Mystery ", options))
        // A blank field and a name that already resolves start empty.
        assertEquals("", importSentinelPrefill("", options))
        assertEquals("", importSentinelPrefill("   ", options))
        // Resolution is case-insensitive, like the field's display.
        assertEquals("", importSentinelPrefill("checking", options))
        assertEquals("", importSentinelPrefill("CASH", options))
    }

    @Test
    fun `an expense or income row's wallet sentinel locks out contact wallets`() {
        assertEquals(
            setOf(WalletType.CHECKING, WalletType.CREDIT_CARD, WalletType.CASH),
            importEditorWalletCreateAllowedTypes(WalletFieldTarget.WALLET),
        )
        // A Transfer's From/To may create any of the four — Contact
        // included — where Contact Wallets belong.
        assertNull(importEditorWalletCreateAllowedTypes(WalletFieldTarget.SOURCE))
        assertNull(importEditorWalletCreateAllowedTypes(WalletFieldTarget.DESTINATION))
    }

    @Test
    fun `a row references the created wallet through any of its wallet-kind fields`() {
        val row = ImportRowDto(
            row = 4,
            status = com.budjetame.android.data.api.ImportRowStatus.ERROR,
            type = TransactionType.TRANSFER,
            date = "2026-08-03",
            amount = "7.00",
            source_wallet = "mystery",
        )
        // Case- and space-insensitive, and any of the three fields counts.
        assertTrue(rowReferencesWallet(row, "Mystery"))
        assertTrue(rowReferencesWallet(row, " mystery "))
        val expense = row.copy(
            type = TransactionType.EXPENSE,
            source_wallet = null,
            destination_wallet = null,
            wallet = "Mystery",
        )
        assertTrue(rowReferencesWallet(expense, "Mystery"))
        val other = expense.copy(wallet = "Cash", category = "Mystery")
        assertFalse(rowReferencesWallet(other, "Mystery"))
    }

    @Test
    fun `a row references the created category only through its category field`() {
        val row = ImportRowDto(
            row = 4,
            status = ImportRowStatus.ERROR,
            type = TransactionType.EXPENSE,
            date = "2026-08-03",
            amount = "7.00",
            wallet = "Checking",
            category = "groceries",
        )
        assertTrue(rowReferencesCategory(row, "Groceries"))
        assertTrue(rowReferencesCategory(row, " groceries "))
        val without = row.copy(category = null)
        assertFalse(rowReferencesCategory(without, "Groceries"))
        // The wallet field never counts for a Category match.
        val walletOnly = row.copy(category = null, wallet = "Groceries")
        assertFalse(rowReferencesCategory(walletOnly, "Groceries"))
    }
}
