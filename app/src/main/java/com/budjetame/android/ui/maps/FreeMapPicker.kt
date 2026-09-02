package com.budjetame.android.ui.maps

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import com.budjetame.android.data.transaction.DEFAULT_MAP_CENTER
import com.budjetame.android.data.transaction.LatLng
import com.budjetame.android.data.transaction.Place
import org.osmdroid.config.Configuration
import org.osmdroid.events.MapEventsReceiver
import org.osmdroid.tileprovider.tilesource.TileSourceFactory
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.CustomZoomButtonsController
import org.osmdroid.views.MapView
import org.osmdroid.views.overlay.MapEventsOverlay
import org.osmdroid.views.overlay.Marker

/**
 * The free map-picker adapter (ADR-0004 parity): an osmdroid (OpenStreetMap)
 * tap-to-pick map with no API key — the Leaflet adapter's Android
 * counterpart. Implements the same `{ position, onPick }` contract as the
 * Google adapter; tap richness lives there, not in the interface. A tap
 * reports coordinates alone — this picker never produces a Place (ADR-0005),
 * so picking here clears any stored Place.
 */
@Composable
fun FreeMapPicker(
    position: LatLng?,
    onPick: (LatLng, Place?) -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    // The freshest callback without recreating the map on recomposition:
    // a tap reports through this, never through a stale captured lambda.
    val currentOnPick = rememberUpdatedState(onPick)
    val mapView = remember(context) {
        // osmdroid identifies its HTTP tile requests by this user agent;
        // without it OpenStreetMap's tile servers reject the app.
        Configuration.getInstance().userAgentValue = context.packageName
        MapView(context).apply {
            setTileSource(TileSourceFactory.MAPNIK)
            setMultiTouchControls(true)
            zoomController.setVisibility(CustomZoomButtonsController.Visibility.NEVER)
            val center = position ?: DEFAULT_MAP_CENTER
            controller.setZoom(position?.let { 15.0 } ?: 6.0)
            controller.setCenter(GeoPoint(center.lat, center.lng))
            val pin = Marker(this).apply {
                this.position = GeoPoint(center.lat, center.lng)
                setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_BOTTOM)
            }
            overlays.add(pin)
            overlays.add(
                MapEventsOverlay(
                    object : MapEventsReceiver {
                        override fun singleTapConfirmedHelper(point: GeoPoint?): Boolean {
                            if (point != null) {
                                currentOnPick.value(LatLng(point.latitude, point.longitude), null)
                            }
                            return true
                        }

                        override fun longPressHelper(point: GeoPoint?): Boolean = false
                    },
                ),
            )
        }
    }
    AndroidView(factory = { mapView }, modifier = modifier)
    DisposableEffect(mapView) {
        mapView.onResume()
        onDispose { mapView.onPause() }
    }
}
