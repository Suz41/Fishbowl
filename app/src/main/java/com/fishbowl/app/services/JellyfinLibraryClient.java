package com.fishbowl.app.services;

import android.content.ContentValues;
import android.content.Context;
import android.database.sqlite.SQLiteDatabase;
import android.util.Log;

import com.fishbowl.app.JellyfinAccountManager;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.File;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TimeZone;

/**
 * Minimal Jellyfin REST client used by the Library tab and the auto-delete engine.
 * Authentication, in order: cached token, an API key created in jellyfin.db, then
 * AuthenticateByName with the admin's known (or blank) password.
 */
public final class JellyfinLibraryClient {

    private static final String TAG = "JellyfinLibraryClient";
    private static final String BASE = "http://127.0.0.1:8096";
    private static final String KEY_NAME = "FishbowlLibrary";

    public static final class LibItem {
        public String id;
        public String type;       // Movie, Series, Season, Episode
        public String name;
        public String seriesName;
        public String seriesId;
        public int seasonNumber = -1;
        public int episodeNumber = -1;
        public int year;
        public int childCount;
        public boolean played;
        public int playCount;
        public long lastPlayedMs;
        public String playedBy;

        public String displayTitle() {
            if ("Episode".equals(type)) {
                String s = seriesName != null ? seriesName : "";
                String code = "";
                if (seasonNumber >= 0 && episodeNumber >= 0) {
                    code = String.format(Locale.US, " S%02dE%02d", seasonNumber, episodeNumber);
                }
                return (s + code + " - " + name).trim();
            }
            if ("Movie".equals(type) && year > 0) return name + " (" + year + ")";
            return name;
        }
    }

    public static class ApiException extends Exception {
        public ApiException(String m) { super(m); }
    }

    private JellyfinLibraryClient() {}

    private static volatile String token;

    // ------------------------------------------------------------------ authentication

    public static final class AuthException extends ApiException {
        public AuthException(String m) { super(m); }
    }

    private static final StringBuilder diag = new StringBuilder();

    private static void d(String line) {
        Log.w(TAG, line);
        if (diag.length() < 1200) diag.append("\n- ").append(line);
    }

    public static synchronized String getToken(Context ctx) throws ApiException {
        if (token == null) token = LibraryPrefs.getToken(ctx);
        if (token != null && validate(token, "cached token")) return token;
        token = null;
        LibraryPrefs.setToken(ctx, null);
        diag.setLength(0);

        String t = createApiKeyInDb(ctx);
        if (t != null && validate(t, "API key")) {
            token = t;
            LibraryPrefs.setToken(ctx, t);
            return t;
        }
        t = authenticateWithStoredPassword(ctx);
        if (t != null && validate(t, "login token")) {
            token = t;
            LibraryPrefs.setToken(ctx, t);
            return t;
        }
        throw new AuthException("Could not authenticate to Jellyfin." + diag);
    }

    /** Signs in with the given admin credentials, remembers them and caches the token. Returns null on success, else an error text. */
    public static synchronized String loginAndStore(Context ctx, String username, String password) {
        diag.setLength(0);
        String t = authenticate(username, password);
        if (t == null || !validate(t, "login token")) {
            return "Login failed." + diag;
        }
        token = t;
        LibraryPrefs.setToken(ctx, t);
        if (password != null && !password.isEmpty()) {
            try { JellyfinAccountManager.storeKnownPassword(ctx, username, password); } catch (Throwable ignored) {}
        }
        return null;
    }

    public static String suggestedUsername(Context ctx) {
        try {
            List<JellyfinAccountManager.JellyfinUser> us = users(ctx);
            for (JellyfinAccountManager.JellyfinUser u : us) if (u.isAdmin) return u.username;
            if (!us.isEmpty()) return us.get(0).username;
        } catch (Throwable ignored) {}
        return "";
    }

    private static boolean validate(String t, String label) {
        try {
            HttpURLConnection c = open("/Users", "GET", t);
            int code = c.getResponseCode();
            c.disconnect();
            if (code != 200) d(label + " rejected by Jellyfin: HTTP " + code);
            return code == 200;
        } catch (Throwable e) {
            d(label + " check failed: " + e.getClass().getSimpleName() + " " + e.getMessage());
            return false;
        }
    }

