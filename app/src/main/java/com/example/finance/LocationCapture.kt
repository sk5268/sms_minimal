package com.example.finance

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationManager
import android.os.CancellationSignal
import android.os.SystemClock
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.Executors
import kotlin.coroutines.resume

/**
 * One-shot location for a payment. No Play Services dependency: the platform fused
 * provider (API 31+) is used when present, then GPS, then network.
 *
 * From the SMS receiver this only works with "Allow all the time" location; from the
 * tag sheet (foreground) "While using the app" is enough.
 */
object LocationCapture {

    private const val FRESH_MS = 2 * 60 * 1000L
    private val executor = Executors.newSingleThreadExecutor()

    fun hasForegroundPermission(context: Context): Boolean =
        context.checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED

    fun hasBackgroundPermission(context: Context): Boolean =
        hasForegroundPermission(context) &&
            context.checkSelfPermission(Manifest.permission.ACCESS_BACKGROUND_LOCATION) == PackageManager.PERMISSION_GRANTED

    suspend fun currentFix(context: Context, timeoutMs: Long = 4_000L): TagMemory.Fix? {
        if (!hasForegroundPermission(context)) return null
        val lm = context.getSystemService(Context.LOCATION_SERVICE) as? LocationManager ?: return null
        val providers = listOf(LocationManager.FUSED_PROVIDER, LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER)
            .filter { runCatching { lm.isProviderEnabled(it) }.getOrDefault(false) }
        if (providers.isEmpty()) return null

        try {
            providers.mapNotNull { lm.getLastKnownLocation(it) }
                .filter { ageMs(it) <= FRESH_MS }
                .minByOrNull { it.accuracy }
                ?.let { return it.toFix() }
        } catch (_: SecurityException) {
            return null
        }

        val provider = providers.first()
        return withTimeoutOrNull(timeoutMs) {
            suspendCancellableCoroutine { cont ->
                val signal = CancellationSignal()
                cont.invokeOnCancellation { signal.cancel() }
                try {
                    lm.getCurrentLocation(provider, signal, executor) { location ->
                        if (cont.isActive) cont.resume(location?.toFix())
                    }
                } catch (_: SecurityException) {
                    if (cont.isActive) cont.resume(null)
                }
            }
        }
    }

    private fun ageMs(location: Location): Long =
        (SystemClock.elapsedRealtimeNanos() - location.elapsedRealtimeNanos) / 1_000_000

    private fun Location.toFix() = TagMemory.Fix(latitude, longitude, if (hasAccuracy()) accuracy.toDouble() else 999.0)
}
