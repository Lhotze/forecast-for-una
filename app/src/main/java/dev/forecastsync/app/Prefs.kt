package dev.forecastsync.app

import android.content.Context

/** All persisted settings and the last sync status in one place. */
class Prefs(ctx: Context) {
    private val sp = ctx.applicationContext.getSharedPreferences("forecast_sync", Context.MODE_PRIVATE)

    var apiKey: String
        get() = sp.getString("apiKey", "") ?: ""
        set(v) = sp.edit().putString("apiKey", v.trim()).apply()

    /** MAC address of the bonded watch. */
    var deviceAddress: String
        get() = sp.getString("deviceAddress", "") ?: ""
        set(v) = sp.edit().putString("deviceAddress", v).apply()

    /** Folder name of the watch app under /Apps (its app ID). */
    var appFolder: String
        get() = sp.getString("appFolder", "F729D18A6934974B") ?: "F729D18A6934974B"
        set(v) = sp.edit().putString("appFolder", v.trim()).apply()

    /** "openmeteo" (no key) or "owm" (OpenWeatherMap, needs the API key). */
    var provider: String
        get() = sp.getString("provider", "openmeteo") ?: "openmeteo"
        set(v) = sp.edit().putString("provider", v).apply()

    /** Refresh interval in minutes: 10, 20 or 30. */
    var intervalMin: Int
        get() = sp.getInt("intervalMin", 30)
        set(v) = sp.edit().putInt("intervalMin", v).apply()

    /** Cached place name for a rounded position, so the geocoder is not asked every run. */
    var locKey: String
        get() = sp.getString("locKey", "") ?: ""
        set(v) = sp.edit().putString("locKey", v).apply()

    var locName: String
        get() = sp.getString("locName", "") ?: ""
        set(v) = sp.edit().putString("locName", v).apply()

    /** Hourly values already seen today (the APIs only return the future). JSON, see HourCache. */
    var hourHistory: String
        get() = sp.getString("hourHistory", "") ?: ""
        set(v) = sp.edit().putString("hourHistory", v).apply()

    var autoSync: Boolean
        get() = sp.getBoolean("autoSync", false)
        set(v) = sp.edit().putBoolean("autoSync", v).apply()

    var lastLat: Double
        get() = java.lang.Double.longBitsToDouble(sp.getLong("lastLat", java.lang.Double.doubleToRawLongBits(Double.NaN)))
        set(v) = sp.edit().putLong("lastLat", java.lang.Double.doubleToRawLongBits(v)).apply()

    var lastLon: Double
        get() = java.lang.Double.longBitsToDouble(sp.getLong("lastLon", java.lang.Double.doubleToRawLongBits(Double.NaN)))
        set(v) = sp.edit().putLong("lastLon", java.lang.Double.doubleToRawLongBits(v)).apply()

    var status: String
        get() = sp.getString("status", "not synced yet") ?: ""
        set(v) = sp.edit().putString("status", v).apply()

    var statusTime: Long
        get() = sp.getLong("statusTime", 0L)
        set(v) = sp.edit().putLong("statusTime", v).apply()

    fun saveStatus(text: String) {
        status = text
        statusTime = System.currentTimeMillis()
    }

    // ---- calendar (Agenda glance) -------------------------------------------------------

    /** Folder name of the Agenda watch app under /Apps (its app ID). */
    var calFolder: String
        get() = sp.getString("calFolder", "49F89FCBC7B9474B") ?: "49F89FCBC7B9474B"
        set(v) = sp.edit().putString("calFolder", v.trim()).apply()

    /** IDs of the calendars to send (Android calendar provider IDs), comma separated. */
    var calIds: Set<Long>
        get() = (sp.getString("calIds", "") ?: "").split(',').mapNotNull { it.trim().toLongOrNull() }.toSet()
        set(v) = sp.edit().putString("calIds", v.joinToString(",")).apply()

    /** Refresh interval of the calendar in minutes: 10, 20 or 30 (independent of the weather). */
    var calIntervalMin: Int
        get() = sp.getInt("calIntervalMin", 30)
        set(v) = sp.edit().putInt("calIntervalMin", v).apply()

    var calAutoSync: Boolean
        get() = sp.getBoolean("calAutoSync", false)
        set(v) = sp.edit().putBoolean("calAutoSync", v).apply()

    var calStatus: String
        get() = sp.getString("calStatus", "not synced yet") ?: ""
        set(v) = sp.edit().putString("calStatus", v).apply()

    var calStatusTime: Long
        get() = sp.getLong("calStatusTime", 0L)
        set(v) = sp.edit().putLong("calStatusTime", v).apply()

    fun saveCalStatus(text: String) {
        calStatus = text
        calStatusTime = System.currentTimeMillis()
    }

    // ---- Find my Phone ----------------------------------------------------------------------

    /** Folder name of the Find my Phone watch app under /Apps (its app ID). */
    var findFolder: String
        get() = sp.getString("findFolder", FindRule.DEFAULT_FOLDER) ?: FindRule.DEFAULT_FOLDER
        set(v) = sp.edit().putString("findFolder", v.trim()).apply()

    /** How often the watch is checked, in seconds; 0 = off. */
    var findIntervalSec: Int
        get() = sp.getInt("findIntervalSec", 0)
        set(v) = sp.edit().putInt("findIntervalSec", v).apply()

    /** Ring even in Do Not Disturb (needs the Do Not Disturb access to be granted to this app). */
    var findDnd: Boolean
        get() = sp.getBoolean("findDnd", true)
        set(v) = sp.edit().putBoolean("findDnd", v).apply()

    /** Time stamp of the last request that was acted on, so one request never rings twice. */
    var findHandledTs: Long
        get() = sp.getLong("findHandledTs", 0L)
        set(v) = sp.edit().putLong("findHandledTs", v).apply()

    var findStatus: String
        get() = sp.getString("findStatus", "not checked yet") ?: ""
        set(v) = sp.edit().putString("findStatus", v).apply()

    var findStatusTime: Long
        get() = sp.getLong("findStatusTime", 0L)
        set(v) = sp.edit().putLong("findStatusTime", v).apply()

    fun saveFindStatus(text: String) {
        findStatus = text
        findStatusTime = System.currentTimeMillis()
    }

    fun registerListener(l: android.content.SharedPreferences.OnSharedPreferenceChangeListener) =
        sp.registerOnSharedPreferenceChangeListener(l)

    fun unregisterListener(l: android.content.SharedPreferences.OnSharedPreferenceChangeListener) =
        sp.unregisterOnSharedPreferenceChangeListener(l)
}
