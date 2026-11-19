package com.budjetame.android.data.locale

import java.util.Locale
import org.junit.Assert.assertEquals
import org.junit.Test

/** The once-per-Account-per-device auto-detect decision (issue #59). */
class LocaleAutoDetectTest {

    @Test
    fun `an en Account on an Italian device switches to it the first time`() {
        assertEquals(
            LocaleAutoDetect.SWITCH_TO_ITALIAN,
            localeAutoDetect(accountLocale = "en", deviceLocale = Locale.ITALY, alreadyRan = false),
        )
        assertEquals(
            LocaleAutoDetect.SWITCH_TO_ITALIAN,
            localeAutoDetect(accountLocale = "en", deviceLocale = Locale("it", "CH"), alreadyRan = false),
        )
    }

    @Test
    fun `an Account already detected on this device is never flipped again`() {
        assertEquals(
            LocaleAutoDetect.ALREADY_RAN,
            localeAutoDetect(accountLocale = "en", deviceLocale = Locale.ITALY, alreadyRan = true),
        )
    }

    @Test
    fun `a non-Italian device keeps en and still counts as detected`() {
        assertEquals(
            LocaleAutoDetect.KEEP,
            localeAutoDetect(accountLocale = "en", deviceLocale = Locale.US, alreadyRan = false),
        )
        assertEquals(
            LocaleAutoDetect.KEEP,
            localeAutoDetect(accountLocale = "en", deviceLocale = Locale.FRANCE, alreadyRan = false),
        )
    }

    @Test
    fun `an Account already on it is kept`() {
        assertEquals(
            LocaleAutoDetect.KEEP,
            localeAutoDetect(accountLocale = "it", deviceLocale = Locale.ITALY, alreadyRan = false),
        )
        assertEquals(
            LocaleAutoDetect.KEEP,
            localeAutoDetect(accountLocale = "it", deviceLocale = Locale.US, alreadyRan = false),
        )
    }
}
