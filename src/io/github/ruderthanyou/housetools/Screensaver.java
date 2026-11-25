// SPDX-License-Identifier: MIT
package io.github.ruderthanyou.housetools;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapShader;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.ColorMatrix;
import android.graphics.ColorMatrixColorFilter;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.Rect;
import android.graphics.Shader;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.os.Handler;
import android.os.Looper;
import android.text.TextUtils;
import android.util.DisplayMetrics;
import android.view.GestureDetector;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import me.jxl.kiosk.plugins.KsTheme;
import me.jxl.kiosk.plugins.OverlayFactory;
import me.jxl.kiosk.plugins.PluginHost;

/**
 * The screensaver view Kiosk Satellite shows when the plugin's mode is selected. Kiosk Satellite builds it through
 * {@link #create} each time the screensaver appears and calls {@link #onDestroy} when it goes, so all state that
 * must last (photo list, history, star catalog) lives in this object, not in the view.
 *
 * <p>Two screens in one view ({@code Root}):
 * <ul>
 *   <li><b>Photo screen</b> (lit room): whole photos over a blurred fill ({@code PhotoView}), at random with no
 *       repeats until all have shown, crossfading; corners with the clock and date, Outside weather and the dew
 *       point, sized from Kiosk Satellite's own widgets; and centered chips for faces, inside climate, house
 *       status and this room's lights, which wrap onto new lines ({@code FlowRow}).</li>
 *   <li><b>Night screen</b> (dark room, when the sky screensaver is on): {@link SkyView} with a big clock and
 *       date and Weather Mood style buttons along the bottom.</li>
 * </ul>
 *
 * <p>Taps: right third = next photo, left third = previous, middle = close (Kiosk Satellite also closes on a
 * double tap). Chips handle their own taps.
 *
 * <p>Threads: everything here runs on the main thread except downloads (photo list, photos, face pictures, the
 * NASA moon image, the star catalog), which run on {@link #worker} and post their results back.
 */
final class Screensaver implements OverlayFactory {

    static final String KEY = "home";

    private static final int CHIP = 0xD91C1D21; // Weather Mood style dark pill
    private static final int NAVY = CHIP;
    private static final int NAVY_SOFT = CHIP;
    private static final int AMBER = 0xFFF2B33D; // light on
    private static final int ORANGE = 0xFFFB8C00;
    private static final int RED = 0xFFE53935;
    private static final int GREEN = 0xFF43A047;
    private int TEXT = 0xFFF5F7FA; // soft gray (OLED care) or near-white, set when the view is made
    private int TEXT_SOFT_E6 = 0xE6F5F7FA, TEXT_SOFT_CC = 0xCCF5F7FA;
    private static final int TEXT_DIM = 0xB3F5F7FA;
    /** Distance of every photo screen corner (and the chips) from the screen edge, in reference pixels. */
    private static final float EDGE = 24;

    private final HouseToolsPlugin plugin;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final Random random = new Random();
    private ExecutorService worker;

    // Photo list (worker thread)
    private List<String> photos = Collections.emptyList();
    private String photoBase = "";
    private long photosLoadedAt;
    private int nextIndex = -1;
    private int leftInBlock;
    private final List<String> history = new ArrayList<>(); // names shown, oldest first (worker thread)
    private final List<String> deck = new ArrayList<>(); // shuffled photos not shown yet (worker thread)
    private int historyPos = -1;

    // Current view (main thread)
    private Root view;
    private boolean dark;
    private final Map<String, Bitmap> avatars = new HashMap<>();

    Screensaver(HouseToolsPlugin plugin) {
        this.plugin = plugin;
    }

    // ----- OverlayFactory (main thread) -----

    @Override
    public View create(Context context, KsTheme theme) {
        boolean soft = plugin.softText();
        TEXT = soft ? 0xFFAAAAAA : 0xFFF5F7FA;
        TEXT_SOFT_E6 = soft ? 0xE6AAAAAA : 0xE6F5F7FA;
        TEXT_SOFT_CC = soft ? 0xCCAAAAAA : 0xCCF5F7FA;
        view = new Root(context, theme);
        worker = Executors.newSingleThreadExecutor();
        applyData(plugin.data());
        setDark(plugin.isDark());
        loadStars();
        if (moonPhoto != null) view.sky.setMoonPhoto(moonPhoto);
        else loadCachedMoon(context);
        scheduleNextPhoto(0);
        return view;
    }

    @Override
    public void onDestroy(View destroyed) {
        if (destroyed == view) view = null;
        main.removeCallbacksAndMessages(null);
        ExecutorService w = worker;
        worker = null;
        if (w != null) w.shutdownNow();
    }

    // ----- Called by the plugin (any thread) -----

    void dataChanged() {
        main.post(() -> {
            if (view != null) applyData(plugin.data());
        });
    }

    void darkChanged(boolean isDark) {
        main.post(() -> setDark(isDark));
    }

    void shutdown() {
        main.post(() -> {
            if (view != null) onDestroy(view);
        });
    }

    // ----- Photos -----

    private void setDark(boolean isDark) {
        boolean changed = dark != isDark;
        dark = isDark;
        if (view == null) return;
        view.setNight(isDark);
        if (changed && !isDark) scheduleNextPhoto(0);
        main.removeCallbacks(tick);
        main.post(tick);
    }

    private void scheduleNextPhoto(long delayMs) {
        main.removeCallbacks(nextPhoto);
        main.postDelayed(nextPhoto, delayMs);
    }

    private final Runnable nextPhoto = new Runnable() {
        @Override
        public void run() {
            if (view == null) return;
            long interval = plugin.photoSeconds() * 1000L;
            if (dark) {
                scheduleNextPhoto(interval);
                return;
            } // no photos in a dark room
            ExecutorService w = worker;
            if (w == null) return;
            DisplayMetrics metrics = view.getResources().getDisplayMetrics();
            int width = Math.max(metrics.widthPixels, 640), height = Math.max(metrics.heightPixels, 480);
            w.execute(() -> loadPhoto(width, height, 1));
            scheduleNextPhoto(interval);
        }
    };

    /** A plain file name: no folders, no "." or "..", no control characters. */
    static boolean safeFileName(String name) {
        if (name == null || name.isEmpty() || name.length() > 255) return false;
        if (name.equals(".") || name.equals("..")) return false;
        for (int i = 0; i < name.length(); i++) {
            char c = name.charAt(i);
            if (c == '/' || c == '\\' || c < 32 || c == 127) return false;
        }
        return true;
    }

    /** Main thread: a tap on the left or right of the photo. */
    private void step(int direction) {
        if (view == null || dark) return;
        ExecutorService w = worker;
        if (w == null) return;
        DisplayMetrics metrics = view.getResources().getDisplayMetrics();
        int width = Math.max(metrics.widthPixels, 640), height = Math.max(metrics.heightPixels, 480);
        w.execute(() -> loadPhoto(width, height, direction));
        scheduleNextPhoto(plugin.photoSeconds() * 1000L); // a full period on the chosen photo
    }

