package com.budjetame.android.ui.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.budjetame.android.R
import com.budjetame.android.data.locale.LocaleGateway
import com.budjetame.android.ui.common.UiText
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * The Language choice of the Settings dialog (issue #59, the web's
 * `settings.language` select): it shows the Account's Locale and stores a
 * new one through the [LocaleGateway], which publishes it to the whole app
 * — amounts, dates and the next Dictation — only once the server took it.
 */
class LanguageSettingsViewModel(private val gateway: LocaleGateway) : ViewModel() {

    data class UiState(
        /** The Account's Locale code: `en` or `it`. */
        val locale: String,
        val saving: Boolean = false,
        val error: UiText? = null,
    )

    private data class Write(val saving: Boolean = false, val error: UiText? = null)

    private val write = MutableStateFlow(Write())

    val uiState: StateFlow<UiState> = combine(gateway.locale, write) { locale, write ->
        UiState(locale = locale, saving = write.saving, error = write.error)
    }.stateIn(viewModelScope, SharingStarted.Eagerly, UiState(locale = gateway.locale.value))

    /** The dialog closed: the ViewModel outlives it, a past error does not. */
    fun dismissError() {
        write.update { if (it.saving) it else Write() }
    }

    fun choose(code: String) {
        if (write.value.saving || code == gateway.locale.value) return
        write.value = Write(saving = true)
        viewModelScope.launch {
            try {
                gateway.changeLocale(code)
                write.value = Write()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                write.update { Write(error = UiText.of(R.string.errors_settings_language)) }
            }
        }
    }
}
