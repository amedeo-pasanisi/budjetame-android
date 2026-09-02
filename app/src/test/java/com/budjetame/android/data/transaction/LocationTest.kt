package com.budjetame.android.data.transaction

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * The location helpers (ticket #29), ported from the web app's location.ts
 * — the pure rules the seam tests rely on: the maps link's precedence and
 * exact bytes (place_id → name → coordinates, never stored as text), the
 * coordinate and Place wire round-trips, and the name-anchored Place rule
 * (ADR-0005). The web's own suite pins these expectations; this file is the
 * Android side of the same contract.
 */
class LocationTest {

    @Test
    fun `mapLink builds a Google Maps search URL from coordinates`() {
        assertEquals(
            "https://www.google.com/maps/search/?api=1&query=41.9028,12.4964",
            mapLink(LatLng(41.9028, 12.4964)),
        )
    }

    @Test
    fun `formatLocation renders the short coordinate pair`() {
        assertEquals("41.9028, 12.4964", formatLocation(LatLng(41.9028, 12.4964)))
    }

    // The Place reference (ADR-0005): a picked Place opens via Google's
    // documented place-with-pin search URL — coordinates as the query,
    // place_id as query_place_id. The mobile Maps apps run it as a search:
    // a place_id they can't resolve still lands a pin on the coordinates.
    @Test
    fun `mapLink with a Place carrying a place_id uses the place-with-pin search URL`() {
        assertEquals(
            "https://www.google.com/maps/search/?api=1" +
                "&query=41.9028,12.4964&query_place_id=ChIJN1t_tDeuEmsRUsoyG83frY4",
            mapLink(LatLng(41.9028, 12.4964), Place("Esselunga", "ChIJN1t_tDeuEmsRUsoyG83frY4")),
        )
    }

    @Test
    fun `mapLink with a name-only Place searches the encoded name`() {
        assertEquals(
            "https://www.google.com/maps/search/?api=1&query=Esselunga%20Bar",
            mapLink(LatLng(41.9028, 12.4964), Place("Esselunga Bar")),
        )
    }

    @Test
    fun `mapLink without a Place is a plain coordinate search`() {
        assertEquals(
            "https://www.google.com/maps/search/?api=1&query=41.9028,12.4964",
            mapLink(LatLng(41.9028, 12.4964), null),
        )
    }

    @Test
    fun `mapLink percent-encodes like encodeURIComponent`() {
        // The web's encodeURIComponent byte-for-byte: a space is %20 (never
        // the form encoding's '+'), UTF-8 bytes upper-case hex, and the
        // unreserved characters pass through untouched.
        assertEquals(
            "https://www.google.com/maps/search/?api=1&query=Caf%C3%A9%20%26%20Bar",
            mapLink(LatLng(41.9028, 12.4964), Place("Café & Bar")),
        )
        assertEquals(
            "https://www.google.com/maps/search/?api=1" +
                "&query=41.9028,12.4964&query_place_id=ChIJ%20id%26x%3F",
            mapLink(LatLng(41.9028, 12.4964), Place("x", "ChIJ id&x?")),
        )
    }

    @Test
    fun `coordinates round-trip through the wire format`() {
        val position = LatLng(41.9028, 12.4964)
        val wire = latLngToWire(position)
        assertEquals("41.9028", wire.latitude)
        assertEquals("12.4964", wire.longitude)
        assertEquals(position, latLngFromWire(wire.latitude, wire.longitude))
    }

    @Test
    fun `missing or unparsable coordinates mean no location`() {
        assertNull(latLngFromWire(null, null))
        assertNull(latLngFromWire("not-a-number", "12.4"))
        // A NaN parse is a missing half too (the web's Number.parseFloat).
        assertNull(latLngFromWire("NaN", "12.4"))
    }

    @Test
    fun `a null position serializes to null coordinates`() {
        assertEquals(WireLatLng(null, null), latLngToWire(null))
    }

    @Test
    fun `a Place round-trips through the wire format`() {
        val wire = placeToWire(Place("Esselunga", "ChIJabc"))
        assertEquals(WirePlace("Esselunga", "ChIJabc"), wire)
        assertEquals(Place("Esselunga", "ChIJabc"), placeFromWire(wire.place_name, wire.place_id))
    }

    @Test
    fun `a Place with a name but no place_id stays a name-only Place`() {
        assertEquals(Place("Esselunga"), placeFromWire("Esselunga", null))
        assertEquals(WirePlace("Esselunga", null), placeToWire(Place("Esselunga")))
    }

    @Test
    fun `a missing or empty name means no Place`() {
        assertNull(placeFromWire(null, "ChIJabc"))
        assertNull(placeFromWire("", "ChIJabc"))
    }

    @Test
    fun `a null Place serializes to null place fields`() {
        assertEquals(WirePlace(null, null), placeToWire(null))
    }
}
