package dev.forecastsync.app

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters

class SyncWorker(ctx: Context, params: WorkerParameters) : CoroutineWorker(ctx, params) {
    override suspend fun doWork(): Result {
        val prefs = Prefs(applicationContext)
        SyncEngine.run(applicationContext)
        // Queue the next run whatever happened; a failed run must not end the schedule.
        if (prefs.autoSync) {
            Scheduler.enqueue(applicationContext, Scheduler.other(Scheduler.slotOf(inputData)), prefs.intervalMin)
        }
        return Result.success()
    }
}
