package com.budjetame.android.data.location

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Build
import android.os.Bundle
import android.os.CancellationSignal
import android.os.Looper
import androidx.core.content.ContextCompat
import com.budjetame.android.data.transaction.LatLng
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.Executor
import kotlin.coroutines.resume

/**
 * The device's GPS position (ticket #29), the client-side counterpart of the
 * web form's `navigator.geolocation`: the Transaction form's "Use my
 * location" pick and its first-save prefill. The interface is UI-free — the
 * permission *prompt* is a screen concern (the ViewModel asks through a
 * state flag and the screen bridges it to the system dialog, reporting the
 * answer back).
 */
interface DeviceLocation {
    /** True when the app already holds a location permission (fine or
     * coarse) — no prompt needed. */
    fun permissionGranted(): Boolean

    /**
     * A current position fix, or null when unavailable (permission denied,
     * location off, timeout, no provider). Must only be called with
     * `permissionGranted()` true; it never prompts.
     */
    suspend fun currentPosition(): LatLng?
}

/**
 * The LocationManager-backed DeviceLocation: fine permission reads the GPS
 * provider first, then the network provider; coarse-only falls back to the
 * network provider directly. One fix attempt per provider, ~10 seconds each
 * like the web's getCurrentPosition timeout. The single-update legacy path
 * (API < 30) is deprecated but still the pre-Android-11 way to ask for a
 * fresh fix.
 */
class AndroidDeviceLocation(context: Context) : DeviceLocation {

    private val appContext = context.applicationContext
    private val manager =
        appContext.getSystemService(Context.LOCATION_SERVICE) as? LocationManager

    override fun permissionGranted(): Boolean =
        hasPermission(Manifest.permission.ACCESS_FINE_LOCATION) ||
            hasPermission(Manifest.permission.ACCESS_COARSE_LOCATION)

    private fun hasPermission(permission: String): Boolean =
        ContextCompat.checkSelfPermission(appContext, permission) ==
            PackageManager.PERMISSION_GRANTED

    override suspend fun currentPosition(): LatLng? {
        val locationManager = manager ?: return null
        if (!permissionGranted()) return null
        val fine = hasPermission(Manifest.permission.ACCESS_FINE_LOCATION)
        val providers = if (fine) {
            listOf(LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER)
        } else {
            listOf(LocationManager.NETWORK_PROVIDER)
        }
        for (provider in providers) {
            if (locationManager.getProvider(provider) == null) continue
            val fix = oneShotFix(locationManager, provider) ?: continue
            return LatLng(fix.latitude, fix.longitude)
        }
        return null
    }

    /** One fresh fix from `provider`, ~10s to answer (the web's timeout). */
    private suspend fun oneShotFix(manager: LocationManager, provider: String): Location? =
        withTimeoutOrNull(FIX_TIMEOUT_MILLIS) {
            if (Build.VERSION.SDK_INT >= 30) {
                modernFix(manager, provider)
            } else {
                legacyFix(manager, provider)
            }
        }

    /** API 30+: the one-shot getCurrentLocation with a cancellation signal. */
    private suspend fun modernFix(manager: LocationManager, provider: String): Location? =
        suspendCancellableCoroutine { continuation ->
            val signal = CancellationSignal()
            continuation.invokeOnCancellation { signal.cancel() }
            try {
                manager.getCurrentLocation(
                    provider,
                    signal,
                    DIRECT_EXECUTOR,
                ) { location ->
                    if (continuation.isActive) continuation.resume(location)
                }
            } catch (_: SecurityException) {
                // A permission the manifest promised but the user revoked
                // mid-flight: no fix from this provider.
                if (continuation.isActive) continuation.resume(null)
            } catch (_: RuntimeException) {
                if (continuation.isActive) continuation.resume(null)
            }
        }

    /** API < 30: requestSingleUpdate on the main looper, removed on answer.
     * The listener is a full object, not a SAM lambda: on these old API
     * levels the platform still calls the other listener methods, whose
     * default implementations only exist from API 30 up. */
    @Suppress("DEPRECATION")
    private suspend fun legacyFix(manager: LocationManager, provider: String): Location? =
        suspendCancellableCoroutine { continuation ->
            val listener = object : LocationListener {
                override fun onLocationChanged(location: Location) {
                    manager.removeUpdates(this)
                    if (continuation.isActive) continuation.resume(location)
                }

                @Deprecated("Deprecated in Java")
                override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) = Unit

                override fun onProviderEnabled(provider: String) = Unit

                override fun onProviderDisabled(provider: String) = Unit
            }
            continuation.invokeOnCancellation { manager.removeUpdates(listener) }
            try {
                manager.requestSingleUpdate(provider, listener, Looper.getMainLooper())
            } catch (_: SecurityException) {
                manager.removeUpdates(listener)
                if (continuation.isActive) continuation.resume(null)
            } catch (_: RuntimeException) {
                manager.removeUpdates(listener)
                if (continuation.isActive) continuation.resume(null)
            }
        }

    private companion object {
        /** The web's getCurrentPosition timeout (location.ts), per provider. */
        const val FIX_TIMEOUT_MILLIS = 10_000L

        /** Callbacks straight back on the caller's thread (a coroutine). */
        val DIRECT_EXECUTOR = Executor { it.run() }
    }
}