    /** Worker thread: forward = next in history or a new pick; back = previous in history. Then sign, download, show. */
    private void loadPhoto(int width, int height, int direction) {
        try {
            String base = plugin.haUrl();
            if (base.isEmpty()) {
                plugin.log("Photo: Home Assistant address not known yet");
                return;
            }
            if (photos.isEmpty() || System.currentTimeMillis() - photosLoadedAt > 6 * 3600_000L) {
                Map<String, Object> index = Net.map(
                    Net.plain(new String(Net.get(base + plugin.photoIndexPath(), 2_000_000), "UTF-8"))
                );
                List<String> names = new ArrayList<>();
                for (Object name : Net.list(index.get("photos"))) names.add(Net.str(name));
                photoBase = Net.str(index.get("base"));
                // The list file sits in Home Assistant's public www folder, so check it before signing anything:
                // only media paths, and only plain file names (no folders, no "..").
                if (!photoBase.startsWith("/media/") || photoBase.contains("..") || !photoBase.endsWith("/")) {
                    photos = java.util.Collections.emptyList();
                    plugin.log("Photo list: base must be a /media/... folder ending in /");
                    return;
                }
                names.removeIf(n -> !safeFileName(n));
                photos = names;
                photosLoadedAt = System.currentTimeMillis();
                leftInBlock = 0;
                deck.clear();
            }
            if (photos.isEmpty()) return;
            String name;
            if (direction < 0) {
                if (historyPos <= 0) return; // nothing earlier
                historyPos--;
                name = history.get(historyPos);
            } else if (historyPos < history.size() - 1) {
                historyPos++;
                name = history.get(historyPos);
            } else if (plugin.photoBlock() <= 1) {
                // Like Kiosk Satellite's and Wallpanel's: each photo at random, but every photo once before any repeats.
                if (deck.isEmpty()) {
                    deck.addAll(photos);
                    Collections.shuffle(deck, random);
                }
                name = deck.remove(deck.size() - 1);
                history.add(name);
                if (history.size() > 50) history.remove(0);
                historyPos = history.size() - 1;
            } else {
                if (leftInBlock <= 0 || nextIndex < 0) {
                    nextIndex = random.nextInt(photos.size());
                    leftInBlock = Math.max(1, plugin.photoBlock());
                }
                name = photos.get(nextIndex % photos.size());
                nextIndex = (nextIndex + 2 + random.nextInt(5)) % photos.size(); // skip 1-5: bursts are near-copies
                leftInBlock--;
                history.add(name);
                if (history.size() > 50) history.remove(0);
                historyPos = history.size() - 1;
            }
            String path = photoBase + name;
            String signed = plugin.signPath(path); // blocks up to a few seconds
            if (signed == null) return;
            int q = signed.indexOf('?');
            String url = base + photoBase + Net.encodeSegment(name) + (q >= 0 ? signed.substring(q) : "");
            byte[] bytes = Net.get(url, 40_000_000);
            Bitmap bitmap = Net.decode(bytes, width / 2, height / 2, false);
            if (bitmap == null) return;
            main.post(() -> {
                if (view != null) view.showPhoto(bitmap);
            });
        } catch (InterruptedException stopped) {
            Thread.currentThread().interrupt();
        } catch (Throwable error) {
            plugin.log("Photo: " + error.getMessage());
        }
    }

    // ----- House data -----

    private void applyData(Map<String, Object> data) {
        if (view == null || data == null) return;
        view.setPeople(Net.list(data.get("people")));
        view.setStatus(
            Net.str(data.get("garage")),
            Net.str(data.get("alarm")),
            Net.list(data.get("alerts")),
            Net.map(data.get("climate"))
        );
        view.setLights(Net.str(data.get("area")), Net.list(data.get("lights")));
        Object sun = data.get("sun");
        Object lat = data.get("lat"), lon = data.get("lon");
        if (lat instanceof Number && lon instanceof Number) view.sky.setLocation(
            ((Number) lat).doubleValue(),
            ((Number) lon).doubleValue()
        );
        Object azimuth = data.get("sun_az");
        if (azimuth instanceof Number) view.sky.setSunAzimuth(((Number) azimuth).doubleValue());
        view.setWeather(Net.map(data.get("weather")), sun instanceof Number ? ((Number) sun).doubleValue() : -30);
    }

    // ----- Night screen: clock tick and NASA moon -----

    private String moonLoaded = "", moonLoading = "";
    private Bitmap moonPhoto; // the latest NASA image, handed to every new sky view
    private long moonRetryAt; // after a failed download, wait until then
    private static final String MOON_CACHE = "house_tools_moon.jpg";

    private final Runnable tick = new Runnable() {
        @Override
        public void run() {
            if (view == null) return;
            view.updateClock();
            if (view.nightShown()) {
                String url = plugin.moonUrl();
                if (
                    !url.equals(moonLoaded) && !url.equals(moonLoading) && System.currentTimeMillis() >= moonRetryAt
                ) loadMoon(url);
            }
            plugin.refreshForecast();
            long now = System.currentTimeMillis();
            main.postDelayed(this, 1000 - (now % 1000) + 20);
        }
    };

    private static float[][] stars; // loaded once from assets/stars.csv

    private void loadStars() {
        if (stars != null) {
            view.sky.setStars(stars[0], stars[1], stars[2], stars[3]);
            return;
        }
        ExecutorService w = worker;
        if (w == null) return;
        w.execute(() -> {
            try (
                java.io.BufferedReader in = new java.io.BufferedReader(
                    new java.io.InputStreamReader(plugin.openAsset("stars.csv"), "UTF-8")
                )
            ) {
                List<float[]> rows = new ArrayList<>();
                String line;
                while ((line = in.readLine()) != null) {
                    if (line.startsWith("#") || line.trim().isEmpty()) continue;
                    String[] f = line.split(",");
                    rows.add(
                        new float[] {
                            Float.parseFloat(f[0]),
                            Float.parseFloat(f[1]),
                            Float.parseFloat(f[2]),
                            Float.parseFloat(f[3]),
                        }
                    );
                }
                float[][] cat = new float[4][rows.size()];
                for (int i = 0; i < rows.size(); i++) for (int k = 0; k < 4; k++) cat[k][i] = rows.get(i)[k];
                stars = cat;
                main.post(() -> {
                    if (view != null) view.sky.setStars(cat[0], cat[1], cat[2], cat[3]);
                });
            } catch (Throwable error) {
                plugin.log("Stars: " + error);
            }
        });
    }

