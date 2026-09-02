package com.budjetame.android.ui.maps

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The map-provider resolver (ADR-0004 parity, ticket #29), ported from the
 * web app's mapProvider.ts: anything that is not exactly `google` is the
 * free osmdroid fallback — a typo can never take down the picker — and a
 * `google` build without an API key fails loudly at resolution time with
 * the fix in the message, instead of rendering a broken map.
 */
class MapProviderConfigTest {

    @Test
    fun `an absent provider resolves to the free fallback`() {
        assertEquals(PROVIDER_LEAFLET, resolveMapProvider(null))
        assertEquals(PROVIDER_LEAFLET, resolveMapProvider(""))
        assertEquals(MapConfig.Leaflet, resolveMapConfig(null, null))
    }

    @Test
    fun `only exactly google selects the Google provider`() {
        assertEquals(PROVIDER_GOOGLE, resolveMapProvider("google"))
        // A typo is the fallback, never an error (web parity).
        assertEquals(PROVIDER_LEAFLET, resolveMapProvider("Google"))
        assertEquals(PROVIDER_LEAFLET, resolveMapProvider("gmaps"))
    }

    @Test
    fun `google with a key resolves to the Google config carrying the key`() {
        assertEquals(MapConfig.Google("AIza-key"), resolveMapConfig("google", "AIza-key"))
    }

    @Test
    fun `google without a key fails loudly with the fix in the message`() {
        val error = assertThrows(IllegalArgumentException::class.java) {
            resolveMapConfig("google", null)
        }
        assertTrue(error.message.orEmpty().contains("GOOGLE_MAPS_API_KEY"))
        assertTrue(error.message.orEmpty().contains("MAP_PROVIDER=leaflet"))
        assertThrows(IllegalArgumentException::class.java) {
            resolveMapConfig("google", "")
        }
    }
}
