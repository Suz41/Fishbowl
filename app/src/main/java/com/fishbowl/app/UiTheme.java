package com.fishbowl.app;

import android.graphics.Color;

/** Single source of truth for card colours, borders and corner radii used by every tab. */
public final class UiTheme {
    private UiTheme() {}

    public static final int CARD = 0xFF0C0C0E;
    public static final int BORDER = 0xFF27272A;
    public static final int INNER = 0xFF121215;

    private static final int[] CARD_FILLS = {
            0xFF0C0D10, 0xFF0B0D12, 0xFF08090C, 0xFF09090B, 0xFF121316, 0xFF060608, 0xFF18191E, 0xFF0C0C0E};
    private static final int[] BORDERS = {
            0xFF1C1C20, 0xFF1B222C, 0xFF18181B, 0xFF3F3F46, 0xFF27272A};

    public static int normalizeFill(int c) {
        for (int f : CARD_FILLS) if (c == f) return CARD;
        if (c == 0xFF121215) return INNER;
        return c;
    }

    public static int normalizeBorder(int c) {
        for (int b : BORDERS) if (c == b) return BORDER;
        // faint white-alpha hairlines
        if (Color.alpha(c) > 0 && Color.alpha(c) < 60 && Color.red(c) > 240 && Color.green(c) > 240) return BORDER;
        return c;
    }

    /** Cards/dialogs 14dp, inner boxes 10dp, small chips/buttons 6dp; tiny bars (<4dp) untouched. */
    public static float normalizeRadius(int px, float density) {
        float dp = px / density;
        if (dp >= 13) return 14f * density;
        if (dp >= 9) return 12f * density;
        if (dp >= 5) return 8f * density;
        return px;
    }
}
