package dev.forecastsync.app

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.text.Normalizer
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/** Hourly values of today as the API gave them; null = the API had nothing for that hour (past hours of OWM). */
class RawHours(
    val t: Array<Double?> = arrayOfNulls(24),
    val r: Array<Double?> = arrayOfNulls(24),
    val u: Array<Double?> = arrayOfNulls(24),
)

/** Everything the watch needs; [hourlyTemp] etc. are filled in by [HourCache.apply] from [raw]. */
data class Forecast(
    val loc: String,
    val tz: Int,               // UTC offset in seconds at the location, incl. DST
    val dayStart: Long,        // epoch seconds of local midnight today
    val tmax: Double, val tmin: Double, val code: Int, val pop: Int, val uv: Double,
    val tmax2: Double, val tmin2: Double, val code2: Int,
    val raw: RawHours,
    val source: String,
    val hourlyTemp: List<Double> = emptyList(),
    val hourlyRain: List<Double> = emptyList(),
    val hourlyUv: List<Double> = emptyList(),
) {
    /** Local hour (0..23) at the location right now. */
    fun currentHour(nowSec: Long = System.currentTimeMillis() / 1000): Int =
        (((nowSec + tz) % 86400 + 86400) % 86400 / 3600).toInt()
}

class HttpStatusException(val code: Int, msg: String) : IOException(msg)

object WeatherClient {

    private const val OWM = "https://api.openweathermap.org"
    /** Set from resources in [App]; Nominatim requires a real identifying User-Agent. */
    @Volatile var userAgent = "ForecastSync"

    private fun get(url: String): String {
        val c = URL(url).openConnection() as HttpURLConnection
        c.connectTimeout = 15_000
        c.readTimeout = 20_000
        c.setRequestProperty("User-Agent", userAgent)
        try {
            val code = c.responseCode
            val body = (if (code in 200..299) c.inputStream else c.errorStream)?.bufferedReader()?.readText().orEmpty()
            if (code !in 200..299) throw HttpStatusException(code, "HTTP $code: ${body.take(120)}")
            return body
        } finally {
            c.disconnect()
        }
    }

    // ---------------------------------------------------------------- place name

    /** Watch font is only known to have ASCII: transliterate, keep 19 characters. */
    fun asciiName(s: String): String {
        val t = s.replace("ä", "ae").replace("ö", "oe").replace("ü", "ue")
            .replace("Ä", "Ae").replace("Ö", "Oe").replace("Ü", "Ue").replace("ß", "ss")
        val n = Normalizer.normalize(t, Normalizer.Form.NFD).replace(Regex("\\p{M}+"), "")
        return n.filter { it.code in 32..126 && it != '"' && it != '\\' }.trim().take(19)
    }

    /** Cached per ~1 km cell; OpenWeatherMap's own geocoder when a key is at hand, else OpenStreetMap Nominatim. */
    suspend fun placeName(prefs: Prefs, lat: Double, lon: Double, owmKey: String?): String = withContext(Dispatchers.IO) {
        val key = String.format(java.util.Locale.US, "%.2f,%.2f", lat, lon)
        if (prefs.locKey == key && prefs.locName.isNotEmpty()) return@withContext prefs.locName
        val name = try {
            if (!owmKey.isNullOrBlank()) {
                val arr = JSONArray(get("$OWM/geo/1.0/reverse?lat=$lat&lon=$lon&limit=1&appid=$owmKey"))
                if (arr.length() > 0) asciiName(arr.getJSONObject(0).optString("name", "")) else ""
            } else {
                val j = JSONObject(get("https://nominatim.openstreetmap.org/reverse?format=jsonv2&zoom=10&accept-language=en&lat=$lat&lon=$lon"))
                val a = j.optJSONObject("address")
                val n = listOf("city", "town", "village", "municipality", "county").firstNotNullOfOrNull { k ->
                    a?.optString(k, "")?.takeIf { it.isNotEmpty() }
                } ?: j.optString("name", "")
                asciiName(n)
            }
        } catch (_: Exception) { "" }
        if (name.isNotEmpty()) { prefs.locKey = key; prefs.locName = name }
        name.ifEmpty { prefs.locName }
    }

    // ---------------------------------------------------------------- Open-Meteo

