package com.fishbowl.app.services;

import android.content.Context;
import android.util.Base64;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.ConnectException;
import java.net.HttpURLConnection;
import java.net.SocketTimeoutException;
import java.net.URL;
import java.nio.charset.StandardCharsets;

/**
 * Thin blocking JSON-RPC client for the local Transmission daemon
 * (http://127.0.0.1:9091/transmission/rpc) used by the Transmission tab.
 *
 * The app's generated settings.json disables RPC authentication and the host whitelist, so the Basic
 * credentials below (fishbowl / fishbowl, the documented default) are only sent for the case where a
 * user turned authentication on; Transmission ignores them otherwise.
 *
 * Every method blocks: call it from a background thread.
 */
public final class TransmissionApi {

    public static final String RPC_URL = "http://127.0.0.1:9091/transmission/rpc";
    private static final String USER = "fishbowl";
    private static final String PASS = "fishbowl";

    public static final String NOT_RUNNING =
            "Transmission is not running (cannot reach 127.0.0.1:9091). Start it from the Services tab, then retry.";

    // torrent status codes
    public static final int ST_STOPPED = 0;
    public static final int ST_CHECK_WAIT = 1;
    public static final int ST_CHECKING = 2;
    public static final int ST_DOWNLOAD_WAIT = 3;
    public static final int ST_DOWNLOADING = 4;
    public static final int ST_SEED_WAIT = 5;
    public static final int ST_SEEDING = 6;

    public static final String[] LIST_FIELDS = {
            "id", "name", "status", "percentDone", "rateDownload", "rateUpload", "eta", "totalSize",
            "sizeWhenDone", "leftUntilDone", "uploadRatio", "peersConnected", "peersSendingToUs",
            "peersGettingFromUs", "error", "errorString", "addedDate", "doneDate", "downloadDir",
            "labels", "isFinished", "queuePosition", "downloadedEver", "uploadedEver", "hashString",
            "recheckProgress"
    };

    public static final String[] DETAIL_FIELDS = {
            "id", "name", "status", "percentDone", "rateDownload", "rateUpload", "eta", "totalSize",
            "sizeWhenDone", "leftUntilDone", "uploadRatio", "peersConnected", "peersSendingToUs",
            "peersGettingFromUs", "error", "errorString", "addedDate", "doneDate", "downloadDir",
            "labels", "isFinished", "queuePosition", "downloadedEver", "uploadedEver", "hashString",
            "recheckProgress", "files", "fileStats", "peers", "trackers", "trackerStats", "comment",
            "creator", "dateCreated", "pieceCount", "pieceSize", "haveValid", "corruptEver",
            "downloadLimit", "downloadLimited", "uploadLimit", "uploadLimited", "seedRatioLimit",
            "seedRatioMode", "bandwidthPriority", "activityDate", "isPrivate", "magnetLink"
    };

    private static volatile String sessionId = null;
    private static volatile Context appCtx = null;

    private TransmissionApi() {}

    /** Returns a client (no state besides the shared session id; the context is not needed). */
    public static TransmissionApi get(Context ctx) {
        if (ctx != null) appCtx = ctx.getApplicationContext();
        return new TransmissionApi();
    }

    // ------------------------------------------------------------------ transport

    /** Sends one RPC call and returns its "arguments" object. Throws a readable Exception on any failure. */
    public JSONObject rpc(String method, JSONObject args) throws Exception {
        return rpc(method, args, 25000);
    }

