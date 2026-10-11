package dev.forecastsync.app

import android.Manifest
import android.content.ContentUris
import android.content.Context
import android.content.pm.PackageManager
import android.provider.CalendarContract
import androidx.core.content.ContextCompat
import java.text.Normalizer
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset
import java.util.TimeZone

/**
 * Reads appointments from the phone's own calendar storage (the calendars Google Calendar and other
 * accounts are synchronised into) and turns them into the calendar.json the Agenda watch app reads:
 *
 *   {"v":1,"ts":<unix s>,"tz":<UTC offset s>,"ev":[{"s":<start>,"e":<end>,"a":<1 if all day>,"t":"<title>"}, ...]}
 *
 * No network, no Google sign-in: only the READ_CALENDAR permission.
 */
object CalendarClient {

    /** What the watch can hold: 12 appointments, 2000 bytes, ASCII titles of at most 40 characters. */
    const val MAX_EVENTS = 12
    const val MAX_BYTES = 2000
    const val MAX_TITLE = 40
    private const val LOOKAHEAD_DAYS = 14L

    class CalInfo(val id: Long, val name: String, val account: String, val color: Int)

    class Appointment(val start: Long, val end: Long, val allDay: Boolean, val title: String)

    fun hasPermission(ctx: Context) =
        ContextCompat.checkSelfPermission(ctx, Manifest.permission.READ_CALENDAR) == PackageManager.PERMISSION_GRANTED

    /** All calendars on the phone, as the calendar storage lists them. Empty without the permission. */
    fun listCalendars(ctx: Context): List<CalInfo> {
        if (!hasPermission(ctx)) return emptyList()
        val out = mutableListOf<CalInfo>()
        val proj = arrayOf(
            CalendarContract.Calendars._ID,
            CalendarContract.Calendars.CALENDAR_DISPLAY_NAME,
            CalendarContract.Calendars.ACCOUNT_NAME,
            CalendarContract.Calendars.CALENDAR_COLOR,
        )
        ctx.contentResolver.query(CalendarContract.Calendars.CONTENT_URI, proj, null, null,
            "${CalendarContract.Calendars.ACCOUNT_NAME}, ${CalendarContract.Calendars.CALENDAR_DISPLAY_NAME}")?.use { c ->
            while (c.moveToNext()) {
                out += CalInfo(c.getLong(0), c.getString(1) ?: "?", c.getString(2) ?: "", c.getInt(3))
            }
        }
        return out
    }

    /**
     * The appointments of the chosen calendars that have not ended yet, in start order, at most [MAX_EVENTS].
     * Running appointments are included (the watch shows them as "Now - 15:30"). Declined and cancelled ones are left out.
     */
    fun appointments(ctx: Context, calendarIds: Set<Long>, nowMs: Long): List<Appointment> {
        if (!hasPermission(ctx) || calendarIds.isEmpty()) return emptyList()
        val uri = CalendarContract.Instances.CONTENT_URI.buildUpon().also {
            ContentUris.appendId(it, nowMs)
            ContentUris.appendId(it, nowMs + LOOKAHEAD_DAYS * 24 * 3600 * 1000)
        }.build()
        val proj = arrayOf(
            CalendarContract.Instances.BEGIN,
            CalendarContract.Instances.END,
            CalendarContract.Instances.TITLE,
            CalendarContract.Instances.ALL_DAY,
            CalendarContract.Instances.STATUS,
            CalendarContract.Instances.SELF_ATTENDEE_STATUS,
        )
        // the ids are numbers read from the calendar storage itself: no user text goes into the query
        val selection = "${CalendarContract.Instances.CALENDAR_ID} IN (${calendarIds.joinToString(",")})"
        val zone = ZoneId.systemDefault()
        val nowS = nowMs / 1000
        val out = mutableListOf<Appointment>()
        ctx.contentResolver.query(uri, proj, selection, null, "${CalendarContract.Instances.BEGIN} ASC")?.use { c ->
            while (c.moveToNext()) {
                val status = c.getInt(4)
                val self = c.getInt(5)
                if (status == CalendarContract.Events.STATUS_CANCELED) continue
                if (self == CalendarContract.Attendees.ATTENDEE_STATUS_DECLINED) continue
                val allDay = c.getInt(3) != 0
                var start = c.getLong(0) / 1000
                var end = c.getLong(1) / 1000
                if (allDay) {
                    // all-day appointments are stored as UTC midnights; the watch wants the local day
                    start = Instant.ofEpochSecond(start).atZone(ZoneOffset.UTC).toLocalDate().atStartOfDay(zone).toEpochSecond()
                    end = Instant.ofEpochSecond(end).atZone(ZoneOffset.UTC).toLocalDate().atStartOfDay(zone).toEpochSecond()
                    if (end <= start) end = start + 24 * 3600
                }
                if (end <= nowS && !(end == start && start > nowS)) continue       // already over
                out += Appointment(start, end, allDay, cleanTitle(c.getString(2)))
            }
        }
        out.sortWith(compareBy({ it.start }, { it.end }))
        return out.take(MAX_EVENTS)
    }

