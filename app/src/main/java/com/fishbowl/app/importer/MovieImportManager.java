package com.fishbowl.app.importer;

import android.content.ContentResolver;
import android.content.Context;
import android.database.Cursor;
import android.media.MediaScannerConnection;
import android.net.Uri;
import android.os.Environment;
import android.os.Handler;
import android.os.Looper;
import android.os.StatFs;
import android.provider.MediaStore;
import android.util.Log;

import androidx.documentfile.provider.DocumentFile;

import com.fishbowl.app.MediaStorageManager;
import com.fishbowl.app.StorageDriveHelper;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.Locale;

/**
 * High-performance streaming media import engine supporting Movies and TV Series.
 * Implements safe 64KB bounded buffer I/O, length verification, atomic rename,
 * clean MOVE (cut & delete) operations, and automated Jellyfin library refresh.
 */
public final class MovieImportManager {

    private static final String TAG = "MovieImportManager";
    private static final int BUFFER_SIZE = 64 * 1024; // 64 KB streaming buffer
    private static final String JELLYFIN_REFRESH_URL = "http://127.0.0.1:8096/Library/Refresh";

    public enum ConflictStrategy {
        KEEP_BOTH,
        SKIP,
        REPLACE
    }

    public interface ImportListener {
        void onProgress(MovieImportJournal.ImportRecord record, long bytesWritten, long totalBytes, int percent);
        void onSuccess(MovieImportJournal.ImportRecord record, boolean jellyfinRefreshed);
        void onError(MovieImportJournal.ImportRecord record, String errorMessage);
        void onCancelled(MovieImportJournal.ImportRecord record);
    }

    private static MovieImportManager instance;
    private static volatile File activeStagingFile = null;
    private final Context appContext;
    private final MovieImportJournal journal;
    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private final AtomicBoolean isCancelled = new AtomicBoolean(false);
    private volatile boolean isRunning = false;

    public static synchronized MovieImportManager getInstance(Context context) {
        if (instance == null && context != null) {
            instance = new MovieImportManager(context.getApplicationContext());
        }
        return instance;
    }

    private MovieImportManager(Context context) {
        this.appContext = context;
        this.journal = MovieImportJournal.getInstance(context);
    }

    public boolean isBusy() {
        return isRunning;
    }

    public void cancelCurrentImport() {
        isCancelled.set(true);
    }

    /**
     * Executes safe media import (Movie or TV Show) in background thread.
     */
    public static String computeSHA256(File file) throws IOException {
        if (file == null || !file.exists() || !file.isFile()) {
            throw new IOException("Cannot compute checksum of non-existent or invalid file: " + (file != null ? file.getAbsolutePath() : "null"));
        }
        try {
            java.security.MessageDigest md = java.security.MessageDigest.getInstance("SHA-256");
            byte[] buf = new byte[BUFFER_SIZE];
            try (InputStream in = new FileInputStream(file)) {
                int r;
                while ((r = in.read(buf)) != -1) {
                    md.update(buf, 0, r);
                }
            }
            byte[] digest = md.digest();
            StringBuilder hex = new StringBuilder();
            for (byte b : digest) {
                hex.append(String.format(Locale.ROOT, "%02x", b));
            }
            return hex.toString();
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new RuntimeException("SHA-256 algorithm unavailable", e);
        }
    }

    public static class CommitTransaction {
        public final File backupFile;
        public final File destFile;
        public final File stagingFile;
        public final boolean wasReplaced;

        public CommitTransaction(File backupFile, File destFile, File stagingFile, boolean wasReplaced) {
            this.backupFile = backupFile;
            this.destFile = destFile;
            this.stagingFile = stagingFile;
            this.wasReplaced = wasReplaced;
        }

        public void commitSuccess() {
            if (wasReplaced && backupFile != null && backupFile.exists()) {
                backupFile.delete();
            }
        }

        public void rollback() {
            if (wasReplaced && backupFile != null && backupFile.exists()) {
                if (destFile.exists()) {
                    destFile.delete();
                }
                backupFile.renameTo(destFile);
                Log.e(TAG, "[Rollback] Restored original destination file from backup: " + destFile.getAbsolutePath());
            } else if (!wasReplaced && destFile != null && destFile.exists()) {
                destFile.delete();
            }
        }
    }

