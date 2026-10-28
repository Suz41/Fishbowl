package com.fishbowl.app.importer;

import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.ContentResolver;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.database.ContentObserver;
import android.database.Cursor;
import android.net.Uri;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.provider.MediaStore;
import android.util.Log;

import androidx.core.app.NotificationCompat;

import com.fishbowl.app.StorageDriveHelper;
import com.termux.R;

import java.io.File;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Opt-in background detection manager for new browser downloads.
 * Uses MediaStore ContentObserver with debounce and file-stability checks.
 * NEVER alters or moves files automatically; presents a review notification to the user.
 */
public final class DownloadDetectionManager {

    private static final String TAG = "DownloadDetection";
    public static final String PREF_NAME = "fishbowl_importer";
    public static final String KEY_DETECT_DOWNLOADS = "detect_downloads_enabled";
    private static final String CHANNEL_ID = "movie_importer_downloads";
    private static final int NOTIFICATION_ID = 2002;

    private static DownloadDetectionManager instance;
    private final Context appContext;
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private final ExecutorService checkExecutor = Executors.newSingleThreadExecutor();
    private boolean isRegistered = false;
    private long lastDebounceTime = 0;

    private final ContentObserver mediaObserver = new ContentObserver(mainHandler) {
        @Override
        public void onChange(boolean selfChange, Uri uri) {
            long now = System.currentTimeMillis();
            if (now - lastDebounceTime < 3000) return; // 3s debounce
            lastDebounceTime = now;
            scanForNewDownloads();
        }
    };

    public static synchronized DownloadDetectionManager getInstance(Context context) {
        if (instance == null && context != null) {
            instance = new DownloadDetectionManager(context.getApplicationContext());
        }
        return instance;
    }

    private DownloadDetectionManager(Context context) {
        this.appContext = context;
        createNotificationChannel();
        if (isDetectionEnabled()) {
            startMonitoring();
        }
    }

