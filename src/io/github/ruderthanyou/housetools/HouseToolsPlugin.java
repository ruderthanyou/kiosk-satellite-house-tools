// SPDX-License-Identifier: MIT
package io.github.ruderthanyou.housetools;

import android.app.Activity;
import android.app.Application;
import android.content.Context;
import android.content.Intent;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.PowerManager;
import android.os.SystemClock;
import android.view.MotionEvent;
import android.view.View;
import android.view.Window;
import java.lang.ref.WeakReference;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.text.SimpleDateFormat;
import java.util.Collections;
import java.util.Date;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TimeZone;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import me.jxl.kiosk.plugins.KioskPlugin;
import me.jxl.kiosk.plugins.PluginHost;

/**
 * House Tools for Kiosk Satellite: the plugin's entry point.
 *
 * <p>Features, each described in the README:
 * <ul>
 *   <li><b>Screensaver</b> ({@link Screensaver}): photos in a lit room, an animated sky ({@link SkyView}) in a dark
 *       one. This class decides which one Kiosk Satellite shows ({@link #applyMode}) from the light sensor.</li>
 *   <li><b>Alert banner</b> ({@link AlertBanner}): a bar at the top for the alerts entity's items.</li>
 *   <li><b>Auto-close</b>: Kiosk Satellite's window is still on screen but has lost input focus to another app's
 *       window (for example the Google Assistant pop-up) for the set time, so bring Kiosk Satellite back to the
 *       front, which closes that window. Full-screen apps stop the activity instead and are left alone.</li>
 *   <li><b>Touch tracking</b>: the activity's Window.Callback is wrapped in a pass-through proxy that notes each
 *       finger-down, for the Last touched and In use entities. The original callback is put back on stop.</li>
 * </ul>
 *
 * <p>Home Assistant data: one live {@code render_template} subscription ({@link #subscribeHouse}) returns a JSON
 * snapshot of everything the screensaver and banner show (people, room climate and lights, garage, alarm, alerts,
 * weather, sun, home location). Nothing about a particular house is built in: entities, the room and the photo
 * list are settings, and Home Assistant's address comes from Kiosk Satellite.
 *
 * <p>Threads: Kiosk Satellite calls {@code start}, {@code configure}, {@code execute}, {@code onEvent} and
 * {@code stop} one at a time on a plugin worker thread, and each must return within three seconds, so they only
 * record state and post work. Window state and views are touched only on the Android main thread ({@link #main}).
 * Downloads run on the screensaver's own background thread.
 *
 * <p>Restarts: the plugin can be stopped and started at any time (updates, app restarts, Home Assistant restarts).
 * On start it reads the current light level, keeps retrying its screensaver choice with growing gaps, and every
 * five minutes re-applies that choice and reconnects the Home Assistant data if it went quiet
 * ({@link #healthCheck}).
 */
public final class HouseToolsPlugin implements KioskPlugin {

    private static final long POLL_MS = 5_000;
    private static final int MAX_TRIES_PER_COVER = 3;
    private static final long TOUCH_PUBLISH_MIN_MS = 10_000;

    private final Handler main = new Handler(Looper.getMainLooper());
    private volatile boolean alive;
    private volatile PluginHost host;

    // Settings, written by the plugin worker and read on the main thread.
    private volatile boolean autoClose = true;
    private volatile long autoCloseMs = 120_000;
    private volatile long inUseMs = 5 * 60_000;
    private volatile String area = "";
    private volatile String haUrlSetting = ""; // optional override
    private volatile String ksHaUrl = ""; // Kiosk Satellite's own Home Assistant address
    private volatile String screensaverName = "Home Screensaver";
    private volatile String garageEntity = "";
    private volatile String alarmEntity = "";
    private volatile String alertsEntity = "";
    private volatile String photoIndexPath = "/local/screensaver/photo_index.json";
    private volatile boolean nightScreensaver;
    private volatile boolean softText;
    private volatile String lightsLabel = "Screensaver"; // Home Assistant label that picks the light buttons
    private volatile int maxLights = 6;
    private volatile String preferSensors = ""; // comma list of words to look for first in room sensor IDs
    private volatile String weatherEntity = "";
    private static final String MOON_NORTH =
        "https://svs.gsfc.nasa.gov/vis/a000000/a005500/a005587/frames/730x730_1x1_30p/moon.{frame}.jpg";
    private static final String MOON_SOUTH =
        "https://svs.gsfc.nasa.gov/vis/a000000/a005500/a005588/frames/730x730_1x1_30p/moon.{frame}.jpg";
    private volatile String moonFrames = ""; // empty = NASA's 2026 images, north-up or south-up by Home Assistant's home latitude
    private volatile Double forecastHigh, forecastLow;
    private long forecastAt;
    private String publishedName;
    private volatile int photoSeconds = 30;
    private volatile int photoBlock = 1;
    private volatile double nightLux = 2;
    private volatile boolean useScreensaver;
    private Boolean modeApplied; // what setScreensaverMode last did: true = ours, false = cleared
    private volatile boolean alertBannerOn = true;
    private volatile int alertMinutes = 5;
    // Pages where the screensaver waits (settings and add-on screens, where people read and type or dictate).
    static final String DEFAULT_HOLD_PAGES =
        "^/(config|developer-tools|hassio|profile)(/|$)|^/[0-9a-f]{8}_[a-z0-9_]+(/|$)";
    private volatile java.util.regex.Pattern holdPages = java.util.regex.Pattern.compile(DEFAULT_HOLD_PAGES);
    private volatile boolean pageHeld; // our holdScreensaver hold is on
    private long pageCheckedAt;

    // Screensaver and alert banner.
    private final Screensaver screensaver = new Screensaver(this);
    private final AlertBanner banner = new AlertBanner(this);
    private final VoiceSkin voiceSkin = new VoiceSkin(this);
    private volatile String voiceTitle = "";
    private volatile Map<String, Object> houseData = Screensaver.emptyData();
    private volatile boolean dark;
    private String subscribedArea;
    private volatile String houseKey = "house0";
    private volatile long lastHouseData;
    private long lastHealthCheck;
    private int houseSubscriptions;

