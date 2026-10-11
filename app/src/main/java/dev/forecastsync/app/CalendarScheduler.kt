package dev.forecastsync.app

import android.content.Context
import androidx.work.Data
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import java.util.concurrent.TimeUnit

/**
 * The same hand-made chain as [Scheduler] (periodic work cannot go below 15 minutes), for the calendar and on its own rate.
 * The calendar is read from the phone, so unlike the weather this needs no network.
 */
object CalendarScheduler {
    private const val KEY_SLOT = "slot"
    private fun name(slot: Int) = "calendar_push_$slot"

    fun start(ctx: Context, minutes: Int) {
        cancel(ctx)
        enqueue(ctx, 0, minutes)
    }

    fun cancel(ctx: Context) {
        val wm = WorkManager.getInstance(ctx.applicationContext)
        wm.cancelUniqueWork(name(0))
        wm.cancelUniqueWork(name(1))
    }

    fun enqueue(ctx: Context, slot: Int, minutes: Int) {
        val req = OneTimeWorkRequestBuilder<CalendarWorker>()
            .setInitialDelay(minutes.toLong(), TimeUnit.MINUTES)
            .setInputData(Data.Builder().putInt(KEY_SLOT, slot).build())
            .build()
        WorkManager.getInstance(ctx.applicationContext)
            .enqueueUniqueWork(name(slot), ExistingWorkPolicy.REPLACE, req)
    }

    fun slotOf(data: Data) = data.getInt(KEY_SLOT, 0)
    fun other(slot: Int) = 1 - slot
}
