# Entity forms validate on submit with inline Field Errors, hand-rolled without a validation library — mirror of web ADR-0029

The entity forms (Transaction, Recurring Cost/Income, Category, Wallet, Import row) disable their Save button until a per-form `canSubmit` expression flips true — so a user who types `17,5` into an Amount field (natural on a comma-locale decimal keypad) hits a `parseAmount` that only accepts `BigDecimal`'s dot format: the amount parses to null, `canSubmit` goes false, and Save sits dead with zero feedback. The user must guess which field is wrong and why. The web app solved the same incident with **submit-and-validate** (web ADR-0029); this ADR records the Android port of that decision.

## The policy, agreed at grilling (mirroring web ADR-0029)

- **Save is disabled only for in-flight work** — submitting, and per-form busy flags (the Transaction form's GPS `locating`, the Recurring forms' occurrence toggles) — never for validation. All `canSubmit`-style gates are deleted; validation moves into the submit path.
- **Two error kinds stay separate.** Field Errors (client-side, computable instantly) render inline beneath their field. Server rejections (duplicate names, merge collisions, rule errors like a no-longer-qualifying Transfer link) keep the existing form-level error banner — a validation layer cannot know them without an HTTP round-trip.
- **Field Errors update only on the next Save attempt** (no live re-validation while typing, no on-blur): an error stays under a field until Save is clicked again and the form passes.
- **The submit flow.** The submit handler runs the form's `validate()` first; any errors are stored on the modal state as a field-key → message map (a **Field Error**, kept distinct from the existing server `error` string) and rendered under their fields, and the submit returns without calling the repository; a valid result clears the errors and proceeds exactly as today.
- **Frozen/read-only and auth forms are untouched.** LoginForm keeps its behavior; the frozen/read-only rendering of Wallet and Recurring forms is unchanged.

## The Amount Input contract — tolerant money entry, the actual fix

Amounts parse with **last-separator-wins**: the final `,` or `.` is the decimal point, earlier ones are thousands groupings; a lone separator with exactly three digits after it is a thousands grouping (`1.000` → 1000, `2,500` → 2500 — no realistic money amount has three decimals). So `17.5`, `17,5`, `2,002.01`, `1.000.420,45`, and `1.000,00` all parse to the same values the web accepts. Display, storage, and export stay US-canonical until i18n adds per-locale display — the tolerant parser is kept as-is then, since it already understands both styles.

Android has no browser-style number-input parsing to fight (unlike the web's `type="number"` swallowing an Italian-locale dot): the Amount/Opening balance fields keep `KeyboardType.Decimal` and their current text behavior, so tolerance is purely a parse concern. There is no `noValidate` analog — the rule is simply *never gate Save on validation*.

## One shared module, zero new dependencies

`ui/validation/` holds the whole layer, so no form re-implements any of it and the six per-form validators build on the same pieces: the tolerant `parseAmount` (replacing the dot-only one), the `FieldErrors` map type and `fieldErrors()` builder, the shared `FieldKey`s and the user-visible message contract (`Messages`), the `amountErrorMessage` mapping (blank → "Enter an amount", unparseable → "That doesn't look like an amount — use digits and one . or , for decimals", zero → "Amount must be a positive number"), and the render helper. No validation library is introduced: the validation logic is plain Kotlin predicates (`> 0`, `≥ 1`, `source !== destination`, `name.trim() !== ''`, date present), and the interesting parts (server errors, merge offers, freeze confirmations, the type cascade) live outside any validation layer anyway (web ADR-0029's reasoning binds here too).

## Field Error rendering — Material 3 `isError` + `supportingText`

The render convention is the app's first use of the pattern: an `OutlinedTextField` with an error sets `isError = errors[field] != null` and renders the message as `supportingText = { FieldErrorText(errors[field]) }` — the shared helper draws the message in the error color, `isError` turns the border error-red, and Material 3 announces the supporting text as part of the field, so TalkBack reads each error with its field. Non-field errors keep the existing `modal.error` banner at the bottom of the form.

## The Transaction form's one behavior change

The silent Contact-wallet reset in `onTypeChange`'s INCOME branch (ADR-0017's guard) is deleted; the new Wallet Field Error ("Incomes can't be recorded on contact wallets.") enforces the rule at Save instead. The Wallet picker for an Income still only offers spendable Wallets (unchanged), so the error is only reachable by switching an Expense that picked a Contact Wallet.

## Considered Options

- **Keep disabled-until-valid, add a "why" hint** — rejected: the `17,5` case proved the button itself is the puzzle; a user can't discover a reason for a disabled control.
- **Validate live (on-change/on-blur) with inline errors** — rejected: live errors on untouched forms feel hostile; errors reveal only on a Save attempt and refresh only on the next.
- **A validation library (zod-style)** — rejected: adds the app's first non-UI dependency to re-implement the same per-form predicates, and would force the custom controls (entity selects with inline-create sentinels, the map picker) into a validation framework. The hand-rolled layer is a few dozen lines.
- **Strict US input (reject `17,5`)** — rejected: it rejects the very comma-decimal habit that started the ticket; tolerance costs nothing to display/storage/exports, which stay US.
- **Read a lone separator as always-decimal** — rejected: `1.000` would parse to 1.00 instead of 1,000; a lone separator with exactly three trailing digits is treated as grouping.

## Consequences

- The six entity forms' Save buttons become in-flight-only and render per-field errors; their `canSubmit` gates are deleted and the validation moves into the submit path, one form at a time (tickets #51–#56).
- The existing per-form suites rename/extend their "button disabled when X" assertions to "Save reveals 'X' under the field" — external behavior at the model/UI boundary, never `validate()` called directly in a way that bypasses the submit path.
- `TransactionFormModel.kt`'s dot-only `parseAmount` is replaced by the shared tolerant parser, form by form, as tickets #51–#56 land; callers keep their contracts (positive value or null). The shared layer itself changes no form behavior.
- Future i18n only changes how amounts are *displayed* and which keyboard hint is shown; the parser already accepts both separators and is kept as-is (like the web).