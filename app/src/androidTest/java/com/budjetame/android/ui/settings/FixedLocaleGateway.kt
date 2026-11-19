package com.budjetame.android.ui.settings

import com.budjetame.android.data.locale.LocaleGateway
import kotlinx.coroutines.flow.MutableStateFlow

/** The shell's Locale in UI tests: in memory, `en` unless told otherwise. */
class FixedLocaleGateway(code: String = "en") : LocaleGateway {

    override val locale = MutableStateFlow(code)

    override suspend fun changeLocale(code: String) {
        locale.value = code
    }
}
