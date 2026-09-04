# Transfers crossing an own Wallet and a Contact Wallet may carry the matching-direction recurring link — mirror of web ADR-0027

A Recurring Income's receipts are recorded by hand as linked Incomes — but when the payer is a Contact Wallet, the receipt *must* be a Transfer (Contact Wallets are never the Wallet of an Income), and until now Transfers could never carry a link (web ADR-0010/0011: "Transfer never carries a link"). The live gap: a recurring 300 €/month from Chiara (a Contact Wallet) arrives as a Transfer Chiara → Checking, so its Occurrence could never be marked Paid. Web ADR-0027 amends the rule: a Transfer whose legs are exactly one own (non-Contact) Wallet and one Contact Wallet may optionally carry one recurring link, and the **direction determines the kind** — a Transfer whose *source* is the Contact Wallet (money in) may link a **Recurring Income**; a Transfer whose *destination* is the Contact Wallet (money out) may link a **Recurring Cost**. Own↔own and Contact↔Contact Transfers never qualify. The Android app mirrors the decision by adoption, as it did for web ADR-0024/0025/0026: the shared backend (ADR-0001) owns the rule and rejects a link-set on a pair that does not qualify with a rule error — **never silently severed** — and the client mirrors the form behavior the web ships.

## The link contract is the typed links' own, unchanged

Everything the Expense/Income links already do carries over untouched: a link pays exactly the oldest Unpaid Occurrence at link time (pinned in `occurrence_date`, never reassigned by later date edits), the amount is never checked against the definition's, Skipped Occurrences are never payable, unlinking or deleting the Transfer frees the Occurrence, deleting the definition severs via `ON DELETE SET NULL`, a Paid Occurrence can never be Skipped, and Monthly Spendable is unaffected (Occurrences count by due date whether paid or not). Imports and exports still never carry links.

## The pair-and-direction rule lives in one pure predicate pair

`TransactionFormModel.kt` gains `transferCostLinkQualifies(source, destination)` and `transferIncomeLinkQualifies(source, destination)`: the first is true exactly when the destination is a Contact Wallet and the source is an own Wallet, the second when the source is a Contact Wallet and the destination is an own Wallet — the web form's `transferCostQualifies`/`transferIncomeQualifies` booleans. A missing leg never qualifies (both legs are always seeded in the form, so the corner is unreachable either way). The form renders `RecurringCostField`/`RecurringIncomeField` on a Transfer exactly when the predicate of the matching direction holds, reusing the shared helpers and composables: the stored pin while editing the very link on the row, else the picked definition's oldest Unpaid Occurrence ("Pays the occurrence of <date>."), the None option unlinks, and the field hides when there is nothing to pick or drop.

## The payload can never carry a stale link

The ViewModel's draft builder gates the wire keys by the same predicates (web parity: the form *sends null for a side that does not qualify*): the cost key rides only an Expense or a cost-qualifying Transfer, the income key only an Income or an income-qualifying Transfer. A pick made for a pair that the user then re-pointed out of qualification therefore never reaches the API — the backend never has to reject a client it could have prevented from lying.

## The edit contract: untouched = absent, null = unlink, value = (re)link

The backend's PATCH semantics need a present key to act — absent leaves the stored pin untouched (a mere amount/date edit never reassigns the Occurrence). The client's wire taxonomy already expressed that with one PATCH shape per linkable kind; a Transfer now gets the same treatment: two new shapes (`TransactionTransferCostLinkUpdateRequest`, `TransactionTransferIncomeLinkUpdateRequest`) carry exactly the touched kind's key — always present, null unlinking — selected by the same `recurringCostTouched`/`recurringIncomeTouched` flags as the Expense/Income shapes, so the untouched-kind key never exists on the wire. The legs and the type are immutable on edit (the wallet fields and the type selector freeze), so an edit can never re-point a linked Transfer out of qualification: the case the backend rejects with a rule error is unreachable from this client, and the client never has to unlink defensively.

## The read side needed nothing

The ledger's recurring-definition filter and the rows already follow the link columns whatever the Transaction's type (a Transfer's `recurring_income_id`/`recurring_cost_id` are ordinary columns), so a linked Transfer flows through the definition's filtered ledger and its ledger jump without special-casing — pinned by a seam test rather than assumed.

## Consequences

- The two pickers' docstrings drop "Transfer never renders it": they now name the qualifying pair instead.
- The seam suite's fake backend mirrors the new 422s (a link-set on a non-qualifying Transfer is rejected under the backend's messages) and accepts the qualifying creates/edits, pinning the oldest Unpaid Occurrence exactly as the real backend does.
- The glossary widens in both CONTEXT.md copies (web repo's pushed with its own ADR-0027): the Paid rule inside the Occurrence entry, the Transfer entry, and the link-contract rule bullet gain the Transfer legs; the delete-sever bullets say "linked Transactions".
- The `[Unreleased]` changelog gains the feature bullet (#47).
