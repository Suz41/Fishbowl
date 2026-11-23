package com.fishbowl.app.services;

import android.content.Context;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;

/**
 * Thin client for the local Prowlarr v1 REST API (http://127.0.0.1:9696/api/v1) used by the Prowlarr tab.
 * Every method blocks: call it from a background thread.
 */
public final class ProwlarrApi {

    private static final String HOST = "http://127.0.0.1:9696";
    private static final String BASE = HOST + "/api/v1";

    private final String key;

    private ProwlarrApi(String key) {
        this.key = key;
    }

    /** Returns a client, or throws a readable error when Prowlarr has no API key yet. */
    public static ProwlarrApi get(Context ctx) throws Exception {
        MediaServiceManager mgr = MediaServiceManager.getInstance(ctx.getApplicationContext());
        String k = mgr == null ? null : mgr.getServiceApiKey("prowlarr");
        if (k == null || k.isEmpty()) throw new Exception("Prowlarr API key not found. Start Prowlarr once first.");
        return new ProwlarrApi(k);
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
                String msg = "Prowlarr returned HTTP " + code;
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

    // ------------------------------------------------------------------ indexers

    public JSONArray indexers() throws Exception { return arr("/indexer"); }

    public JSONObject indexer(int id) throws Exception { return obj("/indexer/" + id); }

    /** All indexer definitions that can be added (each carries its configurable "fields"). */
    public JSONArray indexerSchema() throws Exception {
        String r = call("GET", "/indexer/schema", null, 60000);
        return r.isEmpty() ? new JSONArray() : new JSONArray(r);
    }

    public void addIndexer(JSONObject indexer) throws Exception {
        call("POST", "/indexer?forceSave=true", indexer.toString(), 60000);
    }

    public void updateIndexer(JSONObject indexer) throws Exception {
        call("PUT", "/indexer/" + indexer.getInt("id") + "?forceSave=true", indexer.toString(), 60000);
    }

    public void deleteIndexer(int id) throws Exception { call("DELETE", "/indexer/" + id, null, 30000); }

    public void testIndexer(JSONObject indexer) throws Exception {
        call("POST", "/indexer/test", indexer.toString(), 60000);
    }

    public void testAllIndexers() throws Exception { call("POST", "/indexer/testall", null, 180000); }

    public JSONArray indexerCategories() throws Exception { return arr("/indexer/categories"); }

    public JSONArray appProfiles() throws Exception { return arr("/appprofile"); }

    public JSONObject indexerStats() throws Exception { return obj("/indexerstats"); }

    public JSONArray indexerStatus() throws Exception { return arr("/indexerstatus"); }

    // ------------------------------------------------------------------ search

    /**
     * Interactive search across indexers. type: search, tvsearch, movie, music, book.
     * indexerIds / categories may be null or empty (all indexers / all categories).
     */
    public JSONArray search(String query, String type, int[] indexerIds, int[] categories) throws Exception {
        StringBuilder sb = new StringBuilder("/search?query=").append(enc(query));
        sb.append("&type=").append(enc(type == null || type.isEmpty() ? "search" : type));
        sb.append("&limit=100&offset=0");
        if (indexerIds == null || indexerIds.length == 0) {
            sb.append("&indexerIds=-2");
        } else {
            for (int i : indexerIds) sb.append("&indexerIds=").append(i);
        }
        if (categories != null) {
            for (int c : categories) sb.append("&categories=").append(c);
        }
        String r = call("GET", sb.toString(), null, 120000);
        return r.isEmpty() ? new JSONArray() : new JSONArray(r);
    }

    public void grab(JSONObject release) throws Exception {
        JSONObject b = new JSONObject();
        b.put("guid", release.optString("guid"));
        b.put("indexerId", release.optInt("indexerId"));
        call("POST", "/search", b.toString(), 30000);
    }

    // ------------------------------------------------------------------ applications / clients / tags

    public JSONArray applications() throws Exception { return arr("/applications"); }

    public void testApplication(JSONObject app) throws Exception {
        call("POST", "/applications/test", app.toString(), 60000);
    }

    public void deleteApplication(int id) throws Exception { call("DELETE", "/applications/" + id, null, 20000); }

    public JSONArray downloadClients() throws Exception { return arr("/downloadclient"); }

    public JSONArray tags() throws Exception { return arr("/tag"); }

    // ------------------------------------------------------------------ history

    public JSONObject history(int page, int pageSize) throws Exception {
        return obj("/history?page=" + page + "&pageSize=" + pageSize + "&sortKey=date&sortDirection=descending");
    }

    // ------------------------------------------------------------------ system / commands

    public JSONObject status() throws Exception { return obj("/system/status"); }

    public JSONArray health() throws Exception { return arr("/health"); }

    public JSONArray diskSpace() throws Exception { return arr("/diskspace"); }

    public JSONArray tasks() throws Exception { return arr("/system/task"); }

    public void command(String json) throws Exception { call("POST", "/command", json, 20000); }

    /** Queues a command such as ApplicationIndexerSync, CheckHealth or Backup. */
    public void simpleCommand(String name) throws Exception {
        command(new JSONObject().put("name", name).toString());
    }
}