    private static String createApiKeyInDb(Context ctx) {
        SQLiteDatabase db = null;
        try {
            File dbFile = JellyfinAccountManager.getJellyfinDatabaseFile();
            if (!dbFile.exists()) {
                d("jellyfin.db not found at " + dbFile.getAbsolutePath());
                return null;
            }
            db = SQLiteDatabase.openDatabase(dbFile.getAbsolutePath(), null, SQLiteDatabase.OPEN_READWRITE);
            String existing = null;
            try (android.database.Cursor cur = db.rawQuery("SELECT AccessToken FROM ApiKeys WHERE Name = ?", new String[]{KEY_NAME})) {
                if (cur != null && cur.moveToFirst()) existing = cur.getString(0);
            }
            if (existing != null && !existing.isEmpty()) return existing;

            byte[] raw = new byte[16];
            new SecureRandom().nextBytes(raw);
            StringBuilder sb = new StringBuilder();
            for (byte b : raw) sb.append(String.format(Locale.US, "%02x", b));
            String key = sb.toString();

            SimpleDateFormat fmt = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss.0000000", Locale.US);
            fmt.setTimeZone(TimeZone.getTimeZone("UTC"));
            String now = fmt.format(new Date());
            ContentValues cv = new ContentValues();
            cv.put("DateCreated", now);
            cv.put("DateLastActivity", now);
            cv.put("Name", KEY_NAME);
            cv.put("AccessToken", key);
            long row = db.insert("ApiKeys", null, cv);
            if (row < 0) d("ApiKeys insert returned " + row);
            return row >= 0 ? key : null;
        } catch (Throwable t) {
            d("API key in jellyfin.db: " + t.getClass().getSimpleName() + " " + t.getMessage());
            return null;
        } finally {
            if (db != null) {
                try { db.close(); } catch (Throwable ignored) {}
            }
        }
    }

    private static String authenticateWithStoredPassword(Context ctx) {
        try {
            List<JellyfinAccountManager.JellyfinUser> users = JellyfinAccountManager.getRegisteredUsers(ctx);
            if (users.isEmpty()) {
                d("No Jellyfin users could be read from jellyfin.db");
                return null;
            }
            // Administrators first, then everybody else (admin detection can fail on some schemas).
            for (int pass = 0; pass < 2; pass++) {
                for (JellyfinAccountManager.JellyfinUser u : users) {
                    if ((pass == 0) != u.isAdmin) continue;
                    String pw = u.hasPassword ? u.knownPlaintextPassword : "";
                    if (pw == null) {
                        d("User '" + u.username + "' has a password the app does not know yet");
                        continue;
                    }
                    String t = authenticate(u.username, pw);
                    if (t != null) return t;
                }
            }
        } catch (Throwable t) {
            d("Stored-password login: " + t.getClass().getSimpleName() + " " + t.getMessage());
        }
        return null;
    }

    private static String authenticate(String username, String pw) {
        try {
            JSONObject body = new JSONObject();
            body.put("Username", username);
            body.put("Pw", pw == null ? "" : pw);
            HttpURLConnection c = (HttpURLConnection) new URL(BASE + "/Users/AuthenticateByName").openConnection();
            c.setRequestMethod("POST");
            c.setConnectTimeout(3000);
            c.setReadTimeout(15000);
            c.setRequestProperty("Content-Type", "application/json");
            String authHeader = "MediaBrowser Client=\"Fishbowl\", Device=\"Android\", DeviceId=\"fishbowl-library\", Version=\"1.0\"";
            c.setRequestProperty("X-Emby-Authorization", authHeader);
            c.setRequestProperty("Authorization", authHeader);
            c.setDoOutput(true);
            try (OutputStream os = c.getOutputStream()) {
                os.write(body.toString().getBytes(StandardCharsets.UTF_8));
            }
            int code = c.getResponseCode();
            if (code == 200) {
                String res = readAll(c.getInputStream());
                c.disconnect();
                return new JSONObject(res).optString("AccessToken", null);
            }
            c.disconnect();
            d("Login as '" + username + "': HTTP " + code);
        } catch (Throwable t) {
            d("Login as '" + username + "': " + t.getClass().getSimpleName() + " " + t.getMessage());
        }
        return null;
    }

    // ------------------------------------------------------------------ HTTP helpers