    /** Open-Meteo already speaks WMO codes and includes past hours (past_days=1), so today is complete. */
    suspend fun openMeteo(lat: Double, lon: Double, name: String): Forecast = withContext(Dispatchers.IO) {
        val url = "https://api.open-meteo.com/v1/forecast?latitude=$lat&longitude=$lon&timezone=auto&past_days=1&forecast_days=2" +
            "&daily=temperature_2m_max,temperature_2m_min,weather_code,precipitation_probability_max,uv_index_max" +
            "&hourly=temperature_2m,precipitation,uv_index"
        val j = JSONObject(get(url))
        val tz = j.optInt("utc_offset_seconds", 0)
        val daily = j.getJSONObject("daily")
        val hourly = j.getJSONObject("hourly")

        // With past_days=1 index 0 is yesterday, 1 today, 2 tomorrow.
        fun d(name: String, i: Int) = daily.getJSONArray(name).optDouble(i, 0.0)
        fun di(name: String, i: Int) = daily.getJSONArray(name).optInt(i, 0)
        val raw = RawHours()
        val ht = hourly.getJSONArray("temperature_2m")
        val hr = hourly.getJSONArray("precipitation")
        val hu = hourly.getJSONArray("uv_index")
        for (h in 0 until 24) {
            val i = 24 + h
            if (!ht.isNull(i)) raw.t[h] = ht.getDouble(i)
            raw.r[h] = if (hr.isNull(i)) 0.0 else hr.getDouble(i)
            raw.u[h] = if (hu.isNull(i)) 0.0 else hu.getDouble(i)
        }
        Forecast(
            loc = name, tz = tz, dayStart = dayStart(tz),
            tmax = d("temperature_2m_max", 1), tmin = d("temperature_2m_min", 1), code = di("weather_code", 1),
            pop = di("precipitation_probability_max", 1), uv = d("uv_index_max", 1),
            tmax2 = d("temperature_2m_max", 2), tmin2 = d("temperature_2m_min", 2), code2 = di("weather_code", 2),
            raw = raw, source = "Open-Meteo",
        )
    }

    private fun dayStart(tz: Int): Long =
        floor((System.currentTimeMillis() / 1000.0 + tz) / 86400.0).toLong() * 86400 - tz

    // ---------------------------------------------------------------- OpenWeatherMap

    /** OWM condition id -> WMO code understood by the watch app. */
    fun wmo(id: Int): Int = when (id) {
        800 -> 0
        801 -> 1
        802 -> 2
        803, 804 -> 3
        in 200..232 -> 95
        in 300..321 -> 51
        500, 501 -> 61
        502, 503, 504 -> 65
        511 -> 66
        520 -> 80
        521 -> 81
        522, 531 -> 82
        in 600..602 -> 71
        in 611..616 -> 66
        in 620..622 -> 85
        in 700..781 -> 45
        else -> 3
    }

    /** One Call 3.0 when the key has it, else the free 2.5 forecast. */
    suspend fun openWeatherMap(key: String, lat: Double, lon: Double, name: String): Forecast = withContext(Dispatchers.IO) {
        try {
            oneCall(key, lat, lon, name)
        } catch (e: HttpStatusException) {
            // 401 = key has no One Call 3.0 subscription -> free forecast instead.
            if (e.code == 401 || e.code == 403 || e.code == 429) forecast25(key, lat, lon, name) else throw e
        }
    }

    private fun oneCall(key: String, lat: Double, lon: Double, name: String): Forecast {
        val j = JSONObject(get("$OWM/data/3.0/onecall?lat=$lat&lon=$lon&exclude=minutely,alerts&units=metric&appid=$key"))
        val tz = j.optInt("timezone_offset", 0)
        val ds = dayStart(tz)

        val raw = RawHours()
        val hourly = j.getJSONArray("hourly")
        for (i in 0 until hourly.length()) {
            val h = hourly.getJSONObject(i)
            val idx = ((h.getLong("dt") - ds) / 3600).toInt()
            if (idx in 0..23) {
                raw.t[idx] = h.getDouble("temp")
                raw.r[idx] = h.optJSONObject("rain")?.optDouble("1h", 0.0) ?: 0.0
                raw.u[idx] = h.optDouble("uvi", 0.0)
            }
        }
        val daily = j.getJSONArray("daily")
        val d0 = daily.getJSONObject(0)
        val d1 = daily.getJSONObject(1)
        return Forecast(
            loc = name, tz = tz, dayStart = ds,
            tmax = d0.getJSONObject("temp").getDouble("max"), tmin = d0.getJSONObject("temp").getDouble("min"),
            code = wmo(d0.getJSONArray("weather").getJSONObject(0).getInt("id")),
            pop = (d0.optDouble("pop", 0.0) * 100).roundToInt(), uv = d0.optDouble("uvi", 0.0),
            tmax2 = d1.getJSONObject("temp").getDouble("max"), tmin2 = d1.getJSONObject("temp").getDouble("min"),
            code2 = wmo(d1.getJSONArray("weather").getJSONObject(0).getInt("id")),
            raw = raw, source = "OpenWeatherMap One Call 3.0",
        )
    }

