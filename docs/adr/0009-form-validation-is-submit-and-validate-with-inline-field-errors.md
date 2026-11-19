# Form validation is submit-and-validate with inline Field Errors, hand-rolled without a validation library — mirror of web ADR-0029

The entity forms (Transaction, Recurring Cost/Income, Category, Wallet, Import row) disabled their Save button until a per-form `canSubmit` predicate flipped true, so a user with one wrong field got a dead button and no reason. The live case: `17,5` typed on a comma-locale decimal keypad reached a `parseAmount` built on `BigDecimal(String)`, which only reads dot decimals — the amount parsed to null and Save sat disabled with zero feedback. Web ADR-0029 replaced the same pattern with **submit-and-validate**; the Android app mirrors the decision by adoption, as it did for web ADR-0024/0025/0026/0027: Save is always clickable and, on a failed validation, reveals a **Field Error** inline under each wrong field and submits nothing.

## The policy, as on the web

- **Save is disabled only for in-flight work** — `busy`, plus per-form flags such as the Transaction form's `locating` and the Recurring forms' Occurrence toggles — never for validation. Every `canSubmit`-style gate moves into the submit path.
- **Two error kinds stay separate.** Field Errors (client-side, computable instantly) render inline beneath their field and live in the modal state's own field-errors map. Server rejections (duplicate name, merge collisions, rule errors such as a no-longer-qualifying Transfer link) keep the existing `error` banner — a client validator cannot know them without a round-trip. The two never mix.
- **Field Errors update only on the next Save attempt** — no live re-validation while typing, no on-focus-loss validation. An error stays under a field the user is already fixing until Save is pressed again.
- **The silent Contact-wallet reset becomes a Field Error.** Switching an Expense on a Contact Wallet to Income no longer silently swaps the Wallet (the ADR-0017 guard in `onTypeChange`); the selection stays and the Wallet field shows "Incomes can't be recorded on contact wallets." at Save.
- **Login is untouched**, and so are frozen/read-only renderings.

## Amount Input: last separator wins

Money fields parse with the web's **Amount Input** contract: the final `.` or `,` is the decimal point, earlier ones are thousands groupings, and a lone separator followed by exactly three digits is a thousands grouping. So `17.5`, `17,5`, `2,002.01` (2002.01), `1.000.420,45` (1000420.45), `1.000` (1000), `2,500` (2500) and `1.000,00` (1000.00) all parse; surrounding whitespace is trimmed; empty, letters, signs, exponents, inner spaces, malformed groupings and non-positive values are not amounts. Display, storage, the wire and Export stay US-canonical: forms send the parsed value's plain string, never the typed text. The web's root cause (the browser parsing `type="number"` in the OS locale) has no Android analog — a Compose text field with `KeyboardType.Decimal` hands the app every character — so there is no `noValidate` counterpart: the fix is purely the parser plus never gating Save on validation.

## One shared layer, zero new dependencies

`ui/common/AmountInput.kt` holds `parseAmountInput` (the contract above, returning a positive `BigDecimal` or null) and `amountFieldError` with the three Amount messages, identical to the web's copy ("Enter an amount", "That doesn't look like an amount — use digits and one . or , for decimals", "Amount must be a positive number"). `ui/common/FieldError.kt` holds the `FieldErrors` type (field key → message, empty = valid) and `fieldErrorSupportingText`, the render half: each field passes `isError = message != null` and `supportingText = fieldErrorSupportingText(message)` to its Material 3 `OutlinedTextField` — picker anchors included — so the message renders in the error color beneath the field and, because a labeled `OutlinedTextField` merges its descendants' semantics, TalkBack reads it as part of the field. Each form owns a small pure `validate…()` returning `FieldErrors`; the validation logic is plain Kotlin predicates (`> 0`, `≥ 1`, `source != destination`, `name.isNotBlank()`, date present). No validation library is introduced, for the web's reasons: nothing at this scale needs nesting or unions, and the interesting parts (server errors, merge offers, freeze confirmations, the type cascade, the inline-create sentinels of ADR-0013) live outside any library anyway.

## Considered Options

- **Keep disabled-until-valid, add a "why" hint** — rejected, as on the web: a user cannot discover a reason for a disabled control.
- **Validate live (on change / focus loss)** — rejected: live errors on untouched forms feel hostile; errors appear only on a Save attempt and refresh only on the next.
- **Strict US input (reject `17,5`)** — rejected: it rejects the comma-decimal habit that started the ticket, and tolerance costs nothing to display, storage or Export.
- **A lone separator is always the decimal point** — rejected: `1.000` would read 1.00; no realistic money amount has three decimals.
- **Locale-aware parsing (`DecimalFormat` for the device locale)** — rejected: the keyboard locale, the device locale and the user's habit disagree often enough (paste, a second keyboard) that only a locale-independent rule is predictable, and it matches the web exactly.

## Consequences

- The six entity forms (#51–#56) change Save to in-flight-only, gain a `fieldErrors` map on their modal state, and render per-field errors; their JVM suites move from asserting the `canSubmit` shape to submit-and-reveal (an invalid submit sets the errors and writes nothing).
- The old dot-only `ui.transactions.parseAmount` is superseded by `parseAmountInput`: each form switches as it migrates, and the old function is removed once its last caller is gone.
- A new Wallet's Opening balance inherits the tolerant parser; blank still means €0.
- A future language preference changes only how amounts are displayed; the parser already accepts both separators.