    // Touch tracking, written from the main thread.
    private volatile long lastTouchElapsed = -1;
    private long lastTouchPublished = -2; // -2 = nothing sent yet, so the first poll sends "unknown"
    private Boolean publishedInUse;
    private WeakReference<Window> spiedWindow = new WeakReference<>(null);
    private Window.Callback spiedOriginal;

    // Main-thread state.
    private Application app;
    private WeakReference<Activity> activity = new WeakReference<>(null);
    private Application.ActivityLifecycleCallbacks callbacks;
    private long coveredSince = -1;
    private int triesThisCover;
    private Boolean publishedCovered;
    private int autoCloses;

    private final Runnable poll = new Runnable() {
        @Override
        public void run() {
            if (!alive) return;
            try {
                check();
            } catch (Throwable error) {
                log("Check failed: " + error);
            }
            if (alive) main.postDelayed(this, POLL_MS);
        }
    };

    private long startedAt;

    @Override
    public void start(PluginHost host, Map<String, Object> settings) {
        this.host = host;
        startedAt = System.currentTimeMillis();
        alive = true;
        apply(settings);
        publishSwitch();
        try {
            host.publishSensor("auto_closes", "Auto-closes", counterMeta(), 0.0);
        } catch (Throwable ignored) {}
        main.post(this::attach);
        publishScreensaver();
        try {
            host.subscribe("device.light");
        } catch (Throwable error) {
            log("Light sensor: " + error);
        }
        try {
            host.subscribe("screen.brightness");
            host.executeCommand(
                "getBrightness",
                Collections.singletonMap("panel", (Object) true),
                (ok, data, error) -> {
                    if (ok && data instanceof Number) panel = ((Number) data).doubleValue();
                }
            );
        } catch (Throwable error) {
            log("Screen brightness: " + error);
        }
        // The event only fires on a change, so read the current level once: a room that stays dark
        // would otherwise never tell us.
        try {
            host.executeCommand("getLightLevel", Collections.emptyMap(), (ok, data, error) -> {
                if (ok && data instanceof Map) lightReading(((Map<?, ?>) data).get("lux"));
            });
        } catch (Throwable error) {
            log("Light level: " + error);
        }
        try {
            host.subscribe("browser.state");
        } catch (Throwable error) {
            log("Browser state: " + error);
        }
        readHaUrl();
        subscribeHouse();
        applyMode();
        voiceSkin.start(host);
    }

    @Override
    public void configure(Map<String, Object> settings) {
        apply(settings);
        publishSwitch();
        if (!houseSubscriptionKey().equals(subscribedArea)) subscribeHouse();
        if (!screensaverName.equals(publishedName)) {
            publishScreensaver();
            modeApplied = null;
        }
        applyMode();
    }

    private void publishScreensaver() {
        PluginHost h = host;
        if (h == null) return;
        String name = screensaverName;
        try {
            try {
                h.publishScreensaverView(Screensaver.KEY, name, screensaver, true);
            } catch (UnsupportedOperationException | LinkageError olderHost) {
                h.publishScreensaverView(Screensaver.KEY, name, screensaver);
            }
            publishedName = name;
        } catch (Throwable error) {
            log("Screensaver unavailable: " + error);
        }
    }

    /** Kiosk Satellite's Home Assistant address, so no address has to be configured here. */
    private void readHaUrl() {
        PluginHost h = host();
        if (h == null) return;
        try {
            h.executeCommand("getDashboardState", Collections.emptyMap(), (ok, data, error) -> {
                if (ok && data instanceof Map) {
                    String url = Net.str(((Map<?, ?>) data).get("homeAssistantUrl")).trim();
                    if (!url.isEmpty()) ksHaUrl = trimUrl(url);
                }
            });
        } catch (Throwable error) {
            log("Home Assistant address: " + error);
        }
    }

    /** scheme://host:port only. */
    private static String trimUrl(String url) {
        String u = url.trim();
        int scheme = u.indexOf("//");
        int path = scheme >= 0 ? u.indexOf('/', scheme + 2) : -1;
        if (path > 0) u = u.substring(0, path);
        return u.replaceAll("/+$", "");
    }

    /** What the live template depends on; a change re-subscribes. */
    private String houseSubscriptionKey() {
        return (
            lightsLabel +
            "|" +
            maxLights +
            "|" +
            area +
            "|" +
            garageEntity +
            "|" +
            alarmEntity +
            "|" +
            alertsEntity +
            "|" +
            weatherEntity +
            "|" +
            preferSensors
        );
    }

    /**
     * Lit room: show our screensaver in place of whatever mode Kiosk Satellite or the tablet automation chose.
     * Dark room, or the setting off: clear our choice so the tablet's own (Weather Mood at night) shows.
     */
    private volatile String modeId = "plugin:house-tools:" + Screensaver.KEY;
    private int modeRetries;
    private boolean modeLogged;

    /** Look the mode up in getScreensaverMode's list (the ID format is Kiosk Satellite's) and try again shortly. */
    private void findModeAndRetry(String error) {
        PluginHost h = host();
        if (h == null) return;
        try {
            h.executeCommand("getScreensaverMode", Collections.emptyMap(), (ok, data, err) -> {
                StringBuilder seen = new StringBuilder();
                if (ok && data instanceof Map) {
                    for (Object m : Net.list(((Map<?, ?>) data).get("modes"))) {
                        Map<String, Object> mode = Net.map(m);
                        String id = Net.str(mode.get("id"));
                        String label = Net.str(mode.get("label"));
                        if (id.startsWith("plugin")) seen.append(id).append(" ");
                        if (id.contains("house-tools") || label.startsWith(screensaverName)) modeId = id;
                    }
                }
                log("Screensaver mode: " + error + ". Plugin modes: " + seen + "-> using " + modeId);
                long wait = Math.min(300_000L, 5_000L << Math.min(6, modeRetries++)); // 5 s, 10 s, 20 s ... up to 5 min
                main.postDelayed(
                    () -> {
                        if (alive) applyMode();
                    },
                    wait
                );
            });
        } catch (Throwable e) {
            log("Screensaver mode lookup: " + e);
        }
    }

