package dev.forecastsync.app

import android.content.Context
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import com.google.android.material.card.MaterialCardView

/** The "Guide" tab: what the watch shows and where the data comes from. Keep in sync with the Forecast watch app. */
object GuideContent {

    private class Row(val title: String, val text: String)

    /** Condition text on the watch -> what it stands for (WMO weather codes as used by Open-Meteo). */
    private val conditions = listOf(
        Row("Clear", "Cloudless sky (WMO 0)."),
        Row("Fair", "Mostly clear, a few clouds (WMO 1)."),
        Row("P. cloudy", "Partly cloudy (WMO 2)."),
        Row("Overcast", "Fully clouded sky (WMO 3)."),
        Row("Fog", "Fog or haze, also rime fog (WMO 45, 48)."),
        Row("Drizzle", "Light, fine rain (WMO 51, 53, 55)."),
        Row("Frz. rain", "Freezing rain or freezing drizzle - slippery roads (WMO 56, 57, 66, 67)."),
        Row("Rain", "Continuous rain, light to heavy (WMO 61, 63, 65)."),
        Row("Snow", "Snowfall or snow grains (WMO 71, 73, 75, 77, 85, 86)."),
        Row("Showers", "Rain showers, short and intense (WMO 80, 81, 82)."),
        Row("Thunder", "Thunderstorm, possibly with hail (WMO 95, 96, 99)."),
        Row("Weather", "Unknown condition code - the phone sent a value the watch does not know."),
    )

    private val pages = listOf(
        Row("Page 1 - now", "Place name (with the age of the data, e.g. \"Berlin 3h\", once it is older than 90 minutes), and below it e.g. \"16C 0.4mm UV3\": the temperature right now, the precipitation of the current hour in millimetres, and the UV index of the current hour. Longer values automatically get a smaller font."),
        Row("Page 2 - graph", "Today's hourly graph, explained in the next section."),
        Row("Page 3 - today", "Today's weather condition, and below it e.g. \"9/17C 60%\": the day's low/high and the chance of rain today."),
    )

    private val graph = listOf(
        Row("Orange line and numbers", "Hourly temperature of today; the numbers mark today's lowest and highest value."),
        Row("Blue bars", "Precipitation per hour. Full height = 5 mm per hour or more."),
        Row("Purple bars", "UV index per hour (next to the blue bars). Full height = UV 11. The purple number is today's peak."),
        Row("Green stripe", "The current hour. It covers hh:00 to hh:59 of the hour you are in."),
        Row("Thin vertical lines and 0 / 6 / 12 / 18 / 24", "One line per hour; the brighter ones and the numbers give the rough time of day (local time at the forecast location)."),
    )

    private val about = listOf(
        Row("Weather data", "Open-Meteo.com (CC BY 4.0, https://open-meteo.com) or OpenWeatherMap (https://openweathermap.org) - whichever source you pick under Settings."),
        Row("Place names", "Geocoding by OpenStreetMap Nominatim (data (c) OpenStreetMap contributors, ODbL) or OpenWeatherMap."),
        Row("Not affiliated", "\"UNA\" and \"UNA Watch\" are trademarks of UNA Watch Ltd. This app is an independent project and is not affiliated with or endorsed by UNA Watch Ltd."),
        Row("Temperature unit", "Celsius or Fahrenheit is a setting of the Forecast watch app: change it in the UNA phone app under the app's settings (\"Use Fahrenheit\"). This app always sends Celsius."),
        Row("What it writes", "Only the single file /Apps/<app ID>/weather.json in the folder of the Forecast watch app - nothing else on the watch."),
    )

    fun build(ctx: Context, into: LinearLayout) {
        into.removeAllViews()
        section(ctx, into, "Pages on the watch", pages)
        section(ctx, into, "Graph legend", graph)
        section(ctx, into, "Weather conditions", conditions)
        section(ctx, into, "About", about)
    }

    private fun section(ctx: Context, into: LinearLayout, heading: String, rows: List<Row>) {
        val d = ctx.resources.displayMetrics.density
        val pad = (16 * d).toInt()

        val card = MaterialCardView(ctx).apply {
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
                .apply { bottomMargin = (12 * d).toInt() }
        }
        val col = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL; setPadding(pad, pad, pad, pad) }
        card.addView(col)

        col.addView(TextView(ctx).apply {
            text = heading
            setTextAppearance(com.google.android.material.R.style.TextAppearance_Material3_TitleMedium)
        })
        for (r in rows) {
            col.addView(TextView(ctx).apply {
                text = r.title
                setTextAppearance(com.google.android.material.R.style.TextAppearance_Material3_LabelLarge)
                setPadding(0, (12 * d).toInt(), 0, 0)
            })
            col.addView(TextView(ctx).apply {
                text = r.text
                setTextAppearance(com.google.android.material.R.style.TextAppearance_Material3_BodyMedium)
            })
        }
        into.addView(card)
    }
}
