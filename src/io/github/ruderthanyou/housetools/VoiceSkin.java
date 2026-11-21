// SPDX-License-Identifier: MIT
package io.github.ruderthanyou.housetools;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.PathMeasure;
import android.graphics.RadialGradient;
import android.graphics.Shader;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.SystemClock;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import me.jxl.kiosk.plugins.KsTheme;
import me.jxl.kiosk.plugins.OverlayFactory;
import me.jxl.kiosk.plugins.OverlaySpec;
import me.jxl.kiosk.plugins.PluginHost;

/**
 * The House Tools voice skin: Kiosk Satellite's voice overlay in the plugin's own look. Pick "House Tools" under
 * Voice Satellite > Appearance > Assistant 1 skin (or 2) and Kiosk Satellite draws nothing of its own for a turn.
 *
 * <p>Full screen: a deep navy backdrop with slowly drifting lights. The house logo (a roof over a D) draws itself in,
 * the roof lifts and glows with the voice while rings ripple out from the house, a comet circles it while the
 * assistant thinks, and the words show underneath: what was said, then the answer. Docked: the same logo, small,
 * in a navy card at the bottom with the words beside it. Touches always pass through (double tap dismisses).
 *
 * <p>Follows the Appearance settings Background (backdrop opacity), Text size and Reactive activity bar. The palette
 * is the logo's navy whatever the Theme setting, so the skin always looks like the house brand. Like the SDK's
 * example, it follows the turn's events only while it is picked, so it costs nothing while another skin draws.
 */
final class VoiceSkin {

    static final String KEY = "voice_skin";
    private static final String[] EVENTS = { "voice.overlay", "voice.level", "voice.text", "voice.appearance" };
    private static final float DEFAULT_BACKGROUND = 0.97f;

    // The logo's colors.
    static final int NAVY_TOP = 0xFF0C1730;
    static final int NAVY_BOTTOM = 0xFF1B2D55;
    static final int SKY = 0xFF5AB8F5;
    static final int AMBER = 0xFFFFB547;
    static final int WHITE = 0xFFF4F6FA;

    private final HouseToolsPlugin plugin;
    private PluginHost host;
    private int generation;
    private boolean selected, skinHeard, following, appearanceHeard;
    private String overlay = "hidden";
    private String drawn; // null, "full" or "docked"

    // Read by the views on the main thread.
    private volatile float level;
    private volatile String command = "", answer = "", tool = "";
    private volatile boolean streaming;
    private volatile Float background;
    private volatile float textScale = 1;
    private volatile boolean reactive = true;
    private SkinView view; // main thread only

    VoiceSkin(HouseToolsPlugin plugin) {
        this.plugin = plugin;
    }

    /** Follow only the skin choice from the start; the turn's events once the skin is picked. */
    synchronized void start(PluginHost host) {
        this.host = host;
        final int current = ++generation;
        try {
            host.subscribe("voice.skin");
        } catch (IllegalArgumentException | UnsupportedOperationException older) {
            return; // a Kiosk Satellite without voice skins
        }
        try {
            host.executeCommand("getVoiceState", Collections.<String, Object>emptyMap(), (ok, data, error) -> {
                synchronized (VoiceSkin.this) {
                    if (current != generation || !ok || !(data instanceof Map)) return;
                    Map<?, ?> state = (Map<?, ?>) data;
                    if (!skinHeard) selected = Boolean.TRUE.equals(state.get("skinSelected"));
                    if ("hidden".equals(overlay) && state.get("overlay") instanceof String) overlay =
                        (String) state.get("overlay");
                    follow();
                    update();
                }
            });
        } catch (UnsupportedOperationException older) {}
    }

    private void follow() {
        if (host == null || selected == following) return;
        following = selected;
        final int current = generation;
        for (String event : EVENTS) {
            try {
                if (following) host.subscribe(event);
                else host.unsubscribe(event);
            } catch (IllegalArgumentException older) {}
        }
        if (!following) return;
        try {
            host.executeCommand("getVoiceAppearance", Collections.<String, Object>emptyMap(), (ok, data, error) -> {
                synchronized (VoiceSkin.this) {
                    if (current == generation && ok && data instanceof Map && !appearanceHeard) appearance(
                        (Map<?, ?>) data
                    );
                }
            });
            host.executeCommand("getVoiceText", Collections.<String, Object>emptyMap(), (ok, data, error) -> {
                synchronized (VoiceSkin.this) {
                    if (
                        current == generation && ok && data instanceof Map && command.isEmpty() && answer.isEmpty()
                    ) words((Map<?, ?>) data);
                }
            });
        } catch (UnsupportedOperationException older) {}
    }