    private void applyMode() {
        PluginHost h = host();
        if (h == null) return;
        boolean want = useScreensaver && (!dark || nightScreensaver);
        if (modeApplied != null && modeApplied == want) return;
        modeApplied = want;
        try {
            if (want) {
                h.executeCommand("setScreensaverMode", Collections.singletonMap("mode", modeId), (ok, data, error) -> {
                    if (ok) {
                        boolean wasRetrying = modeRetries > 0 || !modeLogged;
                        modeRetries = 0;
                        modeLogged = true;
                        if (wasRetrying) log("Screensaver selected (" + modeId + ")");
                        return;
                    }
                    modeApplied = null;
                    findModeAndRetry(error);
                });
            } else {
                h.executeCommand("clearScreensaverMode", Collections.emptyMap(), (ok, data, error) -> {
                    if (!ok) {
                        log("Screensaver mode: " + error);
                        modeApplied = null;
                    }
                });
            }
        } catch (Throwable error) {
            log("Screensaver mode: " + error);
            modeApplied = null;
        }
    }

    @Override
    public void execute(String command, Map<String, Object> arguments) {
        if ("bringToFront".equals(command)) {
            main.post(() -> {
                if (alive) bringToFront("command");
            });
            return;
        }
        throw new IllegalArgumentException("Unknown command " + command);
    }

    @Override
    public void onEvent(String event, Map<String, Object> payload) {
        if (voiceSkin.onEvent(event, payload)) return;
        if ("ks.screen.brightness".equals(event)) {
            Object p = payload.get("panel");
            if (p instanceof Number) learnGlow(((Number) p).doubleValue());
            return;
        }
        if ("ks.device.light".equals(event)) {
            lightReading(payload.get("lux"));
            return;
        }
        if ("ks.browser.state".equals(event)) {
            readHaUrl();
            return;
        }
        if (("ks.ha.subscription." + houseKey).equals(event)) {
            if ("ended".equals(payload.get("type"))) {
                log("House data ended (" + payload.get("error") + "); subscribing again");
                main.postDelayed(
                    () -> {
                        if (alive) {
                            subscribedArea = null;
                            subscribeHouse();
                        }
                    },
                    10_000
                );
                return;
            }
            if ("event".equals(payload.get("type"))) {
                lastHouseData = System.currentTimeMillis();
                Object ev = payload.get("event");
                Object result = ev instanceof Map ? ((Map<?, ?>) ev).get("result") : null;
                if (result != null) {
                    try {
                        houseData = Net.map(Net.plain(result));
                        screensaver.dataChanged();
                        final List<Object> alerts = Net.list(houseData.get("alerts"));
                        final boolean on = alertBannerOn;
                        main.post(() -> {
                            if (alive) banner.update(alerts, on);
                        });
                    } catch (Throwable error) {
                        log("House data: " + error);
                    }
                }
            }
            return;
        }
        if ("switch.auto_close".equals(event)) {
            boolean on = Boolean.TRUE.equals(payload.get("on"));
            autoClose = on;
            Map<String, Object> values = Collections.singletonMap("autoClose", (Object) on);
            try {
                host.updateSettings(values);
            } catch (Throwable notSupported) {
                try {
                    host.saveSettings(values);
                } catch (Throwable ignored) {}
            }
            publishSwitch();
            main.post(() -> {
                coveredSince = -1;
                triesThisCover = 0;
            });
        }
    }

    @Override
    public void stop() {
        alive = false;
        setPageHold(false); // never leave the screensaver held after we're gone
        voiceSkin.stop();
        main.removeCallbacksAndMessages(null);
        Application application = app;
        Application.ActivityLifecycleCallbacks registered = callbacks;
        if (application != null && registered != null) {
            try {
                application.unregisterActivityLifecycleCallbacks(registered);
            } catch (Throwable ignored) {}
        }
        callbacks = null;
        unspy();
        screensaver.shutdown();
    }

    // ----- Settings -----

    private void apply(Map<String, Object> settings) {
        Object enabled = settings.get("autoClose");
        if (enabled instanceof Boolean) autoClose = (Boolean) enabled;
        Object seconds = settings.get("autoCloseSeconds");
        if (seconds instanceof Number) {
            double value = ((Number) seconds).doubleValue();
            autoCloseMs = (long) (Math.max(15, Math.min(1800, value)) * 1000);
        }
        Object a = settings.get("area");
        if (a instanceof String) area = ((String) a).trim();
        Object url = settings.get("haUrl");
        if (url instanceof String) haUrlSetting = ((String) url).trim().isEmpty() ? "" : trimUrl((String) url);
        screensaverName = text(settings.get("screensaverName"), "Home Screensaver");
        garageEntity = entity(settings.get("garageEntity"));
        alarmEntity = entity(settings.get("alarmEntity"));
        alertsEntity = entity(settings.get("alertsEntity"));
        String index = text(settings.get("photoIndexPath"), "/local/screensaver/photo_index.json");
        photoIndexPath = index.startsWith("/") ? index : "/" + index;
        Object soft = settings.get("softText");
        if (soft instanceof Boolean) softText = (Boolean) soft;
        Object night = settings.get("nightScreensaver");
        if (night instanceof Boolean) nightScreensaver = (Boolean) night;
        weatherEntity = entity(settings.get("weatherEntity"));
        lightsLabel = text(settings.get("lightsLabel"), "Screensaver").replaceAll("[^A-Za-z0-9 _-]", "");
        maxLights = (int) clamp(settings.get("maxLights"), 0, 20, maxLights);
        preferSensors = text(settings.get("preferSensors"), "")
            .toLowerCase(java.util.Locale.US)
            .replaceAll("[^a-z0-9_,]", "");
        moonFrames = text(settings.get("moonFrames"), "");
        photoSeconds = (int) clamp(settings.get("photoSeconds"), 10, 600, photoSeconds);
        photoBlock = (int) clamp(settings.get("photoBlock"), 1, 50, photoBlock);
        nightLux = clamp(settings.get("nightLux"), 0, 50, nightLux);
        Object use = settings.get("useScreensaver");
        if (use instanceof Boolean) useScreensaver = (Boolean) use;
        Object banner = settings.get("alertBanner");
        if (banner instanceof Boolean) alertBannerOn = (Boolean) banner;
        alertMinutes = (int) clamp(settings.get("alertMinutes"), 1, 60, alertMinutes);
        voiceTitle = text(settings.get("voiceTitle"), "");
        String hold = text(settings.get("holdPages"), DEFAULT_HOLD_PAGES);
        try {
            holdPages = hold.isEmpty() ? null : java.util.regex.Pattern.compile(hold);
        } catch (RuntimeException bad) {
            log("Hold pages: not a valid pattern, using the default");
            holdPages = java.util.regex.Pattern.compile(DEFAULT_HOLD_PAGES);
        }
        Object minutes = settings.get("inUseMinutes");
        if (minutes instanceof Number) {
            double value = ((Number) minutes).doubleValue();
            inUseMs = (long) (Math.max(1, Math.min(60, value)) * 60_000);
        }
    }

