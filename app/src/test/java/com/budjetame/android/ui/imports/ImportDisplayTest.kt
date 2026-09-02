package com.budjetame.android.ui.imports

import com.budjetame.android.data.api.ImportRowStatus
import com.budjetame.android.data.api.TransactionType
import org.junit.Assert.assertEquals
import org.junit.Test

/** The Import screen's presentation-only logic (ImportDisplay.kt), ported
 * from the web app's ImportScreen.tsx: the status words, the row cards'
 * derived text, the sticky bar's copy, and the picked-file line. */
class ImportDisplayTest {

    @Test
    fun `the wire statuses speak the Preview's words`() {
        assertEquals("Ready", importStatusWord(ImportRowStatus.OK))
        assertEquals("Duplicate", importStatusWord(ImportRowStatus.DUPLICATE))
        assertEquals("Problem", importStatusWord(ImportRowStatus.ERROR))
    }

    @Test
    fun `the row card's title line pairs the date with the type word`() {
        assertEquals("2026-08-01 · Expense", importRowTitleLine("2026-08-01", TransactionType.EXPENSE))
        assertEquals("2026-08-01 · Income", importRowTitleLine("2026-08-01", TransactionType.INCOME))
        assertEquals("2026-08-01 · Transfer", importRowTitleLine("2026-08-01", TransactionType.TRANSFER))
    }

    @Test
    fun `a parse-error row shows dashes for the fields the file did not yield`() {
        assertEquals("— · —", importRowTitleLine(null, null))
        assertEquals("2026-08-01 · —", importRowTitleLine("2026-08-01", null))
        assertEquals("— · Transfer", importRowTitleLine(null, TransactionType.TRANSFER))
    }

    @Test
    fun `the wallet line names both legs of a transfer, else the wallet`() {
        assertEquals(
            "Checking → Cash",
            importWalletLine(TransactionType.TRANSFER, null, "Checking", "Cash"),
        )
        // A transfer missing a leg falls back to the wallet field, like the web.
        assertEquals("", importWalletLine(TransactionType.TRANSFER, null, "Checking", null))
        assertEquals("Checking", importWalletLine(TransactionType.EXPENSE, "Checking", null, null))
        assertEquals("", importWalletLine(TransactionType.EXPENSE, null, null, null))
    }

    @Test
    fun `the location suffix appears only when both coordinates are present`() {
        assertEquals(" 📍 45.4642, 9.19", importLocationSuffix("45.4642", "9.19"))
        assertEquals("", importLocationSuffix("45.4642", null))
        assertEquals("", importLocationSuffix(null, "9.19"))
    }

    @Test
    fun `the counts line pluralizes only the non-one words`() {
        assertEquals("3 ready · 2 duplicates · 1 problem", importCountsText(3, 2, 1))
        assertEquals("1 ready · 1 duplicate · 1 problem", importCountsText(1, 1, 1))
        assertEquals("0 ready · 5 duplicates · 0 problems", importCountsText(0, 5, 0))
    }

    @Test
    fun `the import button speaks the selection and the busy state`() {
        assertEquals("Importing…", importButtonText(3, busy = true))
        assertEquals("Nothing to import", importButtonText(0, busy = false))
        assertEquals("Import 1 row", importButtonText(1, busy = false))
        assertEquals("Import 2 rows", importButtonText(2, busy = false))
    }

    @Test
    fun `the picked file line rounds kilobytes with a floor of one`() {
        assertEquals("rows.csv · 1 KB", pickedFileLine("rows.csv", 0))
        assertEquals("rows.csv · 1 KB", pickedFileLine("rows.csv", 512))
        assertEquals("rows.csv · 2 KB", pickedFileLine("rows.csv", 1_800))
        assertEquals("rows.csv · 12 KB", pickedFileLine("rows.csv", 12_100))
    }
}
