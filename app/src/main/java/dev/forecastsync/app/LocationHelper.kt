package dev.forecastsync.app

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationManager
import android.os.Build
import androidx.core.content.ContextCompat
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.coroutines.resume

/** Coarse position is plenty for a weather forecast. */
@SuppressLint("MissingPermission")
object LocationHelper {

    fun hasPermission(ctx: Context) =
        ContextCompat.checkSelfPermission(ctx, Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED ||
            ContextCompat.checkSelfPermission(ctx, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED

    /** Returns lat/lon and a note about where it came from; falls back to the last saved position. */
    suspend fun current(ctx: Context, prefs: Prefs): Pair<Pair<Double, Double>, String>? {
        if (hasPermission(ctx)) {
            val lm = ctx.getSystemService(Context.LOCATION_SERVICE) as LocationManager
            val newest: Location? = lm.getProviders(true)
                .mapNotNull { runCatching { lm.getLastKnownLocation(it) }.getOrNull() }
                .maxByOrNull { it.time }
            var loc = newest
            val stale = loc == null || System.currentTimeMillis() - loc.time > 2 * 3600_000L
            if (stale && Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                val provider = listOf(LocationManager.NETWORK_PROVIDER, LocationManager.GPS_PROVIDER, LocationManager.PASSIVE_PROVIDER)
                    .firstOrNull { lm.isProviderEnabled(it) }
                if (provider != null) {
                    val fresh = withTimeoutOrNull(15_000) {
                        suspendCancellableCoroutine<Location?> { cont ->
                            val cancel = android.os.CancellationSignal()
                            cont.invokeOnCancellation { cancel.cancel() }
                            lm.getCurrentLocation(provider, cancel, ContextCompat.getMainExecutor(ctx)) { cont.resume(it) }
                        }
                    }
                    if (fresh != null) loc = fresh
                }
            }
            if (loc != null) {
                prefs.lastLat = loc.latitude
                prefs.lastLon = loc.longitude
                return Pair(Pair(loc.latitude, loc.longitude), "current location")
            }
        }
        if (!prefs.lastLat.isNaN() && !prefs.lastLon.isNaN()) {
            return Pair(Pair(prefs.lastLat, prefs.lastLon), "last saved location")
        }
        return null
    }
}
