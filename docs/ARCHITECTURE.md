# How House Tools works

House Tools is a Kiosk Satellite plugin: Java code that Kiosk Satellite loads into its own app (not a sandbox) and talks to through the plugin SDK in `sdk/`. This page explains how the pieces fit together.

## Files

| File                            | What it does                                                                                                             |
| ------------------------------- | ------------------------------------------------------------------------------------------------------------------------ |
| `kiosk-satellite-plugin.json`   | The manifest: name, version, capabilities, settings and commands                                                         |
| `src/.../HouseToolsPlugin.java` | Entry point. Settings, Home Assistant data, which screensaver shows, auto-close, touch tracking, Home Assistant entities |
| `src/.../Screensaver.java`      | The screensaver view: photo screen and night screen, taps, chips, corners                                                |
| `src/.../SkyView.java`          | The animated sky: colors, stars, moon, sun, clouds, rain, snow, lightning                                                |
| `src/.../AlertBanner.java`      | The alert bar at the top of the screen                                                                                   |
| `src/.../Net.java`              | HTTP downloads, image decoding and JSON helpers                                                                          |
| `assets/stars.csv`              | The bright-star catalog                                                                                                  |
| `assets/icon.png`               | The plugin icon                                                                                                          |
| `sdk/`                          | Kiosk Satellite's plugin SDK, compile-time only, never packaged                                                          |
| `tools/`                        | Build scripts from the Kiosk Satellite plugin template                                                                   |

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
NASA SVS ──hourly moon image──▶ SkyView (last good image saved in the app's cache)
```

Light buttons: the room's lights (tablet screens, switch LED bars and numbered group members left out); if any carry the **Light buttons label**, only those, otherwise the first working ones up to **Light buttons without a label**.

Chips (buttons and status pills) center their capitals and digits, not the whole text line, so the words sit level with the icon (`textShift`); the icon's bounds are offset by the same amount so it stays in the middle.

One `render_template` subscription carries everything the screens show. Home Assistant re-sends it whenever anything in it changes, so the plugin never polls entities. The template only reads entities named in the settings (and the room's area), so nothing about a particular house is built in.

## Room light

The tablet's light sensor sits next to the screen, so the screen's own glow raises the reading as the screen gets brighter. Fed straight into automatic brightness, that makes a loop that flickers between two levels. The plugin breaks it three ways:

- **Glow correction**: when the screen's brightness changes (Kiosk Satellite's `screen.brightness` event), the jump in the reading 4 seconds later is the screen's own light. The plugin learns that amount per unit of brightness (a slow average, ignoring samples where the room clearly changed too) and subtracts it.
- **Averaging** over about 45 seconds (the last reading is fed in again every 5 seconds, since the sensor only reports changes).
- **Hold**: the **Room light** entity changes at most once a minute, and only for a real change (at least 50% and 2 lux).
- **Lamp on or off**: a jump of at least 3 times and 8 lux that is still there 3 seconds later replaces the average and is published at once (at most once a minute), so the screensaver leaves the night sky as soon as the lights come on.

Kiosk Satellite's automatic brightness can follow that entity (Screen > Adaptive brightness > use a Home Assistant entity), and the screensaver's dark/lit choice uses the same value.

## Which screensaver shows

| Room                                  | Setting                                | What shows                                                  |
| ------------------------------------- | -------------------------------------- | ----------------------------------------------------------- |
| Lit (at or above **Dark room below**) | Use this screensaver                   | Photo screen                                                |
| Dark                                  | Use the sky screensaver in a dark room | Night screen (sky)                                          |
| Dark                                  | Sky screensaver off                    | The tablet's own screensaver (the plugin clears its choice) |

There's a little hysteresis (3 lux) so a reading on the line doesn't flip back and forth. The choice is a plugin override in Kiosk Satellite: it isn't saved, so the plugin sets it again after every start and every five minutes.

## Threads

- Kiosk Satellite calls the plugin's callbacks one at a time on a worker thread; each must return within three seconds, so they only record state and post work.
- Views and window state are only touched on the Android main thread.
- Downloads run on the screensaver's own background thread and post results back to the main thread.

## Sky math

- **Stars**: right ascension and declination from the catalog, converted to altitude and azimuth with local sidereal time from the current time and the home's longitude.
- **Moon photo**: NASA's image for the current hour, downloaded while the night screen shows. The last good one is kept in memory for every new screensaver and saved in the app's cache for restarts (at most every 6 hours); a failed download keeps it and retries after 10 minutes.
- **Moon**: a low-precision lunar position (mean longitude, anomaly and argument of latitude, with the main correction terms), good to about a degree, then the same conversion.
- **Sun**: Home Assistant's `sun.sun` azimuth and elevation.
- **Screen**: looking toward the equator, azimuth 90 degrees either side of it across the width, altitude 0 to 75 degrees from the bottom up. Below the equator the view turns to face north and NASA's south-up moon images are used.

## Build and release

- A push to `main` runs **Build**: it compiles against the SDK and uploads a test ZIP as an Actions artifact. A local test install uses Kiosk Satellite's developer "Install from ZIP".
- A push to `main` also runs **Format**: Prettier formats the Java, JSON and Markdown and commits any changes.
- Publishing a GitHub release tagged `vX.Y.Z` runs **Release**: it builds that version and attaches the three files Kiosk Satellite installs from. Kiosk Satellite only accepts release files uploaded by GitHub Actions.

## Tablet storage

The plugin keeps almost nothing on the tablet's flash storage, so it doesn't wear it out:

- Photos, face pictures, the photo list, the star catalog, house data and the forecast live in memory only.
- Home Assistant entities are sent over the network; Kiosk Satellite doesn't save plugin readings.
- The only file is the moon fallback image (about 100 KB), rewritten at most every 6 hours.
- The same log message is written at most once every 10 minutes, so a repeating error can't fill Kiosk Satellite's log.
- Settings are saved only when someone changes one (for example the Auto-close switch).

## Security notes

- **Templates**: the room, entity and sensor settings are filtered to plain IDs (`a-z`, `0-9`, `_`, `.`) before they go into the Home Assistant template, so a setting can't change what the template does.
- **Photo list**: the list file is in Home Assistant's public `www` folder, so the plugin accepts only a `/media/...` base and plain file names (no folders, no `..`) before asking Home Assistant to sign a link. Signed links last 5 minutes and are never logged: `authSig` values are hidden in every log line and in the Plugin message entity.
- **Downloads** have size limits (photo list 2 MB, photo 40 MB, moon image 2 MB, face picture 5 MB) and images are scaled down while decoding.
- **Text from Home Assistant** (alert names, people, lights) is shown as plain text, never as HTML.
- **Hold pages** reads only the current page path (`getDashboardState`, every 4 s) and holds or releases the screensaver timer; nothing is stored.
- **Touch tracking** notes only that a finger went down and when; it never sees key presses, text or touch positions.
- **The optional Home Assistant address setting** receives signed photo links, so only point it at your own Home Assistant.
- **Builds**: GitHub actions are pinned to exact commits; Prettier and its Java plugin are pinned versions. Kiosk Satellite only installs release files uploaded by GitHub Actions.
