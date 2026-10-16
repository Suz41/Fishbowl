package com.fishbowl.app;

import android.Manifest;
import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;
import android.os.Environment;
import android.provider.Settings;
import android.util.Log;
import android.system.ErrnoException;
import android.system.Os;
import android.system.OsConstants;
import android.system.StructStat;

import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;

import com.termux.shared.termux.TermuxConstants;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

import org.json.JSONArray;
import org.json.JSONObject;

/**
 * Fishbowl Media Storage Manager (v1.4.8-beta.1)
 *
 * Centralizes management of shared Internal Storage at /storage/emulated/0/FishbowlMedia/,
 * automatic directory scaffolding, read/write probing, legacy internal media migration,
 * and service path alignment across Transmission, Radarr, Sonarr, and Jellyfin.
 */
public final class MediaStorageManager {

    private static final String TAG = "MediaStorageManager";
    private static final String PREFS_NAME = "fishbowl_storage";
    private static final String PREF_MEDIA_ROOT = "media_root";
    private static final String PREF_MIGRATION_DONE = "legacy_migration_done";
    public static final String PREF_RETENTION_POLICY = "staging_retention_policy";

    // Download staging retention policies (Spec Section 17)
    public static final String RETENTION_IMMEDIATE_ON_IMPORT = "immediate"; // Zero duplicates: purge staging duplicate upon library import
    public static final String RETENTION_UNTIL_TORRENT_REMOVED = "torrent_removed";
    public static final String RETENTION_24_HOURS = "24h";
    public static final String RETENTION_3_DAYS = "3d";
    public static final String RETENTION_7_DAYS = "7d";
    public static final String RETENTION_MANUAL = "manual";

    public static final String DEFAULT_MEDIA_ROOT = "/storage/emulated/0/FishbowlMedia";
    public static final String LEGACY_MEDIA_ROOT = "/data/data/com.fishbowl.app/files/home/FishbowlMedia";

    // Required subdirectories according to specification
    public static final String SUB_DOWNLOADS = "Downloads";
    public static final String SUB_DOWNLOADS_RADARR = "Downloads/radarr";
    public static final String SUB_DOWNLOADS_SONARR = "Downloads/sonarr";
    public static final String SUB_DOWNLOADS_TORRENTS = "Downloads/torrents";
    public static final String SUB_MOVIES = "Movies";
    public static final String SUB_TV = "TV";
    public static final String SUB_MUSIC = "Music";
    public static final String SUB_BOOKS = "Books";
    public static final String SUB_OTHER = "Other";

    public static final String[] REQUIRED_SUBDIRECTORIES = new String[]{
            SUB_DOWNLOADS,
            SUB_DOWNLOADS + "/radarr",
            SUB_DOWNLOADS + "/sonarr",
            SUB_DOWNLOADS + "/torrents",
            SUB_DOWNLOADS + "/torrents/radarr",
            SUB_DOWNLOADS + "/torrents/sonarr",
            SUB_MOVIES,
            SUB_TV,
            SUB_MUSIC
    };

    private static MediaStorageManager sInstance;
    private final Context appContext;
    private final SharedPreferences prefs;

    public static synchronized MediaStorageManager getInstance(Context context) {
        if (sInstance == null && context != null) {
            sInstance = new MediaStorageManager(context.getApplicationContext());
        }
        return sInstance;
    }

    private MediaStorageManager(Context context) {
        this.appContext = context;
        this.prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
    }

    /**
     * Resolves the configured media root, defaulting to /storage/emulated/0/FishbowlMedia.
     */
    public File getMediaRoot() {
        String saved = prefs.getString(PREF_MEDIA_ROOT, DEFAULT_MEDIA_ROOT);
        if (saved == null || saved.trim().isEmpty()) {
            saved = DEFAULT_MEDIA_ROOT;
        }
        return new File(StorageDriveHelper.normalizeStoragePath(saved));
    }

    public File getDownloadsDir() {
        return new File(getMediaRoot(), SUB_DOWNLOADS);
    }

    public File getTransmissionDownloadsDir() {
        return getDownloadsDir();
    }

    public File getRadarrDownloadsDir() {
        return new File(getMediaRoot(), SUB_DOWNLOADS_RADARR);
    }

    public File getSonarrDownloadsDir() {
        return new File(getMediaRoot(), SUB_DOWNLOADS_SONARR);
    }

    public File getMoviesDir() {
        return new File(getMediaRoot(), SUB_MOVIES);
    }

    public File getTvDir() {
        return new File(getMediaRoot(), SUB_TV);
    }

    public File getMusicDir() {
        return new File(getMediaRoot(), SUB_MUSIC);
    }

    public File getBooksDir() {
        return new File(getMediaRoot(), SUB_BOOKS);
    }

    public File getOtherDir() {
        return new File(getMediaRoot(), SUB_OTHER);
    }

    // Canonical Media Storage API (Spec Section 28)
    public File getRoot() { return getMediaRoot(); }
    public File getDownloads() { return getDownloadsDir(); }
    public File getRadarrDownloads() { return getRadarrDownloadsDir(); }
    public File getSonarrDownloads() { return getSonarrDownloadsDir(); }
    public File getTorrents() { return new File(getDownloadsDir(), "torrents"); }
    public File getMovies() { return getMoviesDir(); }
    public File getTv() { return getTvDir(); }
    public File getMusic() { return getMusicDir(); }
    public File getBooks() { return getBooksDir(); }
    public File getOther() { return getOtherDir(); }

    public String getRetentionPolicy() {
        return prefs.getString(PREF_RETENTION_POLICY, RETENTION_IMMEDIATE_ON_IMPORT);
    }

    public void setRetentionPolicy(String policy) {
        if (policy != null) {
            prefs.edit().putString(PREF_RETENTION_POLICY, policy).apply();
        }
    }

    public String getRetentionPolicyDisplayName() {
        String p = getRetentionPolicy();
        switch (p) {
            case RETENTION_IMMEDIATE_ON_IMPORT: return "Immediate upon import (Zero duplicates - Recommended)";
            case RETENTION_24_HOURS: return "Keep for 24 hours after import";
            case RETENTION_3_DAYS: return "Keep for 3 days after import";
            case RETENTION_7_DAYS: return "Keep for 7 days after import";
            case RETENTION_MANUAL: return "Manual cleanup";
            case RETENTION_UNTIL_TORRENT_REMOVED:
            default: return "Keep until torrent removed";
        }
    }

