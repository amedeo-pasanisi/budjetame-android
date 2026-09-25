package com.budjetame.android.data.transaction

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.zip.ZipInputStream

/**
 * The export's pure mappings (ticket #28): the Content-Disposition
 * filename port (the web transport's `exportFilename` — pure logic, where
 * porting bugs hide) and the seam test's ledger fixture, pinned to the
 * import-template contract it embodies.
 */
class ExportFileTest {

    // --- The Content-Disposition filename port ------------------------------

    @Test
    fun `the quoted filename comes out of the disposition header`() {
        assertEquals(
            "budjetame-2026-08-23.xlsx",
            exportFilename("attachment; filename=\"budjetame-2026-08-23.xlsx\""),
        )
    }

    @Test
    fun `a missing or unquoted header falls back to the default name`() {
        // The server always attaches the header; the fallback keeps the
        // share flow working against a proxy that stripped it (web parity).
        assertEquals(FALLBACK_EXPORT_FILENAME, exportFilename(null))
        assertEquals(FALLBACK_EXPORT_FILENAME, exportFilename(""))
        assertEquals(FALLBACK_EXPORT_FILENAME, exportFilename("attachment"))
        assertEquals(FALLBACK_EXPORT_FILENAME, exportFilename("attachment; filename=budjetame.xlsx"))
    }

    // --- The ledger fixture the seam test's fake serves ---------------------

    /**
     * The bytes of `src/test/resources/export/ledger-export.xlsx`: the
     * import template's workbook exactly as the backend's exporter writes
     * it. The cells below pin the file's meaning — the contract the
     * server-side export applies (CONTEXT.md, verified end to end in the
     * web repo's backend suite): the rows it carries for a ledger that
     * held an Opening Balance and Recurring-linked and Place-carrying
     * Transactions are an ordinary Expense with coordinates, a Transfer,
     * and an ordinary Income — the type vocabulary has no Opening Balance
     * value, the template never carries a link, and Places flatten to the
     * location column's "lat,lon".
     */
    private val fixture: ByteArray by lazy {
        checkNotNull(ExportFileTest::class.java.getResourceAsStream("/export/ledger-export.xlsx")) {
            "missing test resource export/ledger-export.xlsx"
        }.use { it.readBytes() }
    }

    @Test
    fun `the fixture is a zip of the template's sheet parts`() {
        val entries = ZipInputStream(fixture.inputStream()).use { zip ->
            generateSequence { zip.nextEntry?.name }.toList()
        }
        assertEquals(
            listOf(
                "[Content_Types].xml",
                "_rels/.rels",
                "xl/workbook.xml",
                "xl/_rels/workbook.xml.rels",
                "xl/worksheets/sheet1.xml",
            ),
            entries,
        )
    }

    @Test
    fun `the fixture's sheet carries the template header and three rows`() {
        val sheet = ZipInputStream(fixture.inputStream()).use { zip ->
            var xml: String? = null
            while (true) {
                val entry = zip.nextEntry ?: break
                if (entry.name == "xl/worksheets/sheet1.xml") {
                    xml = zip.readBytes().toString(Charsets.UTF_8)
                }
                zip.closeEntry()
            }
            xml
        } ?: error("no sheet1.xml in the fixture")

        // The fixed template header, in file order.
        listOf("date", "type", "amount", "wallet", "source wallet", "destination wallet",
            "category", "description", "location").forEach { header ->
            assertTrue("header cell $header missing", sheet.contains("<t>$header</t>"))
        }
        // One header row plus one row per exported Transaction.
        assertEquals(4, Regex("<row r=\"").findAll(sheet).count())

        // The export contract, cell-level: the rows are an Expense with
        // coordinates, a Transfer, and an Income — no Opening Balance row,
        // no link, Places flattened to the location column's "lat,lon".
        assertTrue(sheet.contains("45.4642,9.19"))
        assertTrue(sheet.contains("<t>expense</t>"))
        assertTrue(sheet.contains("<t>income</t>"))
        assertTrue(sheet.contains("<t>transfer</t>"))
        assertTrue(!sheet.contains("opening"))
        assertTrue(!sheet.contains("recurring"))
        assertTrue(!sheet.contains("place"))
    }

    // --- The backup fixture (ticket #57) ------------------------------------

    /**
     * The bytes of `src/test/resources/export/backup-export.xlsx`: the
     * multi-sheet backup workbook exactly as the backend's exporter writes
     * it. Carries one row per entity kind (Wallets, Categories,
     * Transactions, Recurring Costs, Recurring Incomes, Skips) plus the
     * header row on each sheet.
     */
    private val backupFixture: ByteArray by lazy {
        checkNotNull(ExportFileTest::class.java.getResourceAsStream("/export/backup-export.xlsx")) {
            "missing test resource export/backup-export.xlsx"
        }.use { it.readBytes() }
    }

