# Changelog

All notable changes to the Budjetame Android app. Format follows
[Keep a Changelog](https://keepachangelog.com/en/1.1.0/); versioning is
[SemVer](https://semver.org/). Each release is a `vX.Y.Z` tag, recorded here
and on GitHub Releases. The first release is the M1 milestone (spec #13):
when it lands, `[Unreleased]` becomes `[v1.0.0]`.

<!-- Agents: every ticket whose work lands adds one bullet to [Unreleased].
     See docs/agents/changelog.md before editing. -->

## [Unreleased]

### Changed

- **Recurring definitions & Transactions** — a recurring card's whole surface now jumps to the Transactions ledger pre-filtered to that definition's linked Transactions, the edit moved to a trailing ✎ button, and the card Skip/Un-skip button is gone: skipping lives in the edit modal's new Occurrences section, where every non-Paid Occurrence has its own Skip/Un-skip — skip the top row and the next incoming one surfaces above it, so a whole month can be excused in one sitting — and skipped rows stay greyed with Un-skip, always reachable (#46).
- **Transactions** — a ledger card whose Transaction carries a Place now reads `date · wallet · 📍 place name` next to the pin; a located card without a Place keeps the bare pin, and one without a location shows neither (#46).

## [v1.3.0] — 2026-09-04

### Changed

- **Recurring definitions & Transactions** — a definition with unpaid dues now shows exactly one red "N unpaid" badge on the Recurring screens: the per-row "Overdue" mark and the "X costs overdue · N unpaid occurrences" summary pill are gone, and the badge is read from the Backlog alone. On Transactions, the filtered chips line never wraps: overflow hides behind edge fades and scrolls sideways on one line, Clear all stays pinned beside the strip, and Export to Excel left the chips line — the filter panel's footer is its one home (#45).

## [v1.2.1] — 2026-09-03

### Fixed

- **Transactions** — the header spreads like the web's: the title on the left, Import and New transaction pushed to the right; the open Filters panel is outlined like the Filters toggle instead of floating on a shadow; and the record cards on every tab are now inset a few pixels toward the center, so their shadows no longer peek around the pinned search bar and filter panel while the list scrolls under them (#44).

## [v1.2.0] — 2026-09-03

### Changed

- **Recurring definitions carry one date** — the optional Due day / Due date override is gone (ADR-0024 in the web repo): an Occurrence's due date is its own date, and the start date is the definition's only date. Left empty at creation it becomes the creation day; afterwards it can be changed, never unset — a definition can no longer silently snap back to its creation day.
- **Recurring forms speak plainly** — the interval row reads "Repeats every N months" (the unit turns singular when N is 1), the start date is explained as "The first occurrence. Leave empty to start today." when creating, and editing requires the date.

## [v1.1.0] — 2026-09-03

### Added

- **Wallets & Categories** — a row's whole surface now opens the Transactions ledger already filtered to that Wallet or Category (the web's ledger jump), with the previous filters, search, and open Filters panel reset by the tap and a frozen Wallet landing on its read-only banner; editing moved to a trailing ✎ button on the card, and frozen rows gained one-tap Unfreeze beside it — the row tap itself never edits anymore (#44).
- **Transactions** — the search bar and Filters toggle are now fixed under the header while only the records scroll; the filtered chips line, the open Filters panel, and the frozen-wallet banner stay put above the list the same way, like the Categories tab's pinned search bar (#44).
- **Visual parity** — row text across the tabs now matches the web's scale (14sp titles, 12sp gray subtitles, 14sp semibold amounts, 18sp tab headers), buttons and text fields carry the web's rounded corners, white cards everywhere swapped their gray outlines for soft shadows, the "Import" link sits tight against New transaction, and every tab shares the same gaps between its header, search bar, and lists (#44).

## [v1.0.1] — 2026-09-03

### Changed

- **Dashboard** — pressing and holding a bar of the monthly trend chart now
  floats that month's exact amount in a small chip just above the bar, and
  lifting the finger hides it again (a full-height bar's chip stays inside
  the chart); the tap-to-toggle readout line is gone (#42).
- **App shell** — the five tabs now sit in one finger-following swipeable
  pager: dragging the content moves it with the finger and a release snaps
  to the nearest tab (a fling crosses exactly one), the bottom bar's
  selection follows the pages live, and tab taps glide to their page —
  while the header and bottom bar stay fixed. Returning to a tab still
  shows its held data and scroll position instantly, and the back button
  now exits the app from any tab instead of stepping back through the
  visited tabs; signing out or deleting the account clears every tab's
  state, so the next account starts clean (#41).
- **Dashboard** — the category-pie card now owns its month selector like
  the web app: the standalone month-totals card (its ◀/▶ arrows and the
  Expenses/Incomes totals pair) is gone, and the donut card carries a
  "Month" field that opens the month picker — picking a month refetches
  that month's summary and drives the title, donut, and legend together,
  with the toggle and the picker staying usable while the month loads
  (#39).
- **Transactions** — the ledger chrome now mirrors the web app's v1.2.0
  screen: the header row keeps just the title with Import (a plain text
  link) and New transaction, Export leaves it for the two web entry
  points labelled "Export to Excel" (the filtered line and the Filters
  panel's footer, the old header Export is gone); the search field row is
  the toolbar with the Filters toggle at its right, hidden together on a
  truly empty ledger; and set panel filters show as chips under the
  toolbar, each removable with its own ✕, beside Clear all (filters and
  search together) and Export to Excel, while the panel footer gained
  Clear all filters (#35).

### Fixed

- **Auth** — a failed 'Sign in with Google' now tells the two failure legs
  apart instead of one generic line: a problem with the Google credential
  sheet (client-side, e.g. a missing Android OAuth client registration)
  keeps the standard message, while the backend rejecting the Google token
  now shows 'Google could not verify the sign-in. Please try again.' — and
  both legs log their underlying error under one 'Google sign-in failed'
  marker for diagnosis (#43).
- **Dashboard** — the trend chart now always fills its card's inner
  width: a short From/To range spreads its bars evenly across the plot
  (bar widths unchanged, the gaps grown symmetrically, the gridlines
  spanning the full width) instead of clustering at the card's left,
  while wide ranges keep their fixed geometry and horizontal scroll —
  month labels and tap targets move with the bars (#40).
- **Auth** — the login screen's mode-switch link rows ("Don't have an
  Account? Sign up", "Forgot your password? Reset it", and the
  sign-up-mode "Already have an Account? Sign in") now sit their action
  text on the same visual line as the label at any font scale, instead
  of hanging it lower inside the button's taller touch target (#38).
- **Transactions** — the Location section's "Add location" / "Change
  location" and "Use my location" buttons now keep their natural width
  in a wrapping row instead of splitting the dialog's line in half: the
  two share one line at dialog widths, and when they no longer fit at a
  large font scale whole buttons wrap onto further lines, never a label
  mid-word — a label squeezed to two lines at an extreme scale stays
  centered inside its button (#37).
- **Transactions** — the Expense/Income/Transfer type buttons in the
  transaction form (and the import row editor's picker, which shares
  them) keep their natural width and wrap as whole buttons onto further
  lines when the dialog is narrow, so a label can never break mid-word
  at large font scales (#36).
- **App header** — the header's title, email, and Sign out now clear the
  status-bar clock/camera cutout on edge-to-edge devices: the surface still
  reaches the very top of the screen, while the row's content sits inside
  the status-bar inset with its padding intact, and nothing below the
  header shifts twice (#34).
- **Theme** — every Material 3 color role now maps to the web app's
  slate/indigo/white palette, so the Material baseline's pinkish tones no
  longer appear on any surface: the selected tab is a solid indigo pill with
  white content above a slate-200 divider (the web shell's bottom nav), and
  modal, date-picker, dropdown, divider and border colors all match the web
  app's whites, slates and indigo accent.

## [v1.0.0] — 2026-09-02

First release: the full spec #13 feature set — auth, the 5-tab shell,
wallets, categories, the dashboard, the transactions ledger with forms,
recurring definitions, and import/export with the map picker — verified by
the JVM suite (298 tests), the Compose UI tests, and a signed release
build. Release builds talk to the production backend at budjetame.de.

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
- **Map provider seam** — the Transaction form's Location section: a
  picked or GPS-attached position shows as a chip with the Place's name
  when the row carries one, an "Open in Google Maps" link built
  client-side (place_id → name → coordinates, never stored as text), and a
  Remove that clears the coordinates and the Place together. "Use my
  location" populates the current coordinates (permission asked on the
  first save of a new transaction, never overriding an opted-out user); a
  create form prefills the GPS position when permission is already
  granted. The map picker sits behind a provider seam (ADR-0004): the free
  OpenStreetMap (osmdroid) tap-to-pick map by default, a Google Maps
  picker with place search and POI taps when a MAP_PROVIDER=google build
  carries a key — only Google picks produce a Place; a coordinates-only
  pick clears it (#29).

### Fixed

- **Auth** — sign-in and sign-up failed with "please try again" on devices
  whose Keystore forbids caller-chosen GCM IVs; the session token is now
  encrypted with a Keystore-generated IV (#14).

[v1.3.0]: https://github.com/amedeo-pasanisi/budjetame-android/releases/tag/v1.3.0
[v1.2.1]: https://github.com/amedeo-pasanisi/budjetame-android/releases/tag/v1.2.1
[v1.2.0]: https://github.com/amedeo-pasanisi/budjetame-android/releases/tag/v1.2.0
[v1.1.0]: https://github.com/amedeo-pasanisi/budjetame-android/releases/tag/v1.1.0
[v1.0.1]: https://github.com/amedeo-pasanisi/budjetame-android/releases/tag/v1.0.1
[v1.0.0]: https://github.com/amedeo-pasanisi/budjetame-android/releases/tag/v1.0.0
