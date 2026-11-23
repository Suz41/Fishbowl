package com.fishbowl.app.services;

import android.content.Context;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.ConnectException;
import java.net.HttpURLConnection;
import java.net.SocketTimeoutException;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;

/**
 * Thin client for the local Jackett web-UI JSON endpoints (http://127.0.0.1:9117/api/v2.0/...) used by the
 * Jackett tab. Jackett has no official REST API, so the "apikey" query parameter is appended to every call.
 * Redirects are never followed (a redirect means the UI wants a login) and non-JSON answers are turned into
 * readable errors. Every method blocks: call it from a background thread.
 */
public final class JackettApi {

    public static final String HOST = "http://127.0.0.1:9117";
    private static final String BASE = HOST + "/api/v2.0";

    public static final String NOT_RUNNING =
            "Jackett is not running (cannot reach 127.0.0.1:9117). Start it from the Services tab, then retry.";

    private final String key;

    private JackettApi(String key) {
        this.key = key;
    }

    /** Returns a client, or throws a readable error when Jackett has no API key yet. */
    public static JackettApi get(Context ctx) throws Exception {
        MediaServiceManager mgr = MediaServiceManager.getInstance(ctx.getApplicationContext());
        String k = mgr == null ? null : mgr.getServiceApiKey("jackett");
        if (k == null || k.trim().isEmpty()) throw new Exception("Jackett API key not found. Start Jackett once first.");
        return new JackettApi(k.trim());
    }

    public static String enc(String s) {
        try {
            return URLEncoder.encode(s == null ? "" : s, "UTF-8");
        } catch (Exception e) {
            return s == null ? "" : s;
        }
    }

    public String apiKey() { return key; }

    /** Torznab feed URL of one indexer (use id "all" for the aggregate feed). */
    public String torznabUrl(String indexerId) {
        return BASE + "/indexers/" + enc(indexerId) + "/results/torznab/";
    }

    // ------------------------------------------------------------------ transport

    private String call(String method, String path, String body, int timeoutMs) throws Exception {
        HttpURLConnection conn = null;
        try {
            String url = BASE + path + (path.indexOf('?') >= 0 ? "&" : "?") + "apikey=" + enc(key);
            conn = (HttpURLConnection) new URL(url).openConnection();
            conn.setInstanceFollowRedirects(false);
            conn.setConnectTimeout(6000);
            conn.setReadTimeout(timeoutMs);
            conn.setRequestMethod(method);
            conn.setRequestProperty("Accept", "application/json");
            conn.setRequestProperty("X-Requested-With", "XMLHttpRequest");
            if (body != null) {
                conn.setRequestProperty("Content-Type", "application/json; charset=UTF-8");
                conn.setDoOutput(true);
                OutputStream os = conn.getOutputStream();
                os.write(body.getBytes(StandardCharsets.UTF_8));
                os.close();
            }
            int code = conn.getResponseCode();
            if (code >= 300 && code < 400) {
                throw new Exception("Jackett redirected the request (HTTP " + code + "). Its web UI may require an admin password or a different base path.");
            }
            InputStream is = code >= 400 ? conn.getErrorStream() : conn.getInputStream();
            String text = readAll(is);
            if (code >= 400) {
                throw new Exception(describeError(code, text));
            }
            return text;
        } catch (ConnectException e) {
            throw new Exception(NOT_RUNNING);
        } catch (SocketTimeoutException e) {
            throw new Exception("Jackett did not answer in time. It may still be busy; try again in a moment.");
        } finally {
            if (conn != null) conn.disconnect();
        }
    }

    private static String readAll(InputStream is) throws Exception {
        if (is == null) return "";
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        byte[] buf = new byte[8192];
        int n;
        while ((n = is.read(buf)) > 0) bos.write(buf, 0, n);
        is.close();
        return new String(bos.toByteArray(), StandardCharsets.UTF_8);
    }

    private static boolean looksLikeHtml(String s) {
        String t = s.trim().toLowerCase(java.util.Locale.ROOT);
        return t.startsWith("<!doctype") || t.startsWith("<html") || t.startsWith("<");
    }

