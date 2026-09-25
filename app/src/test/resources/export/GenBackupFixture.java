import java.io.File;
import java.io.FileOutputStream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/**
 * One-off generator for the Android seam test's backup fixture
 * (app/src/test/resources/export/backup-export.xlsx, ticket #57):
 * a multi-sheet workbook carrying the complete backup — Wallets,
 * Categories, Transactions, Recurring Costs, Recurring Incomes, Skips.
 * Each sheet has a header row and one data row, mirroring the backend's
 * backup endpoint layout.
 *
 * Sheets in the order the backend produces them:
 *   1. Wallets
 *   2. Categories
 *   3. Transactions
 *   4. Recurring Costs
 *   5. Recurring Incomes
 *   6. Skips
 *
 * To regenerate: `javac GenBackupFixture.java && java GenBackupFixture backup-export.xlsx`
 * from this directory.
 */
public class GenBackupFixture {

    /** (sheet name, header comma-list, row cell values) */
    private static final SheetDef[] SHEETS = {
        new SheetDef("Wallets", "id,name,type,balance,currency,frozen",
            new String[]{"1,Cash,cash,1500.00,EUR,false"}),
        new SheetDef("Categories", "id,name,type,icon,color",
            new String[]{"1,Food,expense,🍔,#FF5733"}),
        new SheetDef("Transactions", "date,type,amount,wallet,category,description",
            new String[]{"2026-08-01,expense,12.50,Cash,Food,Lunch"}),
        new SheetDef("Recurring Costs", "id,name,amount,day_of_month,category_id",
            new String[]{"1,Rent,1200.00,1,2"}),
        new SheetDef("Recurring Incomes", "id,name,amount,day_of_month",
            new String[]{"1,Salary,5000.00,28"}),
        new SheetDef("Skips", "recurring_id,kind,date",
            new String[]{"1,cost,2026-08-01"}),
    };

    public static void main(String[] args) throws Exception {
        File out = new File(args[0]);
        out.getParentFile().mkdirs();

        String[] sheetNames = new String[SHEETS.length];
        for (int i = 0; i < SHEETS.length; i++) {
            sheetNames[i] = SHEETS[i].name;
        }

        try (ZipOutputStream zip = new ZipOutputStream(new FileOutputStream(out))) {
            // [Content_Types].xml — all sheets
            StringBuilder ct = new StringBuilder();
            ct.append("<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>");
            ct.append("<Types xmlns=\"http://schemas.openxmlformats.org/package/2006/content-types\">");
            ct.append("<Default Extension=\"rels\" ContentType=\"application/vnd.openxmlformats-package.relationships+xml\"/>");
            ct.append("<Default Extension=\"xml\" ContentType=\"application/xml\"/>");
            ct.append("<Override PartName=\"/xl/workbook.xml\" ContentType=\"application/vnd.openxmlformats-officedocument.spreadsheetml.sheet.main+xml\"/>");
            for (int i = 0; i < SHEETS.length; i++) {
                ct.append("<Override PartName=\"/xl/worksheets/sheet").append(i + 1).append(".xml\"");
                ct.append(" ContentType=\"application/vnd.openxmlformats-officedocument.spreadsheetml.worksheet+xml\"/>");
            }
            ct.append("</Types>");
            put(zip, "[Content_Types].xml", ct.toString());

            // _rels/.rels
            put(zip, "_rels/.rels",
                "<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>"
                + "<Relationships xmlns=\"http://schemas.openxmlformats.org/package/2006/relationships\">"
                + "<Relationship Id=\"rId1\" Type=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships/officeDocument\" Target=\"xl/workbook.xml\"/>"
                + "</Relationships>");

            // xl/workbook.xml — all sheet references
            StringBuilder wb = new StringBuilder();
            wb.append("<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>");
            wb.append("<workbook xmlns=\"http://schemas.openxmlformats.org/spreadsheetml/2006/main\"");
            wb.append(" xmlns:r=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships\">");
            wb.append("<sheets>");
            for (int i = 0; i < SHEETS.length; i++) {
                wb.append("<sheet name=\"").append(xmlEscape(SHEETS[i].name))
                  .append("\" sheetId=\"").append(i + 1)
                  .append("\" r:id=\"rId").append(i + 1).append("\"/>");
            }
            wb.append("</sheets></workbook>");
            put(zip, "xl/workbook.xml", wb.toString());

            // xl/_rels/workbook.xml.rels — one rel per sheet
            StringBuilder rels = new StringBuilder();
            rels.append("<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>");
            rels.append("<Relationships xmlns=\"http://schemas.openxmlformats.org/package/2006/relationships\">");
            for (int i = 0; i < SHEETS.length; i++) {
                rels.append("<Relationship Id=\"rId").append(i + 1)
                    .append("\" Type=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships/worksheet\"")
                    .append(" Target=\"worksheets/sheet").append(i + 1).append(".xml\"/>");
            }
            rels.append("</Relationships>");
            put(zip, "xl/_rels/workbook.xml.rels", rels.toString());

            // xl/worksheets/sheetN.xml — each sheet's content
            for (int i = 0; i < SHEETS.length; i++) {
                StringBuilder sheet = new StringBuilder();
                sheet.append("<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>");
                sheet.append("<worksheet xmlns=\"http://schemas.openxmlformats.org/spreadsheetml/2006/main\">");
                sheet.append("<sheetData>");
                String[] headers = SHEETS[i].headers;
                sheet.append(row(1, headers));
                String[] rows = SHEETS[i].rows;
                for (int r = 0; r < rows.length; r++) {
                    sheet.append(row(r + 2, rows[r].split(",")));
                }
                sheet.append("</sheetData></worksheet>");
                put(zip, "xl/worksheets/sheet" + (i + 1) + ".xml", sheet.toString());
            }
        }
        System.out.println("wrote " + out.getAbsolutePath() + " (" + out.length() + " bytes)");
    }

    private static void put(ZipOutputStream zip, String name, String content) throws Exception {
        zip.putNextEntry(new ZipEntry(name));
        zip.write(content.getBytes("UTF-8"));
        zip.closeEntry();
    }

    private static String row(int r, String[] cells) {
        char[] cols = "ABCDEFGHIJKLMNOPQRSTUVWXYZ".toCharArray();
        StringBuilder xml = new StringBuilder();
        xml.append("<row r=\"").append(r).append("\">");
        for (int c = 0; c < cells.length; c++) {
            xml.append("<c r=\"").append(cols[c]).append(r).append("\" t=\"inlineStr\"><is><t>")
                .append(xmlEscape(cells[c]))
                .append("</t></is></c>");
        }
        xml.append("</row>");
        return xml.toString();
    }

    private static String xmlEscape(String s) {
        return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
    }

    private static class SheetDef {
        final String name;
        final String[] headers;
        final String[] rows;

        SheetDef(String name, String headerCsv, String[] rows) {
            this.name = name;
            this.headers = headerCsv.split(",");
            this.rows = rows;
        }
    }
}