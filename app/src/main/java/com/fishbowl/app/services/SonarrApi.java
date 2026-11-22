package com.fishbowl.app.services;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;

/**
 * Thin client for the local Sonarr v3/v4 REST API (http://127.0.0.1:8989/api/v3) used by the Sonarr tab.
 * Every method blocks: call it from a background thread.
 */
public final class SonarrApi {

    private static final String HOST = "http://127.0.0.1:8989";
    private static final String BASE = HOST + "/api/v3";

    private final String key;

    private SonarrApi(String key) {
        this.key = key;
    }

    /** Returns a client, or throws a readable error when Sonarr has no API key yet. */
    public static SonarrApi get(Context ctx) throws Exception {
        MediaServiceManager mgr = MediaServiceManager.getInstance(ctx.getApplicationContext());
        String k = mgr == null ? null : mgr.getServiceApiKey("sonarr");
        if (k == null || k.isEmpty()) throw new Exception("Sonarr API key not found. Start Sonarr once first.");
        return new SonarrApi(k);
    }

    public static String enc(String s) {
        try {
            return URLEncoder.encode(s, "UTF-8");
        } catch (Exception e) {
            return s;
        }
    }

    // ------------------------------------------------------------------ transport

    private String call(String method, String path, String body, int timeoutMs) throws Exception {
        HttpURLConnection conn = null;
        try {
            conn = (HttpURLConnection) new URL(BASE + path).openConnection();
            conn.setConnectTimeout(8000);
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
            InputStream is = code >= 400 ? conn.getErrorStream() : conn.getInputStream();
            StringBuilder sb = new StringBuilder();
            if (is != null) {
                BufferedReader br = new BufferedReader(new InputStreamReader(is, StandardCharsets.UTF_8));
                String line;
                while ((line = br.readLine()) != null) sb.append(line);
                br.close();
            }
            if (code >= 400) {
                String detail = sb.toString();
                String msg = "Sonarr returned HTTP " + code;
                try {
                    if (detail.startsWith("[")) {
                        JSONArray a = new JSONArray(detail);
                        if (a.length() > 0) msg += ": " + a.getJSONObject(0).optString("errorMessage", "");
                    } else if (detail.startsWith("{")) {
                        JSONObject o = new JSONObject(detail);
                        String m = o.optString("message", o.optString("errorMessage", ""));
                        if (!m.isEmpty()) msg += ": " + m;
                    }
                } catch (Throwable ignored) {}
                throw new Exception(msg);
            }
            return sb.toString();
        } finally {
            if (conn != null) conn.disconnect();
        }
    }

    private JSONArray arr(String path) throws Exception {
        String r = call("GET", path, null, 25000);
        return r.isEmpty() ? new JSONArray() : new JSONArray(r);
    }

    private JSONObject obj(String path) throws Exception {
        String r = call("GET", path, null, 25000);
        return r.isEmpty() ? new JSONObject() : new JSONObject(r);
    }

    /** Downloads a cover image (local MediaCover path or a remote URL). Returns null on failure. */
    public Bitmap loadImage(String url, int maxWidthPx) {
        HttpURLConnection conn = null;
        try {
            String full = url.startsWith("http") ? url : HOST + (url.startsWith("/") ? "" : "/") + url;
            conn = (HttpURLConnection) new URL(full).openConnection();
            conn.setConnectTimeout(8000);
            conn.setReadTimeout(20000);
            if (full.startsWith(HOST)) conn.setRequestProperty("X-Api-Key", key);
            if (conn.getResponseCode() >= 400) return null;
            InputStream is = conn.getInputStream();
            ByteArrayOutputStream bos = new ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            int n;
            while ((n = is.read(buf)) > 0) bos.write(buf, 0, n);
            is.close();
            byte[] data = bos.toByteArray();
            BitmapFactory.Options o = new BitmapFactory.Options();
            o.inJustDecodeBounds = true;
            BitmapFactory.decodeByteArray(data, 0, data.length, o);
            int sample = 1;
            while (o.outWidth / (sample * 2) >= maxWidthPx) sample *= 2;
            BitmapFactory.Options o2 = new BitmapFactory.Options();
            o2.inSampleSize = sample;
            return BitmapFactory.decodeByteArray(data, 0, data.length, o2);
        } catch (Throwable t) {
            return null;
        } finally {
            if (conn != null) conn.disconnect();
        }
    }

    // ------------------------------------------------------------------ series

    public JSONArray series() throws Exception { return arr("/series"); }

    public JSONObject series(int id) throws Exception { return obj("/series/" + id); }

    public JSONArray lookup(String term) throws Exception { return arr("/series/lookup?term=" + enc(term)); }

    public void putSeries(JSONObject series) throws Exception {
        call("PUT", "/series/" + series.getInt("id"), series.toString(), 30000);
    }

    public void deleteSeries(int id, boolean deleteFiles) throws Exception {
        call("DELETE", "/series/" + id + "?deleteFiles=" + deleteFiles + "&addImportListExclusion=false", null, 60000);
    }

