import java.io.File;
import java.io.FileOutputStream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/**
 * One-off generator for the Android seam test's export fixture
 * (app/src/test/resources/export/ledger-export.xlsx, ticket #28): an .xlsx
 * whose cells mirror exactly what the backend's build_export_workbook
 * writes for a ledger that held an Opening Balance (dropped — no row), a
 * Recurring-linked Expense (exported as an ordinary row — no link column
 * in the template) and an Income with a Place (flattened to coordinates in
 * the location column). Cell-for-cell template layout: header + rows,
 * dates as "YYYY-MM-DD" text, amounts as two-decimal text, blanks empty,
 * location as "lat,lon".
 */
public class GenExportFixture {

    private static final String[][] ROWS = {
        // date, type, amount, wallet, source wallet, destination wallet, category, description, location
        {"2026-08-01", "expense", "12.50", "Cash", "", "", "Food", "Lunch", "45.4642,9.19"},
        {"2026-08-02", "transfer", "50.00", "", "Checking", "Cash", "", "ATM", ""},
        {"2026-08-03", "income", "2500.00", "Checking", "", "", "Salary", "", ""},
    };

    private static final String HEADER = "date,type,amount,wallet,source wallet,destination wallet,category,description,location";

    public static void main(String[] args) throws Exception {
        StringBuilder sheet = new StringBuilder();
        sheet.append("<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>");
        sheet.append("<worksheet xmlns=\"http://schemas.openxmlformats.org/spreadsheetml/2006/main\">");
        sheet.append("<sheetData>");
        sheet.append(row(1, HEADER.split(",")));
        for (int i = 0; i < ROWS.length; i++) {
            sheet.append(row(i + 2, ROWS[i]));
        }
        sheet.append("</sheetData></worksheet>");

        File out = new File(args[0]);
        out.getParentFile().mkdirs();
        try (ZipOutputStream zip = new ZipOutputStream(new FileOutputStream(out))) {
            put(zip, "[Content_Types].xml", "<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>"
                + "<Types xmlns=\"http://schemas.openxmlformats.org/package/2006/content-types\">"
                + "<Default Extension=\"rels\" ContentType=\"application/vnd.openxmlformats-package.relationships+xml\"/>"
                + "<Default Extension=\"xml\" ContentType=\"application/xml\"/>"
                + "<Override PartName=\"/xl/workbook.xml\" ContentType=\"application/vnd.openxmlformats-officedocument.spreadsheetml.sheet.main+xml\"/>"
                + "<Override PartName=\"/xl/worksheets/sheet1.xml\" ContentType=\"application/vnd.openxmlformats-officedocument.spreadsheetml.worksheet+xml\"/>"
                + "</Types>");
            put(zip, "_rels/.rels", "<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>"
                + "<Relationships xmlns=\"http://schemas.openxmlformats.org/package/2006/relationships\">"
                + "<Relationship Id=\"rId1\" Type=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships/officeDocument\" Target=\"xl/workbook.xml\"/>"
                + "</Relationships>");
            put(zip, "xl/workbook.xml", "<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>"
                + "<workbook xmlns=\"http://schemas.openxmlformats.org/spreadsheetml/2006/main\""
                + " xmlns:r=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships\">"
                + "<sheets><sheet name=\"Sheet1\" sheetId=\"1\" r:id=\"rId1\"/></sheets></workbook>");
            put(zip, "xl/_rels/workbook.xml.rels", "<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>"
                + "<Relationships xmlns=\"http://schemas.openxmlformats.org/package/2006/relationships\">"
                + "<Relationship Id=\"rId1\" Type=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships/worksheet\" Target=\"worksheets/sheet1.xml\"/>"
                + "</Relationships>");
            put(zip, "xl/worksheets/sheet1.xml", sheet.toString());
        }
        System.out.println("wrote " + out.getAbsolutePath() + " (" + out.length() + " bytes)");
    }

    private static void put(ZipOutputStream zip, String name, String content) throws Exception {
        zip.putNextEntry(new ZipEntry(name));
        zip.write(content.getBytes("UTF-8"));
        zip.closeEntry();
    }

    private static String row(int r, String[] cells) {
        char[] cols = "ABCDEFGHI".toCharArray();
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
}
