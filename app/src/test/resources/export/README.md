# ledger-export.xlsx — the seam test's export fixture (ticket #28)

The bytes the TransactionsViewModelTest fake serves for
`GET /transactions/export`: a real import-template workbook, so the seam
test's mapping assertions run over a genuine server-shaped file (the
client never reshapes it — pass-through is the whole client contract).

## Provenance

Generated with the backend exporter's own cell layout
(`app/services/exports.py::build_export_workbook` in the web repo,
`~/budjetame-ai`): one flat sheet, the fixed header in file order
(date, type, amount, wallet, source wallet, destination wallet, category,
description, location), rows in date-ascending order, amounts as
two-decimal text, blanks empty, locations as "lat,lon".

The rows mirror what that endpoint returns for a ledger that also held an
Opening Balance, a Recurring-linked Expense, and a Place-carrying Income:

| date       | type     | amount  | wallet    | source | destination | category | description | location     |
|------------|----------|---------|-----------|--------|-------------|----------|-------------|--------------|
| 2026-08-01 | expense  | 12.50   | Cash      |        |             | Food     | Lunch       | 45.4642,9.19 |
| 2026-08-02 | transfer | 50.00   |           | Checking | Cash       |          | ATM         |              |
| 2026-08-03 | income   | 2500.00 | Checking  |        |             | Salary   |             |              |

The export contract is applied server-side (CONTEXT.md): the Opening
Balance row is absent (the template's type vocabulary has no value for
it), the linked Expense exports as an ordinary row (the template never
carries a link), and the Place flattened to the coordinates above. Those
rules have their own end-to-end suite in the web repo
(`backend/tests/test_exports.py`); `ExportFileTest` pins this file's cells
so the record the fake serves can never drift, and the seam test pins the
byte-for-byte mapping.

To regenerate: `javac GenExportFixture.java && java GenExportFixture ledger-export.xlsx`
from this directory (the generator mirrors the builder's cells one-for-one).