    /** The file for the watch, within [MAX_BYTES]: appointments are dropped from the end until it fits. */
    fun toJson(list: List<Appointment>, nowMs: Long): String {
        val tz = TimeZone.getDefault().getOffset(nowMs) / 1000
        var n = list.size
        while (true) {
            val sb = StringBuilder()
            sb.append("{\"v\":1,\"ts\":").append(nowMs / 1000).append(",\"tz\":").append(tz).append(",\"ev\":[")
            for (i in 0 until n) {
                val a = list[i]
                if (i > 0) sb.append(',')
                sb.append("{\"s\":").append(a.start).append(",\"e\":").append(a.end)
                if (a.allDay) sb.append(",\"a\":1")
                sb.append(",\"t\":\"").append(a.title).append("\"}")
            }
            sb.append("]}")
            if (n == 0 || sb.toString().toByteArray(Charsets.UTF_8).size <= MAX_BYTES) return sb.toString()
            --n
        }
    }

    private val replacements = mapOf(
        'ä' to "ae", 'ö' to "oe", 'ü' to "ue", 'Ä' to "Ae", 'Ö' to "Oe", 'Ü' to "Ue", 'ß' to "ss",
        'æ' to "ae", 'Æ' to "AE", 'ø' to "o", 'Ø' to "O", 'å' to "a", 'Å' to "A", 'œ' to "oe", 'Œ' to "OE",
        'ł' to "l", 'Ł' to "L", 'đ' to "d", 'Đ' to "D", 'ð' to "d", 'þ' to "th",
        '‘' to "'", '’' to "'", '“' to "'", '”' to "'", '–' to "-", '—' to "-", '…' to "...",
    )

    /**
     * Makes a title fit what the watch can show: Latin letters only (umlauts become "ae" and so on, accents are dropped),
     * no quotes or backslashes (they would break the file), no line breaks, at most [MAX_TITLE] characters.
     */
    fun cleanTitle(raw: String?): String {
        val src = raw?.trim().orEmpty()
        val sb = StringBuilder()
        for (ch in src) {
            val rep = replacements[ch]
            if (rep != null) { sb.append(rep); continue }
            val decomposed = Normalizer.normalize(ch.toString(), Normalizer.Form.NFD)
            for (d in decomposed) {
                when {
                    Character.getType(d) == Character.NON_SPACING_MARK.toInt() -> {}                // an accent: drop
                    d == '"' -> sb.append('\'')
                    d == '\\' -> sb.append('/')
                    d == '\n' || d == '\r' || d == '\t' -> sb.append(' ')
                    d.code in 32..126 -> sb.append(d)
                    else -> sb.append('?')
                }
            }
        }
        val collapsed = sb.toString().replace(Regex(" {2,}"), " ").trim()
        return if (collapsed.length > MAX_TITLE) collapsed.substring(0, MAX_TITLE).trimEnd() else collapsed
    }
}
