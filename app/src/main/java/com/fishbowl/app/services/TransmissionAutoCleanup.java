package com.fishbowl.app.services;

import android.content.Context;
import android.util.Log;

import com.fishbowl.app.MediaStorageManager;
import com.fishbowl.app.model.ManagedService;

import java.util.List;
import java.util.Locale;

/**
 * Built-in replacement for the Tasker/Termux "transmission_cleanup.sh" job: removes completed
 * torrents from Transmission together with their downloaded files once they have been finished
 * for longer than the configured number of hours.
 */
public final class TransmissionAutoCleanup {

    private static final String TAG = "TransmissionAutoCleanup";
    // Refuse to act if the device clock is obviously wrong (before Nov 2023): ages would be meaningless.
    private static final long MIN_SANE_CLOCK_MS = 1_700_000_000_000L;

    private TransmissionAutoCleanup() {}

    public static final class Result {
        public final boolean dryRun;
        public final int hours;
        public final int checked;
        public final int eligible;
        public final int removed;
        public final int failed;
        public final long eligibleBytes;
        public final long removedBytes;
        public final String skippedReason;

        Result(boolean dryRun, int hours, int checked, int eligible, int removed, int failed,
               long eligibleBytes, long removedBytes, String skippedReason) {
            this.dryRun = dryRun;
            this.hours = hours;
            this.checked = checked;
            this.eligible = eligible;
            this.removed = removed;
            this.failed = failed;
            this.eligibleBytes = eligibleBytes;
            this.removedBytes = removedBytes;
            this.skippedReason = skippedReason;
        }

        public String describe() {
            if (skippedReason != null) return "Skipped: " + skippedReason;
            if (dryRun) {
                return String.format(Locale.US,
                        "%d of %d torrent(s) finished more than %d h ago (%s) would be removed.",
                        eligible, checked, hours, formatBytes(eligibleBytes));
            }
            return String.format(Locale.US,
                    "Removed %d of %d torrent(s) finished more than %d h ago (%s freed)%s.",
                    removed, checked, hours, formatBytes(removedBytes),
                    failed > 0 ? ", " + failed + " failed" : "");
        }
    }

    public static String formatBytes(long bytes) {
        if (bytes < 1024) return bytes + " B";
        double v = bytes / 1024.0;
        String[] units = {"KB", "MB", "GB", "TB"};
        int i = 0;
        while (v >= 1024 && i < units.length - 1) {
            v /= 1024.0;
            i++;
        }
        return String.format(Locale.US, "%.1f %s", v, units[i]);
    }

    /**
     * Runs one cleanup pass. When {@code dryRun} is true nothing is deleted and only the
     * eligible torrents are counted.
     */
    public static synchronized Result run(Context ctx, boolean dryRun) {
        Context app = ctx.getApplicationContext();
        int hours = AutomationPrefs.getCleanupHours(app);

        if (System.currentTimeMillis() < MIN_SANE_CLOCK_MS) {
            return new Result(dryRun, hours, 0, 0, 0, 0, 0, 0, "device clock looks wrong");
        }

        MediaStorageManager msm = MediaStorageManager.getInstance(app);
        if (msm == null) {
            return new Result(dryRun, hours, 0, 0, 0, 0, 0, 0, "storage manager unavailable");
        }

        long cutoffSec = System.currentTimeMillis() / 1000L - hours * 3600L;
        List<MediaStorageManager.TorrentInfo> torrents = msm.queryTransmissionTorrents();

        int eligible = 0, removed = 0, failed = 0;
        long eligibleBytes = 0, removedBytes = 0;

        for (MediaStorageManager.TorrentInfo t : torrents) {
            boolean complete = t.percentDone >= 1.0 && t.leftUntilDone == 0;
            // doneDate is the completion time in epoch seconds (0 = unknown, never auto-removed).
            if (!complete || t.doneDate <= 0 || t.doneDate > cutoffSec) continue;

            eligible++;
            long size = Math.max(0L, t.sizeWhenDone);
            eligibleBytes += size;
            if (dryRun) continue;

            TransmissionHistoryTracker.noteRemoval(t.id, true, "auto-cleanup");
            if (msm.removeTransmissionTorrent(t.id, true)) {
                removed++;
                removedBytes += size;
                Log.i(TAG, "Removed finished torrent with data: " + t.name);
            } else {
                failed++;
                Log.w(TAG, "Could not remove torrent: " + t.name);
            }
        }

        Result result = new Result(dryRun, hours, torrents.size(), eligible, removed, failed,
                eligibleBytes, removedBytes, null);

        if (!dryRun) {
            AutomationPrefs.recordCleanupRun(app, result.describe());
            if (eligible > 0) {
                MediaServiceManager mgr = MediaServiceManager.getInstance();
                ManagedService tr = mgr != null ? mgr.getService("transmission") : null;
                if (tr != null) tr.appendLog("[Auto-cleanup] " + result.describe());
            }
        }
        return result;
    }
}
