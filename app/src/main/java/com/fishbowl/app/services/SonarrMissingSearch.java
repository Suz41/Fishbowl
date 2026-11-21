package com.fishbowl.app.services;

import android.content.Context;
import android.util.Log;

import com.fishbowl.app.model.ManagedService;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Built-in replacement for the Tasker/Termux "missing-fix.sh" job: asks Sonarr to search for every
 * monitored episode that is missing, on a fixed interval, and offers a small client for the Sonarr tab.
 */
public final class SonarrMissingSearch {

    private static final String TAG = "SonarrMissingSearch";
    private static final String BASE = "http://127.0.0.1:8989/api/v3";

    private SonarrMissingSearch() {}

    public static final class MissingEpisode {
        public String seriesTitle = "";
        public int season;
        public int episode;
        public String title = "";
        public String airDate = "";
        public int episodeId;
    }

    public static final class Page {
        public final List<MissingEpisode> items = new ArrayList<>();
        public int totalRecords;
    }

    private static String apiKey(Context ctx) {
        MediaServiceManager mgr = MediaServiceManager.getInstance(ctx);
        return mgr == null ? null : mgr.getServiceApiKey("sonarr");
    }

    private static String request(String method, String path, String body, String key, int timeoutMs) throws Exception {
        HttpURLConnection conn = null;
        try {
            conn = (HttpURLConnection) new URL(BASE + path).openConnection();
            conn.setConnectTimeout(timeoutMs);
            conn.setReadTimeout(timeoutMs);
            conn.setRequestMethod(method);
            conn.setRequestProperty("X-Api-Key", key);
            if (body != null) {
                conn.setRequestProperty("Content-Type", "application/json");
                conn.setDoOutput(true);
                OutputStream os = conn.getOutputStream();
                os.write(body.getBytes(StandardCharsets.UTF_8));
                os.close();
            }
            int code = conn.getResponseCode();
            java.io.InputStream is = code >= 400 ? conn.getErrorStream() : conn.getInputStream();
            StringBuilder sb = new StringBuilder();
            if (is != null) {
                BufferedReader br = new BufferedReader(new InputStreamReader(is, StandardCharsets.UTF_8));
                String line;
                while ((line = br.readLine()) != null) sb.append(line);
                br.close();
            }
            if (code >= 400) throw new Exception("Sonarr returned HTTP " + code);
            return sb.toString();
        } finally {
            if (conn != null) conn.disconnect();
        }
    }

    /** Fetches one page of monitored missing episodes (newest air date first). */
    public static Page fetchMissing(Context ctx, int page, int pageSize) throws Exception {
        String key = apiKey(ctx);
        if (key == null || key.isEmpty()) throw new Exception("Sonarr API key not found. Start Sonarr once first.");
        String resp = request("GET", "/wanted/missing?page=" + page + "&pageSize=" + pageSize
                + "&monitored=true&includeSeries=true&sortKey=airDateUtc&sortDirection=descending", null, key, 15000);
        JSONObject o = new JSONObject(resp);
        Page p = new Page();
        p.totalRecords = o.optInt("totalRecords", 0);
        JSONArray recs = o.optJSONArray("records");
        if (recs != null) {
            for (int i = 0; i < recs.length(); i++) {
                JSONObject r = recs.getJSONObject(i);
                MissingEpisode e = new MissingEpisode();
                JSONObject s = r.optJSONObject("series");
                e.seriesTitle = s != null ? s.optString("title", "") : "";
                e.season = r.optInt("seasonNumber");
                e.episode = r.optInt("episodeNumber");
                e.title = r.optString("title", "");
                String air = r.optString("airDate", "");
                e.airDate = air;
                e.episodeId = r.optInt("id");
                p.items.add(e);
            }
        }
        return p;
    }

    /** Searches for one episode only. */
    public static boolean searchEpisode(Context ctx, int episodeId) {
        try {
            String key = apiKey(ctx);
            if (key == null || key.isEmpty()) return false;
            request("POST", "/command", "{\"name\":\"EpisodeSearch\",\"episodeIds\":[" + episodeId + "]}", key, 15000);
            return true;
        } catch (Throwable t) {
            Log.w(TAG, "EpisodeSearch failed: " + t.getMessage());
            return false;
        }
    }

    /**
     * Runs one pass: counts the monitored missing episodes and, unless {@code dryRun}, queues a
     * Sonarr "MissingEpisodeSearch" for all of them. Returns a one-line summary.
     */
    public static synchronized String run(Context ctx, boolean dryRun) {
        Context app = ctx.getApplicationContext();
        try {
            MediaServiceManager mgr = MediaServiceManager.getInstance(app);
            if (mgr == null || !mgr.isServiceRunning("sonarr")) {
                return "Skipped: Sonarr is not running.";
            }
            String key = mgr.getServiceApiKey("sonarr");
            if (key == null || key.isEmpty()) return "Skipped: Sonarr API key not found.";

            int missing = fetchMissing(app, 1, 1).totalRecords;
            if (missing == 0) {
                String s = "No monitored missing episodes.";
                if (!dryRun) AutomationPrefs.recordSonarrMissingRun(app, s);
                return s;
            }
            if (dryRun) {
                return String.format(Locale.US, "%d monitored missing episode(s) would be searched.", missing);
            }
            request("POST", "/command", "{\"name\":\"MissingEpisodeSearch\",\"monitored\":true}", key, 20000);
            String s = String.format(Locale.US, "Search queued for %d monitored missing episode(s).", missing);
            AutomationPrefs.recordSonarrMissingRun(app, s);
            ManagedService sv = mgr.getService("sonarr");
            if (sv != null) sv.appendLog("[Auto-search] " + s);
            return s;
        } catch (Throwable t) {
            String s = "Failed: " + t.getMessage();
            if (!dryRun) AutomationPrefs.recordSonarrMissingRun(app, s);
            Log.w(TAG, s);
            return s;
        }
    }
}
