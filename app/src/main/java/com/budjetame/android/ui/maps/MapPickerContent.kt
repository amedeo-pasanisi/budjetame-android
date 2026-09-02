package com.budjetame.android.ui.maps

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.layout.padding
import com.budjetame.android.BuildConfig
import com.budjetame.android.data.transaction.LatLng
import com.budjetame.android.data.transaction.Place

/**
 * The map picker, behind a provider seam (ADR-0004 parity): the build-time
 * provider (BuildConfig, from the MAP_PROVIDER Gradle property) selects the
 * adapter; the osmdroid picker is the default fallback and never requires a
 * key. The contract stays `{ position, onPick }` regardless of provider;
 * `onPick` carries an optional Place (ADR-0005) — picks made on the Google
 * map supply one (search pick or a POI tap); the osmdroid picker never
 * does. A misconfigured Google build fails loudly here, at render time,
 * instead of showing a broken map.
 */
@Composable
fun MapPickerContent(
    position: LatLng?,
    onPick: (LatLng, Place?) -> Unit,
    modifier: Modifier = Modifier,
) {
    val config = remember {
        runCatching { resolveMapConfig(BuildConfig.MAP_PROVIDER, BuildConfig.GOOGLE_MAPS_API_KEY) }
    }
    val resolved = config.getOrNull()
    when (resolved) {
        null -> Text(
            text = config.exceptionOrNull()?.message ?: "Map provider misconfigured.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.error,
            modifier = modifier.padding(16.dp),
        )
        MapConfig.Leaflet -> FreeMapPicker(position = position, onPick = onPick, modifier = modifier)
        is MapConfig.Google -> GoogleMapPicker(
            apiKey = resolved.apiKey,
            position = position,
            onPick = onPick,
            modifier = modifier,
        )
    }
}
