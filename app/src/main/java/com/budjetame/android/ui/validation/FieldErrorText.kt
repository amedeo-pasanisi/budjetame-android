package com.budjetame.android.ui.validation

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier

/**
 * The Field Error render convention (ADR-0009, the Android mirror of the
 * web's ADR-0029): an entity form field with an error renders it inline,
 * beneath the field, in the error color — Material 3's `OutlinedTextField`
 * `isError` + `supportingText`, the pattern's first use in the app:
 *
 *     OutlinedTextField(
 *         value = ...,
 *         onValueChange = ...,
 *         isError = errors[FieldKey.AMOUNT] != null,
 *         supportingText = { FieldErrorText(errors[FieldKey.AMOUNT]) },
 *     )
 *
 * `isError` turns the border and the supporting text error-red, and M3
 * announces the supporting text as part of the field for TalkBack — the
 * error is read with its field, satisfying the a11y story. The helper
 * renders nothing when the field has no error, so a valid form carries no
 * error text anywhere. Errors update only on the next Save attempt
 * (ADR-0029); this helper never clears while typing, the form's state does.
 */
@Composable
fun FieldErrorText(error: String?, modifier: Modifier = Modifier) {
    if (error != null) {
        Text(
            text = error,
            color = MaterialTheme.colorScheme.error,
            modifier = modifier,
        )
    }
}