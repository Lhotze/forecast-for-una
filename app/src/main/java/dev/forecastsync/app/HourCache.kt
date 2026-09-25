package dev.forecastsync.app

import org.json.JSONArray
import org.json.JSONObject

/**
 * OpenWeatherMap only returns the current and future hours, so the past hours of
 * today would otherwise be filled with a copy of the current value (a flat line).
 * This remembers every hourly value seen during the day and reuses it for hours
 * that have passed. Values the API does return always win over what is stored.
 */
object HourCache {

    fun apply(prefs: Prefs, f: Forecast): Forecast {
        val t = arrayOfNulls<Double>(24)
        val r = arrayOfNulls<Double>(24)
        val u = arrayOfNulls<Double>(24)

        try {
            val h = JSONObject(prefs.hourHistory)
            if (h.optLong("day", -1) == f.dayStart) {
                load(h.optJSONArray("t"), t); load(h.optJSONArray("r"), r); load(h.optJSONArray("u"), u)
            }
        } catch (_: Exception) { /* no or unreadable history: start empty */ }

        for (i in 0 until 24) {
            f.raw.t[i]?.let { t[i] = it }
            f.raw.r[i]?.let { r[i] = it }
            f.raw.u[i]?.let { u[i] = it }
        }

        val o = JSONObject()
        o.put("day", f.dayStart)
        o.put("t", store(t)); o.put("r", store(r)); o.put("u", store(u))
        prefs.hourHistory = o.toString()

        // Hours never seen at all (first run of the day, OpenWeatherMap): pad them with the
        // earliest known value so the arrays stay complete, and tell the watch where real data starts.
        val firstIdx = t.indexOfFirst { it != null }.let { if (it < 0) 0 else it }
        val firstT = t.firstOrNull { it != null } ?: 0.0
        var last = firstT
        val tt = t.map { v -> if (v != null) { last = v; v } else last }
        return f.copy(hourlyTemp = tt, hourlyRain = r.map { it ?: 0.0 }, hourlyUv = u.map { it ?: 0.0 }, firstHour = firstIdx)
    }

    private fun load(a: JSONArray?, into: Array<Double?>) {
        if (a == null) return
        for (i in 0 until minOf(24, a.length())) if (!a.isNull(i)) into[i] = a.getDouble(i)
    }

    private fun store(a: Array<Double?>): JSONArray {
        val j = JSONArray()
        a.forEach { if (it == null) j.put(JSONObject.NULL) else j.put(it) }
        return j
    }
}
