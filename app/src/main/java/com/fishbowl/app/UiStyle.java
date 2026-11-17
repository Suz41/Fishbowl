package com.fishbowl.app;

import android.content.Context;
import android.content.SharedPreferences;
import android.content.res.ColorStateList;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.os.Build;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.EditText;
import android.widget.Switch;

/** Central appearance helper: accent colour, glass cards, switches, dirty-aware SAVE buttons. */
public final class UiStyle {
    private UiStyle() {}

    public interface StringSource { String get(); }

    public static final String[] ACCENT_NAMES = {"Sky", "Emerald", "Violet", "Rose", "Amber", "Cyan"};
    public static final int[] ACCENTS = {
            0xFF38BDF8, 0xFF34D399, 0xFFA78BFA, 0xFFFB7185, 0xFFFBBF24, 0xFF22D3EE};

    private static SharedPreferences p(Context c) {
        return c.getSharedPreferences("fishbowl_appearance", Context.MODE_PRIVATE);
    }

    public static int accentIndex(Context c) {
        int i = p(c).getInt("accent", 0);
        return (i < 0 || i >= ACCENTS.length) ? 0 : i;
    }
    public static void setAccentIndex(Context c, int i) { p(c).edit().putInt("accent", i).apply(); }
    public static int accent(Context c) { return ACCENTS[accentIndex(c)]; }

    public static boolean glass(Context c) { return p(c).getBoolean("glass", true); }
    public static void setGlass(Context c, boolean v) { p(c).edit().putBoolean("glass", v).apply(); }

    /** 0..100 glass strength (higher = more translucent / brighter glass and stronger dialog blur). */
    public static int blur(Context c) { return Math.max(0, Math.min(100, p(c).getInt("blur", 60))); }
    public static void setBlur(Context c, int v) { p(c).edit().putInt("blur", v).apply(); }

    /** Card corner radius in dp (8..28). */
    public static int radius(Context c) { return Math.max(8, Math.min(28, p(c).getInt("radius", 16))); }
    public static void setRadius(Context c, int v) { p(c).edit().putInt("radius", v).apply(); }

    /** 0 = pure black (OLED), 1 = aurora gradient. */
    public static int background(Context c) { return p(c).getInt("bg", 1); }
    public static void setBackground(Context c, int v) { p(c).edit().putInt("bg", v).apply(); }

    public static void reset(Context c) { p(c).edit().clear().apply(); }

    private static int dp(Context c, float v) { return (int) (v * c.getResources().getDisplayMetrics().density); }

    private static int mix(int a, int b, float t) {
        return Color.rgb(
                (int) (Color.red(a) * (1 - t) + Color.red(b) * t),
                (int) (Color.green(a) * (1 - t) + Color.green(b) * t),
                (int) (Color.blue(a) * (1 - t) + Color.blue(b) * t));
    }

    /** Card background for a settings section: frosted-glass gradient or solid. */
    public static GradientDrawable card(Context c) {
        GradientDrawable gd = new GradientDrawable();
        int acc = accent(c);
        if (glass(c)) {
            int b = blur(c);
            int a1 = 70 + b * 130 / 100;   // 70..200
            int a2 = 50 + b * 110 / 100;
            gd.setOrientation(GradientDrawable.Orientation.TL_BR);
            gd.setColors(new int[]{
                    Color.argb(a1, 46, 52, 72) | 0,
                    Color.argb(a2, Color.red(mix(0x0C0D10, acc, 0.10f)), Color.green(mix(0x0C0D10, acc, 0.10f)), Color.blue(mix(0x0C0D10, acc, 0.10f)))});
            gd.setStroke(dp(c, 1), Color.argb(46, 255, 255, 255));
        } else {
            gd.setColor(0xFF0C0D10);
            gd.setStroke(dp(c, 1), Color.argb(20, 255, 255, 255));
        }
        gd.setCornerRadius(dp(c, radius(c)));
        return gd;
    }

    /** Page background. */
    public static GradientDrawable pageBackground(Context c) {
        GradientDrawable gd = new GradientDrawable();
        if (background(c) == 1) {
            int acc = accent(c);
            gd.setOrientation(GradientDrawable.Orientation.TOP_BOTTOM);
            gd.setColors(new int[]{mix(0x05060A, acc, 0.22f), mix(0x05060A, acc, 0.07f), 0xFF000000});
        } else {
            gd.setColor(0xFF000000);
        }
        return gd;
    }

    private static ColorStateList states(int checked, int unchecked) {
        return new ColorStateList(
                new int[][]{new int[]{android.R.attr.state_checked}, new int[]{}},
                new int[]{checked, unchecked});
    }

    /** Switch: vivid accent track when ON, subdued grey (as before) when OFF. */
    public static void styleSwitch(Context c, Switch s) {
        if (Build.VERSION.SDK_INT < 23) return;
        try {
            s.setThumbTintList(states(0xFFFFFFFF, 0xFF9CA3AF));
            s.setTrackTintList(states(accent(c), 0xFF3F3F46));
        } catch (Throwable ignored) {}
    }

    public static void styleAllSwitches(Context c, View root) {
        if (root instanceof Switch) { styleSwitch(c, (Switch) root); return; }
        if (root instanceof ViewGroup) {
            ViewGroup g = (ViewGroup) root;
            for (int i = 0; i < g.getChildCount(); i++) styleAllSwitches(c, g.getChildAt(i));
        }
    }

    /**
     * SAVE button that is dimmed and disabled while the field equals the stored value.
     * Returns a Runnable that re-evaluates the state (call it after saving).
     */
    public static Runnable bindDirty(final Button save, final EditText input, final StringSource saved) {
        final Runnable update = new Runnable() {
            @Override public void run() {
                boolean dirty;
                try { dirty = !input.getText().toString().trim().equals(String.valueOf(saved.get()).trim()); }
                catch (Throwable t) { dirty = true; }
                save.setEnabled(dirty);
                save.setAlpha(dirty ? 1f : 0.35f);
            }
        };
        input.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int a, int b, int c) {}
            @Override public void onTextChanged(CharSequence s, int a, int b, int c) {}
            @Override public void afterTextChanged(Editable s) { update.run(); }
        });
        update.run();
        return update;
    }

    /** Frosted blur behind a dialog window (Android 12+; harmless elsewhere). */
    public static void blurWindow(Context c, Window w) {
        if (w == null) return;
        try {
            if (Build.VERSION.SDK_INT >= 31) {
                w.addFlags(WindowManager.LayoutParams.FLAG_BLUR_BEHIND);
                WindowManager.LayoutParams lp = w.getAttributes();
                lp.blurBehindRadius = dp(c, 6 + blur(c) * 0.3f);
                w.setAttributes(lp);
                w.setDimAmount(0.55f);
            }
        } catch (Throwable ignored) {}
    }
}