    // ----- Room light: a steady light level, for the screensaver and for Kiosk Satellite's brightness -----

    private double lightAverage = Double.NaN; // smoothed, glow-corrected reading
    private long lightAverageAt;
    private Double lightPublished; // last value sent as the Room light entity
    private long lightPublishedAt;
    private long jumpSince; // when the reading first jumped far from the average (0 = it hasn't)
    private long lastSnapAt; // when a jump was last taken at once
    private volatile Double lightRaw; // latest raw reading, fed in again every 5 s so the average keeps moving
    private volatile double panel = Double.NaN; // current screen brightness, 0..1
    private volatile double glow = 0; // learned: lux the sensor gains per unit of screen brightness

    /**
     * Raw readings from the tablet's light sensor. The sensor sits next to the screen, so the screen's own glow
     * raises the reading as the screen gets brighter; fed straight into automatic brightness that makes a loop
     * that flickers. Three things break it:
     * <ol>
     *   <li>Glow correction: when the screen's brightness changes, the jump in the reading right after is the
     *       screen's own light ({@link #learnGlow}); that amount per unit of brightness is subtracted.</li>
     *   <li>Averaging over about 45 seconds.</li>
     *   <li>The Room light entity changes at most once a minute, and only for a real change (at least 50% and
     *       2 lux), except at the very first reading.</li>
     * </ol>
     * A lamp switched on or off is the exception: a jump of at least 3 times and 8 lux that is still there 3
     * seconds later replaces the average and is published at once (at most once a minute), so the screensaver
     * goes from the night sky to photos as soon as the light comes on.
     * Kiosk Satellite's automatic brightness follows that entity, and the screensaver's dark/lit choice uses it.
     */
    private synchronized void lightReading(Object lux) {
        if (!(lux instanceof Number)) return;
        double raw = Math.max(0, ((Number) lux).doubleValue());
        lightRaw = raw;
        double p = Double.isNaN(panel) ? 0 : panel;
        double v = Math.max(0, raw - glow * p);
        long now = System.currentTimeMillis();
        // a lamp switched on or off: a big jump that lasts more than a few seconds is taken at once, so the
        // screensaver and brightness follow without waiting for the average (at most once a minute)
        boolean jump =
            !Double.isNaN(lightAverage) &&
            Math.abs(v - lightAverage) >= 8 &&
            (v >= lightAverage * 3 || v <= lightAverage / 3);
        if (!jump) jumpSince = 0;
        else if (jumpSince == 0) jumpSince = now;
        boolean snap = jump && now - jumpSince >= 3_000L && now - lastSnapAt >= 60_000L;
        if (Double.isNaN(lightAverage) || snap) {
            lightAverage = v;
            if (snap) {
                lastSnapAt = now;
                jumpSince = 0;
            }
        } else {
            double dt = Math.max(0, now - lightAverageAt) / 1000.0;
            lightAverage += (v - lightAverage) * (1 - Math.exp(-dt / 45.0));
        }
        lightAverageAt = now;
        double level = lightAverage;
        boolean first = lightPublished == null || snap;
        boolean real =
            !first &&
            Math.abs(level - lightPublished) >= 2.0 &&
            (level >= lightPublished * 1.5 || level <= lightPublished / 1.5);
        if (first || (real && now - lightPublishedAt >= 60_000L)) {
            lightPublished = (double) Math.round(level);
            lightPublishedAt = now;
            try {
                host.publishSensor("room_light", "Room light", lightMeta(), lightPublished);
            } catch (Throwable ignored) {}
        }
        double steady = lightPublished;
        // a little hysteresis so a level on the line doesn't flip back and forth
        boolean nowDark = dark ? steady < nightLux + 3 : steady < nightLux;
        if (nowDark != dark) {
            dark = nowDark;
            screensaver.darkChanged(nowDark);
        }
        applyMode();
    }

    /**
     * The screen's brightness changed (Kiosk Satellite event). Note the reading before, look again 4 seconds
     * later: the difference per unit of brightness is the screen's own glow on the sensor. Kept as a slow
     * average and capped, so a lamp switched on at the same moment can't throw it far off.
     */
    private void learnGlow(double newPanel) {
        double before = Double.isNaN(panel) ? newPanel : panel;
        panel = newPanel;
        Double rawBefore = lightRaw;
        if (rawBefore == null || Math.abs(newPanel - before) < 0.15) return;
        final double change = newPanel - before;
        main.postDelayed(
            () -> {
                Double rawAfter = lightRaw;
                if (!alive || rawAfter == null) return;
                double sample = (rawAfter - rawBefore) / change;
                if (sample < 0 || sample > 60) return; // the room changed too, not just the screen
                glow = glow == 0 ? sample : glow * 0.7 + sample * 0.3;
            },
            4_000
        );
    }

    private static Map<String, Object> lightMeta() {
        Map<String, Object> meta = new LinkedHashMap<>();
        meta.put("unit", "lx");
        meta.put("deviceClass", "illuminance");
        meta.put("stateClass", "measurement");
        meta.put("accuracyDecimals", 0);
        return meta;
    }

    private static String text(Object value, String fallback) {
        return value instanceof String && !((String) value).trim().isEmpty() ? ((String) value).trim() : fallback;
    }

    /** Only domain.object_id, so a setting can't change the template. */
    private static String entity(Object value) {
        String v = value instanceof String ? ((String) value).trim() : "";
        return v.matches("[a-z_]+[.][a-z0-9_]+") ? v : "";
    }

    private static double clamp(Object value, double min, double max, double fallback) {
        if (!(value instanceof Number)) return fallback;
        return Math.max(min, Math.min(max, ((Number) value).doubleValue()));
    }

