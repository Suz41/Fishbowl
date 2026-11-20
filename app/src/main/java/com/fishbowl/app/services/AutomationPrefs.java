package com.fishbowl.app.services;

import android.content.Context;
import android.content.SharedPreferences;

import java.util.HashSet;
import java.util.Locale;
import java.util.Set;

/**
 * Persistent settings for the built-in automations that replace the old Tasker/Termux jobs:
 * Transmission auto-cleanup, auto-recheck and the per-service "Wi-Fi only" network policy.
 */
public final class AutomationPrefs {

    private static final String PREFS_NAME = "fishbowl_automation";
    private static final String KEY_CLEANUP_ENABLED = "transmission_cleanup_enabled";
    private static final String KEY_CLEANUP_HOURS = "transmission_cleanup_hours";
    private static final String KEY_CLEANUP_LAST_RUN = "transmission_cleanup_last_run";
    private static final String KEY_CLEANUP_LAST_SUMMARY = "transmission_cleanup_last_summary";
    private static final String KEY_WIFI_ONLY_PREFIX = "wifi_only_";
    private static final String KEY_AUTO_RECHECK = "transmission_auto_recheck";
    private static final String KEY_WIFI_PAUSED = "wifi_paused_ids";

    public static final int DEFAULT_CLEANUP_HOURS = 48;
    public static final int MIN_CLEANUP_HOURS = 1;
    public static final int MAX_CLEANUP_HOURS = 24 * 365;

    private AutomationPrefs() {}

    private static SharedPreferences prefs(Context ctx) {
        return ctx.getApplicationContext().getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
    }

    private static String normalize(String id) {
        return id == null ? "" : id.trim().toLowerCase(Locale.ROOT);
    }

    // ---- Transmission auto-cleanup ------------------------------------------------------------

    public static boolean isCleanupEnabled(Context ctx) {
        return prefs(ctx).getBoolean(KEY_CLEANUP_ENABLED, false);
    }

    public static void setCleanupEnabled(Context ctx, boolean enabled) {
        prefs(ctx).edit().putBoolean(KEY_CLEANUP_ENABLED, enabled).apply();
    }

    public static int getCleanupHours(Context ctx) {
        int h = prefs(ctx).getInt(KEY_CLEANUP_HOURS, DEFAULT_CLEANUP_HOURS);
        return Math.max(MIN_CLEANUP_HOURS, Math.min(MAX_CLEANUP_HOURS, h));
    }

    public static void setCleanupHours(Context ctx, int hours) {
        int h = Math.max(MIN_CLEANUP_HOURS, Math.min(MAX_CLEANUP_HOURS, hours));
        prefs(ctx).edit().putInt(KEY_CLEANUP_HOURS, h).apply();
    }

    public static long getCleanupLastRun(Context ctx) {
        return prefs(ctx).getLong(KEY_CLEANUP_LAST_RUN, 0L);
    }

    public static String getCleanupLastSummary(Context ctx) {
        return prefs(ctx).getString(KEY_CLEANUP_LAST_SUMMARY, "");
    }

    public static void recordCleanupRun(Context ctx, String summary) {
        prefs(ctx).edit()
                .putLong(KEY_CLEANUP_LAST_RUN, System.currentTimeMillis())
                .putString(KEY_CLEANUP_LAST_SUMMARY, summary == null ? "" : summary)
                .apply();
    }

    // ---- Transmission auto-recheck on error (default ON) --------------------------------------

    public static boolean isAutoRecheckEnabled(Context ctx) {
        return prefs(ctx).getBoolean(KEY_AUTO_RECHECK, true);
    }

    public static void setAutoRecheckEnabled(Context ctx, boolean enabled) {
        prefs(ctx).edit().putBoolean(KEY_AUTO_RECHECK, enabled).apply();
    }

    // ---- Per-service Wi-Fi only ---------------------------------------------------------------

    public static boolean isWifiOnly(Context ctx, String serviceId) {
        String id = normalize(serviceId);
        if (id.isEmpty()) return false;
        return prefs(ctx).getBoolean(KEY_WIFI_ONLY_PREFIX + id, false);
    }

    public static void setWifiOnly(Context ctx, String serviceId, boolean enabled) {
        String id = normalize(serviceId);
        if (id.isEmpty()) return;
        prefs(ctx).edit().putBoolean(KEY_WIFI_ONLY_PREFIX + id, enabled).apply();
    }

    /** Services that were stopped (or whose start was deferred) by the Wi-Fi only policy. */
    public static synchronized Set<String> getWifiPaused(Context ctx) {
        Set<String> stored = prefs(ctx).getStringSet(KEY_WIFI_PAUSED, null);
        return stored == null ? new HashSet<String>() : new HashSet<String>(stored);
    }

    public static synchronized boolean isWifiPaused(Context ctx, String serviceId) {
        return getWifiPaused(ctx).contains(normalize(serviceId));
    }

    public static synchronized void addWifiPaused(Context ctx, String serviceId) {
        String id = normalize(serviceId);
        if (id.isEmpty()) return;
        Set<String> set = getWifiPaused(ctx);
        if (set.add(id)) prefs(ctx).edit().putStringSet(KEY_WIFI_PAUSED, set).apply();
    }

    public static synchronized void removeWifiPaused(Context ctx, String serviceId) {
        String id = normalize(serviceId);
        if (id.isEmpty()) return;
        Set<String> set = getWifiPaused(ctx);
        if (set.remove(id)) prefs(ctx).edit().putStringSet(KEY_WIFI_PAUSED, set).apply();
    }
}
