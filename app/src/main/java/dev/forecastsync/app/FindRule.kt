package dev.forecastsync.app

/**
 * The rules of the Find my Phone request, kept free of Android classes so they are easy to reason about.
 *
 * The watch utility writes `ring.txt` ("ts=<UTC seconds>") into its app folder and deletes it again to cancel.
 * This app looks for that file over Bluetooth, rings if the request is recent enough, and deletes the file.
 */
object FindRule {
    const val DEFAULT_FOLDER = "B8E0979895A97AFD"
    const val FILE_NAME = "ring.txt"

    /** How long the phone rings. */
    const val ALARM_SECONDS = 30

    /** A request this old (plus one check interval, so it cannot expire between two checks) still rings. */
    const val BASE_MAX_AGE_SECONDS = 10 * 60L

    /** How often the watch is asked while the alarm is ringing, so a cancel on the watch ends it quickly. */
    const val CANCEL_POLL_SECONDS = 3

    /** The choices of the interval setting: seconds (0 = off) and its label. */
    val INTERVALS: List<Pair<Int, String>> = listOf(
        0 to "Off",
        30 to "30 seconds",
        60 to "1 minute",
        120 to "2 minutes",
        180 to "3 minutes",
        240 to "4 minutes",
        300 to "5 minutes",
        600 to "10 minutes",
    )

    fun intervalLabel(seconds: Int) = INTERVALS.firstOrNull { it.first == seconds }?.second ?: "Off"

    private val TS = Regex("^ts=(\\d{1,12})")

    /** The time stamp in the file, or null if the text is not (yet) a complete request. */
    fun parse(text: String): Long? =
        TS.find(text.trim())?.groupValues?.get(1)?.toLongOrNull()?.takeIf { it > 0 }

    fun maxAgeSeconds(intervalSeconds: Int) = BASE_MAX_AGE_SECONDS + intervalSeconds.coerceAtLeast(0)

    enum class Verdict {
        /** Recent: ring for [ALARM_SECONDS]. */
        RING,
        /** Too old: only tell the user that the watch asked, then delete the file. */
        STALE,
        /** The same request was handled before (its file could not be deleted then): just delete it. */
        ALREADY_HANDLED,
    }

    /** A time stamp in the future (watch clock ahead of the phone) counts as brand new. */
    fun decide(ts: Long, nowSec: Long, handledTs: Long, intervalSeconds: Int): Verdict = when {
        ts == handledTs -> Verdict.ALREADY_HANDLED
        nowSec - ts <= maxAgeSeconds(intervalSeconds) -> Verdict.RING
        else -> Verdict.STALE
    }
}
