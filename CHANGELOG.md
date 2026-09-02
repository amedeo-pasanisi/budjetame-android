# Changelog

All notable changes to the Budjetame Android app. Format follows
[Keep a Changelog](https://keepachangelog.com/en/1.1.0/); versioning is
[SemVer](https://semver.org/). Each release is a `vX.Y.Z` tag, recorded here
and on GitHub Releases. The first release is the M1 milestone (spec #13):
when it lands, `[Unreleased]` becomes `[v1.0.0]`.

<!-- Agents: every ticket whose work lands adds one bullet to [Unreleased].
     See docs/agents/changelog.md before editing. -->

## [Unreleased]

### Added

- **Auth & shell** — email+password login, registration, Google sign-in via
  Credential Manager, and forgot-password requests; the 90-day session JWT
  persists encrypted (Keystore AES-GCM) across restarts, a rejected token
  returns to the login screen, and a startup network failure offers Retry.
  Account deletion lives behind a Settings modal with the web app's exact
  confirm copy. The 5-tab shell (Dashboard, Wallets, Transactions,
  Categories, Recurring) keeps each tab's loaded data and revalidates in the
  background after writes (ADR-0002) (#14).
- **Wallets** — fixed sections (Contacts, Checking Accounts, Credit Cards,
  Cash) sorted A→Z with signed balances; create with name, type, and an
  optional opening balance ≥ €0; rename (type immutable); freeze at exactly
  €0 behind a tap-again confirm; collapsed "Frozen wallets (N)" footer with
  one-tap unfreeze (#15).
- **Categories** — expense/income sections with live search; create, edit,
  and delete with name, emoji icon, and color; a colliding rename offers a
  merge showing the transaction count; deleting a Category leaves its
  transactions uncategorized, never deletes them (#16).
- **Dashboard part 1** — Net Worth (the sum of every wallet balance,
  contact wallets included) front and center, the reference month's
  income/expense totals with previous/next navigation, and the category pie
  with an Expenses/Incomes toggle and a neutral "Uncategorized" slice (#17).
- **Dashboard part 2** — the monthly trend chart with an Expenses/Incomes
  toggle over a user-picked From/To month range (swapping like the web app,
  never reversed), Europe/Rome month bars oldest-first with zero-filled
  empties, tap-to-read exact totals; and the Budget card — Spendable Today
  big, the daily-allowance/this-month line, and a "You're €X over" note
  when the bucket is negative (shown as €0.00 until accruals repay it,
  ADR-0012) (#18).
- **Transactions ledger** — the read path: newest-first cursor paging (50 per
  page) with infinite scroll, a collapsible filter bar (wallet, frozen ones
  included and marked "Frozen", date range, category) with the debounced
  description search composed in, "Uncategorized" on category-less Expenses,
  and rows on frozen wallets rendered read-only (#19).
- **Transaction forms** — create, edit, and delete Expenses, Incomes, and
  Transfers from the ledger: the type-specific rules (a Transfer's distinct
  From/To wallets and no category, Contact wallets selectable on an Expense
  only), the Europe/Rome-defaulted date, a live balance preview with the Cash
  negative-balance warning, and the API's post-write warning banner; delete
  sits behind a tap-again confirmation, rows on frozen wallets stay
  read-only, and the ledger refreshes through the data-version bump (#20).
- **Transaction forms, inline creation** — the Wallet and Category selects
  create a missing entity without leaving the form (ADR-0013/0014): a
  trailing "New wallet…" / "New category…" option stacks the entity's
  create form on the transaction form, confirming creates it for real —
  visible on its screen and usable immediately — and auto-selects it into
  the originating field, the draft intact and ready to submit. An Income's
  Wallet pick never creates a Contact Wallet, and a new Category is locked
  to the form's type (#21).
- **Recurring Costs** — the Recurring tab's costs side: every definition
  sorted by next due date, each row showing the amount, the interval, the
  next due date, the next unpaid occurrence date, an "N unpaid" backlog
  badge, and an Overdue mark, with a "costs overdue · unpaid occurrences"
  summary line. Create, edit, and delete in one modal: name, amount, every
  N days/weeks/months/years, an optional start date (unset = today), and
  the due-date override that follows the unit (a day-of-month for months, a
  month+day for years); names are unique case-insensitively, and deleting a
  definition leaves its linked expenses as ordinary ones (#22).
- **Expense recurring link** — the transaction form's Recurring Cost
  picker on Expenses (never Incomes or Transfers): it lists the definitions
  and names the occurrence the link would pay, linking an Expense signs the
  definition's oldest unpaid occurrence as paid, unlinking frees it, and a
  mere amount or date edit never reassigns a link's occurrence (#22).

- **Recurring Incomes + income link** — the Recurring tab's incomes side
  (a Costs | Incomes toggle above the two sides, remembering the last side
  for the session): every definition sorted by next due date, each row
  showing the amount, the interval, the next due date, the next unpaid
  occurrence date, an "N unpaid" backlog badge, and an Overdue mark, with
  an "incomes overdue · unpaid occurrences" summary line. Create, edit,
  and delete in one modal mirroring the costs side (name unique
  case-insensitively, deleting leaves linked incomes as ordinary ones).
  The transaction form's Income branch carries the Recurring Income
  picker (never Expenses or Transfers): it lists the definitions and names
  the occurrence the link would pay, linking an Income signs the
  definition's oldest unpaid occurrence as paid, unlinking frees it, and a
  mere amount or date edit never reassigns a link's occurrence (#23).
- **Skip occurrences** — every Recurring Cost and Recurring Income row
  carries a Skip/Un-skip button (ADR-0016): a press excuses the oldest
  unpaid occurrence, which never enters the backlog, never counts toward
  Monthly Spendable, and can never be linked; once the whole backlog is
  excused the button reads Un-skip, and pressing it restores the oldest
  skipped occurrence. Pressing repeatedly clears a backlog oldest-first —
  the badge ticks down and the Overdue mark clears — and the response's
  refreshed definition re-renders the row in place (#24).
- **Ledger recurring filter** — the Transactions filter bar gains a
  Recurring select listing every Recurring Cost and Recurring Income
  (grouped by kind), narrowing the ledger to the transactions linked to
  that one definition and composing with the wallet, date, and category
  filters and the search (#25).
- **Import: preview, verify, confirm** — the Transactions header's Import
  button starts a bulk import from a .csv/.xlsx file picked through the
  system file picker: the Preview classifies every row Ready, Duplicate,
  or Problem (with the backend's message) and counts them in a sticky
  bar; every Ready row is preselected, duplicates and problems never are.
  Tapping any row opens the row editor — type, amount, date, wallet(s),
  category, description, location — and saving re-validates the row
  (duplicates detectable by a changed description alone, a blank one
  matching a missing one) and flips it in place, auto-selecting it when
  it turns Ready. Import sends exactly the kept rows; the backend inserts
  them transactionally, and any invalid row rejects the batch with its
  message. Nothing is written before that confirmation, and the draft
  survives tab switches — discarded only by Cancel, picking another file,
  or a successful import, which then reports what was imported and
  refreshes the ledger (#26).
- **Import: revalidation + inline creation** — returning to a live Preview
  re-checks every problem row in one batch against the account's current
  wallets and categories, flipping the rows that now pass to Ready (and
  marking one that now duplicates); and the row editor's Wallet and
  Category selects carry the same inline "New wallet…" / "New category…"
  creation as the transaction forms — the create form stacks on the row
  editor, prefilled with the missing name from the file, an Expense or
  Income row's Wallet pick never creates a Contact wallet, a new Category
  is locked to the row's type, and confirming creates the entity for real,
  selects it into the exact originating field, and re-validates every
  problem row that was waiting on its name in one batch (#27).
- **Export** — the Transactions header's Export button (beside Import,
  hidden while an Import Draft is open) downloads the whole filtered
  ledger — the current wallet, date, category, and recurring filters with
  the search applied, not just the visible page — as the import template's
  .xlsx under the backend's dated name, and opens it in the system share
  sheet, ready to share or save through SAF-backed targets (#28).

### Fixed

- **Auth** — sign-in and sign-up failed with "please try again" on devices
  whose Keystore forbids caller-chosen GCM IVs; the session token is now
  encrypted with a Keystore-generated IV (#14).