    public static boolean isPathContained(File libraryRoot, File target) {
        if (libraryRoot == null || target == null) return false;
        try {
            String rootCanonical = libraryRoot.getCanonicalFile().getAbsolutePath();
            String targetCanonical = target.getCanonicalFile().getAbsolutePath();
            return targetCanonical.equals(rootCanonical) || targetCanonical.startsWith(rootCanonical + File.separator);
        } catch (IOException e) {
            return false;
        }
    }

    public static CommitTransaction beginSafeCommitDestination(File stagingFile, File destFile) throws IOException {
        if (stagingFile == null || !stagingFile.exists()) {
            throw new IOException("Staging file does not exist: " + (stagingFile != null ? stagingFile.getAbsolutePath() : "null"));
        }
        File destFolder = destFile.getParentFile();
        if (destFolder != null && !destFolder.exists()) {
            destFolder.mkdirs();
        }

        // Case 1: Destination does not exist yet (clean new import)
        if (!destFile.exists()) {
            if (stagingFile.renameTo(destFile)) {
                return new CommitTransaction(null, destFile, stagingFile, false);
            }
            try {
                java.nio.file.Files.move(stagingFile.toPath(), destFile.toPath(), java.nio.file.StandardCopyOption.REPLACE_EXISTING);
                return new CommitTransaction(null, destFile, stagingFile, false);
            } catch (Throwable t) {
                throw new IOException("Failed to commit staging file to destination: " + t.getMessage(), t);
            }
        }

        // Case 2: Destination already exists (REPLACE) - Safe Backup & Rollback
        File backupFile = new File(destFolder, destFile.getName() + ".replace_backup_" + System.currentTimeMillis());
        if (!destFile.renameTo(backupFile)) {
            try {
                java.nio.file.Files.move(destFile.toPath(), backupFile.toPath(), java.nio.file.StandardCopyOption.REPLACE_EXISTING);
            } catch (Throwable t) {
                throw new IOException("Cannot backup existing destination file before replace: " + destFile.getAbsolutePath(), t);
            }
        }

        boolean committed = false;
        try {
            if (stagingFile.renameTo(destFile)) {
                committed = true;
            } else {
                java.nio.file.Files.move(stagingFile.toPath(), destFile.toPath(), java.nio.file.StandardCopyOption.REPLACE_EXISTING);
                committed = true;
            }
        } catch (Throwable t) {
            committed = false;
        }

        if (committed && destFile.exists() && destFile.length() > 0) {
            // Keep backup until destination checksum verification succeeds!
            return new CommitTransaction(backupFile, destFile, stagingFile, true);
        } else {
            // Immediate rollback
            Log.e(TAG, "[Rollback] Commit failed! Restoring original destination file from backup.");
            if (destFile.exists()) destFile.delete();
            backupFile.renameTo(destFile);
            throw new IOException("Failed to commit replacement file. Rollback successfully restored original destination.");
        }
    }

    public static boolean safeCommitDestination(File stagingFile, File destFile) throws IOException {
        CommitTransaction tx = beginSafeCommitDestination(stagingFile, destFile);
        tx.commitSuccess();
        return true;
    }

