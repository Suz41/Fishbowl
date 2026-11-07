package com.fishbowl.app;

import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Environment;
import android.os.FileObserver;
import android.util.Log;

import androidx.core.app.NotificationCompat;
import androidx.core.app.NotificationManagerCompat;
import androidx.core.content.ContextCompat;

import com.termux.R;
import com.termux.shared.termux.TermuxConstants;

import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * Monitors Jellyfin data directory for password reset PIN tokens generated when a user
 * triggers "Forgot Password" on the Jellyfin web client.
 *
 * Automatically mirrors the reset PIN to /sdcard/Fishbowl/password/ with 5-minute auto-deletion,
 * and posts a dedicated high-priority system notification with a direct 'COPY PIN' action button.
 */
public final class PasswordResetManager {

    private static final String TAG = "PasswordResetManager";
    public static final String CHANNEL_ID = "fishbowl_password_reset_channel";
    public static final int NOTIFICATION_ID = 2048;
    public static final long EXPIRATION_DURATION_MS = 5 * 60 * 1000L; // 5 minutes

    private static volatile PasswordResetManager instance;

    private final Context appContext;
    private final ScheduledExecutorService scheduler;
    private final Set<String> processedPins = new HashSet<>();
    private FileObserver fileObserver;
    private boolean isMonitoring = false;

