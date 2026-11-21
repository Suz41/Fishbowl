package com.fishbowl.app;

import android.content.Context;
import android.content.SharedPreferences;
import android.content.res.Configuration;
import android.os.Build;

import androidx.appcompat.app.AppCompatDelegate;

/**
 * Single source of truth for every colour in the app (Material 3 style colour roles).
 *
 * <ul>
 *   <li>Mode: System / Light / Dark / AMOLED black.</li>
 *   <li>Accent: 8 presets, or Material You "Dynamic" (wallpaper colours, Android 12+).</li>
 * </ul>
 *
 * The UI is built programmatically and bakes colours when views are created, so changing the
 * theme is applied by recreating the activity (see Settings > Appearance).
 */
public final class AppTheme {
    private AppTheme() {}

    public static final int MODE_SYSTEM = 0;
    public static final int MODE_LIGHT = 1;
    public static final int MODE_DARK = 2;
    public static final int MODE_AMOLED = 3;

    /** Accent index meaning "use the wallpaper colours" (Android 12+). */
    public static final int ACCENT_DYNAMIC = -1;

    private static final String PREFS = "fishbowl_settings";
    private static final String KEY_MODE = "theme_mode";
    private static final String KEY_ACCENT = "theme_accent";

    public static final String[] ACCENT_NAMES = {
            "Ocean", "Violet", "Emerald", "Amber", "Rose", "Orange", "Teal", "Indigo"};
    private static final int[] ACCENT_DARK = {
            0xFF38BDF8, 0xFFA78BFA, 0xFF34D399, 0xFFFBBF24, 0xFFFB7185, 0xFFFB923C, 0xFF2DD4BF, 0xFF818CF8};
    private static final int[] ACCENT_LIGHT = {
            0xFF0369A1, 0xFF6D28D9, 0xFF047857, 0xFFB45309, 0xFFBE123C, 0xFFC2410C, 0xFF0F766E, 0xFF4338CA};

    private static int mode = MODE_AMOLED;
    private static int accentIndex = 0;
    private static boolean dark = true;
    private static boolean initialized = false;

    private static int bg, card, inner, outline, outlineStrong;
    private static int text, textSecondary, textMuted;
    private static int accent, onAccent, accentContainer, onAccentContainer;
    private static int success, warning, danger;

    // ---- lifecycle ----------------------------------------------------------------------

    /** Reads the saved choice and rebuilds the palette. Cheap; safe to call from every activity. */
    public static synchronized void init(Context ctx) {
        Context app = ctx.getApplicationContext() != null ? ctx.getApplicationContext() : ctx;
        SharedPreferences sp = app.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        mode = sp.getInt(KEY_MODE, MODE_AMOLED);
        if (mode < MODE_SYSTEM || mode > MODE_AMOLED) mode = MODE_AMOLED;
        accentIndex = sp.getInt(KEY_ACCENT, 0);
        if (accentIndex == ACCENT_DYNAMIC && !isDynamicAvailable()) accentIndex = 0;
        if (accentIndex < ACCENT_DYNAMIC || accentIndex >= ACCENT_NAMES.length) accentIndex = 0;

        if (mode == MODE_LIGHT) {
            dark = false;
        } else if (mode == MODE_SYSTEM) {
            // Application resources carry the real system setting; an Activity may already hold an AppCompat override.
            int night = app.getResources().getConfiguration().uiMode & Configuration.UI_MODE_NIGHT_MASK;
            dark = night == Configuration.UI_MODE_NIGHT_YES;
        } else {
            dark = true;
        }
        build(ctx);
        initialized = true;
    }

    private static void ensure() {
        // Defensive default (AMOLED + Ocean) if a getter is hit before init(); keeps the old look.
        if (!initialized) {
            mode = MODE_AMOLED;
            accentIndex = 0;
            dark = true;
            build(null);
            initialized = true;
        }
    }

    /** Applies the matching AppCompat night mode. Call before super.onCreate(). */
    public static void applyNightMode() {
        int m;
        if (mode == MODE_LIGHT) m = AppCompatDelegate.MODE_NIGHT_NO;
        else if (mode == MODE_SYSTEM) m = AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM;
        else m = AppCompatDelegate.MODE_NIGHT_YES;
        AppCompatDelegate.setDefaultNightMode(m);
    }

    public static boolean isDynamicAvailable() {
        return Build.VERSION.SDK_INT >= 31;
    }

    public static int getMode(Context ctx) {
        ensure();
        return mode;
    }

