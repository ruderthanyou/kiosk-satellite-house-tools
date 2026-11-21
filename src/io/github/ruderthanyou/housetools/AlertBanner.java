// SPDX-License-Identifier: MIT
package io.github.ruderthanyou.housetools;

import android.content.Context;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.os.Handler;
import android.os.Looper;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.TextView;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import me.jxl.kiosk.plugins.KsTheme;
import me.jxl.kiosk.plugins.OverlayFactory;
import me.jxl.kiosk.plugins.OverlaySpec;
import me.jxl.kiosk.plugins.PluginHost;

/**
 * A slim bar at the top of the screen, over the dashboard and the screensaver, for the same alerts the
 * chosen alerts entity lists in its 'items' attribute ({key, name, color} each). It appears when a new alert starts, goes away after the
 * set minutes or a tap, and comes back only for an alert it hasn't shown yet.
 */
final class AlertBanner {

    static final String KEY = "alert_banner";

    private final HouseToolsPlugin plugin;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final Set<String> shown = new HashSet<>(); // alert keys already announced
    private List<Map<String, Object>> current = new ArrayList<>();
    private boolean visible;

    AlertBanner(HouseToolsPlugin plugin) {
        this.plugin = plugin;
    }

    /** Main thread. */
    void update(List<Object> alerts, boolean enabled) {
        List<Map<String, Object>> list = new ArrayList<>();
        Set<String> keys = new HashSet<>();
        for (Object o : alerts) {
            Map<String, Object> a = Net.map(o);
            list.add(a);
            keys.add(Net.str(a.get("key")));
        }
        shown.retainAll(keys); // a cleared alert can announce itself again next time
        current = list;
        if (!enabled || list.isEmpty()) {
            hide();
            return;
        }
        boolean fresh = false;
        for (String k : keys) if (shown.add(k)) fresh = true;
        if (fresh) show();
        else if (visible) show(); // refresh text for the alerts still listed
    }

    void hide() {
        main.removeCallbacks(autoHide);
        if (!visible) return;
        visible = false;
        try {
            plugin.host().hideOverlay(KEY);
        } catch (Throwable ignored) {}
    }

    private final Runnable autoHide = this::hide;

    private void show() {
        PluginHost host = plugin.host();
        if (host == null) return;
        final List<Map<String, Object>> alerts = new ArrayList<>(current);
        try {
            OverlaySpec spec = OverlaySpec.at(OverlaySpec.TOP).inset(0, 12, 0, 0).closeOnBack(false);
            try {
                spec = spec.layer(OverlaySpec.LAYER_TOP).enter(OverlaySpec.SLIDE).exit(OverlaySpec.SLIDE);
            } catch (LinkageError older) {
                spec = spec.onTop(true);
            }
            host.showOverlay(
                KEY,
                spec,
                new OverlayFactory() {
                    @Override
                    public View create(Context context, KsTheme theme) {
                        return build(context, theme, alerts);
                    }
                }
            );
            visible = true;
        } catch (Throwable error) {
            plugin.log("Alert banner: " + error);
            return;
        }
        main.removeCallbacks(autoHide);
        main.postDelayed(autoHide, plugin.alertMinutes() * 60_000L);
    }

    private View build(Context context, KsTheme theme, List<Map<String, Object>> alerts) {
        LinearLayout bar = new LinearLayout(context);
        bar.setOrientation(LinearLayout.HORIZONTAL);
        bar.setGravity(Gravity.CENTER_VERTICAL);
        int color = alerts.isEmpty() ? 0xFFFB8C00 : colorFor(alerts.get(0));
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(0xF20F1A2E);
        bg.setCornerRadius(theme.px(26));
        bg.setStroke(theme.px(2), color);
        bar.setBackground(bg);
        bar.setPadding(theme.px(16), theme.px(10), theme.px(18), theme.px(10));

        TextView text = new TextView(context);
        StringBuilder names = new StringBuilder();
        for (Map<String, Object> a : alerts) {
            if (names.length() > 0) names.append("  ·  ");
            names.append(Net.str(a.get("name")));
        }
        text.setText(names);
        theme.styleText(text, 17, 600, "onSurface");
        text.setTextColor(0xFFF5F7FA);
        text.setSingleLine(true);
        text.setEllipsize(TextUtils.TruncateAt.END);
        text.setMaxWidth(theme.px(640));
        try {
            Drawable icon = theme.icon(isLeak(alerts) ? "mdi:water-alert" : "mdi:bell-ring", "onSurface");
            if (icon != null) {
                icon.setBounds(0, 0, theme.px(24), theme.px(24));
                icon.setTint(color);
                text.setCompoundDrawables(icon, null, null, null);
                text.setCompoundDrawablePadding(theme.px(10));
            }
        } catch (Throwable ignored) {}
        bar.addView(text);

        TextView dismiss = new TextView(context);
        dismiss.setText("Tap to hide");
        theme.styleText(dismiss, 13, 400, "onSurface");
        dismiss.setTextColor(0x99F5F7FA);
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.WRAP_CONTENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        );
        p.leftMargin = theme.px(16);
        bar.addView(dismiss, p);

        bar.setOnClickListener(v -> {
            try {
                hide();
            } catch (Throwable ignored) {}
        });
        return bar;
    }

    private static boolean isLeak(List<Map<String, Object>> alerts) {
        for (Map<String, Object> a : alerts) if ("leak".equals(Net.str(a.get("key")))) return true;
        return false;
    }

    /** LED color names from Settings > Notifications. Leaks are always red. */
    static int colorFor(Map<String, Object> alert) {
        if ("leak".equals(Net.str(alert.get("key")))) return 0xFFE53935;
        switch (Net.str(alert.get("color")).toLowerCase(Locale.US)) {
            case "red":
                return 0xFFE53935;
            case "orange":
                return 0xFFFB8C00;
            case "lemon":
                return 0xFFFDD835;
            case "lime":
                return 0xFFC0CA33;
            case "green":
                return 0xFF43A047;
            case "teal":
                return 0xFF00897B;
            case "cyan":
                return 0xFF00ACC1;
            case "aqua":
                return 0xFF26C6DA;
            case "blue":
                return 0xFF1E88E5;
            case "violet":
                return 0xFF8E24AA;
            case "magenta":
                return 0xFFD81B60;
            case "pink":
                return 0xFFEC407A;
            case "white":
                return 0xFFECEFF1;
            default:
                return 0xFFFB8C00;
        }
    }
}
