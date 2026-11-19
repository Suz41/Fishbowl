package com.fishbowl.app;

import android.Manifest;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.job.JobInfo;
import android.app.job.JobParameters;
import android.app.job.JobScheduler;
import android.app.job.JobService;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.os.Build;

import com.termux.R;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;

/** Background update checks, system notifications and per-channel (stable / beta) notification settings. */
public final class UpdateNotifier {
    private UpdateNotifier() {}

    public static final String EXTRA_OPEN_UPDATE = "fishbowl_open_update";
    public static final String EXTRA_CHANNEL = "fishbowl_update_channel";
    private static final String NOTIF_CHANNEL_ID = "fishbowl_updates";
    private static final int JOB_ID = 74021;
    private static final String RELEASES_URL = "https://api.github.com/repos/MedRAM-RAM/Jellyarr/releases?per_page=30";

    /** Result of one channel lookup. */
    public static final class Latest {
        public int code = -1;
        public String name = "";
        public String notes = "";
        public boolean found() { return code > 0; }
    }

    public static final class Result {
        public final Latest stable = new Latest();
        public final Latest beta = new Latest();
        public Latest get(String channel) { return UpdateManager.CHANNEL_STABLE.equals(channel) ? stable : beta; }
    }

    private static SharedPreferences p(Context c) {
        return c.getApplicationContext().getSharedPreferences("fishbowl_update_notify", Context.MODE_PRIVATE);
    }

    public static boolean systemNotify(Context c, String ch) { return p(c).getBoolean("sys_" + ch, true); }
    public static void setSystemNotify(Context c, String ch, boolean v) { p(c).edit().putBoolean("sys_" + ch, v).apply(); schedule(c); }
    public static boolean inAppNotify(Context c, String ch) { return p(c).getBoolean("inapp_" + ch, true); }
    public static void setInAppNotify(Context c, String ch, boolean v) { p(c).edit().putBoolean("inapp_" + ch, v).apply(); }
    public static int intervalHours(Context c) { return p(c).getInt("interval_h", 12); }
    public static void setIntervalHours(Context c, int h) { p(c).edit().putInt("interval_h", h).apply(); schedule(c); }
    public static int dismissedCode(Context c, String ch) { return p(c).getInt("dismissed_" + ch, 0); }
    public static void setDismissed(Context c, String ch, int code) { p(c).edit().putInt("dismissed_" + ch, code).apply(); }
    public static int lastSeenVersion(Context c) { return p(c).getInt("last_seen_version", 0); }
    public static void setLastSeenVersion(Context c, int v) { p(c).edit().putInt("last_seen_version", v).apply(); }

    // ------------------------------------------------------------------ lookup

    /** Blocking network call: newest stable and newest beta (any release) from the Jellyarr releases. */
    public static Result fetchLatest() throws Exception {
        HttpURLConnection conn = null;
        try {
            conn = (HttpURLConnection) new URL(RELEASES_URL).openConnection();
            conn.setConnectTimeout(10000);
            conn.setReadTimeout(10000);
            conn.setRequestProperty("User-Agent", "Fishbowl-Updater");
            conn.setRequestProperty("Accept", "application/vnd.github.v3+json");
            if (conn.getResponseCode() != 200) throw new java.io.IOException("HTTP " + conn.getResponseCode());
            BufferedReader in = new BufferedReader(new InputStreamReader(conn.getInputStream()));
            StringBuilder sb = new StringBuilder();
            String line;
            while ((line = in.readLine()) != null) sb.append(line);
            in.close();
            JSONArray arr = new JSONArray(sb.toString());
            Result r = new Result();
            for (int i = 0; i < arr.length(); i++) {
                JSONObject rel = arr.getJSONObject(i);
                if (rel.optBoolean("draft", false)) continue;
                int code = UpdateManager.extractVersionCode(rel);
                if (code <= 0) continue;
                boolean pre = rel.optBoolean("prerelease", false);
                String tag = rel.optString("tag_name", "").replaceFirst("^build-\\d+-", "");
                String notes = cleanNotes(rel.optString("body", ""));
                if (!pre && code > r.stable.code) fill(r.stable, code, tag, notes);
                if (code > r.beta.code) fill(r.beta, code, tag, notes);
            }
            return r;
        } finally {
            if (conn != null) conn.disconnect();
        }
    }

    private static void fill(Latest l, int code, String tag, String notes) {
        l.code = code;
        l.name = tag.startsWith("v") ? tag.substring(1) : tag;
        l.notes = notes;
    }

    private static String cleanNotes(String body) {
        StringBuilder sb = new StringBuilder();
        for (String line : body.split("\n")) {
            String t = line.trim().toLowerCase();
            if (t.startsWith("versioncode") || t.startsWith("sha256")) continue;
            sb.append(line).append('\n');
        }
        return sb.toString().trim();
    }

