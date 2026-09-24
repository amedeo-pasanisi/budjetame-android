package com.budjetame.android.ui.imports

import com.budjetame.android.data.api.ImportRowDto
import com.budjetame.android.data.api.ImportRowInput
import com.budjetame.android.data.api.ImportRowStatus
import com.budjetame.android.data.api.TransactionType
import com.budjetame.android.data.api.WalletType
import com.budjetame.android.ui.transactions.WalletFieldTarget
import com.budjetame.android.ui.validation.FieldKey
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The Verification row editor's draft rules (ImportRowEditorModel.kt),
 * ported from the web app's ImportRowModal.tsx + ImportEntitySelect.tsx:
 * the cleaned-value normalization, the mandatory-fields gate on Save, the
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

    @Test
    fun `validate returns empty map for a valid row`() {
        val errors = validateImportRow(
            type = TransactionType.EXPENSE,
            amount = "12.50",
            date = "2026-08-01",
            wallet = "Checking",
            sourceWallet = "",
            destinationWallet = "",
        )
        assertTrue(errors.isEmpty())
    }

    @Test
    fun `validate returns an amount error for a blank or unparseable amount`() {
        val blankErrors = validateImportRow(
            TransactionType.EXPENSE, "", "2026-08-01", "Checking", "", "",
        )
        assertEquals("Enter an amount", blankErrors[FieldKey.AMOUNT])

        val lettersErrors = validateImportRow(
            TransactionType.EXPENSE, "abc", "2026-08-01", "Checking", "", "",
        )
        assertEquals(
            "That doesn't look like an amount — use digits and one . or , for decimals",
            lettersErrors[FieldKey.AMOUNT],
        )

        val zeroErrors = validateImportRow(
            TransactionType.EXPENSE, "0", "2026-08-01", "Checking", "", "",
        )
        assertEquals("Amount must be a positive number", zeroErrors[FieldKey.AMOUNT])
    }

    @Test
    fun `validate returns a date error when date is empty`() {
        val errors = validateImportRow(
            TransactionType.EXPENSE, "12.50", "", "Checking", "", "",
        )
        assertEquals("Choose a date", errors[FieldKey.DATE])
    }

    @Test
    fun `validate returns wallet errors for an expense with no wallet`() {
        val errors = validateImportRow(
            TransactionType.EXPENSE, "12.50", "2026-08-01", "   ", "", "",
        )
        assertEquals("Choose a wallet.", errors[FieldKey.WALLET])
    }

    @Test
    fun `validate returns both source and destination errors for a transfer with no wallets`() {
        val errors = validateImportRow(
            TransactionType.TRANSFER, "12.50", "2026-08-01", "", "", "",
        )
        assertEquals("Choose the source wallet.", errors[FieldKey.SOURCE_WALLET])
        assertEquals("Choose the destination wallet.", errors[FieldKey.DESTINATION_WALLET])
    }

    @Test
    fun `validate returns distinct-wallet error when source and destination are the same`() {
        val errors = validateImportRow(
            TransactionType.TRANSFER, "12.50", "2026-08-01", "", "Checking", "Checking",
        )
        assertEquals("Source and destination must be different wallets.", errors[FieldKey.SOURCE_WALLET])
    }

    @Test
    fun `validate returns all errors at once for a fully invalid row`() {
        val errors = validateImportRow(
            TransactionType.TRANSFER, "", "", "", "", "",
        )
        assertTrue(errors.size >= 3)
        assertTrue(errors.containsKey(FieldKey.AMOUNT))
        assertTrue(errors.containsKey(FieldKey.DATE))
        assertTrue(errors.containsKey(FieldKey.SOURCE_WALLET))
        assertTrue(errors.containsKey(FieldKey.DESTINATION_WALLET))
    }

    @Test
    fun `validate accepts tolerant amount separators`() {
        val dotErrors = validateImportRow(
            TransactionType.EXPENSE, "17.5", "2026-08-01", "Checking", "", "",
        )
        assertTrue(dotErrors.isEmpty())

        val commaErrors = validateImportRow(
            TransactionType.EXPENSE, "17,5", "2026-08-01", "Checking", "", "",
        )
        assertTrue(commaErrors.isEmpty())

        val groupedDotErrors = validateImportRow(
            TransactionType.EXPENSE, "2,002.01", "2026-08-01", "Checking", "", "",
        )
        assertTrue(groupedDotErrors.isEmpty())

        val groupedCommaErrors = validateImportRow(
            TransactionType.EXPENSE, "1.000,00", "2026-08-01", "Checking", "", "",
        )
        assertTrue(groupedCommaErrors.isEmpty())
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
