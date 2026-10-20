package dev.forecastsync.app

import android.annotation.SuppressLint
import android.app.AlarmManager
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.media.AudioAttributes
import android.media.AudioManager
import android.media.MediaPlayer
import android.media.RingtoneManager
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.os.SystemClock
import android.os.VibrationEffect
import android.os.Vibrator
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.withLock
import java.io.FileNotFoundException
import java.text.DateFormat
import java.util.Date

/**
 * Watches the Find my Phone watch app over Bluetooth and rings the phone.
 *
 * It runs as a foreground service while a check interval is set (the notification is what lets Android
 * keep it alive and lets it start the alarm from the background). Between two checks the CPU sleeps: the
 * next check is an alarm clock entry ([scheduleTick]), and a wake lock is only held while a check or the
 * alarm is running. Android may stretch the interval while the phone lies still in Doze mode.
 *
 * One check = connect to the watch, list its Find app folder, and if `ring.txt` is there, read it:
 *  - recent request  -> 30 seconds alarm, then the file is deleted (a cancel on the watch, i.e. the file
 *    disappearing, or the Stop button in the notification ends it earlier);
 *  - old request     -> only a notification, the file is deleted;
 *  - already handled -> the file is deleted (an earlier delete had not got through).
 * The Bluetooth lock is shared with the weather and calendar sync, so they take turns.
 */
@SuppressLint("MissingPermission")
class FindService : Service() {

    companion object {
        const val ACTION_START = "dev.forecastsync.app.FIND_START"
        const val ACTION_TICK = "dev.forecastsync.app.FIND_TICK"
        const val ACTION_CHECK_NOW = "dev.forecastsync.app.FIND_CHECK_NOW"
        const val ACTION_TEST = "dev.forecastsync.app.FIND_TEST"
        const val ACTION_STOP_ALARM = "dev.forecastsync.app.FIND_STOP_ALARM"

        private const val CH_WATCH = "find_watch"
        private const val CH_ALARM = "find_alarm"
        private const val CH_INFO = "find_info"
        private const val ID_WATCH = 11
        private const val ID_ALARM = 12
        private const val ID_INFO = 13

        /** Starts or stops the service to match the interval setting. */
        fun apply(ctx: Context) {
            if (Prefs(ctx).findIntervalSec > 0) send(ctx, ACTION_START)
            else {
                cancelTick(ctx)
                ctx.stopService(Intent(ctx, FindService::class.java))
            }
        }

        fun send(ctx: Context, action: String) {
            ContextCompat.startForegroundService(ctx, Intent(ctx, FindService::class.java).setAction(action))
        }

        private fun tickIntent(ctx: Context): PendingIntent =
            PendingIntent.getForegroundService(
                ctx, 7, Intent(ctx, FindService::class.java).setAction(ACTION_TICK),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )

        fun canScheduleExact(ctx: Context): Boolean {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return true
            return (ctx.getSystemService(Context.ALARM_SERVICE) as AlarmManager).canScheduleExactAlarms()
        }

        private fun scheduleTick(ctx: Context, seconds: Int) {
            val am = ctx.getSystemService(Context.ALARM_SERVICE) as AlarmManager
            val at = SystemClock.elapsedRealtime() + seconds * 1000L
            val pi = tickIntent(ctx)
            try {
                if (canScheduleExact(ctx)) am.setExactAndAllowWhileIdle(AlarmManager.ELAPSED_REALTIME_WAKEUP, at, pi)
                else am.setAndAllowWhileIdle(AlarmManager.ELAPSED_REALTIME_WAKEUP, at, pi)
            } catch (e: SecurityException) {
                am.setAndAllowWhileIdle(AlarmManager.ELAPSED_REALTIME_WAKEUP, at, pi)
            }
        }

        private fun cancelTick(ctx: Context) {
            (ctx.getSystemService(Context.ALARM_SERVICE) as AlarmManager).cancel(tickIntent(ctx))
        }
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var job: Job? = null
    @Volatile private var stopRequested = false
    @Volatile private var destroyed = false

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        try {
            startAsForeground()
        } catch (e: Exception) {
            // e.g. the Bluetooth permission was taken away: say so instead of crashing
            Prefs(this).saveFindStatus("${now()} Cannot run in the background: ${e.message ?: e.javaClass.simpleName}")
            stopSelf()
            return START_NOT_STICKY
        }
        when (intent?.action) {
            ACTION_STOP_ALARM -> {
                stopRequested = true
                if (job?.isActive != true) afterJob()          // nothing is ringing: go back to idle
            }
            ACTION_TEST -> launchJob { testAlarm() }
            else -> launchJob { check() }          // START, TICK, CHECK_NOW and a restart by the system
        }
        return START_STICKY
    }

