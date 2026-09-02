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

### Fixed

- **Auth** — sign-in and sign-up failed with "please try again" on devices
  whose Keystore forbids caller-chosen GCM IVs; the session token is now
  encrypted with a Keystore-generated IV (#14).