    public static int getAccentIndex() {
        ensure();
        return accentIndex;
    }

    public static void setMode(Context ctx, int newMode) {
        ctx.getApplicationContext().getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .edit().putInt(KEY_MODE, newMode).apply();
        init(ctx);
    }

    public static void setAccent(Context ctx, int newAccent) {
        ctx.getApplicationContext().getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .edit().putInt(KEY_ACCENT, newAccent).apply();
        init(ctx);
    }

    /** Preview colour shown on the accent picker swatch for the current dark/light state. */
    public static int accentPreview(Context ctx, int index) {
        if (index == ACCENT_DYNAMIC) {
            if (isDynamicAvailable()) {
                try { return Dyn.accentPreview(ctx, dark); } catch (Throwable ignored) {}
            }
            return ACCENT_DARK[0];
        }
        return dark ? ACCENT_DARK[index] : ACCENT_LIGHT[index];
    }

    // ---- palette ------------------------------------------------------------------------

    private static void build(Context ctx) {
        boolean amoled = mode == MODE_AMOLED;

        if (dark) {
            bg = amoled ? 0xFF000000 : 0xFF111114;
            card = amoled ? 0xFF0C0C0E : 0xFF1B1B1F;
            inner = amoled ? 0xFF18181B : 0xFF26262B;
            outline = amoled ? 0xFF27272A : 0xFF36363D;
            outlineStrong = amoled ? 0xFF3F3F46 : 0xFF4A4A53;
            text = 0xFFF4F4F5;
            textSecondary = 0xFFA1A1AA;
            textMuted = amoled ? 0xFF71717A : 0xFF8B8B95;
            success = 0xFF34D399;
            warning = 0xFFFBBF24;
            danger = 0xFFF87171;
        } else {
            bg = 0xFFF7F7FA;
            card = 0xFFFFFFFF;
            inner = 0xFFF0F0F5;
            outline = 0xFFE1E1E9;
            outlineStrong = 0xFFC9C9D3;
            text = 0xFF1B1B1F;
            textSecondary = 0xFF46464F;
            textMuted = 0xFF6B6B75;
            success = 0xFF047857;
            warning = 0xFFB45309;
            danger = 0xFFDC2626;
        }

        boolean usedDynamic = false;
        if (accentIndex == ACCENT_DYNAMIC && ctx != null && isDynamicAvailable()) {
            try {
                int[] d = Dyn.palette(ctx, dark, amoled);
                // order: bg, card, inner, outline, outlineStrong, text, textSecondary, textMuted,
                //        accent, accentContainer, onAccentContainer
                bg = d[0]; card = d[1]; inner = d[2]; outline = d[3]; outlineStrong = d[4];
                text = d[5]; textSecondary = d[6]; textMuted = d[7];
                accent = d[8]; accentContainer = d[9]; onAccentContainer = d[10];
                usedDynamic = true;
            } catch (Throwable ignored) {
                usedDynamic = false;
            }
        }
        if (!usedDynamic) {
            int idx = accentIndex < 0 ? 0 : accentIndex;
            accent = dark ? ACCENT_DARK[idx] : ACCENT_LIGHT[idx];
            if (dark) {
                accentContainer = mix(card, accent, 0.22f);
                onAccentContainer = mix(accent, 0xFFFFFFFF, 0.45f);
            } else {
                accentContainer = mix(0xFFFFFFFF, accent, 0.14f);
                onAccentContainer = mix(accent, 0xFF000000, 0.40f);
            }
        }
        onAccent = contrastOn(accent);
    }

    /** Wallpaper-derived colours via the Android 12 system palette resources. */
    private static final class Dyn {
        static int c(Context ctx, int id) {
            return androidx.core.content.res.ResourcesCompat.getColor(ctx.getResources(), id, ctx.getTheme());
        }

        static int accentPreview(Context ctx, boolean dark) {
            return dark ? c(ctx, android.R.color.system_accent1_200) : c(ctx, android.R.color.system_accent1_600);
        }

