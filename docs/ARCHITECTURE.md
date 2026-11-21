# How House Tools works

House Tools is a Kiosk Satellite plugin: Java code that Kiosk Satellite loads into its own app (not a sandbox) and talks to through the plugin SDK in `sdk/`. This page explains how the pieces fit together.

## Files

| File | What it does |
| --- | --- |
| `kiosk-satellite-plugin.json` | The manifest: name, version, capabilities, settings and commands |
| `src/.../HouseToolsPlugin.java` | Entry point. Settings, Home Assistant data, which screensaver shows, auto-close, touch tracking, Home Assistant entities |
| `src/.../Screensaver.java` | The screensaver view: photo screen and night screen, taps, chips, corners |
| `src/.../SkyView.java` | The animated sky: colors, stars, moon, sun, clouds, rain, snow, lightning |
| `src/.../AlertBanner.java` | The alert bar at the top of the screen |
| `src/.../Net.java` | HTTP downloads, image decoding and JSON helpers |
| `assets/stars.csv` | The bright-star catalog |
| `assets/icon.png` | The plugin icon |
| `sdk/` | Kiosk Satellite's plugin SDK, compile-time only, never packaged |
| `tools/` | Build scripts from the Kiosk Satellite plugin template |

## Data flow

```
Home Assistant ──render_template──▶ HouseToolsPlugin.houseData ──▶ Screensaver (chips, corners, sky)
      │                                     │
      │ sun.sun, zone.home, weather         └──▶ AlertBanner (alerts entity's items)
      │
      ├──auth/sign_path──▶ signed photo links ──▶ Screensaver downloads the photo
      └──weather.get_forecasts (every 30 min)──▶ today's high and low

Kiosk Satellite ──device.light──▶ dark or lit ──▶ setScreensaverMode / clearScreensaverMode
                ──getDashboardState──▶ Home Assistant's address (for downloads)
NASA SVS ──hourly moon image──▶ SkyView
```

One `render_template` subscription carries everything the screens show. Home Assistant re-sends it whenever anything in it changes, so the plugin never polls entities. The template only reads entities named in the settings (and the room's area), so nothing about a particular house is built in.

## Which screensaver shows

| Room | Setting | What shows |
| --- | --- | --- |
| Lit (at or above **Dark room below**) | Use this screensaver | Photo screen |
| Dark | Use the sky screensaver in a dark room | Night screen (sky) |
| Dark | Sky screensaver off | The tablet's own screensaver (the plugin clears its choice) |

There's a little hysteresis (3 lux) so a reading on the line doesn't flip back and forth. The choice is a plugin override in Kiosk Satellite: it isn't saved, so the plugin sets it again after every start and every five minutes.

## Threads

- Kiosk Satellite calls the plugin's callbacks one at a time on a worker thread; each must return within three seconds, so they only record state and post work.
- Views and window state are only touched on the Android main thread.
- Downloads run on the screensaver's own background thread and post results back to the main thread.

## Sky math

- **Stars**: right ascension and declination from the catalog, converted to altitude and azimuth with local sidereal time from the current time and the home's longitude.
- **Moon**: a low-precision lunar position (mean longitude, anomaly and argument of latitude, with the main correction terms), good to about a degree, then the same conversion.
- **Sun**: Home Assistant's `sun.sun` azimuth and elevation.
- **Screen**: looking toward the equator, azimuth 90 degrees either side of it across the width, altitude 0 to 75 degrees from the bottom up. Below the equator the view turns to face north and NASA's south-up moon images are used.

## Build and release

- A push to `main` runs **Build**: it compiles against the SDK and uploads a test ZIP as an Actions artifact. A local test install uses Kiosk Satellite's developer "Install from ZIP".
- A push to `main` also runs **Format**: Prettier formats the Java, JSON and Markdown and commits any changes.
- Publishing a GitHub release tagged `vX.Y.Z` runs **Release**: it builds that version and attaches the three files Kiosk Satellite installs from. Kiosk Satellite only accepts release files uploaded by GitHub Actions.