    public void startImport(Uri sourceUri,
                            String sourceFilename,
                            String destinationFolderRoot,
                            MovieMetadata metadata,
                            boolean isMoveOperation,
                            ConflictStrategy conflictStrategy,
                            ImportListener listener) {

        if (isRunning) {
            if (listener != null) {
                mainHandler.post(() -> listener.onError(null, "An import operation is already actively running."));
            }
            return;
        }

        isRunning = true;
        isCancelled.set(false);

        // Purge orphaned staging files from previous interrupted moves
        cleanOrphanedStagingFiles(appContext);

        executor.execute(() -> {
            MovieImportJournal.ImportRecord record = new MovieImportJournal.ImportRecord();
            record.id = java.util.UUID.randomUUID().toString();
            record.sourceUriString = sourceUri != null ? sourceUri.toString() : "";
            record.sourceFilename = sourceFilename != null ? sourceFilename : "unknown.mkv";
            record.movieTitle = metadata != null ? metadata.title : "Unknown";
            record.movieYear = metadata != null ? metadata.year : 0;
            record.seasonNumber = metadata != null ? metadata.seasonNumber : -1;
            record.episodeNumber = metadata != null ? metadata.episodeNumber : -1;
            boolean isTv = metadata != null && metadata.mediaType == MovieFilenameParser.MediaType.TV_SHOW;
            record.mediaType = isTv ? "TV_SHOW" : "MOVIE";
            record.operation = isMoveOperation ? "MOVE" : "COPY";
            record.retainOriginal = !isMoveOperation;
            record.status = "IN_PROGRESS";
            record.timestampMs = System.currentTimeMillis();

            // Resolve exact source filesystem path if possible
            record.sourcePath = resolveSourcePath(sourceUri, record.sourceFilename);

            File tempStagingFile = null;
            CommitTransaction transaction = null;

            try {
                // 1. Validate Source Access and Length
                ContentResolver resolver = appContext.getContentResolver();
                long sourceLength = 0;
                try (android.content.res.AssetFileDescriptor afd = resolver.openAssetFileDescriptor(sourceUri, "r")) {
                    if (afd != null) {
                        sourceLength = afd.getLength();
                    }
                } catch (Throwable ignored) {}

                if (sourceLength <= 0) {
                    if (record.sourcePath != null && !record.sourcePath.isEmpty()) {
                        File f = new File(record.sourcePath);
                        if (f.exists()) sourceLength = f.length();
                    }
                }

                if (sourceLength <= 0) {
                    DocumentFile df = DocumentFile.fromSingleUri(appContext, sourceUri);
                    if (df != null) {
                        sourceLength = df.length();
                    }
                }
                record.fileSizeBytes = sourceLength;
                journal.addRecord(record);

                // 2. Validate Destination Directory
                String rootPath;
                if (destinationFolderRoot != null && !destinationFolderRoot.trim().isEmpty()) {
                    rootPath = StorageDriveHelper.normalizeStoragePath(destinationFolderRoot);
                } else {
                    rootPath = isTv
                            ? MediaStorageManager.getInstance(appContext).getTvDir().getAbsolutePath()
                            : MediaStorageManager.getInstance(appContext).getMoviesDir().getAbsolutePath();
                }

                String ext = getFileExtension(record.sourceFilename);
                if (!MovieFilenameParser.isSupportedMediaExtension(ext)) {
                    throw new IllegalArgumentException("Unsupported or unsafe media extension: '" + ext + "'. Supported: " +
                            MovieFilenameParser.ALLOWED_MEDIA_EXTENSIONS);
                }

                File destFolder;
                File finalDestFile;

                if (isTv) {
                    int sNum = record.seasonNumber > 0 ? record.seasonNumber : 1;
                    int eNum = record.episodeNumber > 0 ? record.episodeNumber : 1;
                    int endENum = metadata != null ? metadata.endEpisodeNumber : -1;
                    String tvRelFolder = MovieFilenameParser.buildSanitizedTvFolder(record.movieTitle, record.movieYear, sNum);
                    destFolder = new File(rootPath, tvRelFolder);
                    String baseFileName = MovieFilenameParser.buildSanitizedTvFile(record.movieTitle, sNum, eNum, endENum, ext);
                    finalDestFile = new File(destFolder, baseFileName);
                } else {
                    String movieFolderName = MovieFilenameParser.buildSanitizedName(record.movieTitle, record.movieYear, "");
                    destFolder = new File(rootPath, movieFolderName);
                    String baseFileName = MovieFilenameParser.buildSanitizedName(record.movieTitle, record.movieYear, ext);
                    finalDestFile = new File(destFolder, baseFileName);
                }

                File rootDir = new File(rootPath);
                if (!isPathContained(rootDir, destFolder) || !isPathContained(rootDir, finalDestFile)) {
                    throw new SecurityException("Destination path containment violation: Target path escapes media library root. Dest: " +
                            finalDestFile.getAbsolutePath() + ", Root: " + rootDir.getAbsolutePath());
                }

                if (!destFolder.exists()) {
                    if (!destFolder.mkdirs() && !destFolder.exists()) {
                        throw new IllegalStateException("Failed to create destination directory: " + destFolder.getAbsolutePath());
                    }
                }

                // Check free storage space
                try {
                    StatFs stat = new StatFs(destFolder.getAbsolutePath());
                    long availableBytes = stat.getAvailableBytes();
                    if (sourceLength > 0 && availableBytes < sourceLength + (50 * 1024 * 1024)) { // 50MB safety buffer
                        throw new IllegalStateException("Insufficient destination storage. Available: " +
                                MediaStorageManager.formatBytes(availableBytes) +
                                ", Required: " + MediaStorageManager.formatBytes(sourceLength));
                    }
                } catch (IllegalArgumentException ignored) {}

                // 3. Conflict Detection
                if (finalDestFile.exists()) {
                    if (conflictStrategy == ConflictStrategy.SKIP) {
                        record.status = "SKIPPED";
                        record.destinationPath = finalDestFile.getAbsolutePath();
                        record.errorMessage = "File already exists at destination (Skipped).";
                        journal.updateRecord(record);
                        mainHandler.post(() -> {
                            if (listener != null) listener.onSuccess(record, false);
                        });
                        isRunning = false;
                        return;
                    } else if (conflictStrategy == ConflictStrategy.KEEP_BOTH) {
                        finalDestFile = getUniqueConflictFile(destFolder, finalDestFile.getName());
                    }
                }

                record.destinationPath = finalDestFile.getAbsolutePath();

                // 4. Temporary Staging Target for Atomic Write
                tempStagingFile = new File(destFolder, finalDestFile.getName() + ".fishbowl_tmp");
                activeStagingFile = tempStagingFile;
                if (tempStagingFile.exists()) tempStagingFile.delete();

                // 5. Streamed Buffer Copying & SHA-256 Digest Verification
                long bytesCopied = 0;
                long lastProgressTime = 0;
                java.security.MessageDigest md;
                try {
                    md = java.security.MessageDigest.getInstance("SHA-256");
                } catch (java.security.NoSuchAlgorithmException e) {
                    throw new IllegalStateException("Cryptographic engine failure: SHA-256 unavailable. Import aborted.", e);
                }

                try (InputStream in = resolver.openInputStream(sourceUri);
                     FileOutputStream out = new FileOutputStream(tempStagingFile)) {

                    if (in == null) {
                        throw new IllegalStateException("Cannot open input stream for source URI: " + sourceUri);
                    }

                    byte[] buffer = new byte[BUFFER_SIZE];
                    int read;

                    while ((read = in.read(buffer)) != -1) {
                        if (isCancelled.get()) {
                            tempStagingFile.delete();
                            record.status = "CANCELLED";
                            record.errorMessage = "User cancelled import.";
                            journal.updateRecord(record);
                            mainHandler.post(() -> {
                                if (listener != null) listener.onCancelled(record);
                            });
                            isRunning = false;
                            activeStagingFile = null;
                            return;
                        }

                        out.write(buffer, 0, read);
                        if (md != null) md.update(buffer, 0, read);
                        bytesCopied += read;

                        long now = System.currentTimeMillis();
                        if (now - lastProgressTime > 150) { // Throttle progress callbacks to ~7/sec
                            lastProgressTime = now;
                            final long curBytes = bytesCopied;
                            final long total = sourceLength;
                            final int pct = total > 0 ? (int) ((curBytes * 100) / total) : 0;
                            mainHandler.post(() -> {
                                if (listener != null) listener.onProgress(record, curBytes, total, pct);
                            });
                        }
                    }

                    out.flush();
                    try {
                        out.getFD().sync();
                    } catch (Throwable ignored) {}
                }

                if (md != null) {
                    byte[] digest = md.digest();
                    StringBuilder hex = new StringBuilder();
                    for (byte b : digest) {
                        hex.append(String.format(Locale.ROOT, "%02x", b));
                    }
                    record.sourceChecksum = hex.toString();
                }

                // 6. Verification
                if (sourceLength > 0 && tempStagingFile.length() != sourceLength) {
                    tempStagingFile.delete();
                    throw new IllegalStateException("Verification failed: Copied file size (" +
                            tempStagingFile.length() + " bytes) does not match source (" + sourceLength + " bytes).");
                }

                // 7. Safe Destination Commit with Backup & Rollback Protection
                transaction = beginSafeCommitDestination(tempStagingFile, finalDestFile);

                if (!finalDestFile.exists() || !finalDestFile.canRead()) {
                    transaction.rollback();
                    throw new IllegalStateException("Destination file verification failed after commit.");
                }

                // 8. Independent Destination SHA-256 Verification & Fail-Closed Source Deletion Guard
                String destChecksum = computeSHA256(finalDestFile);
                if (record.sourceChecksum == null || record.sourceChecksum.trim().isEmpty()) {
                    transaction.rollback();
                    throw new IllegalStateException("Cryptographic verification failed: Source checksum is missing or empty. Original destination restored and source preserved.");
                }

                if (!java.security.MessageDigest.isEqual(
                        record.sourceChecksum.toLowerCase(Locale.ROOT).getBytes(StandardCharsets.UTF_8),
                        destChecksum.toLowerCase(Locale.ROOT).getBytes(StandardCharsets.UTF_8))) {
                    transaction.rollback();
                    throw new IllegalStateException("Cryptographic verification failed: Destination SHA-256 (" +
                            destChecksum + ") does not match source checksum (" + record.sourceChecksum + "). Original destination restored and source preserved.");
                }

                Log.i(TAG, "[IntegrityGuard] Cryptographic SHA-256 match verified between source and destination: " + destChecksum);

                // Both commit and cryptographic verification succeeded: safely purge backup
                transaction.commitSuccess();

                if (isMoveOperation) {
                    deleteSourceFile(sourceUri, record.sourcePath, record.sourceFilename, finalDestFile, sourceLength);
                }

                // Mark Completed
                record.status = "COMPLETED";
                record.fileSizeBytes = finalDestFile.length();
                journal.updateRecord(record);

                // Register Signature to prevent duplicate auto-detection notifications
                String sig = sourceUri.toString() + ":" + record.fileSizeBytes;
                journal.recordSignatureProcessed(sig);

                // 9. Jellyfin Integration: Trigger Library Scan
                boolean jellyfinRefreshed = triggerJellyfinScan();

                mainHandler.post(() -> {
                    if (listener != null) listener.onSuccess(record, jellyfinRefreshed);
                });

            } catch (Throwable t) {
                Log.e(TAG, "Media import failed: " + t.getMessage(), t);
                if (transaction != null) {
                    transaction.rollback();
                }
                if (tempStagingFile != null && tempStagingFile.exists()) {
                    tempStagingFile.delete();
                }
                record.status = "FAILED";
                record.errorMessage = t.getMessage() != null ? t.getMessage() : "Unknown I/O Error";
                journal.updateRecord(record);

                mainHandler.post(() -> {
                    if (listener != null) listener.onError(record, record.errorMessage);
                });
            } finally {
                activeStagingFile = null;
                isRunning = false;
            }
        });
    }

