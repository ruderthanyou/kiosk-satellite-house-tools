# House Tools for Kiosk Satellite

Small helpers for tablets running [Kiosk Satellite](https://github.com/jxlarrea/kiosk-satellite).

## Screensaver

A screensaver for a lit room, shown under the name you choose (Home Screensaver by default). In a dark room (below 2 lux by default) the plugin steps aside and the tablet's own screensaver shows, such as Weather Mood.

- Family photos at random from a Home Assistant media folder, every photo once before any repeats. Bursts are left out of the list, so the same moment rarely shows twice.
- Tap the right side for the next photo and the left side for the previous one. Tap the middle to close. A double tap also closes it.
- Chips in the style of Weather Mood's buttons: faces (gray when away), **Inside** temperature and humidity for the room, the thermostat when it's on, the garage door, the alarm and any alert.
- This room's lights as buttons. Tap one to switch it. Give lights the Home Assistant label **Screensaver** to choose which ones show; without labels, the first working lights show (up to 6), and when a light group exists its numbered bulbs are left out.
- Its own corners: clock and date (top left), Outside weather with today's high and low (bottom right), and the dew point colored by comfort with the humidity under it (bottom left). Turn off Kiosk Satellite's corner widgets to avoid doubles.
- The rows wrap instead of cutting off. Kiosk Satellite's Now Playing card and pixel shift work on top of it.

Home Assistant's address comes from Kiosk Satellite, so there's nothing to set for it. Photos come from a list file in Home Assistant's `www` folder: `{"base": "/media/local/photos/", "photos": ["a.jpg", ...]}`. Each photo is downloaded through a signed Home Assistant link, so the photos themselves stay private.

The room's temperature and humidity come from a temperature (and humidity) sensor in the room's area, skipping batteries, chips and device internals, and preferring one with a matching humidity sensor. **Preferred room sensors** lets you name the ones to use first.

| Setting                                | Default                               | Meaning                                                                  |
| -------------------------------------- | ------------------------------------- | ------------------------------------------------------------------------ |
| Use this screensaver                   | Off                                   | Show it in a lit room                                                    |
| Screensaver name                       | Home Screensaver                      | Its name in Kiosk Satellite's screensaver list                           |
| Room (area ID)                         | empty                                 | Home Assistant area whose lights and climate show, such as `living_room` |
| Garage door                            | empty                                 | A cover entity for the garage chip                                       |
| Alarm                                  | empty                                 | An alarm panel entity for the alarm chip                                 |
| Preferred room sensors (optional)      | empty                                 | Words to look for first in room sensor IDs                               |
| Photo list                             | `/local/screensaver/photo_index.json` | Where the photo list file is                                             |
| Each photo for                         | 30 s                                  | How long each photo shows                                                |
| Photos in a row                        | 1                                     | 1 = every photo at random. More = that many from one random spot         |
| Dark room below                        | 2 lx                                  | Below this, the tablet's own screensaver shows                           |
| Home Assistant address (optional)      | empty                                 | Leave empty to use Kiosk Satellite's                                     |
| Use the sky screensaver in a dark room | Off                                   | See below                                                                |
| Soft gray text                         | Off                                   | Gray text and dimmer colors, for OLED screens                            |
| Weather                                | empty                                 | Weather entity for the sky screensaver                                   |
| Moon images (optional)                 | empty                                 | Another year's NASA image address                                        |

## Sky screensaver (dark room)

With **Use the sky screensaver in a dark room** on, a dark room shows an animated sky instead of the tablet's own screensaver: night, dusk, golden hour or day colors from the sun's height; the real stars above your horizon right now (about 1,000 brightest, sized by brightness and tinted by color), seen looking toward the equator; layered clouds, rain, snow and lightning from the current weather; the sun where it really is; and the real moon, from NASA's hourly images ([Moon Phase and Libration, 2026](https://svs.gsfc.nasa.gov/5587)), south-up below the equator. A big clock and date sit in the middle, with weather buttons along the bottom.

No location is stored in the plugin: the sun comes from Home Assistant's `sun.sun`, the weather from the chosen weather entity, and the latitude and longitude for the stars, the moon's position and the hemisphere from `zone.home`. The moon is drawn where it really is, and only while it's up.

Star data: the Yale Bright Star Catalog, 5th revised edition (Hoffleit & Warren, 1991), stars to magnitude 4.6, in `assets/stars.csv`.

## Alert banner

A bar at the top of the screen, over the dashboard and the screensaver, for the house's alerts (leak, garage open, laundry done, trash night...). It hides after 5 minutes or a tap, and comes back only for a new alert. Alerts come from an entity whose `items` attribute lists them as `{key, name, color}`; `color` is a color name such as Orange or Blue, and an alert with key `leak` is always red.

| Setting           | Default | Meaning                          |
| ----------------- | ------- | -------------------------------- |
| Show alert banner | On      | Turns the banner on or off       |
| Alerts            | empty   | The entity that lists the alerts |
| Hide banner after | 5 min   | How long it stays up             |

## Auto-close

When a window from another app stays on top of Kiosk Satellite, such as the Google Assistant pop-up after "Hey Google", the plugin brings Kiosk Satellite back to the front after a set time (2 minutes by default). That closes the window.

It only acts when Kiosk Satellite is still showing underneath and has lost focus. Full-screen apps you open on purpose are left alone. Kiosk Satellite's own return-home setting handles those. It also does nothing while the screen is off.

### Settings

| Setting                   | Default | Meaning                                            |
| ------------------------- | ------- | -------------------------------------------------- |
| Close windows left on top | On      | Turns auto-close on or off                         |
| Close after               | 120 s   | How long a window may stay on top (15 s to 30 min) |

## Touch tracking

Reports when the screen was last touched, and an **In use** flag that stays on for a few minutes (5 by default) after the last touch. Only finger-down events are counted; touches pass through to Kiosk Satellite unchanged.

On pages matching **Hold the screensaver on pages** the plugin holds Kiosk Satellite's screensaver timer (`holdScreensaver`) and lets go when you leave, so a long answer or voice typing in an add-on panel isn't covered by the screensaver.

| Setting                       | Default                                   | Meaning                                                                                                                                                     |
| ----------------------------- | ----------------------------------------- | ----------------------------------------------------------------------------------------------------------------------------------------------------------- |
| In use for                    | 5 min                                     | How long In use stays on after a touch                                                                                                                      |
| Hold the screensaver on pages | Home Assistant settings and add-on panels | A regular expression for page paths where the screensaver waits until you leave (reading, typing or dictating doesn't touch the screen). Empty turns it off |

## Voice skin

House Tools can draw Kiosk Satellite's voice overlay in its own look. Pick **House Tools** under **Voice Satellite > Appearance > Assistant 1 skin** (or **Assistant 2 skin**).

- **Full screen:** a deep navy backdrop with slowly drifting lights and faint twinkling stars. The house logo draws itself in: the roof from eave to peak to eave, then the D. While you speak the roof lifts and glows and rings ripple out from the house. While the assistant thinks, a comet circles the house and a band of light sweeps across the D. What you said shows in quotes, then the answer rises in.
- **Docked:** the same logo, small, in a navy card at the bottom of the screen with the words beside it.

It follows the Appearance settings Background (down to 90%, so the logo always stands out), Text size and Reactive activity bar. The palette always matches the logo. Touches pass through, so a double tap still dismisses the turn.

| Setting            | Default | Meaning                                            |
| ------------------ | ------- | -------------------------------------------------- |
| Name over the logo | empty   | Small capitals above the logo, such as a home name |

## Home Assistant entities (through Kiosk Satellite's ESPHome connection)

| Entity                                  | Meaning                                                                                        |
| --------------------------------------- | ---------------------------------------------------------------------------------------------- |
| Window on top (binary sensor)           | On while another app's window covers Kiosk Satellite                                           |
| Auto-closes (sensor)                    | How many times the plugin closed a window since it started                                     |
| Auto-close (switch)                     | Same as the setting                                                                            |
| Plugin message (text sensor)            | The plugin's last message, for troubleshooting                                                 |
| Last touched (text sensor)              | Time of the last touch, ISO 8601 UTC (use `as_datetime` in templates). Sent at most every 10 s |
| In use (binary sensor)                  | On from a touch until the In use time passes without one                                       |
| Bring Kiosk Satellite to front (button) | Optional; enable it on the plugin page                                                         |

## Install

In Kiosk Satellite: **Plugin Manager > Add plugin**, paste `https://github.com/ruderthanyou/kiosk-satellite-house-tools`, preview, install, then enable it.

## Build

See [docs/ARCHITECTURE.md](docs/ARCHITECTURE.md) for how the plugin works.

Pushes to `main` build a test ZIP as a GitHub Actions artifact and format the code with Prettier. Publishing a GitHub release (tag `v1.2.3`) builds and attaches the release files Kiosk Satellite installs from.

## License

MIT. The SDK under `sdk/` is Apache-2.0 from the Kiosk Satellite plugin template.
