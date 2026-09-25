package com.budjetame.android.ui.shell

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.budjetame.android.data.auth.LocaleGateway
import com.budjetame.android.util.AppLocale
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * State for the Settings locale picker.
 *
 * @property currentLocale The current locale tag ("en" / "it").
 * @property updating True while an API call is in flight.
 * @property error An API error message, null when idle.
 */
data class LocalePickerState(
    val currentLocale: String = "en",
    val updating: Boolean = false,
    val error: String? = null,
)

/**
 * ViewModel for the Settings locale picker (i18n, ticket #59).
 */
class SettingsLocaleViewModel(
    private val gateway: LocaleGateway,
) : ViewModel() {

    private val _state = MutableStateFlow(LocalePickerState())
    val state: StateFlow<LocalePickerState> = _state.asStateFlow()

    init {
        viewModelScope.launch {
            val stored = try {
                gateway.fetchLocale()
            } catch (_: Exception) {
                null
            }
            val tag = stored?.takeIf { it == "it" || it == "en" } ?: AppLocale.detectDeviceTag()
            _state.update { it.copy(currentLocale = tag) }
        }
    }

    /** Switch to a new locale tag, persist it via the API, and update the app-wide locale. */
    fun selectLocale(tag: String) {
        if (tag == _state.value.currentLocale || _state.value.updating) return
        viewModelScope.launch {
            _state.update { it.copy(updating = true, error = null) }
            try {
                gateway.updateLocale(tag)
                AppLocale.setFromTag(tag)
                _state.update { it.copy(currentLocale = tag, updating = false) }
            } catch (_: Exception) {
                _state.update { it.copy(updating = false, error = "Could not update language setting.") }
            }
        }
    }
}