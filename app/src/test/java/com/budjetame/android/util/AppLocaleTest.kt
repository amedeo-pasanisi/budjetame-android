package com.budjetame.android.util

import java.util.Locale
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * AppLocale detection and tag mapping (i18n, ticket #59).
 */
class AppLocaleTest {

    @Test
    fun `detectDeviceTag maps it language to it`() {
        val original = Locale.getDefault()
        try {
            Locale.setDefault(Locale.ITALY)
            assertEquals("it", AppLocale.detectDeviceTag())
        } finally {
            Locale.setDefault(original)
        }
    }

    @Test
    fun `detectDeviceTag maps non-Italian to en`() {
        val original = Locale.getDefault()
        try {
            Locale.setDefault(Locale.US)
            assertEquals("en", AppLocale.detectDeviceTag())
        } finally {
            Locale.setDefault(original)
        }
    }

    @Test
    fun `detectDeviceTag maps any non-IT locale to en`() {
        val original = Locale.getDefault()
        try {
            Locale.setDefault(Locale.GERMANY)
            assertEquals("en", AppLocale.detectDeviceTag())
        } finally {
            Locale.setDefault(original)
        }
    }

    @Test
    fun `fromTag maps en to US locale`() {
        assertEquals(Locale.US, AppLocale.fromTag("en"))
    }

    @Test
    fun `fromTag maps it to Italy locale`() {
        assertEquals(Locale.ITALY, AppLocale.fromTag("it"))
    }

    @Test
    fun `fromTag maps any it prefix to Italy locale`() {
        assertEquals(Locale.ITALY, AppLocale.fromTag("it-IT"))
        assertEquals(Locale.ITALY, AppLocale.fromTag("it-CH"))
    }

    @Test
    fun `fromTag maps any non-IT tag to US locale`() {
        assertEquals(Locale.US, AppLocale.fromTag("de"))
        assertEquals(Locale.US, AppLocale.fromTag("fr"))
        assertEquals(Locale.US, AppLocale.fromTag("en-US"))
    }

    @Test
    fun `setFromTag updates current`() {
        val before = AppLocale.current
        AppLocale.setFromTag("it")
        assertEquals(Locale.ITALY, AppLocale.current)
        AppLocale.setFromTag("en")
        assertEquals(Locale.US, AppLocale.current)
    }
}