    // ----- Home Assistant data for the screensaver and banner -----

    /** One live template: people, garage, alarm, alerts, this room's climate and lights, as JSON. */
    private void subscribeHouse() {
        PluginHost h = host;
        if (h == null) return;
        // A new key each time: reusing "house" right after unsubscribing it can lose the new subscription.
        try {
            h.haUnsubscribe(houseKey);
        } catch (Throwable ignored) {} // unknown keys are ignored
        houseKey = "house" + (++houseSubscriptions);
        subscribedArea = houseSubscriptionKey();
        String safeArea = area.replaceAll("[^a-z0-9_]", "");
        String template =
            "{% set area = '" +
            safeArea +
            "' %}" +
            "{% set garage = '" +
            garageEntity +
            "' %}{% set alarm = '" +
            alarmEntity +
            "' %}{% set alerts = '" +
            alertsEntity +
            "' %}" +
            "{% set al = (area_entities(area) if area else []) | select('match','light\\.') | reject('search','_screen_kiosk$|_screen_browser$|_led$') | list %}" +
            "{% set ns = namespace(l=[], p=[]) %}" +
            "{% for l in al %}{% set base = l | regex_replace('_\\d+_light$', '_light') %}" +
            "{% if base == l or base not in al %}{% set ns.l = ns.l + [{'id': l, 'name': state_attr(l,'friendly_name') or l, 'on': is_state(l,'on'), 'off_line': states(l) in ['unavailable','unknown']}] %}{% endif %}{% endfor %}" +
            // Lights labeled with the chosen label (Screensaver by default) win; otherwise the first few that work.
            "{% set lab = label_id('" +
            lightsLabel +
            "') %}{% set tagged = label_entities(lab) if lab else [] %}" +
            "{% set chosen = ns.l | selectattr('id', 'in', tagged) | list %}" +
            "{% set ns.l = chosen if chosen else (ns.l | rejectattr('off_line') | list)[:" +
            maxLights +
            "] %}" +
            "{% for p in states.person if p.attributes.entity_picture %}{% set ns.p = ns.p + [{'name': p.name, 'home': p.state == 'home', 'pic': p.attributes.entity_picture}] %}{% endfor %}" +
            // Room temperature/humidity: sensors in the area with that device class, not batteries, chips or
            // device internals; preferred words first, then one with a matching humidity sensor, then any.
            "{% set prefer = '" +
            preferSensors +
            "'.split(',') | reject('eq','') | list %}" +
            "{% set skip = 'battery|internal|device_temperature|chip|cpu|pcb|mcu|nozzle|_bed_|chamber|_ams_|_esp_|processor|soc|board' %}" +
            "{% set sens = (area_entities(area) if area else []) | select('match','sensor[.]') | reject('search', skip) | list %}" +
            "{% set temps = sens | select('is_state_attr','device_class','temperature') | select('has_value') | list %}" +
            "{% set hums = sens | select('is_state_attr','device_class','humidity') | select('has_value') | list %}" +
            "{% set ns2 = namespace(t='', h='') %}" +
            "{% for word in prefer %}" +
            "{% for e in temps if ns2.t == '' and word in e %}{% set ns2.t = e %}{% endfor %}" +
            "{% for e in hums if ns2.h == '' and word in e %}{% set ns2.h = e %}{% endfor %}" +
            "{% endfor %}" +
            "{% for e in temps if ns2.t == '' and (e | regex_replace('temperature$', 'humidity')) in hums %}{% set ns2.t = e %}{% set ns2.h = e | regex_replace('temperature$', 'humidity') %}{% endfor %}" +
            "{% if ns2.t == '' and temps %}{% set ns2.t = temps[0] %}{% endif %}" +
            "{% if ns2.h == '' and hums %}{% set ns2.h = hums[0] %}{% endif %}" +
            "{% set cl = ((area_entities(area) if area else []) | select('match','climate\\.') | list + [''])[0] %}" +
            "{% set clim = {'temp': (states(ns2.t) | float(0) | round(0) | int) if ns2.t else none, 'unit': state_attr(ns2.t,'unit_of_measurement') if ns2.t else ''," +
            " 'hum': (states(ns2.h) | float(0) | round(0) | int) if ns2.h else none," +
            " 'mode': states(cl) if cl else '', 'action': state_attr(cl,'hvac_action') if cl else '', 'target': state_attr(cl,'temperature') if cl else none} %}" +
            "{% set items = (state_attr(alerts,'items') if alerts else none) %}" +
            "{% set wx = '" +
            weatherEntity +
            "' %}" +
            "{% set weather = {'state': states(wx), 'temp': state_attr(wx,'temperature'), 'unit': state_attr(wx,'temperature_unit') or ''," +
            " 'hum': state_attr(wx,'humidity'), 'dew': state_attr(wx,'dew_point'), 'wind': state_attr(wx,'wind_speed'), 'wind_unit': state_attr(wx,'wind_speed_unit') or ''," +
            " 'cloud': state_attr(wx,'cloud_coverage')} if wx else {} %}" +
            "{{ {'area': (area_name(area) if area else '') or '', 'climate': clim, 'people': ns.p," +
            " 'garage': states(garage) if garage else '', 'alarm': states(alarm) if alarm else ''," +
            " 'alerts': (items if items is list else []), 'lights': ns.l, 'weather': weather," +
            " 'sun': state_attr('sun.sun','elevation'), 'sun_az': state_attr('sun.sun','azimuth')," +
            " 'lat': state_attr('zone.home','latitude'), 'lon': state_attr('zone.home','longitude')} | to_json }}";
        Map<String, Object> message = new HashMap<>();
        message.put("type", "render_template");
        message.put("template", template);
        message.put("report_errors", true);
        try {
            final String key = houseKey;
            h.haSubscribe(key, message, (ok, data, error) -> {
                if (!key.equals(houseKey)) return; // an older subscription
                if (!ok) status("Home Assistant data: " + error, true);
            });
        } catch (Throwable error) {
            log("House subscription: " + error);
        }
    }

    // Used by Screensaver and AlertBanner.
    PluginHost host() {
        return alive ? host : null;
    }

    Map<String, Object> data() {
        return houseData;
    }