    private void deleteSourceFile(Uri sourceUri, String sourcePath, String sourceFilename, File verifiedDestFile, long expectedSize) {
        if (verifiedDestFile == null || !verifiedDestFile.exists() || (expectedSize > 0 && verifiedDestFile.length() != expectedSize)) {
            Log.e(TAG, "[SafetyGuard] Refusing to delete source file! Destination verification failed. Source file preserved: " + sourcePath);
            return;
        }
        ContentResolver resolver = appContext.getContentResolver();

        // 1. Verified Direct POSIX Deletion: Strict identity, canonical verification, size parity
        if (sourcePath != null && !sourcePath.trim().isEmpty()) {
            try {
                File src = new File(sourcePath).getCanonicalFile();
                if (src.exists() && src.isFile()) {
                    // Refuse deletion if file length changed during transfer
                    if (expectedSize > 0 && src.length() != expectedSize) {
                        Log.e(TAG, "[SafetyGuard] Source file modified during transfer! Refusing deletion to prevent data loss: " + sourcePath);
                        return;
                    }
                    // Refuse deletion if source path matches destination path
                    if (src.getAbsolutePath().equals(verifiedDestFile.getCanonicalPath())) {
                        Log.e(TAG, "[SafetyGuard] Source path equals destination path! Refusing deletion: " + sourcePath);
                        return;
                    }
                    boolean deleted = src.delete();
                    if (deleted) {
                        Log.i(TAG, "[SafetyGuard] Safely deleted verified source file: " + src.getAbsolutePath());
                        try {
                            MediaScannerConnection.scanFile(appContext, new String[]{src.getAbsolutePath()}, null, null);
                        } catch (Throwable ignored) {}
                    }
                }
            } catch (Throwable t) {
                Log.w(TAG, "Failed deleting verified direct source path: " + t.getMessage());
            }
        }

        // 2. Targeted Document / Content URI Deletion (Delete strictly the verified specific source URI)
        if (sourceUri != null) {
            String scheme = sourceUri.getScheme();
            if (ContentResolver.SCHEME_CONTENT.equalsIgnoreCase(scheme)) {
                try {
                    DocumentFile df = DocumentFile.fromSingleUri(appContext, sourceUri);
                    if (df != null && df.exists() && df.isFile()) {
                        if (expectedSize <= 0 || df.length() == expectedSize) {
                            df.delete();
                            Log.i(TAG, "[SafetyGuard] Safely deleted source DocumentFile: " + sourceUri);
                        }
                    } else {
                        resolver.delete(sourceUri, null, null);
                        Log.i(TAG, "[SafetyGuard] Safely deleted source Content URI: " + sourceUri);
                    }
                } catch (Throwable t) {
                    Log.w(TAG, "Failed deleting source Content URI: " + t.getMessage());
                }
            }
        }
    }