    /**
     * Download NASA's moon image for this hour. On success it replaces the current one and is saved on the tablet;
     * on failure the last image stays (it looks almost the same a few hours later) and we try again in 10 minutes.
     */
    private void loadMoon(String url) {
        ExecutorService w = worker;
        if (w == null) return;
        moonLoading = url;
        final java.io.File cache = view == null ? null : new java.io.File(view.getContext().getCacheDir(), MOON_CACHE);
        w.execute(() -> {
            try {
                byte[] bytes = Net.get(url, 2_000_000);
                Bitmap moon = Net.decode(bytes, 300, 300, false);
                if (moon == null) throw new java.io.IOException("not an image");
                // Save at most every 6 hours: it's only a fallback, and this keeps writes to the tablet's flash rare.
                if (cache != null && System.currentTimeMillis() - cache.lastModified() > 6 * 3600_000L) {
                    try (java.io.FileOutputStream out = new java.io.FileOutputStream(cache)) {
                        out.write(bytes);
                    } catch (Throwable ignored) {}
                }
                main.post(() -> {
                    moonLoading = "";
                    moonLoaded = url;
                    moonPhoto = moon;
                    if (view != null) view.sky.setMoonPhoto(moon);
                });
            } catch (Throwable error) {
                main.post(() -> {
                    moonLoading = "";
                    moonRetryAt = System.currentTimeMillis() + 10 * 60_000L;
                });
                plugin.log("Moon image: " + error.getMessage() + " (keeping the last one)");
            }
        });
    }

    /** After a restart: the last saved image, until the new hour's one arrives. */
    private void loadCachedMoon(Context context) {
        ExecutorService w = worker;
        if (w == null) return;
        final java.io.File cache = new java.io.File(context.getCacheDir(), MOON_CACHE);
        w.execute(() -> {
            try {
                if (!cache.exists()) return;
                Bitmap moon = android.graphics.BitmapFactory.decodeFile(cache.getPath());
                if (moon == null) return;
                main.post(() -> {
                    if (moonPhoto != null) return; // a fresh one already arrived
                    moonPhoto = moon;
                    if (view != null) view.sky.setMoonPhoto(moon);
                });
            } catch (Throwable ignored) {}
        });
    }

    private void loadAvatar(String url, CircleImage target) {
        Bitmap cached = avatars.get(url);
        if (cached != null) {
            target.setBitmap(cached);
            return;
        }
        ExecutorService w = worker;
        if (w == null || url.isEmpty()) return;
        String full = url.startsWith("http") ? url : plugin.haUrl() + url;
        w.execute(() -> {
            try {
                Bitmap bitmap = Net.decode(Net.get(full, 5_000_000), 128, 128, true);
                if (bitmap == null) return;
                main.post(() -> {
                    avatars.put(url, bitmap);
                    target.setBitmap(bitmap);
                });
            } catch (Throwable error) {
                plugin.log("Face picture: " + error.getMessage());
            }
        });
    }

    // ----- Views -----

    /** Round picture, grayed out when the person is away. */
    private static final class CircleImage extends View {

        private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint ring = new Paint(Paint.ANTI_ALIAS_FLAG);
        private Bitmap bitmap;
        private String initial = "";
        private boolean away;

        CircleImage(Context context) {
            super(context);
            ring.setStyle(Paint.Style.STROKE);
        }

        @Override
        protected void onMeasure(int widthSpec, int heightSpec) {
            setMeasuredDimension(getSuggestedMinimumWidth(), getSuggestedMinimumHeight());
        }

        void setBitmap(Bitmap b) {
            bitmap = b;
            invalidate();
        }

        void setInitial(String s) {
            initial = s;
            invalidate();
        }

        void setAway(boolean a) {
            away = a;
            if (a) {
                ColorMatrix gray = new ColorMatrix();
                gray.setSaturation(0);
                paint.setColorFilter(new ColorMatrixColorFilter(gray));
            } else {
                paint.setColorFilter(null);
            }
            setAlpha(a ? 0.55f : 1f);
            invalidate();
        }

