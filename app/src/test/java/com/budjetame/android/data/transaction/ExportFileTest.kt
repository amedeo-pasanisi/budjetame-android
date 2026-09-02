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
}
