package com.fishbowl.app.services;

import android.content.Context;
import android.util.Log;

import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Persistent, append-only log of everything that happens to Transmission downloads
 * (added, completed, failed, paused, cancelled, removed...). Stored as JSON lines in the app's
 * private files directory and trimmed automatically.
 */
public final class TransmissionHistory {

    private static final String TAG = "TransmissionHistory";
    private static final String FILE_NAME = "transmission_history.jsonl";
    private static final int MAX_LINES = 1500;
    private static final int TRIM_TO = 1000;

    public static final String ADDED = "ADDED";
    public static final String COMPLETED = "COMPLETED";
    public static final String FAILED = "FAILED";
    public static final String RECOVERED = "RECOVERED";
    public static final String PAUSED = "PAUSED";
    public static final String RESUMED = "RESUMED";
    public static final String CANCELLED = "CANCELLED";
    public static final String REMOVED = "REMOVED";
    public static final String AUTO_RECHECK = "AUTO-RECHECK";
    public static final String AUTO_CLEANUP = "AUTO-CLEANUP";

    public static final class Entry {
        public final long timeMs;
        public final String event;
        public final String name;
        public final String detail;

        Entry(long timeMs, String event, String name, String detail) {
            this.timeMs = timeMs;
            this.event = event;
            this.name = name;
            this.detail = detail;
        }
    }

    private TransmissionHistory() {}

    private static File file(Context ctx) {
        return new File(ctx.getApplicationContext().getFilesDir(), FILE_NAME);
    }

    public static synchronized void append(Context ctx, String event, String name, String detail) {
        try {
            JSONObject o = new JSONObject();
            o.put("t", System.currentTimeMillis());
            o.put("e", event);
            o.put("n", name == null ? "" : name);
            o.put("d", detail == null ? "" : detail);
            File f = file(ctx);
            try (FileOutputStream fos = new FileOutputStream(f, true)) {
                fos.write((o.toString() + "\n").getBytes(StandardCharsets.UTF_8));
            }
            if (f.length() > 400_000L) trim(f);
        } catch (Throwable t) {
            Log.w(TAG, "append failed: " + t.getMessage());
        }
    }

    private static void trim(File f) {
        try {
            List<String> lines = readLines(f);
            if (lines.size() <= MAX_LINES && f.length() < 800_000L) return;
            List<String> keep = lines.subList(Math.max(0, lines.size() - TRIM_TO), lines.size());
            try (FileOutputStream fos = new FileOutputStream(f, false)) {
                for (String l : keep) fos.write((l + "\n").getBytes(StandardCharsets.UTF_8));
            }
        } catch (Throwable t) {
            Log.w(TAG, "trim failed: " + t.getMessage());
        }
    }

    private static List<String> readLines(File f) throws Exception {
        List<String> lines = new ArrayList<String>();
        if (!f.exists()) return lines;
        try (BufferedReader br = new BufferedReader(new InputStreamReader(new FileInputStream(f), StandardCharsets.UTF_8))) {
            String line;
            while ((line = br.readLine()) != null) {
                if (!line.trim().isEmpty()) lines.add(line);
            }
        }
        return lines;
    }

    /** Returns up to {@code limit} entries, newest first. */
    public static synchronized List<Entry> read(Context ctx, int limit) {
        List<Entry> out = new ArrayList<Entry>();
        try {
            List<String> lines = readLines(file(ctx));
            Collections.reverse(lines);
            for (String l : lines) {
                if (out.size() >= limit) break;
                try {
                    JSONObject o = new JSONObject(l);
                    out.add(new Entry(o.optLong("t"), o.optString("e"), o.optString("n"), o.optString("d")));
                } catch (Throwable ignored) {}
            }
        } catch (Throwable t) {
            Log.w(TAG, "read failed: " + t.getMessage());
        }
        return out;
    }

    public static synchronized void clear(Context ctx) {
        try {
            File f = file(ctx);
            if (f.exists()) f.delete();
        } catch (Throwable ignored) {}
    }
}
