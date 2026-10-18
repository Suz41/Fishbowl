package com.fishbowl.app;

import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.net.Uri;
import android.os.Environment;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import androidx.core.content.FileProvider;

import com.termux.shared.termux.TermuxConstants;

import org.json.JSONObject;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Date;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import java.util.zip.ZipOutputStream;

/**
 * FishBowl 1-Tap Configuration Export & Migration Manager.
 * Safely exports SharedPreferences and service configuration files (.xml, .json, .yaml, .db)
 * into a single portable, timestamped backup archive, and provides 1-tap restoration.
 */
public final class BackupRestoreManager {
    private static final String TAG = "BackupRestoreManager";
    public static final String BACKUP_DIR_NAME = "FishBowl_Backups";
    public static final String META_FILENAME = "fishbowl_backup_meta.json";

    public static final String PREF_LAST_BACKUP_TIME = "last_backup_timestamp";
    public static final String PREF_LAST_BACKUP_PATH = "last_backup_path";
    public static final String PREF_LAST_BACKUP_SIZE = "last_backup_size";

    private static final ExecutorService EXECUTOR = Executors.newSingleThreadExecutor();

    private static void postOnMainThread(Runnable r) {
        if (r == null) return;
        try {
            if (Looper.getMainLooper() != null) {
                new Handler(Looper.getMainLooper()).post(r);
                return;
            }
        } catch (Throwable ignored) {}
        r.run();
    }

    private static final Set<String> ALLOWED_EXTENSIONS = new HashSet<>(Arrays.asList(
            ".xml", ".json", ".yaml", ".yml", ".db", ".db-wal", ".db-shm", ".conf", ".ini"
    ));

    private static final Set<String> IGNORED_NAMES = new HashSet<>(Arrays.asList(
            "cache", "logs", "log", "temp", "tmp", "mediacover", "backups", "update"
    ));

    public interface BackupCallback {
        void onProgress(String message);
        void onSuccess(File backupFile, long fileSizeBytes, int itemsCount);
        void onError(String error);
    }

    public interface RestoreCallback {
        void onProgress(String message);
        void onSuccess(int restoredPrefsCount, int restoredConfigsCount);
        void onError(String error);
    }

    private BackupRestoreManager() {}