    public boolean isDetectionEnabled() {
        if (appContext == null) return false;
        SharedPreferences sp = appContext.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE);
        return sp.getBoolean(KEY_DETECT_DOWNLOADS, false);
    }

    public synchronized void setDetectionEnabled(boolean enabled) {
        if (appContext == null) return;
        appContext.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
                .edit()
                .putBoolean(KEY_DETECT_DOWNLOADS, enabled)
                .apply();

        if (enabled) {
            startMonitoring();
            scanForNewDownloads();
        } else {
            stopMonitoring();
        }
    }

    public synchronized void startMonitoring() {
        if (isRegistered || appContext == null) return;
        try {
            ContentResolver resolver = appContext.getContentResolver();
            resolver.registerContentObserver(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, true, mediaObserver);
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                resolver.registerContentObserver(MediaStore.Downloads.EXTERNAL_CONTENT_URI, true, mediaObserver);
            }
            isRegistered = true;
            Log.i(TAG, "Download detection monitoring started.");
        } catch (Throwable t) {
            Log.w(TAG, "Failed to register download observer: " + t.getMessage());
        }
    }

    public synchronized void stopMonitoring() {
        if (!isRegistered || appContext == null) return;
        try {
            appContext.getContentResolver().unregisterContentObserver(mediaObserver);
            isRegistered = false;
            Log.i(TAG, "Download detection monitoring stopped.");
        } catch (Throwable t) {
            Log.w(TAG, "Failed to unregister download observer: " + t.getMessage());
        }
    }

    /**
     * Inspects MediaStore for recently finalized video files.
     */
    public void scanForNewDownloads() {
        if (!isDetectionEnabled()) return;

        checkExecutor.execute(() -> {
            try {
                ContentResolver resolver = appContext.getContentResolver();
                String[] projection = {
                        MediaStore.Video.Media._ID,
                        MediaStore.Video.Media.DISPLAY_NAME,
                        MediaStore.Video.Media.SIZE,
                        MediaStore.Video.Media.DATE_MODIFIED
                };

                // Look for videos modified within the last 1 hour
                long sinceSeconds = (System.currentTimeMillis() - (60 * 60 * 1000L)) / 1000L;
                String selection = MediaStore.Video.Media.DATE_MODIFIED + " >= ?";
                String[] args = { String.valueOf(sinceSeconds) };
                String sort = MediaStore.Video.Media.DATE_MODIFIED + " DESC";

                try (Cursor cursor = resolver.query(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, projection, selection, args, sort)) {
                    if (cursor != null && cursor.moveToFirst()) {
                        do {
                            long id = cursor.getLong(cursor.getColumnIndexOrThrow(MediaStore.Video.Media._ID));
                            String name = cursor.getString(cursor.getColumnIndexOrThrow(MediaStore.Video.Media.DISPLAY_NAME));
                            long size = cursor.getLong(cursor.getColumnIndexOrThrow(MediaStore.Video.Media.SIZE));

                            if (name == null || size < 20 * 1024 * 1024L) continue; // Minimum 20MB for a movie/video

                            // Exclude incomplete browser download artifacts
                            String lower = name.toLowerCase();
                            if (lower.endsWith(".crdownload") || lower.endsWith(".part") || lower.endsWith(".tmp")) {
                                continue;
                            }

                            if (!StorageDriveHelper.isMediaFile(name)) continue;

                            Uri contentUri = Uri.withAppendedPath(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, String.valueOf(id));
                            String sig = contentUri.toString() + ":" + size;

                            MovieImportJournal journal = MovieImportJournal.getInstance(appContext);
                            if (journal.isSignatureProcessed(sig)) {
                                continue;
                            }

                            // Verify file stability (avoid files still being actively written)
                            try { Thread.sleep(2000); } catch (InterruptedException ignored) {}
                            long secondSize = 0;
                            try (android.content.res.AssetFileDescriptor afd = resolver.openAssetFileDescriptor(contentUri, "r")) {
                                if (afd != null) secondSize = afd.getLength();
                            } catch (Throwable ignored) {}

                            if (secondSize > 0 && secondSize == size) {
                                // Stable complete download detected!
                                journal.recordSignatureProcessed(sig);
                                postDownloadNotification(contentUri, name, size);
                                break; // Notify for the top new item
                            }

                        } while (cursor.moveToNext());
                    }
                }
            } catch (Throwable t) {
                Log.w(TAG, "Error checking new downloads: " + t.getMessage());
            }
        });
    }

    private void postDownloadNotification(Uri fileUri, String filename, long sizeBytes) {
        try {
            Intent intent = new Intent(appContext, MovieImportActivity.class);
            intent.setAction("com.fishbowl.app.ACTION_REVIEW_IMPORT");
            intent.setData(fileUri);
            intent.putExtra("extra_source_name", filename);
            intent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_SINGLE_TOP);

            PendingIntent pi = PendingIntent.getActivity(
                    appContext,
                    (int) System.currentTimeMillis(),
                    intent,
                    PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE
            );

            MovieFilenameParser.ParsedMovie parsed = MovieFilenameParser.parse(filename);

            NotificationCompat.Builder builder = new NotificationCompat.Builder(appContext, CHANNEL_ID)
                    .setContentTitle("Movie download detected — Review import")
                    .setContentText(parsed.title + (parsed.year > 0 ? " (" + parsed.year + ")" : ""))
                    .setStyle(new NotificationCompat.BigTextStyle()
                            .setBigContentTitle("Movie download detected")
                            .bigText("File: " + filename + "\nReady to organize into Jellyfin movie folder structure. Tap to review destination and import."))
                    .setSmallIcon(R.drawable.ic_notification_jellyfin)
                    .setContentIntent(pi)
                    .setAutoCancel(true)
                    .setPriority(NotificationCompat.PRIORITY_DEFAULT);

            NotificationManager nm = (NotificationManager) appContext.getSystemService(Context.NOTIFICATION_SERVICE);
            if (nm != null) {
                nm.notify(NOTIFICATION_ID, builder.build());
            }
        } catch (Throwable t) {
            Log.w(TAG, "Failed to post download notification: " + t.getMessage());
        }
    }

    private void createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel channel = new NotificationChannel(
                    CHANNEL_ID,
                    "Fishbowl Movie Importer",
                    NotificationManager.IMPORTANCE_DEFAULT
            );
            channel.setDescription("Notifies when new video downloads are ready to review and import into Jellyfin");
            NotificationManager nm = (NotificationManager) appContext.getSystemService(Context.NOTIFICATION_SERVICE);
            if (nm != null) {
                nm.createNotificationChannel(channel);
            }
        }
    }
}
