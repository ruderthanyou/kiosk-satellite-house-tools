// SPDX-License-Identifier: MIT
package io.github.ruderthanyou.housetools;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapShader;
import android.graphics.Canvas;
import android.graphics.Matrix;
import android.graphics.LightingColorFilter;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RadialGradient;
import android.graphics.RectF;
import android.graphics.Shader;
import android.os.SystemClock;
import android.view.View;
import java.util.Random;

/**
 * An animated sky for the night screensaver: colors follow the sun's height, stars twinkle on clear nights,
 * the moon shows its real phase, and clouds, rain or snow follow the current weather. Redraws about 12 times
 * a second only while it is on screen.
 */
final class SkyView extends View {
    private static final int STARS = 220;
    private final float[] sx = new float[STARS], sy = new float[STARS], ss = new float[STARS], sp = new float[STARS];
    // Clouds: pre-drawn puffy cloud bitmaps in three depth layers, drifting at different speeds.
    private static final int CLOUDS = 9;
    private final Bitmap[] cloudArt = new Bitmap[CLOUDS];
    private final float[] cloudX = new float[CLOUDS], cloudY = new float[CLOUDS], cloudSpeed = new float[CLOUDS];
    private final int[] cloudLayer = new int[CLOUDS];
    private int cloudsBuiltFor; // width the bitmaps were made for
    // Rain: three layers (far, mid, near).
    private static final int DROPS = 240;
    private final float[] dropX = new float[DROPS], dropY = new float[DROPS], dropLen = new float[DROPS];
    private final int[] dropLayer = new int[DROPS];
    private double windSpeed;
    private double sunAzimuth = 180;
    private long nextFlash, flashUntil;
    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Random random = new Random(7);
    private final float density;

    private String condition = "clear-night";
    private double sunElevation = -30;
    private double cloudCover = 0;
    private boolean running;
    private Bitmap moonPhoto; // NASA's image of the moon for this hour, if downloaded

    SkyView(Context context) {
        super(context);
        density = context.getResources().getDisplayMetrics().density;
        for (int i = 0; i < STARS; i++) {
            sx[i] = random.nextFloat();
            sy[i] = random.nextFloat() * 0.8f;
            ss[i] = 0.6f + random.nextFloat() * 1.4f;
            sp[i] = random.nextFloat() * 6.28f;
        }
        for (int i = 0; i < CLOUDS; i++) {
            cloudLayer[i] = i % 3;                                   // 0 far, 1 mid, 2 near
            cloudX[i] = random.nextFloat() * 1.4f - 0.2f;
            cloudY[i] = 0.02f + random.nextFloat() * 0.42f + cloudLayer[i] * 0.04f;
            cloudSpeed[i] = 0.0025f + cloudLayer[i] * 0.003f + random.nextFloat() * 0.0015f;
        }
        for (int i = 0; i < DROPS; i++) {
            dropLayer[i] = i % 3;
            dropX[i] = random.nextFloat();
            dropY[i] = random.nextFloat();
            dropLen[i] = 0.6f + random.nextFloat() * 0.8f;
        }
    }

    void setMoonPhoto(Bitmap photo) { moonPhoto = photo; invalidate(); }

    void setWind(double speed) { windSpeed = speed; }
    void setSunAzimuth(double azimuth) { sunAzimuth = azimuth; }

    void setWeather(String condition, double sunElevation, double cloudCover) {
        this.condition = condition == null || condition.isEmpty() ? "clear-night" : condition;
        this.sunElevation = sunElevation;
        this.cloudCover = cloudCover;
        invalidate();
    }

    @Override protected void onWindowVisibilityChanged(int visibility) {
        super.onWindowVisibilityChanged(visibility);
        running = visibility == VISIBLE;
        if (running) postInvalidateOnAnimation();
    }

    @Override protected void onDetachedFromWindow() {
        running = false;
        super.onDetachedFromWindow();
    }

    private boolean cloudy() {
        return condition.contains("cloud") || condition.equals("rainy") || condition.equals("pouring")
            || condition.equals("snowy") || condition.equals("snowy-rainy") || condition.equals("fog")
            || condition.startsWith("lightning") || cloudCover >= 60;
    }

