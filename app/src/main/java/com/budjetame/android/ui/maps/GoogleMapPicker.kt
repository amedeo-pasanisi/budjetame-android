package com.budjetame.android.ui.maps

import android.app.Activity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import com.budjetame.android.data.transaction.DEFAULT_MAP_CENTER
import com.budjetame.android.data.transaction.LatLng
import com.budjetame.android.data.transaction.Place
import com.google.android.gms.maps.CameraUpdateFactory
import com.google.android.gms.maps.GoogleMap
import com.google.android.gms.maps.MapView
import com.google.android.gms.maps.model.LatLng as GoogleLatLng
import com.google.android.gms.maps.model.MarkerOptions
import com.google.android.libraries.places.api.Places
import com.google.android.libraries.places.api.model.Place as GooglePlace
import com.google.android.libraries.places.api.net.PlacesClient
import com.google.android.libraries.places.widget.Autocomplete
import com.google.android.libraries.places.widget.model.AutocompleteActivityMode
import java.util.Locale
import kotlinx.coroutines.delay

/**
 * The Google Maps adapter (ADR-0004 parity): a real Google map with place
 * search, behind the same `{ position, onPick }` contract as the free
 * picker. Search richness lives here, not in the interface: the search
 * button opens the Places SDK's autocomplete screen, and a search pick
 * reports the Place's name and place_id alongside the coordinates
 * (ADR-0005) — only when the result carries both, like the web adapter. A
 * tap on a map POI reports its name and place_id (the POI click carries
 * them for free); a bare-map tap reports coordinates alone. Only this
 * adapter produces a Place — the free picker's taps, GPS, and imports stay
 * coordinates-only.
 */
@Composable
fun GoogleMapPicker(
    apiKey: String,
    position: LatLng?,
    onPick: (LatLng, Place?) -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    var loadError by remember { mutableStateOf<String?>(null) }

    // Places.initialize is process-global and throws on a bad key; the
    // facade has already validated the key's presence, so a failure here is
    // an environment problem and should fail loudly, like the web adapter.
    // The app is single-locale (like its currency and timezone): place names
    // come back in Italian regardless of the device's language, so the
    // initialization asks for it explicitly — the web's language=it.
    val placesInitialized = remember(apiKey) {
        runCatching {
            Places.initialize(context.applicationContext, apiKey, Locale.ITALY)
        }.isSuccess
    }
    val placesClient: PlacesClient? = remember(placesInitialized) {
        if (placesInitialized) runCatching { Places.createClient(context) }.getOrNull() else null
    }

    val autocompleteLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult(),
    ) { result ->
        if (result.resultCode != Activity.RESULT_OK) return@rememberLauncherForActivityResult
        val intent = result.data ?: return@rememberLauncherForActivityResult
        val picked = runCatching { Autocomplete.getPlaceFromIntent(intent) }.getOrNull()
            ?: return@rememberLauncherForActivityResult
        val latLng = picked.location ?: return@rememberLauncherForActivityResult
        val name = picked.displayName
        val placeId = picked.id
        // A search pick produces a Place only when the result carries both
        // name and place_id (the web adapter's rule); anything less stays a
        // coordinates-only pick.
        val place = if (!name.isNullOrEmpty() && !placeId.isNullOrEmpty()) {
            Place(name, placeId)
        } else {
            null
        }
        onPick(LatLng(latLng.latitude, latLng.longitude), place)
    }

    Column(modifier = modifier) {
        OutlinedButton(
            onClick = {
                if (placesClient != null) {
                    // The autocomplete activity handles billing/key errors
                    // itself (it returns RESULT_CANCELED), so a failure
                    // here just never opens a pick.
                    runCatching {
                        autocompleteLauncher.launch(
                            Autocomplete.IntentBuilder(
                                AutocompleteActivityMode.FULLSCREEN,
                                listOf(
                                    GooglePlace.Field.DISPLAY_NAME,
                                    GooglePlace.Field.ID,
                                    GooglePlace.Field.LOCATION,
                                ),
                            ).build(context),
                        )
                    }
                }
            },
            enabled = placesClient != null && loadError == null,
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 8.dp),
        ) {
            Text("Search for a place…")
        }

        val mapView = remember(context) {
            MapView(context).apply { onCreate(null) }
        }
        var googleMap by remember { mutableStateOf<GoogleMap?>(null) }
        DisposableEffect(mapView) {
            mapView.onResume()
            onDispose {
                mapView.onPause()
                mapView.onDestroy()
            }
        }
        LaunchedEffect(mapView) {
            try {
                mapView.getMapAsync { map ->
                    val center = position ?: DEFAULT_MAP_CENTER
                    map.moveCamera(
                        CameraUpdateFactory.newLatLngZoom(
                            GoogleLatLng(center.lat, center.lng),
                            if (position == null) 6f else 15f,
                        ),
                    )
                    map.addMarker(MarkerOptions().position(GoogleLatLng(center.lat, center.lng)))
                    map.setOnMapClickListener { tapped ->
                        // Bare-map tap: coordinates-only (ADR-0005).
                        onPick(LatLng(tapped.latitude, tapped.longitude), null)
                    }
                    map.setOnPoiClickListener { poi ->
                        // A tap on a POI carries its name and place_id for
                        // free — the Android counterpart of the web's
                        // place_id-carrying map click.
                        onPick(
                            LatLng(poi.latLng.latitude, poi.latLng.longitude),
                            Place(poi.name, poi.placeId),
                        )
                    }
                    googleMap = map
                }
            } catch (error: Exception) {
                loadError = googleLoadMessage(error.message ?: "the map could not start.")
            }
        }
        // Fail loudly when the map never becomes ready (no Google Play
        // services, a revoked key, a firewall): the web adapter times out
        // its script load the same way.
        LaunchedEffect(mapView) {
            delay(MAP_READY_TIMEOUT_MILLIS)
            if (googleMap == null && loadError == null) {
                loadError = googleLoadMessage("the map did not become ready.")
            }
        }

        Box(modifier = Modifier.weight(1f)) {
            AndroidView(factory = { mapView }, modifier = Modifier.fillMaxWidth())
            when {
                loadError != null -> {
                    Text(
                        text = loadError!!,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                        modifier = Modifier
                            .align(Alignment.Center)
                            .padding(16.dp),
                    )
                }
                googleMap == null -> {
                    Text(
                        text = "Loading Google Map…",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier
                            .align(Alignment.Center)
                            .padding(16.dp),
                    )
                }
            }
        }
    }
}

/** The web adapter's load-failure copy, adapted to the Android build switch:
 * the free picker is one Gradle property away. */
private fun googleLoadMessage(reason: String): String =
    "Google Maps failed to load: $reason " +
        "You can switch the picker back to the free Leaflet map with " +
        "MAP_PROVIDER=leaflet."

private const val MAP_READY_TIMEOUT_MILLIS = 20_000L
