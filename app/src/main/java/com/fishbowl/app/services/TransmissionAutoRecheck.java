package com.fishbowl.app.services;

import android.content.Context;
import android.util.Log;

import com.fishbowl.app.MediaStorageManager;
import com.fishbowl.app.model.ManagedService;

import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * When a torrent reports any error, re-verifies its local data and starts it again.
 * Uses an exponential cool-down per torrent (5, 10, 20, 40, 80 minutes) and gives up after
 * {@link #MAX_ATTEMPTS} tries until the error clears, so a permanently failing tracker cannot
 * keep the CPU busy re-hashing the same data.
 */
public final class TransmissionAutoRecheck {

    private static final String TAG = "TransmissionAutoRecheck";
    private static final long BASE_COOLDOWN_MS = 5L * 60L * 1000L;
    private static final int MAX_ATTEMPTS = 5;

    private static final class Attempt {
        int count;
        long lastMs;
    }

    private static final Map<Integer, Attempt> attempts = new HashMap<Integer, Attempt>();

    private TransmissionAutoRecheck() {}

    public static synchronized int run(Context ctx) {
        Context app = ctx.getApplicationContext();
        MediaStorageManager msm = MediaStorageManager.getInstance(app);
        if (msm == null) return 0;

        List<MediaStorageManager.TorrentInfo> torrents = msm.queryTransmissionTorrents();
        long now = System.currentTimeMillis();
        int fired = 0;
        Set<Integer> stillFailing = new HashSet<Integer>();

        for (MediaStorageManager.TorrentInfo t : torrents) {
            if (t.getLifecycleState() != MediaStorageManager.DownloadLifecycleState.ERROR) continue;
            stillFailing.add(t.id);
            // Already being verified or waiting to be verified.
            if (t.status == 1 || t.status == 2) continue;

            Attempt a = attempts.get(t.id);
            if (a == null) {
                a = new Attempt();
                attempts.put(t.id, a);
            }
            if (a.count >= MAX_ATTEMPTS) continue;
            long cooldown = BASE_COOLDOWN_MS << a.count;
            if (a.count > 0 && now - a.lastMs < cooldown) continue;

            a.count++;
            a.lastMs = now;
            boolean verified = msm.recheckTransmissionTorrent(t.id);
            boolean started = verified && msm.resumeTransmissionTorrent(t.id);
            fired++;
            String msg = "[Auto-recheck] \"" + t.name + "\" error: " + t.errorString
                    + " -> recheck " + (verified ? "queued" : "FAILED")
                    + (started ? ", resumed" : "")
                    + " (attempt " + a.count + "/" + MAX_ATTEMPTS + ")";
            Log.i(TAG, msg);
            log(msg);
        }

        // Forget torrents whose error cleared (or that were removed).
        attempts.keySet().retainAll(stillFailing);
        return fired;
    }

    private static void log(String msg) {
        try {
            MediaServiceManager mgr = MediaServiceManager.getInstance();
            ManagedService tr = mgr != null ? mgr.getService("transmission") : null;
            if (tr != null) tr.appendLog(msg);
        } catch (Throwable ignored) {}
    }
}