    @Override protected void onDraw(Canvas canvas) {
        int w = getWidth(), h = getHeight();
        if (w == 0 || h == 0) return;
        float t = SystemClock.uptimeMillis() / 1000f;
        boolean night = sunElevation < -6;

        // Sky gradient by the sun's height: night, twilight, day (kept dark: this is the dark-room screensaver).
        int top, bottom;
        if (sunElevation < -12) { top = 0xFF03050B; bottom = 0xFF0B1224; }        // night
        else if (sunElevation < -4) { top = 0xFF0A1230; bottom = 0xFF4A2A3A; }    // dusk / dawn
        else if (sunElevation < 6) { top = 0xFF1C3A6B; bottom = 0xFFB0603E; }     // golden hour
        else { top = 0xFF1A4A86; bottom = 0xFF5D8EC4; }                           // day, soft for a dark room
        if (cloudy()) { top = mix(top, 0xFF15181E, 0.5f); bottom = mix(bottom, 0xFF22262E, 0.5f); }
        paint.setShader(new LinearGradient(0, 0, 0, h, top, bottom, Shader.TileMode.CLAMP));
        canvas.drawRect(0, 0, w, h, paint);
        paint.setShader(null);

        // Stars on clear nights, twinkling.
        if (night && !cloudy()) {
            for (int i = 0; i < STARS; i++) {
                float a = 0.35f + 0.65f * (0.5f + 0.5f * (float) Math.sin(t * (0.6 + (i % 5) * 0.25) + sp[i]));
                paint.setColor(((int) (a * 230) << 24) | 0xFFFFFF);
                canvas.drawCircle(sx[i] * w, sy[i] * h, ss[i] * density * 0.8f, paint);
            }
        }

        // Moon at night (top right), sun glow by day.
        float mx = w * 0.84f, my = h * 0.22f, mr = 34 * density;
        if (night) drawMoon(canvas, mx, my, mr);
        else if (sunElevation > -4) {
            // Where the sun really is: east (left) to west (right) by azimuth, height by elevation.
            float sxp = (float) (0.08 + 0.84 * Math.max(0, Math.min(1, (sunAzimuth - 90) / 180.0))) * w;
            float syp = (float) (0.80 - 0.62 * Math.max(0, Math.min(1, sunElevation / 60.0))) * h;
            boolean low = sunElevation < 8;
            int glow = low ? 0x88FFB060 : 0x77FFE9A8;
            paint.setShader(new RadialGradient(sxp, syp, mr * 5, glow, glow & 0x00FFFFFF, Shader.TileMode.CLAMP));
            canvas.drawCircle(sxp, syp, mr * 5, paint);
            paint.setShader(null);
            if (!cloudy()) {
                paint.setColor(low ? 0xFFFFD08A : 0xFFFFF4D6);
                canvas.drawCircle(sxp, syp, mr * 0.9f, paint);
            }
        }

        // Clouds: puffy, lit from above, in three layers.
        boolean partly = condition.contains("partly");
        if (cloudy() || partly) {
            if (cloudsBuiltFor != w) buildClouds(w);
            int shown = partly ? 4 : CLOUDS;
            boolean dim = sunElevation < -6;
            paint.setColorFilter(dim ? null : new LightingColorFilter(0xFFFFFFFF, sunElevation < 6 ? 0x3A2A20 : 0x505050)); // daylight
            for (int i = 0; i < shown; i++) {
                Bitmap art = cloudArt[i];
                if (art == null) continue;
                float span = w + art.getWidth();
                float x = (float) (((cloudX[i] * w + t * cloudSpeed[i] * w) % span + span) % span) - art.getWidth();
                float y = cloudY[i] * h;
                paint.setAlpha(dim ? 150 + cloudLayer[i] * 30 : 200 + cloudLayer[i] * 20);
                canvas.drawBitmap(art, x, y, paint);
            }
            paint.setAlpha(255);
            paint.setColorFilter(null);
        }

        // Rain in three layers, slanted by the wind; snow drifts.
        boolean rain = condition.equals("rainy") || condition.equals("pouring") || condition.equals("lightning-rainy") || condition.equals("snowy-rainy") || condition.equals("hail");
        boolean snow = condition.equals("snowy") || condition.equals("snowy-rainy");
        if (rain || snow) {
            int count = condition.equals("pouring") ? DROPS : condition.equals("rainy") ? DROPS * 2 / 3 : DROPS / 2;
            float slant = (float) Math.min(0.45, windSpeed / 60.0); // dx per dy
            for (int i = 0; i < count; i++) {
                int layer = dropLayer[i];
                boolean flake = snow && (!rain || i % 2 == 0);
                if (flake) {
                    float fy = (float) ((dropY[i] + t * (0.03f + layer * 0.02f)) % 1.0) * h;
                    float fx = (float) ((dropX[i] + 0.015 * Math.sin(t * 0.8 + i)) % 1.0) * w;
                    paint.setColor(((90 + layer * 50) << 24) | 0xFFFFFF);
                    canvas.drawCircle(fx, fy, (1.2f + layer * 1.0f) * density, paint);
                    continue;
                }
                float speed = 0.55f + layer * 0.35f;                 // screen heights per second
                float len = (10 + layer * 10) * dropLen[i] * density;
                float y = (float) ((dropY[i] + t * speed) % 1.0) * (h + len) - len;
                float x = ((dropX[i] * (w + h * slant)) - (y + len) * slant);
                paint.setStrokeWidth((0.7f + layer * 0.5f) * density);
                paint.setColor(((40 + layer * 45) << 24) | 0xB4C8E0);
                canvas.drawLine(x, y, x - len * slant, y + len, paint);
            }
        }

        // Lightning: a soft flash every 8 to 25 seconds during storms.
        if (condition.startsWith("lightning")) {
            long now = SystemClock.uptimeMillis();
            if (nextFlash == 0) nextFlash = now + 8000 + random.nextInt(17000);
            if (now >= nextFlash) { flashUntil = now + 120 + random.nextInt(140); nextFlash = now + 8000 + random.nextInt(17000); }
            if (now < flashUntil) {
                paint.setColor(0x55DDE6FF);
                canvas.drawRect(0, 0, w, h, paint);
            }
        }

        if (running) postInvalidateDelayed(80);
    }