    public JSONObject rpc(String method, JSONObject args, int timeoutMs) throws Exception {
        JSONObject req = new JSONObject();
        req.put("method", method);
        if (args != null) req.put("arguments", args);
        byte[] payload = req.toString().getBytes(StandardCharsets.UTF_8);

        for (int attempt = 0; attempt < 2; attempt++) {
            HttpURLConnection conn = null;
            try {
                conn = (HttpURLConnection) new URL(RPC_URL).openConnection();
                conn.setConnectTimeout(4000);
                conn.setReadTimeout(timeoutMs);
                conn.setRequestMethod("POST");
                conn.setDoOutput(true);
                conn.setRequestProperty("Content-Type", "application/json");
                String authUser = USER;
                String authPass = PASS;
                Context ac = appCtx;
                if (ac != null && com.fishbowl.app.UnifiedAccount.isConfigured(ac)) {
                    authUser = com.fishbowl.app.UnifiedAccount.getUsername(ac);
                    authPass = com.fishbowl.app.UnifiedAccount.getPassword(ac);
                }
                conn.setRequestProperty("Authorization", "Basic "
                        + Base64.encodeToString((authUser + ":" + authPass).getBytes(StandardCharsets.UTF_8), Base64.NO_WRAP));
                String sid = sessionId;
                if (sid != null) conn.setRequestProperty("X-Transmission-Session-Id", sid);
                OutputStream os = conn.getOutputStream();
                os.write(payload);
                os.close();

                int code = conn.getResponseCode();
                if (code == 409) {
                    String fresh = conn.getHeaderField("X-Transmission-Session-Id");
                    if (fresh != null && attempt == 0) {
                        sessionId = fresh;
                        continue;
                    }
                    throw new Exception("Transmission session handshake failed (HTTP 409).");
                }
                if (code == 401) throw new Exception("Transmission rejected the RPC credentials (HTTP 401).");
                if (code == 403) throw new Exception("Transmission blocked this request (HTTP 403, check rpc-whitelist).");
                InputStream is = code >= 400 ? conn.getErrorStream() : conn.getInputStream();
                String body = readAll(is);
                if (code >= 400) throw new Exception("Transmission returned HTTP " + code);
                JSONObject resp = body.isEmpty() ? new JSONObject() : new JSONObject(body);
                String result = resp.optString("result", "success");
                if (!"success".equals(result)) throw new Exception("Transmission: " + result);
                JSONObject out = resp.optJSONObject("arguments");
                return out == null ? new JSONObject() : out;
            } catch (ConnectException e) {
                throw new Exception(NOT_RUNNING);
            } catch (SocketTimeoutException e) {
                throw new Exception("Transmission did not answer in time. It may be busy or not running.");
            } finally {
                if (conn != null) conn.disconnect();
            }
        }
        throw new Exception("Transmission session handshake failed.");
    }

    private static String readAll(InputStream is) throws Exception {
        if (is == null) return "";
        try {
            ByteArrayOutputStream bos = new ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            int n;
            while ((n = is.read(buf)) > 0) bos.write(buf, 0, n);
            return new String(bos.toByteArray(), StandardCharsets.UTF_8);
        } finally {
            try { is.close(); } catch (Exception ignored) {}
        }
    }

    private static JSONArray idArray(int[] ids) throws Exception {
        JSONArray a = new JSONArray();
        if (ids != null) for (int id : ids) a.put(id);
        return a;
    }

    private static JSONObject withIds(int[] ids) throws Exception {
        JSONObject a = new JSONObject();
        if (ids != null && ids.length > 0) a.put("ids", idArray(ids));
        return a;
    }

    // ------------------------------------------------------------------ torrents: read

    /** ids == null or empty returns every torrent. */
    public JSONArray torrentGet(String[] fields, int[] ids) throws Exception {
        JSONObject a = withIds(ids);
        JSONArray f = new JSONArray();
        for (String s : fields) f.put(s);
        a.put("fields", f);
        JSONArray t = rpc("torrent-get", a).optJSONArray("torrents");
        return t == null ? new JSONArray() : t;
    }

    public JSONArray torrentList() throws Exception {
        return torrentGet(LIST_FIELDS, null);
    }

    /** Full detail (files, fileStats, peers, trackerStats...) of one torrent. */
    public JSONObject torrentDetails(int id) throws Exception {
        JSONArray t = torrentGet(DETAIL_FIELDS, new int[]{id});
        if (t.length() == 0) throw new Exception("Torrent not found (it may have been removed).");
        return t.getJSONObject(0);
    }

    // ------------------------------------------------------------------ torrents: add

    /** Adds a magnet link, http(s) URL or local .torrent path (filename) and/or base64 metainfo. */
    public JSONObject torrentAdd(String filename, String metainfoBase64, String downloadDir, boolean paused) throws Exception {
        JSONObject a = new JSONObject();
        if (filename != null && !filename.isEmpty()) a.put("filename", filename);
        if (metainfoBase64 != null && !metainfoBase64.isEmpty()) a.put("metainfo", metainfoBase64);
        if (downloadDir != null && !downloadDir.trim().isEmpty()) a.put("download-dir", downloadDir.trim());
        a.put("paused", paused);
        return rpc("torrent-add", a, 60000);
    }

    /** Reads a local .torrent file and uploads it as base64 metainfo. */
    public JSONObject torrentAddFile(File torrentFile, String downloadDir, boolean paused) throws Exception {
        if (torrentFile == null || !torrentFile.isFile()) throw new Exception("Torrent file not found.");
        if (torrentFile.length() > 8L * 1024 * 1024) throw new Exception("Torrent file is too large.");
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        InputStream is = new FileInputStream(torrentFile);
        try {
            byte[] buf = new byte[8192];
            int n;
            while ((n = is.read(buf)) > 0) bos.write(buf, 0, n);
        } finally {
            is.close();
        }
        return torrentAdd(null, Base64.encodeToString(bos.toByteArray(), Base64.NO_WRAP), downloadDir, paused);
    }

    // ------------------------------------------------------------------ torrents: control

    /** ids == null or empty applies to all torrents. */
    public void torrentStart(int[] ids) throws Exception { rpc("torrent-start", withIds(ids)); }