    private static String describeError(int code, String text) {
        String t = text == null ? "" : text.trim();
        String detail = "";
        try {
            if (t.startsWith("{")) {
                JSONObject o = new JSONObject(t);
                detail = o.optString("error", o.optString("message", o.optString("Message", "")));
                if (detail.isEmpty()) {
                    JSONObject ex = o.optJSONObject("exception");
                    if (ex != null) detail = ex.optString("message", "");
                }
            } else if (t.startsWith("[")) {
                JSONArray a = new JSONArray(t);
                if (a.length() > 0) detail = String.valueOf(a.opt(0));
            } else if (!t.isEmpty() && !looksLikeHtml(t)) {
                detail = t.length() > 300 ? t.substring(0, 300) : t;
            }
        } catch (Throwable ignored) {}
        if (code == 401 || code == 403) {
            return "Jackett rejected the API key (HTTP " + code + ")" + (detail.isEmpty() ? "" : ": " + detail);
        }
        return "Jackett returned HTTP " + code + (detail.isEmpty() ? "" : ": " + detail);
    }

    private JSONArray arr(String method, String path, String body, int timeoutMs) throws Exception {
        String r = call(method, path, body, timeoutMs).trim();
        if (r.isEmpty()) return new JSONArray();
        if (!r.startsWith("[")) throw new Exception("Jackett sent an unexpected (non-JSON-array) answer. Check that Jackett is fully started.");
        return new JSONArray(r);
    }

    private JSONObject obj(String method, String path, String body, int timeoutMs) throws Exception {
        String r = call(method, path, body, timeoutMs).trim();
        if (r.isEmpty()) return new JSONObject();
        if (!r.startsWith("{")) throw new Exception("Jackett sent an unexpected (non-JSON) answer. Check that Jackett is fully started.");
        return new JSONObject(r);
    }

    // ------------------------------------------------------------------ server config

    public JSONObject serverConfig() throws Exception {
        return obj("GET", "/server/config", null, 20000);
    }

    /** Saves the whole (modified) server config object. Jackett may restart itself afterwards. */
    public void saveServerConfig(JSONObject cfg) throws Exception {
        call("POST", "/server/config", cfg.toString(), 30000);
    }

    // ------------------------------------------------------------------ indexers

    /** configured=true: the user's indexers; configured=false: the unconfigured catalogue. */
    public JSONArray indexers(boolean configured) throws Exception {
        return arr("GET", "/indexers?configured=" + (configured ? "true" : "false"), null, 30000);
    }

    /** The indexer's editable field array (each item: id, type, name, value, options...). */
    public JSONArray indexerConfig(String id) throws Exception {
        return arr("GET", "/indexers/" + enc(id) + "/config", null, 30000);
    }

    /** Saves a config field array. Jackett tests the indexer while saving, so this can take a while. */
    public void saveIndexerConfig(String id, JSONArray fields) throws Exception {
        call("POST", "/indexers/" + enc(id) + "/config", fields.toString(), 120000);
    }

    /** Tests one indexer; throws with Jackett's message when the test fails. */
    public void testIndexer(String id) throws Exception {
        call("POST", "/indexers/" + enc(id) + "/test", "", 90000);
    }

    public void deleteIndexer(String id) throws Exception {
        call("DELETE", "/indexers/" + enc(id), null, 20000);
    }

    // ------------------------------------------------------------------ search

    /**
     * Searches every configured indexer. categories may be null/empty (all). Returns the Results array.
     */
    public JSONArray search(String query, int[] categories) throws Exception {
        StringBuilder p = new StringBuilder("/indexers/all/results?Query=").append(enc(query));
        if (categories != null) {
            for (int c : categories) p.append("&Category%5B%5D=").append(c);
        }
        JSONObject o = obj("GET", p.toString(), null, 120000);
        JSONArray res = o.optJSONArray("Results");
        return res == null ? new JSONArray() : res;
    }
}
