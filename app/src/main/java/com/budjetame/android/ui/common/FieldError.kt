package com.budjetame.android.ui.common

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable

/*
 * The Field Error half of the shared validation layer (ADR-0009, mirror of
 * web ADR-0029): submit-and-validate with inline Field Errors.
 *
 * The convention every entity form follows:
 *
 * 1. A pure `validate…(draft): FieldErrors` per form returns one message per
 *    invalid field, keyed by a form-local field key constant (e.g.
 *    `"amount"`, `"name"`, `"wallet"`); an empty map means valid. Money
 *    fields use [amountFieldError].
 * 2. The modal state holds `fieldErrors: FieldErrors = emptyMap()` beside —
 *    never inside — the existing server `error: String?` banner.
 * 3. Submit runs the validator first: errors → store them and return without
 *    touching the repository; empty → clear them and submit exactly as
 *    before. Typing never clears or recomputes them; only the next Save does.
 * 4. Save is enabled for in-flight work only (`enabled = !busy`), never gated
 *    on validation.
 * 5. Each field renders its message inline:
 *
 *    ```
 *    val error = modal.fieldErrors[AMOUNT]
 *    OutlinedTextField(
 *        …,
 *        label = { Text("Amount") },
 *        isError = error != null,
 *        supportingText = fieldErrorSupportingText(error),
 *    )
 *    ```
 *
 *    The same two lines go on a picker's read-only anchor OutlinedTextField
 *    (Wallet, From, To, Category).
 */

/**
 * A form's **Field Errors**: field key → the message shown inline beneath
 * that field. Client-side only — server rejections (a duplicate name, a
 * Transfer link that no longer qualifies) stay in the form's `error` banner
 * and are never Field Errors. An empty map is a valid form.
 */
typealias FieldErrors = Map<String, String>

/**
 * The `supportingText` slot for a field whose Field Error is [message]: the
 * message in the error color, or null when the field is fine — so a valid
 * field reserves no space and carries no error text. Pair it with
 * `isError = message != null`. A labeled M3 `OutlinedTextField` merges its
 * supporting text into the field's semantics, so TalkBack reads the message
 * as part of the field it belongs to.
 */
fun fieldErrorSupportingText(message: String?): (@Composable () -> Unit)? =
    message?.let { { Text(it, color = MaterialTheme.colorScheme.error) } }
