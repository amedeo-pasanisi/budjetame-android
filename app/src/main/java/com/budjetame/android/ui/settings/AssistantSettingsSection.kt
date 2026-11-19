package com.budjetame.android.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuAnchorType
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.budjetame.android.data.api.AssistantModelDto
import com.budjetame.android.data.api.AssistantProvider
import com.budjetame.android.data.assistant.AssistantSettingsGateway

/** The most model rows the picker lists at once; typing narrows the list
 * (OpenRouter's catalogue runs to hundreds). */
private const val MAX_LISTED_MODELS = 50

/**
 * The Assistant Settings block of the Settings dialog (issue #66): with no
 * settings, provider, Assistant Key, model and Save; once configured, only
 * "provider / model, key ends in …" with Replace and Remove. The key field
 * is masked and never pre-filled.
 */
@Composable
fun AssistantSettingsSection(assistantSettings: AssistantSettingsGateway) {
    val viewModel: AssistantSettingsViewModel = viewModel { AssistantSettingsViewModel(assistantSettings) }
    val state by viewModel.uiState.collectAsStateWithLifecycle()

    // The block is composed while the dialog shows: reload on opening, and
    // drop a key typed but not saved on closing.
    DisposableEffect(viewModel) {
        viewModel.open()
        onDispose { viewModel.close() }
    }

    Column {
        Text(
            text = "Assistant",
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.Medium,
        )
        Text(
            text = "Dictate or type money movements: the Assistant turns them into Draft Transactions. " +
                "Choose a provider and enter its key.",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 4.dp),
        )
        Text(
            text = statusLine(state),
            style = MaterialTheme.typography.labelSmall,
            modifier = Modifier.padding(top = 8.dp),
        )

        if (state.editing) {
            AssistantSettingsForm(state, viewModel)
        } else if (state.configured) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                TextButton(onClick = viewModel::startReplace, enabled = !state.saving) { Text("Replace") }
                TextButton(onClick = viewModel::remove, enabled = !state.saving) {
                    Text(
                        text = if (state.saving) "Removing…" else "Remove",
                        color = MaterialTheme.colorScheme.error,
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

private fun statusLine(state: AssistantSettingsViewModel.UiState): String {
    val status = state.status
    return when {
        state.loading -> "Loading…"
        status == null -> ""
        status.configured && status.provider != null ->
            "Configured: ${status.provider.label} / ${status.model ?: "provider's default"}, " +
                "key ends in …${status.last4.orEmpty()}"
        else -> "Not configured."
    }
}

@Composable
private fun AssistantSettingsForm(
    state: AssistantSettingsViewModel.UiState,
    viewModel: AssistantSettingsViewModel,
) {
    Column(
        verticalArrangement = Arrangement.spacedBy(8.dp),
        modifier = Modifier.padding(top = 8.dp),
    ) {
        ProviderField(value = state.provider, onSelect = viewModel::onProviderChange)
        OutlinedTextField(
            value = state.key,
            onValueChange = viewModel::onKeyChange,
            label = { Text("Assistant Key") },
            singleLine = true,
            visualTransformation = PasswordVisualTransformation(),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, autoCorrectEnabled = false),
            isError = state.keyError != null,
            supportingText = state.keyError?.let { { Text(it) } },
            modifier = Modifier
                .fillMaxWidth()
                .onFocusChanged { if (!it.isFocused) viewModel.onKeyFieldLeft() },
        )
        ModelField(
            value = state.model,
            models = state.models,
            hint = state.catalogue.message,
            onChange = viewModel::onModelChange,
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            TextButton(onClick = viewModel::save, enabled = !state.saving) {
                Text(if (state.saving) "Saving…" else "Save")
            }
            if (state.configured) {
                TextButton(onClick = viewModel::cancelReplace, enabled = !state.saving) { Text("Cancel") }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ProviderField(value: AssistantProvider, onSelect: (AssistantProvider) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    ExposedDropdownMenuBox(expanded = expanded, onExpandedChange = { expanded = it }) {
        OutlinedTextField(
            value = value.label,
            onValueChange = {},
            readOnly = true,
            label = { Text("Provider") },
            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded) },
            modifier = Modifier
                .fillMaxWidth()
                .menuAnchor(ExposedDropdownMenuAnchorType.PrimaryNotEditable),
        )
        ExposedDropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            AssistantProvider.entries.forEach { provider ->
                DropdownMenuItem(
                    text = { Text(provider.label) },
                    onClick = {
                        onSelect(provider)
                        expanded = false
                    },
                )
            }
        }
    }
}

/** The model id: picked from the provider's list, which typing narrows, or
 * typed as is. Blank means the provider's default. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ModelField(
    value: String,
    models: List<AssistantModelDto>,
    hint: String?,
    onChange: (String) -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    val matches = models
        .filter { value.isBlank() || it.id.contains(value, ignoreCase = true) || it.label.contains(value, ignoreCase = true) }
        .take(MAX_LISTED_MODELS)
    ExposedDropdownMenuBox(expanded = expanded, onExpandedChange = { expanded = it }) {
        OutlinedTextField(
            value = value,
            onValueChange = {
                onChange(it)
                expanded = true
            },
            label = { Text("Model") },
            placeholder = { Text("Provider's default") },
            singleLine = true,
            supportingText = hint?.let { { Text(it) } },
            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded) },
            modifier = Modifier
                .fillMaxWidth()
                .menuAnchor(ExposedDropdownMenuAnchorType.PrimaryEditable),
        )
        ExposedDropdownMenu(
            expanded = expanded && matches.isNotEmpty(),
            onDismissRequest = { expanded = false },
        ) {
            matches.forEach { model ->
                DropdownMenuItem(
                    text = { Text(if (model.free) "${model.label} (free)" else model.label) },
                    onClick = {
                        onChange(model.id)
                        expanded = false
                    },
                )
            }
        }
    }
}
