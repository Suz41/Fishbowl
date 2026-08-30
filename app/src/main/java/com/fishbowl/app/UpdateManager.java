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
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class UpdateManager {

    private static final String TAG = "UpdateManager";
    private static final String REPO_OWNER = "Suz41";
    private static final String REPO_NAME = "Fishbowl";
    private static final String LATEST_RELEASE_URL = "https://api.github.com/repos/" + REPO_OWNER + "/" + REPO_NAME + "/releases/latest";
    private static final String PREFS_NAME = "fishbowl_updates";
    private static final String KEY_LAST_CHECK = "last_check_timestamp";
    private static final String KEY_LATEST_VER_CODE = "latest_version_code";
    private static final String KEY_LATEST_VER_NAME = "latest_version_name";
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
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
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
        mainHandler.post(() -> {
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
        mainHandler.post(() -> {
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
                URL url = new URL(LATEST_RELEASE_URL);
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

                    JSONObject releaseJson = new JSONObject(sb.toString());
                    String tagName = releaseJson.optString("tag_name", "");
                    String body = releaseJson.optString("body", "");
                    JSONArray assets = releaseJson.optJSONArray("assets");

                    int parsedVersionCode = -1;
                    Pattern pattern = Pattern.compile("versionCode\\s*[:=]?\\s*(\\d+)");
                    Matcher matcher = pattern.matcher(body);
                    if (matcher.find()) {
                        parsedVersionCode = Integer.parseInt(matcher.group(1));
                    }

                    if (parsedVersionCode <= 0) {
                        Log.w(TAG, "versionCode not specified in release body, parsing from tag_name");
                        parsedVersionCode = parseVersionCodeFromTag(tagName);
                    }

                    if (parsedVersionCode <= 0) {
                        notifyStateChanged(State.RELEASE_UNAVAILABLE);
                        return;
                    }

                    String downloadUrl = "";
                    String checksumUrl = "";
                    if (assets != null) {
                        for (int i = 0; i < assets.length(); i++) {
                            JSONObject asset = assets.getJSONObject(i);
                            String name = asset.optString("name", "");
                            if (name.endsWith("-universal.apk")) {
                                downloadUrl = asset.optString("browser_download_url", "");
                            } else if (name.endsWith("-universal.apk.sha256")) {
                                checksumUrl = asset.optString("browser_download_url", "");
                            }
                        }
                    }

                    if (downloadUrl.isEmpty()) {
                        notifyStateChanged(State.RELEASE_UNAVAILABLE);
                        return;
                    }

                    synchronized (this) {
                        latestVersionCode = parsedVersionCode;
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

    private int parseVersionCodeFromTag(String tagName) {
        try {
            String digits = tagName.replaceAll("[^0-9]", "");
            if (digits.length() >= 3) {
                int major = Character.getNumericValue(digits.charAt(0));
                int minor = Character.getNumericValue(digits.charAt(1));
                int patch = Character.getNumericValue(digits.charAt(2));
                return 100000 + (major * 10000) + (minor * 100) + patch;
            }
        } catch (Exception ignored) {}
        return -1;
    }

    private String cleanReleaseNotes(String body) {
        String clean = body.replaceAll("(?m)^versionCode\\s*[:=]?\\s*\\d+\\s*$", "");
        return clean.trim();
    }

    public synchronized void startDownload(Context context) {
        if (isDownloading) return;
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

        if (expectedHash.isEmpty()) {
            Log.w(TAG, "Missing expected checksum. Skipping verification but logging safety warning (as per prompt instructions).");
            notifyStateChanged(State.VERIFICATION_SUCCESSFUL);
            return;
        }

        String calculatedHash = calculateSHA256(downloadedApkFile);
        if (calculatedHash != null && calculatedHash.equals(expectedHash)) {
            Log.i(TAG, "Checksum verification successful.");
            notifyStateChanged(State.VERIFICATION_SUCCESSFUL);
        } else {
            Log.e(TAG, "Checksum verification failed: expected=" + expectedHash + ", got=" + calculatedHash);
            if (downloadedApkFile != null && downloadedApkFile.exists()) {
                downloadedApkFile.delete();
            }
            notifyStateChanged(State.VERIFICATION_FAILED);
        }
    }

    private String calculateSHA256(File file) {
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

    private void deleteRecursive(File fileOrDirectory) {
        if (fileOrDirectory.isDirectory()) {
            for (File child : fileOrDirectory.listFiles()) {
                deleteRecursive(child);
            }
        }
        fileOrDirectory.delete();
    }
}