    /** Adds a series from a lookup result. monitor: all, future, missing, existing, firstSeason, lastSeason, none. */
    public void addSeries(JSONObject lookup, String rootFolder, int qualityProfileId, String monitor,
                          boolean seasonFolder, String seriesType, boolean searchMissing) throws Exception {
        JSONObject body = new JSONObject(lookup.toString());
        body.remove("id");
        body.put("rootFolderPath", rootFolder);
        body.put("qualityProfileId", qualityProfileId);
        body.put("monitored", !"none".equals(monitor));
        body.put("seasonFolder", seasonFolder);
        body.put("seriesType", seriesType);
        try {
            JSONArray lp = arr("/languageprofile");
            if (lp.length() > 0) body.put("languageProfileId", lp.getJSONObject(0).optInt("id", 1));
        } catch (Throwable ignored) {
            // Sonarr v4 has no language profiles
        }
        JSONObject opts = new JSONObject();
        opts.put("monitor", monitor);
        opts.put("searchForMissingEpisodes", searchMissing);
        opts.put("searchForCutoffUnmetEpisodes", false);
        body.put("addOptions", opts);
        call("POST", "/series", body.toString(), 60000);
    }

    // ------------------------------------------------------------------ episodes

    public JSONArray episodes(int seriesId) throws Exception {
        return arr("/episode?seriesId=" + seriesId + "&includeEpisodeFile=true");
    }

    public void putEpisode(JSONObject episode) throws Exception {
        call("PUT", "/episode/" + episode.getInt("id"), episode.toString(), 20000);
    }

    public void deleteEpisodeFile(int episodeFileId) throws Exception {
        call("DELETE", "/episodefile/" + episodeFileId, null, 30000);
    }

    // ------------------------------------------------------------------ commands

    public void command(String json) throws Exception { call("POST", "/command", json, 20000); }

    public void searchSeries(int seriesId) throws Exception {
        command("{\"name\":\"SeriesSearch\",\"seriesId\":" + seriesId + "}");
    }

    public void searchSeason(int seriesId, int season) throws Exception {
        command("{\"name\":\"SeasonSearch\",\"seriesId\":" + seriesId + ",\"seasonNumber\":" + season + "}");
    }

    public void searchEpisode(int episodeId) throws Exception {
        command("{\"name\":\"EpisodeSearch\",\"episodeIds\":[" + episodeId + "]}");
    }

    public void refreshSeries(int seriesId) throws Exception {
        command("{\"name\":\"RefreshSeries\",\"seriesId\":" + seriesId + "}");
    }

    public void rescanSeries(int seriesId) throws Exception {
        command("{\"name\":\"RescanSeries\",\"seriesId\":" + seriesId + "}");
    }

    public void simpleCommand(String name) throws Exception {
        command("{\"name\":\"" + name + "\"}");
    }

    // ------------------------------------------------------------------ releases (interactive search)

    public JSONArray releasesForEpisode(int episodeId) throws Exception {
        String r = call("GET", "/release?episodeId=" + episodeId, null, 120000);
        return r.isEmpty() ? new JSONArray() : new JSONArray(r);
    }

    public JSONArray releasesForSeason(int seriesId, int season) throws Exception {
        String r = call("GET", "/release?seriesId=" + seriesId + "&seasonNumber=" + season, null, 120000);
        return r.isEmpty() ? new JSONArray() : new JSONArray(r);
    }

    public void grabRelease(JSONObject release) throws Exception {
        JSONObject b = new JSONObject();
        b.put("guid", release.optString("guid"));
        b.put("indexerId", release.optInt("indexerId"));
        call("POST", "/release", b.toString(), 30000);
    }

    // ------------------------------------------------------------------ queue / history / calendar / wanted

    public JSONObject queue(int page, int pageSize) throws Exception {
        return obj("/queue?page=" + page + "&pageSize=" + pageSize + "&includeSeries=true&includeEpisode=true");
    }

    public void removeQueueItem(int id, boolean removeFromClient, boolean blocklist) throws Exception {
        call("DELETE", "/queue/" + id + "?removeFromClient=" + removeFromClient + "&blocklist=" + blocklist, null, 20000);
    }

    public JSONObject history(int page, int pageSize) throws Exception {
        return obj("/history?page=" + page + "&pageSize=" + pageSize
                + "&includeSeries=true&includeEpisode=true&sortKey=date&sortDirection=descending");
    }

    public JSONArray calendar(String startDate, String endDate) throws Exception {
        return arr("/calendar?start=" + startDate + "&end=" + endDate + "&includeSeries=true&unmonitored=false");
    }

    public JSONObject wantedMissing(int page, int pageSize) throws Exception {
        return obj("/wanted/missing?page=" + page + "&pageSize=" + pageSize
                + "&monitored=true&includeSeries=true&sortKey=airDateUtc&sortDirection=descending");
    }

    // ------------------------------------------------------------------ system / settings

    public JSONObject status() throws Exception { return obj("/system/status"); }

    public JSONArray health() throws Exception { return arr("/health"); }

    public JSONArray diskSpace() throws Exception { return arr("/diskspace"); }

    public JSONArray rootFolders() throws Exception { return arr("/rootfolder"); }

    public void addRootFolder(String path) throws Exception {
        call("POST", "/rootfolder", new JSONObject().put("path", path).toString(), 20000);
    }

    public void deleteRootFolder(int id) throws Exception { call("DELETE", "/rootfolder/" + id, null, 20000); }

    public JSONArray qualityProfiles() throws Exception { return arr("/qualityprofile"); }

    public JSONArray indexers() throws Exception { return arr("/indexer"); }

    public JSONArray downloadClients() throws Exception { return arr("/downloadclient"); }

    public JSONArray tags() throws Exception { return arr("/tag"); }

    public String testAll(String kind) throws Exception {
        // kind: indexer | downloadclient
        String r = call("POST", "/" + kind + "/testall", null, 120000);
        return r;
    }
}
