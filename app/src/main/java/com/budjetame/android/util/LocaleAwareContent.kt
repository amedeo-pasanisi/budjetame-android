package com.budjetame.android.util

import android.content.Context
import android.content.res.Configuration
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import java.util.Locale

/**
 * Wraps the composition tree with a [Context] whose resources resolve to
 * [AppLocale.current] instead of the device locale.
 *
 * All [stringResource] calls inside [content] will use the app's active
 * locale, and the wrapper recomposes whenever [AppLocale.current] changes
 * (via `@Volatile` + [remember] key), so switching language in Settings
 * re-renders every screen in the chosen language instantly.
 *
 * Usage: wrap every screen that is visible after auth:
 * ```kotlin
 * LocaleAwareContent {
 *     AppShell(...)
 * }
 * ```
 *
 * Alternatively wrap only the content body inside the shell, keeping the
 * header/header buttons in the device locale. This implementation wraps
 * the content body so the header's "Sign out" and "Settings" also flip.
 */
@Composable
fun LocaleAwareContent(content: @Composable () -> Unit) {
    val context = LocalContext.current
    val locale = AppLocale.current
    val localeAwareContext = remember(context, locale) {
        wrapContextLocale(context, locale)
    }
    CompositionLocalProvider(LocalContext provides localeAwareContext) {
        content()
    }
}

/**
 * Create a locale-aware [Context] whose [Configuration] uses [locale].
 *
 * Uses [android.content.Context.createConfigurationContext] to produce a
 * context whose `resources` resolve string resources from the resource
 * directory that matches [locale].
 */
fun wrapContextLocale(base: Context, locale: Locale): Context {
    val config = Configuration(base.resources.configuration)
    config.setLocale(locale)
    return base.createConfigurationContext(config)
}