    boolean isDark() {
        return dark;
    }

    String haUrl() {
        return haUrlSetting.isEmpty() ? ksHaUrl : haUrlSetting;
    }

    String photoIndexPath() {
        return photoIndexPath;
    }

    boolean nightScreensaver() {
        return nightScreensaver;
    }

    /** The house name over the voice skin's logo; empty for none. */
    String voiceTitle() {
        return voiceTitle;
    }

    boolean softText() {
        return softText;
    }

    java.io.InputStream openAsset(String name) throws java.io.IOException {
        PluginHost h = host();
        if (h == null) throw new java.io.IOException("Plugin stopped");
        return h.openAsset(name);
    }

    Double forecastHigh() {
        return forecastHigh;
    }

    Double forecastLow() {
        return forecastLow;
    }

    /** NASA's moon image for this hour of the year (frames numbered from 1 at 00:00 UTC on January 1). */
    String moonUrl() {
        java.util.Calendar utc = java.util.Calendar.getInstance(java.util.TimeZone.getTimeZone("UTC"));
        int frame = (utc.get(java.util.Calendar.DAY_OF_YEAR) - 1) * 24 + utc.get(java.util.Calendar.HOUR_OF_DAY) + 1;
        String pattern = moonFrames;
        if (pattern.isEmpty()) {
            Object lat = houseData.get("lat"); // Home Assistant's home location (zone.home)
            pattern = lat instanceof Number && ((Number) lat).doubleValue() < 0 ? MOON_SOUTH : MOON_NORTH;
        }
        return pattern.replace("{frame}", String.format(java.util.Locale.US, "%04d", frame));
    }

    /** Today's high and low, refreshed every 30 minutes while the night screensaver is used. */
    void refreshForecast() {
        PluginHost h = host();
        String wx = weatherEntity;
        if (h == null || wx.isEmpty() || System.currentTimeMillis() - forecastAt < 30 * 60_000L) return;
        forecastAt = System.currentTimeMillis();
        Map<String, Object> message = new HashMap<>();
        message.put("type", "call_service");
        message.put("domain", "weather");
        message.put("service", "get_forecasts");
        message.put("service_data", Collections.singletonMap("type", "daily"));
        message.put("target", Collections.singletonMap("entity_id", wx));
        message.put("return_response", true);
        try {
            h.haCall(message, (ok, data, error) -> {
                if (!ok) {
                    log("Forecast: " + error);
                    forecastAt = 0;
                    return;
                }
                try {
                    Map<String, Object> response = Net.map(Net.map(data).get("response"));
                    List<Object> days = Net.list(Net.map(response.get(wx)).get("forecast"));
                    if (days.isEmpty()) return;
                    Map<String, Object> today = Net.map(days.get(0));
                    Object hi = today.get("temperature"), lo = today.get("templow");
                    forecastHigh = hi instanceof Number ? ((Number) hi).doubleValue() : null;
                    forecastLow = lo instanceof Number ? ((Number) lo).doubleValue() : null;
                    screensaver.dataChanged();
                } catch (Throwable e) {
                    log("Forecast: " + e);
                }
            });
        } catch (Throwable e) {
            log("Forecast: " + e);
        }
    }

    int photoSeconds() {
        return photoSeconds;
    }

    int photoBlock() {
        return photoBlock;
    }

    int alertMinutes() {
        return alertMinutes;
    }

    void closeScreensaver() {
        PluginHost h = host();
        if (h == null) return;
        h.executeCommand("stopScreensaver", Collections.emptyMap(), (ok, data, error) -> {
            if (!ok) log("Close screensaver: " + error);
        });
    }

    void toggleLight(String entityId) {
        PluginHost h = host();
        if (h == null) return;
        Map<String, Object> message = new HashMap<>();
        message.put("type", "call_service");
        message.put("domain", "light");
        message.put("service", "toggle");
        message.put("target", Collections.singletonMap("entity_id", entityId));
        h.haCall(message, (ok, data, error) -> {
            if (!ok) log("Light " + entityId + ": " + error);
        });
    }

    /** Background threads only: a signed link for an HA path such as /media/local/photos/x.jpg. */
    String signPath(String path) throws InterruptedException {
        PluginHost h = host();
        if (h == null) return null;
        Map<String, Object> message = new HashMap<>();
        message.put("type", "auth/sign_path");
        message.put("path", path);
        message.put("expires", 300);
        CountDownLatch done = new CountDownLatch(1);
        AtomicReference<String> result = new AtomicReference<>();
        h.haCall(message, (ok, data, error) -> {
            if (ok && data instanceof Map) result.set(Net.str(((Map<?, ?>) data).get("path")));
            else log("Sign photo link: " + error);
            done.countDown();
        });
        if (!done.await(10, TimeUnit.SECONDS)) return null;
        return result.get();
    }

    // ----- Main thread -----

    private void attach() {
        if (!alive) return;
        app = currentApplication();
        if (app == null) {
            status("Could not reach the Kiosk Satellite app. Auto-close is not running.", true);
            return;
        }
        callbacks = new Tracker();
        app.registerActivityLifecycleCallbacks(callbacks);
        Activity current = currentActivity();
        if (current != null) {
            activity = new WeakReference<>(current);
            spy(current);
        }
        publishTouch(true);
        status(
            current != null
                ? "Watching for windows left on top."
                : "Waiting for the Kiosk Satellite screen. Turn the screen off and on if this stays.",
            false
        );
        main.post(poll);
    }

    /**
     * Every 4 seconds: is the browser on a page from Hold pages (settings, add-on panels)? Then the screensaver's idle
     * timer is held, since talking to the keyboard's voice typing or reading a long answer doesn't touch the screen.
     * Leaving the page lets go, and the countdown starts again.
     */
    private void holdOnPage() {
        long now = SystemClock.elapsedRealtime();
        if (now - pageCheckedAt < 4_000L) return;
        pageCheckedAt = now;
        PluginHost h = host();
        if (h == null) return;
        try {
            h.executeCommand("getDashboardState", Collections.emptyMap(), (ok, data, error) -> {
                if (!ok || !(data instanceof Map)) return;
                java.util.regex.Pattern pattern = holdPages;
                String path = Net.str(((Map<?, ?>) data).get("currentPath"));
                setPageHold(pattern != null && pattern.matcher(path).find());
            });
        } catch (Throwable error) {
            log("Hold pages: " + error);
        }
    }

