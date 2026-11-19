package com.budjetame.android.ui.settings

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuAnchorType
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.budjetame.android.R
import com.budjetame.android.data.locale.LOCALE_EN
import com.budjetame.android.data.locale.LOCALE_IT
import com.budjetame.android.data.locale.LocaleGateway

/** The Locales the Language choice offers, each named in its own language
 * (the web's `settings.language.en` / `.it`). */
private val LANGUAGES = listOf(LOCALE_EN to "English", LOCALE_IT to "Italiano")

/**
 * The Language block of the Settings dialog (issue #59, the web's
 * `settings.language` select): the Account's Locale, and a choice that
 * stores a new one. A failed write says so and keeps the previous Locale.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LanguageSettingsSection(locale: LocaleGateway) {
    val viewModel: LanguageSettingsViewModel = viewModel { LanguageSettingsViewModel(locale) }
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    var expanded by remember { mutableStateOf(false) }
    DisposableEffect(viewModel) { onDispose { viewModel.dismissError() } }

    Column {
        Text(
            text = stringResource(R.string.settings_language),
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.Medium,
        )
        Text(
            text = stringResource(R.string.settings_language_description),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 4.dp),
        )
        ExposedDropdownMenuBox(
            expanded = expanded,
            onExpandedChange = { expanded = it && !state.saving },
            modifier = Modifier.padding(top = 8.dp),
        ) {
            OutlinedTextField(
                value = LANGUAGES.first { it.first == state.locale }.second,
                onValueChange = {},
                readOnly = true,
                enabled = !state.saving,
                label = { Text(stringResource(R.string.settings_language)) },
                trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded) },
                modifier = Modifier
                    .fillMaxWidth()
                    .menuAnchor(ExposedDropdownMenuAnchorType.PrimaryNotEditable),
            )
            ExposedDropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
                LANGUAGES.forEach { (code, name) ->
                    DropdownMenuItem(
                        text = { Text(name) },
                        onClick = {
                            expanded = false
                            viewModel.choose(code)
                        },
                    )
                }
            }
        }
        state.error?.let {
            Text(
                text = it,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.error,
                modifier = Modifier.padding(top = 4.dp),
            )
        }
    }
}