    private static HttpURLConnection open(String pathAndQuery, String method, String t) throws Exception {
        HttpURLConnection c = (HttpURLConnection) new URL(BASE + pathAndQuery).openConnection();
        c.setRequestMethod(method);
        c.setConnectTimeout(3000);
        c.setReadTimeout(30000);
        if (t != null) {
            c.setRequestProperty("X-Emby-Token", t);
            c.setRequestProperty("Authorization", "MediaBrowser Token=\"" + t + "\"");
        }
        c.setRequestProperty("Accept", "application/json");
        return c;
    }

    private static String readAll(InputStream in) throws Exception {
        StringBuilder sb = new StringBuilder();
        try (BufferedReader br = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8))) {
            String line;
            while ((line = br.readLine()) != null) sb.append(line);
        }
        return sb.toString();
    }

    private static String get(Context ctx, String pathAndQuery) throws ApiException {
        for (int attempt = 0; attempt < 2; attempt++) {
            try {
                String t = getToken(ctx);
                HttpURLConnection c = open(pathAndQuery, "GET", t);
                int code = c.getResponseCode();
                if (code == 401 && attempt == 0) {
                    c.disconnect();
                    token = null;
                    LibraryPrefs.setToken(ctx, null);
                    continue;
                }
                if (code != 200) {
                    c.disconnect();
                    throw new ApiException("Jellyfin returned HTTP " + code);
                }
                String body = readAll(c.getInputStream());
                c.disconnect();
                return body;
            } catch (ApiException e) {
                throw e;
            } catch (Throwable e) {
                throw new ApiException("Jellyfin is not reachable on port 8096 (" + e.getClass().getSimpleName() + "). Start Jellyfin first.");
            }
        }
        throw new ApiException("Jellyfin authentication failed");
    }

    // ------------------------------------------------------------------ users & parsing

    public static List<JellyfinAccountManager.JellyfinUser> users(Context ctx) {
        return JellyfinAccountManager.getRegisteredUsers(ctx);
    }

    private static String primaryUserId(Context ctx) throws ApiException {
        List<JellyfinAccountManager.JellyfinUser> us = users(ctx);
        if (us.isEmpty()) throw new ApiException("No Jellyfin users found.");
        for (JellyfinAccountManager.JellyfinUser u : us) if (u.isAdmin) return u.id;
        return us.get(0).id;
    }

    private static long parseDate(String s) {
        if (s == null || s.length() < 19) return 0L;
        try {
            SimpleDateFormat f = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", Locale.US);
            f.setTimeZone(TimeZone.getTimeZone("UTC"));
            return f.parse(s.substring(0, 19)).getTime();
        } catch (Throwable t) {
            return 0L;
        }
    }

    private static List<LibItem> parseItems(String body, String userName) throws Exception {
        List<LibItem> out = new ArrayList<LibItem>();
        JSONObject root = new JSONObject(body);
        JSONArray arr = root.optJSONArray("Items");
        if (arr == null) return out;
        for (int i = 0; i < arr.length(); i++) {
            JSONObject o = arr.getJSONObject(i);
            LibItem it = new LibItem();
            it.id = o.optString("Id");
            it.type = o.optString("Type");
            it.name = o.optString("Name");
            it.seriesName = o.optString("SeriesName", null);
            it.seriesId = o.optString("SeriesId", null);
            if (o.has("ParentIndexNumber")) it.seasonNumber = o.optInt("ParentIndexNumber", -1);
            if (o.has("IndexNumber")) {
                if ("Season".equals(it.type)) it.seasonNumber = o.optInt("IndexNumber", -1);
                else it.episodeNumber = o.optInt("IndexNumber", -1);
            }
            it.year = o.optInt("ProductionYear", 0);
            it.childCount = o.optInt("ChildCount", 0);
            JSONObject ud = o.optJSONObject("UserData");
            if (ud != null) {
                it.played = ud.optBoolean("Played", false);
                it.playCount = ud.optInt("PlayCount", 0);
                it.lastPlayedMs = parseDate(ud.optString("LastPlayedDate", null));
            }
            it.playedBy = userName;
            out.add(it);
        }
        return out;
    }

    private static String enc(String s) {
        try { return URLEncoder.encode(s, "UTF-8"); } catch (Throwable t) { return s; }
    }

    // ------------------------------------------------------------------ queries

    public static List<LibItem> movies(Context ctx) throws ApiException {
        try {
            String uid = primaryUserId(ctx);
            return parseItems(get(ctx, "/Items?userId=" + enc(uid)
                    + "&IncludeItemTypes=Movie&Recursive=true&SortBy=SortName&SortOrder=Ascending"
                    + "&Fields=ProductionYear&EnableTotalRecordCount=false"), null);
        } catch (ApiException e) {
            throw e;
        } catch (Throwable t) {
            throw new ApiException("Unexpected response: " + t.getMessage());
        }
    }

    public static List<LibItem> series(Context ctx) throws ApiException {
        try {
            String uid = primaryUserId(ctx);
            return parseItems(get(ctx, "/Items?userId=" + enc(uid)
                    + "&IncludeItemTypes=Series&Recursive=true&SortBy=SortName&SortOrder=Ascending"
                    + "&Fields=ChildCount,ProductionYear&EnableTotalRecordCount=false"), null);
        } catch (ApiException e) {
            throw e;
        } catch (Throwable t) {
            throw new ApiException("Unexpected response: " + t.getMessage());
        }
    }

    public static List<LibItem> seasons(Context ctx, String seriesId) throws ApiException {
        try {
            String uid = primaryUserId(ctx);
            return parseItems(get(ctx, "/Shows/" + enc(seriesId) + "/Seasons?userId=" + enc(uid)), null);
        } catch (ApiException e) {
            throw e;
        } catch (Throwable t) {
            throw new ApiException("Unexpected response: " + t.getMessage());
        }
    }

    public static List<LibItem> episodes(Context ctx, String seriesId, String seasonId) throws ApiException {
        try {
            String uid = primaryUserId(ctx);
            return parseItems(get(ctx, "/Shows/" + enc(seriesId) + "/Episodes?userId=" + enc(uid)
                    + (seasonId != null ? "&seasonId=" + enc(seasonId) : "")), null);
        } catch (ApiException e) {
            throw e;
        } catch (Throwable t) {
            throw new ApiException("Unexpected response: " + t.getMessage());
        }
    }

    /**
     * Played movies and episodes across all users, most recently watched first. When several users
     * watched the same item, the latest watch wins.
     */
    public static List<LibItem> recentlyWatched(Context ctx, int limitPerUser) throws ApiException {
        try {
            Map<String, LibItem> best = new HashMap<String, LibItem>();
            List<JellyfinAccountManager.JellyfinUser> us = users(ctx);
            if (us.isEmpty()) throw new ApiException("No Jellyfin users found.");
            for (JellyfinAccountManager.JellyfinUser u : us) {
                String body = get(ctx, "/Items?userId=" + enc(u.id)
                        + "&Filters=IsPlayed&Recursive=true&IncludeItemTypes=Movie,Episode"
                        + "&SortBy=DatePlayed&SortOrder=Descending&Limit=" + limitPerUser
                        + "&Fields=ProductionYear&EnableTotalRecordCount=false");
                for (LibItem it : parseItems(body, u.username)) {
                    if (it.lastPlayedMs <= 0) continue;
                    LibItem cur = best.get(it.id);
                    if (cur == null || it.lastPlayedMs > cur.lastPlayedMs) best.put(it.id, it);
                }
            }
            List<LibItem> list = new ArrayList<LibItem>(best.values());
            java.util.Collections.sort(list, new java.util.Comparator<LibItem>() {
                @Override
                public int compare(LibItem a, LibItem b) {
                    return Long.compare(b.lastPlayedMs, a.lastPlayedMs);
                }
            });
            return list;
        } catch (ApiException e) {
            throw e;
        } catch (Throwable t) {
            throw new ApiException("Unexpected response: " + t.getMessage());
        }
    }

    /** Deletes an item (and its files on disk, as Jellyfin does for library deletion). */
    public static boolean deleteItem(Context ctx, String itemId) {
        for (int attempt = 0; attempt < 2; attempt++) {
            try {
                String t = getToken(ctx);
                HttpURLConnection c = open("/Items/" + enc(itemId), "DELETE", t);
                int code = c.getResponseCode();
                c.disconnect();
                if (code == 401 && attempt == 0) {
                    token = null;
                    LibraryPrefs.setToken(ctx, null);
                    continue;
                }
                return code == 200 || code == 204;
            } catch (Throwable e) {
                Log.w(TAG, "deleteItem failed: " + e.getMessage());
                return false;
            }
        }
        return false;
    }
}