    private void setPageHold(boolean held) {
        if (held == pageHeld) return;
        PluginHost h = host();
        if (h == null) return;
        Map<String, Object> params = new LinkedHashMap<>();
        params.put("holder", "house_tools_page");
        params.put("held", held);
        try {
            h.executeCommand("holdScreensaver", params, (ok, data, error) -> {
                if (ok) pageHeld = held;
                else log("Hold screensaver: " + error);
            });
        } catch (Throwable error) {
            log("Hold screensaver: " + error);
        }
    }

    /** Every 5 minutes: put our screensaver choice back and reconnect the house data if it went quiet. */
    private void healthCheck() {
        long now = System.currentTimeMillis();
        if (now - lastHealthCheck < 300_000L) return;
        lastHealthCheck = now;
        modeApplied = null; // setScreensaverMode again; harmless if nothing changed
        applyMode();
        if (
            (lastHouseData > 0 && now - lastHouseData > 30 * 60_000L) ||
            (lastHouseData == 0 && now - startedAt > 120_000L)
        ) {
            log("No house data for a while; subscribing again");
            subscribedArea = null;
            subscribeHouse();
            lastHouseData = now; // don't repeat for another 30 minutes
        }
    }

    private void check() {
        healthCheck();
        holdOnPage();
        // The sensor only reports changes; keep the average moving toward a reading that has stopped changing.
        Double raw = lightRaw;
        if (raw != null) lightReading(raw);
        Activity current = activity.get();
        if (current != null) spy(current);
        publishTouch(false);
        boolean covered = current != null && isCovered(current);
        publishCovered(covered);
        long now = SystemClock.elapsedRealtime();
        if (!covered) {
            coveredSince = -1;
            triesThisCover = 0;
            return;
        }
        if (coveredSince < 0) coveredSince = now;
        if (!autoClose || triesThisCover >= MAX_TRIES_PER_COVER) return;
        if (now - coveredSince < autoCloseMs) return;
        triesThisCover++;
        coveredSince = now; // the next try, if needed, waits a full period again
        bringToFront("auto-close");
        if (triesThisCover == MAX_TRIES_PER_COVER) {
            log("Window still on top after " + MAX_TRIES_PER_COVER + " tries; waiting until it goes away.");
        }
    }

    /** Visible (not stopped), screen on, but another window holds input focus. */
    private boolean isCovered(Activity current) {
        if (current.isFinishing()) return false;
        if (Build.VERSION.SDK_INT >= 17 && current.isDestroyed()) return false;
        if (!screenOn(current)) return false;
        View decor = current.getWindow() == null ? null : current.getWindow().peekDecorView();
        // Android hides the decor view when the activity stops, e.g. a full-screen app or the home screen.
        if (decor == null || decor.getVisibility() != View.VISIBLE || decor.getWindowToken() == null) return false;
        return !current.hasWindowFocus();
    }

    private static boolean screenOn(Context context) {
        PowerManager power = (PowerManager) context.getSystemService(Context.POWER_SERVICE);
        if (power == null) return true;
        return Build.VERSION.SDK_INT >= 20 ? power.isInteractive() : power.isScreenOn();
    }

