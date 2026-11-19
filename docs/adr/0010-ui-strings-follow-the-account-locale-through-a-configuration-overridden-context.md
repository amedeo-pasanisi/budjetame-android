# UI strings follow the Account Locale through a configuration-overridden context at the composition root — the Android analogue of web #117's IntlProvider

The app's UI language follows the Account's **Locale** (#59), not the device language. UI strings live in Android string resources: `values/` holds English, which is both the source and the fallback, and `values-it/` holds Italian. Whenever the same English text exists in the web's `frontend/src/i18n/catalogs.ts`, the Italian is taken from its `it` catalog, so both clients say the same thing. Issue #62 builds the scaffold and translates the core screens. #63 (Recurring, Categories, Import, Login, `ui/common`) and #71 (Assistant/Dictation/Quick Dictation, the messages built outside composables, the server error catalogue, the system-drawn tile and shortcut labels) apply the same convention.

## Where the Locale is applied

`ui/common/AccountLocaleResources.kt`:

- **`ProvideAccountLocale(localeCode) { … }`** goes around each Activity's content, once. `MainActivity` applies it through `BudjetameApp` and `QuickDictationActivity` applies it directly, both fed by `LocaleGateway.locale`, the app's single observable source. It provides `LocalDisplayLocale`, which amounts and dates use, and then `AccountLocaleResources`.
- **`AccountLocaleResources { … }`** provides a `LocalContext`, `LocalConfiguration` and `LocalResources` whose `Configuration` carries the Locale. The context is a `ContextWrapper` around the window's own context, so the Activity, system services and the theme stay reachable, and only `getResources()` is swapped. `stringResource` and `pluralStringResource` read `LocalResources`, so they resolve `values-it` for `it` and `values/` for `en`, whatever the device language. A key missing from `values-it` falls back to `values/`. A change of Locale replaces the provided configuration, which re-renders every reader in place without recreating the Activity.
- **Every new window applies `AccountLocaleResources` again.** `Dialog`, `AlertDialog`, `DatePickerDialog`, `DropdownMenu`/`ExposedDropdownMenu` and any other `Popup` compose in their own ComposeView, which re-provides the Activity's own `LocalContext`/`LocalConfiguration`, and those carry the device language. Only `LocalDisplayLocale` (an ordinary composition local) crosses into the window. So a window either wraps its content (`text = { AccountLocaleResources { … } }`, `ExposedDropdownMenu(…) { AccountLocaleResources { items } }`), or resolves its strings outside the window and passes them in as plain `String`s. Without either, the window silently renders in the device language. The Settings dialog wraps its content, so the Language choice re-renders the open dialog the moment it changes.
- Composables tested outside the root (`createComposeRule().setContent { WalletModal(…) }`) resolve against the test device, which runs `en`, so the English instrumented suites are unchanged. A test proves Italian by wrapping its content in `ProvideAccountLocale("it")`.

## Conventions

- **Files per area:** `values/strings_<area>.xml`, mirrored in `values-it/`. One file per area keeps parallel tickets from colliding. Today's files and the key prefixes each one holds:

  | File | Prefixes |
  | --- | --- |
  | `strings_common` | `common_` |
  | `strings_shell` | `shell_`, plus `settings_title` and `settings_delete_*` for the Settings dialog's frame |
  | `strings_settings` | `settings_` |
  | `strings_dashboard` | `dashboard_` |
  | `strings_transactions` | `transactions_`, `drafts_` |
  | `strings_transaction_form` | `transaction_form_` |
  | `strings_wallets` | `wallets_`, `wallet_form_` |

  Later tickets add `strings_recurring`, `strings_categories`, `strings_imports`, `strings_login`, `strings_assistant` and `strings_errors`. `strings_common` holds only the shared verbs: Cancel, Save, Saving…, Close, Delete, Deleting…, Edit, Retry and Loading…. A ticket that needs a new shared word adds it to its own area file.
- **Key names:** `snake_case`, `<area>_<element>[_<qualifier>]`, named for the role rather than the English wording: `wallets_empty`, `transaction_form_amount`, `settings_delete_account_confirm`, `dashboard_budget_left_this_month`. Enum labels use `<area>_<enum>_<value>`, e.g. `wallets_type_credit_card`. Accessibility labels end in `_cd`.
- **Arguments:** always positional (`%1$s`, `%2$d`), so a translation can reorder them. Amounts and dates are passed already formatted as `String`s (`Money` / `Dates` with `LocalDisplayLocale`), never as `%f`. Escape `'` as `\'`. Text that is not language (emoji, `→`, `·` separators, brand names, user data) stays in code. There is one exception. When a `UiText` must join a translated part with other text, the join pattern may be a `translatable="false"` resource, e.g. `transactions_joined` = `%1$s · %2$s`. Such a resource has no `values-it` entry.
- **Plurals:** a count-dependent phrase is a `<plurals>` resource with `one` and `other`, read with `pluralStringResource(id, count, count)`. A count shown only as a parenthesised number, e.g. "Frozen wallets (2)", is a plain `%1$d` string.
- **Text produced outside a composable** uses `ui/common/UiText.kt`. A ViewModel's UI state or a presentation helper holds a `UiText`, never a resolved `String`: `UiText.Res(R.string.x, listOf(args))` or `UiText.of(R.string.x, arg1, arg2)`; `UiText.Plural(R.plurals.x, count)`; and `UiText.Raw(text)` for a server message or user data. An argument may itself be a `UiText`. The composable renders `text.resolve()`, and non-composable Android code calls `text.resolve(resources)`. A Locale switch therefore re-renders text already held in state. JVM tests assert the exact English with `text.english()` (test-only `ui/common/EnglishStrings.kt` reads `values/strings*.xml`), or they assert the `UiText` value itself.
- The **system-drawn labels** (the Quick Settings tile, the launcher shortcut, `app_name`) are read by the system outside any composition, so they follow the device language. They are #71's concern.

## Scope of #62

Translated: the app shell and tabs; Settings with its Language, Assistant Settings, Backup (Export all) and Restore sections; Dashboard; Transactions (the list, the filters, the Transaction form, the Draft Transactions block); and Wallets (the list and the Wallet modal), including their presentation helpers (`WalletDisplay`, `BudgetDisplay`, `TransactionDisplay`, `DraftTransactionDisplay`), which now return `UiText`. Left as English `String`s for #71: the messages the ViewModels build (load and save errors, Field Errors, Undo toasts, the Backup and Restore outcomes).

## Considered Options

- **Per-app locales (`AppCompatDelegate.setApplicationLocales`).** Rejected, because it needs AppCompat, a new dependency the app does not otherwise use. Below API 33 it applies the locale by recreating the Activity, which drops the composition's `remember` state (the auth state machine, open dialogs), and it persists a second copy of a setting the server already owns.
- **Overriding the Activity's configuration (`attachBaseContext` / `applyOverrideConfiguration`) and `recreate()` on change.** Rejected: it does reach every window, but it restarts the Activity on every switch and needs the Locale before the `/auth/me` read that serves it.
- **`Resources.updateConfiguration` on the Activity's resources.** Rejected: the API is deprecated, it mutates shared state behind Compose's back, and a configuration change silently undoes it.
- **A Kotlin string table keyed by Locale, without Android resources.** Rejected: it gives up `plurals`, the resource fallback, and the platform's tooling.

## Consequences

- Each window in a translated screen needs `AccountLocaleResources`, or strings resolved outside the window. Reviewers check this whenever they see a `Dialog`, `AlertDialog`, `DatePickerDialog` or `DropdownMenu`.
- The presentation helpers' JVM tests resolve through `english()`, so they still pin the exact English copy.
- `LocalDisplayLocale` remains the one Locale value that crosses windows. It is the input to `AccountLocaleResources` too, so numbers, dates and words cannot disagree.