    /** Events from HouseToolsPlugin.onEvent; true if it was one of ours. */
    synchronized boolean onEvent(String event, Map<String, Object> payload) {
        if (!event.startsWith("ks.voice.")) return false;
        if (host == null) return true;
        switch (event) {
            case "ks.voice.skin":
                selected = Boolean.TRUE.equals(payload.get("selected"));
                skinHeard = true;
                follow();
                break;
            case "ks.voice.overlay":
                overlay = String.valueOf(payload.get("overlay"));
                break;
            case "ks.voice.level":
                Object value = payload.get("level");
                level = value instanceof Number ? ((Number) value).floatValue() : 0;
                return true;
            case "ks.voice.text":
                words(payload);
                return true;
            case "ks.voice.appearance":
                appearanceHeard = true;
                appearance(payload);
                return true;
            default:
                return true;
        }
        update();
        return true;
    }

    private void appearance(Map<?, ?> settings) {
        background = settings.get("background") instanceof Number
            ? ((Number) settings.get("background")).floatValue()
            : null;
        textScale = settings.get("textScale") instanceof Number ? ((Number) settings.get("textScale")).floatValue() : 1;
        reactive = !Boolean.FALSE.equals(settings.get("reactive"));
        final SkinView current = view;
        if (current != null) current.post(current::restyle);
    }

    private void words(Map<?, ?> text) {
        command = text.get("command") instanceof String ? ((String) text.get("command")).trim() : "";
        answer = text.get("answer") instanceof String ? ((String) text.get("answer")).trim() : "";
        streaming = Boolean.TRUE.equals(text.get("streaming"));
        Object tools = text.get("tools");
        tool = tools instanceof List && !((List<?>) tools).isEmpty()
            ? String.valueOf(((List<?>) tools).get(((List<?>) tools).size() - 1))
            : "";
        final SkinView current = view;
        if (current != null) current.post(current::refresh);
    }

    /** Show the look the overlay asks for while the skin is picked; hide it otherwise. */
    private void update() {
        String want = selected && ("full".equals(overlay) || "docked".equals(overlay)) ? overlay : null;
        if (want == null ? drawn == null : want.equals(drawn)) return;
        drawn = want;
        try {
            if (want == null) {
                host.hideOverlay(KEY);
                level = 0;
                return;
            }
            boolean docked = "docked".equals(want);
            OverlaySpec spec = docked ? OverlaySpec.at(OverlaySpec.BOTTOM).inset(24) : OverlaySpec.fullScreen();
            spec = spec.onTop(true).touchable(false).closeOnBack(false);
            try {
                spec = spec
                    .enter(docked ? OverlaySpec.SLIDE : OverlaySpec.FADE)
                    .exit(docked ? OverlaySpec.SLIDE : OverlaySpec.FADE)
                    .transitionDuration(docked ? 250 : 350);
            } catch (LinkageError older) {}
            host.showOverlay(KEY, spec, new Factory(docked));
        } catch (Throwable error) {
            plugin.log("Voice skin: " + error);
        }
    }

    synchronized void stop() {
        host = null;
        generation++;
        selected = skinHeard = following = appearanceHeard = false;
        overlay = "hidden";
        drawn = null;
        level = 0;
        command = answer = tool = "";
    }

    private final class Factory implements OverlayFactory {

        private final boolean docked;

        Factory(boolean docked) {
            this.docked = docked;
        }

        @Override
        public View create(Context context, KsTheme theme) {
            SkinView skin = new SkinView(context, theme, docked);
            view = skin;
            return skin;
        }

        @Override
        public void onDestroy(View destroyed) {
            if (view == destroyed) view = null;
        }
    }

    /** Full screen: backdrop, title, logo and words in a column. Docked: a navy card with a small logo. */
    private final class SkinView extends FrameLayout {

        private final boolean docked;
        private final KsTheme theme;
        private final Backdrop backdrop;
        private final Logo logo;
        private final TextView title, said, answered;
        private final LinearLayout card;
        private String lastAnswer = "";