    private void bringToFront(String reason) {
        Activity current = activity.get();
        Context context = current != null ? current : app;
        if (context == null) {
            log("Bring to front (" + reason + "): app not reachable.");
            return;
        }
        Intent intent = context.getPackageManager().getLaunchIntentForPackage(context.getPackageName());
        if (intent == null && current != null) intent = new Intent(current, current.getClass());
        if (intent == null) {
            log("Bring to front (" + reason + "): no launch intent.");
            return;
        }
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_REORDER_TO_FRONT);
        try {
            context.startActivity(intent);
        } catch (Throwable error) {
            log("Bring to front (" + reason + ") failed: " + error);
            return;
        }
        if (!"command".equals(reason)) {
            autoCloses++;
            try {
                host.publishSensor("auto_closes", "Auto-closes", counterMeta(), (double) autoCloses);
            } catch (Throwable ignored) {}
        }
        log("Brought Kiosk Satellite to front (" + reason + ").");
    }

    private final class Tracker implements Application.ActivityLifecycleCallbacks {

        private void remember(Activity a) {
            activity = new WeakReference<>(a);
            spy(a);
        }

        @Override
        public void onActivityCreated(Activity a, Bundle state) {
            remember(a);
        }

        @Override
        public void onActivityStarted(Activity a) {
            remember(a);
        }

        @Override
        public void onActivityResumed(Activity a) {
            remember(a);
        }

        @Override
        public void onActivityPaused(Activity a) {}

        @Override
        public void onActivityStopped(Activity a) {}

        @Override
        public void onActivitySaveInstanceState(Activity a, Bundle state) {}

        @Override
        public void onActivityDestroyed(Activity a) {
            if (activity.get() == a) activity = new WeakReference<>(null);
            if (spiedWindow.get() == a.getWindow()) {
                spiedWindow = new WeakReference<>(null);
                spiedOriginal = null;
            }
        }
    }

    // ----- Touch tracking (main thread) -----

    /** Marker so a window we already wrap is not wrapped twice. */
    private final class TouchSpy implements InvocationHandler {

        final Window.Callback original;

        TouchSpy(Window.Callback original) {
            this.original = original;
        }

        @Override
        public Object invoke(Object proxy, Method method, Object[] args) throws Throwable {
            if (
                args != null &&
                args.length == 1 &&
                args[0] instanceof MotionEvent &&
                "dispatchTouchEvent".equals(method.getName())
            ) {
                try {
                    if (((MotionEvent) args[0]).getActionMasked() == MotionEvent.ACTION_DOWN) touched();
                } catch (Throwable ignored) {}
            }
            if (method.getDeclaringClass() == Object.class) {
                if ("equals".equals(method.getName())) return proxy == args[0];
                if ("hashCode".equals(method.getName())) return System.identityHashCode(proxy);
                if ("toString".equals(method.getName())) return "HouseTools(" + original + ")";
            }
            try {
                return method.invoke(original, args);
            } catch (InvocationTargetException error) {
                throw error.getCause();
            }
        }
    }

    private void spy(Activity a) {
        if (!alive) return;
        Window window = a.getWindow();
        if (window == null) return;
        Window.Callback current = window.getCallback();
        if (current == null) return;
        if (Proxy.isProxyClass(current.getClass()) && Proxy.getInvocationHandler(current) instanceof TouchSpy) return;
        try {
            Window.Callback wrapped = (Window.Callback) Proxy.newProxyInstance(
                Window.Callback.class.getClassLoader(),
                new Class<?>[] { Window.Callback.class },
                new TouchSpy(current)
            );
            unspy(); // only ever keep one window wrapped
            window.setCallback(wrapped);
            spiedWindow = new WeakReference<>(window);
            spiedOriginal = current;
        } catch (Throwable error) {
            log("Touch tracking unavailable: " + error);
        }
    }

    /** Put the original callback back, unless something else replaced ours since. */
    private void unspy() {
        Window window = spiedWindow.get();
        Window.Callback original = spiedOriginal;
        spiedWindow = new WeakReference<>(null);
        spiedOriginal = null;
        if (window == null || original == null) return;
        Runnable restore = () -> {
            Window.Callback current = window.getCallback();
            if (
                current != null &&
                Proxy.isProxyClass(current.getClass()) &&
                Proxy.getInvocationHandler(current) instanceof TouchSpy
            ) {
                window.setCallback(original);
            }
        };
        if (Looper.myLooper() == Looper.getMainLooper()) restore.run();
        else main.post(restore);
    }

    private void touched() {
        lastTouchElapsed = SystemClock.elapsedRealtime();
        if (!alive) return;
        publishTouch(false);
    }

    private void publishTouch(boolean force) {
        long touch = lastTouchElapsed;
        boolean inUse = touch >= 0 && SystemClock.elapsedRealtime() - touch < inUseMs;
        if (publishedInUse == null || publishedInUse != inUse) {
            publishedInUse = inUse;
            try {
                host.publishBinarySensor("in_use", "In use", "", inUse);
            } catch (Throwable ignored) {}
        }
        if (touch == lastTouchPublished) return;
        // Throttle: a burst of touches is sent at most every 10 s; the poll sends the latest one after that.
        if (
            !force &&
            lastTouchPublished >= 0 &&
            touch - lastTouchPublished < TOUCH_PUBLISH_MIN_MS &&
            SystemClock.elapsedRealtime() - lastTouchPublished < TOUCH_PUBLISH_MIN_MS
        ) return;
        lastTouchPublished = touch;
        String text = null;
        if (touch >= 0) {
            long wall = System.currentTimeMillis() - (SystemClock.elapsedRealtime() - touch);
            SimpleDateFormat format = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ssXXX", Locale.US);
            format.setTimeZone(TimeZone.getTimeZone("UTC"));
            text = format.format(new Date(wall));
        }
        try {
            host.publishTextSensor("last_touch", "Last touched", text);
        } catch (Throwable ignored) {}
    }

    // ----- Android framework lookups (no Kiosk Satellite internals) -----

    private static Application currentApplication() {
        try {
            Method method = Class.forName("android.app.ActivityThread").getMethod("currentApplication");
            return (Application) method.invoke(null);
        } catch (Throwable error) {
            return null;
        }
    }

    /** Best effort: the activity that already existed when the plugin started. */
    private static Activity currentActivity() {
        try {
            Class<?> threadClass = Class.forName("android.app.ActivityThread");
            Object thread = threadClass.getMethod("currentActivityThread").invoke(null);
            Field field = threadClass.getDeclaredField("mActivities");
            field.setAccessible(true);
            Map<?, ?> records = (Map<?, ?>) field.get(thread);
            Activity found = null;
            for (Object record : records.values()) {
                Field activityField = record.getClass().getDeclaredField("activity");
                activityField.setAccessible(true);
                Activity candidate = (Activity) activityField.get(record);
                if (candidate == null || candidate.isFinishing()) continue;
                found = candidate;
                Field paused = record.getClass().getDeclaredField("paused");
                paused.setAccessible(true);
                if (!paused.getBoolean(record)) break; // prefer the resumed one
            }
            return found;
        } catch (Throwable error) {
            return null;
        }
    }

    // ----- Entities and logging -----

    private void publishCovered(boolean covered) {
        if (publishedCovered != null && publishedCovered == covered) return;
        publishedCovered = covered;
        try {
            host.publishBinarySensor("covered", "Window on top", "", covered);
        } catch (Throwable ignored) {}
    }

    private void publishSwitch() {
        if (!alive) return;
        try {
            host.publishSwitch("auto_close", "Auto-close", autoClose);
        } catch (Throwable ignored) {}
    }

    private static Map<String, Object> counterMeta() {
        Map<String, Object> meta = new LinkedHashMap<>();
        meta.put("stateClass", "total_increasing");
        meta.put("accuracyDecimals", 0);
        return meta;
    }

    void status(String message, boolean error) {
        if (!alive) return;
        try {
            host.status(message, error);
        } catch (Throwable ignored) {}
    }

    private final java.util.Map<String, Long> lastLogged = new java.util.HashMap<>();

    /**
     * Log a message and show it as the Plugin message entity. The same message is logged at most once every
     * 10 minutes, so a repeating error (photos failing while the NAS is down) can't fill Kiosk Satellite's log.
     */
    void log(String message) {
        if (!alive) return;
        // Never let a signed link's token reach the log or Home Assistant (exception texts can contain URLs).
        message = message.replaceAll("authSig=[^&\\s\"']+", "authSig=(hidden)");
        long now = System.currentTimeMillis();
        synchronized (lastLogged) {
            Long last = lastLogged.get(message);
            if (last != null && now - last < 10 * 60_000L) return;
            if (lastLogged.size() > 200) lastLogged.clear();
            lastLogged.put(message, now);
        }
        try {
            host.log(message);
        } catch (Throwable ignored) {}
        // Also visible in Home Assistant, since plugin logs can't be read remotely.
        String text = message.length() > 240 ? message.substring(0, 240) : message;
        try {
            host.publishTextSensor("last_message", "Plugin message", text);
        } catch (Throwable ignored) {}
    }
}