    // ------------------------------------------------------------------ background work

    /** Called by the job: notify for each channel that is enabled and has an unseen newer version. */
    static void runBackground(Context ctx) {
        try {
            Result r = fetchLatest();
            int cur = UpdateManager.getInstance(ctx).getCurrentVersionCode(ctx);
            String[] chans = {UpdateManager.CHANNEL_STABLE, UpdateManager.CHANNEL_BETA};
            for (String ch : chans) {
                Latest l = r.get(ch);
                if (!systemNotify(ctx, ch) || !l.found() || l.code <= cur) continue;
                if (p(ctx).getInt("notified_" + ch, 0) >= l.code) continue;
                // A newer stable build is also a newer beta build: avoid double notification
                if (UpdateManager.CHANNEL_BETA.equals(ch) && r.stable.code == r.beta.code
                        && systemNotify(ctx, UpdateManager.CHANNEL_STABLE)) continue;
                if (notify(ctx, ch, l)) p(ctx).edit().putInt("notified_" + ch, l.code).apply();
            }
        } catch (Throwable ignored) {
        }
    }

    private static boolean notify(Context ctx, String ch, Latest l) {
        try {
            if (Build.VERSION.SDK_INT >= 33 && ctx.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)
                    != PackageManager.PERMISSION_GRANTED) return false;
            NotificationManager nm = (NotificationManager) ctx.getSystemService(Context.NOTIFICATION_SERVICE);
            if (nm == null) return false;
            if (Build.VERSION.SDK_INT >= 26) {
                nm.createNotificationChannel(new NotificationChannel(NOTIF_CHANNEL_ID, "App updates", NotificationManager.IMPORTANCE_DEFAULT));
            }
            Intent open = new Intent(ctx, FishbowlActivity.class);
            open.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_SINGLE_TOP);
            open.putExtra(EXTRA_OPEN_UPDATE, true);
            open.putExtra(EXTRA_CHANNEL, ch);
            int flags = PendingIntent.FLAG_UPDATE_CURRENT | (Build.VERSION.SDK_INT >= 23 ? PendingIntent.FLAG_IMMUTABLE : 0);
            PendingIntent pi = PendingIntent.getActivity(ctx, ch.hashCode(), open, flags);
            String label = UpdateManager.CHANNEL_STABLE.equals(ch) ? "Stable" : "Beta";
            Notification.Builder b = Build.VERSION.SDK_INT >= 26
                    ? new Notification.Builder(ctx, NOTIF_CHANNEL_ID) : new Notification.Builder(ctx);
            b.setSmallIcon(R.drawable.ic_service_notification)
                    .setContentTitle("Fishbowl update available (" + label + ")")
                    .setContentText("v" + l.name + " - tap to see what's new and install")
                    .setStyle(new Notification.BigTextStyle().bigText("v" + l.name + "\n" + l.notes))
                    .setContentIntent(pi)
                    .setAutoCancel(true);
            nm.notify(7400 + (UpdateManager.CHANNEL_STABLE.equals(ch) ? 1 : 2), b.build());
            return true;
        } catch (Throwable t) {
            return false;
        }
    }

    // ------------------------------------------------------------------ scheduling

    public static void schedule(Context c) {
        try {
            if (Build.VERSION.SDK_INT < 21) return;
            Context app = c.getApplicationContext();
            JobScheduler js = (JobScheduler) app.getSystemService(Context.JOB_SCHEDULER_SERVICE);
            if (js == null) return;
            boolean any = systemNotify(app, UpdateManager.CHANNEL_STABLE) || systemNotify(app, UpdateManager.CHANNEL_BETA);
            if (!any) { js.cancel(JOB_ID); return; }
            long interval = Math.max(1, intervalHours(app)) * 60L * 60L * 1000L;
            JobInfo info = new JobInfo.Builder(JOB_ID, new ComponentName(app, UpdateJobService.class))
                    .setRequiredNetworkType(JobInfo.NETWORK_TYPE_ANY)
                    .setPeriodic(interval)
                    .setPersisted(true)
                    .build();
            js.schedule(info);
        } catch (Throwable ignored) {
        }
    }

    /** Periodic job entry point. */
    public static final class UpdateJobService extends JobService {
        @Override public boolean onStartJob(final JobParameters params) {
            new Thread(new Runnable() {
                @Override public void run() {
                    runBackground(getApplicationContext());
                    jobFinished(params, false);
                }
            }, "fishbowl-update-check").start();
            return true;
        }
        @Override public boolean onStopJob(JobParameters params) { return true; }
    }
}