    override fun onDestroy() {
        destroyed = true
        scope.cancel()
        super.onDestroy()
    }

    // ---- one unit of work at a time, with the CPU kept awake while it runs -----------------------

    private fun launchJob(body: suspend () -> Unit) {
        if (job?.isActive == true) return
        job = scope.launch {
            val wl = (getSystemService(Context.POWER_SERVICE) as PowerManager)
                .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "forecastsync:find")
            wl.acquire(3 * 60_000L)
            try {
                body()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Prefs(this@FindService).saveFindStatus("${now()} Error: ${e.message ?: e.javaClass.simpleName}")
            } finally {
                if (wl.isHeld) wl.release()
                afterJob()
            }
        }
    }

    private fun afterJob() {
        if (destroyed) return
        val sec = Prefs(this).findIntervalSec
        if (sec > 0) {
            scheduleTick(this, sec)
        } else {
            cancelTick(this)
            stopSelf()
        }
    }

    private fun now(): String = DateFormat.getTimeInstance(DateFormat.SHORT).format(Date())

    // ---- the check ------------------------------------------------------------------------------

    private suspend fun check() {
        val prefs = Prefs(this)
        val address = prefs.deviceAddress
        val folder = prefs.findFolder
        if (address.isBlank()) { prefs.saveFindStatus("${now()} No watch selected (choose it on the Weather tab)"); return }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S &&
            ContextCompat.checkSelfPermission(this, android.Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED) {
            prefs.saveFindStatus("${now()} Bluetooth permission missing"); return
        }
        if (!Regex("[0-9A-Fa-f]{16}").matches(folder)) { prefs.saveFindStatus("${now()} The app folder must be 16 hex characters"); return }

        val dir = "/Apps/$folder"
        val path = "$dir/${FindRule.FILE_NAME}"
        try {
            SyncEngine.bleLock.withLock {
                FtsClient(this).withConnection(address) {
                    val entry = listDir(dir).firstOrNull { !it.isDir && it.name.equals(FindRule.FILE_NAME, ignoreCase = true) }
                    if (entry == null) {
                        prefs.saveFindStatus("${now()} Watch reached, no request")
                    } else {
                        try {
                            handleRequest(this, prefs, dir, path, entry)
                        } catch (e: FileNotFoundException) {
                            prefs.saveFindStatus("${now()} Watch reached, no request")     // cancelled just now
                        }
                    }
                }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            prefs.saveFindStatus("${now()} Watch not reachable: ${e.message ?: e.javaClass.simpleName}")
        }
    }

    private suspend fun handleRequest(c: FtsClient, prefs: Prefs, dir: String, path: String, entry: FtsClient.Entry) {
        // The time stamp is in the file; the file's own modification time is the fallback for an unreadable one.
        var ts: Long? = null
        if (entry.size in 1..FtsClient.MAX_SMALL_FILE) {
            ts = FindRule.parse(String(c.readSmallFile(path, entry.size), Charsets.UTF_8))
        }
        if (ts == null) ts = (entry.mtimeNs / 1_000_000_000L).takeIf { it > 0 }
        if (ts == null) { prefs.saveFindStatus("${now()} Request file found but not readable yet"); return }

        val whenText = DateFormat.getTimeInstance(DateFormat.SHORT).format(Date(ts * 1000))
        when (FindRule.decide(ts, System.currentTimeMillis() / 1000, prefs.findHandledTs, prefs.findIntervalSec)) {
            FindRule.Verdict.ALREADY_HANDLED -> {
                c.deleteFile(path)
                prefs.saveFindStatus("${now()} Removed a request that was already handled ($whenText)")
            }
            FindRule.Verdict.STALE -> {
                prefs.findHandledTs = ts
                notifyStale(whenText)
                try { c.deleteFile(path) } catch (e: Exception) { /* the next check removes it */ }
                prefs.saveFindStatus("${now()} Request from $whenText was too old, not ringing")
            }
            FindRule.Verdict.RING -> ring(c, prefs, dir, path, ts, whenText)
        }
    }

    private suspend fun ring(c: FtsClient, prefs: Prefs, dir: String, path: String, ts: Long, whenText: String) {
        prefs.findHandledTs = ts                      // before ringing: one request never rings twice
        prefs.saveFindStatus("${now()} Request from $whenText - ringing")
        stopRequested = false
        var goneOnWatch = false

        val alarm = Alarm(prefs)
        alarm.start()
        try {
            coroutineScope {
                // While it rings, look at the watch now and then: a cancel there (the file is deleted) ends the alarm.
                val watcher = launch {
                    while (isActive) {
                        delay(FindRule.CANCEL_POLL_SECONDS * 1000L)
                        try {
                            if (c.listDir(dir).none { it.name.equals(FindRule.FILE_NAME, ignoreCase = true) }) {
                                goneOnWatch = true
                                stopRequested = true
                            }
                        } catch (e: CancellationException) {
                            throw e
                        } catch (e: Exception) {
                            // link lost: keep ringing, the file is removed by a later check
                        }
                    }
                }
                val end = SystemClock.elapsedRealtime() + FindRule.ALARM_SECONDS * 1000L
                while (!stopRequested && SystemClock.elapsedRealtime() < end) delay(200)
                watcher.cancelAndJoin()
            }
        } finally {
            alarm.stop()
        }

        if (goneOnWatch) {
            prefs.saveFindStatus("${now()} Alarm ended: cancelled on the watch")
        } else {
            try {
                c.deleteFile(path)
                prefs.saveFindStatus("${now()} Rang for the request from $whenText, request deleted")
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                prefs.saveFindStatus("${now()} Rang for the request from $whenText; could not delete it (${e.message}), a later check will")
            }
        }
    }

    private suspend fun testAlarm() {
        val prefs = Prefs(this)
        stopRequested = false
        prefs.saveFindStatus("${now()} Test alarm")
        val alarm = Alarm(prefs)
        alarm.start()
        try {
            val end = SystemClock.elapsedRealtime() + FindRule.ALARM_SECONDS * 1000L
            while (!stopRequested && SystemClock.elapsedRealtime() < end) delay(200)
        } finally {
            alarm.stop()
        }
        prefs.saveFindStatus("${now()} Test alarm finished")
    }

    // ---- the alarm itself -----------------------------------------------------------------------

    /** Loud alarm-stream sound, vibration and a notification with a Stop button; undone again in [stop]. */
    private inner class Alarm(private val prefs: Prefs) {
        private var player: MediaPlayer? = null
        private var ringtone: android.media.Ringtone? = null
        private var tone: android.media.ToneGenerator? = null
        private var oldVolume = -1
        private var oldFilter = -1
        private var vibrator: Vibrator? = null

        fun start() {
            val audio = getSystemService(Context.AUDIO_SERVICE) as AudioManager
            oldVolume = audio.getStreamVolume(AudioManager.STREAM_ALARM)
            try {
                audio.setStreamVolume(AudioManager.STREAM_ALARM, audio.getStreamMaxVolume(AudioManager.STREAM_ALARM), 0)
            } catch (e: SecurityException) { /* a Do Not Disturb rule may refuse; the sound still plays at the current level */ }

            // Do Not Disturb: with the access granted, the filter is lifted for the length of the alarm.
            val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            if (prefs.findDnd && nm.isNotificationPolicyAccessGranted) {
                try {
                    oldFilter = nm.currentInterruptionFilter
                    nm.setInterruptionFilter(NotificationManager.INTERRUPTION_FILTER_ALL)
                } catch (e: SecurityException) { oldFilter = -1 }
            }

            val uri = RingtoneManager.getActualDefaultRingtoneUri(this@FindService, RingtoneManager.TYPE_ALARM)
                ?: RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM)
                ?: RingtoneManager.getDefaultUri(RingtoneManager.TYPE_RINGTONE)
            val attrs = AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_ALARM)
                .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                .build()
            try {
                player = MediaPlayer().apply {
                    setAudioAttributes(attrs)
                    setDataSource(this@FindService, uri)
                    isLooping = true
                    prepare()
                    start()
                }
            } catch (e: Exception) {
                player?.release()
                player = null
                // the ringtone could not be opened: try the system's own player, then a plain beep
                try {
                    ringtone = RingtoneManager.getRingtone(this@FindService, uri)?.apply {
                        audioAttributes = attrs
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) isLooping = true
                        play()
                    }
                } catch (e2: Exception) {
                    ringtone = null
                }
                if (ringtone?.isPlaying != true) {
                    try {
                        tone = android.media.ToneGenerator(AudioManager.STREAM_ALARM, 100).also {
                            it.startTone(android.media.ToneGenerator.TONE_CDMA_EMERGENCY_RINGBACK, FindRule.ALARM_SECONDS * 1000)
                        }
                    } catch (e3: Exception) {
                        tone = null
                    }
                }
            }

            @Suppress("DEPRECATION")
            vibrator = getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
            vibrator?.vibrate(VibrationEffect.createWaveform(longArrayOf(0, 700, 500), 0))

            nm.notify(ID_ALARM, alarmNotification())
        }

        fun stop() {
            try { player?.stop() } catch (_: Exception) {}
            player?.release()
            player = null
            try { ringtone?.stop() } catch (_: Exception) {}
            ringtone = null
            tone?.release()
            tone = null
            vibrator?.cancel()
            val audio = getSystemService(Context.AUDIO_SERVICE) as AudioManager
            if (oldVolume >= 0) try { audio.setStreamVolume(AudioManager.STREAM_ALARM, oldVolume, 0) } catch (_: SecurityException) {}
            val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            if (oldFilter >= 0) try { nm.setInterruptionFilter(oldFilter) } catch (_: SecurityException) {}
            nm.cancel(ID_ALARM)
        }
    }

    // ---- notifications --------------------------------------------------------------------------

    private fun ensureChannels() {
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        nm.createNotificationChannel(NotificationChannel(CH_WATCH, "Find my Phone: watching", NotificationManager.IMPORTANCE_MIN).apply {
            description = "Shown while the app checks the watch for a ring request."
            setShowBadge(false)
        })
        nm.createNotificationChannel(NotificationChannel(CH_ALARM, "Find my Phone: alarm", NotificationManager.IMPORTANCE_HIGH).apply {
            description = "The alarm when the watch asks the phone to ring. Switch on \"Override Do Not Disturb\" here to be sure."
            setSound(null, null)               // the sound is played by the service on the alarm stream
            enableVibration(false)
            setBypassDnd(true)
        })
        nm.createNotificationChannel(NotificationChannel(CH_INFO, "Find my Phone: old requests", NotificationManager.IMPORTANCE_DEFAULT).apply {
            description = "Tells you that the watch asked for a ring a while ago."
        })
    }

    private fun openAppIntent(): PendingIntent =
        PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)

    private fun startAsForeground() {
        ensureChannels()
        val sec = Prefs(this).findIntervalSec
        val text = if (sec > 0) "Checking the watch every ${FindRule.intervalLabel(sec).lowercase()}" else "Running a test"
        val n = NotificationCompat.Builder(this, CH_WATCH)
            .setSmallIcon(R.drawable.ic_stat_find)
            .setContentTitle("Find my Phone")
            .setContentText(text)
            .setOngoing(true)
            .setSilent(true)
            .setContentIntent(openAppIntent())
            .build()
        val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE else 0
        ServiceCompat.startForeground(this, ID_WATCH, n, type)
    }

    private fun alarmNotification(): Notification {
        val stop = PendingIntent.getService(
            this, 1, Intent(this, FindService::class.java).setAction(ACTION_STOP_ALARM),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        return NotificationCompat.Builder(this, CH_ALARM)
            .setSmallIcon(R.drawable.ic_stat_find)
            .setContentTitle("Your phone is ringing")
            .setContentText("Your watch asked it to ring.")
            .setCategory(NotificationCompat.CATEGORY_ALARM)
            .setPriority(NotificationCompat.PRIORITY_MAX)
            .setOngoing(true)
            .setContentIntent(openAppIntent())
            .addAction(0, "Stop", stop)
            .build()
    }

    private fun notifyStale(whenText: String) {
        val n = NotificationCompat.Builder(this, CH_INFO)
            .setSmallIcon(R.drawable.ic_stat_find)
            .setContentTitle("Your watch asked for a ring")
            .setContentText("At $whenText - too long ago to ring now.")
            .setAutoCancel(true)
            .setContentIntent(openAppIntent())
            .build()
        (getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager).notify(ID_INFO, n)
    }
}
