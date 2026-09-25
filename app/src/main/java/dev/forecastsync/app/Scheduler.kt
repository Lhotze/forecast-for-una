package dev.forecastsync.app

import android.content.Context
import androidx.work.Constraints
import androidx.work.Data
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.ExistingWorkPolicy
import java.util.concurrent.TimeUnit

/**
 * WorkManager's periodic work cannot go below 15 minutes, so the chain is done by hand:
 * every run queues the next one [Prefs.intervalMin] minutes later. Two alternating
 * names keep a running worker from cancelling the one it just queued.
 */
object Scheduler {
    private const val KEY_SLOT = "slot"
    private fun name(slot: Int) = "weather_push_$slot"

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
        val req = OneTimeWorkRequestBuilder<SyncWorker>()
            .setInitialDelay(minutes.toLong(), TimeUnit.MINUTES)
            .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
            .setInputData(Data.Builder().putInt(KEY_SLOT, slot).build())
            .build()
        WorkManager.getInstance(ctx.applicationContext)
            .enqueueUniqueWork(name(slot), ExistingWorkPolicy.REPLACE, req)
    }

    fun slotOf(data: Data) = data.getInt(KEY_SLOT, 0)
    fun other(slot: Int) = 1 - slot
}
