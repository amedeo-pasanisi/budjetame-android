package com.budjetame.android.data.transaction

/**
 * Geographic Location helpers (ticket #29), ported from the web app's
 * location.ts: coordinates are stored on the Transaction; the maps link is
 * built here, client-side, and is never stored as text (CONTEXT.md).
 */

/** A coordinate pair attached to a Transaction (spec decision #11). */
data class LatLng(val lat: Double, val lng: Double)

/**
 * An optional Place reference on a Geographic Location (ADR-0005 parity):
 * the name from a name-search pick or a tap on the Google map, plus the
 * provider's reference id when it has one (e.g. a Google place_id). Only
 * picks made on the Google map produce a Place; the free picker's taps,
 * GPS, and imports attach coordinates alone.
 */
data class Place(val name: String, val placeId: String? = null)

/** Default map center when nothing is picked yet (Europe/Rome), shared by
 * the free and Google map pickers — the web's DEFAULT_MAP_CENTER. */
val DEFAULT_MAP_CENTER = LatLng(41.9028, 12.4964)

/**
 * The Google Maps link for a coordinate pair — built at render time, never
 * persisted (CONTEXT.md: "the link itself is never stored as text"). A Place
 * (ADR-0005) with a place_id becomes Google's documented place-with-pin
 * search URL: `search/?api=1&query={lat},{lng}&query_place_id={id}`. The
 * mobile Maps apps run it as a search, so an unresolvable place_id still
 * lands a pin on the exact coordinates (never a literal "place_id:…" text
 * search, and never a place URL the app silently ignores). Without a
 * place_id the place name is searched; without a Place the link is a plain
 * coordinate search.
 */
fun mapLink(position: LatLng, place: Place? = null): String {
    val placeId = place?.placeId
    if (place != null && !placeId.isNullOrEmpty()) {
        return "https://www.google.com/maps/search/?api=1" +
            "&query=${position.lat},${position.lng}" +
            "&query_place_id=${encodeUriComponent(placeId)}"
    }
    if (place != null && place.name.isNotEmpty()) {
        return "https://www.google.com/maps/search/?api=1&query=${encodeUriComponent(place.name)}"
    }
    return "https://www.google.com/maps/search/?api=1&query=${position.lat},${position.lng}"
}

/** Short display form of a coordinate pair ("41.9028, 12.4964"). */
fun formatLocation(position: LatLng): String = "${position.lat}, ${position.lng}"

/**
 * The wire's coordinate fields. Coordinates travel as decimal strings (the
 * backend stores them in a Numeric(9, 6) column and canonicalizes responses
 * to at most six decimals), so the request writes the double's shortest
 * round-trip form — like the web's String(position.lat).
 */
data class WireLatLng(val latitude: String?, val longitude: String?)

/** Parse the API's coordinate strings into a position, or null when absent —
 * a missing half, an unparsable half, or a NaN parse all mean "no
 * location", exactly like the web's Number.parseFloat-based helper. */
fun latLngFromWire(latitude: String?, longitude: String?): LatLng? {
    if (latitude == null || longitude == null) return null
    val lat = latitude.toDoubleOrNull()
    val lng = longitude.toDoubleOrNull()
    if (lat == null || lng == null || lat.isNaN() || lng.isNaN()) return null
    return LatLng(lat, lng)
}

/** Serialize a position to the API's coordinate strings (null when absent). */
fun latLngToWire(position: LatLng?): WireLatLng =
    if (position == null) {
        WireLatLng(null, null)
    } else {
        WireLatLng(position.lat.toString(), position.lng.toString())
    }

/**
 * The wire's Place fields (ADR-0005): name and provider reference id, both
 * written and cleared together with the coordinates.
 */
data class WirePlace(val place_name: String?, val place_id: String?)

/**
 * Parse the API's place fields into a Place, or null when absent. The name
 * is the anchor (ADR-0005): no name, no Place; a missing id stays a
 * name-only Place.
 */
fun placeFromWire(name: String?, placeId: String?): Place? {
    if (name.isNullOrEmpty()) return null
    return if (placeId.isNullOrEmpty()) Place(name) else Place(name, placeId)
}

/** Serialize a Place to the API's field names (null when absent). */
fun placeToWire(place: Place?): WirePlace =
    if (place == null) WirePlace(null, null) else WirePlace(place.name, place.placeId)

/** The percent-encoding the URLs above use — a port of JavaScript's
 * encodeURIComponent (location.test.ts pins the exact bytes): the
 * unreserved characters pass through, everything else becomes upper-case
 * %XX UTF-8 escapes (a space is %20, never the form-encoding's '+'). */
private const val URI_UNRESERVED = "-_.!~*'()"

internal fun encodeUriComponent(value: String): String = buildString {
    for (char in value) {
        // ASCII letters and digits only — the web's encodeURIComponent
        // leaves non-ASCII letters (é, ü, …) to the UTF-8 escape.
        if (char in 'a'..'z' || char in 'A'..'Z' || char in '0'..'9' ||
            char in URI_UNRESERVED
        ) {
            append(char)
        } else {
            for (byte in char.toString().toByteArray(Charsets.UTF_8)) {
                val unsigned = byte.toInt() and 0xFF
                append('%')
                append(HEX_DIGITS[unsigned ushr 4])
                append(HEX_DIGITS[unsigned and 0x0F])
            }
        }
    }
}

private const val HEX_DIGITS = "0123456789ABCDEF"
