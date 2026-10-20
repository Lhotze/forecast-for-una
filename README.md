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

### Calendar (Agenda glance)

The **Calendar** tab feeds the **Agenda** glance. Allow calendar access, tick the calendars to show (they come from
Android's own calendar storage, so Google Calendar works without a separate sign-in), check the Agenda's app folder
(default `49F89FCBC7B9474B`), choose 10 / 20 / 30 minutes and tap **Send calendar now**, then switch on
**Update automatically**. The watch is the one chosen on the Weather tab. The calendar has its own schedule,
independent of the weather.

### Find my Phone

The **Find** tab rings the phone when the **Find my Phone** watch app (default app ID `B8E0979895A97AFD`)
asks for it. A watch app cannot send anything to the phone, so the phone looks: at the interval you choose (Off, 30 seconds, or 1, 2, 3, 4, 5 or 10 minutes) it opens a short Bluetooth
connection, lists the watch app's folder and, if `ring.txt` is there, reads it.

- A request is `ts=<UTC seconds>` and rings for **30 seconds** if it is at most 10 minutes plus one check interval old
  (so a 10-minute interval cannot miss a request). Older ones only produce a notification. Afterwards the file is deleted.
- **Cancel:** on the watch (it deletes the file; the phone checks every 3 seconds while ringing), with Stop in the
  notification, or the Stop alarm button.
- **Do Not Disturb:** the alarm plays on the alarm volume. Allow *Do Not Disturb access* for the app and the filter is
  lifted for the 30 seconds and restored afterwards.
- It runs as a foreground service with a silent notification while an interval is set, wakes the CPU only for a check
  (an alarm clock entry; allow *exact timing* for punctual checks), and restarts after a reboot. Android may stretch the
  interval while the phone lies still in Doze mode. Short intervals cost battery on the phone and on the watch.
- If the phone is out of range, the request waits on the watch and rings once the phone is back and has looked.

## Things to know

- Only **one Bluetooth client can write to the watch at a time.** If the official UNA app or another device is holding the
  connection, a run fails and is retried automatically (3 attempts per run, then the next interval).
- The app writes **exactly one file per glance**: `/Apps/<16-hex app ID>/weather.json` and
  `/Apps/<16-hex app ID>/calendar.json`. The Find tab additionally lists a watch app folder and reads and deletes
  `/Apps/<16-hex app ID>/ring.txt`. All paths are checked in code; the firmware
  update directory is never touched. Bluetooth file transfer itself has no access control beyond pairing.
- Android may delay scheduled runs in Doze/battery-saver mode, especially at 10 minutes. WorkManager's own minimum for
  periodic work is 15 minutes, so the app chains one-off jobs instead.
- The place name is looked up per ~1 km cell and cached. With Open-Meteo it comes from OpenStreetMap
  [Nominatim](https://operations.osmfoundation.org/policies/nominatim/); please respect its usage policy and set
  `user_agent` in `app/src/main/res/values/strings.xml` to your own repository URL when you fork.

## The calendar file

```json
{"v":1,"ts":1760000000,"tz":7200,"ev":[{"s":1760011800,"e":1760017200,"t":"Team meeting"},
 {"s":1760047200,"e":1760133600,"a":1,"t":"Holiday"}]}
```

`s`/`e` are unix seconds, `a:1` marks an all-day appointment (local midnights), `tz` is the phone's UTC offset in
seconds. Up to 12 appointments, < 2000 bytes, ASCII titles of at most 40 characters. The watch decides on its own
clock what is running, next or over.

## The data file

The phone writes a small JSON file (< 2000 bytes, schema v2) that the watch app parses:

```json
{"v":2,"ts":1758480000,"tz":7200,"hs":0,"loc":"Frankfurt","days":3,
 "tmax":[19.0,17.0,15.0],"tmin":[7.0,8.0,6.0],"code":[61,3,2],"pop":[60,20,10],"uv":[5.2,3.0,2.0],
 "t":[/*72 hourly °C, day-major*/],"r":[/*72 mm*/],"u":[/*72 UV index*/],"w":[/*72 wind km/h*/]}
```

`code` uses WMO weather codes. `days` is how many forecast days are present (up to 3); the watch works
out which one is "today" itself from `ts`/`tz` against its own clock and advances to the next entry at
local midnight even with no new file - so a watch with no phone connection for a day or more still
shows the right day, up to `days` days ahead, instead of getting stuck. `tz` is the location's UTC
offset in seconds. `hs` is the first hour of *today only* with real data (OpenWeatherMap cannot deliver
the past; the watch draws the temperature line only from there). The hourly arrays are day-major:
hours 0-23 of day 0, then day 1, then day 2. Wind (`w`) only gets a peak-per-day label on the watch, in
km/h or mph depending on a setting on the watch itself, not on anything in this file. With
OpenWeatherMap's paid One Call 3.0, day 2 has no real hourly rain/UV/wind (that API only reaches ~48h
ahead); the app fills its temperature with a smooth curve from that day's low/high instead of leaving
it empty. Open-Meteo, the default free source, has no such gap.

## Building

Requires JDK 17 and the Android SDK (API 34).

```bash
echo "sdk.dir=/path/to/Android/Sdk" > local.properties
./gradlew assembleDebug        # app/build/outputs/apk/debug/
```

## License

[MIT](LICENSE). Third-party components and data sources: see [NOTICE.md](NOTICE.md).
