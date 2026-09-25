# Forecast Sync for UNA

An Android app that fetches the weather for your current location and pushes it to the **Forecast** glance on a
[UNA Watch](https://www.unawatch.com) over Bluetooth, every 10, 20 or 30 minutes. The watch app only displays what the
phone has written; it does no networking itself.

> **Unofficial and unaffiliated.** "UNA" and "UNA Watch" are trademarks of UNA Watch Ltd. This is an independent
> project. It is pre-release software and comes **without any warranty** (see [LICENSE](LICENSE)).

## What you need

- A UNA Watch with **firmware 1.4.0 or newer** (the app verifies every write with the watch's CRC32 `DIGEST`).
- The **Forecast** watch app installed from the app store. Its folder on the watch is `/Apps/<app ID>/`; the
  default app ID in this app is `F729D18A6934974B` and can be changed in the settings.
- The watch **paired in Android's Bluetooth settings** first.
- Android 8.0 or newer.

## Using it

1. Choose the weather source: **Open-Meteo** (no account) or **OpenWeatherMap** (your own free API key from
   <https://openweathermap.org/api>; with the free key there is no UV data).
2. Pick your watch from the list, check the app folder, choose an interval and tap **Grant permissions**
   (location + Bluetooth; "allow all the time" lets the scheduled run read your position while the app is closed - without
   it the last known position is used). Optionally disable battery optimisation for reliable timing.
3. **Send now** for a first run, then switch on **Update automatically**.

The **Guide** tab explains every page and symbol on the watch.

## Things to know

- Only **one Bluetooth client can write to the watch at a time.** If the official UNA app or another device is holding the
  connection, a run fails and is retried automatically (3 attempts per run, then the next interval).
- The app writes **exactly one file**, `/Apps/<16-hex app ID>/weather.json`. The path is checked in code; the firmware
  update directory is never touched. Bluetooth file transfer itself has no access control beyond pairing.
- Android may delay scheduled runs in Doze/battery-saver mode, especially at 10 minutes. WorkManager's own minimum for
  periodic work is 15 minutes, so the app chains one-off jobs instead.
- The place name is looked up per ~1 km cell and cached. With Open-Meteo it comes from OpenStreetMap
  [Nominatim](https://operations.osmfoundation.org/policies/nominatim/); please respect its usage policy and set
  `user_agent` in `app/src/main/res/values/strings.xml` to your own repository URL when you fork.

## The data file

The phone writes a small JSON file (< 2000 bytes) that the watch app parses:

```json
{"v":1,"ts":1758480000,"tz":7200,"hs":0,"loc":"Frankfurt","tmax":19.0,"tmin":7.0,"code":61,"pop":60,"uv":5.2,
 "tmax2":17.0,"tmin2":8.0,"code2":3,"t":[/*24 hourly °C*/],"r":[/*24 mm*/],"u":[/*24 UV index*/]}
```

`code` uses WMO weather codes, `tz` is the location's UTC offset in seconds, `hs` the first hour of today with real data (OpenWeatherMap cannot deliver the past; the watch draws the temperature line only from there), arrays cover hours 0-23 of today.

## Building

Requires JDK 17 and the Android SDK (API 34).

```bash
echo "sdk.dir=/path/to/Android/Sdk" > local.properties
./gradlew assembleDebug        # app/build/outputs/apk/debug/
```

## License

[MIT](LICENSE). Third-party components and data sources: see [NOTICE.md](NOTICE.md).
