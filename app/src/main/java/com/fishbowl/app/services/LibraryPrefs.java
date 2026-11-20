package com.fishbowl.app.services;

import android.content.Context;
import android.content.SharedPreferences;

/** Settings for the Jellyfin library auto-delete (movies and series configured separately). */
public final class LibraryPrefs {

    private static final String PREFS = "fishbowl_library";
    private static final String K_MOVIES_ON = "auto_delete_movies_on";
    private static final String K_MOVIES_HOURS = "auto_delete_movies_hours";
    private static final String K_SERIES_ON = "auto_delete_series_on";
    private static final String K_SERIES_HOURS = "auto_delete_series_hours";
    private static final String K_LAST_RUN = "auto_delete_last_run";
    private static final String K_LAST_SUMMARY = "auto_delete_last_summary";
    private static final String K_TOKEN = "api_token";

    public static final int DEFAULT_HOURS = 72;
    public static final int MIN_HOURS = 1;
    public static final int MAX_HOURS = 24 * 365;

    private LibraryPrefs() {}

    private static SharedPreferences p(Context c) {
        return c.getApplicationContext().getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    private static int clamp(int h) {
        return Math.max(MIN_HOURS, Math.min(MAX_HOURS, h));
    }

    public static boolean isMoviesEnabled(Context c) { return p(c).getBoolean(K_MOVIES_ON, false); }
    public static void setMoviesEnabled(Context c, boolean v) { p(c).edit().putBoolean(K_MOVIES_ON, v).apply(); }
    public static int getMoviesHours(Context c) { return clamp(p(c).getInt(K_MOVIES_HOURS, DEFAULT_HOURS)); }
    public static void setMoviesHours(Context c, int h) { p(c).edit().putInt(K_MOVIES_HOURS, clamp(h)).apply(); }

    public static boolean isSeriesEnabled(Context c) { return p(c).getBoolean(K_SERIES_ON, false); }
    public static void setSeriesEnabled(Context c, boolean v) { p(c).edit().putBoolean(K_SERIES_ON, v).apply(); }
    public static int getSeriesHours(Context c) { return clamp(p(c).getInt(K_SERIES_HOURS, DEFAULT_HOURS)); }
    public static void setSeriesHours(Context c, int h) { p(c).edit().putInt(K_SERIES_HOURS, clamp(h)).apply(); }

    public static long getLastRun(Context c) { return p(c).getLong(K_LAST_RUN, 0L); }
    public static String getLastSummary(Context c) { return p(c).getString(K_LAST_SUMMARY, ""); }
    public static void recordRun(Context c, String summary) {
        p(c).edit().putLong(K_LAST_RUN, System.currentTimeMillis()).putString(K_LAST_SUMMARY, summary == null ? "" : summary).apply();
    }

    public static String getToken(Context c) { return p(c).getString(K_TOKEN, null); }
    public static void setToken(Context c, String t) {
        SharedPreferences.Editor e = p(c).edit();
        if (t == null) e.remove(K_TOKEN); else e.putString(K_TOKEN, t);
        e.apply();
    }
}
