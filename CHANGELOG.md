# Forecast Sync for UNA — Changelog

## 0.3.0
- Added wind speed to the fetched forecast and the file sent to the watch,
  for the new teal wind graph in the watch app (needs Forecast 1.0.9+).
- Forecast now covers **3 days** instead of 1, day-major in the hourly arrays,
  so the watch can advance on its own overnight with no sync (**schema v2** —
  requires Forecast 1.0.9+; older watch app versions won't understand it and
  will show "No data").
- Open-Meteo request extended accordingly (`past_days=1&forecast_days=3`,
  `wind_speed_10m`, `windspeed_unit=kmh`).
- OpenWeatherMap: One Call 3.0's hourly data only reaches ~48h ahead, so the
  third day's temperature is now a smooth curve from that day's low/high
  instead of missing; rain/UV/wind stay at 0 for that day. The free 2.5
  forecast (5 days of 3-hourly data) covers all 3 days with real data,
  including wind.
- The Fahrenheit setting that briefly lived in this app moved to the watch
  app itself in Forecast 1.0.7 (Settings on the watch, in the UNA app) — this
  app always sends Celsius / km/h, never converts.

## 0.2.2
- Guide tab rewritten to match the watch app's current 3-page layout (Now /
  Graph / Today) and the temperature-unit setting living on the watch.

## 0.2.1
- Matches Forecast 1.0.5 on the watch: sends `hs`, the first hour of today
  with real data, so the watch's temperature graph starts there instead of
  drawing an invented flat line for hours OpenWeatherMap can't provide.

## 0.2.0 — first public release
- Fetches the forecast for the current location (Open-Meteo, no account
  needed, or your own OpenWeatherMap API key) and writes it to the Forecast
  glance on a paired UNA Watch over Bluetooth (BLE File Transfer Service),
  verified with the watch's CRC32 `DIGEST`, retried up to 3 times.
- Update interval: 10, 20 or 30 minutes, chained as one-off background jobs
  (WorkManager's periodic work can't go below 15 min).
- Place name via reverse geocoding (OpenStreetMap Nominatim, or OpenWeatherMap
  when its key is set), cached per ~1 km cell.
- Guide tab explaining every page and symbol on the watch.
- Published under the MIT license as an independent, unofficial project
  (see [NOTICE.md](NOTICE.md) for data sources and trademarks).

---
*Earlier development (0.0.1–0.1.4, under the working names "UNA Weather
Push" / "UNA Weather") predates this repository and is not itemised here.*
