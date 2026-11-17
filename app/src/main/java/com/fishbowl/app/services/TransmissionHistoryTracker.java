package com.fishbowl.app.services;

import android.content.Context;
import android.util.Log;

import com.fishbowl.app.MediaStorageManager;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Locale;

/**
 * Polls Transmission and turns state changes into {@link TransmissionHistory} entries.
 * The last known state is persisted, so events that happen while the app is closed are still
 * detected (and attributed) on the next run.
 */
public final class TransmissionHistoryTracker {

    private static final String TAG = "TransmissionHistoryTracker";
    private static final String SNAPSHOT_FILE = "transmission_snapshot.json";

    private static final class Snap {
        int id;
        String name;
        double pct;
        int status;
        String err;
        boolean complete;
        long size;
    }

    /** Removal reasons noted just before a removal is sent to Transmission (key: id). */
    private static final Map<Integer, String> removalNotes = new HashMap<Integer, String>();

    private TransmissionHistoryTracker() {}

    /** Called right before a torrent is removed, so the log can say who removed it and whether files went too. */
    public static synchronized void noteRemoval(int torrentId, boolean deleteData, String source) {
        if (removalNotes.containsKey(torrentId)) return;
        removalNotes.put(torrentId, (source == null ? "manual" : source) + (deleteData ? ", files deleted" : ", files kept"));
    }

    private static String key(int id, String name) {
        return id + "|" + name;
    }

    public static synchronized void run(Context ctx) {
        Context app = ctx.getApplicationContext();
        MediaStorageManager msm = MediaStorageManager.getInstance(app);
        if (msm == null) return;

        List<MediaStorageManager.TorrentInfo> torrents = msm.queryTransmissionTorrents();
        if (!msm.lastTransmissionQueryOk) return; // Transmission unreachable: never guess removals.

        Map<String, Snap> old = loadSnapshot(app);
        boolean firstRun = old == null;
        if (old == null) old = new HashMap<String, Snap>();

        Map<String, Snap> now = new HashMap<String, Snap>();
        for (MediaStorageManager.TorrentInfo t : torrents) {
            Snap s = new Snap();
            s.id = t.id;
            s.name = t.name == null ? ("Torrent #" + t.id) : t.name;
            s.pct = t.percentDone;
            s.status = t.status;
            s.err = t.errorString == null ? "" : t.errorString.trim();
            s.complete = t.percentDone >= 1.0 && t.leftUntilDone == 0;
            s.size = Math.max(0L, t.sizeWhenDone);
            String k = key(s.id, s.name);
            now.put(k, s);

            Snap prev = old.get(k);
            if (prev == null) {
                if (!firstRun) {
                    TransmissionHistory.append(app, TransmissionHistory.ADDED, s.name, "Size " + TransmissionAutoCleanup.formatBytes(s.size));
                    if (s.complete) {
                        TransmissionHistory.append(app, TransmissionHistory.COMPLETED, s.name, "Already complete when added");
                    }
                }
                continue;
            }
            if (!prev.complete && s.complete) {
                TransmissionHistory.append(app, TransmissionHistory.COMPLETED, s.name, TransmissionAutoCleanup.formatBytes(s.size));
            }
            if (prev.err.isEmpty() && !s.err.isEmpty()) {
                TransmissionHistory.append(app, TransmissionHistory.FAILED, s.name, s.err);
            } else if (!prev.err.isEmpty() && s.err.isEmpty()) {
                TransmissionHistory.append(app, TransmissionHistory.RECOVERED, s.name, "Error cleared");
            }
            if (prev.status != 0 && s.status == 0 && !s.complete) {
                TransmissionHistory.append(app, TransmissionHistory.PAUSED, s.name, String.format(Locale.US, "at %.1f%%", s.pct * 100));
            } else if (prev.status == 0 && s.status != 0 && !s.complete) {
                TransmissionHistory.append(app, TransmissionHistory.RESUMED, s.name, String.format(Locale.US, "from %.1f%%", s.pct * 100));
            }
        }

        for (Map.Entry<String, Snap> e : old.entrySet()) {
            if (now.containsKey(e.getKey())) continue;
            Snap gone = e.getValue();
            String note = removalNotes.remove(gone.id);
            String event = gone.complete ? TransmissionHistory.REMOVED : TransmissionHistory.CANCELLED;
            String detail = (gone.complete ? "Finished download removed" : String.format(Locale.US, "Removed at %.1f%%", gone.pct * 100))
                    + (note != null ? " (" + note + ")" : " (removed from Transmission)");
            TransmissionHistory.append(app, event, gone.name, detail);
        }
        removalNotes.clear();

        saveSnapshot(app, now);
    }

    private static File snapFile(Context app) {
        return new File(app.getFilesDir(), SNAPSHOT_FILE);
    }

    private static Map<String, Snap> loadSnapshot(Context app) {
        File f = snapFile(app);
        if (!f.exists()) return null;
        try {
            ByteArrayOutputStream bos = new ByteArrayOutputStream();
            try (FileInputStream fis = new FileInputStream(f)) {
                byte[] b = new byte[4096];
                int n;
                while ((n = fis.read(b)) != -1) bos.write(b, 0, n);
            }
            JSONArray arr = new JSONArray(new String(bos.toByteArray(), StandardCharsets.UTF_8));
            Map<String, Snap> m = new HashMap<String, Snap>();
            for (int i = 0; i < arr.length(); i++) {
                JSONObject o = arr.getJSONObject(i);
                Snap s = new Snap();
                s.id = o.optInt("id");
                s.name = o.optString("n");
                s.pct = o.optDouble("p");
                s.status = o.optInt("s");
                s.err = o.optString("e");
                s.complete = o.optBoolean("c");
                s.size = o.optLong("z");
                m.put(key(s.id, s.name), s);
            }
            return m;
        } catch (Throwable t) {
            Log.w(TAG, "snapshot load failed: " + t.getMessage());
            return null;
        }
    }

    private static void saveSnapshot(Context app, Map<String, Snap> m) {
        try {
            JSONArray arr = new JSONArray();
            for (Snap s : m.values()) {
                JSONObject o = new JSONObject();
                o.put("id", s.id);
                o.put("n", s.name);
                o.put("p", s.pct);
                o.put("s", s.status);
                o.put("e", s.err);
                o.put("c", s.complete);
                o.put("z", s.size);
                arr.put(o);
            }
            try (FileOutputStream fos = new FileOutputStream(snapFile(app), false)) {
                fos.write(arr.toString().getBytes(StandardCharsets.UTF_8));
            }
        } catch (Throwable t) {
            Log.w(TAG, "snapshot save failed: " + t.getMessage());
        }
    }
}