    /** Free 5-day/3-hour forecast: interpolated to hours, no UV data. */
    private fun forecast25(key: String, lat: Double, lon: Double, name: String): Forecast {
        val j = JSONObject(get("$OWM/data/2.5/forecast?lat=$lat&lon=$lon&units=metric&appid=$key"))
        val tz = j.getJSONObject("city").optInt("timezone", 0)
        val ds = dayStart(tz)
        val list = j.getJSONArray("list")

        class P(val t: Long, val temp: Double, val rain3h: Double, val pop: Double, val id: Int)
        val pts = (0 until list.length()).map {
            val e = list.getJSONObject(it)
            P(e.getLong("dt"), e.getJSONObject("main").getDouble("temp"),
                e.optJSONObject("rain")?.optDouble("3h", 0.0) ?: 0.0,
                e.optDouble("pop", 0.0), e.getJSONArray("weather").getJSONObject(0).getInt("id"))
        }

        fun tempAt(t: Long): Double? {
            val a = pts.lastOrNull { it.t <= t } ?: return null
            val b = pts.firstOrNull { it.t >= t } ?: return null
            if (a.t == b.t) return a.temp
            return a.temp + (b.temp - a.temp) * (t - a.t).toDouble() / (b.t - a.t)
        }

        val raw = RawHours()
        for (h in 0 until 24) {
            val t = ds + h * 3600L
            raw.t[h] = tempAt(t)
            val slot = pts.firstOrNull { t >= it.t - 3 * 3600 && t < it.t }   // 3h slot ending at it.t
            raw.r[h] = slot?.let { it.rain3h / 3.0 }
            raw.u[h] = 0.0
        }

        fun dayPts(day: Int) = pts.filter { it.t >= ds + day * 86400L && it.t < ds + (day + 1) * 86400L }
        val today = dayPts(0).ifEmpty { pts.take(1) }
        val tomorrow = dayPts(1).ifEmpty { today }
        fun rep(l: List<P>) = l.getOrNull(l.size / 2)?.id ?: 800
        return Forecast(
            loc = name.ifEmpty { asciiName(j.getJSONObject("city").optString("name", "")) },
            tz = tz, dayStart = ds,
            tmax = today.maxOf { it.temp }, tmin = today.minOf { it.temp }, code = wmo(rep(today)),
            pop = ((today.maxOfOrNull { it.pop } ?: 0.0) * 100).roundToInt(), uv = 0.0,
            tmax2 = tomorrow.maxOf { it.temp }, tmin2 = tomorrow.minOf { it.temp }, code2 = wmo(rep(tomorrow)),
            raw = raw, source = "OpenWeatherMap Forecast 2.5 (Free, ohne UV)",
        )
    }

    // ---------------------------------------------------------------- JSON for the watch

    fun toJson(f: Forecast, nowSec: Long): String {
        fun r1(v: Double) = (v * 10.0).roundToInt() / 10.0
        val o = JSONObject()
        o.put("v", 1)
        o.put("ts", nowSec)
        o.put("tz", f.tz)
        o.put("loc", f.loc)
        o.put("tmax", r1(f.tmax)); o.put("tmin", r1(f.tmin))
        o.put("code", f.code); o.put("pop", min(100, max(0, f.pop))); o.put("uv", r1(f.uv))
        o.put("tmax2", r1(f.tmax2)); o.put("tmin2", r1(f.tmin2)); o.put("code2", f.code2)
        o.put("t", JSONArray(f.hourlyTemp.map { r1(it) }))
        o.put("r", JSONArray(f.hourlyRain.map { r1(it) }))
        o.put("u", JSONArray(f.hourlyUv.map { r1(it) }))
        return o.toString()
    }
}
