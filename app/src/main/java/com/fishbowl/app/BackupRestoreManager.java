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
    private static final Handler MAIN_HANDLER = new Handler(Looper.getMainLooper());

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
            postProgress(callback, "Preparing FishBowl backup staging...");
            File stagingDir = new File(context.getCacheDir(), "fishbowl_backup_staging_" + System.currentTimeMillis());
            if (!stagingDir.exists()) stagingDir.mkdirs();

            try {
                int totalCount = 0;

                // 1. Collect SharedPreferences
                postProgress(callback, "Backing up FishBowl preferences...");
                File prefsDir = new File(context.getApplicationInfo().dataDir, "shared_prefs");
                File stagingPrefs = new File(stagingDir, "shared_prefs");
                if (prefsDir.exists() && prefsDir.isDirectory()) {
                    File[] prefFiles = prefsDir.listFiles((d, name) -> name != null && name.endsWith(".xml"));
                    if (prefFiles != null) {
                        for (File pf : prefFiles) {
                            if (!stagingPrefs.exists()) stagingPrefs.mkdirs();
                            copyFile(pf, new File(stagingPrefs, pf.getName()));
                            totalCount++;
                        }
                    }
                }

                // 2. Collect Service Configs from TermuxConstants.TERMUX_HOME_DIR/.config
                postProgress(callback, "Backing up media service configs...");
                File homeDir = TermuxConstants.TERMUX_HOME_DIR;
                File configDir = new File(homeDir, ".config");
                File stagingConfig = new File(stagingDir, "config");
                if (configDir.exists() && configDir.isDirectory()) {
                    totalCount += stageConfigDir(configDir, stagingConfig);
                }

                // 3. Create Metadata Manifest
                JSONObject meta = new JSONObject();
                meta.put("app", "FishBowl");
                meta.put("version", "1.4.8-beta.2");
                meta.put("versionCode", 101413);
                meta.put("timestamp", System.currentTimeMillis());
                meta.put("date", new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(new Date()));
                meta.put("itemsCount", totalCount);

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
                MAIN_HANDLER.post(() -> callback.onSuccess(zipFile, zipFile.length(), finalCount));

            } catch (Throwable t) {
                Log.e(TAG, "Export backup failed", t);
                MAIN_HANDLER.post(() -> callback.onError("Backup failed: " + t.getMessage()));
            } finally {
                deleteRecursively(stagingDir);
            }
        });
    }

    /**
     * Recursively stages config files filtering out large caches/logs.
     */
    private static int stageConfigDir(File source, File dest) {
        int count = 0;
        File[] files = source.listFiles();
        if (files == null) return 0;

        for (File f : files) {
            String nameLower = f.getName().toLowerCase(Locale.ROOT);
            if (IGNORED_NAMES.contains(nameLower)) continue;

            if (f.isDirectory()) {
                File subDest = new File(dest, f.getName());
                count += stageConfigDir(f, subDest);
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
                        copyFile(f, new File(dest, f.getName()));
                        count++;
                    } catch (Throwable ignored) {}
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
            postProgress(callback, "Unpacking backup archive...");
            File stagingDir = new File(context.getCacheDir(), "fishbowl_restore_staging_" + System.currentTimeMillis());
            if (!stagingDir.exists()) stagingDir.mkdirs();

            try {
                // 1. Unzip to Staging
                unzip(is, stagingDir);

                // 2. Validate Metadata
                File metaFile = new File(stagingDir, META_FILENAME);
                if (!metaFile.exists()) {
                    throw new IllegalArgumentException("Invalid backup: Missing " + META_FILENAME);
                }
                String metaContent = readFileToString(metaFile);
                JSONObject metaJson = new JSONObject(metaContent);
                if (!metaJson.has("app") || !metaJson.getString("app").equalsIgnoreCase("FishBowl")) {
                    throw new IllegalArgumentException("Incompatible backup archive (Not a FishBowl backup).");
                }

                // 3. Restore SharedPreferences
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

                // 4. Restore Service Configs
                postProgress(callback, "Restoring service configurations...");
                int configCount = 0;
                File stagingConfig = new File(stagingDir, "config");
                if (stagingConfig.exists() && stagingConfig.isDirectory()) {
                    File targetConfig = new File(TermuxConstants.TERMUX_HOME_DIR, ".config");
                    if (!targetConfig.exists()) targetConfig.mkdirs();
                    configCount = copyDirectory(stagingConfig, targetConfig);
                }

                // 5. POSIX block sync
                flushDisk();

                final int finalPrefs = prefsCount;
                final int finalConfigs = configCount;
                MAIN_HANDLER.post(() -> callback.onSuccess(finalPrefs, finalConfigs));

            } catch (Throwable t) {
                Log.e(TAG, "Restore backup failed", t);
                MAIN_HANDLER.post(() -> callback.onError("Restore failed: " + t.getMessage()));
            } finally {
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
        if (callback != null) MAIN_HANDLER.post(() -> callback.onProgress(msg));
    }

    private static void postProgress(RestoreCallback callback, String msg) {
        if (callback != null) MAIN_HANDLER.post(() -> callback.onProgress(msg));
    }

    private static void zipDirectory(File sourceDir, File zipFile) throws Exception {
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

    private static void unzip(InputStream is, File targetDir) throws Exception {
        byte[] buffer = new byte[8192];
        try (ZipInputStream zis = new ZipInputStream(new BufferedInputStream(is))) {
            ZipEntry entry;
            while ((entry = zis.getNextEntry()) != null) {
                File file = new File(targetDir, entry.getName());
                // Prevent Zip Slip vulnerability
                if (!file.getCanonicalPath().startsWith(targetDir.getCanonicalPath())) {
                    throw new SecurityException("Zip entry outside target directory: " + entry.getName());
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

    private static int copyDirectory(File source, File dest) {
        int count = 0;
        File[] files = source.listFiles();
        if (files == null) return 0;
        if (!dest.exists()) dest.mkdirs();

        for (File f : files) {
            File target = new File(dest, f.getName());
            if (f.isDirectory()) {
                count += copyDirectory(f, target);
            } else {
                try {
                    copyFile(f, target);
                    count++;
                } catch (Throwable ignored) {}
            }
        }
        return count;
    }

    private static void copyFile(File src, File dst) throws Exception {
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

    private static String readFileToString(File file) throws Exception {
        try (FileInputStream fis = new FileInputStream(file)) {
            byte[] data = new byte[(int) file.length()];
            fis.read(data);
            return new String(data, StandardCharsets.UTF_8);
        }
    }

    private static void deleteRecursively(File fileOrDir) {
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
