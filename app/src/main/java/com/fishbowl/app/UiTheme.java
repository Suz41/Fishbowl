package com.fishbowl.app;

/**
 * Shape scale + surface helpers shared by every tab. Colours come from {@link AppTheme};
 * this class only normalises radii to the Material 3 shape scale.
 */
public final class UiTheme {
    private UiTheme() {}

    /** Kept for call-site compatibility: card / border / inner surface of the active theme. */
    public static int card() { return AppTheme.card(); }
    public static int border() { return AppTheme.outline(); }
    public static int inner() { return AppTheme.inner(); }

    public static int normalizeFill(int c) { return c; }

    public static int normalizeBorder(int c) { return c; }

    /** M3 shape scale: cards/dialogs 16dp, inner boxes 12dp, small chips 8dp; tiny bars (<4dp) untouched. */
    public static float normalizeRadius(int px, float density) {
        float dp = px / density;
        if (dp >= 13) return 16f * density;
        if (dp >= 9) return 12f * density;
        if (dp >= 5) return 8f * density;
        return px;
    }
}