        static int[] palette(Context ctx, boolean dark, boolean amoled) {
            int[] o = new int[11];
            if (dark) {
                int n900 = c(ctx, android.R.color.system_neutral1_900);
                int n800 = c(ctx, android.R.color.system_neutral1_800);
                o[0] = amoled ? 0xFF000000 : n900;
                o[1] = amoled ? mix(0xFF000000, n900, 0.7f) : mix(n900, n800, 0.5f);
                o[2] = amoled ? n900 : n800;
                o[3] = c(ctx, android.R.color.system_neutral1_700);
                o[4] = c(ctx, android.R.color.system_neutral2_600);
                o[5] = c(ctx, android.R.color.system_neutral1_100);
                o[6] = c(ctx, android.R.color.system_neutral2_200);
                o[7] = c(ctx, android.R.color.system_neutral2_400);
                o[8] = c(ctx, android.R.color.system_accent1_200);
                o[9] = c(ctx, android.R.color.system_accent1_700);
                o[10] = c(ctx, android.R.color.system_accent1_100);
            } else {
                o[0] = c(ctx, android.R.color.system_neutral1_10);
                o[1] = c(ctx, android.R.color.system_neutral1_0);
                o[2] = c(ctx, android.R.color.system_neutral1_50);
                o[3] = c(ctx, android.R.color.system_neutral1_200);
                o[4] = c(ctx, android.R.color.system_neutral2_300);
                o[5] = c(ctx, android.R.color.system_neutral1_900);
                o[6] = c(ctx, android.R.color.system_neutral2_700);
                o[7] = c(ctx, android.R.color.system_neutral2_500);
                o[8] = c(ctx, android.R.color.system_accent1_600);
                o[9] = c(ctx, android.R.color.system_accent1_100);
                o[10] = c(ctx, android.R.color.system_accent1_900);
            }
            return o;
        }
    }

    // ---- colour math (pure, no android.graphics) -----------------------------------------

    public static int mix(int from, int to, float t) {
        int a = (int) (alpha(from) + (alpha(to) - alpha(from)) * t);
        int r = (int) (red(from) + (red(to) - red(from)) * t);
        int g = (int) (green(from) + (green(to) - green(from)) * t);
        int b = (int) (blue(from) + (blue(to) - blue(from)) * t);
        return (a << 24) | (r << 16) | (g << 8) | b;
    }

    public static int withAlpha(int color, int alpha) {
        return (alpha << 24) | (color & 0x00FFFFFF);
    }

    public static float luminance(int color) {
        return 0.2126f * lin(red(color)) + 0.7152f * lin(green(color)) + 0.0722f * lin(blue(color));
    }

    public static float contrast(int a, int b) {
        float la = luminance(a), lb = luminance(b);
        float hi = Math.max(la, lb), lo = Math.min(la, lb);
        return (hi + 0.05f) / (lo + 0.05f);
    }

    /** Readable foreground (near-black or white) for text/icons drawn on {@code background}. */
    public static int contrastOn(int background) {
        int darkInk = 0xFF0B1220;
        int lightInk = 0xFFFFFFFF;
        return contrast(background, darkInk) >= contrast(background, lightInk) ? darkInk : lightInk;
    }

    private static float lin(int c8) {
        float c = c8 / 255f;
        return c <= 0.03928f ? c / 12.92f : (float) Math.pow((c + 0.055f) / 1.055f, 2.4f);
    }

    private static int alpha(int c) { return (c >>> 24) & 0xFF; }
    private static int red(int c) { return (c >> 16) & 0xFF; }
    private static int green(int c) { return (c >> 8) & 0xFF; }
    private static int blue(int c) { return c & 0xFF; }

    // ---- role getters --------------------------------------------------------------------

    public static boolean isDark() { ensure(); return dark; }
    public static int bg() { ensure(); return bg; }
    public static int card() { ensure(); return card; }
    public static int inner() { ensure(); return inner; }
    public static int outline() { ensure(); return outline; }
    public static int outlineStrong() { ensure(); return outlineStrong; }
    public static int text() { ensure(); return text; }
    public static int textSecondary() { ensure(); return textSecondary; }
    public static int textMuted() { ensure(); return textMuted; }
    public static int accent() { ensure(); return accent; }
    public static int onAccent() { ensure(); return onAccent; }
    public static int accentContainer() { ensure(); return accentContainer; }
    public static int onAccentContainer() { ensure(); return onAccentContainer; }
    public static int success() { ensure(); return success; }
    public static int warning() { ensure(); return warning; }
    public static int danger() { ensure(); return danger; }
    /** Soft tinted fills used behind status text (error / success / warning banners and badges). */
    public static int dangerContainer() { return mix(card(), danger(), dark ? 0.18f : 0.12f); }
    public static int successContainer() { return mix(card(), success(), dark ? 0.18f : 0.12f); }
    public static int warningContainer() { return mix(card(), warning(), dark ? 0.18f : 0.12f); }
    /** Text/icon colour for content drawn on a {@link #danger()} fill. */
    public static int onDanger() { return contrastOn(danger()); }
}