    private String resolveSourcePath(Uri uri, String filename) {
        if (uri == null) return "";
        if ("file".equalsIgnoreCase(uri.getScheme())) {
            return uri.getPath();
        }

        // Try querying MediaStore _data
        try {
            String[] proj = {MediaStore.Video.Media.DATA};
            try (Cursor cursor = appContext.getContentResolver().query(uri, proj, null, null, null)) {
                if (cursor != null && cursor.moveToFirst()) {
                    int idx = cursor.getColumnIndex(MediaStore.Video.Media.DATA);
                    if (idx >= 0) {
                        String path = cursor.getString(idx);
                        if (path != null && !path.isEmpty()) return path;
                    }
                }
            }
        } catch (Throwable ignored) {}

        // Fallback: check standard public downloads directory
        if (filename != null && !filename.isEmpty()) {
            File dlFile = new File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS), filename);
            if (dlFile.exists()) {
                return dlFile.getAbsolutePath();
            }
        }
        return uri.toString();
    }

    private File getUniqueConflictFile(File folder, String fullName) {
        int dot = fullName.lastIndexOf('.');
        String base = dot > 0 ? fullName.substring(0, dot) : fullName;
        String ext = dot > 0 ? fullName.substring(dot) : "";

        int counter = 1;
        while (counter < 100) {
            String candidate = base + " (" + counter + ")" + ext;
            File f = new File(folder, candidate);
            if (!f.exists()) return f;
            counter++;
        }
        return new File(folder, base + "_" + System.currentTimeMillis() + ext);
    }

    
    public static void cleanOrphanedStagingFiles(Context ctx) {
        if (ctx == null) return;
        try {
            MediaStorageManager sm = MediaStorageManager.getInstance(ctx);
            cleanStagingInDir(sm.getMoviesDir());
            cleanStagingInDir(sm.getTvDir());
        } catch (Throwable t) {
            Log.w(TAG, "Failed cleaning orphaned staging files: " + t.getMessage());
        }
    }

    private static void cleanStagingInDir(File dir) {
        if (dir == null || !dir.exists() || !dir.isDirectory()) return;
        File[] list = dir.listFiles();
        if (list == null) return;
        long now = System.currentTimeMillis();
        for (File f : list) {
            if (f.isDirectory()) {
                cleanStagingInDir(f);
            } else if (f.getName().endsWith(".fishbowl_tmp")) {
                // Safeguard: Never delete an actively transferring staging file
                if (f.equals(activeStagingFile)) {
                    continue;
                }
                // Safeguard: Only purge staging files older than 30 minutes to prevent race conditions
                long age = now - f.lastModified();
                if (age > 30 * 60 * 1000L) {
                    Log.i(TAG, "Purging stale orphaned staging file (" + (age / 60000) + "m old): " + f.getAbsolutePath());
                    f.delete();
                }
            }
        }
    }

    public static String getFileExtension(String filename) {
        if (filename == null) return "";
        int dot = filename.lastIndexOf('.');
        if (dot >= 0 && dot < filename.length() - 1) {
            String ext = filename.substring(dot + 1).toLowerCase(java.util.Locale.ROOT);
            if (MovieFilenameParser.isSupportedMediaExtension(ext)) {
                return MovieFilenameParser.normalizeMediaExtension(ext);
            }
            return ext;
        }
        return "";
    }

    private boolean triggerJellyfinScan() {
        try {
            URL url = new URL(JELLYFIN_REFRESH_URL);
            HttpURLConnection conn = (HttpURLConnection) url.openConnection();
            conn.setRequestMethod("POST");
            conn.setConnectTimeout(3000);
            conn.setReadTimeout(3000);
            int code = conn.getResponseCode();
            conn.disconnect();
            return code >= 200 && code < 400;
        } catch (Throwable t) {
            Log.d(TAG, "Jellyfin library refresh HTTP call skipped (Server offline or port unreachable): " + t.getMessage());
            return false;
        }
    }
}
