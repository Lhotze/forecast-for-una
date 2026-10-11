package dev.forecastsync.app

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters

class CalendarWorker(ctx: Context, params: WorkerParameters) : CoroutineWorker(ctx, params) {
    override suspend fun doWork(): Result {
        val prefs = Prefs(applicationContext)
        SyncEngine.runCalendar(applicationContext)
        // Queue the next run whatever happened; a failed run must not end the schedule.
        if (prefs.calAutoSync) {
            CalendarScheduler.enqueue(applicationContext, CalendarScheduler.other(CalendarScheduler.slotOf(inputData)), prefs.calIntervalMin)
        }
        return Result.success()
    }
}