    /** Draw each cloud once: a cluster of soft puffs, light on top and shaded underneath. */
    private void buildClouds(int w) {
        cloudsBuiltFor = w;
        Random r = new Random(11);
        Paint puff = new Paint(Paint.ANTI_ALIAS_FLAG);
        for (int i = 0; i < CLOUDS; i++) {
            float scale = 0.55f + cloudLayer[i] * 0.3f;               // near clouds bigger
            int cw = (int) (w * (0.30f + r.nextFloat() * 0.18f) * scale), ch = (int) (cw * 0.45f);
            if (cw < 8 || ch < 8) continue;
            Bitmap art = Bitmap.createBitmap(cw, ch, Bitmap.Config.ARGB_8888);
            Canvas c = new Canvas(art);
            int puffs = 10 + r.nextInt(6);
            for (int k = 0; k < puffs; k++) {
                float px = cw * (0.15f + 0.7f * r.nextFloat());
                float base = ch * 0.62f;                               // flat-ish bottom
                float pr = ch * (0.18f + 0.22f * r.nextFloat());
                float py = base - pr * (0.2f + 0.8f * r.nextFloat());
                // shaded underside first, highlight on top
                puff.setShader(new RadialGradient(px, py + pr * 0.25f, pr, 0x99525866, 0x00525866, Shader.TileMode.CLAMP));
                c.drawCircle(px, py + pr * 0.25f, pr, puff);
                puff.setShader(new RadialGradient(px - pr * 0.1f, py - pr * 0.25f, pr * 0.85f, 0xCC9FA8B8, 0x009FA8B8, Shader.TileMode.CLAMP));
                c.drawCircle(px - pr * 0.1f, py - pr * 0.25f, pr * 0.85f, puff);
            }
            Bitmap old = cloudArt[i];
            cloudArt[i] = art;
            if (old != null) old.recycle();
        }
    }

    /**
     * The moon: NASA's photo for this hour when we have it (always drawn at the same size, cropped to the same
     * circle), otherwise a drawn moon with the real phase.
     */
    private void drawMoon(Canvas canvas, float cx, float cy, float r) {
        paint.setShader(new RadialGradient(cx, cy, r * 3.2f, 0x33DDE6F0, 0x00DDE6F0, Shader.TileMode.CLAMP));
        canvas.drawCircle(cx, cy, r * 3.2f, paint);
        paint.setShader(null);
        Bitmap photo = moonPhoto;
        if (photo != null) {
            // NASA frames are square with the moon filling about 91% of the width, centered.
            float disc = photo.getWidth() * 0.455f;
            float scale = r / disc;
            BitmapShader shader = new BitmapShader(photo, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP);
            Matrix m = new Matrix();
            m.setScale(scale, scale);
            m.postTranslate(cx - photo.getWidth() / 2f * scale, cy - photo.getHeight() / 2f * scale);
            shader.setLocalMatrix(m);
            paint.setShader(shader);
            canvas.drawCircle(cx, cy, r, paint);
            paint.setShader(null);
            return;
        }
        double phase = moonPhase(System.currentTimeMillis());
        paint.setColor(0xFF2A2F38); // the dark side, faintly visible
        canvas.drawCircle(cx, cy, r, paint);
        // Lit part: a half disc plus or minus a half ellipse whose width follows the phase.
        double k = Math.cos(phase * 2 * Math.PI); // 1 new, -1 full
        boolean waxing = phase < 0.5;
        Path lit = new Path();
        RectF disc = new RectF(cx - r, cy - r, cx + r, cy + r);
        lit.addArc(disc, waxing ? -90 : 90, 180); // right half while waxing, left while waning
        float ew = (float) Math.abs(k) * r;
        RectF ellipse = new RectF(cx - ew, cy - r, cx + ew, cy + r);
        Path terminator = new Path();
        terminator.addOval(ellipse, Path.Direction.CW);
        if (k > 0) lit.op(terminator, Path.Op.DIFFERENCE); // crescent
        else lit.op(terminator, Path.Op.UNION);            // gibbous
        paint.setColor(0xFFE4E7EC);
        canvas.drawPath(lit, paint);
    }

    /** Days since a known new moon, as a fraction of the synodic month. */
    static double moonPhase(long millis) {
        double days = (millis - 947182440000L) / 86400000.0; // new moon 2000-01-06 18:14 UTC
        double p = (days % 29.530588853) / 29.530588853;
        return p < 0 ? p + 1 : p;
    }

    private static int mix(int a, int b, float f) {
        int ar = (a >> 16) & 255, ag = (a >> 8) & 255, ab = a & 255;
        int br = (b >> 16) & 255, bg = (b >> 8) & 255, bb = b & 255;
        return 0xFF000000 | ((int) (ar + (br - ar) * f) << 16) | ((int) (ag + (bg - ag) * f) << 8) | (int) (ab + (bb - ab) * f);
    }
}
