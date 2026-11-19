package com.budjetame.android.data.locale

import android.content.Context
import java.util.Locale

/**
 * The last Account Locale this device saw (issue #63). The login screen runs
 * before any Account is read, so it renders in this Locale, and in the
 * device language when nothing is stored yet. Only the Locale code is kept:
 * no Account data survives a sign-out.
 */
interface LastLocaleStore {
    fun read(): String?
    fun write(code: String)

    /** Nothing stored, nothing kept: the device language decides. */
    object None : LastLocaleStore {
        override fun read(): String? = null
        override fun write(code: String) = Unit
    }
}

/** The device's own record, in plain preferences: a Locale code is no secret. */
class PrefsLastLocaleStore(context: Context) : LastLocaleStore {

    private val prefs = context.getSharedPreferences("last_locale", Context.MODE_PRIVATE)

    override fun read(): String? = prefs.getString(KEY, null)

    override fun write(code: String) {
        prefs.edit().putString(KEY, code).apply()
    }

    private companion object {
        const val KEY = "locale"
    }
}

/** The Locale before any Account is read: the last one stored on the
 * device, or the device language (`it*` reads `it`, anything else `en`). */
fun signedOutLocale(stored: String?, device: Locale): String =
    stored?.let(::localeCode) ?: if (device.language == LOCALE_IT) LOCALE_IT else LOCALE_EN