    @Test
    fun `the backup fixture is a zip carrying six sheets`() {
        val entries = ZipInputStream(backupFixture.inputStream()).use { zip ->
            generateSequence { zip.nextEntry?.name }.toList()
        }
        assertEquals(
            listOf(
                "[Content_Types].xml",
                "_rels/.rels",
                "xl/workbook.xml",
                "xl/_rels/workbook.xml.rels",
                "xl/worksheets/sheet1.xml",
                "xl/worksheets/sheet2.xml",
                "xl/worksheets/sheet3.xml",
                "xl/worksheets/sheet4.xml",
                "xl/worksheets/sheet5.xml",
                "xl/worksheets/sheet6.xml",
            ),
            entries,
        )
    }

    @Test
    fun `the backup workbook carries named entity sheets`() {
        val workbook = ZipInputStream(backupFixture.inputStream()).use { zip ->
            var xml: String? = null
            while (true) {
                val entry = zip.nextEntry ?: break
                if (entry.name == "xl/workbook.xml") {
                    xml = zip.readBytes().toString(Charsets.UTF_8)
                }
                zip.closeEntry()
            }
            xml
        } ?: error("no workbook.xml in the backup fixture")

        listOf("Wallets", "Categories", "Transactions", "Recurring Costs",
            "Recurring Incomes", "Skips").forEach { sheetName ->
            assertTrue("sheet $sheetName missing from workbook", workbook.contains("""sheet name="$sheetName""""))
        }
    }

    @Test
    fun `the backup fixture's Wallets sheet carries header and one row`() {
        val sheet = readSheet(backupFixture, "xl/worksheets/sheet1.xml")
        listOf("id", "name", "type", "balance", "currency", "frozen").forEach { header ->
            assertTrue("Wallets header cell $header missing", sheet.contains("<t>$header</t>"))
        }
        assertEquals(2, Regex("<row r=\"").findAll(sheet).count())
        assertTrue(sheet.contains("<t>Cash</t>"))
    }

    @Test
    fun `the backup fixture's Categories sheet carries header and one row`() {
        val sheet = readSheet(backupFixture, "xl/worksheets/sheet2.xml")
        listOf("id", "name", "type", "icon", "color").forEach { header ->
            assertTrue("Categories header cell $header missing", sheet.contains("<t>$header</t>"))
        }
        assertEquals(2, Regex("<row r=\"").findAll(sheet).count())
        assertTrue(sheet.contains("<t>Food</t>"))
        assertTrue(sheet.contains("<t>expense</t>"))
    }

    @Test
    fun `the backup fixture's Transactions sheet carries header and one row`() {
        val sheet = readSheet(backupFixture, "xl/worksheets/sheet3.xml")
        listOf("date", "type", "amount", "wallet", "category", "description").forEach { header ->
            assertTrue("Transactions header cell $header missing", sheet.contains("<t>$header</t>"))
        }
        assertEquals(2, Regex("<row r=\"").findAll(sheet).count())
        assertTrue(sheet.contains("<t>2026-08-01</t>"))
        assertTrue(sheet.contains("<t>expense</t>"))
        assertTrue(sheet.contains("<t>Lunch</t>"))
    }

    @Test
    fun `the backup fixture's Recurring Costs sheet carries header and one row`() {
        val sheet = readSheet(backupFixture, "xl/worksheets/sheet4.xml")
        listOf("id", "name", "amount", "day_of_month", "category_id").forEach { header ->
            assertTrue("Recurring Costs header cell $header missing", sheet.contains("<t>$header</t>"))
        }
        assertEquals(2, Regex("<row r=\"").findAll(sheet).count())
        assertTrue(sheet.contains("<t>Rent</t>"))
    }

    @Test
    fun `the backup fixture's Recurring Incomes sheet carries header and one row`() {
        val sheet = readSheet(backupFixture, "xl/worksheets/sheet5.xml")
        listOf("id", "name", "amount", "day_of_month").forEach { header ->
            assertTrue("Recurring Incomes header cell $header missing", sheet.contains("<t>$header</t>"))
        }
        assertEquals(2, Regex("<row r=\"").findAll(sheet).count())
        assertTrue(sheet.contains("<t>Salary</t>"))
    }

    @Test
    fun `the backup fixture's Skips sheet carries header and one row`() {
        val sheet = readSheet(backupFixture, "xl/worksheets/sheet6.xml")
        listOf("recurring_id", "kind", "date").forEach { header ->
            assertTrue("Skips header cell $header missing", sheet.contains("<t>$header</t>"))
        }
        assertEquals(2, Regex("<row r=\"").findAll(sheet).count())
        assertTrue(sheet.contains("<t>cost</t>"))
    }

    /** Read one sheet's XML from a multi-sheet .xlsx zip. */
    private fun readSheet(bytes: ByteArray, path: String): String =
        ZipInputStream(bytes.inputStream()).use { zip ->
            var xml: String? = null
            while (true) {
                val entry = zip.nextEntry ?: break
                if (entry.name == path) {
                    xml = zip.readBytes().toString(Charsets.UTF_8)
                }
                zip.closeEntry()
            }
            xml
        } ?: error("no $path in the backup fixture")
}