    /**
     * Checks whether storage permission is granted for raw POSIX access.
     */
    public boolean hasStoragePermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            return Environment.isExternalStorageManager();
        } else {
            return ContextCompat.checkSelfPermission(appContext, Manifest.permission.WRITE_EXTERNAL_STORAGE)
                    == PackageManager.PERMISSION_GRANTED;
        }
    }

    /**
     * Requests user storage permission.
     */
    public void requestStoragePermission(Activity activity) {
        if (activity == null) return;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            try {
                Intent intent = new Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION);
                intent.setData(Uri.parse("package:" + activity.getPackageName()));
                activity.startActivity(intent);
            } catch (Throwable t) {
                try {
                    Intent intent = new Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION);
                    activity.startActivity(intent);
                } catch (Throwable ignored) {}
            }
        } else {
            ActivityCompat.requestPermissions(activity, new String[]{
                    Manifest.permission.READ_EXTERNAL_STORAGE,
                    Manifest.permission.WRITE_EXTERNAL_STORAGE
            }, 1001);
        }
    }

    /**
     * First launch initialization:
     * 1. Detect if media root exists.
     * 2. If not, create it and all subfolders.
     * 3. Probe read and write.
     * 4. Persist media root.
     * 5. Check and migrate legacy media if present.
     */
    public StorageDiagnosticResult initializeOnLaunch(Context context) {
        File root = getMediaRoot();
        Log.i(TAG, "Initializing media storage at: " + root.getAbsolutePath());

        boolean perm = hasStoragePermission();
        if (!perm) {
            Log.w(TAG, "Storage permission not yet granted. Attempting creation anyway (targetSdk compatibility)...");
        }

        boolean rootCreated = false;
        if (!root.exists()) {
            rootCreated = root.mkdirs();
            Log.i(TAG, "Created media root: " + root.getAbsolutePath() + " (success=" + rootCreated + ")");
        } else {
            rootCreated = true;
        }

        // Create all required subdirectories
        for (String sub : REQUIRED_SUBDIRECTORIES) {
            File subDir = new File(root, sub);
            if (!subDir.exists()) {
                boolean ok = subDir.mkdirs();
                Log.d(TAG, "Created subdirectory: " + subDir.getAbsolutePath() + " (success=" + ok + ")");
            }
        }

        // Persist media root
        prefs.edit().putString(PREF_MEDIA_ROOT, root.getAbsolutePath()).apply();

        // Check and migrate legacy internal media if present
        if (hasLegacyMedia()) {
            Log.i(TAG, "Legacy media detected in private app storage. Triggering automatic migration...");
            performLegacyMigration();
        }

        return runDiagnostics();
    }

    /**
     * Tests whether legacy media folder exists and has files.
     */
    public boolean hasLegacyMedia() {
        File legacy = new File(LEGACY_MEDIA_ROOT);
        if (!legacy.exists() || !legacy.isDirectory()) return false;
        File[] files = legacy.listFiles();
        if (files == null || files.length == 0) return false;

        // Check if there are actual media files or non-empty subdirectories
        for (File f : files) {
            if (f.isFile() && f.length() > 0) return true;
            if (f.isDirectory()) {
                File[] subs = f.listFiles();
                if (subs != null && subs.length > 0) return true;
            }
        }
        return false;
    }

    public static class MigrationResult {
        public final boolean success;
        public final int filesMigrated;
        public final long bytesMigrated;
        public final String message;

        public MigrationResult(boolean success, int filesMigrated, long bytesMigrated, String message) {
            this.success = success;
            this.filesMigrated = filesMigrated;
            this.bytesMigrated = bytesMigrated;
            this.message = message;
        }
    }

    /**
     * Seamlessly migrates existing movies, TV, music, downloads, and torrents from
     * /data/data/com.fishbowl.app/files/home/FishbowlMedia/ to /storage/emulated/0/FishbowlMedia/.
     * Old files are ONLY removed after each file is verified with matching size.
     */
    public synchronized MigrationResult performLegacyMigration() {
        File legacyRoot = new File(LEGACY_MEDIA_ROOT);
        File targetRoot = getMediaRoot();

        if (!legacyRoot.exists() || !legacyRoot.isDirectory()) {
            return new MigrationResult(true, 0, 0, "No legacy media directory found.");
        }

        if (!targetRoot.exists() && !targetRoot.mkdirs()) {
            return new MigrationResult(false, 0, 0, "Failed to create target media directory: " + targetRoot.getAbsolutePath());
        }

        int migratedCount = 0;
        long totalBytes = 0;
        List<File> toDeleteAfterVerification = new ArrayList<>();

        try {
            // Traverse legacy directory recursively
            List<File> allFiles = new ArrayList<>();
            collectFilesRecursive(legacyRoot, allFiles);

            for (File src : allFiles) {
                // Compute relative path
                String relPath = src.getAbsolutePath().substring(legacyRoot.getAbsolutePath().length());
                if (relPath.startsWith("/")) relPath = relPath.substring(1);

                // Map legacy subfolders to target structure:
                // If in Downloads/radarr -> Downloads/radarr
                // If in Downloads/sonarr -> Downloads/sonarr
                // If in Downloads root -> Downloads/torrents
                File dst;
                if (relPath.startsWith("Downloads/") && !relPath.startsWith("Downloads/radarr/")
                        && !relPath.startsWith("Downloads/sonarr/") && !relPath.startsWith("Downloads/torrents/")) {
                    String sub = relPath.substring("Downloads/".length());
                    dst = new File(targetRoot, "Downloads/torrents/" + sub);
                } else {
                    dst = new File(targetRoot, relPath);
                }

                File dstParent = dst.getParentFile();
                if (dstParent != null && !dstParent.exists()) {
                    dstParent.mkdirs();
                }

                boolean fileOk = false;
                if (dst.exists() && dst.length() == src.length()) {
                    // Already copied and verified
                    fileOk = true;
                } else {
                    Log.i(TAG, "Migrating file: " + src.getName() + " (" + (src.length() / (1024 * 1024)) + " MB)");
                    if (copyFileWithChannel(src, dst)) {
                        if (dst.exists() && dst.length() == src.length()) {
                            fileOk = true;
                            migratedCount++;
                            totalBytes += src.length();
                        } else {
                            Log.e(TAG, "Verification failed for migrated file: " + dst.getAbsolutePath());
                        }
                    }
                }

                if (fileOk) {
                    toDeleteAfterVerification.add(src);
                }
            }

            // Only delete verified files from old location
            for (File oldFile : toDeleteAfterVerification) {
                try {
                    oldFile.delete();
                } catch (Throwable ignored) {}
            }

            // Clean up empty directories in legacy root
            cleanEmptyDirsRecursive(legacyRoot);

            prefs.edit().putBoolean(PREF_MIGRATION_DONE, true).apply();
            String msg = "Successfully migrated " + migratedCount + " file(s) (" + (totalBytes / (1024 * 1024)) + " MB) to " + targetRoot.getAbsolutePath();
            Log.i(TAG, msg);
            return new MigrationResult(true, migratedCount, totalBytes, msg);

        } catch (Throwable t) {
            Log.e(TAG, "Migration encountered an error: " + t.getMessage(), t);
            return new MigrationResult(false, migratedCount, totalBytes, "Migration error: " + t.getMessage());
        }
    }

    private void collectFilesRecursive(File dir, List<File> list) {
        if (dir == null || !dir.exists()) return;
        File[] files = dir.listFiles();
        if (files == null) return;
        for (File f : files) {
            if (f.isFile()) {
                list.add(f);
            } else if (f.isDirectory()) {
                collectFilesRecursive(f, list);
            }
        }
    }

    public void cleanEmptyDirsRecursive(File dir) {
        if (dir == null || !dir.exists() || !dir.isDirectory()) return;
        File[] files = dir.listFiles();
        if (files != null) {
            for (File f : files) {
                if (f.isDirectory()) {
                    cleanEmptyDirsRecursive(f);
                }
            }
        }
        File[] remaining = dir.listFiles();
        if (remaining != null && remaining.length == 0) {
            String path = dir.getAbsolutePath();
            if (!path.equals(LEGACY_MEDIA_ROOT) &&
                    !path.equals(getMediaRoot().getAbsolutePath()) &&
                    !path.equals(getDownloadsDir().getAbsolutePath()) &&
                    !path.equals(getRadarrDownloadsDir().getAbsolutePath()) &&
                    !path.equals(getSonarrDownloadsDir().getAbsolutePath()) &&
                    !path.equals(getTorrents().getAbsolutePath()) &&
                    !path.equals(getMoviesDir().getAbsolutePath()) &&
                    !path.equals(getTvDir().getAbsolutePath()) &&
                    !path.equals(getMusicDir().getAbsolutePath())) {
                dir.delete();
            }
        }
    }

    private boolean copyFileWithChannel(File src, File dst) {
        try (FileChannel in = new FileInputStream(src).getChannel();
             FileChannel out = new FileOutputStream(dst).getChannel()) {
            long size = in.size();
            long transferred = 0;
            while (transferred < size) {
                long chunk = in.transferTo(transferred, size - transferred, out);
                if (chunk <= 0) break;
                transferred += chunk;
            }
            return transferred == size;
        } catch (IOException e) {
            Log.e(TAG, "copyFileWithChannel failed: " + e.getMessage());
            return false;
        }
    }

    // =========================================================================
    // HARDLINK CAPABILITY PROBING & IDENTIFICATION (Spec Sections 3, 4, 5)
    // =========================================================================

    public static class HardlinkProbeResult {
        public final boolean supported;
        public final boolean sameFilesystem;
        public final String reason;
        public final long probeTimeMillis;

        public HardlinkProbeResult(boolean supported, boolean sameFilesystem, String reason) {
            this.supported = supported;
            this.sameFilesystem = sameFilesystem;
            this.reason = reason;
            this.probeTimeMillis = System.currentTimeMillis();
        }
    }

    private HardlinkProbeResult cachedHardlinkProbeResult;

    public synchronized HardlinkProbeResult probeHardlinkSupport() {
        File root = getMediaRoot();
        File dlRadarr = getRadarrDownloadsDir();
        File movies = getMoviesDir();

        if (!dlRadarr.exists()) dlRadarr.mkdirs();
        if (!movies.exists()) movies.mkdirs();

        boolean sameFs = false;
        try {
            StructStat dlStat = Os.stat(dlRadarr.getAbsolutePath());
            StructStat movStat = Os.stat(movies.getAbsolutePath());
            sameFs = (dlStat.st_dev == movStat.st_dev);
        } catch (Throwable t) {
            Log.w(TAG, "stat failed for filesystem probe: " + t.getMessage());
        }

        File probeSrc = new File(dlRadarr, ".probe_hl_src_" + System.currentTimeMillis() + ".tmp");
        File probeDst = new File(movies, ".probe_hl_dst_" + System.currentTimeMillis() + ".tmp");

        try {
            try (FileOutputStream fos = new FileOutputStream(probeSrc)) {
                fos.write(42);
            }

            // Attempt native hardlink via Bionic Os.link
            Os.link(probeSrc.getAbsolutePath(), probeDst.getAbsolutePath());

            StructStat srcStat = Os.stat(probeSrc.getAbsolutePath());
            StructStat dstStat = Os.stat(probeDst.getAbsolutePath());

            boolean ok = (srcStat.st_ino == dstStat.st_ino && srcStat.st_dev == dstStat.st_dev && srcStat.st_nlink >= 2);

            probeDst.delete();
            probeSrc.delete();

            if (ok) {
                cachedHardlinkProbeResult = new HardlinkProbeResult(true, sameFs,
                        "Supported (Verified hardlink on same filesystem, dev=" + srcStat.st_dev + ")");
            } else {
                cachedHardlinkProbeResult = new HardlinkProbeResult(false, sameFs,
                        "Inodes did not match after link creation");
            }
        } catch (ErrnoException e) {
            String msg = "Unsupported (" + e.getMessage() + ")";
            if (e.errno == OsConstants.EPERM) {
                msg = "Unsupported (Android FUSE layer restricts link() cross-directory access: EPERM)";
            } else if (e.errno == OsConstants.EXDEV) {
                msg = "Unsupported (Cross-device link not permitted: EXDEV)";
            } else if (e.errno == OsConstants.EACCES) {
                msg = "Unsupported (Permission denied: EACCES)";
            }
            cachedHardlinkProbeResult = new HardlinkProbeResult(false, sameFs, msg);
        } catch (Throwable t) {
            cachedHardlinkProbeResult = new HardlinkProbeResult(false, sameFs, "Unsupported (" + t.getMessage() + ")");
        } finally {
            if (probeDst.exists()) probeDst.delete();
            if (probeSrc.exists()) probeSrc.delete();
        }

        return cachedHardlinkProbeResult;
    }

    public boolean isHardlinkSupported() {
        if (cachedHardlinkProbeResult == null) {
            probeHardlinkSupport();
        }
        return cachedHardlinkProbeResult != null && cachedHardlinkProbeResult.supported;
    }

    public String getHardlinkSupportReason() {
        if (cachedHardlinkProbeResult == null) {
            probeHardlinkSupport();
        }
        return cachedHardlinkProbeResult != null ? cachedHardlinkProbeResult.reason : "Not probed";
    }

    public static class FileIdentity {
        public final long dev;
        public final long ino;
        public final long nlink;
        public final long size;

        public FileIdentity(long dev, long ino, long nlink, long size) {
            this.dev = dev;
            this.ino = ino;
            this.nlink = nlink;
            this.size = size;
        }
    }

    public FileIdentity getFileIdentity(File file) {
        if (file == null || !file.exists() || !file.isFile()) return null;
        try {
            StructStat stat = Os.stat(file.getAbsolutePath());
            return new FileIdentity(stat.st_dev, stat.st_ino, stat.st_nlink, stat.st_size);
        } catch (Throwable t) {
            return null;
        }
    }

    public boolean areFilesHardlinked(File f1, File f2) {
        if (f1 == null || f2 == null || !f1.exists() || !f2.exists()) return false;
        if (!f1.isFile() || !f2.isFile()) return false;
        if (f1.length() != f2.length()) return false;
        try {
            StructStat s1 = Os.stat(f1.getAbsolutePath());
            StructStat s2 = Os.stat(f2.getAbsolutePath());
            return (s1.st_dev == s2.st_dev && s1.st_ino == s2.st_ino && s1.st_nlink >= 2);
        } catch (Throwable t) {
            return false;
        }
    }

    // =========================================================================
    // TRANSMISSION RPC TORRENT QUERY (Spec Sections 6, 7, 8, 16)
    // =========================================================================

    public static final int TR_STATUS_STOPPED = 0;
    public static final int TR_STATUS_CHECK_WAIT = 1;
    public static final int TR_STATUS_CHECK = 2;
    public static final int TR_STATUS_DOWNLOAD_WAIT = 3;
    public static final int TR_STATUS_DOWNLOAD = 4;
    public static final int TR_STATUS_SEED_WAIT = 5;
    public static final int TR_STATUS_SEED = 6;

    public enum DownloadLifecycleState {
        QUEUED,
        CHECKING,
        DOWNLOADING,
        STALLED,
        WAITING_FINAL_PIECES,
        VERIFYING,
        SEEDING,
        COMPLETE,
        PAUSED,
        ERROR
    }

    public static class TorrentInfo {
        public final int id;
        public final String name;
        public final int status;
        public final double percentDone;
        public final String downloadDir;
        public final List<String> fileNames;
        public final boolean isFinished;
        public final long doneDate;
        public final long rateDownload;
        public final long rateUpload;
        public final long eta;
        public final long leftUntilDone;
        public final long sizeWhenDone;
        public final String errorString;

        public TorrentInfo(int id, String name, int status, double percentDone,
                           String downloadDir, List<String> fileNames, boolean isFinished, long doneDate,
                           long rateDownload, long rateUpload, long eta, long leftUntilDone, long sizeWhenDone, String errorString) {
            this.id = id;
            this.name = name;
            this.status = status;
            this.percentDone = percentDone;
            this.downloadDir = downloadDir;
            this.fileNames = fileNames;
            this.isFinished = isFinished;
            this.doneDate = doneDate;
            this.rateDownload = rateDownload;
            this.rateUpload = rateUpload;
            this.eta = eta;
            this.leftUntilDone = leftUntilDone;
            this.sizeWhenDone = sizeWhenDone;
            this.errorString = errorString;
        }

        public TorrentInfo(int id, String name, int status, double percentDone,
                           String downloadDir, List<String> fileNames, boolean isFinished, long doneDate) {
            this(id, name, status, percentDone, downloadDir, fileNames, isFinished, doneDate, 0, 0, -1, 0, 0, null);
        }

        public boolean isSeeding() {
            return status == TR_STATUS_SEED_WAIT || status == TR_STATUS_SEED;
        }

        public boolean isActive() {
            return status >= 1 && status <= 6;
        }

        public DownloadLifecycleState getLifecycleState() {
            if (errorString != null && !errorString.trim().isEmpty()) {
                return DownloadLifecycleState.ERROR;
            }
            if (status == TR_STATUS_STOPPED) {
                if (isFinished || (percentDone >= 1.0 && leftUntilDone == 0)) {
                    return DownloadLifecycleState.COMPLETE;
                }
                return DownloadLifecycleState.PAUSED;
            }
            if (status == TR_STATUS_CHECK_WAIT) {
                return DownloadLifecycleState.QUEUED;
            }
            if (status == TR_STATUS_CHECK) {
                return percentDone >= 0.99 ? DownloadLifecycleState.VERIFYING : DownloadLifecycleState.CHECKING;
            }
            if (status == TR_STATUS_DOWNLOAD_WAIT || status == TR_STATUS_SEED_WAIT) {
                return DownloadLifecycleState.QUEUED;
            }
            if (status == TR_STATUS_SEED) {
                return DownloadLifecycleState.SEEDING;
            }
            if (status == TR_STATUS_DOWNLOAD) {
                if (percentDone >= 0.999 && (!isFinished || leftUntilDone > 0)) {
                    return DownloadLifecycleState.WAITING_FINAL_PIECES;
                }
                if (rateDownload == 0) {
                    return DownloadLifecycleState.STALLED;
                }
                return DownloadLifecycleState.DOWNLOADING;
            }
            if (isFinished && percentDone >= 1.0 && leftUntilDone == 0) {
                return DownloadLifecycleState.COMPLETE;
            }
            return DownloadLifecycleState.DOWNLOADING;
        }

        public String getStatusString() {
            switch (getLifecycleState()) {
                case QUEUED: return "Queued";
                case CHECKING: return "Checking Files";
                case DOWNLOADING: return "Downloading (" + Math.round(percentDone * 100) + "%)";
                case STALLED: return "Stalled (" + Math.round(percentDone * 100) + "%)";
                case WAITING_FINAL_PIECES: return "Waiting for final pieces (" + String.format(Locale.US, "%.2f", percentDone * 100) + "%)";
                case VERIFYING: return "Verifying Integrity";
                case SEEDING: return "Seeding (100%)";
                case COMPLETE: return "Complete";
                case PAUSED: return "Paused";
                case ERROR: return "Error: " + (errorString != null ? errorString : "Unknown");
                default: return "Active";
            }
        }
    }

    private String cachedTransmissionSessionId = null;

    public List<TorrentInfo> queryTransmissionTorrents() {
        List<TorrentInfo> result = new ArrayList<>();
        HttpURLConnection conn = null;
        try {
            URL url = new URL("http://127.0.0.1:9091/transmission/rpc");
            JSONObject rpcReq = new JSONObject();
            rpcReq.put("method", "torrent-get");
            JSONObject args = new JSONObject();
            JSONArray fields = new JSONArray();
            fields.put("id");
            fields.put("name");
            fields.put("status");
            fields.put("percentDone");
            fields.put("downloadDir");
            fields.put("files");
            fields.put("isFinished");
            fields.put("doneDate");
            fields.put("rateDownload");
            fields.put("rateUpload");
            fields.put("eta");
            fields.put("leftUntilDone");
            fields.put("sizeWhenDone");
            fields.put("errorString");
            args.put("fields", fields);
            rpcReq.put("arguments", args);

            String payload = rpcReq.toString();
            conn = (HttpURLConnection) url.openConnection();
            conn.setRequestMethod("POST");
            conn.setConnectTimeout(2500);
            conn.setReadTimeout(2500);
            conn.setRequestProperty("Content-Type", "application/json");
            if (cachedTransmissionSessionId != null) {
                conn.setRequestProperty("X-Transmission-Session-Id", cachedTransmissionSessionId);
            }
            conn.setDoOutput(true);
            try (OutputStream os = conn.getOutputStream()) {
                os.write(payload.getBytes(StandardCharsets.UTF_8));
            }

            int code = conn.getResponseCode();
            if (code == 409) {
                // CSRF Protection: Retrieve X-Transmission-Session-Id and retry
                String newSessionId = conn.getHeaderField("X-Transmission-Session-Id");
                conn.disconnect();
                if (newSessionId != null && !newSessionId.isEmpty()) {
                    cachedTransmissionSessionId = newSessionId;
                    conn = (HttpURLConnection) url.openConnection();
                    conn.setRequestMethod("POST");
                    conn.setConnectTimeout(2500);
                    conn.setReadTimeout(2500);
                    conn.setRequestProperty("Content-Type", "application/json");
                    conn.setRequestProperty("X-Transmission-Session-Id", cachedTransmissionSessionId);
                    conn.setDoOutput(true);
                    try (OutputStream os = conn.getOutputStream()) {
                        os.write(payload.getBytes(StandardCharsets.UTF_8));
                    }
                    code = conn.getResponseCode();
                }
            }

            if (code == 200) {
                StringBuilder sb = new StringBuilder();
                try (BufferedReader br = new BufferedReader(new InputStreamReader(conn.getInputStream(), StandardCharsets.UTF_8))) {
                    String line;
                    while ((line = br.readLine()) != null) sb.append(line);
                }
                JSONObject resObj = new JSONObject(sb.toString());
                JSONObject argsObj = resObj.optJSONObject("arguments");
                if (argsObj != null) {
                    JSONArray torrents = argsObj.optJSONArray("torrents");
                    if (torrents != null) {
                        for (int i = 0; i < torrents.length(); i++) {
                            JSONObject t = torrents.getJSONObject(i);
                            int id = t.optInt("id");
                            String name = t.optString("name");
                            int status = t.optInt("status");
                            double pct = t.optDouble("percentDone");
                            String dlDir = t.optString("downloadDir");
                            boolean finished = t.optBoolean("isFinished");
                            long doneDate = t.optLong("doneDate");
                            long rateDl = t.optLong("rateDownload");
                            long rateUl = t.optLong("rateUpload");
                            long eta = t.optLong("eta");
                            long leftUntilDone = t.optLong("leftUntilDone");
                            long sizeWhenDone = t.optLong("sizeWhenDone");
                            String errStr = t.optString("errorString", "");

                            List<String> files = new ArrayList<>();
                            JSONArray filesArr = t.optJSONArray("files");
                            if (filesArr != null) {
                                for (int j = 0; j < filesArr.length(); j++) {
                                    JSONObject f = filesArr.getJSONObject(j);
                                    files.add(f.optString("name"));
                                }
                            }
                            result.add(new TorrentInfo(id, name, status, pct, dlDir, files, finished, doneDate,
                                    rateDl, rateUl, eta, leftUntilDone, sizeWhenDone, errStr));
                        }
                    }
                }
            }
        } catch (Throwable t) {
            Log.d(TAG, "queryTransmissionTorrents skipped or unavailable: " + t.getMessage());
        } finally {
            if (conn != null) conn.disconnect();
        }
        return result;
    }

    public boolean removeTransmissionTorrent(int torrentId, boolean deleteData) {
        HttpURLConnection conn = null;
        try {
            URL url = new URL("http://127.0.0.1:9091/transmission/rpc");
            JSONObject rpcReq = new JSONObject();
            rpcReq.put("method", "torrent-remove");
            JSONObject args = new JSONObject();
            JSONArray ids = new JSONArray();
            ids.put(torrentId);
            args.put("ids", ids);
            args.put("delete-local-data", deleteData);
            rpcReq.put("arguments", args);

            String payload = rpcReq.toString();
            conn = (HttpURLConnection) url.openConnection();
            conn.setRequestMethod("POST");
            conn.setConnectTimeout(2500);
            conn.setReadTimeout(2500);
            conn.setRequestProperty("Content-Type", "application/json");
            if (cachedTransmissionSessionId != null) {
                conn.setRequestProperty("X-Transmission-Session-Id", cachedTransmissionSessionId);
            }
            conn.setDoOutput(true);
            try (OutputStream os = conn.getOutputStream()) {
                os.write(payload.getBytes(StandardCharsets.UTF_8));
            }

            int code = conn.getResponseCode();
            if (code == 409) {
                String newSessionId = conn.getHeaderField("X-Transmission-Session-Id");
                conn.disconnect();
                if (newSessionId != null && !newSessionId.isEmpty()) {
                    cachedTransmissionSessionId = newSessionId;
                    conn = (HttpURLConnection) url.openConnection();
                    conn.setRequestMethod("POST");
                    conn.setConnectTimeout(2500);
                    conn.setReadTimeout(2500);
                    conn.setRequestProperty("Content-Type", "application/json");
                    conn.setRequestProperty("X-Transmission-Session-Id", cachedTransmissionSessionId);
                    conn.setDoOutput(true);
                    try (OutputStream os = conn.getOutputStream()) {
                        os.write(payload.getBytes(StandardCharsets.UTF_8));
                    }
                    code = conn.getResponseCode();
                }
            }
            return code == 200;
        } catch (Throwable t) {
            Log.d(TAG, "removeTransmissionTorrent failed: " + t.getMessage());
            return false;
        } finally {
            if (conn != null) conn.disconnect();
        }
    }

    public int clearCompletedTransmissionTorrents(boolean deleteData) {
        List<TorrentInfo> torrents = queryTransmissionTorrents();
        int count = 0;
        for (TorrentInfo t : torrents) {
            DownloadLifecycleState state = t.getLifecycleState();
            if (state == DownloadLifecycleState.COMPLETE
                    || state == DownloadLifecycleState.ERROR
                    || state == DownloadLifecycleState.PAUSED
                    || t.isFinished
                    || t.percentDone >= 1.0) {
                if (removeTransmissionTorrent(t.id, deleteData)) {
                    count++;
                }
            }
        }
        return count;
    }

    public int clearAllTransmissionTorrents(boolean deleteData) {
        List<TorrentInfo> torrents = queryTransmissionTorrents();
        int count = 0;
        for (TorrentInfo t : torrents) {
            if (removeTransmissionTorrent(t.id, deleteData)) {
                count++;
            }
        }
        return count;
    }

    public boolean setTransmissionSessionLimits(boolean ratioLimited, double ratioLimit) {
        HttpURLConnection conn = null;
        try {
            URL url = new URL("http://127.0.0.1:9091/transmission/rpc");
            JSONObject rpcReq = new JSONObject();
            rpcReq.put("method", "session-set");
            JSONObject args = new JSONObject();
            args.put("seedRatioLimited", ratioLimited);
            args.put("seedRatioLimit", ratioLimit);
            args.put("idle-seeding-limit-enabled", true);
            args.put("idle-seeding-limit", 0);
            rpcReq.put("arguments", args);

            String payload = rpcReq.toString();
            conn = (HttpURLConnection) url.openConnection();
            conn.setRequestMethod("POST");
            conn.setConnectTimeout(2500);
            conn.setReadTimeout(2500);
            conn.setRequestProperty("Content-Type", "application/json");
            if (cachedTransmissionSessionId != null) {
                conn.setRequestProperty("X-Transmission-Session-Id", cachedTransmissionSessionId);
            }
            conn.setDoOutput(true);
            try (OutputStream os = conn.getOutputStream()) {
                os.write(payload.getBytes(StandardCharsets.UTF_8));
            }

            int code = conn.getResponseCode();
            if (code == 409) {
                String newSessionId = conn.getHeaderField("X-Transmission-Session-Id");
                conn.disconnect();
                if (newSessionId != null && !newSessionId.isEmpty()) {
                    cachedTransmissionSessionId = newSessionId;
                    conn = (HttpURLConnection) url.openConnection();
                    conn.setRequestMethod("POST");
                    conn.setConnectTimeout(2500);
                    conn.setReadTimeout(2500);
                    conn.setRequestProperty("Content-Type", "application/json");
                    conn.setRequestProperty("X-Transmission-Session-Id", cachedTransmissionSessionId);
                    conn.setDoOutput(true);
                    try (OutputStream os = conn.getOutputStream()) {
                        os.write(payload.getBytes(StandardCharsets.UTF_8));
                    }
                    code = conn.getResponseCode();
                }
            }
            return code == 200;
        } catch (Throwable t) {
            Log.d(TAG, "setTransmissionSessionLimits failed: " + t.getMessage());
            return false;
        } finally {
            if (conn != null) conn.disconnect();
        }
    }

    // =========================================================================
    // STAGING & DUPLICATE ANALYSIS ENGINE (Spec Sections 15, 16, 17)
    // =========================================================================

    public enum StagingItemType {
        HARDLINK,
        PHYSICAL_DUPLICATE,
        ORPHAN,
        ACTIVE_DOWNLOAD
    }

    public static class StagingMediaItem {
        public final File stagingFile;
        public final File libraryFile;
        public final String category; // "radarr" or "sonarr"
        public final StagingItemType type;
        public final long sizeBytes;
        public final boolean isHardlink;
        public final boolean isPhysicalDuplicate;
        public final boolean isOrphan;
        public final boolean isSeeding;
        public final boolean isTorrentActive;
        public final String torrentName;
        public final String torrentStatusString;
        public final TorrentInfo matchingTorrent;
        public final boolean isSafeToClean;
        public final String statusSummary;

        public StagingMediaItem(File stagingFile, File libraryFile, String category,
                                StagingItemType type, long sizeBytes,
                                boolean isHardlink, boolean isPhysicalDuplicate, boolean isOrphan,
                                boolean isSeeding, boolean isTorrentActive, String torrentName,
                                String torrentStatusString, boolean isSafeToClean, String statusSummary) {
            this(stagingFile, libraryFile, category, type, sizeBytes, isHardlink, isPhysicalDuplicate, isOrphan,
                 isSeeding, isTorrentActive, torrentName, torrentStatusString, null, isSafeToClean, statusSummary);
        }

        public StagingMediaItem(File stagingFile, File libraryFile, String category,
                                StagingItemType type, long sizeBytes,
                                boolean isHardlink, boolean isPhysicalDuplicate, boolean isOrphan,
                                boolean isSeeding, boolean isTorrentActive, String torrentName,
                                String torrentStatusString, TorrentInfo matchingTorrent,
                                boolean isSafeToClean, String statusSummary) {
            this.stagingFile = stagingFile;
            this.libraryFile = libraryFile;
            this.category = category;
            this.type = type;
            this.sizeBytes = sizeBytes;
            this.isHardlink = isHardlink;
            this.isPhysicalDuplicate = isPhysicalDuplicate;
            this.isOrphan = isOrphan;
            this.isSeeding = isSeeding;
            this.isTorrentActive = isTorrentActive;
            this.torrentName = torrentName;
            this.torrentStatusString = torrentStatusString;
            this.matchingTorrent = matchingTorrent;
            this.isSafeToClean = isSafeToClean;
            this.statusSummary = statusSummary;
        }
    }

    public static class StagingAnalysisReport {
        public final List<StagingMediaItem> items;
        public final long totalStagingLogicalBytes;
        public final long totalStagingPhysicalBytes;
        public final long totalPotentialSavingsBytes;
        public final int hardlinkCount;
        public final int duplicateCount;
        public final int orphanCount;
        public final int activeSeedingCount;
        public final int safeToCleanCount;
        public final long safeToCleanBytes;
        public final boolean hardlinkSupported;
        public final String hardlinkSupportReason;
        public final String retentionPolicy;
        public final long timestamp;

        public StagingAnalysisReport(List<StagingMediaItem> items, long totalStagingLogicalBytes,
                                     long totalStagingPhysicalBytes, long totalPotentialSavingsBytes,
                                     int hardlinkCount, int duplicateCount, int orphanCount,
                                     int activeSeedingCount, int safeToCleanCount, long safeToCleanBytes,
                                     boolean hardlinkSupported, String hardlinkSupportReason,
                                     String retentionPolicy) {
            this.items = items;
            this.totalStagingLogicalBytes = totalStagingLogicalBytes;
            this.totalStagingPhysicalBytes = totalStagingPhysicalBytes;
            this.totalPotentialSavingsBytes = totalPotentialSavingsBytes;
            this.hardlinkCount = hardlinkCount;
            this.duplicateCount = duplicateCount;
            this.orphanCount = orphanCount;
            this.activeSeedingCount = activeSeedingCount;
            this.safeToCleanCount = safeToCleanCount;
            this.safeToCleanBytes = safeToCleanBytes;
            this.hardlinkSupported = hardlinkSupported;
            this.hardlinkSupportReason = hardlinkSupportReason;
            this.retentionPolicy = retentionPolicy;
            this.timestamp = System.currentTimeMillis();
        }
    }

    public StagingAnalysisReport scanStagingAndDuplicates() {
        boolean hlSupported = isHardlinkSupported();
        String hlReason = getHardlinkSupportReason();
        String retentionPolicy = getRetentionPolicy();

        List<TorrentInfo> torrents = queryTransmissionTorrents();

        // Index library files: Movies and TV
        File moviesDir = getMoviesDir();
        File tvDir = getTvDir();

        List<File> allMovieFiles = new ArrayList<>();
        collectFilesRecursive(moviesDir, allMovieFiles);
        Map<String, File> movieFilesByName = new LinkedHashMap<>();
        Map<Long, List<File>> movieFilesBySize = new LinkedHashMap<>();
        for (File f : allMovieFiles) {
            movieFilesByName.put(f.getName().toLowerCase(Locale.US), f);
            long sz = f.length();
            if (!movieFilesBySize.containsKey(sz)) movieFilesBySize.put(sz, new ArrayList<>());
            movieFilesBySize.get(sz).add(f);
        }

        List<File> allTvFiles = new ArrayList<>();
        collectFilesRecursive(tvDir, allTvFiles);
        Map<String, File> tvFilesByName = new LinkedHashMap<>();
        Map<Long, List<File>> tvFilesBySize = new LinkedHashMap<>();
        for (File f : allTvFiles) {
            tvFilesByName.put(f.getName().toLowerCase(Locale.US), f);
            long sz = f.length();
            if (!tvFilesBySize.containsKey(sz)) tvFilesBySize.put(sz, new ArrayList<>());
            tvFilesBySize.get(sz).add(f);
        }

        List<StagingMediaItem> items = new ArrayList<>();
        long totalLogical = 0;
        long totalPhysical = 0;
        long potentialSavings = 0;
        int hlCount = 0;
        int dupCount = 0;
        int orphanCount = 0;
        int activeSeedingCount = 0;
        int safeCleanCount = 0;
        long safeCleanBytes = 0;

        // 1. Scan Downloads/radarr
        File radarrStaging = getRadarrDownloadsDir();
        List<File> radarrFiles = new ArrayList<>();
        collectFilesRecursive(radarrStaging, radarrFiles);

        // 2. Scan Downloads/sonarr
        File sonarrStaging = getSonarrDownloadsDir();
        List<File> sonarrFiles = new ArrayList<>();
        collectFilesRecursive(sonarrStaging, sonarrFiles);

        List<File> combinedStaging = new ArrayList<>();
        for (File f : radarrFiles) {
            if (f.isFile() && !f.getName().startsWith(".")) combinedStaging.add(f);
        }
        for (File f : sonarrFiles) {
            if (f.isFile() && !f.getName().startsWith(".")) combinedStaging.add(f);
        }

        for (File stagingFile : combinedStaging) {
            long fileSize = stagingFile.length();
            totalLogical += fileSize;

            boolean isRadarr = stagingFile.getAbsolutePath().startsWith(radarrStaging.getAbsolutePath());
            String category = isRadarr ? "radarr" : "sonarr";
            Map<String, File> libByName = isRadarr ? movieFilesByName : tvFilesByName;
            Map<Long, List<File>> libBySize = isRadarr ? movieFilesBySize : tvFilesBySize;

            // Find library match
            File libraryFile = libByName.get(stagingFile.getName().toLowerCase(Locale.US));
            if (libraryFile == null && fileSize > 10 * 1024 * 1024L) { // >10MB
                List<File> sameSize = libBySize.get(fileSize);
                if (sameSize != null && !sameSize.isEmpty()) {
                    libraryFile = sameSize.get(0);
                }
            }

            // Check hardlink
            boolean isHardlink = (libraryFile != null) && areFilesHardlinked(stagingFile, libraryFile);
            boolean isPhysicalDuplicate = (libraryFile != null) && !isHardlink && (libraryFile.length() == fileSize);

            // Match with Transmission torrents
            TorrentInfo matchingTorrent = null;
            for (TorrentInfo t : torrents) {
                for (String tf : t.fileNames) {
                    if (stagingFile.getName().equalsIgnoreCase(new File(tf).getName()) ||
                            stagingFile.getAbsolutePath().contains(tf)) {
                        matchingTorrent = t;
                        break;
                    }
                }
                if (matchingTorrent != null) break;
            }

            boolean isSeeding = matchingTorrent != null && matchingTorrent.isSeeding();
            boolean isTorrentActive = matchingTorrent != null && matchingTorrent.isActive();
            String torrentName = matchingTorrent != null ? matchingTorrent.name : null;
            String torrentStatusStr = matchingTorrent != null ? matchingTorrent.getStatusString() : "Not in torrent client";

            if (isSeeding) activeSeedingCount++;

            StagingItemType type;
            boolean isOrphan = false;

            if (isHardlink) {
                type = StagingItemType.HARDLINK;
                hlCount++;
                // Inodes shared, physical storage is 0 extra
            } else if (isPhysicalDuplicate) {
                type = StagingItemType.PHYSICAL_DUPLICATE;
                dupCount++;
                totalPhysical += fileSize;
                potentialSavings += fileSize;
            } else if (isTorrentActive) {
                type = StagingItemType.ACTIVE_DOWNLOAD;
                totalPhysical += fileSize;
            } else {
                type = StagingItemType.ORPHAN;
                isOrphan = true;
                orphanCount++;
                totalPhysical += fileSize;
                potentialSavings += fileSize;
            }

            // Evaluate Safe to Clean based on Seeding Protection and Retention Policy
            boolean isSafeToClean = false;
            long fileAgeMillis = System.currentTimeMillis() - stagingFile.lastModified();

            if (isPhysicalDuplicate) {
                switch (retentionPolicy) {
                    case RETENTION_MANUAL:
                        isSafeToClean = false;
                        break;
                    case RETENTION_24_HOURS:
                        isSafeToClean = (!isSeeding && !isTorrentActive) || fileAgeMillis >= 24 * 3600 * 1000L;
                        break;
                    case RETENTION_3_DAYS:
                        isSafeToClean = (!isSeeding && !isTorrentActive) || fileAgeMillis >= 3 * 24 * 3600 * 1000L;
                        break;
                    case RETENTION_7_DAYS:
                        isSafeToClean = (!isSeeding && !isTorrentActive) || fileAgeMillis >= 7 * 24 * 3600 * 1000L;
                        break;
                    case RETENTION_UNTIL_TORRENT_REMOVED:
                        isSafeToClean = !isSeeding && !isTorrentActive;
                        break;
                    case RETENTION_IMMEDIATE_ON_IMPORT:
                    default:
                        // Immediate Zero Duplicates: library copy is 100% verified to exist and match in size
                        isSafeToClean = true;
                        break;
                }
            } else if (isHardlink) {
                // Hardlink consumes no duplicate space; only safe to clean if torrent is inactive
                isSafeToClean = !isSeeding && !isTorrentActive;
            } else if (isOrphan) {
                // Orphan has no library match and no active torrent
                isSafeToClean = !isTorrentActive;
            }

            if (isSafeToClean) {
                safeCleanCount++;
                safeCleanBytes += fileSize;
            }

            String summary;
            if (isHardlink) {
                summary = "[OK] Hardlink (0 MB extra physical space)";
            } else if (isPhysicalDuplicate) {
                summary = "[WARN] Physical duplicate (" + (isSafeToClean ? "Safe to clean" : "Protected / retained") + ")";
            } else if (isOrphan) {
                summary = "[INFO] Orphan download (No matching library file)";
            } else {
                summary = "[ACTIVE] Active download in progress";
            }

            items.add(new StagingMediaItem(
                    stagingFile, libraryFile, category, type, fileSize,
                    isHardlink, isPhysicalDuplicate, isOrphan,
                    isSeeding, isTorrentActive, torrentName, torrentStatusStr,
                    matchingTorrent, isSafeToClean, summary
            ));
        }

        return new StagingAnalysisReport(
                items, totalLogical, totalPhysical, potentialSavings,
                hlCount, dupCount, orphanCount, activeSeedingCount,
                safeCleanCount, safeCleanBytes, hlSupported, hlReason,
                retentionPolicy
        );
    }

    public synchronized boolean cleanupStagingFile(StagingMediaItem item, boolean force) {
        if (item == null || item.stagingFile == null || !item.stagingFile.exists()) return false;

        // Invariant 1: If physical duplicate, ensure library file is verified!
        if (item.isPhysicalDuplicate) {
            if (item.libraryFile == null || !item.libraryFile.exists() || item.libraryFile.length() != item.stagingFile.length()) {
                Log.e(TAG, "Refusing to delete staging duplicate: library copy not verified!");
                return false;
            }
        }

        // If a matching torrent is in Transmission, stop and remove it
        if (item.matchingTorrent != null) {
            removeTransmissionTorrent(item.matchingTorrent.id, false);
        } else if (item.isSeeding && !force) {
            Log.w(TAG, "Refusing to delete staging file: active Transmission seeding torrent requires it!");
            return false;
        }

        // Preserve companion subtitles before deleting staging folder
        preserveCompanionSubtitles(item.stagingFile, item.libraryFile);

        boolean deleted = item.stagingFile.delete();
        if (deleted) {
            Log.i(TAG, "Successfully removed staging file: " + item.stagingFile.getAbsolutePath());
            cleanEmptyStagingParents(item.stagingFile.getParentFile(), item.category);
        }
        return deleted;
    }

    public synchronized boolean repairDuplicateWithHardlink(StagingMediaItem item) {
        if (item == null || !item.isPhysicalDuplicate || item.libraryFile == null) return false;
        if (!item.stagingFile.exists() || !item.libraryFile.exists()) return false;
        if (item.stagingFile.length() != item.libraryFile.length()) return false;

        if (!isHardlinkSupported()) {
            Log.w(TAG, "Cannot repair duplicate with hardlink: hardlink unsupported on filesystem (" + getHardlinkSupportReason() + ")");
            return false;
        }

        try {
            File staging = item.stagingFile;
            File library = item.libraryFile;
            if (!staging.delete()) return false;
            Os.link(library.getAbsolutePath(), staging.getAbsolutePath());
            return areFilesHardlinked(staging, library);
        } catch (Throwable t) {
            Log.e(TAG, "repairDuplicateWithHardlink failed: " + t.getMessage(), t);
            return false;
        }
    }

    private static final Set<String> MEDIA_EXTENSIONS = new HashSet<>(Arrays.asList(
            ".mp4", ".mkv", ".avi", ".mov", ".wmv", ".webm", ".flv", ".m4v",
            ".ts", ".m2ts", ".vob", ".iso", ".mp3", ".flac", ".m4a", ".aac",
            ".ogg", ".wav", ".opus"
    ));

    public boolean containsMediaFiles(File dir) {
        if (dir == null || !dir.exists() || !dir.isDirectory()) return false;
        File[] files = dir.listFiles();
        if (files == null) return false;
        for (File f : files) {
            if (f.isDirectory()) {
                if (containsMediaFiles(f)) return true;
            } else if (f.isFile()) {
                String name = f.getName().toLowerCase(Locale.US);
                for (String ext : MEDIA_EXTENSIONS) {
                    if (name.endsWith(ext)) return true;
                }
            }
        }
        return false;
    }

    public boolean deleteDirectoryContents(File dir) {
        if (dir == null || !dir.exists() || !dir.isDirectory()) return false;
        File[] files = dir.listFiles();
        if (files == null) return true;
        boolean allDeleted = true;
        for (File f : files) {
            if (f.isDirectory()) {
                deleteDirectoryContents(f);
                if (!f.delete()) allDeleted = false;
            } else {
                if (!f.delete()) allDeleted = false;
            }
        }
        return allDeleted;
    }

    public synchronized int cleanupAllSafeDuplicates(boolean force) {
        StagingAnalysisReport report = scanStagingAndDuplicates();
        int cleaned = 0;
        for (StagingMediaItem item : report.items) {
            if (item.isSafeToClean || (force && item.isPhysicalDuplicate && !item.isSeeding)) {
                if (cleanupStagingFile(item, force)) {
                    cleaned++;
                }
            }
        }
        cleanupStagingJunkAndEmptyDirs(getRadarrDownloadsDir());
        cleanupStagingJunkAndEmptyDirs(getSonarrDownloadsDir());
        cleanupStagingJunkAndEmptyDirs(getTorrents());
        cleanEmptyDirsRecursive(getDownloadsDir());
        return cleaned;
    }

    public synchronized int autoPurgeVerifiedDuplicates() {
        StagingAnalysisReport report = scanStagingAndDuplicates();
        int cleaned = 0;
        for (StagingMediaItem item : report.items) {
            if (item.isPhysicalDuplicate && item.libraryFile != null && item.libraryFile.exists() && item.libraryFile.length() == item.sizeBytes) {
                if (cleanupStagingFile(item, true)) {
                    cleaned++;
                    Log.i(TAG, "[AutoPurge] Cleaned physical duplicate: " + item.stagingFile.getName() + " (" + formatBytes(item.sizeBytes) + ")");
                }
            }
        }
        cleanupStagingJunkAndEmptyDirs(getRadarrDownloadsDir());
        cleanupStagingJunkAndEmptyDirs(getSonarrDownloadsDir());
        cleanupStagingJunkAndEmptyDirs(getTorrents());
        cleanEmptyDirsRecursive(getDownloadsDir());
        return cleaned;
    }

    public void cleanupStagingJunkAndEmptyDirs(File stagingRoot) {
        if (stagingRoot == null || !stagingRoot.exists() || !stagingRoot.isDirectory()) return;
        File[] children = stagingRoot.listFiles();
        if (children == null) return;

        List<TorrentInfo> torrents = queryTransmissionTorrents();
        Set<String> activeTorrentPaths = new HashSet<>();
        for (TorrentInfo t : torrents) {
            if (t.isActive()) {
                if (t.downloadDir != null) activeTorrentPaths.add(t.downloadDir.toLowerCase(Locale.US));
                if (t.name != null) activeTorrentPaths.add(t.name.toLowerCase(Locale.US));
            }
        }

        for (File child : children) {
            if (child.isDirectory()) {
                cleanupStagingFolderIfNoMedia(child, activeTorrentPaths);
            }
        }
    }

    private void cleanupStagingFolderIfNoMedia(File dir, Set<String> activeTorrentPaths) {
        if (dir == null || !dir.exists() || !dir.isDirectory()) return;

        File radarrRoot = getRadarrDownloadsDir();
        File sonarrRoot = getSonarrDownloadsDir();
        File downloadsRoot = getDownloadsDir();
        File torrentsRoot = getTorrents();
        File mediaRoot = getMediaRoot();
        String path = dir.getAbsolutePath();
        if (path.equals(radarrRoot.getAbsolutePath()) ||
                path.equals(sonarrRoot.getAbsolutePath()) ||
                path.equals(downloadsRoot.getAbsolutePath()) ||
                path.equals(torrentsRoot.getAbsolutePath()) ||
                path.equals(mediaRoot.getAbsolutePath())) {
            return;
        }

        File[] files = dir.listFiles();
        if (files != null) {
            for (File f : files) {
                if (f.isDirectory()) {
                    cleanupStagingFolderIfNoMedia(f, activeTorrentPaths);
                }
            }
        }

        String dirNameLower = dir.getName().toLowerCase(Locale.US);
        String dirPathLower = dir.getAbsolutePath().toLowerCase(Locale.US);
        for (String active : activeTorrentPaths) {
            if (dirPathLower.contains(active) || active.contains(dirNameLower)) {
                Log.d(TAG, "Skipping staging folder cleanup, active torrent in progress: " + dir.getName());
                return;
            }
        }

        if (!containsMediaFiles(dir)) {
            preserveSubtitlesFromOrphanFolder(dir);
            deleteDirectoryContents(dir);
            boolean deleted = dir.delete();
            Log.i(TAG, "Cleaned leftover staging junk folder: " + dir.getAbsolutePath() + " (success=" + deleted + ")");
        }
    }

    private void preserveCompanionSubtitles(File stagingFile, File libraryFile) {
        if (stagingFile == null || libraryFile == null) return;
        File stagingParent = stagingFile.getParentFile();
        File libraryParent = libraryFile.getParentFile();
        if (stagingParent == null || libraryParent == null || !stagingParent.exists() || !libraryParent.exists()) return;
        File radarrRoot = getRadarrDownloadsDir();
        File sonarrRoot = getSonarrDownloadsDir();
        if (stagingParent.equals(radarrRoot) || stagingParent.equals(sonarrRoot)) return;

        File[] files = stagingParent.listFiles();
        if (files == null) return;
        for (File f : files) {
            String nameLower = f.getName().toLowerCase(Locale.US);
            if (nameLower.endsWith(".srt") || nameLower.endsWith(".sub") || nameLower.endsWith(".vtt")) {
                File targetSub = new File(libraryParent, f.getName());
                if (!targetSub.exists()) {
                    copyFileWithChannel(f, targetSub);
                    Log.i(TAG, "Preserved companion subtitle to library: " + targetSub.getAbsolutePath());
                }
            }
        }
    }

    private void preserveSubtitlesFromOrphanFolder(File stagingDir) {
        if (stagingDir == null || !stagingDir.exists() || !stagingDir.isDirectory()) return;
        List<File> allFiles = new ArrayList<>();
        collectFilesRecursive(stagingDir, allFiles);
        List<File> subFiles = new ArrayList<>();
        for (File f : allFiles) {
            String name = f.getName().toLowerCase(Locale.US);
            if (name.endsWith(".srt") || name.endsWith(".sub") || name.endsWith(".vtt")) {
                subFiles.add(f);
            }
        }
        if (subFiles.isEmpty()) return;

        File matchedLibDir = findMatchingLibraryDir(stagingDir.getName());
        if (matchedLibDir != null && matchedLibDir.exists() && matchedLibDir.isDirectory()) {
            for (File sub : subFiles) {
                File target = new File(matchedLibDir, sub.getName());
                if (!target.exists()) {
                    copyFileWithChannel(sub, target);
                    Log.i(TAG, "Preserved companion subtitle to library: " + target.getAbsolutePath());
                }
            }
        }
    }

    private File findMatchingLibraryDir(String stagingName) {
        if (stagingName == null || stagingName.trim().isEmpty()) return null;
        String cleanName = stagingName.replaceAll("[\\[\\]\\(\\)\\._\\-]", " ").toLowerCase(Locale.US).trim();

        // Check Movies
        File moviesDir = getMoviesDir();
        File[] movieDirs = moviesDir.listFiles();
        if (movieDirs != null) {
            for (File md : movieDirs) {
                if (md.isDirectory()) {
                    if (md.getName().equalsIgnoreCase(stagingName)) return md;
                    String cleanMd = md.getName().replaceAll("[\\[\\]\\(\\)\\._\\-]", " ").toLowerCase(Locale.US).trim();
                    if (cleanName.contains(cleanMd) || cleanMd.contains(cleanName)) return md;
                }
            }
        }

        // Check TV
        File tvDir = getTvDir();
        File[] tvDirs = tvDir.listFiles();
        if (tvDirs != null) {
            for (File td : tvDirs) {
                if (td.isDirectory()) {
                    if (td.getName().equalsIgnoreCase(stagingName)) return td;
                    String cleanTd = td.getName().replaceAll("[\\[\\]\\(\\)\\._\\-]", " ").toLowerCase(Locale.US).trim();
                    if (cleanName.contains(cleanTd) || cleanTd.contains(cleanName)) return td;
                }
            }
        }
        return null;
    }

    private void cleanEmptyStagingParents(File dir, String category) {
        if (dir == null || !dir.exists() || !dir.isDirectory()) return;
        File radarrRoot = getRadarrDownloadsDir();
        File sonarrRoot = getSonarrDownloadsDir();
        File downloadsRoot = getDownloadsDir();
        File mediaRoot = getMediaRoot();

        // Do not delete radarr or sonarr root itself
        String path = dir.getAbsolutePath();
        if (path.equals(radarrRoot.getAbsolutePath()) ||
                path.equals(sonarrRoot.getAbsolutePath()) ||
                path.equals(downloadsRoot.getAbsolutePath()) ||
                path.equals(mediaRoot.getAbsolutePath())) {
            return;
        }

        // If no media files remain in this staging subdirectory, clean all junk and remove it!
        if (!containsMediaFiles(dir)) {
            preserveSubtitlesFromOrphanFolder(dir);
            deleteDirectoryContents(dir);
            boolean deleted = dir.delete();
            Log.i(TAG, "Cleaned staging parent with leftover companion files: " + path + " (success=" + deleted + ")");
            cleanEmptyStagingParents(dir.getParentFile(), category);
            return;
        }

        File[] list = dir.listFiles();
        if (list != null && list.length == 0) {
            File parent = dir.getParentFile();
            dir.delete();
            cleanEmptyStagingParents(parent, category);
        }
    }

    // =========================================================================
    // STORAGE BREAKDOWN & USAGE CALCULATION (Spec Sections 18, 19)
    // =========================================================================

    public static class StorageSizeBreakdown {
        public final long moviesBytes;
        public final long tvBytes;
        public final long downloadsBytes;
        public final long musicBytes;
        public final long booksBytes;
        public final long otherBytes;
        public final long totalLogicalBytes;
        public final long totalPhysicalBytes;
        public final Map<String, Long> directorySizes;

        public StorageSizeBreakdown(long moviesBytes, long tvBytes, long downloadsBytes,
                                    long musicBytes, long booksBytes, long otherBytes,
                                    long totalLogicalBytes, long totalPhysicalBytes,
                                    Map<String, Long> directorySizes) {
            this.moviesBytes = moviesBytes;
            this.tvBytes = tvBytes;
            this.downloadsBytes = downloadsBytes;
            this.musicBytes = musicBytes;
            this.booksBytes = booksBytes;
            this.otherBytes = otherBytes;
            this.totalLogicalBytes = totalLogicalBytes;
            this.totalPhysicalBytes = totalPhysicalBytes;
            this.directorySizes = directorySizes;
        }
    }

    public StorageSizeBreakdown calculateStorageBreakdown() {
        Map<String, Long> sizes = new LinkedHashMap<>();
        long movies = calculateDirLogicalSize(getMoviesDir());
        long tv = calculateDirLogicalSize(getTvDir());
        long downloads = calculateDirLogicalSize(getDownloadsDir());
        long music = calculateDirLogicalSize(getMusicDir());
        long books = calculateDirLogicalSize(getBooksDir());
        long other = calculateDirLogicalSize(getOtherDir());

        sizes.put("Movies", movies);
        sizes.put("TV", tv);
        sizes.put("Downloads", downloads);
        sizes.put("Music", music);
        sizes.put("Books", books);
        sizes.put("Other", other);

        long logical = movies + tv + downloads + music + books + other;
        long physical = calculatePhysicalSizeDeduplicated(getMediaRoot());

        return new StorageSizeBreakdown(movies, tv, downloads, music, books, other, logical, physical, sizes);
    }

    private long calculateDirLogicalSize(File dir) {
        if (dir == null || !dir.exists() || !dir.isDirectory()) return 0;
        List<File> files = new ArrayList<>();
        collectFilesRecursive(dir, files);
        long sum = 0;
        for (File f : files) sum += f.length();
        return sum;
    }

    private long calculatePhysicalSizeDeduplicated(File dir) {
        if (dir == null || !dir.exists() || !dir.isDirectory()) return 0;
        List<File> files = new ArrayList<>();
        collectFilesRecursive(dir, files);
        Set<String> seenInodes = new HashSet<>();
        long physicalSum = 0;

        for (File f : files) {
            try {
                StructStat stat = Os.stat(f.getAbsolutePath());
                String key = stat.st_dev + ":" + stat.st_ino;
                if (seenInodes.add(key)) {
                    physicalSum += stat.st_size;
                }
            } catch (Throwable t) {
                physicalSum += f.length();
            }
        }
        return physicalSum;
    }

    public static String formatBytes(long bytes) {
        if (bytes < 1024) return bytes + " B";
        if (bytes < 1024 * 1024) return String.format(Locale.US, "%.1f KB", bytes / 1024.0);
        if (bytes < 1024 * 1024 * 1024) return String.format(Locale.US, "%.2f MB", bytes / (1024.0 * 1024.0));
        return String.format(Locale.US, "%.2f GB", bytes / (1024.0 * 1024.0 * 1024.0));
    }

    public static class StorageDiagnosticResult {
        public final String mediaRoot;
        public final boolean rootExists;
        public final boolean rootReadable;
        public final boolean rootWritable;
        public final Map<String, Boolean> directoryStatus;
        public final boolean transmissionAccess;
        public final boolean radarrAccess;
        public final boolean sonarrAccess;
        public final boolean jellyfinAccess;
        public final String summary;

        // Extended diagnostic properties (v1.4.8-beta.1)
        public final boolean hardlinkSupported;
        public final String hardlinkReason;
        public final boolean sameFilesystem;
        public final Map<String, Long> directorySizes;
        public final long totalLogicalBytes;
        public final long totalPhysicalBytes;
        public final long potentialSavingsBytes;

        public StorageDiagnosticResult(String mediaRoot, boolean rootExists, boolean rootReadable,
                                       boolean rootWritable, Map<String, Boolean> directoryStatus,
                                       boolean transmissionAccess, boolean radarrAccess,
                                       boolean sonarrAccess, boolean jellyfinAccess, String summary) {
            this(mediaRoot, rootExists, rootReadable, rootWritable, directoryStatus,
                    transmissionAccess, radarrAccess, sonarrAccess, jellyfinAccess, summary,
                    false, "Not probed", true, Collections.emptyMap(), 0, 0, 0);
        }

        public StorageDiagnosticResult(String mediaRoot, boolean rootExists, boolean rootReadable,
                                       boolean rootWritable, Map<String, Boolean> directoryStatus,
                                       boolean transmissionAccess, boolean radarrAccess,
                                       boolean sonarrAccess, boolean jellyfinAccess, String summary,
                                       boolean hardlinkSupported, String hardlinkReason, boolean sameFilesystem,
                                       Map<String, Long> directorySizes, long totalLogicalBytes,
                                       long totalPhysicalBytes, long potentialSavingsBytes) {
            this.mediaRoot = mediaRoot;
            this.rootExists = rootExists;
            this.rootReadable = rootReadable;
            this.rootWritable = rootWritable;
            this.directoryStatus = directoryStatus;
            this.transmissionAccess = transmissionAccess;
            this.radarrAccess = radarrAccess;
            this.sonarrAccess = sonarrAccess;
            this.jellyfinAccess = jellyfinAccess;
            this.summary = summary;
            this.hardlinkSupported = hardlinkSupported;
            this.hardlinkReason = hardlinkReason;
            this.sameFilesystem = sameFilesystem;
            this.directorySizes = directorySizes;
            this.totalLogicalBytes = totalLogicalBytes;
            this.totalPhysicalBytes = totalPhysicalBytes;
            this.potentialSavingsBytes = potentialSavingsBytes;
        }
    }

    /**
     * Executes comprehensive storage diagnostics:
     * - Media Root exists, readable, writable
     * - All canonical subdirectories
     * - Hardlink support probe on filesystem
     * - Service access validation
     * - Storage breakdown (Logical vs Physical)
     */
    public StorageDiagnosticResult runDiagnostics() {
        File root = getMediaRoot();
        boolean rExists = root.exists() && root.isDirectory();
        boolean rReadable = rExists && root.canRead();
        boolean rWritable = testProbeWritable(root);

        Map<String, Boolean> dirStatus = new LinkedHashMap<>();
        for (String sub : REQUIRED_SUBDIRECTORIES) {
            File d = new File(root, sub);
            boolean ok = d.exists() && d.isDirectory() && testProbeWritable(d);
            dirStatus.put(sub, ok);
        }

        // Test service access paths
        File torrentsDir = new File(root, SUB_DOWNLOADS_TORRENTS);
        File radarrDlDir = new File(root, SUB_DOWNLOADS_RADARR);
        File sonarrDlDir = new File(root, SUB_DOWNLOADS_SONARR);
        File moviesDir = new File(root, SUB_MOVIES);
        File tvDir = new File(root, SUB_TV);
        File musicDir = new File(root, SUB_MUSIC);

        boolean transOk = torrentsDir.exists() && torrentsDir.canRead() && testProbeWritable(torrentsDir);
        boolean radarrOk = moviesDir.exists() && radarrDlDir.exists() && testProbeWritable(moviesDir);
        boolean sonarrOk = tvDir.exists() && sonarrDlDir.exists() && testProbeWritable(tvDir);
        boolean jfOk = moviesDir.exists() && tvDir.exists() && musicDir.exists() && moviesDir.canRead();

        // Hardlink probe
        HardlinkProbeResult hlProbe = probeHardlinkSupport();

        // Storage size breakdown
        StorageSizeBreakdown breakdown = calculateStorageBreakdown();
        long savings = Math.max(0, breakdown.totalLogicalBytes - breakdown.totalPhysicalBytes);

        StringBuilder sb = new StringBuilder();
        sb.append("MEDIA STORAGE\n\n");
        sb.append("Root: ").append(root.getAbsolutePath()).append("\n\n");
        sb.append("Status:\n");
        sb.append(rExists ? "  [OK] Exists\n" : "  [FAIL] Exists (Missing)\n");
        sb.append(rReadable ? "  [OK] Readable\n" : "  [FAIL] Readable (Permission denied)\n");
        sb.append(rWritable ? "  [OK] Writable\n\n" : "  [FAIL] Writable (Write probe failed)\n\n");

        sb.append("Filesystem & Hardlinks:\n");
        sb.append("  ").append(hlProbe.sameFilesystem ? "[OK]" : "[FAIL]").append(" Same filesystem\n");
        sb.append("  ").append(hlProbe.supported ? "[OK]" : "[FAIL]").append(" Hardlink support: ")
                .append(hlProbe.reason).append("\n\n");

        sb.append("Storage Usage:\n");
        for (Map.Entry<String, Long> e : breakdown.directorySizes.entrySet()) {
            sb.append("  ").append(String.format(Locale.US, "%-12s", e.getKey())).append(": ")
                    .append(formatBytes(e.getValue())).append("\n");
        }
        sb.append("  --------------------\n");
        sb.append("  Logical size   : ").append(formatBytes(breakdown.totalLogicalBytes)).append("\n");
        sb.append("  Physical size  : ").append(formatBytes(breakdown.totalPhysicalBytes)).append("\n");
        if (savings > 0) {
            sb.append("  Hardlink savings: ").append(formatBytes(savings)).append("\n");
        }
        sb.append("\n");

        sb.append("Service Access:\n");
        sb.append(transOk ? "  [OK] Transmission access\n" : "  [FAIL] Transmission access\n");
        sb.append(radarrOk ? "  [OK] Radarr access\n" : "  [FAIL] Radarr access\n");
        sb.append(sonarrOk ? "  [OK] Sonarr access\n" : "  [FAIL] Sonarr access\n");
        sb.append(jfOk ? "  [OK] Jellyfin access\n" : "  [FAIL] Jellyfin access\n");

        return new StorageDiagnosticResult(
                root.getAbsolutePath(),
                rExists,
                rReadable,
                rWritable,
                dirStatus,
                transOk,
                radarrOk,
                sonarrOk,
                jfOk,
                sb.toString(),
                hlProbe.supported,
                hlProbe.reason,
                hlProbe.sameFilesystem,
                breakdown.directorySizes,
                breakdown.totalLogicalBytes,
                breakdown.totalPhysicalBytes,
                savings
        );
    }

    private boolean testProbeWritable(File dir) {
        if (dir == null || !dir.exists() || !dir.isDirectory()) return false;
        File probe = new File(dir, ".fishbowl_probe_" + System.currentTimeMillis());
        try {
            boolean created = probe.createNewFile();
            if (created) {
                probe.delete();
                return true;
            }
            return probe.exists() && probe.delete();
        } catch (Throwable t) {
            return false;
        }
    }

    // =========================================================================
    // EXTERNAL STORAGE DETECTION & ANTI-CORRUPTION SAFEGUARDS
    // =========================================================================

    /**
     * Retrieves all detected external (removable/OTG/SD) storage drives that are currently mounted.
     * Returns an empty list if no external drives are connected.
     */
    public static List<StorageDriveHelper.DriveInfo> getConnectedExternalDrives(Context context) {
        List<StorageDriveHelper.DriveInfo> externalList = new ArrayList<>();
        if (context == null) return externalList;
        try {
            List<StorageDriveHelper.DriveInfo> allDrives = StorageDriveHelper.getMountedDrives(context);
            if (allDrives != null) {
                for (StorageDriveHelper.DriveInfo drive : allDrives) {
                    if (drive == null) continue;
                    // Filter: must be non-primary (removable SD card or USB OTG) and currently mounted
                    if (!drive.isPrimary && (drive.isRemovable || !drive.rootPath.startsWith("/storage/emulated/"))) {
                        if (Environment.MEDIA_MOUNTED.equalsIgnoreCase(drive.state) ||
                                Environment.MEDIA_MOUNTED_READ_ONLY.equalsIgnoreCase(drive.state) ||
                                "mounted".equalsIgnoreCase(drive.state)) {
                            externalList.add(drive);
                        }
                    }
                }
            }
        } catch (Throwable t) {
            Log.w(TAG, "Failed to query connected external storage: " + t.getMessage());
        }
        return externalList;
    }

    /**
     * Checks if a given path resides on a removable external drive.
     */
    public static boolean isPathOnExternalStorage(String path) {
        if (path == null) return false;
        String normalized = StorageDriveHelper.normalizeStoragePath(path);
        return normalized.startsWith("/storage/") && !normalized.startsWith("/storage/emulated/");
    }

    /**
     * Flushes and synchronizes all modified in-core memory buffers to physical underlying flash storage.
     * Invokes POSIX sync() via android.system.Os to safeguard external USB drives and SD cards
     * against filesystem corruption prior to unmounting or idle state.
     */
    public static boolean flushAndSyncStorage() {
        try {
            Process p = Runtime.getRuntime().exec("sync");
            p.waitFor();
            Log.i(TAG, "Storage flush and sync completed successfully.");
            return true;
        } catch (Throwable t) {
            Log.w(TAG, "Failed to execute sync: " + t.getMessage());
            return false;
        }
    }

    /**
     * Writes binary data atomically to disk with explicit sync to prevent file or directory corruption.
     * Writes into a temporary file in the same directory, syncs the file descriptor, and performs an
     * atomic filesystem rename. If an interruption occurs, the destination file remains uncorrupted.
     */
    public static boolean safeAtomicWrite(File destFile, byte[] data) {
        if (destFile == null || data == null) return false;
        File parent = destFile.getParentFile();
        if (parent != null && !parent.exists()) {
            parent.mkdirs();
        }
        File tmpFile = new File(destFile.getAbsolutePath() + ".fb_tmp_" + System.currentTimeMillis());
        FileOutputStream fos = null;
        try {
            fos = new FileOutputStream(tmpFile);
            fos.write(data);
            fos.flush();
            try {
                fos.getFD().sync();
            } catch (Throwable ignored) {}
            fos.close();
            fos = null;

            // Atomic rename to target
            if (destFile.exists()) {
                destFile.delete();
            }
            boolean success = tmpFile.renameTo(destFile);
            if (!success) {
                // Fallback copy if cross-filesystem rename fails
                try (InputStream in = new FileInputStream(tmpFile);
                     OutputStream out = new FileOutputStream(destFile)) {
                    byte[] buf = new byte[8192];
                    int len;
                    while ((len = in.read(buf)) > 0) {
                        out.write(buf, 0, len);
                    }
                    out.flush();
                }
                tmpFile.delete();
                return true;
            }
            return success;
        } catch (Throwable t) {
            Log.e(TAG, "safeAtomicWrite error for " + destFile.getAbsolutePath() + ": " + t.getMessage(), t);
            if (tmpFile.exists()) {
                tmpFile.delete();
            }
            return false;
        } finally {
            if (fos != null) {
                try { fos.close(); } catch (Throwable ignored) {}
            }
        }
    }
}
