package com.budjetame.android.ui.maps

/**
 * Map-provider seam (ADR-0004 parity, ticket #29): one Gradle property
 * switches the Transaction form's picker between Google Maps and the free
 * osmdroid picker, mirroring the web app's `VITE_MAP_PROVIDER` switch.
 *
 * Configuration is part of the contract: when the provider is `google` the
 * API key is required, so a misconfigured build fails loudly at render time
 * instead of showing a broken map. The osmdroid fallback never needs a key.
 * Do not remove the fallback, the resolver, or the switch: it is what keeps
 * the app runnable without a Google billing account, and a key never lives
 * in the codebase — it comes from the `GOOGLE_MAPS_API_KEY` Gradle property
 * at build time (BuildConfig), never committed.
 */

/** The providers the seam can resolve to; anything that is not exactly
 * `google` is the free fallback, so a typo can never take down the picker. */
sealed interface MapConfig {
    /** The free osmdroid (OpenStreetMap) picker: tap-only, no key, never a
     * Place (ADR-0005) — the Leaflet adapter's Android counterpart. */
    data object Leaflet : MapConfig

    /** The Google Maps picker: place search and POI taps carry a Place. */
    data class Google(val apiKey: String) : MapConfig
}

/** The build-time provider strings, shared by the resolver and the facade. */
const val PROVIDER_GOOGLE = "google"
const val PROVIDER_LEAFLET = "leaflet"

/** raw provider string → provider. Anything that is not exactly `google` is
 * the free fallback, so a typo can never take down the picker. */
fun resolveMapProvider(rawProvider: String?): String =
    if (rawProvider == PROVIDER_GOOGLE) PROVIDER_GOOGLE else PROVIDER_LEAFLET

/** Resolve the full map configuration; throws when `google` is configured
 * without an API key (fail fast, with the fix in the message). */
fun resolveMapConfig(rawProvider: String?, rawApiKey: String?): MapConfig {
    val provider = resolveMapProvider(rawProvider)
    if (provider == PROVIDER_LEAFLET) return MapConfig.Leaflet
    val apiKey = rawApiKey.orEmpty()
    if (apiKey.isEmpty()) {
        throw IllegalArgumentException(
            "GOOGLE_MAPS_API_KEY is required when MAP_PROVIDER=google. " +
                "Set it as a Gradle property (see README) or switch back to " +
                "MAP_PROVIDER=leaflet.",
        )
    }
    return MapConfig.Google(apiKey)
}
