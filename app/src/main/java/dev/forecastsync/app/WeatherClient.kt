package dev.forecastsync.app

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.text.Normalizer
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/** Days of forecast fetched and sent to the watch: today plus this many more. */
const val DAYS = 3

/** Hours per day. */
const val HOURS = 24

/** Hourly values of one day as an API gave them; null = nothing for that hour
 * (only happens for today - past hours OpenWeatherMap does not deliver). */
class RawHours(
    val t: Array<Double?> = arrayOfNulls(HOURS),
    val r: Array<Double?> = arrayOfNulls(HOURS),
    val u: Array<Double?> = arrayOfNulls(HOURS),
    val w: Array<Double?> = arrayOfNulls(HOURS), // km/h
)

/** One future day's hourly detail - always fully known, no caching needed. */
class HourlyDay(val temp: List<Double>, val rain: List<Double>, val uv: List<Double>, val wind: List<Double>)

/** What an API call produced, before [HourCache] fills today's gaps. */
class ForecastRaw(
    val loc: String,
    val tz: Int,
    val dayStart: Long,
    val tmax: List<Double>, val tmin: List<Double>, val code: List<Int>, val pop: List<Int>, val uvPeak: List<Double>,
    val day0: RawHours,
    val futureHourly: List<HourlyDay>, // size = tmax.size - 1, index 0 = tomorrow
    val source: String,
)

/** One day's numbers, ready for [WeatherClient.toJson]. */
data class DayForecast(
    val tmax: Double, val tmin: Double, val code: Int, val pop: Int, val uvPeak: Double,
    val temp: List<Double>, val rain: List<Double>, val uv: List<Double>, val wind: List<Double>,
)

/** Everything the watch needs: today plus up to [DAYS] - 1 more days, so it can
 * keep advancing on its own for a while with no connection to the phone. */