    public void torrentStartNow(int[] ids) throws Exception { rpc("torrent-start-now", withIds(ids)); }

    public void torrentStop(int[] ids) throws Exception { rpc("torrent-stop", withIds(ids)); }

    public void torrentVerify(int[] ids) throws Exception { rpc("torrent-verify", withIds(ids)); }

    public void torrentReannounce(int[] ids) throws Exception { rpc("torrent-reannounce", withIds(ids)); }

    public void torrentRemove(int[] ids, boolean deleteLocalData) throws Exception {
        JSONObject a = withIds(ids);
        a.put("delete-local-data", deleteLocalData);
        rpc("torrent-remove", a);
    }

    public void torrentSetLocation(int[] ids, String location, boolean move) throws Exception {
        JSONObject a = withIds(ids);
        a.put("location", location);
        a.put("move", move);
        rpc("torrent-set-location", a, 60000);
    }

    public void queueMoveTop(int[] ids) throws Exception { rpc("queue-move-top", withIds(ids)); }

    public void queueMoveUp(int[] ids) throws Exception { rpc("queue-move-up", withIds(ids)); }

    public void queueMoveDown(int[] ids) throws Exception { rpc("queue-move-down", withIds(ids)); }

    public void queueMoveBottom(int[] ids) throws Exception { rpc("queue-move-bottom", withIds(ids)); }

    // ------------------------------------------------------------------ torrents: set

    /** Generic torrent-set; args holds keys like downloadLimit, files-wanted, priority-high, labels... */
    public void torrentSet(int[] ids, JSONObject args) throws Exception {
        JSONObject a = args == null ? new JSONObject() : new JSONObject(args.toString());
        if (ids != null && ids.length > 0) a.put("ids", idArray(ids));
        rpc("torrent-set", a);
    }

    public void setFilesWanted(int id, int[] fileIndexes, boolean wanted) throws Exception {
        JSONObject a = new JSONObject();
        JSONArray idx = new JSONArray();
        for (int i : fileIndexes) idx.put(i);
        a.put(wanted ? "files-wanted" : "files-unwanted", idx);
        torrentSet(new int[]{id}, a);
    }

    /** priority: -1 low, 0 normal, 1 high. */
    public void setFilePriority(int id, int[] fileIndexes, int priority) throws Exception {
        JSONObject a = new JSONObject();
        JSONArray idx = new JSONArray();
        for (int i : fileIndexes) idx.put(i);
        a.put(priority > 0 ? "priority-high" : priority < 0 ? "priority-low" : "priority-normal", idx);
        torrentSet(new int[]{id}, a);
    }

    /** kBps values; pass limited=false to remove the per-torrent limit. */
    public void setTorrentSpeedLimits(int id, boolean downLimited, int downKBps, boolean upLimited, int upKBps) throws Exception {
        JSONObject a = new JSONObject();
        a.put("downloadLimited", downLimited);
        a.put("downloadLimit", downKBps);
        a.put("uploadLimited", upLimited);
        a.put("uploadLimit", upKBps);
        torrentSet(new int[]{id}, a);
    }

    /** bandwidthPriority: -1 low, 0 normal, 1 high. */
    public void setBandwidthPriority(int[] ids, int priority) throws Exception {
        JSONObject a = new JSONObject();
        a.put("bandwidthPriority", priority);
        torrentSet(ids, a);
    }

    public void setLabels(int[] ids, String[] labels) throws Exception {
        JSONObject a = new JSONObject();
        JSONArray l = new JSONArray();
        for (String s : labels) l.put(s);
        a.put("labels", l);
        torrentSet(ids, a);
    }

    /** mode: 0 = use global limit, 1 = use this torrent's limit, 2 = unlimited. */
    public void setSeedRatio(int[] ids, int mode, double limit) throws Exception {
        JSONObject a = new JSONObject();
        a.put("seedRatioMode", mode);
        a.put("seedRatioLimit", limit);
        torrentSet(ids, a);
    }

    // ------------------------------------------------------------------ session

    public JSONObject sessionGet() throws Exception { return rpc("session-get", null); }

    public void sessionSet(JSONObject args) throws Exception { rpc("session-set", args); }

    public JSONObject sessionStats() throws Exception { return rpc("session-stats", null); }

    /** Free bytes on the volume that holds path, or -1 when unknown. */
    public long freeSpace(String path) throws Exception {
        JSONObject a = new JSONObject();
        a.put("path", path);
        return rpc("free-space", a).optLong("size-bytes", -1L);
    }

    public boolean portTest() throws Exception {
        return rpc("port-test", null, 30000).optBoolean("port-is-open", false);
    }

    /** Returns the number of rules in the refreshed blocklist. */
    public int blocklistUpdate() throws Exception {
        return rpc("blocklist-update", null, 90000).optInt("blocklist-size", 0);
    }
}