        SkinView(Context context, KsTheme theme, boolean docked) {
            super(context);
            this.theme = theme;
            this.docked = docked;
            int screenW = context.getResources().getDisplayMetrics().widthPixels;
            int screenH = context.getResources().getDisplayMetrics().heightPixels;
            logo = new Logo(context, docked);
            title = new TextView(context);
            said = new TextView(context);
            answered = new TextView(context);
            if (docked) {
                backdrop = null;
                card = new LinearLayout(context);
                card.setOrientation(LinearLayout.HORIZONTAL);
                card.setGravity(Gravity.CENTER_VERTICAL);
                int size = theme.px(76);
                card.addView(logo, new LinearLayout.LayoutParams(size, size));
                LinearLayout words = new LinearLayout(context);
                words.setOrientation(LinearLayout.VERTICAL);
                said.setMaxLines(2);
                said.setEllipsize(TextUtils.TruncateAt.END);
                answered.setMaxLines(3);
                answered.setEllipsize(TextUtils.TruncateAt.END);
                words.addView(said);
                words.addView(answered);
                LinearLayout.LayoutParams wp = new LinearLayout.LayoutParams(
                    0,
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    1
                );
                wp.leftMargin = theme.px(14);
                card.addView(words, wp);
                GradientDrawable bg = new GradientDrawable(
                    GradientDrawable.Orientation.TOP_BOTTOM,
                    new int[] { NAVY_TOP, NAVY_BOTTOM }
                );
                bg.setCornerRadius(theme.px(28));
                bg.setStroke(Math.max(1, theme.px(1)), 0x555AB8F5);
                card.setBackground(bg);
                card.setPadding(theme.px(10), theme.px(10), theme.px(22), theme.px(10));
                card.setElevation(theme.px(8));
                int width = Math.min(theme.px(600), screenW - theme.px(48));
                FrameLayout.LayoutParams cp = new FrameLayout.LayoutParams(width, LayoutParams.WRAP_CONTENT);
                cp.gravity = Gravity.CENTER_HORIZONTAL;
                addView(card, cp);
            } else {
                card = null;
                backdrop = new Backdrop(context);
                addView(backdrop, new FrameLayout.LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT));
                LinearLayout column = new LinearLayout(context);
                column.setOrientation(LinearLayout.VERTICAL);
                column.setGravity(Gravity.CENTER);
                title.setGravity(Gravity.CENTER);
                title.setLetterSpacing(0.32f);
                column.addView(
                    title,
                    new LinearLayout.LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT)
                );
                int size = Math.round(Math.min(screenW, screenH) * 0.46f);
                LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(size, size);
                lp.topMargin = theme.px(8);
                column.addView(logo, lp);
                said.setGravity(Gravity.CENTER);
                said.setMaxLines(3);
                said.setEllipsize(TextUtils.TruncateAt.END);
                LinearLayout.LayoutParams sp = new LinearLayout.LayoutParams(
                    LayoutParams.MATCH_PARENT,
                    LayoutParams.WRAP_CONTENT
                );
                sp.topMargin = theme.px(4);
                column.addView(said, sp);
                answered.setGravity(Gravity.CENTER);
                answered.setMaxLines(7);
                answered.setEllipsize(TextUtils.TruncateAt.END);
                answered.setLineSpacing(0, 1.12f);
                LinearLayout.LayoutParams ap = new LinearLayout.LayoutParams(
                    LayoutParams.MATCH_PARENT,
                    LayoutParams.WRAP_CONTENT
                );
                ap.topMargin = theme.px(14);
                column.addView(answered, ap);
                int side = Math.max(theme.px(40), screenW / 10);
                column.setPadding(side, theme.px(24), side, theme.px(32));
                addView(column, new FrameLayout.LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT));
            }
            restyle();
            refresh();
        }

        void restyle() {
            float scale = textScale;
            if (docked) {
                theme.styleText(said, 14 * scale, 400, "onSurface");
                theme.styleText(answered, 18 * scale, 500, "onSurface");
            } else {
                // Background lowers the backdrop a little, never so far that the screen behind fights the logo.
                float opacity = background != null ? background : DEFAULT_BACKGROUND;
                backdrop.setAlpha(Math.max(0.9f, Math.min(1f, opacity)));
                theme.styleText(title, 15 * scale, 600, "onSurface");
                theme.styleText(said, 21 * scale, 400, "onSurface");
                theme.styleText(answered, 30 * scale, 500, "onSurface");
                title.setTextColor(SKY);
            }
            said.setTextColor(0xB3F4F6FA);
            answered.setTextColor(WHITE);
        }

        void refresh() {
            String name = plugin.voiceTitle();
            title.setText(name.toUpperCase(java.util.Locale.US));
            title.setVisibility(docked || name.isEmpty() ? GONE : VISIBLE);
            String shown;
            if (!answer.isEmpty()) shown = answer;
            else if (!tool.isEmpty()) shown = tool;
            else shown = command.isEmpty() ? "Listening…" : "Thinking…";
            boolean status = answer.isEmpty() && tool.isEmpty();
            said.setText(command.isEmpty() ? "" : "“" + command + "”");
            said.setVisibility(command.isEmpty() ? GONE : VISIBLE);
            answered.setText(shown);
            answered.setTextColor(status ? 0x99F4F6FA : WHITE);
            if (!status && lastAnswer.isEmpty()) { // the answer arrives: let it rise in
                answered.setAlpha(0f);
                answered.setTranslationY(theme.px(14));
                answered.animate().alpha(1f).translationY(0).setDuration(450).start();
            }
            lastAnswer = status ? "" : shown;
        }

        /** Deep navy with soft lights drifting slowly, like a night sky over the house. */
        private final class Backdrop extends View {

            private final Paint fill = new Paint();
            private final Paint orb = new Paint(Paint.ANTI_ALIAS_FLAG);
            private final RadialGradient[] glows = new RadialGradient[4];
            private final int[] colors = { 0x335AB8F5, 0x2A3E7BFA, 0x22FFB547, 0x2A7FD3FF };
            private final int STARS = 46;
            private final float[] starX = new float[STARS], starY = new float[STARS], starSize = new float[STARS];
            private final double[] starSpeed = new double[STARS], starPhase = new double[STARS];
            private final Paint star = new Paint(Paint.ANTI_ALIAS_FLAG);

            {
                java.util.Random random = new java.util.Random(7); // the same sky every time
                float dp = getResources().getDisplayMetrics().density;
                for (int i = 0; i < STARS; i++) {
                    starX[i] = random.nextFloat();
                    starY[i] = random.nextFloat();
                    starSize[i] = dp * (0.6f + 1.2f * random.nextFloat());
                    starSpeed[i] = 0.4 + 1.4 * random.nextDouble();
                    starPhase[i] = random.nextDouble() * Math.PI * 2;
                }
                star.setColor(0xFFDCEBFF);
            }

            Backdrop(Context context) {
                super(context);
                for (int i = 0; i < glows.length; i++) glows[i] = new RadialGradient(
                    0,
                    0,
                    1,
                    colors[i],
                    0x00000000,
                    Shader.TileMode.CLAMP
                );
            }

            @Override
            protected void onSizeChanged(int w, int h, int oldW, int oldH) {
                fill.setShader(new LinearGradient(0, 0, 0, h, NAVY_TOP, NAVY_BOTTOM, Shader.TileMode.CLAMP));
            }

            @Override
            protected void onDraw(Canvas canvas) {
                int w = getWidth(), h = getHeight();
                canvas.drawRect(0, 0, w, h, fill);
                // Faint stars twinkling, each at its own pace.
                double now = SystemClock.uptimeMillis() / 1000.0;
                for (int i = 0; i < STARS; i++) {
                    double tw = Math.sin(now * starSpeed[i] + starPhase[i]);
                    star.setAlpha((int) Math.round(40 + 150 * tw * tw));
                    canvas.drawCircle(starX[i] * w, starY[i] * h, starSize[i], star);
                }
                double t = SystemClock.uptimeMillis() / 1000.0;
                float big = Math.max(w, h);
                for (int i = 0; i < glows.length; i++) {
                    double a = t / (22 + i * 7) + i * 1.7;
                    float x = (float) (w * (0.5 + 0.38 * Math.sin(a * 1.3 + i)));
                    float y = (float) (h * (0.5 + 0.34 * Math.cos(a + i * 2.1)));
                    float r = big * (0.32f + 0.06f * i);
                    orb.setShader(glows[i]);
                    canvas.save();
                    canvas.translate(x, y);
                    canvas.scale(r, r);
                    canvas.drawCircle(0, 0, 1, orb);
                    canvas.restore();
                }
                postInvalidateOnAnimation();
            }
        }
    }

    /**
     * The house logo, alive: the roof draws itself in and the D glows up; with the voice the roof lifts and glows and
     * rings ripple outward; while the assistant thinks a comet of light circles the house.
     */
    private final class Logo extends View {

        private final boolean small;
        private final long born = SystemClock.uptimeMillis();
        private final Paint roof = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint roofGlow = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint letter = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint ring = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint halo = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint comet = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Path roofPath = new Path();
        private final Path roofPart = new Path();
        private final List<float[]> ripples = new ArrayList<>(); // {start ms, strength}
        private RadialGradient haloShader;
        private float shown; // eased voice level
        private long lastRipple;
        private float thinkingFade;

        Logo(Context context, boolean small) {
            super(context);
            this.small = small;
            roof.setStyle(Paint.Style.STROKE);
            roof.setStrokeCap(Paint.Cap.ROUND);
            roof.setStrokeJoin(Paint.Join.ROUND);
            roof.setColor(SKY);
            roofGlow.setStyle(Paint.Style.STROKE);
            roofGlow.setStrokeCap(Paint.Cap.ROUND);
            roofGlow.setStrokeJoin(Paint.Join.ROUND);
            letter.setColor(WHITE);
            letter.setTypeface(Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD));
            letter.setTextAlign(Paint.Align.CENTER);
            ring.setStyle(Paint.Style.STROKE);
            comet.setStyle(Paint.Style.STROKE);
            comet.setStrokeCap(Paint.Cap.ROUND);
            haloShader = new RadialGradient(0, 0, 1, 0x665AB8F5, 0x005AB8F5, Shader.TileMode.CLAMP);
            halo.setShader(haloShader);
        }

        private float ease(float x) {
            x = Math.max(0, Math.min(1, x));
            return 1 - (1 - x) * (1 - x) * (1 - x);
        }

        @Override
        protected void onDraw(Canvas canvas) {
            long now = SystemClock.uptimeMillis();
            float age = now - born;
            float target = reactive ? level : 0;
            shown += (target - shown) * 0.22f;
            float cx = getWidth() / 2f, cy = getHeight() / 2f;
            float unit = Math.min(cx, cy); // the logo box is [-unit, unit]
            boolean thinking = !command.isEmpty() && answer.isEmpty() && tool.isEmpty();
            thinkingFade += ((thinking ? 1f : 0f) - thinkingFade) * 0.08f;
            double breath = 0.5 - 0.5 * Math.cos(((now % 3200) / 3200.0) * Math.PI * 2);

            // Ripples: one for each surge of the voice, and a slow one at rest.
            if (shown > 0.12f && now - lastRipple > 260) {
                ripples.add(new float[] { now, Math.min(1f, 0.35f + shown) });
                lastRipple = now;
            } else if (shown < 0.05f && !thinking && now - lastRipple > 1800 && age > 600) {
                ripples.add(new float[] { now, 0.28f });
                lastRipple = now;
            }
            float life = 1700;
            for (int i = ripples.size() - 1; i >= 0; i--) {
                float[] r = ripples.get(i);
                float p = (now - r[0]) / life;
                if (p >= 1) {
                    ripples.remove(i);
                    continue;
                }
                float radius = unit * (0.5f + 0.5f * ease(p));
                int alpha = Math.round(220 * r[1] * (1 - p) * (1 - p));
                ring.setStrokeWidth(unit * (small ? 0.03f : 0.018f) * (1 - p * 0.6f));
                ring.setColor(Color.argb(alpha, 0x5A, 0xB8, 0xF5));
                canvas.drawCircle(cx, cy, radius, ring);
            }

            // A soft glow behind the house that swells with the voice.
            float haloR = unit * (0.62f + 0.06f * (float) breath + 0.25f * shown + 0.1f * thinkingFade);
            halo.setAlpha(
                Math.round(255 * Math.min(1f, 0.55f + 0.8f * shown + 0.3f * thinkingFade) * ease(age / 600f))
            );
            canvas.save();
            canvas.translate(cx, cy);
            canvas.scale(haloR, haloR);
            canvas.drawCircle(0, 0, 1, halo);
            canvas.restore();

            // Thinking: a comet circles the house, sky blue fading into an amber tail.
            if (thinkingFade > 0.02f) {
                float orbit = unit * 0.82f;
                double head = ((now % 1500) / 1500.0) * 360.0;
                comet.setStrokeWidth(unit * (small ? 0.07f : 0.045f));
                int steps = 18;
                for (int i = steps; i >= 0; i--) {
                    float f = i / (float) steps; // 0 = head, 1 = end of the tail
                    double angle = Math.toRadians(head - i * 7);
                    float x = cx + (float) (Math.cos(angle) * orbit);
                    float y = cy + (float) (Math.sin(angle) * orbit);
                    int color = blend(SKY, AMBER, f);
                    int alpha = Math.round(255 * thinkingFade * (1 - f) * (1 - f));
                    comet.setColor((color & 0x00FFFFFF) | (alpha << 24));
                    canvas.drawPoint(x, y, comet);
                }
            }

            // The roof draws itself from eave to peak to eave, then lifts and glows with the voice.
            float lift = unit * (0.07f * shown + 0.015f * (float) breath);
            roofPath.reset();
            roofPath.moveTo(cx - unit * 0.62f, cy - unit * 0.06f - lift);
            roofPath.lineTo(cx, cy - unit * 0.62f - lift);
            roofPath.lineTo(cx + unit * 0.62f, cy - unit * 0.06f - lift);
            PathMeasure measure = new PathMeasure(roofPath, false);
            roofPart.reset();
            measure.getSegment(0, measure.getLength() * ease(age / 750f), roofPart, true);
            float stroke = unit * 0.13f;
            // A soft glow: wider, fainter strokes stacked under the roof, brighter with the voice.
            float glow = 0.6f + 1.1f * shown + 0.5f * thinkingFade;
            float[] widths = { 3.2f, 2.4f, 1.75f, 1.35f };
            int[] alphas = { 10, 18, 30, 48 };
            for (int i = 0; i < widths.length; i++) {
                roofGlow.setStrokeWidth(stroke * (widths[i] + 0.8f * shown));
                roofGlow.setColor(Color.argb(Math.min(255, Math.round(alphas[i] * glow)), 0x5A, 0xB8, 0xF5));
                canvas.drawPath(roofPart, roofGlow);
            }
            roof.setStrokeWidth(stroke);
            roof.setShadowLayer(stroke * (0.5f + 0.9f * shown), 0, 0, 0xCC5AB8F5);
            roof.setColor(thinkingFade > 0.02f ? blend(SKY, 0xFFBFE6FF, (float) breath * thinkingFade) : SKY);
            canvas.drawPath(roofPart, roof);

            // The D glows up after the roof is drawn.
            float d = ease((age - 380) / 520f);
            if (d > 0) {
                letter.setTextSize(unit * 0.98f * (0.85f + 0.15f * d));
                letter.setAlpha(Math.round(255 * d));
                letter.setShadowLayer(unit * 0.08f * (0.4f + shown), 0, 0, 0xAA5AB8F5);
                // Thinking: a band of light sweeps across the D.
                if (thinkingFade > 0.02f) {
                    float sweep = (now % 1600) / 1600f;
                    float x = cx - unit + sweep * unit * 2.6f;
                    int edge = blend(WHITE, 0xFF9FD8FF, thinkingFade);
                    letter.setShader(
                        new LinearGradient(
                            x - unit * 0.35f,
                            0,
                            x + unit * 0.35f,
                            0,
                            new int[] { WHITE, edge, WHITE },
                            new float[] { 0f, 0.5f, 1f },
                            Shader.TileMode.CLAMP
                        )
                    );
                } else letter.setShader(null);
                Paint.FontMetrics fm = letter.getFontMetrics();
                float baseline = cy + unit * 0.3f - (fm.ascent + fm.descent) / 2f;
                canvas.drawText("D", cx, baseline, letter);
            }
            postInvalidateOnAnimation();
        }
    }

    private static int blend(int from, int to, float f) {
        f = Math.max(0, Math.min(1, f));
        int r = Math.round(Color.red(from) + (Color.red(to) - Color.red(from)) * f);
        int g = Math.round(Color.green(from) + (Color.green(to) - Color.green(from)) * f);
        int b = Math.round(Color.blue(from) + (Color.blue(to) - Color.blue(from)) * f);
        return Color.rgb(r, g, b);
    }
}
