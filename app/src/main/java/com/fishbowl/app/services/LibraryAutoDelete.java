package com.fishbowl.app.services;

import android.content.Context;
import android.util.Log;

import com.fishbowl.app.model.ManagedService;

import java.util.List;
import java.util.Locale;

/**
 * Deletes watched movies / episodes once the configured time has passed since they were last
 * watched. Movies and series have independent switches and periods.
 */
public final class LibraryAutoDelete {

    private static final String TAG = "LibraryAutoDelete";
    private static final long MIN_SANE_CLOCK_MS = 1_700_000_000_000L;

    private LibraryAutoDelete() {}

    public static synchronized String run(Context ctx, boolean dryRun) {
        Context app = ctx.getApplicationContext();
        boolean movies = LibraryPrefs.isMoviesEnabled(app);
        boolean series = LibraryPrefs.isSeriesEnabled(app);
        if (!dryRun && !movies && !series) return "Auto-delete is off.";
        if (System.currentTimeMillis() < MIN_SANE_CLOCK_MS) return "Skipped: device clock looks wrong";

        long now = System.currentTimeMillis();
        long movieCutoff = now - LibraryPrefs.getMoviesHours(app) * 3600_000L;
        long seriesCutoff = now - LibraryPrefs.getSeriesHours(app) * 3600_000L;

        int deletedMovies = 0, deletedEpisodes = 0, failed = 0, eligibleMovies = 0, eligibleEpisodes = 0;
        try {
            List<JellyfinLibraryClient.LibItem> watched = JellyfinLibraryClient.recentlyWatched(app, 500);
            for (JellyfinLibraryClient.LibItem it : watched) {
                boolean isMovie = "Movie".equals(it.type);
                boolean isEpisode = "Episode".equals(it.type);
                if (isMovie && (dryRun ? true : movies) && it.lastPlayedMs <= movieCutoff) {
                    eligibleMovies++;
                    if (dryRun) continue;
                    if (JellyfinLibraryClient.deleteItem(app, it.id)) {
                        deletedMovies++;
                        log("Deleted watched movie: " + it.displayTitle());
                    } else {
                        failed++;
                    }
                } else if (isEpisode && (dryRun ? true : series) && it.lastPlayedMs <= seriesCutoff) {
                    eligibleEpisodes++;
                    if (dryRun) continue;
                    if (JellyfinLibraryClient.deleteItem(app, it.id)) {
                        deletedEpisodes++;
                        log("Deleted watched episode: " + it.displayTitle());
                    } else {
                        failed++;
                    }
                }
            }
        } catch (Throwable t) {
            String msg = "Skipped: " + t.getMessage();
            if (!dryRun) LibraryPrefs.recordRun(app, msg);
            return msg;
        }

        String summary;
        if (dryRun) {
            summary = String.format(Locale.US, "Would delete %d movie(s) and %d episode(s) with the current periods.",
                    eligibleMovies, eligibleEpisodes);
        } else {
            summary = String.format(Locale.US, "Deleted %d movie(s) and %d episode(s)%s.",
                    deletedMovies, deletedEpisodes, failed > 0 ? ", " + failed + " failed" : "");
            LibraryPrefs.recordRun(app, summary);
        }
        return summary;
    }

    private static void log(String msg) {
        Log.i(TAG, msg);
        try {
            MediaServiceManager mgr = MediaServiceManager.getInstance();
            ManagedService jf = mgr != null ? mgr.getService("jellyfin") : null;
            if (jf != null) jf.appendLog("[Library auto-delete] " + msg);
        } catch (Throwable ignored) {}
    }
}
