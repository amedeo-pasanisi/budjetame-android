package com.budjetame.android.ui.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.budjetame.android.data.api.ApiException
import com.budjetame.android.data.api.AssistantModelDto
import com.budjetame.android.data.api.AssistantProvider
import com.budjetame.android.data.api.AssistantSettingsDto
import com.budjetame.android.data.assistant.AssistantSettingsGateway
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** The model list's state, with the line the picker shows under itself
 * (the web block's messages, keyed by the listing's status). */
enum class CatalogueState(val message: String?) {
    IDLE(null),
    LOADING("Loading the models…"),
    READY(null),
    /** 409: the provider lists its models only with a key. */
    KEY_NEEDED("Enter the Assistant Key to list this provider's models, or type a model id."),
    /** 422: the provider rejected the key. */
    KEY_REJECTED("The provider rejected the Assistant Key: check it, or type a model id."),
    ERROR("The model list is unavailable right now: type a model id or try again later."),
}

/**
 * The Assistant Settings block of the Settings dialog (issue #66, web issues
 * #126/#127): provider, Assistant Key and model, Save, and once configured
 * only the status line with Replace and Remove.
 *
 * The ViewModel outlives the dialog (it lives in the Activity's store), so
 * the dialog calls [open] when it shows and [close] when it goes: the status
 * is reloaded on every opening (the web block is re-mounted the same way)
 * and a key typed but not saved is dropped on closing. The full key lives
 * only in [UiState.key] while typed and is cleared on Save; the server only
 * ever returns its last four characters.
 */
class AssistantSettingsViewModel(private val gateway: AssistantSettingsGateway) : ViewModel() {

    data class UiState(
        val loading: Boolean = true,
        val status: AssistantSettingsDto? = null,
        /** The form is showing: no settings yet, or Replace was tapped. */
        val editing: Boolean = false,
        val provider: AssistantProvider = AssistantProvider.ANTHROPIC,
        val key: String = "",
        /** The model id, picked from [models] or typed; blank is the provider's default. */
        val model: String = "",
        val models: List<AssistantModelDto> = emptyList(),
        val catalogue: CatalogueState = CatalogueState.IDLE,
        val keyError: String? = null,
        val error: String? = null,
        val saving: Boolean = false,
    ) {
        val configured: Boolean get() = status?.configured == true
    }

    private val _uiState = MutableStateFlow(UiState())
    val uiState: StateFlow<UiState> = _uiState.asStateFlow()

    private var loadingStatus: Job? = null
    private var listing: Job? = null

    /** The unsaved key the current listing was made with, if any. */
    private var listedWithKey: String? = null

    fun open() {
        close()
        loadingStatus = viewModelScope.launch {
            try {
                val status = gateway.fetchStatus()
                _uiState.update { formFor(status) }
                if (!status.configured) listModels()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                _uiState.update { it.copy(loading = false, error = "Could not load the Assistant Settings.") }
            }
        }
    }

    /** Drop everything the block holds, the typed key first. */
    fun close() {
        loadingStatus?.cancel()
        listing?.cancel()
        listedWithKey = null
        _uiState.value = UiState()
    }

    fun startReplace() {
        _uiState.update { it.copy(editing = true, key = "", keyError = null, error = null) }
        listModels()
    }

    fun cancelReplace() {
        val status = _uiState.value.status ?: return
        listing?.cancel()
        _uiState.update { formFor(status) }
    }

    fun onKeyChange(value: String) {
        _uiState.update { it.copy(key = value, keyError = null) }
    }

    /** The key field lost focus: when the listing is waiting for a key (or
     * the provider rejected the one sent), list again with the key typed —
     * sent once, in a header, before Save. */
    fun onKeyFieldLeft() {
        val state = _uiState.value
        val key = state.key.trim()
        val waitingForKey = state.catalogue == CatalogueState.KEY_NEEDED ||
            state.catalogue == CatalogueState.KEY_REJECTED
        if (state.editing && waitingForKey && key.isNotEmpty() && key != listedWithKey) listModels(key)
    }

    /** Switching provider resets the model to the provider's default and
     * lists the new provider's models. */
    fun onProviderChange(value: AssistantProvider) {
        if (value == _uiState.value.provider) return
        _uiState.update { it.copy(provider = value, model = "") }
        listModels()
    }

    fun onModelChange(value: String) {
        _uiState.update { it.copy(model = value) }
    }

    fun save() {
        val state = _uiState.value
        if (state.saving) return
        if (state.key.isBlank()) {
            _uiState.update { it.copy(keyError = "Enter the Assistant Key.") }
            return
        }
        viewModelScope.launch {
            _uiState.update { it.copy(saving = true, error = null) }
            try {
                val saved = gateway.save(state.provider, state.key, state.model)
                listing?.cancel()
                _uiState.update { formFor(saved) }
            } catch (error: ApiException) {
                _uiState.update {
                    it.copy(saving = false, error = error.detail ?: "Could not save the Assistant Settings.")
                }
            } catch (_: Exception) {
                _uiState.update { it.copy(saving = false, error = "Could not save the Assistant Settings.") }
            }
        }
    }

    fun remove() {
        if (_uiState.value.saving) return
        viewModelScope.launch {
            _uiState.update { it.copy(saving = true, error = null) }
            try {
                gateway.remove()
                _uiState.update { formFor(AssistantSettingsDto(configured = false)) }
                listModels()
            } catch (_: Exception) {
                _uiState.update { it.copy(saving = false, error = "Could not remove the Assistant Settings.") }
            }
        }
    }

    /** The block's state for a loaded or saved status: the form only when
     * nothing is configured, on the configured provider and model, and
     * never a key. */
    private fun formFor(status: AssistantSettingsDto) = UiState(
        loading = false,
        status = status,
        editing = !status.configured,
        provider = status.provider ?: AssistantProvider.ANTHROPIC,
        model = status.model.orEmpty(),
    )

    /** List the chosen provider's models, replacing any listing in flight
     * (a response for a provider no longer shown is dropped with it). When
     * the provider lists only with a key and one is typed, it is sent for
     * one more try. */
    private fun listModels(unsavedKey: String? = null) {
        val provider = _uiState.value.provider
        listing?.cancel()
        listedWithKey = unsavedKey
        listing = viewModelScope.launch {
            _uiState.update { it.copy(models = emptyList(), catalogue = CatalogueState.LOADING) }
            var catalogue = fetchCatalogue(provider, unsavedKey)
            val typedKey = _uiState.value.key.trim()
            if (catalogue == CatalogueState.KEY_NEEDED && unsavedKey == null && typedKey.isNotEmpty()) {
                listedWithKey = typedKey
                catalogue = fetchCatalogue(provider, typedKey)
            }
            _uiState.update { it.copy(catalogue = catalogue) }
        }
    }

    private suspend fun fetchCatalogue(provider: AssistantProvider, unsavedKey: String?): CatalogueState = try {
        val models = gateway.fetchModels(provider, unsavedKey)
        _uiState.update { it.copy(models = models) }
        CatalogueState.READY
    } catch (error: ApiException) {
        when {
            error.status == 409 -> CatalogueState.KEY_NEEDED
            // The handler's 422 carries a string detail; FastAPI's own
            // validation 422 (a list) is a client bug, a plain failure.
            error.status == 422 && error.detail != null -> CatalogueState.KEY_REJECTED
            else -> CatalogueState.ERROR
        }
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (_: Exception) {
        CatalogueState.ERROR
    }
}
