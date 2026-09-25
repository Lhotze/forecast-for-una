package dev.forecastsync.app

import android.app.Application
import com.google.android.material.color.DynamicColors

class App : Application() {
    override fun onCreate() {
        super.onCreate()
        WeatherClient.userAgent = getString(R.string.user_agent)
        DynamicColors.applyToActivitiesIfAvailable(this)
    }
}
