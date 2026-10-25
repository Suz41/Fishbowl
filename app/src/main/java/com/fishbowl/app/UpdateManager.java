package com.fishbowl.app;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.util.Log;

import androidx.core.content.FileProvider;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedInputStream;
import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.security.MessageDigest;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class UpdateManager {

    private static final String TAG = "UpdateManager";
    private static final String REPO_OWNER = "Suz41";
    private static final String REPO_NAME = "Fishbowl";
    private static final String LATEST_RELEASE_URL = "https://api.github.com/repos/" + REPO_OWNER + "/" + REPO_NAME + "/releases/latest";
    private static final String ALL_RELEASES_URL = "https://api.github.com/repos/" + REPO_OWNER + "/" + REPO_NAME + "/releases?per_page=10";
    private static final String PREFS_NAME = "fishbowl_updates";
    private static final String KEY_LAST_CHECK = "last_check_timestamp";
    private static final String KEY_LATEST_VER_CODE = "latest_version_code";
    private static final String KEY_LATEST_VER_NAME = "latest_version_name";
    private static final String KEY_UPDATE_CHANNEL = "update_channel";
    public static final String CHANNEL_STABLE = "stable";
    public static final String CHANNEL_BETA = "beta";
    private static final long CACHE_DURATION_MS = 24 * 60 * 60 * 1000L; // 24 hours

    public enum State {
        IDLE,
        CHECKING,
        UP_TO_DATE,
        UPDATE_AVAILABLE,
        DOWNLOADING,
        DOWNLOAD_COMPLETE,
        VERIFYING,
        VERIFICATION_SUCCESSFUL,
        VERIFICATION_FAILED,
        PERMISSION_REQUIRED,
        DOWNLOAD_FAILED,
        NETWORK_UNAVAILABLE,
        RELEASE_UNAVAILABLE
    }

    public interface UpdateListener {
        void onUpdateStateChanged(State state);
        void onDownloadProgress(long bytesDownloaded, long totalBytes);
    }

    private static UpdateManager instance;
    private final ExecutorService executor = Executors.newSingleThreadExecutor();

    private void postOnMainThread(Runnable r) {
        if (r == null) return;
        try {
            if (Looper.getMainLooper() != null) {
                new Handler(Looper.getMainLooper()).post(r);
                return;
            }
        } catch (Throwable ignored) {}
        r.run();
    }

    private final CopyOnWriteArrayList<UpdateListener> listeners = new CopyOnWriteArrayList<>();
    private final SharedPreferences prefs;

    private State currentState = State.IDLE;
    private String latestVersionName = "";
    private int latestVersionCode = -1;
    private String latestReleaseNotes = "";
    private String apkUrl = "";
    private String sha256Url = "";
    private long totalApkSize = 0;
    private long downloadedBytes = 0;
    private File downloadedApkFile;
    private boolean isDownloading = false;

    private UpdateManager(Context context) {
        this.prefs = context.getApplicationContext().getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        this.latestVersionCode = prefs.getInt(KEY_LATEST_VER_CODE, -1);
        this.latestVersionName = prefs.getString(KEY_LATEST_VER_NAME, "");
    }

    public static synchronized UpdateManager getInstance(Context context) {
        if (instance == null) {
            instance = new UpdateManager(context);
        }
        return instance;
    }

    public synchronized State getCurrentState() {
        return currentState;
    }

    public synchronized String getLatestVersionName() {
        return latestVersionName;
    }

    public synchronized int getLatestVersionCode() {
        return latestVersionCode;
    }

    public synchronized String getLatestReleaseNotes() {
        return latestReleaseNotes;
    }

    public synchronized long getDownloadedBytes() {
        return downloadedBytes;
    }

    public synchronized long getTotalApkSize() {
        return totalApkSize;
    }

    public synchronized String getApkUrl() {
        return apkUrl;
    }

    public void addListener(UpdateListener l) {
        listeners.add(l);
    }

    public void removeListener(UpdateListener l) {
        listeners.remove(l);
    }

    private void notifyStateChanged(State state) {
        synchronized (this) {
            currentState = state;
        }
        postOnMainThread(() -> {
            for (UpdateListener l : listeners) {
                l.onUpdateStateChanged(state);
            }
        });
    }

    private void notifyProgress(long downloaded, long total) {
        synchronized (this) {
            downloadedBytes = downloaded;
            totalApkSize = total;
        }
        postOnMainThread(() -> {
            for (UpdateListener l : listeners) {
                l.onDownloadProgress(downloaded, total);
            }
        });
    }

    public int getCurrentVersionCode(Context context) {
        try {
            PackageInfo pInfo = context.getPackageManager().getPackageInfo(context.getPackageName(), 0);
            return pInfo.versionCode;
        } catch (PackageManager.NameNotFoundException e) {
            Log.e(TAG, "Cannot get version code", e);
            return 1;
        }
    }

    public String getCurrentVersionName(Context context) {
        try {
            PackageInfo pInfo = context.getPackageManager().getPackageInfo(context.getPackageName(), 0);
            return pInfo.versionName;
        } catch (PackageManager.NameNotFoundException e) {
            Log.e(TAG, "Cannot get version name", e);
            return "1.0.0";
        }
    }

    public String getUpdateChannel() {
        return prefs.getString(KEY_UPDATE_CHANNEL, CHANNEL_BETA);
    }

    public String getUpdateChannel(Context context) {
        String defaultChannel = (context != null && getCurrentVersionName(context).toLowerCase(Locale.ROOT).contains("beta"))
                ? CHANNEL_BETA : CHANNEL_STABLE;
        return prefs.getString(KEY_UPDATE_CHANNEL, defaultChannel);
    }

    public void setUpdateChannel(String channel) {
        if (channel != null) {
            prefs.edit().putString(KEY_UPDATE_CHANNEL, channel).apply();
        }
    }

    public void checkForUpdates(Context context, boolean forceCheck) {
        long lastCheck = prefs.getLong(KEY_LAST_CHECK, 0);
        long now = System.currentTimeMillis();

        if (!forceCheck && (now - lastCheck < CACHE_DURATION_MS) && latestVersionCode > 0) {
            int currentCode = getCurrentVersionCode(context);
            if (latestVersionCode > currentCode) {
                notifyStateChanged(State.UPDATE_AVAILABLE);
            } else {
                notifyStateChanged(State.UP_TO_DATE);
            }
            return;
        }

        notifyStateChanged(State.CHECKING);
        executor.execute(() -> {
            HttpURLConnection conn = null;
            try {
                String channel = getUpdateChannel(context);
                String fetchUrl = CHANNEL_BETA.equalsIgnoreCase(channel) ? ALL_RELEASES_URL : LATEST_RELEASE_URL;

                URL url = new URL(fetchUrl);
                conn = (HttpURLConnection) url.openConnection();
                conn.setConnectTimeout(10000);
                conn.setReadTimeout(10000);
                conn.setRequestMethod("GET");
                conn.setRequestProperty("User-Agent", "Fishbowl-Updater");
                conn.setRequestProperty("Accept", "application/vnd.github.v3+json");

                int responseCode = conn.getResponseCode();
                if (responseCode == 200) {
                    BufferedReader in = new BufferedReader(new InputStreamReader(conn.getInputStream()));
                    StringBuilder sb = new StringBuilder();
                    String line;
                    while ((line = in.readLine()) != null) {
                        sb.append(line);
                    }
                    in.close();

                    String rawJson = sb.toString().trim();
                    JSONObject bestRelease = null;
                    int highestVerCode = -1;

                    if (rawJson.startsWith("[")) {
                        JSONArray releases = new JSONArray(rawJson);
                        for (int i = 0; i < releases.length(); i++) {
                            JSONObject rel = releases.getJSONObject(i);
                            boolean isPrerelease = rel.optBoolean("prerelease", false);
                            if (CHANNEL_STABLE.equalsIgnoreCase(channel) && isPrerelease) {
                                continue;
                            }
                            int code = extractVersionCode(rel);
                            if (code > highestVerCode) {
                                highestVerCode = code;
                                bestRelease = rel;
                            }
                        }
                    } else if (rawJson.startsWith("{")) {
                        bestRelease = new JSONObject(rawJson);
                        highestVerCode = extractVersionCode(bestRelease);
                    }

                    if (bestRelease == null || highestVerCode <= 0) {
                        notifyStateChanged(State.RELEASE_UNAVAILABLE);
                        return;
                    }

                    String tagName = bestRelease.optString("tag_name", "");
                    String body = bestRelease.optString("body", "");
                    JSONArray assets = bestRelease.optJSONArray("assets");

                    String downloadUrl = "";
                    String checksumUrl = "";
                    if (assets != null) {
                        String bestApkName = "";

                        // Pass 1: Look for arm64-v8a specific APK first
                        for (int i = 0; i < assets.length(); i++) {
                            JSONObject asset = assets.getJSONObject(i);
                            String name = asset.optString("name", "");
                            if (name.contains("arm64") && name.endsWith(".apk")) {
                                downloadUrl = asset.optString("browser_download_url", "");
                                bestApkName = name;
                                break;
                            }
                        }

                        // Pass 2: Fallback to universal APK
                        if (downloadUrl.isEmpty()) {
                            for (int i = 0; i < assets.length(); i++) {
                                JSONObject asset = assets.getJSONObject(i);
                                String name = asset.optString("name", "");
                                if (name.endsWith("-universal.apk")) {
                                    downloadUrl = asset.optString("browser_download_url", "");
                                    bestApkName = name;
                                    break;
                                }
                            }
                        }

                        // Pass 3: Fallback to any Fishbowl release .apk asset
                        if (downloadUrl.isEmpty()) {
                            for (int i = 0; i < assets.length(); i++) {
                                JSONObject asset = assets.getJSONObject(i);
                                String name = asset.optString("name", "");
                                if (name.startsWith("Fishbowl") && name.endsWith(".apk") && !name.contains("indices")) {
                                    downloadUrl = asset.optString("browser_download_url", "");
                                    bestApkName = name;
                                    break;
                                }
                            }
                        }

                        // Match checksum for the chosen APK
                        if (!bestApkName.isEmpty()) {
                            for (int i = 0; i < assets.length(); i++) {
                                JSONObject asset = assets.getJSONObject(i);
                                String name = asset.optString("name", "");
                                if (name.equals(bestApkName + ".sha256") || name.endsWith(".apk.sha256") || name.endsWith(".sha256")) {
                                    checksumUrl = asset.optString("browser_download_url", "");
                                    break;
                                }
                            }
                        }
                    }

                    if (downloadUrl.isEmpty() || !downloadUrl.toLowerCase(Locale.ROOT).startsWith("https://")) {
                        Log.e(TAG, "Invalid or unencrypted download URL: " + downloadUrl);
                        notifyStateChanged(State.RELEASE_UNAVAILABLE);
                        return;
                    }

                    synchronized (this) {
                        latestVersionCode = highestVerCode;
                        latestVersionName = tagName.startsWith("v") ? tagName.substring(1) : tagName;
                        latestReleaseNotes = cleanReleaseNotes(body);
                        apkUrl = downloadUrl;
                        sha256Url = checksumUrl;
                    }

                    prefs.edit()
                            .putLong(KEY_LAST_CHECK, now)
                            .putInt(KEY_LATEST_VER_CODE, latestVersionCode)
                            .putString(KEY_LATEST_VER_NAME, latestVersionName)
                            .apply();

                    int currentCode = getCurrentVersionCode(context);
                    if (latestVersionCode > currentCode) {
                        notifyStateChanged(State.UPDATE_AVAILABLE);
                    } else {
                        notifyStateChanged(State.UP_TO_DATE);
                    }
                } else if (responseCode == 404 || responseCode == 403) {
                    notifyStateChanged(State.RELEASE_UNAVAILABLE);
                } else {
                    notifyStateChanged(State.NETWORK_UNAVAILABLE);
                }
            } catch (Exception e) {
                Log.e(TAG, "Error checking for updates", e);
                notifyStateChanged(State.NETWORK_UNAVAILABLE);
            } finally {
                if (conn != null) {
                    conn.disconnect();
                }
            }
        });
    }

    private int extractVersionCode(JSONObject releaseJson) {
        if (releaseJson == null) return -1;
        String body = releaseJson.optString("body", "");
        if (!body.isEmpty()) {
            for (String bLine : body.split("\n")) {
                String trimmed = bLine.trim();
                if (trimmed.startsWith("versionCode")) {
                    String rest = trimmed.substring("versionCode".length()).trim();
                    if (rest.startsWith(":") || rest.startsWith("=")) {
                        rest = rest.substring(1).trim();
                    }
                    try {
                        return Integer.parseInt(rest);
                    } catch (NumberFormatException ignored) {}
                }
            }
        }
        String tagName = releaseJson.optString("tag_name", "");
        return parseVersionCodeFromTag(tagName);
    }

    public static int parseVersionCodeFromTag(String tagName) {
        if (tagName == null) return -1;
        String lower = tagName.toLowerCase(Locale.ROOT).trim();
        if (lower.contains("1.4.8-beta.2")) return 101413;
        if (lower.contains("1.4.8-beta.1")) return 101412;
        if (lower.contains("1.4.8")) return 101414;
        if (lower.contains("1.4.7-beta.3")) return 101411;
        if (lower.contains("1.4.7-beta.2")) return 101410;
        if (lower.contains("1.4.7-beta.1")) return 101409;
        if (lower.contains("1.4.6")) return 101408;
        if (lower.contains("1.4.5")) return 101407;
        if (lower.contains("1.4.3")) return 101405;
        if (lower.contains("1.4.2")) return 101404;
        if (lower.contains("1.4.1")) return 101403;
        if (lower.contains("1.4.0")) return 101402;

        try {
            int betaIdx = lower.indexOf("-beta");
            int betaNum = 0;
            String verPart = lower;
            if (betaIdx != -1) {
                verPart = lower.substring(0, betaIdx);
                String rest = lower.substring(betaIdx + 5);
                if (rest.startsWith(".")) rest = rest.substring(1);
                int b = 0;
                while (b < rest.length() && Character.isDigit(rest.charAt(b))) {
                    b++;
                }
                betaNum = b > 0 ? Integer.parseInt(rest.substring(0, b)) : 1;
            }

            int p1 = verPart.indexOf('.');
            if (p1 != -1) {
                int p2 = verPart.indexOf('.', p1 + 1);
                if (p2 != -1) {
                    int p3 = p2 + 1;
                    while (p3 < verPart.length() && Character.isDigit(verPart.charAt(p3))) {
                        p3++;
                    }
                    String majStr = extractDigits(verPart.substring(0, p1));
                    String minStr = extractDigits(verPart.substring(p1 + 1, p2));
                    String patStr = extractDigits(verPart.substring(p2 + 1, p3));
                    if (!majStr.isEmpty() && !minStr.isEmpty() && !patStr.isEmpty()) {
                        int major = Integer.parseInt(majStr);
                        int minor = Integer.parseInt(minStr);
                        int patch = Integer.parseInt(patStr);
                        if (betaIdx != -1) {
                            return 100000 + (major * 1000) + (minor * 100) + (patch * 2) + betaNum;
                        } else {
                            return 100000 + (major * 1000) + (minor * 100) + patch;
                        }
                    }
                }
            }
        } catch (Exception ignored) {}
        return -1;
    }

    private static String extractDigits(String s) {
        if (s == null) return "";
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (Character.isDigit(c)) sb.append(c);
        }
        return sb.toString();
    }

    private String cleanReleaseNotes(String body) {
        if (body == null) return "";
        StringBuilder sb = new StringBuilder();
        for (String line : body.split("\n")) {
            String trimmed = line.trim();
            if (trimmed.startsWith("versionCode")) {
                String rest = trimmed.substring("versionCode".length()).trim();
                if (rest.startsWith(":") || rest.startsWith("=")) {
                    rest = rest.substring(1).trim();
                }
                boolean isAllDigits = !rest.isEmpty();
                for (int i = 0; i < rest.length(); i++) {
                    if (!Character.isDigit(rest.charAt(i))) {
                        isAllDigits = false;
                        break;
                    }
                }
                if (isAllDigits) {
                    continue;
                }
            }
            sb.append(line).append("\n");
        }
        return sb.toString().trim();
    }

    public synchronized void startDownload(Context context) {
        if (isDownloading) return;

        if (apkUrl == null || !apkUrl.toLowerCase(Locale.ROOT).startsWith("https://")) {
            Log.e(TAG, "Refusing download: non-HTTPS or invalid URL: " + apkUrl);
            notifyStateChanged(State.DOWNLOAD_FAILED);
            return;
        }

        long usableCache = context.getCacheDir().getUsableSpace();
        long requiredSpace = (totalApkSize > 0) ? (totalApkSize + 50 * 1024 * 1024L) : (150 * 1024 * 1024L);
        if (usableCache < requiredSpace) {
            Log.e(TAG, "Insufficient storage for update: " + (usableCache / (1024 * 1024)) + " MB available");
            notifyStateChanged(State.DOWNLOAD_FAILED);
            return;
        }

        isDownloading = true;
        notifyStateChanged(State.DOWNLOADING);

        executor.execute(() -> {
            HttpURLConnection conn = null;
            FileOutputStream out = null;
            InputStream in = null;
            try {
                File dir = new File(context.getCacheDir(), "updates");
                if (dir.exists()) {
                    deleteRecursive(dir);
                }
                dir.mkdirs();

                downloadedApkFile = new File(dir, "Fishbowl-update.apk");

                URL url = new URL(apkUrl);
                conn = (HttpURLConnection) url.openConnection();
                conn.setConnectTimeout(15000);
                conn.setReadTimeout(30000);
                conn.setRequestMethod("GET");
                conn.connect();

                int responseCode = conn.getResponseCode();
                if (responseCode == 200) {
                    long totalSize = conn.getContentLength();
                    in = new BufferedInputStream(conn.getInputStream());
                    out = new FileOutputStream(downloadedApkFile);

                    byte[] buffer = new byte[8192];
                    int read;
                    long downloaded = 0;
                    while ((read = in.read(buffer)) != -1) {
                        out.write(buffer, 0, read);
                        downloaded += read;
                        notifyProgress(downloaded, totalSize);
                    }
                    out.flush();
                    out.close();
                    in.close();

                    verifyAndInstall(context);
                } else {
                    notifyStateChanged(State.DOWNLOAD_FAILED);
                }
            } catch (Exception e) {
                Log.e(TAG, "Error downloading update", e);
                notifyStateChanged(State.DOWNLOAD_FAILED);
            } finally {
                isDownloading = false;
                try { if (out != null) out.close(); } catch (Exception ignored) {}
                try { if (in != null) in.close(); } catch (Exception ignored) {}
                if (conn != null) conn.disconnect();
            }
        });
    }

    private void verifyAndInstall(Context context) {
        notifyStateChanged(State.VERIFYING);

        if (downloadedApkFile == null || !downloadedApkFile.exists() || downloadedApkFile.length() <= 0) {
            Log.e(TAG, "Update verification failed: APK file missing or empty");
            notifyStateChanged(State.VERIFICATION_FAILED);
            return;
        }

        // 1. SHA-256 Checksum Verification (if available)
        String expectedHash = "";
        if (!sha256Url.isEmpty()) {
            HttpURLConnection conn = null;
            try {
                URL url = new URL(sha256Url);
                conn = (HttpURLConnection) url.openConnection();
                conn.setConnectTimeout(10000);
                conn.setReadTimeout(10000);
                conn.setRequestMethod("GET");
                if (conn.getResponseCode() == 200) {
                    BufferedReader reader = new BufferedReader(new InputStreamReader(conn.getInputStream()));
                    String line = reader.readLine();
                    if (line != null) {
                        expectedHash = line.split("\\s+")[0].trim().toLowerCase();
                    }
                    reader.close();
                }
            } catch (Exception e) {
                Log.w(TAG, "Could not fetch expected sha256 checksum", e);
            } finally {
                if (conn != null) conn.disconnect();
            }
        }

        if (!expectedHash.isEmpty()) {
            String calculatedHash = calculateSHA256(downloadedApkFile);
            if (calculatedHash == null || !calculatedHash.equalsIgnoreCase(expectedHash)) {
                Log.e(TAG, "Checksum verification failed: expected=" + expectedHash + ", got=" + calculatedHash);
                if (downloadedApkFile != null && downloadedApkFile.exists()) {
                    downloadedApkFile.delete();
                }
                notifyStateChanged(State.VERIFICATION_FAILED);
                return;
            }
            Log.i(TAG, "Checksum SHA-256 verification succeeded.");
        }

        // 2. Structural & Cryptographic Package Verification
        try {
            PackageManager pm = context.getPackageManager();
            PackageInfo archiveInfo = pm.getPackageArchiveInfo(
                    downloadedApkFile.getAbsolutePath(),
                    PackageManager.GET_SIGNATURES | PackageManager.GET_ACTIVITIES);

            if (archiveInfo == null || archiveInfo.packageName == null) {
                Log.e(TAG, "Package archive info could not be parsed from downloaded APK");
                if (downloadedApkFile.exists()) downloadedApkFile.delete();
                notifyStateChanged(State.VERIFICATION_FAILED);
                return;
            }

            // Verify expected package name
            String currentPkg = context.getPackageName();
            if (!currentPkg.equals(archiveInfo.packageName)) {
                Log.e(TAG, "Package ID mismatch: expected " + currentPkg + ", got " + archiveInfo.packageName);
                if (downloadedApkFile.exists()) downloadedApkFile.delete();
                notifyStateChanged(State.VERIFICATION_FAILED);
                return;
            }

            // Verify version code compatibility
            int currentVerCode = getCurrentVersionCode(context);
            if (archiveInfo.versionCode < currentVerCode) {
                Log.e(TAG, "Rejecting downgrade attempt: current=" + currentVerCode + ", update=" + archiveInfo.versionCode);
                if (downloadedApkFile.exists()) downloadedApkFile.delete();
                notifyStateChanged(State.VERIFICATION_FAILED);
                return;
            }

            // Verify signature compatibility with currently installed package
            try {
                PackageInfo currentInfo = pm.getPackageInfo(currentPkg, PackageManager.GET_SIGNATURES);
                if (currentInfo.signatures != null && currentInfo.signatures.length > 0 &&
                        archiveInfo.signatures != null && archiveInfo.signatures.length > 0) {
                    boolean sigMatch = false;
                    for (android.content.pm.Signature curSig : currentInfo.signatures) {
                        for (android.content.pm.Signature archSig : archiveInfo.signatures) {
                            if (curSig.equals(archSig)) {
                                sigMatch = true;
                                break;
                            }
                        }
                        if (sigMatch) break;
                    }
                    if (!sigMatch) {
                        Log.e(TAG, "APK signature mismatch with currently installed certificate");
                        if (downloadedApkFile.exists()) downloadedApkFile.delete();
                        notifyStateChanged(State.VERIFICATION_FAILED);
                        return;
                    }
                }
            } catch (PackageManager.NameNotFoundException ignored) {}

            Log.i(TAG, "Package archive structural, identity, and signature verification succeeded for " + archiveInfo.packageName);
            notifyStateChanged(State.VERIFICATION_SUCCESSFUL);

        } catch (Throwable t) {
            Log.e(TAG, "Package verification exception", t);
            if (downloadedApkFile != null && downloadedApkFile.exists()) {
                downloadedApkFile.delete();
            }
            notifyStateChanged(State.VERIFICATION_FAILED);
        }
    }

    public static String calculateSHA256(File file) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            InputStream is = new FileInputStream(file);
            byte[] buffer = new byte[8192];
            int read;
            while ((read = is.read(buffer)) > 0) {
                digest.update(buffer, 0, read);
            }
            is.close();
            byte[] hash = digest.digest();
            StringBuilder sb = new StringBuilder();
            for (byte b : hash) {
                sb.append(String.format("%02x", b));
            }
            return sb.toString();
        } catch (Exception e) {
            Log.e(TAG, "Failed to calculate SHA-256", e);
            return null;
        }
    }

    public void installUpdate(Activity activity) {
        if (downloadedApkFile == null || !downloadedApkFile.exists()) {
            notifyStateChanged(State.DOWNLOAD_FAILED);
            return;
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            if (!activity.getPackageManager().canRequestPackageInstalls()) {
                notifyStateChanged(State.PERMISSION_REQUIRED);
                return;
            }
        }

        try {
            Uri apkUri = FileProvider.getUriForFile(activity, activity.getPackageName() + ".fileprovider", downloadedApkFile);
            Intent intent = new Intent(Intent.ACTION_VIEW);
            intent.setDataAndType(apkUri, "application/vnd.android.package-archive");
            intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            activity.startActivity(intent);
        } catch (Exception e) {
            Log.e(TAG, "Failed to start installation activity", e);
            notifyStateChanged(State.DOWNLOAD_FAILED);
        }
    }

    public void openInstallPermissionSettings(Activity activity) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            Intent intent = new Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES);
            intent.setData(Uri.parse("package:" + activity.getPackageName()));
            activity.startActivity(intent);
        }
    }

    public void cleanCachedApk(Context context) {
        File dir = new File(context.getCacheDir(), "updates");
        if (dir.exists()) {
            deleteRecursive(dir);
        }
        notifyStateChanged(State.IDLE);
    }

    public static class ServiceVersionInfo {
        public final String serviceId;
        public final String displayName;
        public final String installedVersion;
        public final String upstreamVersion;
        public final boolean updateAvailable;
        public final String notes;

        public ServiceVersionInfo(String serviceId, String displayName, String installedVersion,
                                  String upstreamVersion, boolean updateAvailable, String notes) {
            this.serviceId = serviceId;
            this.displayName = displayName;
            this.installedVersion = installedVersion;
            this.upstreamVersion = upstreamVersion;
            this.updateAvailable = updateAvailable;
            this.notes = notes;
        }
    }

    public java.util.List<ServiceVersionInfo> getUniversalServiceVersionMatrix() {
        java.util.List<ServiceVersionInfo> list = new java.util.ArrayList<>();
        list.add(new ServiceVersionInfo("jellyfin", "Jellyfin Server", "12.1.0", "12.2.0", true,
                "Stable release v12.1.0 (.NET 10 & FFmpeg 7.1.4 Bionic transcoding engine active)."));
        list.add(new ServiceVersionInfo("radarr", "Radarr", "6.4.4", "6.4.4", false,
                "Current release up to date (.NET 10 ARM64 Bionic runtime ready)."));
        list.add(new ServiceVersionInfo("sonarr", "Sonarr", "4.0.13", "4.0.13", false,
                "Current release up to date (.NET 10 ARM64)."));
        list.add(new ServiceVersionInfo("prowlarr", "Prowlarr", "1.31.2", "1.31.2", false,
                "Current release up to date (.NET 10 ARM64)."));
        list.add(new ServiceVersionInfo("transmission", "Transmission", "4.1.4", "4.1.4", false,
                "Active native BitTorrent client v4.1.4 up to date."));
        list.add(new ServiceVersionInfo("seerr", "Seerr Discovery", "2.2.0", "2.2.0", false,
                "Node.js 22 LTS runtime request engine active."));
        list.add(new ServiceVersionInfo("bazarr", "Bazarr", "1.4.5", "1.4.5", false,
                "Python 3.12 Bionic runtime supervisor active."));
        return list;
    }

    private void deleteRecursive(File fileOrDirectory) {
        if (fileOrDirectory.isDirectory()) {
            for (File child : fileOrDirectory.listFiles()) {
                deleteRecursive(child);
            }
        }
        fileOrDirectory.delete();
    }
}
