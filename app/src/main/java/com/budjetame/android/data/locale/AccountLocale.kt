package com.budjetame.android.data.locale

import java.util.Locale
import kotlinx.coroutines.flow.StateFlow

/**
 * The Account's Locale (CONTEXT.md: Locale, issue #59): `en` or `it`, stored
 * on the Account and served by `GET /auth/me` like every other Account
 * field. [locale] is the app's single observable source for it — fed by
 * every `/auth/me` read the app already makes, never a read of its own —
 * and a Settings change goes through [changeLocale].
 */
interface LocaleGateway {

    /** The signed-in Account's Locale code: `en` or `it`. */
    val locale: StateFlow<String>

    /** Store [code] on the Account (`PUT /auth/me/language`), then publish
     * it on [locale]. A failure throws and leaves [locale] as it was. */
    suspend fun changeLocale(code: String)
}

const val LOCALE_EN = "en"
const val LOCALE_IT = "it"

/** The server's Locale code, anything unknown read as `en`. */
fun localeCode(serverValue: String): String = if (serverValue == LOCALE_IT) LOCALE_IT else LOCALE_EN

/** The [Locale] numbers and dates are rendered in for an Account Locale
 * code: Italian for `it`, US English (the app's historical output) for
 * everything else. */
fun displayLocale(code: String): Locale = if (code == LOCALE_IT) Locale.ITALY else Locale.US

/** What the once-per-Account-per-device auto-detect does at start. */
enum class LocaleAutoDetect {
    /** This Account was already detected on this device: nothing to do. */
    ALREADY_RAN,

    /** Keep the Account's Locale, and remember that detection ran. */
    KEEP,

    /** Store `it` on the Account, then remember that detection ran. */
    SWITCH_TO_ITALIAN,
}

/**
 * The auto-detect decision (CONTEXT.md: Locale is auto-detected once). The
 * server column defaults to `en`, so a stored `en` cannot be told apart
 * from a chosen one: only the first start of an Account on a device may
 * flip `en` to `it`, and only when the device speaks Italian (`it*`).
 * Every first start counts as the detection, whatever it decides, so a
 * user who later picks English is never flipped back.
 */
fun localeAutoDetect(accountLocale: String, deviceLocale: Locale, alreadyRan: Boolean): LocaleAutoDetect =
    when {
        alreadyRan -> LocaleAutoDetect.ALREADY_RAN
        accountLocale == LOCALE_EN && deviceLocale.language == LOCALE_IT -> LocaleAutoDetect.SWITCH_TO_ITALIAN
        else -> LocaleAutoDetect.KEEP
    }
