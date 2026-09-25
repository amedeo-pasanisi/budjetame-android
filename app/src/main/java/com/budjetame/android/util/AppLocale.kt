package com.budjetame.android.util

import java.util.Locale

/**
 * The app-wide locale for number and date formatting.
 *
 * Initialised to [Locale.US] before the auth check completes; after the
 * Account is loaded (or the fallback auto-detect runs) it is replaced with
 * the stored value.
 */
object AppLocale {

    @Volatile
    var current: Locale = Locale.US
        internal set

    /** Map an API tag ("en" / "it") to a Locale. */
    fun fromTag(tag: String): Locale = when {
        tag.startsWith("it") -> Locale.ITALY
        else -> Locale.US
    }

    /** Set [current] from an API tag. */
    fun setFromTag(tag: String) {
        current = fromTag(tag)
    }

    /** Detect the device locale: `it*` → `it`, everything else → `en`. */
    fun detectDeviceTag(): String {
        val device = Locale.getDefault()
        return if (device.language == "it") "it" else "en"
    }
}