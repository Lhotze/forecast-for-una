package dev.forecastsync.app

import org.json.JSONArray
import org.json.JSONObject

/**
 * OpenWeatherMap only returns the current and future hours, so the past hours of
 * today would otherwise be filled with a copy of the current value (a flat line).
 * This remembers every hourly value seen during the day and reuses it for hours
 * that have passed. Values the API does return always win over what is stored.
 * Only today (day 0) needs this: tomorrow and later are always entirely future,
 * so every API gives them complete hourly data already.
 */
object HourCache {

    fun apply(prefs: Prefs, raw: ForecastRaw): Forecast {
        val t = arrayOfNulls<Double>(HOURS)
        val r = arrayOfNulls<Double>(HOURS)
        val u = arrayOfNulls<Double>(HOURS)
        val w = arrayOfNulls<Double>(HOURS)

        try {
            val h = JSONObject(prefs.hourHistory)
            if (h.optLong("day", -1) == raw.dayStart) {
                load(h.optJSONArray("t"), t)
                load(h.optJSONArray("r"), r)
                load(h.optJSONArray("u"), u)
                load(h.optJSONArray("w"), w)
            }
        } catch (_: Exception) { /* no or unreadable history: start empty */ }

        for (i in 0 until HOURS) {
            raw.day0.t[i]?.let { t[i] = it }
            raw.day0.r[i]?.let { r[i] = it }
            raw.day0.u[i]?.let { u[i] = it }
            raw.day0.w[i]?.let { w[i] = it }
        }

        val o = JSONObject()
        o.put("day", raw.dayStart)
        o.put("t", store(t)); o.put("r", store(r)); o.put("u", store(u)); o.put("w", store(w))
        prefs.hourHistory = o.toString()

        // Hours never seen at all (first run of the day, OpenWeatherMap): earliest known value.
        val firstIdx = t.indexOfFirst { it != null }.let { if (it < 0) 0 else it }
        val firstT = t.firstOrNull { it != null } ?: 0.0
        var last = firstT
        val tempToday = t.map { v -> if (v != null) { last = v; v } else last }
        val rainToday = r.map { it ?: 0.0 }
        val uvToday = u.map { it ?: 0.0 }
        val windToday = w.map { it ?: 0.0 }

        val days = ArrayList<DayForecast>(raw.tmax.size)
        days.add(DayForecast(raw.tmax[0], raw.tmin[0], raw.code[0], raw.pop[0], raw.uvPeak[0],
            tempToday, rainToday, uvToday, windToday))
        for (i in 1 until raw.tmax.size) {
            val fh = raw.futureHourly.getOrNull(i - 1)
            days.add(DayForecast(raw.tmax[i], raw.tmin[i], raw.code[i], raw.pop[i], raw.uvPeak[i],
                fh?.temp ?: List(HOURS) { 0.0 }, fh?.rain ?: List(HOURS) { 0.0 },
                fh?.uv ?: List(HOURS) { 0.0 }, fh?.wind ?: List(HOURS) { 0.0 }))
        }

        return Forecast(loc = raw.loc, tz = raw.tz, dayStart = raw.dayStart, days = days,
            source = raw.source, firstHour = firstIdx)
    }

    private fun load(a: JSONArray?, into: Array<Double?>) {
        if (a == null) return
        for (i in 0 until minOf(HOURS, a.length())) if (!a.isNull(i)) into[i] = a.getDouble(i)
    }

    private fun store(a: Array<Double?>): JSONArray {
        val j = JSONArray()
        a.forEach { if (it == null) j.put(JSONObject.NULL) else j.put(it) }
        return j
    }
}
