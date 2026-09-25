package dev.forecastsync.app

import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.content.ContextCompat
import kotlinx.coroutines.delay
import java.text.DateFormat
import java.util.Date

/** One complete refresh: position -> OpenWeatherMap -> JSON -> watch. Used by the button and the 30-minute worker. */
object SyncEngine {

    private const val ATTEMPTS = 3

    private fun btPermission(ctx: Context) =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.S ||
            ContextCompat.checkSelfPermission(ctx, android.Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED

    /** @return true on success. The human readable result is stored in [Prefs.status]. */
    suspend fun run(ctx: Context): Boolean {
        val prefs = Prefs(ctx)
        val result = try {
            doRun(ctx, prefs)
        } catch (e: Exception) {
            "Error: ${e.message ?: e.javaClass.simpleName}" to false
        }
        prefs.saveStatus(result.first)
        return result.second
    }

    private suspend fun doRun(ctx: Context, prefs: Prefs): Pair<String, Boolean> {
        val useOwm = prefs.provider == "owm"
        if (useOwm && prefs.apiKey.isBlank()) return "No OpenWeatherMap API key entered" to false
        if (prefs.deviceAddress.isBlank()) return "No watch selected" to false
        if (!btPermission(ctx)) return "Bluetooth permission missing" to false

        val pos = LocationHelper.current(ctx, prefs)
            ?: return "No location (grant the permission, or open the app once with location on)" to false
        val (lat, lon) = pos.first

        val name = WeatherClient.placeName(prefs, lat, lon, if (useOwm) prefs.apiKey else null)
        val fetched = if (useOwm) WeatherClient.openWeatherMap(prefs.apiKey, lat, lon, name)
                      else WeatherClient.openMeteo(lat, lon, name)
        val forecast = HourCache.apply(prefs, fetched)
        val json = WeatherClient.toJson(forecast, System.currentTimeMillis() / 1000)
        val bytes = json.toByteArray(Charsets.UTF_8)
        if (bytes.size > 2000) return "File too large (${bytes.size} B > 2000 B)" to false

        val path = "/Apps/${prefs.appFolder}/weather.json"
        var lastError = ""
        for (attempt in 1..ATTEMPTS) {
            try {
                val info = FtsClient(ctx).writeFile(prefs.deviceAddress, path, bytes)
                val t = DateFormat.getTimeInstance(DateFormat.SHORT).format(Date())
                return "$t OK: ${forecast.loc.ifEmpty { "?" }}, ${info}; source ${forecast.source}; ${pos.second}" to true
            } catch (e: Exception) {
                lastError = e.message ?: e.javaClass.simpleName
                if (attempt < ATTEMPTS) delay(4_000L * attempt)
            }
        }
        return "Transfer failed after $ATTEMPTS attempts: $lastError. " +
            "Is the UNA app or another device holding the watch right now?" to false
    }
}
