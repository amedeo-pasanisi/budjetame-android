package com.budjetame.android.ui.common

import android.content.Context
import android.content.ContextWrapper
import android.content.res.Configuration
import android.content.res.Resources
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalResources
import com.budjetame.android.data.locale.displayLocale

/**
 * The composition root's i18n frame (ADR-0010, issue #62): everything under
 * it renders in the Account's Locale — amounts and dates through
 * [LocalDisplayLocale], and `stringResource` / `pluralStringResource`
 * through [AccountLocaleResources] — whatever the device language. Each
 * Activity applies it once around its content, fed by the app's single
 * observable source (`LocaleGateway.locale`), so a change in Settings
 * re-renders in place.
 */
@Composable
fun ProvideAccountLocale(localeCode: String, content: @Composable () -> Unit) {
    CompositionLocalProvider(LocalDisplayLocale provides displayLocale(localeCode)) {
        AccountLocaleResources(content)
    }
}

/**
 * Resolves string resources in the Account's Locale ([LocalDisplayLocale]):
 * it provides a `LocalContext` / `LocalConfiguration` / `LocalResources`
 * whose configuration carries that Locale, so `values-it` answers for `it`
 * and the default `values` (English) for `en` — and for any key `values-it`
 * lacks.
 *
 * [ProvideAccountLocale] applies it at the root. **Every new window applies
 * it again**: a `Dialog`, an `AlertDialog`, a `DatePickerDialog`, a
 * `DropdownMenu` / `ExposedDropdownMenu` and any other `Popup` composes in
 * its own ComposeView, which re-provides the Activity's own `LocalContext`
 * and `LocalConfiguration` (device language). Only [LocalDisplayLocale]
 * crosses into the window, so wrapping the window's content here restores
 * the Account Locale — and re-renders it in place when the Locale changes
 * while the window is open (the Language choice lives in the Settings
 * dialog). Alternatively, resolve the strings outside the window and pass
 * them in as plain `String`s.
 */
@Composable
fun AccountLocaleResources(content: @Composable () -> Unit) {
    val locale = LocalDisplayLocale.current
    val current = LocalContext.current
    // Never stack wrappers: re-wrap the window's own context.
    val base = (current as? AccountLocaleContext)?.baseContext ?: current
    val baseConfiguration = LocalConfiguration.current
    val configuration = remember(baseConfiguration, locale) {
        Configuration(baseConfiguration).apply { setLocale(locale) }
    }
    val context = remember(base, configuration) {
        AccountLocaleContext(base, base.createConfigurationContext(configuration).resources)
    }
    CompositionLocalProvider(
        LocalContext provides context,
        LocalConfiguration provides configuration,
        LocalResources provides context.resources,
        content = content,
    )
}

/** A window slot that re-applies the Account Locale — shorthand for an
 * `AlertDialog`'s slots and a menu's content:
 * `title = localized { Text(stringResource(R.string.x)) }`. */
fun localized(content: @Composable () -> Unit): @Composable () -> Unit = { AccountLocaleResources(content) }

/** The window's own context (Activity or dialog), its resources swapped
 * for the Account Locale's: everything else — the Activity behind it,
 * system services, the theme — stays the base's. */
private class AccountLocaleContext(base: Context, private val localized: Resources) : ContextWrapper(base) {
    override fun getResources(): Resources = localized
}
