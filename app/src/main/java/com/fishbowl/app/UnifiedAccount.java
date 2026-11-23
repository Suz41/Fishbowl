package com.fishbowl.app;

import android.content.Context;
import android.content.SharedPreferences;
import android.util.Base64;

import com.fishbowl.app.services.JellyfinLibraryClient;
import com.fishbowl.app.services.MediaServiceManager;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.Locale;

/**
 * One username / password for every service: Jellyfin (user), Sonarr, Radarr, Prowlarr (Forms login)
 * and Transmission (RPC authentication). The account is stored locally and pushed to each service
 * when it is running; services that are offline stay "pending" until the next apply.
 */
public final class UnifiedAccount {

    public static final String PREFS = "fishbowl_unified_account";
    private static final String K_USER = "username";
    private static final String K_PASS = "password";
    private static final String K_SETUP_DONE = "first_run_done";

    public static final String[] SERVICES = {"jellyfin", "sonarr", "radarr", "prowlarr", "transmission"};

    public interface Progress {
        void onStatus(String serviceId, String status, String message);
    }

    private UnifiedAccount() {}

    private static SharedPreferences sp(Context c) {
        return c.getApplicationContext().getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    // ------------------------------------------------------------------ storage

    public static boolean isConfigured(Context c) {
        String u = getUsername(c);
        String p = getPassword(c);
        return u != null && !u.isEmpty() && p != null && !p.isEmpty();
    }

    public static String getUsername(Context c) {
        return sp(c).getString(K_USER, "");
    }

    public static String getPassword(Context c) {
        return sp(c).getString(K_PASS, "");
    }

    public static boolean isFirstRunDone(Context c) {
        return sp(c).getBoolean(K_SETUP_DONE, false);
    }

    public static void setFirstRunDone(Context c) {
        sp(c).edit().putBoolean(K_SETUP_DONE, true).apply();
    }

    public static void save(Context c, String user, String pass) {
        SharedPreferences.Editor e = sp(c).edit().putString(K_USER, user).putString(K_PASS, pass);
        for (String s : SERVICES) e.putString("status_" + s, "pending").remove("msg_" + s);
        e.apply();
    }

    /** Forgets the stored account (services keep whatever login they already have). */
    public static void clear(Context c) {
        SharedPreferences.Editor e = sp(c).edit().remove(K_USER).remove(K_PASS);
        for (String s : SERVICES) e.remove("status_" + s).remove("msg_" + s);
        e.apply();
    }

    /** ok / pending / failed / none */
    public static String getStatus(Context c, String id) {
        return sp(c).getString("status_" + id, isConfigured(c) ? "pending" : "none");
    }

    public static String getMessage(Context c, String id) {
        return sp(c).getString("msg_" + id, "");
    }

    public static boolean hasPending(Context c) {
        if (!isConfigured(c)) return false;
        for (String s : SERVICES) {
            if (!"ok".equals(getStatus(c, s))) return true;
        }
        return false;
    }

    private static void setStatus(Context c, String id, String status, String msg, Progress cb) {
        sp(c).edit().putString("status_" + id, status).putString("msg_" + id, msg == null ? "" : msg).apply();
        if (cb != null) cb.onStatus(id, status, msg);
    }

    /** Returns null when the password is acceptable. */
    public static String validate(String user, String pass) {
        if (user == null || user.trim().length() < 3) return "Username must be at least 3 characters";
        if (!user.trim().matches("[A-Za-z0-9._-]+")) return "Username may use letters, digits, . _ - only";
        if (pass == null || pass.length() < 6) return "Password must be at least 6 characters";
        if (pass.startsWith("{")) return "Password must not start with {";
        return null;
    }

    // ------------------------------------------------------------------ Transmission helper

    /** Adds the Basic header to a Transmission RPC connection when an account exists. */
    public static void authorize(HttpURLConnection conn, Context c) {
        try {
            if (conn == null || c == null || !isConfigured(c)) return;
            String raw = getUsername(c) + ":" + getPassword(c);
            conn.setRequestProperty("Authorization",
                    "Basic " + Base64.encodeToString(raw.getBytes(StandardCharsets.UTF_8), Base64.NO_WRAP));
        } catch (Throwable ignored) {}
    }

    // ------------------------------------------------------------------ apply

    /** Blocking: call from a background thread. */
    public static void applyAll(Context ctx, boolean force, Progress cb) {
        final Context c = ctx.getApplicationContext();
        if (!isConfigured(c)) return;
        final String user = getUsername(c).trim();
        final String pass = getPassword(c);

        if (force || !"ok".equals(getStatus(c, "jellyfin"))) applyJellyfin(c, user, pass, cb);
        if (force || !"ok".equals(getStatus(c, "sonarr"))) applyArr(c, "sonarr", 8989, "/api/v3", user, pass, cb);
        if (force || !"ok".equals(getStatus(c, "radarr"))) applyArr(c, "radarr", 7878, "/api/v3", user, pass, cb);
        if (force || !"ok".equals(getStatus(c, "prowlarr"))) applyArr(c, "prowlarr", 9696, "/api/v1", user, pass, cb);
        if (force || !"ok".equals(getStatus(c, "transmission"))) applyTransmission(c, cb);
    }

    // ---- Sonarr / Radarr / Prowlarr

    private static void applyArr(Context c, String id, int port, String api, String user, String pass, Progress cb) {
        try {
            MediaServiceManager mgr = MediaServiceManager.getInstance(c);
            if (!portOpen(port)) {
                setStatus(c, id, "pending", "Not running", cb);
                return;
            }
            String key = mgr.getServiceApiKey(id);
            if (key == null || key.isEmpty()) {
                setStatus(c, id, "pending", "API key not found yet", cb);
                return;
            }
            String base = "http://127.0.0.1:" + port + api;
            String cur = http("GET", base + "/config/host", key, null, null);
            JSONObject host = new JSONObject(cur);
            host.put("authenticationMethod", "forms");
            host.put("authenticationRequired", "enabled");
            host.put("username", user);
            host.put("password", pass);
            host.put("passwordConfirmation", pass);
            http("PUT", base + "/config/host/" + host.optInt("id", 1), key, host.toString(), null);

            // Keep the Transmission download client in Radarr / Sonarr in sync with the new credentials.
            if (!"prowlarr".equals(id)) {
                try {
                    String list = http("GET", base + "/downloadclient", key, null, null);
                    JSONArray arr = new JSONArray(list);
                    for (int i = 0; i < arr.length(); i++) {
                        JSONObject cl = arr.optJSONObject(i);
                        if (cl == null || !"Transmission".equalsIgnoreCase(cl.optString("implementation"))) continue;
                        JSONArray fields = cl.optJSONArray("fields");
                        if (fields == null) continue;
                        boolean sawUser = false, sawPass = false;
                        for (int j = 0; j < fields.length(); j++) {
                            JSONObject f = fields.optJSONObject(j);
                            if (f == null) continue;
                            if ("username".equalsIgnoreCase(f.optString("name"))) {
                                f.put("value", user);
                                sawUser = true;
                            } else if ("password".equalsIgnoreCase(f.optString("name"))) {
                                f.put("value", pass);
                                sawPass = true;
                            }
                        }
                        if (!sawUser) fields.put(new JSONObject().put("name", "username").put("value", user));
                        if (!sawPass) fields.put(new JSONObject().put("name", "password").put("value", pass));
                        http("PUT", base + "/downloadclient/" + cl.optInt("id") + "?forceSave=true", key, cl.toString(), null);
                    }
                } catch (Throwable ignored) {
                    // non fatal: wiring is repaired on the next SYNC STACK
                }
            }
            setStatus(c, id, "ok", "", cb);
        } catch (Throwable t) {
            setStatus(c, id, "failed", String.valueOf(t.getMessage()), cb);
        }
    }

    // ---- Transmission

    private static void applyTransmission(Context c, Progress cb) {
        try {
            MediaServiceManager mgr = MediaServiceManager.getInstance(c);
            com.fishbowl.app.model.ManagedService svc = mgr.getService("transmission");
            if (svc == null || !svc.isInstalled()) {
                setStatus(c, "transmission", "pending", "Not installed", cb);
                return;
            }
            if (!portOpen(9091)) {
                // Not running: the credentials are written into settings.json the next time it starts.
                setStatus(c, "transmission", "pending", "Will apply at next start", cb);
                return;
            }
            // Transmission only reads rpc-username / rpc-password from settings.json at start-up.
            mgr.stopService("transmission");
            long end = System.currentTimeMillis() + 20000;
            while (portOpen(9091) && System.currentTimeMillis() < end) Thread.sleep(500);
            Thread.sleep(800);
            mgr.startService("transmission");
            end = System.currentTimeMillis() + 40000;
            while (!portOpen(9091) && System.currentTimeMillis() < end) Thread.sleep(800);
            if (!portOpen(9091)) {
                setStatus(c, "transmission", "failed", "Did not come back after restart", cb);
                return;
            }
            // Verify: an authenticated call must be accepted (200 or the 409 session challenge).
            int code = rpcProbe(c);
            if (code == 200 || code == 409) setStatus(c, "transmission", "ok", "", cb);
            else setStatus(c, "transmission", "failed", "RPC answered HTTP " + code, cb);
        } catch (Throwable t) {
            setStatus(c, "transmission", "failed", String.valueOf(t.getMessage()), cb);
        }
    }

    private static int rpcProbe(Context c) {
        HttpURLConnection conn = null;
        try {
            conn = (HttpURLConnection) new URL("http://127.0.0.1:9091/transmission/rpc").openConnection();
            conn.setConnectTimeout(3000);
            conn.setReadTimeout(5000);
            conn.setRequestMethod("POST");
            conn.setDoOutput(true);
            authorize(conn, c);
            OutputStream os = conn.getOutputStream();
            os.write("{\"method\":\"session-get\"}".getBytes(StandardCharsets.UTF_8));
            os.close();
            return conn.getResponseCode();
        } catch (Throwable t) {
            return -1;
        } finally {
            if (conn != null) conn.disconnect();
        }
    }

    // ---- Jellyfin

    private static void applyJellyfin(Context c, String user, String pass, Progress cb) {
        try {
            if (!portOpen(8096)) {
                setStatus(c, "jellyfin", "pending", "Not running", cb);
                return;
            }
            String pub = http("GET", "http://127.0.0.1:8096/System/Info/Public", null, null, null);
            boolean wizardDone = new JSONObject(pub).optBoolean("StartupWizardCompleted", true);
            if (!wizardDone) {
                // Brand new server: finish the first-run wizard with this account.
                http("POST", "http://127.0.0.1:8096/Startup/Configuration", null,
                        "{\"UICulture\":\"en-US\",\"MetadataCountryCode\":\"US\",\"PreferredMetadataLanguage\":\"en\"}", null);
                try { http("GET", "http://127.0.0.1:8096/Startup/User", null, null, null); } catch (Throwable ignored) {}
                http("POST", "http://127.0.0.1:8096/Startup/User", null,
                        new JSONObject().put("Name", user).put("Password", pass).toString(), null);
                http("POST", "http://127.0.0.1:8096/Startup/Complete", null, "", null);
                JellyfinAccountManager.storeKnownPassword(c, user, pass);
                setStatus(c, "jellyfin", "ok", "", cb);
                return;
            }

            // Existing server: reuse the user if it exists, otherwise create an administrator.
            boolean exists = false;
            for (JellyfinAccountManager.JellyfinUser u : JellyfinAccountManager.getRegisteredUsers(c)) {
                if (u.username != null && u.username.equalsIgnoreCase(user)) {
                    exists = true;
                    user = u.username;
                    break;
                }
            }
            if (exists) {
                if (!JellyfinAccountManager.setPassword(c, user, pass)) {
                    setStatus(c, "jellyfin", "failed", "Could not update the existing user's password", cb);
                    return;
                }
                setStatus(c, "jellyfin", "ok", "", cb);
                return;
            }
            String token = JellyfinLibraryClient.getToken(c);
            if (token == null) {
                setStatus(c, "jellyfin", "failed", "No administrator login available", cb);
                return;
            }
            String created = http("POST", "http://127.0.0.1:8096/Users/New", null,
                    new JSONObject().put("Name", user).put("Password", pass).toString(), token);
            String uid = new JSONObject(created).optString("Id", "");
            if (!uid.isEmpty()) {
                try {
                    JSONObject u = new JSONObject(http("GET", "http://127.0.0.1:8096/Users/" + uid, null, null, token));
                    JSONObject pol = u.optJSONObject("Policy");
                    if (pol != null) {
                        pol.put("IsAdministrator", true);
                        http("POST", "http://127.0.0.1:8096/Users/" + uid + "/Policy", null, pol.toString(), token);
                    }
                } catch (Throwable ignored) {}
            }
            JellyfinAccountManager.storeKnownPassword(c, user, pass);
            setStatus(c, "jellyfin", "ok", "", cb);
        } catch (Throwable t) {
            setStatus(c, "jellyfin", "failed", String.valueOf(t.getMessage()), cb);
        }
    }

    /** True when the entered credentials belong to an existing Jellyfin user (used by "Sign in"). */
    public static boolean verifyAgainstJellyfin(Context c, String user, String pass) {
        try {
            for (JellyfinAccountManager.JellyfinUser u : JellyfinAccountManager.getRegisteredUsers(c)) {
                if (u.username != null && u.username.equalsIgnoreCase(user.trim())) {
                    return u.hasPassword && JellyfinAccountManager.verifyPasswordHash(pass, u.passwordHash);
                }
            }
        } catch (Throwable ignored) {}
        return false;
    }

    public static boolean jellyfinHasUser(Context c, String user) {
        try {
            for (JellyfinAccountManager.JellyfinUser u : JellyfinAccountManager.getRegisteredUsers(c)) {
                if (u.username != null && u.username.equalsIgnoreCase(user.trim())) return true;
            }
        } catch (Throwable ignored) {}
        return false;
    }

    // ------------------------------------------------------------------ http

    private static boolean portOpen(int port) {
        try (Socket s = new Socket()) {
            s.connect(new InetSocketAddress("127.0.0.1", port), 700);
            return true;
        } catch (Throwable t) {
            return false;
        }
    }

    private static String http(String method, String url, String apiKey, String body, String jellyfinToken) throws Exception {
        HttpURLConnection conn = null;
        try {
            conn = (HttpURLConnection) new URL(url).openConnection();
            conn.setConnectTimeout(5000);
            conn.setReadTimeout(30000);
            conn.setRequestMethod(method);
            if (apiKey != null) conn.setRequestProperty("X-Api-Key", apiKey);
            if (jellyfinToken != null) {
                conn.setRequestProperty("X-Emby-Token", jellyfinToken);
                conn.setRequestProperty("Authorization", "MediaBrowser Token=\"" + jellyfinToken + "\"");
            }
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
                throw new Exception(String.format(Locale.US, "HTTP %d at %s", code, url.replaceAll("\\?.*", "")));
            }
            return sb.toString();
        } finally {
            if (conn != null) conn.disconnect();
        }
    }
}