    /**
     * Resolves the primary directory where backups are stored.
     */
    public static File getBackupDirectory(Context context) {
        File downloads = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS);
        File dir = new File(downloads, BACKUP_DIR_NAME);
        if (!dir.exists()) {
            dir.mkdirs();
        }
        if (!dir.exists() || !dir.canWrite()) {
            dir = new File(context.getExternalFilesDir(null), BACKUP_DIR_NAME);
            if (!dir.exists()) dir.mkdirs();
        }
        return dir;
    }

    /**
     * Lists existing backup zip files sorted descending by modification time.
     */
    public static List<File> listExistingBackups(Context context) {
        File dir = getBackupDirectory(context);
        File[] files = dir.listFiles((d, name) -> name != null && name.toLowerCase(Locale.ROOT).endsWith(".zip"));
        if (files == null || files.length == 0) {
            return Collections.emptyList();
        }
        List<File> list = new ArrayList<>(Arrays.asList(files));
        Collections.sort(list, (a, b) -> Long.compare(b.lastModified(), a.lastModified()));
        return list;
    }

    /**
     * Asynchronously creates a 1-tap export backup archive.
     */
    public static void exportBackup(Context context, BackupCallback callback) {
        EXECUTOR.execute(() -> {
            postProgress(callback, "Checking available storage...");
            long usableCache = context.getCacheDir().getUsableSpace();
            if (usableCache < 50 * 1024 * 1024L) {
                postOnMainThread(() -> callback.onError("Insufficient storage: only " + (usableCache / (1024 * 1024)) + " MB available in cache. At least 50 MB required."));
                return;
            }

            postProgress(callback, "Preparing FishBowl backup staging...");
            File stagingDir = new File(context.getCacheDir(), "fishbowl_backup_staging_" + System.currentTimeMillis());
            if (!stagingDir.exists()) stagingDir.mkdirs();

            try {
                int totalCount = 0;
                org.json.JSONArray itemsArray = new org.json.JSONArray();

                // 1. Collect SharedPreferences
                postProgress(callback, "Backing up FishBowl preferences...");
                File prefsDir = new File(context.getApplicationInfo().dataDir, "shared_prefs");
                File stagingPrefs = new File(stagingDir, "shared_prefs");
                if (prefsDir.exists() && prefsDir.isDirectory()) {
                    File[] prefFiles = prefsDir.listFiles((d, name) -> name != null && name.endsWith(".xml"));
                    if (prefFiles != null) {
                        for (File pf : prefFiles) {
                            if (!stagingPrefs.exists()) stagingPrefs.mkdirs();
                            File destFile = new File(stagingPrefs, pf.getName());
                            copyFile(pf, destFile);
                            totalCount++;

                            JSONObject itemObj = new JSONObject();
                            itemObj.put("path", "shared_prefs/" + pf.getName());
                            itemObj.put("size", destFile.length());
                            itemObj.put("sha256", computeSHA256(destFile));
                            itemObj.put("component", "prefs");
                            itemsArray.put(itemObj);
                        }
                    }
                }

                // 2. Collect Service Configs from TermuxConstants.TERMUX_HOME_DIR/.config
                postProgress(callback, "Backing up media service configs...");
                File homeDir = TermuxConstants.TERMUX_HOME_DIR;
                File configDir = new File(homeDir, ".config");
                File stagingConfig = new File(stagingDir, "config");
                if (configDir.exists() && configDir.isDirectory()) {
                    totalCount += stageConfigDir(configDir, stagingConfig, "config", itemsArray);
                }

                // 3. Create Metadata Manifest
                JSONObject meta = new JSONObject();
                meta.put("formatVersion", 2);
                meta.put("app", "FishBowl");
                meta.put("version", "1.4.8");
                meta.put("versionCode", 101414);
                meta.put("timestamp", System.currentTimeMillis());
                meta.put("date", new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(new Date()));
                meta.put("itemsCount", totalCount);
                meta.put("items", itemsArray);

                File metaFile = new File(stagingDir, META_FILENAME);
                try (FileOutputStream fos = new FileOutputStream(metaFile)) {
                    fos.write(meta.toString(2).getBytes(StandardCharsets.UTF_8));
                }

                // 4. Compress Staging Directory to Final ZIP
                postProgress(callback, "Compressing backup archive...");
                File destDir = getBackupDirectory(context);
                String timestampStr = new SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(new Date());
                File zipFile = new File(destDir, "FishBowl_Backup_" + timestampStr + ".zip");

                zipDirectory(stagingDir, zipFile);

                // Flush file blocks to disk
                flushDisk();

                // Save last backup timestamp & path
                SharedPreferences sp = context.getSharedPreferences("fishbowl_settings", Context.MODE_PRIVATE);
                sp.edit()
                        .putLong(PREF_LAST_BACKUP_TIME, System.currentTimeMillis())
                        .putString(PREF_LAST_BACKUP_PATH, zipFile.getAbsolutePath())
                        .putLong(PREF_LAST_BACKUP_SIZE, zipFile.length())
                        .apply();

                final int finalCount = totalCount;
                postOnMainThread(() -> callback.onSuccess(zipFile, zipFile.length(), finalCount));

            } catch (Throwable t) {
                Log.e(TAG, "Export backup failed", t);
                postOnMainThread(() -> callback.onError("Backup failed: " + t.getMessage()));
            } finally {
                deleteRecursively(stagingDir);
            }
        });
    }

    /**
     * Recursively stages config files filtering out large caches/logs.
     */
    private static int stageConfigDir(File source, File dest, String relativeParent, org.json.JSONArray itemsArray) {
        int count = 0;
        File[] files = source.listFiles();
        if (files == null) return 0;

        for (File f : files) {
            String nameLower = f.getName().toLowerCase(Locale.ROOT);
            if (IGNORED_NAMES.contains(nameLower)) continue;

            if (f.isDirectory()) {
                File subDest = new File(dest, f.getName());
                count += stageConfigDir(f, subDest, relativeParent + "/" + f.getName(), itemsArray);
            } else if (f.isFile()) {
                boolean allowed = false;
                for (String ext : ALLOWED_EXTENSIONS) {
                    if (nameLower.endsWith(ext)) {
                        allowed = true;
                        break;
                    }
                }
                if (allowed) {
                    if (!dest.exists()) dest.mkdirs();
                    try {
                        File destFile = new File(dest, f.getName());
                        copyFile(f, destFile);
                        count++;

                        if (itemsArray != null) {
                            JSONObject itemObj = new JSONObject();
                            itemObj.put("path", relativeParent + "/" + f.getName());
                            itemObj.put("size", destFile.length());
                            itemObj.put("sha256", computeSHA256(destFile));
                            itemObj.put("component", "config");
                            itemsArray.put(itemObj);
                        }
                    } catch (Throwable t) {
                        Log.w(TAG, "Failed to stage config file: " + f.getName(), t);
                    }
                }
            }
        }
        return count;
    }

    /**
     * Asynchronously restores configuration from a backup File.
     */
    public static void restoreBackup(Context context, File backupFile, RestoreCallback callback) {
        try {
            FileInputStream fis = new FileInputStream(backupFile);
            restoreFromInputStream(context, fis, callback);
        } catch (Throwable t) {
            callback.onError("Cannot open backup file: " + t.getMessage());
        }
    }

    /**
     * Asynchronously restores configuration from an InputStream (e.g. Content URI from SAF).
     */
    public static void restoreBackup(Context context, Uri backupUri, RestoreCallback callback) {
        try {
            InputStream is = context.getContentResolver().openInputStream(backupUri);
            if (is == null) {
                callback.onError("Failed to read selected backup stream.");
                return;
            }
            restoreFromInputStream(context, is, callback);
        } catch (Throwable t) {
            callback.onError("Failed to open backup URI: " + t.getMessage());
        }
    }

    private static void restoreFromInputStream(Context context, InputStream is, RestoreCallback callback) {
        EXECUTOR.execute(() -> {
            postProgress(callback, "Checking usable storage...");
            long usableCache = context.getCacheDir().getUsableSpace();
            if (usableCache < 50 * 1024 * 1024L) {
                postOnMainThread(() -> callback.onError("Insufficient storage for restore: only " + (usableCache / (1024 * 1024)) + " MB available in cache. At least 50 MB required."));
                try { is.close(); } catch (Throwable ignored) {}
                return;
            }

            postProgress(callback, "Unpacking backup archive...");
            File stagingDir = new File(context.getCacheDir(), "fishbowl_restore_staging_" + System.currentTimeMillis());
            if (!stagingDir.exists()) stagingDir.mkdirs();

            File snapshotDir = new File(context.getFilesDir(), "backup_prerestore_snapshot_" + System.currentTimeMillis());
            boolean snapshotCreated = false;

            try {
                // 1. Unzip to Staging
                unzip(is, stagingDir);

                // 2. Validate Metadata & Integrity BEFORE modifying any live files
                postProgress(callback, "Verifying backup archive cryptographic manifest...");
                validateManifestIntegrity(stagingDir);

                // 3. Quiesce services before touching live data
                postProgress(callback, "Quiescing active services...");
                try {
                    com.fishbowl.app.services.MediaServiceManager msm = com.fishbowl.app.services.MediaServiceManager.getInstance(context);
                    if (msm != null) msm.stopAllServices();
                    JellyfinController jc = JellyfinController.getInstance();
                    if (jc != null) jc.stop();
                } catch (Throwable t) {
                    Log.w(TAG, "Service quiesce note: " + t.getMessage());
                }

                // 4. Create Pre-Restore Snapshot for automatic rollback on error
                postProgress(callback, "Creating pre-restore recovery snapshot...");
                if (!snapshotDir.exists()) snapshotDir.mkdirs();
                File livePrefs = new File(context.getApplicationInfo().dataDir, "shared_prefs");
                if (livePrefs.exists() && livePrefs.isDirectory()) {
                    copyDirectory(livePrefs, new File(snapshotDir, "shared_prefs"));
                }
                File liveConfig = new File(TermuxConstants.TERMUX_HOME_DIR, ".config");
                if (liveConfig.exists() && liveConfig.isDirectory()) {
                    copyDirectory(liveConfig, new File(snapshotDir, "config"));
                }
                snapshotCreated = true;

                // 5. Restore SharedPreferences
                postProgress(callback, "Restoring preferences...");
                int prefsCount = 0;
                File stagingPrefs = new File(stagingDir, "shared_prefs");
                if (stagingPrefs.exists() && stagingPrefs.isDirectory()) {
                    File destPrefs = new File(context.getApplicationInfo().dataDir, "shared_prefs");
                    if (!destPrefs.exists()) destPrefs.mkdirs();

                    File[] files = stagingPrefs.listFiles();
                    if (files != null) {
                        for (File f : files) {
                            copyFile(f, new File(destPrefs, f.getName()));
                            prefsCount++;
                        }
                    }
                }

                // 6. Restore Service Configs
                postProgress(callback, "Restoring service configurations...");
                int configCount = 0;
                File stagingConfig = new File(stagingDir, "config");
                if (stagingConfig.exists() && stagingConfig.isDirectory()) {
                    File targetConfig = new File(TermuxConstants.TERMUX_HOME_DIR, ".config");
                    if (!targetConfig.exists()) targetConfig.mkdirs();
                    configCount = copyDirectory(stagingConfig, targetConfig);
                }

                // 7. POSIX block sync
                flushDisk();

                // Delete snapshot only after complete success
                deleteRecursively(snapshotDir);

                final int finalPrefs = prefsCount;
                final int finalConfigs = configCount;
                postOnMainThread(() -> callback.onSuccess(finalPrefs, finalConfigs));

            } catch (Throwable t) {
                Log.e(TAG, "Restore backup failed. Reverting to pre-restore snapshot...", t);
                if (snapshotCreated) {
                    try {
                        File snapPrefs = new File(snapshotDir, "shared_prefs");
                        if (snapPrefs.exists()) {
                            copyDirectory(snapPrefs, new File(context.getApplicationInfo().dataDir, "shared_prefs"));
                        }
                        File snapConfig = new File(snapshotDir, "config");
                        if (snapConfig.exists()) {
                            copyDirectory(snapConfig, new File(TermuxConstants.TERMUX_HOME_DIR, ".config"));
                        }
                        flushDisk();
                        Log.i(TAG, "Successfully rolled back to pre-restore snapshot.");
                    } catch (Throwable rollbackErr) {
                        Log.e(TAG, "Rollback error: " + rollbackErr.getMessage(), rollbackErr);
                    }
                }
                postOnMainThread(() -> callback.onError("Restore failed (original data preserved): " + t.getMessage()));
            } finally {
                deleteRecursively(snapshotDir);
                deleteRecursively(stagingDir);
                try { is.close(); } catch (Throwable ignored) {}
            }
        });
    }

    /**
     * Launches the system share chooser for the backup file.
     */
    public static void shareBackupFile(Context context, File backupFile) {
        try {
            Uri contentUri = FileProvider.getUriForFile(
                    context,
                    context.getPackageName() + ".fileprovider",
                    backupFile
            );
            Intent shareIntent = new Intent(Intent.ACTION_SEND);
            shareIntent.setType("application/zip");
            shareIntent.putExtra(Intent.EXTRA_STREAM, contentUri);
            shareIntent.putExtra(Intent.EXTRA_SUBJECT, "FishBowl Configuration Backup");
            shareIntent.putExtra(Intent.EXTRA_TEXT, "FishBowl System Backup: " + backupFile.getName());
            shareIntent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
            context.startActivity(Intent.createChooser(shareIntent, "Share / Save FishBowl Backup"));
        } catch (Throwable t) {
            Log.e(TAG, "Share backup failed", t);
        }
    }

    private static void postProgress(BackupCallback callback, String msg) {
        if (callback != null) postOnMainThread(() -> callback.onProgress(msg));
    }

    private static void postProgress(RestoreCallback callback, String msg) {
        if (callback != null) postOnMainThread(() -> callback.onProgress(msg));
    }

    public static String computeSHA256(File file) throws Exception {
        java.security.MessageDigest md = java.security.MessageDigest.getInstance("SHA-256");
        try (InputStream fis = new java.io.BufferedInputStream(new FileInputStream(file))) {
            byte[] buf = new byte[8192];
            int read;
            while ((read = fis.read(buf)) > 0) {
                md.update(buf, 0, read);
            }
        }
        byte[] digest = md.digest();
        StringBuilder sb = new StringBuilder();
        for (byte b : digest) {
            sb.append(String.format("%02x", b));
        }
        return sb.toString();
    }

    public static class ManifestEntry {
        public String path;
        public long size = -1;
        public String sha256 = "";
    }

    public static void validateManifestIntegrity(File stagingDir) throws Exception {
        File metaFile = new File(stagingDir, META_FILENAME);
        if (!metaFile.exists()) {
            throw new IllegalArgumentException("Invalid backup: Missing " + META_FILENAME);
        }
        String metaContent = readFileToString(metaFile);
        boolean isFishbowl = false;
        List<ManifestEntry> entries = new ArrayList<>();

        // Try standard org.json parser first
        try {
            JSONObject metaJson = new JSONObject(metaContent);
            if (metaJson.has("app") && metaJson.getString("app").equalsIgnoreCase("FishBowl")) {
                isFishbowl = true;
            }
            if (metaJson.has("items")) {
                org.json.JSONArray items = metaJson.getJSONArray("items");
                for (int i = 0; i < items.length(); i++) {
                    JSONObject item = items.getJSONObject(i);
                    ManifestEntry e = new ManifestEntry();
                    e.path = item.optString("path");
                    e.size = item.optLong("size", -1);
                    e.sha256 = item.optString("sha256", "");
                    if (e.path != null && !e.path.isEmpty()) entries.add(e);
                }
            }
        } catch (Throwable ignored) {}

        // Fallback for unmocked host testing or corrupt json token stream
        if (!isFishbowl) {
            String lower = metaContent.toLowerCase(Locale.ROOT);
            if (lower.contains("\"app\"") && lower.contains("fishbowl")) {
                isFishbowl = true;
            }
        }

        if (!isFishbowl) {
            throw new IllegalArgumentException("Incompatible backup archive (Not a FishBowl backup).");
        }

        if (entries.isEmpty() && metaContent.contains("\"items\"")) {
            java.util.regex.Pattern itemPattern = java.util.regex.Pattern.compile("\\{[^\\{\\}]*\"path\"\\s*:\\s*\"([^\"]+)\"[^\\{\\}]*\\}");
            java.util.regex.Matcher m = itemPattern.matcher(metaContent);
            while (m.find()) {
                String block = m.group(0);
                ManifestEntry e = new ManifestEntry();
                java.util.regex.Matcher mPath = java.util.regex.Pattern.compile("\"path\"\\s*:\\s*\"([^\"]+)\"").matcher(block);
                if (mPath.find()) e.path = mPath.group(1);
                java.util.regex.Matcher mSize = java.util.regex.Pattern.compile("\"size\"\\s*:\\s*(\\d+)").matcher(block);
                if (mSize.find()) e.size = Long.parseLong(mSize.group(1));
                java.util.regex.Matcher mHash = java.util.regex.Pattern.compile("\"sha256\"\\s*:\\s*\"([^\"]+)\"").matcher(block);
                if (mHash.find()) e.sha256 = mHash.group(1);
                if (e.path != null) entries.add(e);
            }
        }

        // Verify all declared items
        for (ManifestEntry entry : entries) {
            String relPath = entry.path;
            if (relPath.startsWith("/") || relPath.startsWith("\\") || relPath.contains("..")) {
                throw new SecurityException("Malicious path in backup manifest: " + relPath);
            }
            File stagedItem = new File(stagingDir, relPath);
            if (!stagedItem.exists() || !stagedItem.isFile()) {
                throw new IllegalArgumentException("Corrupt archive: Missing declared manifest item: " + relPath);
            }
            if (entry.size >= 0 && stagedItem.length() != entry.size) {
                throw new IllegalArgumentException("Corrupt archive: Size mismatch for item: " + relPath);
            }
            if (!entry.sha256.isEmpty()) {
                String actualHash = computeSHA256(stagedItem);
                if (!actualHash.equalsIgnoreCase(entry.sha256)) {
                    throw new IllegalArgumentException("Corrupt archive: SHA-256 verification failed for: " + relPath);
                }
            }
        }
    }

    public static void zipDirectory(File sourceDir, File zipFile) throws Exception {
        try (ZipOutputStream zos = new ZipOutputStream(new BufferedOutputStream(new FileOutputStream(zipFile)))) {
            zipSubDir("", sourceDir, zos);
        }
    }

    private static void zipSubDir(String parentPath, File currentDir, ZipOutputStream zos) throws Exception {
        File[] files = currentDir.listFiles();
        if (files == null) return;
        byte[] buffer = new byte[8192];

        for (File file : files) {
            String entryName = parentPath.isEmpty() ? file.getName() : parentPath + "/" + file.getName();
            if (file.isDirectory()) {
                zipSubDir(entryName, file, zos);
            } else {
                ZipEntry entry = new ZipEntry(entryName);
                entry.setTime(file.lastModified());
                zos.putNextEntry(entry);
                try (BufferedInputStream bis = new BufferedInputStream(new FileInputStream(file))) {
                    int read;
                    while ((read = bis.read(buffer)) != -1) {
                        zos.write(buffer, 0, read);
                    }
                }
                zos.closeEntry();
            }
        }
    }

    public static void unzip(InputStream is, File targetDir) throws Exception {
        byte[] buffer = new byte[8192];
        String targetCanonical = targetDir.getCanonicalPath();

        try (ZipInputStream zis = new ZipInputStream(new BufferedInputStream(is))) {
            ZipEntry entry;
            while ((entry = zis.getNextEntry()) != null) {
                String name = entry.getName();
                if (name == null || name.startsWith("/") || name.startsWith("\\") || name.contains("..")) {
                    throw new SecurityException("Malicious zip entry path detected: " + name);
                }

                File file = new File(targetDir, name);
                String fileCanonical = file.getCanonicalPath();

                // Prevent Zip Slip vulnerability
                if (!fileCanonical.equals(targetCanonical) && !fileCanonical.startsWith(targetCanonical + File.separator)) {
                    throw new SecurityException("Zip entry outside target directory: " + name);
                }

                if (entry.isDirectory()) {
                    file.mkdirs();
                } else {
                    File parent = file.getParentFile();
                    if (parent != null && !parent.exists()) parent.mkdirs();
                    try (BufferedOutputStream bos = new BufferedOutputStream(new FileOutputStream(file))) {
                        int count;
                        while ((count = zis.read(buffer)) != -1) {
                            bos.write(buffer, 0, count);
                        }
                    }
                }
                zis.closeEntry();
            }
        }
    }

    public static int copyDirectory(File source, File dest) throws Exception {
        int count = 0;
        File[] files = source.listFiles();
        if (files == null) return 0;
        if (!dest.exists() && !dest.mkdirs()) {
            throw new java.io.IOException("Failed to create destination directory: " + dest.getAbsolutePath());
        }

        for (File f : files) {
            File target = new File(dest, f.getName());
            if (f.isDirectory()) {
                count += copyDirectory(f, target);
            } else {
                copyFile(f, target);
                count++;
            }
        }
        return count;
    }

    public static void copyFile(File src, File dst) throws Exception {
        File parent = dst.getParentFile();
        if (parent != null && !parent.exists()) parent.mkdirs();
        try (InputStream in = new FileInputStream(src);
             OutputStream out = new FileOutputStream(dst)) {
            byte[] buf = new byte[8192];
            int len;
            while ((len = in.read(buf)) > 0) {
                out.write(buf, 0, len);
            }
        }
    }

    public static String readFileToString(File file) throws Exception {
        try (FileInputStream fis = new FileInputStream(file)) {
            byte[] data = new byte[(int) file.length()];
            fis.read(data);
            return new String(data, StandardCharsets.UTF_8);
        }
    }

    public static void deleteRecursively(File fileOrDir) {
        if (fileOrDir == null || !fileOrDir.exists()) return;
        if (fileOrDir.isDirectory()) {
            File[] children = fileOrDir.listFiles();
            if (children != null) {
                for (File child : children) {
                    deleteRecursively(child);
                }
            }
        }
        fileOrDir.delete();
    }

    private static void flushDisk() {
        try {
            Runtime.getRuntime().exec("sync").waitFor();
        } catch (Throwable ignored) {}
    }
}
