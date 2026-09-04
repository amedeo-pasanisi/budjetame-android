# Per-Occurrence skip controls in the edit modal; recurring cards jump to the filtered ledger — mirror of web ADR-0026

The web app replaced its card-level Skip/Un-skip button (web ADR-0016) with per-Occurrence controls in ADR-0026 (web commit 5d2658e): a single toggle acts on the front of the queue, so once the whole Backlog was excused it flipped to Un-skip and toggled the oldest excused Occurrence — invisible in the card-only UI — and the user could never excuse incoming Occurrences in advance. The button is gone; each row of an Occurrences section in the edit modal skips or un-skips one Occurrence independently, in any order, and the recurring cards adopt the Wallets/Categories row anatomy (ADR-0004): the whole-surface tap jumps to the ledger filtered to the definition's linked Transactions, and a trailing ✎ opens the edit modal. The Android app mirrors the decision by adoption, as it did for web ADR-0024/0025.

## The forced API removal (ADR-0001)

The Android app is a client of the shared backend, so the backend's removal of `POST …/skip-toggle` and the derived `next_skip_action` field is not optional here: the card pill it drove is deleted, `SkipAction` and `skipToggleLabel` leave the display layer, and the definition DTOs drop the field. The removal is safe on either side of the backend deploy: the JSON config ignores unknown keys, so a backend that still sends `next_skip_action` parses cleanly (a test pins this), and the section's data comes from the new `GET …/occurrences` read and `PUT …/occurrences/{date}` write (`{"skipped": bool}`, idempotent, 422 on a Paid Occurrence or a non-Occurrence date), both answering the refreshed read.

## The Occurrences section

The section lives in the edit modals of both sides (Recurring Incomes mirror everything, ADR-0011) — edit mode only, since a definition under creation has no id yet. Its state rides in the modal state: the rows (null while the read is in flight), a section-local error, and the in-flight row date. The rows are fetched when an existing definition opens the modal, and a row toggle swaps in the write's response — the section never refetches and never re-sorts.

**The read's order is authoritative**: the backend returns the next incoming Unpaid row on top, then every other non-Paid row newest-first down to the oldest (today first among the past; excused future rows stay listed and reachable). The section renders the list verbatim — a client that sorted or grouped the rows client-side would diverge from the web (skip the top row and the following one must surface above it). This is the one place where doing nothing is the port: ADR-0002's data-version bump already refetches the lists behind the modal, so a skip write re-derives the badges and the next-due dates on the cards without any extra wiring.

## The ledger jump grows recurring kinds

The recurring cards' main tap fires the ADR-0004 ledger jump: `LedgerJump` gains `RecurringCost` and `RecurringIncome` kinds (separate types because a Cost and an Income may share an id), mapping onto the ledger's existing single recurring filter slot (`RecurringFilter` with its kind), which already powers the Filters bar's Recurring select (web issue #86). Seeded jumps fetch the first page pre-filtered in one fetch; applied jumps replace every filter — including a manual recurring pick of the other kind — and skip the refetch when the state already matches. The trailing ✎ is the shared `RowEditButton`, exactly as on the Wallets and Categories rows.

## Consequences

- The modal's Occurrences section shares one composable across the Costs and Incomes modals (`ui/recurring/OccurrencesSection.kt`) — the row pill is the old card pill's look, moved off the card (ADR-0011 leaves the display layer free to share pure UI).
- The web copy is ported verbatim: "Occurrences", "Loading occurrences…", "Skip excuses an occurrence: it never counts as unpaid, and a payment covers it only after un-skipping. Paid ones live in the ledger.", and the "Skipped — un-skip to pay it" row caption.
- The Recurring Costs and Recurring Incomes ViewModel suites' toggle tests rewrite against the occurrences read and the per-date write; the ledger-jump suite gains recurring-kind tests.
- The glossary's "Ledger jump" entry widens to cover Recurring definition rows, in both CONTEXT.md copies (web repo's pushed separately; ADR-0001 keeps the copies in sync).