        @Override
        protected void onDraw(Canvas canvas) {
            float size = Math.min(getWidth(), getHeight());
            float r = size / 2f;
            float stroke = size * 0.06f;
            if (bitmap != null) {
                BitmapShader shader = new BitmapShader(bitmap, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP);
                float scale = size / Math.min(bitmap.getWidth(), bitmap.getHeight());
                Matrix m = new Matrix();
                m.setScale(scale, scale);
                m.postTranslate((size - bitmap.getWidth() * scale) / 2f, (size - bitmap.getHeight() * scale) / 2f);
                shader.setLocalMatrix(m);
                paint.setShader(shader);
                canvas.drawCircle(r, r, r - stroke / 2, paint);
                paint.setShader(null);
            } else {
                Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG);
                fill.setColor(0xFF2A3F63);
                canvas.drawCircle(r, r, r - stroke / 2, fill);
                Paint text = new Paint(Paint.ANTI_ALIAS_FLAG);
                text.setColor(0xFFF5F7FA);
                text.setTextSize(size * 0.45f);
                text.setTextAlign(Paint.Align.CENTER);
                canvas.drawText(initial, r, r - (text.descent() + text.ascent()) / 2, text);
            }
            ring.setStrokeWidth(stroke);
            ring.setColor(away ? 0x66FFFFFF : 0xFF4CAF50);
            canvas.drawCircle(r, r, r - stroke / 2, ring);
        }
    }

    private final class Root extends FrameLayout {

        private final KsTheme theme;
        private PhotoView back, front;
        private final View scrim;
        private final FlowRow topRow, lightsRow; // faces + status chips; this room's lights
        private final LinearLayout content; // the photo screen's chips column
        private final FrameLayout nightLayer; // the dark-room screen: sky, clock, weather
        final SkyView sky;
        private final TextView clock, date;
        private final LinearLayout wxLeft, wxRight;
        // Photo screen corners: clock and date (top left), dew point (bottom left), Outside weather (bottom right).
        private final TextView dayClock, dayDate;
        private final LinearLayout dayClockBox, dewBox, dayWeather;
        private final java.util.List<View> people = new ArrayList<>(), status = new ArrayList<>();
        private final TextView hint;

        Root(Context context, KsTheme theme) {
            super(context);
            this.theme = theme;
            setBackgroundColor(Color.BLACK);
            back = photoView(context);
            front = photoView(context);
            addView(back, new LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT));
            addView(front, new LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT));

            scrim = new View(context);
            scrim.setBackground(
                new GradientDrawable(GradientDrawable.Orientation.BOTTOM_TOP, new int[] { 0xCC000000, 0x00000000 })
            );
            LayoutParams scrimParams = new LayoutParams(LayoutParams.MATCH_PARENT, dp(220));
            scrimParams.gravity = Gravity.BOTTOM;
            addView(scrim, scrimParams);

            LinearLayout column = new LinearLayout(context);
            column.setOrientation(LinearLayout.VERTICAL);
            column.setGravity(Gravity.CENTER_HORIZONTAL);
            LayoutParams columnParams = new LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT);
            columnParams.gravity = Gravity.BOTTOM;
            // Bottom lines up with the bottom corners; the sides are set in onMeasure to clear them.
            columnParams.bottomMargin = (int) ref(EDGE);
            addView(column, columnParams);
            content = column;

            // Corners like Kiosk Satellite's widgets: plain text with a soft shadow.
            dayClockBox = new LinearLayout(context);
            dayClockBox.setOrientation(LinearLayout.VERTICAL);
            dayClock = cornerText(context, 78, 400);
            dayClock.setIncludeFontPadding(false);
            dayDate = cornerText(context, 37, 300);
            dayDate.setTextColor(TEXT_SOFT_E6);
            dayClockBox.addView(dayClock);
            dayClockBox.addView(dayDate, gapBelow(2));
            LayoutParams clockParams = new LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT);
            clockParams.gravity = Gravity.TOP | Gravity.LEFT;
            clockParams.leftMargin = (int) ref(EDGE);
            clockParams.topMargin = (int) ref(EDGE);
            addView(dayClockBox, clockParams);

            dewBox = new LinearLayout(context);
            dewBox.setOrientation(LinearLayout.VERTICAL);
            // Filled in setWeather: dew point and the words "Dew point" on one line, humidity under it.
            LayoutParams dewParams = new LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT);
            dewParams.gravity = Gravity.BOTTOM | Gravity.LEFT;
            dewParams.leftMargin = (int) ref(EDGE);
            dewParams.bottomMargin = (int) ref(EDGE);
            addView(dewBox, dewParams);

            dayWeather = new LinearLayout(context);
            dayWeather.setOrientation(LinearLayout.VERTICAL);
            dayWeather.setGravity(Gravity.RIGHT);
            LayoutParams dwParams = new LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT);
            dwParams.gravity = Gravity.BOTTOM | Gravity.RIGHT;
            dwParams.rightMargin = (int) ref(EDGE);
            dwParams.bottomMargin = (int) ref(EDGE);
            addView(dayWeather, dwParams);

            // Dark-room screen, like Weather Mood: animated sky, big clock and date, weather buttons along the bottom.
            nightLayer = new FrameLayout(context);
            nightLayer.setVisibility(GONE);
            sky = new SkyView(context);
            nightLayer.addView(sky, new LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT));
            LinearLayout middle = new LinearLayout(context);
            middle.setOrientation(LinearLayout.VERTICAL);
            middle.setGravity(Gravity.CENTER_HORIZONTAL);
            clock = new TextView(context);
            theme.styleText(clock, 104, 300, "onSurface");
            clock.setTextColor(TEXT);
            clock.setIncludeFontPadding(false);
            date = new TextView(context);
            theme.styleText(date, 24, 400, "onSurface");
            date.setTextColor(TEXT_SOFT_E6);
            middle.addView(clock);
            middle.addView(date);
            LayoutParams middleParams = new LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT);
            middleParams.gravity = Gravity.CENTER;
            nightLayer.addView(middle, middleParams);
            wxLeft = row(context);
            LayoutParams leftParams = new LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT);
            leftParams.gravity = Gravity.BOTTOM | Gravity.LEFT;
            leftParams.leftMargin = dp(12);
            leftParams.bottomMargin = dp(12);
            nightLayer.addView(wxLeft, leftParams);
            wxRight = row(context);
            LayoutParams rightParams = new LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT);
            rightParams.gravity = Gravity.BOTTOM | Gravity.RIGHT;
            rightParams.rightMargin = dp(12);
            rightParams.bottomMargin = dp(12);
            nightLayer.addView(wxRight, rightParams);
            addView(nightLayer, new LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT));

            topRow = new FlowRow(context, dp(8), dp(8));
            column.addView(
                topRow,
                new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                )
            );
            lightsRow = new FlowRow(context, dp(8), dp(8));
            LinearLayout.LayoutParams lightsParams = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            );
            lightsParams.topMargin = dp(10);
            column.addView(lightsRow, lightsParams);

            hint = new TextView(context);
            hint.setText("");
            hint.setVisibility(View.GONE); // everyone knows: middle closes, sides change photos
            theme.styleText(hint, 12, 400, "onSurface");
            hint.setTextColor(TEXT_DIM);
            LinearLayout.LayoutParams hintParams = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            );
            hintParams.topMargin = dp(10);
            column.addView(hint, hintParams);

            // Taps on the photo itself: right third = next, left third = previous, middle = close.
            // Acts on the tap at once; chips handle their own taps.
            GestureDetector taps = new GestureDetector(
                context,
                new GestureDetector.SimpleOnGestureListener() {
                    @Override
                    public boolean onDown(MotionEvent e) {
                        return true;
                    }

                    @Override
                    public boolean onSingleTapUp(MotionEvent e) {
                        try {
                            float third = getWidth() / 3f;
                            if (e.getX() > 2 * third) step(1);
                            else if (e.getX() < third) step(-1);
                            else plugin.closeScreensaver();
                        } catch (Throwable error) {
                            plugin.log("Photo tap: " + error);
                        }
                        return true;
                    }
                }
            );
            setOnTouchListener((v, e) -> taps.onTouchEvent(e));
        }

        int dp(float v) {
            return theme.px(v);
        }

        /**
         * Sizes for the corners, measured on Kiosk Satellite's own widgets in a screenshot 800 pixels tall, scaled
         * to this screen so they look the same on every tablet.
         */
        float ref(float px) {
            android.util.DisplayMetrics m = getResources().getDisplayMetrics();
            return (px * Math.min(m.widthPixels, m.heightPixels)) / 800f;
        }

        private TextView cornerText(Context context, float refPx, int weight) {
            TextView t = shadowed(context, 12, weight);
            t.setTextSize(android.util.TypedValue.COMPLEX_UNIT_PX, ref(refPx));
            // No built-in font padding, so the edge distance and the gaps between lines are only what we set.
            t.setIncludeFontPadding(false);
            return t;
        }

        /** Layout params for a corner line with a fixed gap above it (reference pixels, see {@link #ref}). */
        private LinearLayout.LayoutParams gapBelow(float refPx) {
            LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            );
            p.topMargin = (int) ref(refPx);
            return p;
        }

        /**
         * Keeps the chips column clear of the bottom corners: its side margins are the wider bottom corner plus a
         * gap, the same on both sides so the chips stay centered.
         */
        @Override
        protected void onMeasure(int widthSpec, int heightSpec) {
            int unspecified = MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED);
            int corner = 0;
            for (View v : new View[] { dewBox, dayWeather }) {
                if (v.getVisibility() == GONE) continue;
                v.measure(unspecified, unspecified);
                corner = Math.max(corner, v.getMeasuredWidth());
            }
            int side = (int) ref(EDGE) + corner + dp(16);
            // Never squeeze the chips below half the screen; on a narrow screen they may then sit above a corner.
            side = Math.min(side, MeasureSpec.getSize(widthSpec) / 4);
            LayoutParams p = (LayoutParams) content.getLayoutParams();
            if (p.leftMargin != side) {
                p.leftMargin = side;
                p.rightMargin = side;
            }
            super.onMeasure(widthSpec, heightSpec);
        }

        private PhotoView photoView(Context context) {
            PhotoView image = new PhotoView(context);
            image.setAlpha(0f);
            return image;
        }

        private LinearLayout row(Context context) {
            LinearLayout row = new LinearLayout(context);
            row.setOrientation(LinearLayout.HORIZONTAL);
            row.setGravity(Gravity.CENTER_VERTICAL);
            return row;
        }

        boolean nightShown() {
            return nightLayer.getVisibility() == VISIBLE;
        }

        private TextView shadowed(Context context, float sp, int weight) {
            TextView t = new TextView(context);
            theme.styleText(t, sp, weight, "onSurface");
            t.setTextColor(TEXT);
            t.setShadowLayer(dp(6), 0, dp(1), 0xCC000000);
            return t;
        }

        void updateClock() {
            java.util.Date now = new java.util.Date();
            String time = new java.text.SimpleDateFormat("h:mm a", java.util.Locale.US).format(now);
            String day = new java.text.SimpleDateFormat("EEEE, MMMM d", java.util.Locale.US).format(now);
            clock.setText(time);
            date.setText(day);
            dayClock.setText(time);
            dayDate.setText(new java.text.SimpleDateFormat("EEE, MMM d", java.util.Locale.US).format(now));
        }

        /** Weather Mood style buttons: condition and temperature on the left; high/low, humidity, wind on the right. */
        void setWeather(Map<String, Object> w, double sunElevation) {
            String state = Net.str(w.get("state"));
            Object cloud = w.get("cloud");
            Object windSpeed = w.get("wind");
            sky.setWind(windSpeed instanceof Number ? ((Number) windSpeed).doubleValue() : 0);
            sky.setWeather(state, sunElevation, cloud instanceof Number ? ((Number) cloud).doubleValue() : 0);
            wxLeft.removeAllViews();
            wxRight.removeAllViews();
            dayWeather.removeAllViews();
            dewBox.setVisibility(GONE);
            if (state.isEmpty() || "unavailable".equals(state)) return;
            // Photo screen corners, like Kiosk Satellite's widgets.
            {
                String u = Net.str(w.get("unit")).replace("°", "");
                Object tt = w.get("temp");
                if (tt instanceof Number) dayWeather.addView(
                    iconLine(
                        Math.round(((Number) tt).doubleValue()) + "°" + u,
                        conditionIcon(state),
                        56,
                        52,
                        conditionColor(state),
                        300
                    )
                );
                dayWeather.addView(iconLine(conditionName(state), conditionIcon(state), 27, 22, TEXT, 300), lineGap());
                Double h = plugin.forecastHigh(), l = plugin.forecastLow();
                if (h != null && l != null) dayWeather.addView(
                    iconLine(Math.round(h) + "° / " + Math.round(l) + "°", "mdi:thermometer", 27, 22, TEXT, 300),
                    lineGap()
                );
                Object ws = w.get("wind");
                if (ws instanceof Number) dayWeather.addView(
                    iconLine(
                        Math.round(((Number) ws).doubleValue()) + " " + Net.str(w.get("wind_unit")),
                        "mdi:weather-windy",
                        27,
                        22,
                        TEXT,
                        300
                    ),
                    lineGap()
                );
                Object dew = w.get("dew"), hum = w.get("hum");
                if (dew instanceof Number) {
                    double d = ((Number) dew).doubleValue();
                    boolean celsius = u.toUpperCase(java.util.Locale.US).contains("C");
                    int color = dewColor(celsius ? (d * 9) / 5 + 32 : d);
                    if (plugin.softText()) color = dim(color, 0.7f);
                    dewBox.removeAllViews();
                    // Line 1: icon, the dew point in its comfort color, then the words "Dew point" smaller.
                    String number = Math.round(d) + " °" + u;
                    android.text.SpannableString text = new android.text.SpannableString(number + "  Dew point");
                    text.setSpan(
                        new android.text.style.ForegroundColorSpan(color),
                        0,
                        number.length(),
                        android.text.Spannable.SPAN_EXCLUSIVE_EXCLUSIVE
                    );
                    text.setSpan(
                        new android.text.style.AbsoluteSizeSpan((int) ref(17)),
                        number.length(),
                        text.length(),
                        android.text.Spannable.SPAN_EXCLUSIVE_EXCLUSIVE
                    );
                    text.setSpan(
                        new android.text.style.ForegroundColorSpan(TEXT_SOFT_CC),
                        number.length(),
                        text.length(),
                        android.text.Spannable.SPAN_EXCLUSIVE_EXCLUSIVE
                    );
                    TextView value = iconLine("", "mdi:water-thermometer", 34, 30, color, 400, true);
                    value.setText(text);
                    dewBox.addView(value);
                    // Line 2: the humidity, starting under the number (past the icon).
                    if (hum instanceof Number) {
                        TextView humidity = cornerText(getContext(), 17, 400);
                        humidity.setTextColor(TEXT_SOFT_CC);
                        humidity.setText(Math.round(((Number) hum).doubleValue()) + "% humidity");
                        humidity.setPadding((int) ref(30) + (int) ref(8), 0, 0, 0);
                        dewBox.addView(humidity, gapBelow(4));
                    }
                    dewBox.setVisibility(nightShown() ? GONE : VISIBLE);
                }
            }
            String unit = Net.str(w.get("unit")).replace("°", "");
            Object temp = w.get("temp");
            String t = temp instanceof Number ? Math.round(((Number) temp).doubleValue()) + "°" + unit + "  " : "";
            wxLeft.addView(chip(t + conditionName(state), conditionIcon(state), CHIP, TEXT));
            Double hi = plugin.forecastHigh(), lo = plugin.forecastLow();
            if (hi != null && lo != null) addRight(
                chip(Math.round(hi) + "° / " + Math.round(lo) + "°", "mdi:thermometer", CHIP, TEXT)
            );
            Object hum = w.get("hum");
            if (hum instanceof Number) addRight(
                chip(Math.round(((Number) hum).doubleValue()) + "%", "mdi:water-outline", CHIP, TEXT)
            );
            Object wind = w.get("wind");
            if (wind instanceof Number) addRight(
                chip(
                    Math.round(((Number) wind).doubleValue()) + " " + Net.str(w.get("wind_unit")),
                    "mdi:weather-windy",
                    CHIP,
                    TEXT
                )
            );
        }

        /** The gap above each smaller line of the weather corner. */
        private LinearLayout.LayoutParams lineGap() {
            return gapBelow(6);
        }

        /** "56°F" with an icon after it, right-aligned: one line of the weather corner. */
        private TextView iconLine(String text, String icon, float refPx, int iconRef, int iconColor, int weight) {
            return iconLine(text, icon, refPx, iconRef, iconColor, weight, false);
        }

        /**
         * One corner line with an icon. {@code left} = icon before the text, left-aligned (the dew point corner,
         * a mirror of the weather corner); otherwise icon after the text, right-aligned.
         */
        private TextView iconLine(
            String text,
            String icon,
            float refPx,
            int iconRef,
            int iconColor,
            int weight,
            boolean left
        ) {
            TextView line = cornerText(getContext(), refPx, weight);
            line.setText(text);
            line.setGravity((left ? Gravity.LEFT : Gravity.RIGHT) | Gravity.CENTER_VERTICAL);
            try {
                Drawable d = theme.icon(icon, "onSurface");
                if (d != null) {
                    int size = (int) ref(iconRef);
                    d.setBounds(0, 0, size, size);
                    d.setTint(iconColor);
                    if (left) line.setCompoundDrawables(d, null, null, null);
                    else line.setCompoundDrawables(null, null, d, null);
                    line.setCompoundDrawablePadding((int) ref(refPx > 40 ? 12 : 8));
                }
            } catch (Throwable ignored) {}
            return line;
        }

        private void addRight(View chip) {
            LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            );
            if (wxRight.getChildCount() > 0) params.leftMargin = dp(10);
            wxRight.addView(chip, params);
        }

        void setNight(boolean night) {
            boolean ours = night && plugin.nightScreensaver();
            nightLayer.setVisibility(ours ? VISIBLE : GONE);
            content.setVisibility(ours ? GONE : VISIBLE);
            int corners = ours ? GONE : VISIBLE;
            dayClockBox.setVisibility(corners);
            dayWeather.setVisibility(corners);
            if (ours) dewBox.setVisibility(GONE);
            if (ours) updateClock();
            front.animate().alpha(night ? 0f : 1f).setDuration(800).start();
            back.animate().alpha(0f).setDuration(800).start();
            scrim.setAlpha(night ? 0f : 1f);
            float content = night ? 0.6f : 1f;
            topRow.setAlpha(content);
            lightsRow.setAlpha(content);
            hint.setAlpha(night ? 0.5f : 1f);
        }

        /** Crossfade: the hidden view gets the new photo and fades in while the shown one fades out. */
        void showPhoto(Bitmap bitmap) {
            if (dark) return;
            PhotoView incoming = back, outgoing = front;
            incoming.setPhoto(bitmap);
            incoming.animate().cancel();
            incoming.setAlpha(0f);
            incoming.animate().alpha(1f).setDuration(1200).start();
            outgoing.animate().cancel();
            outgoing.animate().alpha(0f).setDuration(1200).withEndAction(() -> outgoing.setPhoto(null)).start();
            back = outgoing;
            front = incoming;
        }

        // ----- Chips -----

        /** A pill like Weather Mood's: dark, thin outline, the icon in a small ring on the left. */
        private TextView chip(String text, String icon, int background, int foreground) {
            TextView chip = new TextView(getContext());
            chip.setText(text);
            chip.setSingleLine(true);
            chip.setEllipsize(TextUtils.TruncateAt.END);
            chip.setMaxWidth(dp(240));
            theme.styleText(chip, 18, 500, "onSurface");
            chip.setTextColor(foreground);
            chip.setGravity(Gravity.CENTER_VERTICAL);
            chip.setIncludeFontPadding(false);
            int shift = textShift(chip);
            chip.setPadding(dp(icon == null ? 18 : 8), dp(8) + shift, dp(18), dp(8) - shift);
            chip.setBackground(pill(background));
            setIcon(chip, icon, foreground);
            return chip;
        }

        /**
         * How far to move a chip's text down so its capitals and digits sit in the middle of the pill, level with
         * the icon. Android centers the whole line, including the room kept below for descenders (g, y), which
         * leaves capitals and digits a little high.
         */
        private int textShift(TextView chip) {
            Paint.FontMetrics fm = chip.getPaint().getFontMetrics();
            Rect caps = new Rect();
            chip.getPaint().getTextBounds("H0", 0, 2, caps);
            return Math.round((fm.ascent + fm.descent - caps.top - caps.bottom) / 2f);
        }

        private GradientDrawable pill(int background) {
            GradientDrawable bg = new GradientDrawable();
            bg.setColor(background);
            bg.setCornerRadius(dp(28));
            bg.setStroke(Math.max(1, dp(1)), background == CHIP ? 0x40FFFFFF : 0x30000000);
            return bg;
        }

        private void setIcon(TextView chip, String icon, int foreground) {
            if (icon == null) return;
            try {
                Drawable d = theme.icon(icon, "onSurface");
                if (d == null) return;
                d.setTint(foreground);
                Drawable ringed = new RingIcon(d, foreground, dp(34), dp(18), Math.max(1, dp(1)));
                int shift = textShift(chip); // the padding moved the icon too; put it back in the middle
                ringed.setBounds(0, -shift, dp(34), dp(34) - shift);
                chip.setCompoundDrawables(ringed, null, null, null);
                chip.setCompoundDrawablePadding(dp(10));
            } catch (Throwable ignored) {}
        }

        /** Faces first, then climate, garage, alarm and alerts; the row wraps when it's too wide. */
        private void rebuildTop() {
            topRow.removeAllViews();
            for (View v : people) topRow.addView(v);
            for (View v : status) topRow.addView(v);
        }

        void setPeople(List<Object> list) {
            people.clear();
            for (Object o : list) {
                Map<String, Object> p = Net.map(o);
                String name = Net.str(p.get("name"));
                boolean home = Boolean.TRUE.equals(p.get("home"));
                CircleImage face = new CircleImage(getContext());
                face.setInitial(name.isEmpty() ? "?" : name.substring(0, 1));
                face.setAway(!home);
                loadAvatar(Net.str(p.get("pic")), face);
                face.setLayoutParams(new android.view.ViewGroup.LayoutParams(dp(46), dp(46)));
                face.setMinimumWidth(dp(46));
                face.setMinimumHeight(dp(46));
                people.add(face);
            }
        }

        void setStatus(String garage, String alarm, List<Object> alerts, Map<String, Object> climate) {
            status.clear();
            Object temp = climate.get("temp"), hum = climate.get("hum");
            if (temp instanceof Number) {
                String text =
                    "Inside " +
                    ((Number) temp).intValue() +
                    "°" +
                    (hum instanceof Number ? "  ·  " + ((Number) hum).intValue() + "%" : "");
                status.add(chip(text, "mdi:home-thermometer-outline", CHIP, TEXT));
            }
            String mode = Net.str(climate.get("mode"));
            Object target = climate.get("target");
            if (!mode.isEmpty() && !"off".equals(mode) && !"unavailable".equals(mode) && target instanceof Number) {
                String action = Net.str(climate.get("action"));
                String verb = "heating".equals(action)
                    ? "Heating to "
                    : "cooling".equals(action) ? "Cooling to " : capital(mode.replace("_", " ")) + " ";
                int color = "heating".equals(action) ? 0xFFE65100 : "cooling".equals(action) ? 0xFF1565C0 : CHIP;
                status.add(
                    chip(verb + Math.round(((Number) target).doubleValue()) + "°", "mdi:thermostat", color, TEXT)
                );
            }
            boolean garageOpen = "open".equals(garage) || "opening".equals(garage);
            if (!garage.isEmpty()) {
                status.add(
                    chip(
                        garageOpen ? "Garage open" : "Garage closed",
                        garageOpen ? "mdi:garage-open-variant" : "mdi:garage-variant",
                        garageOpen ? ORANGE : CHIP,
                        garageOpen ? 0xFF1A1A1A : TEXT
                    )
                );
            }
            if (!alarm.isEmpty() && !"unavailable".equals(alarm) && !"unknown".equals(alarm)) {
                boolean armed = alarm.startsWith("armed");
                boolean alert = "triggered".equals(alarm) || "pending".equals(alarm) || "arming".equals(alarm);
                String label = "triggered".equals(alarm)
                    ? "Alarm!"
                    : armed ? "Alarm " + alarm.replace("armed_", "").replace('_', ' ') : "Alarm off";
                status.add(
                    chip(
                        label,
                        armed || alert ? "mdi:shield-lock" : "mdi:shield-off-outline",
                        alert ? RED : armed ? GREEN : CHIP,
                        TEXT
                    )
                );
            }
            if (!alerts.isEmpty()) {
                String first = Net.str(Net.map(alerts.get(0)).get("name"));
                String label = alerts.size() == 1 ? first : alerts.size() + " alerts";
                status.add(chip(label, "mdi:bell-ring", AlertBanner.colorFor(Net.map(alerts.get(0))), TEXT));
            }
            rebuildTop();
        }

        void setLights(String area, List<Object> lights) {
            lightsRow.removeAllViews();
            int shown = 0;
            for (Object o : lights) {
                Map<String, Object> l = Net.map(o);
                String id = Net.str(l.get("id"));
                String name = shortName(Net.str(l.get("name")), area);
                boolean on = Boolean.TRUE.equals(l.get("on"));
                boolean unavailable = Boolean.TRUE.equals(l.get("off_line"));
                TextView chip = chip(
                    name,
                    on ? "mdi:lightbulb-on" : "mdi:lightbulb-outline",
                    on ? AMBER : NAVY,
                    on ? 0xFF1A1A1A : TEXT
                );
                if (unavailable) chip.setAlpha(0.45f);
                chip.setOnClickListener(v -> {
                    try {
                        boolean nowOn = !Boolean.TRUE.equals(v.getTag());
                        v.setTag(nowOn);
                        restyleLight((TextView) v, name, nowOn); // show it at once; HA confirms shortly
                        plugin.toggleLight(id);
                    } catch (Throwable error) {
                        plugin.log("Light tap: " + error);
                    }
                });
                chip.setTag(on);
                lightsRow.addView(chip);
                shown++;
            }
        }

        private void restyleLight(TextView chip, String name, boolean on) {
            chip.setBackground(pill(on ? AMBER : CHIP));
            int fg = on ? 0xFF1A1A1A : TEXT;
            chip.setTextColor(fg);
            setIcon(chip, on ? "mdi:lightbulb-on" : "mdi:lightbulb-outline", fg);
        }
    }

    /**
     * The whole photo, fitted to the screen, over a blurred and darkened copy of itself that fills the rest,
     * the way Kiosk Satellite's photo screensaver shows it. The blur is a tiny copy scaled up with filtering.
     */
    private static final class PhotoView extends View {

        private Bitmap photo, blur;
        private final Paint paint = new Paint(Paint.FILTER_BITMAP_FLAG | Paint.ANTI_ALIAS_FLAG);
        private final Paint shade = new Paint();
        private final Matrix matrix = new Matrix();

        PhotoView(Context context) {
            super(context);
            shade.setColor(0x8C000000);
        }

        void setPhoto(Bitmap b) {
            photo = b;
            blur = null;
            if (b != null) {
                int w = 24, h = Math.max(1, Math.round((24f * b.getHeight()) / b.getWidth()));
                Bitmap tiny = Bitmap.createScaledBitmap(b, w, h, true);
                blur = Bitmap.createScaledBitmap(tiny, w * 4, h * 4, true); // a little smoother when stretched
                if (tiny != blur) tiny.recycle();
            }
            invalidate();
        }

        @Override
        protected void onDraw(Canvas canvas) {
            int w = getWidth(), h = getHeight();
            if (photo == null || w == 0 || h == 0) return;
            float cover = Math.max((float) w / blur.getWidth(), (float) h / blur.getHeight());
            matrix.setScale(cover, cover);
            matrix.postTranslate((w - blur.getWidth() * cover) / 2f, (h - blur.getHeight() * cover) / 2f);
            canvas.drawBitmap(blur, matrix, paint);
            canvas.drawRect(0, 0, w, h, shade);
            float fit = Math.min((float) w / photo.getWidth(), (float) h / photo.getHeight());
            matrix.setScale(fit, fit);
            matrix.postTranslate((w - photo.getWidth() * fit) / 2f, (h - photo.getHeight() * fit) / 2f);
            canvas.drawBitmap(photo, matrix, paint);
        }
    }

    /** Lays children out left to right and wraps to a new line when full; every line is centered. */
    private static final class FlowRow extends android.view.ViewGroup {

        private final int gapX, gapY;

        FlowRow(Context context, int gapX, int gapY) {
            super(context);
            this.gapX = gapX;
            this.gapY = gapY;
        }

        @Override
        protected void onMeasure(int widthSpec, int heightSpec) {
            int max = MeasureSpec.getSize(widthSpec);
            if (MeasureSpec.getMode(widthSpec) == MeasureSpec.UNSPECIFIED) max = Integer.MAX_VALUE;
            int lineW = 0, lineH = 0, width = 0, height = 0;
            for (int i = 0; i < getChildCount(); i++) {
                View c = getChildAt(i);
                if (c.getVisibility() == GONE) continue;
                c.measure(MeasureSpec.makeMeasureSpec(max, MeasureSpec.AT_MOST), MeasureSpec.UNSPECIFIED);
                int w = c.getMeasuredWidth(), h = c.getMeasuredHeight();
                if (lineW > 0 && lineW + gapX + w > max) {
                    width = Math.max(width, lineW);
                    height += lineH + gapY;
                    lineW = 0;
                    lineH = 0;
                }
                lineW += (lineW > 0 ? gapX : 0) + w;
                lineH = Math.max(lineH, h);
            }
            width = Math.max(width, lineW);
            height += lineH;
            setMeasuredDimension(
                MeasureSpec.getMode(widthSpec) == MeasureSpec.EXACTLY ? max : Math.min(width, max),
                height
            );
        }

        @Override
        protected void onLayout(boolean changed, int l, int t, int r, int b) {
            int max = r - l;
            java.util.List<View> line = new ArrayList<>();
            int lineW = 0, lineH = 0, y = 0;
            for (int i = 0; i <= getChildCount(); i++) {
                View c = i < getChildCount() ? getChildAt(i) : null;
                if (c != null && c.getVisibility() == GONE) continue;
                boolean flush = c == null || (lineW > 0 && lineW + gapX + c.getMeasuredWidth() > max);
                if (flush && !line.isEmpty()) {
                    int x = (max - lineW) / 2;
                    for (View v : line) {
                        int top = y + (lineH - v.getMeasuredHeight()) / 2;
                        v.layout(x, top, x + v.getMeasuredWidth(), top + v.getMeasuredHeight());
                        x += v.getMeasuredWidth() + gapX;
                    }
                    y += lineH + gapY;
                    line.clear();
                    lineW = 0;
                    lineH = 0;
                }
                if (c == null) break;
                lineW += (lineW > 0 ? gapX : 0) + c.getMeasuredWidth();
                lineH = Math.max(lineH, c.getMeasuredHeight());
                line.add(c);
            }
        }
    }

    /** An icon centered in a thin ring, like the Weather Mood buttons. */
    private static final class RingIcon extends Drawable {

        private final Drawable icon;
        private final Paint ring = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final int size, iconSize;

        RingIcon(Drawable icon, int color, int size, int iconSize, int stroke) {
            this.icon = icon;
            this.size = size;
            this.iconSize = iconSize;
            ring.setStyle(Paint.Style.STROKE);
            ring.setStrokeWidth(stroke);
            ring.setColor((color & 0x00FFFFFF) | 0x66000000);
        }

        @Override
        public void draw(Canvas canvas) {
            android.graphics.Rect b = getBounds();
            float r = Math.min(b.width(), b.height()) / 2f;
            canvas.drawCircle(b.exactCenterX(), b.exactCenterY(), r - ring.getStrokeWidth(), ring);
            int l = (int) (b.exactCenterX() - iconSize / 2f), t = (int) (b.exactCenterY() - iconSize / 2f);
            icon.setBounds(l, t, l + iconSize, t + iconSize);
            icon.draw(canvas);
        }

        @Override
        public int getIntrinsicWidth() {
            return size;
        }

        @Override
        public int getIntrinsicHeight() {
            return size;
        }

        @Override
        public void setAlpha(int alpha) {
            ring.setAlpha(alpha);
            icon.setAlpha(alpha);
        }

        @Override
        public void setColorFilter(android.graphics.ColorFilter filter) {
            icon.setColorFilter(filter);
        }

        @Override
        public int getOpacity() {
            return android.graphics.PixelFormat.TRANSLUCENT;
        }
    }

    static int dim(int color, float f) {
        int r = (int) (((color >> 16) & 255) * f), g = (int) (((color >> 8) & 255) * f), b = (int) ((color & 255) * f);
        return 0xFF000000 | (r << 16) | (g << 8) | b;
    }

    /** Dew point comfort colors (°F): dry and comfortable blues/greens up to muggy reds. */
    static int dewColor(double f) {
        if (f < 50) return 0xFF82C8FF;
        if (f < 55) return 0xFF6FD08C;
        if (f < 60) return 0xFFB5D96A;
        if (f < 65) return 0xFFF2D45C;
        if (f < 70) return 0xFFF5A04A;
        if (f < 75) return 0xFFEF5350;
        return 0xFFC62828;
    }

    /** Weather icon colors like Kiosk Satellite's colored icons. */
    static int conditionColor(String state) {
        switch (state) {
            case "clear-night":
                return 0xFF8AB4F8;
            case "sunny":
                return 0xFFFFC94D;
            case "partlycloudy":
                return 0xFFFFD580;
            case "rainy":
            case "pouring":
                return 0xFF64B5F6;
            case "lightning":
            case "lightning-rainy":
                return 0xFFFFD54F;
            case "snowy":
            case "snowy-rainy":
                return 0xFFE3F2FD;
            default:
                return 0xFFCFD8DC;
        }
    }

    static String conditionName(String state) {
        switch (state) {
            case "clear-night":
                return "Clear night";
            case "sunny":
                return "Sunny";
            case "partlycloudy":
                return "Partly cloudy";
            case "cloudy":
                return "Cloudy";
            case "fog":
                return "Fog";
            case "hail":
                return "Hail";
            case "lightning":
            case "lightning-rainy":
                return "Storms";
            case "pouring":
                return "Heavy rain";
            case "rainy":
                return "Rain";
            case "snowy":
                return "Snow";
            case "snowy-rainy":
                return "Sleet";
            case "windy":
            case "windy-variant":
                return "Windy";
            case "exceptional":
                return "Severe";
            default:
                return capital(state.replace('-', ' '));
        }
    }

    static String conditionIcon(String state) {
        switch (state) {
            case "clear-night":
                return "mdi:weather-night";
            case "sunny":
                return "mdi:weather-sunny";
            case "partlycloudy":
                return "mdi:weather-partly-cloudy";
            case "cloudy":
                return "mdi:weather-cloudy";
            case "fog":
                return "mdi:weather-fog";
            case "hail":
                return "mdi:weather-hail";
            case "lightning":
                return "mdi:weather-lightning";
            case "lightning-rainy":
                return "mdi:weather-lightning-rainy";
            case "pouring":
                return "mdi:weather-pouring";
            case "rainy":
                return "mdi:weather-rainy";
            case "snowy":
                return "mdi:weather-snowy";
            case "snowy-rainy":
                return "mdi:weather-snowy-rainy";
            case "windy":
            case "windy-variant":
                return "mdi:weather-windy";
            default:
                return "mdi:weather-cloudy";
        }
    }

    static String capital(String s) {
        return s.isEmpty() ? s : Character.toUpperCase(s.charAt(0)) + s.substring(1);
    }

    /** "Living Room Ceiling Light" in Living Room -> "Ceiling". */
    static String shortName(String name, String area) {
        String n = name;
        if (!area.isEmpty() && n.toLowerCase().startsWith(area.toLowerCase() + " ")) n = n.substring(area.length() + 1);
        if (n.toLowerCase().endsWith(" light") && n.length() > 6) n = n.substring(0, n.length() - 6);
        return n.isEmpty() ? name : n;
    }

    static Map<String, Object> emptyData() {
        return new LinkedHashMap<>();
    }
}
