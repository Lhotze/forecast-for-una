package dev.forecastsync.app

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/** Starts the Find my Phone watching again after a reboot or an app update, if an interval is set. */
class FindBootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == Intent.ACTION_BOOT_COMPLETED || intent.action == Intent.ACTION_MY_PACKAGE_REPLACED) {
            try {
                FindService.apply(context)
            } catch (e: Exception) {
                Prefs(context).saveFindStatus("Could not restart after boot: ${e.message ?: e.javaClass.simpleName}")
            }
        }
    }
}