    private PasswordResetManager(Context context) {
        this.appContext = context.getApplicationContext();
        this.scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "FishbowlPasswordResetWatcher");
            t.setDaemon(true);
            return t;
        });
        createNotificationChannel();
    }

    public static PasswordResetManager getInstance(Context context) {
        if (instance == null) {
            synchronized (PasswordResetManager.class) {
                if (instance == null) {
                    instance = new PasswordResetManager(context);
                }
            }
        }
        return instance;
    }

    private void createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationManager nm = (NotificationManager) appContext.getSystemService(Context.NOTIFICATION_SERVICE);
            if (nm != null) {
                NotificationChannel channel = new NotificationChannel(
                        CHANNEL_ID,
                        "Fishbowl Password Reset",
                        NotificationManager.IMPORTANCE_HIGH
                );
                channel.setDescription("Jellyfin Password Reset PIN notifications");
                channel.enableVibration(true);
                channel.setShowBadge(true);
                nm.createNotificationChannel(channel);
            }
        }
    }

    /**
     * Starts continuous monitoring for Jellyfin password reset token files.
     */
    public synchronized void startMonitoring() {
        if (isMonitoring) return;
        isMonitoring = true;

        // Periodic background poll every 3 seconds to ensure reliability across all Android versions
        scheduler.scheduleWithFixedDelay(this::checkAndProcessTokens, 1, 3, TimeUnit.SECONDS);

        // FileObserver for instantaneous inotify dispatch
        try {
            File jfShareDir = getJellyfinShareDir();
            if (jfShareDir.exists()) {
                fileObserver = new FileObserver(jfShareDir.getAbsolutePath(), FileObserver.CREATE | FileObserver.MOVED_TO | FileObserver.CLOSE_WRITE) {
                    @Override
                    public void onEvent(int event, String path) {
                        if (path != null && path.startsWith("passwordreset") && path.endsWith(".json")) {
                            scheduler.execute(PasswordResetManager.this::checkAndProcessTokens);
                        }
                    }
                };
                fileObserver.startWatching();
            }
        } catch (Throwable t) {
            Log.w(TAG, "FileObserver initialization skipped: " + t.getMessage());
        }

        // Clean up any stale files on startup
        scheduler.execute(this::cleanupExpiredPasswordFiles);
    }

    /**
     * Stops monitoring.
     */
    public synchronized void stopMonitoring() {
        if (!isMonitoring) return;
        isMonitoring = false;
        if (fileObserver != null) {
            try {
                fileObserver.stopWatching();
            } catch (Throwable ignored) {}
            fileObserver = null;
        }
    }

    /**
     * Checks immediately for pending password reset tokens.
     */
    public void checkOnce() {
        scheduler.execute(this::checkAndProcessTokens);
    }

    private File getJellyfinShareDir() {
        return new File(TermuxConstants.TERMUX_HOME_DIR, ".local/share/jellyfin");
    }

    public static File getPasswordDirectory() {
        File fishbowlRoot = new File(Environment.getExternalStorageDirectory(), "Fishbowl");
        File passDir = new File(fishbowlRoot, "password");
        if (!passDir.exists()) {
            passDir.mkdirs();
        }
        return passDir;
    }

    private void checkAndProcessTokens() {
        try {
            cleanupExpiredPasswordFiles();

            File jfDir = getJellyfinShareDir();
            if (!jfDir.exists() || !jfDir.isDirectory()) return;

            File[] resetFiles = jfDir.listFiles((d, name) -> name != null && name.startsWith("passwordreset") && name.endsWith(".json"));
            if (resetFiles == null || resetFiles.length == 0) return;

            for (File rf : resetFiles) {
                processResetJsonFile(rf);
            }
        } catch (Throwable t) {
            Log.e(TAG, "Error checking password reset tokens", t);
        }
    }

    private void processResetJsonFile(File rf) {
        try {
            StringBuilder sb = new StringBuilder();
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(new FileInputStream(rf), StandardCharsets.UTF_8))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    sb.append(line);
                }
            }

            JSONObject json = new JSONObject(sb.toString());
            String pin = json.optString("Pin", "").trim();
            String userName = json.optString("UserName", "").trim();
            String expiration = json.optString("ExpirationDate", "").trim();

            if (pin.isEmpty()) return;

            synchronized (processedPins) {
                if (processedPins.contains(pin)) {
                    return;
                }
                processedPins.add(pin);
            }

            Log.i(TAG, "Active Jellyfin Password Reset PIN detected: " + pin + " for user: " + userName);

            // 1. Write PIN to /sdcard/Fishbowl/password/jellyfin_password_reset_pin.txt
            File passwordDir = getPasswordDirectory();
            File pinTextFile = new File(passwordDir, "jellyfin_password_reset_pin.txt");
            File userPinTextFile = new File(passwordDir, "jellyfin_password_reset_" + userName + ".txt");

            String content = "==================================================\n"
                    + "Fishbowl Jellyfin Password Reset PIN\n"
                    + "==================================================\n"
                    + "Username:   " + userName + "\n"
                    + "Reset PIN:  " + pin + "\n"
                    + "Expires:    " + expiration + "\n"
                    + "Generated:  " + new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(new Date()) + "\n"
                    + "\n"
                    + "NOTE: This file will be automatically deleted in 5 minutes\n"
                    + "to protect account security and prevent storage buildup.\n"
                    + "==================================================\n";

            writeTextFile(pinTextFile, content);
            writeTextFile(userPinTextFile, content);

            // 2. Schedule deletion within 5 minutes
            scheduleFileDeletion(pinTextFile, EXPIRATION_DURATION_MS);
            scheduleFileDeletion(userPinTextFile, EXPIRATION_DURATION_MS);

            // 3. Post Dedicated System Notification with 'COPY PIN' Action
            postSystemNotification(pin, userName);

        } catch (Throwable t) {
            Log.e(TAG, "Failed to process password reset file: " + rf.getName(), t);
        }
    }

    private void writeTextFile(File file, String content) {
        try (FileOutputStream fos = new FileOutputStream(file)) {
            fos.write(content.getBytes(StandardCharsets.UTF_8));
            fos.flush();
        } catch (Throwable t) {
            Log.e(TAG, "Failed writing reset PIN file: " + file.getAbsolutePath(), t);
        }
    }

    private void scheduleFileDeletion(File file, long delayMs) {
        scheduler.schedule(() -> {
            try {
                if (file.exists()) {
                    boolean deleted = file.delete();
                    Log.i(TAG, "Auto-deleted temporary PIN file: " + file.getName() + " (success=" + deleted + ")");
                }
            } catch (Throwable ignored) {}
        }, delayMs, TimeUnit.MILLISECONDS);
    }

    /**
     * Purges temporary password reset files older than 5 minutes to prevent storage accumulation.
     */
    public void cleanupExpiredPasswordFiles() {
        try {
            File passwordDir = getPasswordDirectory();
            if (passwordDir.exists() && passwordDir.isDirectory()) {
                File[] files = passwordDir.listFiles();
                if (files != null) {
                    long now = System.currentTimeMillis();
                    for (File f : files) {
                        if (now - f.lastModified() > EXPIRATION_DURATION_MS) {
                            f.delete();
                        }
                    }
                }
            }

            // Also check internal Jellyfin share directory for expired reset json files
            File jfDir = getJellyfinShareDir();
            if (jfDir.exists() && jfDir.isDirectory()) {
                File[] resetFiles = jfDir.listFiles((d, name) -> name != null && name.startsWith("passwordreset") && name.endsWith(".json"));
                if (resetFiles != null) {
                    long now = System.currentTimeMillis();
                    for (File rf : resetFiles) {
                        if (now - rf.lastModified() > EXPIRATION_DURATION_MS + 60_000L) {
                            rf.delete();
                        }
                    }
                }
            }
        } catch (Throwable ignored) {}
    }

    private void postSystemNotification(String pin, String userName) {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                if (ContextCompat.checkSelfPermission(appContext, android.Manifest.permission.POST_NOTIFICATIONS)
                        != PackageManager.PERMISSION_GRANTED) {
                    Log.w(TAG, "POST_NOTIFICATIONS permission not granted. PIN written to file only.");
                    return;
                }
            }

            // Intent to Copy PIN directly to clipboard
            Intent copyIntent = new Intent(appContext, CopyPinReceiver.class);
            copyIntent.setAction(CopyPinReceiver.ACTION_COPY_PIN);
            copyIntent.putExtra(CopyPinReceiver.EXTRA_PIN, pin);
            copyIntent.putExtra(CopyPinReceiver.EXTRA_NOTIFICATION_ID, NOTIFICATION_ID);

            int pFlags = PendingIntent.FLAG_UPDATE_CURRENT;
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                pFlags |= PendingIntent.FLAG_IMMUTABLE;
            }

            PendingIntent copyPendingIntent = PendingIntent.getBroadcast(
                    appContext,
                    101,
                    copyIntent,
                    pFlags
            );

            // Intent to open Fishbowl Activity if user taps notification body
            Intent activityIntent = new Intent(appContext, FishbowlActivity.class);
            activityIntent.setFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP | Intent.FLAG_ACTIVITY_CLEAR_TOP);
            activityIntent.putExtra("focus_password_pin", pin);

            PendingIntent activityPendingIntent = PendingIntent.getActivity(
                    appContext,
                    102,
                    activityIntent,
                    pFlags
            );

            String title = "Jellyfin Password Reset PIN: " + pin;
            String shortText = "User: " + userName + " | PIN: " + pin + " (Expires in 5 min)";
            String detailedText = "Username: " + userName + "\n"
                    + "Reset PIN: " + pin + "\n"
                    + "Saved in: /sdcard/Fishbowl/password/\n"
                    + "Expires in 5 minutes. Tap 'COPY PIN' below.";

            NotificationCompat.Builder builder = new NotificationCompat.Builder(appContext, CHANNEL_ID)
                    .setSmallIcon(R.drawable.ic_fishbowl_logo)
                    .setContentTitle(title)
                    .setContentText(shortText)
                    .setStyle(new NotificationCompat.BigTextStyle().bigText(detailedText))
                    .setPriority(NotificationCompat.PRIORITY_HIGH)
                    .setCategory(NotificationCompat.CATEGORY_ALARM)
                    .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
                    .setAutoCancel(true)
                    .setTimeoutAfter(EXPIRATION_DURATION_MS)
                    .setContentIntent(activityPendingIntent)
                    .addAction(android.R.drawable.ic_menu_save, "COPY PIN", copyPendingIntent);

            NotificationManagerCompat.from(appContext).notify(NOTIFICATION_ID, builder.build());
            Log.i(TAG, "Dedicated Password Reset PIN system notification posted successfully!");

        } catch (Throwable t) {
            Log.e(TAG, "Failed posting password reset notification", t);
        }
    }
}
