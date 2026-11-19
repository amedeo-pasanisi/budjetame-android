package com.budjetame.android.ui.common

import androidx.compose.runtime.compositionLocalOf
import java.util.Locale

/**
 * The [Locale] screens render amounts and dates in (issue #59): the
 * Account's Locale, provided by the shell from the app's single observable
 * source (`LocaleGateway.locale`), so a change in Settings recomposes every
 * reader. Composables pass it to `Money` and `Dates`; outside the shell it is
 * US English, the app's historical output.
 */
val LocalDisplayLocale = compositionLocalOf { Locale.US }