data class Forecast(
    val loc: String,
    val tz: Int,        // UTC offset in seconds at the location, incl. DST
    val dayStart: Long,  // epoch seconds of local midnight today
    val days: List<DayForecast>, // index 0 = today
    val source: String,
    /** First hour of today with real temperature data; earlier hours are placeholders the watch must not draw. */
    val firstHour: Int = 0,
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

    private fun dayStart(tz: Int): Long =
        floor((System.currentTimeMillis() / 1000.0 + tz) / 86400.0).toLong() * 86400 - tz

    /** A smooth diurnal curve from a day's low/high, used only where an API gives
     * no hourly detail for a day (see [oneCall]'s third day). Peaks at 15:00. */
    private fun diurnal(tmin: Double, tmax: Double): List<Double> =
        (0 until HOURS).map { h -> tmin + (tmax - tmin) * (0.5 + 0.5 * cos((h - 15) * PI / 12)) }

    // ---------------------------------------------------------------- Open-Meteo

    /** Open-Meteo already speaks WMO codes and includes past hours (past_days=1),
     * so today is complete, and its hourly forecast comfortably covers [DAYS]. */
    suspend fun openMeteo(lat: Double, lon: Double, name: String): ForecastRaw = withContext(Dispatchers.IO) {
        val url = "https://api.open-meteo.com/v1/forecast?latitude=$lat&longitude=$lon&timezone=auto" +
            "&past_days=1&forecast_days=$DAYS&windspeed_unit=kmh" +
            "&daily=temperature_2m_max,temperature_2m_min,weather_code,precipitation_probability_max,uv_index_max" +
            "&hourly=temperature_2m,precipitation,uv_index,wind_speed_10m"
        val j = JSONObject(get(url))
        val tz = j.optInt("utc_offset_seconds", 0)
        val daily = j.getJSONObject("daily")
        val hourly = j.getJSONObject("hourly")

        // With past_days=1, daily/hourly index 0 is yesterday, 1 is today, 2 tomorrow, etc.
        fun d(key: String, i: Int) = daily.getJSONArray(key).optDouble(i, 0.0)
        fun di(key: String, i: Int) = daily.getJSONArray(key).optInt(i, 0)
        val tmax = (1..DAYS).map { d("temperature_2m_max", it) }
        val tmin = (1..DAYS).map { d("temperature_2m_min", it) }
        val code = (1..DAYS).map { di("weather_code", it) }
        val pop = (1..DAYS).map { di("precipitation_probability_max", it) }
        val uvPeak = (1..DAYS).map { d("uv_index_max", it) }

        val ht = hourly.getJSONArray("temperature_2m")
        val hr = hourly.getJSONArray("precipitation")
        val hu = hourly.getJSONArray("uv_index")
        val hw = hourly.getJSONArray("wind_speed_10m")

        val day0 = RawHours()
        for (h in 0 until HOURS) {
            val i = HOURS + h // skip the "yesterday" block
            if (!ht.isNull(i)) day0.t[h] = ht.getDouble(i)
            day0.r[h] = if (hr.isNull(i)) 0.0 else hr.getDouble(i)
            day0.u[h] = if (hu.isNull(i)) 0.0 else hu.getDouble(i)
            day0.w[h] = if (hw.isNull(i)) 0.0 else hw.getDouble(i)
        }

        val future = (1 until DAYS).map { day ->
            val base = HOURS * (day + 1)
            HourlyDay(
                temp = (0 until HOURS).map { ht.optDouble(base + it, 0.0) },
                rain = (0 until HOURS).map { hr.optDouble(base + it, 0.0) },
                uv = (0 until HOURS).map { hu.optDouble(base + it, 0.0) },
                wind = (0 until HOURS).map { hw.optDouble(base + it, 0.0) },
            )
        }

        ForecastRaw(loc = name, tz = tz, dayStart = dayStart(tz), tmax = tmax, tmin = tmin, code = code,
            pop = pop, uvPeak = uvPeak, day0 = day0, futureHourly = future, source = "Open-Meteo")
    }

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
    suspend fun openWeatherMap(key: String, lat: Double, lon: Double, name: String): ForecastRaw = withContext(Dispatchers.IO) {
        try {
            oneCall(key, lat, lon, name)
        } catch (e: HttpStatusException) {
            // 401 = key has no One Call 3.0 subscription -> free forecast instead.
            if (e.code == 401 || e.code == 403 || e.code == 429) forecast25(key, lat, lon, name) else throw e
        }
    }

    /** One Call 3.0's hourly forecast only reaches ~48h ahead (today + tomorrow):
     * day 2 (the day after tomorrow) gets no hourly rain/UV/wind, and its
     * temperature is a smooth curve from that day's low/high rather than real
     * hourly readings. Open-Meteo (free, the default source) does not have
     * this gap. */
    private fun oneCall(key: String, lat: Double, lon: Double, name: String): ForecastRaw {
        val j = JSONObject(get("$OWM/data/3.0/onecall?lat=$lat&lon=$lon&exclude=minutely,alerts&units=metric&appid=$key"))
        val tz = j.optInt("timezone_offset", 0)
        val ds = dayStart(tz)

        val day0 = RawHours()
        val day1 = RawHours()
        val hourly = j.getJSONArray("hourly")
        for (i in 0 until hourly.length()) {
            val h = hourly.getJSONObject(i)
            val idx = ((h.getLong("dt") - ds) / 3600).toInt()
            val windKmh = h.optDouble("wind_speed", 0.0) * 3.6
            when {
                idx in 0..23 -> {
                    day0.t[idx] = h.getDouble("temp")
                    day0.r[idx] = h.optJSONObject("rain")?.optDouble("1h", 0.0) ?: 0.0
                    day0.u[idx] = h.optDouble("uvi", 0.0)
                    day0.w[idx] = windKmh
                }
                idx in 24..47 -> {
                    val hh = idx - 24
                    day1.t[hh] = h.getDouble("temp")
                    day1.r[hh] = h.optJSONObject("rain")?.optDouble("1h", 0.0) ?: 0.0
                    day1.u[hh] = h.optDouble("uvi", 0.0)
                    day1.w[hh] = windKmh
                }
            }
        }

        val daily = j.getJSONArray("daily")
        val tmax = ArrayList<Double>(DAYS)
        val tmin = ArrayList<Double>(DAYS)
        val code = ArrayList<Int>(DAYS)
        val pop = ArrayList<Int>(DAYS)
        val uvPeak = ArrayList<Double>(DAYS)
        for (d in 0 until DAYS) {
            val dj = daily.getJSONObject(minOf(d, daily.length() - 1))
            tmax.add(dj.getJSONObject("temp").getDouble("max"))
            tmin.add(dj.getJSONObject("temp").getDouble("min"))
            code.add(wmo(dj.getJSONArray("weather").getJSONObject(0).getInt("id")))
            pop.add((dj.optDouble("pop", 0.0) * 100).roundToInt())
            uvPeak.add(dj.optDouble("uvi", 0.0))
        }

        val future = (1 until DAYS).map { d ->
            if (d == 1) {
                HourlyDay(
                    temp = (0 until HOURS).map { day1.t[it] ?: tmin[1] },
                    rain = (0 until HOURS).map { day1.r[it] ?: 0.0 },
                    uv = (0 until HOURS).map { day1.u[it] ?: 0.0 },
                    wind = (0 until HOURS).map { day1.w[it] ?: 0.0 },
                )
            } else {
                HourlyDay(temp = diurnal(tmin[d], tmax[d]), rain = List(HOURS) { 0.0 },
                    uv = List(HOURS) { 0.0 }, wind = List(HOURS) { 0.0 })
            }
        }

        return ForecastRaw(loc = name, tz = tz, dayStart = ds, tmax = tmax, tmin = tmin, code = code,
            pop = pop, uvPeak = uvPeak, day0 = day0, futureHourly = future, source = "OpenWeatherMap One Call 3.0")
    }

    /** Free 5-day/3-hour forecast: interpolated to hours, no UV data, but its
     * 5-day span covers all of [DAYS] with real (if coarse) data throughout. */
    private fun forecast25(key: String, lat: Double, lon: Double, name: String): ForecastRaw {
        val j = JSONObject(get("$OWM/data/2.5/forecast?lat=$lat&lon=$lon&units=metric&appid=$key"))
        val tz = j.getJSONObject("city").optInt("timezone", 0)
        val ds = dayStart(tz)
        val list = j.getJSONArray("list")

        class P(val t: Long, val temp: Double, val rain3h: Double, val pop: Double, val windKmh: Double, val id: Int)
        val pts = (0 until list.length()).map {
            val e = list.getJSONObject(it)
            P(e.getLong("dt"), e.getJSONObject("main").getDouble("temp"),
                e.optJSONObject("rain")?.optDouble("3h", 0.0) ?: 0.0,
                e.optDouble("pop", 0.0),
                (e.optJSONObject("wind")?.optDouble("speed", 0.0) ?: 0.0) * 3.6,
                e.getJSONArray("weather").getJSONObject(0).getInt("id"))
        }

        fun tempAt(t: Long): Double? {
            val a = pts.lastOrNull { it.t <= t } ?: return null
            val b = pts.firstOrNull { it.t >= t } ?: return null
            if (a.t == b.t) return a.temp
            return a.temp + (b.temp - a.temp) * (t - a.t).toDouble() / (b.t - a.t)
        }

        val day0 = RawHours()
        val future = ArrayList<HourlyDay>(DAYS - 1)
        for (day in 0 until DAYS) {
            val temp = DoubleArray(HOURS)
            val rain = DoubleArray(HOURS)
            val wind = DoubleArray(HOURS)
            for (h in 0 until HOURS) {
                val t = ds + (day * HOURS + h) * 3600L
                val tv = tempAt(t)
                temp[h] = tv ?: 0.0
                // 3h slot ending at "it.t" covers [it.t - 3h, it.t).
                val slot = pts.firstOrNull { t >= it.t - 3 * 3600 && t < it.t }
                rain[h] = slot?.let { it.rain3h / 3.0 } ?: 0.0
                wind[h] = slot?.windKmh ?: 0.0
                if (day == 0) {
                    day0.t[h] = tv
                    day0.r[h] = rain[h]
                    day0.u[h] = 0.0
                    day0.w[h] = wind[h]
                }
            }
            if (day > 0) future.add(HourlyDay(temp.toList(), rain.toList(), List(HOURS) { 0.0 }, wind.toList()))
        }

        fun dayPts(day: Int) = pts.filter { it.t >= ds + day * 86400L && it.t < ds + (day + 1) * 86400L }
        fun rep(l: List<P>) = l.getOrNull(l.size / 2)?.id ?: 800

        val tmax = ArrayList<Double>(DAYS)
        val tmin = ArrayList<Double>(DAYS)
        val code = ArrayList<Int>(DAYS)
        val pop = ArrayList<Int>(DAYS)
        var prev = dayPts(0).ifEmpty { pts.take(1) }
        for (day in 0 until DAYS) {
            val dp = dayPts(day).ifEmpty { prev }
            tmax.add(dp.maxOf { it.temp }); tmin.add(dp.minOf { it.temp })
            code.add(wmo(rep(dp))); pop.add(((dp.maxOfOrNull { it.pop } ?: 0.0) * 100).roundToInt())
            prev = dp
        }

        return ForecastRaw(
            loc = name.ifEmpty { asciiName(j.getJSONObject("city").optString("name", "")) },
            tz = tz, dayStart = ds, tmax = tmax, tmin = tmin, code = code, pop = pop,
            uvPeak = List(DAYS) { 0.0 }, day0 = day0, futureHourly = future,
            source = "OpenWeatherMap Forecast 2.5 (Free, ohne UV)",
        )
    }

    // ---------------------------------------------------------------- JSON for the watch

    fun toJson(f: Forecast, nowSec: Long): String {
        fun r1(v: Double) = (v * 10.0).roundToInt() / 10.0
        val o = JSONObject()
        o.put("v", 2)
        o.put("ts", nowSec)
        o.put("tz", f.tz)
        o.put("hs", f.firstHour)
        o.put("loc", f.loc)
        o.put("days", f.days.size)
        o.put("tmax", JSONArray(f.days.map { r1(it.tmax) }))
        o.put("tmin", JSONArray(f.days.map { r1(it.tmin) }))
        o.put("code", JSONArray(f.days.map { it.code }))
        o.put("pop", JSONArray(f.days.map { min(100, max(0, it.pop)) }))
        o.put("uv", JSONArray(f.days.map { r1(it.uvPeak) }))
        o.put("t", JSONArray(f.days.flatMap { it.temp }.map { r1(it) }))
        o.put("r", JSONArray(f.days.flatMap { it.rain }.map { r1(it) }))
        o.put("u", JSONArray(f.days.flatMap { it.uv }.map { r1(it) }))
        // Whole km/h: the watch line only needs a peak label, not fractions, and it saves bytes.
        o.put("w", JSONArray(f.days.flatMap { it.wind }.map { it.roundToInt() }))
        return o.toString()
    }
}
