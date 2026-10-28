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

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

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
                if (tempStagingFile.exists()) tempStagingFile.delete();

                // 5. Streamed Buffer Copying
                long bytesCopied = 0;
                long lastProgressTime = 0;

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
                            return;
                        }

                        out.write(buffer, 0, read);
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

                // 6. Verification
                if (sourceLength > 0 && tempStagingFile.length() != sourceLength) {
                    tempStagingFile.delete();
                    throw new IllegalStateException("Verification failed: Copied file size (" +
                            tempStagingFile.length() + " bytes) does not match source (" + sourceLength + " bytes).");
                }

                // 7. Atomic Commit (Rename Staging to Final)
                if (finalDestFile.exists()) {
                    finalDestFile.delete();
                }
                if (!tempStagingFile.renameTo(finalDestFile)) {
                    throw new IllegalStateException("Failed to commit final destination file: " + finalDestFile.getAbsolutePath());
                }

                if (!finalDestFile.exists() || !finalDestFile.canRead()) {
                    throw new IllegalStateException("Destination file verification failed after commit.");
                }

                // 8. Handle MOVE: Delete source only after destination verified
                if (isMoveOperation) {
                    deleteSourceFile(sourceUri, record.sourcePath, record.sourceFilename);
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
                isRunning = false;
            }
        });
    }

    private void deleteSourceFile(Uri sourceUri, String sourcePath, String sourceFilename) {
        ContentResolver resolver = appContext.getContentResolver();

        // 1. Delete direct POSIX file if path exists
        if (sourcePath != null && !sourcePath.isEmpty()) {
            try {
                File src = new File(sourcePath);
                if (src.exists()) {
                    src.delete();
                }
            } catch (Throwable t) {
                Log.w(TAG, "Failed deleting direct source path: " + t.getMessage());
            }
        }

        // 2. Check public Downloads folder by filename
        try {
            File dl = new File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS), sourceFilename);
            if (dl.exists()) {
                dl.delete();
            }
        } catch (Throwable ignored) {}

        // 3. Delete via URI
        if (sourceUri != null) {
            try {
                resolver.delete(sourceUri, null, null);
            } catch (Throwable ignored) {}

            try {
                DocumentFile df = DocumentFile.fromSingleUri(appContext, sourceUri);
                if (df != null && df.exists()) {
                    df.delete();
                }
            } catch (Throwable ignored) {}
        }

        // 4. Delete from MediaStore Video table
        try {
            resolver.delete(MediaStore.Video.Media.EXTERNAL_CONTENT_URI,
                    MediaStore.Video.Media.DISPLAY_NAME + "=?", new String[]{sourceFilename});
        } catch (Throwable ignored) {}

        // 5. Trigger MediaScanner to drop deleted file
        if (sourcePath != null && !sourcePath.isEmpty()) {
            try {
                MediaScannerConnection.scanFile(appContext, new String[]{sourcePath}, null, null);
            } catch (Throwable ignored) {}
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

    private String getFileExtension(String filename) {
        if (filename == null) return "mkv";
        int dot = filename.lastIndexOf('.');
        if (dot >= 0 && dot < filename.length() - 1) {
            return filename.substring(dot + 1).toLowerCase(java.util.Locale.ROOT);
        }
        return "mkv";
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
