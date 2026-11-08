package com.fishbowl.app;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Intent;
import android.graphics.Color;
import android.os.Build;
import android.os.IBinder;
import android.os.PowerManager;
import android.util.Log;

import androidx.annotation.Nullable;
import androidx.core.app.NotificationCompat;

import com.fishbowl.app.model.ManagedService;
import com.fishbowl.app.model.ServiceState;
import com.fishbowl.app.services.MediaServiceManager;
import com.termux.R;

import java.net.Inet4Address;
import java.net.NetworkInterface;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Enumeration;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Universal Android Background Survival & Multi-Service Foreground Service.
 *
 * Keeps Fishbowl media server process alive across all Android devices & OEMs.
 * Holds PARTIAL_WAKE_LOCK as long as ANY service (Jellyfin, Transmission, Radarr,
 * Sonarr, Prowlarr, Seerr) is running or active.
 * Activity does not own service lifetime.
 */
public class JellyfinServerService extends Service implements JellyfinController.Listener, MediaServiceManager.ServiceListener {

    private static final String TAG              = "JellyfinServerService";
    public  static final String ACTION_START          = "com.fishbowl.app.START";
    public  static final String ACTION_START_JELLYFIN = "com.fishbowl.app.START_JELLYFIN";
    public  static final String ACTION_STOP_JELLYFIN  = "com.fishbowl.app.STOP_JELLYFIN";
    public  static final String ACTION_START_ALL      = "com.fishbowl.app.START_ALL";
    public  static final String ACTION_RESTART_ALL    = "com.fishbowl.app.RESTART_ALL";
    public  static final String ACTION_TOGGLE_SERVICE = "com.fishbowl.app.TOGGLE_SERVICE";
    public  static final String EXTRA_SERVICE_ID      = "service_id";
    public  static final String ACTION_STOP           = "com.fishbowl.app.STOP";
    public  static final String ACTION_STOP_ALL       = "com.fishbowl.app.STOP_ALL";
    public  static final String ACTION_KEEP_ALIVE     = "com.fishbowl.app.KEEP_ALIVE";
    public  static final String CHANNEL_ID            = "fishbowl_server_service";
    private static final int    NOTIFICATION_ID       = 1001;

    private JellyfinController controller;
    private MediaServiceManager serviceManager;
    private PowerManager.WakeLock wakeLock;
    private android.net.wifi.WifiManager.MulticastLock multicastLock;
    private final android.os.Handler lockHandler = new android.os.Handler(android.os.Looper.getMainLooper());
    private static final long DISCOVERY_WINDOW_MS = 60_000L; // 60 seconds SSDP/mDNS discovery window
    private final Runnable multicastReleaseRunnable = () -> {
        synchronized (JellyfinServerService.this) {
            if (multicastLock != null && multicastLock.isHeld()) {
                try {
                    multicastLock.release();
                    Log.i(TAG, "Wi-Fi MulticastLock released after discovery window (battery optimization)");
                } catch (Exception e) {
                    Log.w(TAG, "Failed to release MulticastLock: " + e.getMessage());
                }
            }
        }
    };
    private boolean hasStarted = false;
    private long serviceStartTimeMs = 0;

    private static final String PREF_NAME = "fishbowl_service";
    private static final String KEY_STACK_ACTIVE = "stack_active";

    private boolean isStackActivePref() {
        return getSharedPreferences(PREF_NAME, MODE_PRIVATE).getBoolean(KEY_STACK_ACTIVE, false);
    }

    private void setStackActivePref(boolean active) {
        getSharedPreferences(PREF_NAME, MODE_PRIVATE).edit().putBoolean(KEY_STACK_ACTIVE, active).apply();
    }

    // -- Lifecycle -------------------------------------------------------------

    @Override
    public void onCreate() {
        super.onCreate();
        createNotificationChannel();
        controller = JellyfinController.getInstance();
        controller.setContext(getApplicationContext());
        controller.addListener(this);
        serviceManager = MediaServiceManager.getInstance(this);
        if (serviceManager != null) {
            serviceManager.addListener(this);
        }
        acquireLocks();
        PasswordResetManager.getInstance(getApplicationContext()).startMonitoring();
        promoteToForeground(buildNotification("Jellyfin Server", "Starting...", false));
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent != null) {
            String action = intent.getAction();
            if (ACTION_START_JELLYFIN.equals(action) || ACTION_START.equals(action)) {
                Log.i(TAG, "Service received START_JELLYFIN - launching Jellyfin server only");
                if (serviceManager != null) {
                    serviceManager.recordServiceActive("jellyfin", true);
                }
                hasStarted = true;
                acquireLocks();
                promoteToForeground(buildNotification("Jellyfin Server", "Starting...", false));
                controller.start(getApplicationContext());
                updateNotificationState();
            } else if (ACTION_STOP_JELLYFIN.equals(action)) {
                Log.i(TAG, "Service received STOP_JELLYFIN - stopping Jellyfin server only");
                if (serviceManager != null) {
                    serviceManager.recordServiceActive("jellyfin", false);
                }
                controller.stop();
                updateNotificationState();
            } else if (ACTION_START_ALL.equals(action)) {
                Log.i(TAG, "Service received START_ALL - launching all media stack services");
                setStackActivePref(true);
                hasStarted = true;
                acquireLocks();
                promoteToForeground(buildNotification("Jellyfin Server", "Starting services...", false));
                if (serviceManager != null) {
                    serviceManager.startAllServices();
                } else {
                    controller.start(getApplicationContext());
                }
                updateNotificationState();
            } else if (ACTION_STOP_ALL.equals(action) || ACTION_STOP.equals(action)) {
                Log.i(TAG, "Service received STOP_ALL - stopping all media stack services");
                setStackActivePref(false);
                if (serviceManager != null) {
                    serviceManager.clearPersistedActiveServices();
                }
                hasStarted = false;
                releaseLocks();
                if (serviceManager != null) {
                    serviceManager.stopAllServices();
                }
                controller.stop();
                updateNotificationState();
                stopSelf();
            } else if (ACTION_RESTART_ALL.equals(action)) {
                Log.i(TAG, "Service received RESTART_ALL - restarting all media stack services");
                hasStarted = true;
                acquireLocks();
                promoteToForeground(buildNotification("Fishbowl Stack", "Restarting services...", false));
                if (serviceManager != null) {
                    serviceManager.stopAllServices();
                }
                controller.stop();
                updateNotificationState();
                lockHandler.postDelayed(() -> {
                    if (serviceManager != null) {
                        serviceManager.startAllServices();
                    } else {
                        controller.start(getApplicationContext());
                    }
                    updateNotificationState();
                }, 1500);
            } else if (ACTION_TOGGLE_SERVICE.equals(action)) {
                String serviceId = intent.getStringExtra(EXTRA_SERVICE_ID);
                Log.i(TAG, "Service received TOGGLE_SERVICE for: " + serviceId);
                if ("jellyfin".equalsIgnoreCase(serviceId)) {
                    if (controller.getState() == JellyfinController.State.RUNNING) {
                        Log.i(TAG, "Stopping Jellyfin via service toggle");
                        if (serviceManager != null) serviceManager.recordServiceActive("jellyfin", false);
                        controller.stop();
                    } else {
                        Log.i(TAG, "Starting Jellyfin via service toggle");
                        hasStarted = true;
                        acquireLocks();
                        promoteToForeground(buildNotification("Jellyfin Server", "Starting...", false));
                        if (serviceManager != null) serviceManager.recordServiceActive("jellyfin", true);
                        controller.start(getApplicationContext());
                    }
                } else if (serviceManager != null && serviceId != null) {
                    com.fishbowl.app.model.ManagedService ms = serviceManager.getService(serviceId);
                    if (ms != null) {
                        if (ms.isRunning()) {
                            Log.i(TAG, "Stopping companion service via toggle: " + serviceId);
                            serviceManager.stopService(serviceId);
                        } else {
                            Log.i(TAG, "Starting companion service via toggle: " + serviceId);
                            hasStarted = true;
                            acquireLocks();
                            promoteToForeground(buildNotification("Fishbowl Services", "Starting " + ms.getDisplayName() + "...", false));
                            serviceManager.startService(serviceId, getApplicationContext());
                        }
                    }
                }
                updateNotificationState();
            } else if (ACTION_KEEP_ALIVE.equals(action)) {
                hasStarted = true;
                acquireLocks();
                updateNotificationState();
            }
        } else {
            // Intent is null: Restarted by Android OS after Low Memory Killer (START_STICKY)
            Set<String> activeServices = serviceManager != null ? serviceManager.getPersistedActiveServices() : Collections.emptySet();
            boolean wasActive = !activeServices.isEmpty() || isStackActivePref();
            Log.i(TAG, "JellyfinServerService restarted by Android OS after LMK (intent=null, activeServices=" + activeServices + ", wasActive=" + wasActive + ")");
            if (wasActive) {
                // LMK Revival: Fast background cold-start of server & auto-revival of all previously active services
                hasStarted = true;
                acquireLocks();
                promoteToForeground(buildNotification("Jellyfin Server", "Recovering...", false));
                controller.start(getApplicationContext());
                if (serviceManager != null && serviceManager.isCompanionAutoStartEnabled()) {
                    serviceManager.autoRevivePersistedServices(getApplicationContext());
                }
                updateNotificationState();
            } else {
                // User previously stopped services; dismiss any transient notification
                Log.i(TAG, "No services were active prior to OS kill; releasing resources and terminating service.");
                releaseLocks();
                try {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                        stopForeground(STOP_FOREGROUND_REMOVE);
                    } else {
                        stopForeground(true);
                    }
                    NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
                    if (nm != null) {
                        nm.cancel(NOTIFICATION_ID);
                    }
                } catch (Throwable ignored) {}
                stopSelf();
            }
        }

        return START_STICKY; // Restart service if killed by the OS
    }

    @Override
    public void onDestroy() {
        controller.removeListener(this);
        if (serviceManager != null) {
            serviceManager.removeListener(this);
        }
        PasswordResetManager.getInstance(getApplicationContext()).stopMonitoring();
        releaseLocks();
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                stopForeground(STOP_FOREGROUND_REMOVE);
            } else {
                stopForeground(true);
            }
            NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
            if (nm != null) {
                nm.cancel(NOTIFICATION_ID);
            }
        } catch (Throwable ignored) {}
        super.onDestroy();
    }

    @Override
    public void onTaskRemoved(Intent rootIntent) {
        Log.i(TAG, "Fishbowl task removed from recents - preserving foreground streaming & download service");
        super.onTaskRemoved(rootIntent);
    }

    @Nullable
    @Override
    public IBinder onBind(Intent intent) { return null; }

    // -- Listeners -------------------------------------------------------------

    @Override
    public void onServerChanged(final JellyfinController.State state) {
        updateNotificationState();
        FishbowlQuickSettingsTileService.requestTileUpdate(this);
    }

    @Override
    public void onBootstrapProgress(final String message, final int percent) {
        updateNotificationState();
    }

    @Override
    public void onServiceStateChanged(String serviceId, ServiceState state) {
        updateNotificationState();
    }

    private void updateNotificationState() {
        new android.os.Handler(android.os.Looper.getMainLooper()).post(() -> {
            JellyfinController.State jfState = controller != null ? controller.getState() : JellyfinController.State.STOPPED;
            boolean jfRunning = (jfState == JellyfinController.State.RUNNING);
            List<String> activeCompanions = new ArrayList<>();

            if (serviceManager != null) {
                for (ManagedService ms : serviceManager.getAllServices()) {
                    if (!ms.getManifest().isCore() && ms.isRunning()) {
                        activeCompanions.add(ms.getDisplayName());
                    }
                }
            }

            boolean isInitializing = (jfState == JellyfinController.State.INITIALIZING);
            boolean isStarting = (jfState == JellyfinController.State.STARTING || isInitializing);
            boolean anyRunning = jfRunning || !activeCompanions.isEmpty();
            boolean isFailed = (jfState == JellyfinController.State.FAILED || jfState == JellyfinController.State.CRASHED || jfState == JellyfinController.State.CRASH_LOOP);

            if (anyRunning) {
                if (serviceStartTimeMs == 0) {
                    serviceStartTimeMs = System.currentTimeMillis();
                }
            } else if (!isStarting) {
                serviceStartTimeMs = 0;
            }

            if (anyRunning || isStarting) {
                hasStarted = true;
                acquireLocks();

                String lan = getLanAddress();
                String fullUrl = (lan != null) ? lan : "http://127.0.0.1:8096";

                String title;
                String shortText;
                String expandedText;
                String subText;
                int iconRes;

                if (isInitializing) {
                    int pct = controller != null ? controller.getBootstrapProgressPercent() : 0;
                    String bMsg = controller != null ? controller.getBootstrapProgressMessage() : "Extracting runtime...";
                    int displayPct = Math.max(5, pct);
                    title = "Jellyfin Setup (" + displayPct + "%)";
                    shortText = (bMsg != null && !bMsg.isEmpty()) ? bMsg : "Configuring server environment...";
                    expandedText = shortText;
                    subText = displayPct + "%";
                    iconRes = R.drawable.ic_notification_jellyfin;
                } else if (isStarting && !anyRunning) {
                    title = "Jellyfin Server";
                    shortText = "Starting server...";
                    expandedText = "Starting server...";
                    subText = "Starting";
                    iconRes = R.drawable.ic_notification_jellyfin;
                } else if (jfRunning) {
                    title = "Jellyfin Server";
                    shortText = fullUrl;
                    if (!activeCompanions.isEmpty()) {
                        subText = "Online • " + activeCompanions.size() + " Active";
                        expandedText = fullUrl + "\nServices: " + android.text.TextUtils.join(", ", activeCompanions);
                    } else {
                        subText = "Online";
                        expandedText = fullUrl;
                    }
                    iconRes = R.drawable.ic_notification_jellyfin;
                } else {
                    // Only companions are running; Jellyfin is stopped
                    title = "Fishbowl Services";
                    shortText = activeCompanions.size() + " active: " + android.text.TextUtils.join(", ", activeCompanions);
                    expandedText = "Running services:\n• " + android.text.TextUtils.join("\n• ", activeCompanions);
                    subText = activeCompanions.size() + " Active";
                    iconRes = R.drawable.ic_fishbowl_logo;
                }

                Notification notification = buildNotification(title, shortText, expandedText, subText, iconRes, jfRunning, anyRunning, isInitializing, isFailed);
                promoteToForeground(notification);
                com.fishbowl.app.widget.FishbowlWidgetProvider.updateAllWidgets(getApplicationContext());
                com.fishbowl.app.widget.FishbowlServicesWidgetProvider.updateAllWidgets(getApplicationContext());
            } else if (isFailed && hasStarted) {
                // Failure occurred - report real error instead of leaving "Starting..." or stopping silently
                serviceStartTimeMs = 0;
                releaseLocks();
                String err = (controller != null && controller.getLastError() != null) ? controller.getLastError() : "Server stopped unexpectedly";
                String title = (jfState == JellyfinController.State.CRASH_LOOP) ? "Jellyfin: Crash Loop" : "Jellyfin: Server Error";
                Notification failNotif = buildNotification(title, err, err, "Error", R.drawable.ic_notification_jellyfin, false, false, false, true);
                promoteToForeground(failNotif);
                com.fishbowl.app.widget.FishbowlWidgetProvider.updateAllWidgets(getApplicationContext());
                com.fishbowl.app.widget.FishbowlServicesWidgetProvider.updateAllWidgets(getApplicationContext());
            } else if (hasStarted) {
                // Fully stopped cleanly after having been active
                serviceStartTimeMs = 0;
                hasStarted = false;
                releaseLocks();
                com.fishbowl.app.widget.FishbowlWidgetProvider.updateAllWidgets(getApplicationContext());
                com.fishbowl.app.widget.FishbowlServicesWidgetProvider.updateAllWidgets(getApplicationContext());
                try {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                        stopForeground(STOP_FOREGROUND_REMOVE);
                    } else {
                        stopForeground(true);
                    }
                    NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
                    if (nm != null) {
                        nm.cancel(NOTIFICATION_ID);
                    }
                } catch (Throwable ignored) {}
                stopSelf();
            }
        });
    }

    private void promoteToForeground(Notification notification) {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                try {
                    int type = android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK;
                    startForeground(NOTIFICATION_ID, notification, type);
                    return;
                } catch (Throwable t) {
                    Log.w(TAG, "startForeground with mediaPlayback type failed, falling back: " + t.getMessage());
                }
            }
            startForeground(NOTIFICATION_ID, notification);
        } catch (Throwable e) {
            Log.e(TAG, "startForeground error: " + e.getMessage(), e);
        }
    }

    // -- WakeLock & MulticastLock Management -----------------------------------

    private synchronized void acquireLocks() {
        if (wakeLock == null) {
            PowerManager pm = (PowerManager) getSystemService(POWER_SERVICE);
            if (pm != null) {
                wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "Fishbowl:ServerWakeLock");
                wakeLock.setReferenceCounted(false);
            }
        }
        if (wakeLock != null && !wakeLock.isHeld()) {
            wakeLock.acquire(24 * 60 * 60 * 1000L); // 24 hours
            Log.i(TAG, "CPU WakeLock acquired for Fishbowl media stack & background supervision");
        }

        acquireDiscoveryMulticastLock(DISCOVERY_WINDOW_MS);
    }

    public synchronized void acquireDiscoveryMulticastLock(long durationMs) {
        if (multicastLock == null) {
            try {
                android.net.wifi.WifiManager wm = (android.net.wifi.WifiManager) getApplicationContext().getSystemService(WIFI_SERVICE);
                if (wm != null) {
                    multicastLock = wm.createMulticastLock("Fishbowl:SmartTvMulticastLock");
                    multicastLock.setReferenceCounted(false);
                }
            } catch (Exception e) {
                Log.w(TAG, "Could not initialize Wi-Fi MulticastLock: " + e.getMessage());
            }
        }
        if (multicastLock != null) {
            try {
                if (!multicastLock.isHeld()) {
                    multicastLock.acquire();
                    Log.i(TAG, "Wi-Fi MulticastLock acquired for " + (durationMs / 1000) + "s discovery window");
                }
                lockHandler.removeCallbacks(multicastReleaseRunnable);
                lockHandler.postDelayed(multicastReleaseRunnable, durationMs);
            } catch (Exception e) {
                Log.w(TAG, "Failed to acquire MulticastLock: " + e.getMessage());
            }
        }
    }

    private synchronized void releaseLocks() {
        lockHandler.removeCallbacks(multicastReleaseRunnable);
        if (wakeLock != null && wakeLock.isHeld()) {
            wakeLock.release();
            Log.i(TAG, "CPU WakeLock released");
        }
        if (multicastLock != null && multicastLock.isHeld()) {
            try {
                multicastLock.release();
                Log.i(TAG, "Wi-Fi MulticastLock released");
            } catch (Exception e) {
                Log.w(TAG, "Failed to release MulticastLock: " + e.getMessage());
            }
        }
    }

    // -- Notification helpers --------------------------------------------------

    private Notification buildNotification(String title, String text, boolean isRunning) {
        return buildNotification(title, text, null, isRunning ? "Online" : "Starting", R.drawable.ic_notification_jellyfin, isRunning, isRunning, false, false);
    }

    private Notification buildNotification(String title, String shortText, String expandedText, boolean isRunning) {
        return buildNotification(title, shortText, expandedText, isRunning ? "Online" : "Starting", R.drawable.ic_notification_jellyfin, isRunning, isRunning, false, false);
    }

    private Notification buildNotification(String title, String shortText, String expandedText, boolean isRunning, boolean isInitializing, boolean isFailed) {
        String subText = isFailed ? "Error" : (isInitializing ? "Installing" : (isRunning ? "Online" : "Starting"));
        return buildNotification(title, shortText, expandedText, subText, R.drawable.ic_notification_jellyfin, isRunning, isRunning, isInitializing, isFailed);
    }

    private Notification buildNotification(String title, String shortText, String expandedText, String subText, int iconRes, boolean isJfRunning, boolean isAnyRunning, boolean isInitializing, boolean isFailed) {
        Intent openIntent = new Intent(this, FishbowlActivity.class);
        openIntent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        PendingIntent openPi = PendingIntent.getActivity(
                this, 0, openIntent,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);

        Intent stopIntent = new Intent(this, JellyfinServerService.class);
        stopIntent.setAction(ACTION_STOP);
        PendingIntent stopPi = PendingIntent.getService(
                this, 1, stopIntent,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);

        NotificationCompat.Builder builder = new NotificationCompat.Builder(this, CHANNEL_ID)
                .setContentTitle(title)
                .setContentText(shortText)
                .setSmallIcon(iconRes != 0 ? iconRes : R.drawable.ic_notification_jellyfin)
                .setContentIntent(openPi)
                .setOngoing(true)
                .setOnlyAlertOnce(true)
                .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
                .setPriority(NotificationCompat.PRIORITY_DEFAULT);

        if (subText != null && !subText.isEmpty()) {
            builder.setSubText(subText);
        }

        if (isAnyRunning) {
            builder.setColor(Color.parseColor("#10B981")); // Emerald green for active stack
            if (serviceStartTimeMs > 0) {
                builder.setWhen(serviceStartTimeMs);
                builder.setUsesChronometer(true);
            }
        } else if (isInitializing) {
            builder.setColor(Color.parseColor("#38BDF8")); // Electric sky blue for setup
            int pct = controller != null ? controller.getBootstrapProgressPercent() : 0;
            builder.setProgress(100, Math.max(5, pct), false);
        } else if (isFailed) {
            builder.setColor(Color.parseColor("#EF4444")); // Crimson for error
        } else {
            builder.setColor(Color.parseColor("#F59E0B")); // Amber for starting
        }

        if (expandedText != null && !expandedText.isEmpty()) {
            builder.setStyle(new NotificationCompat.BigTextStyle()
                    .setBigContentTitle(title)
                    .bigText(expandedText));
        }

        if (isJfRunning) {
            Intent webIntent = new Intent(this, JellyfinWebActivity.class);
            webIntent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_SINGLE_TOP);
            PendingIntent webPi = PendingIntent.getActivity(
                    this, 2, webIntent,
                    PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
            builder.addAction(R.drawable.ic_open_in_browser, "Open", webPi);
            builder.addAction(R.drawable.ic_stop, "Stop", stopPi);
        } else if (isAnyRunning) {
            // Companions running, but Jellyfin stopped
            builder.addAction(R.drawable.ic_fishbowl_logo, "Dashboard", openPi);
            builder.addAction(R.drawable.ic_stop, "Stop All", stopPi);
        } else if (isFailed) {
            Intent retryIntent = new Intent(this, JellyfinServerService.class);
            retryIntent.setAction(ACTION_START);
            PendingIntent retryPi = PendingIntent.getService(
                    this, 3, retryIntent,
                    PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
            builder.addAction(R.drawable.ic_restart, "Retry", retryPi);
            builder.addAction(R.drawable.ic_fishbowl_logo, "Dashboard", openPi);
        } else {
            builder.addAction(R.drawable.ic_stop, "Stop", stopPi);
        }

        return builder.build();
    }

    private void createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationManager nm = getSystemService(NotificationManager.class);
            if (nm != null) {
                try {
                    nm.deleteNotificationChannel("jellyfin_server");
                } catch (Throwable ignored) {}
            }
            NotificationChannel channel = new NotificationChannel(
                    CHANNEL_ID,
                    "Jellyfin Media Server",
                    NotificationManager.IMPORTANCE_DEFAULT
            );
            channel.setDescription("Jellyfin media server and background supervision");
            channel.setShowBadge(false);
            channel.enableVibration(false);
            channel.setSound(null, null);
            channel.setLockscreenVisibility(Notification.VISIBILITY_PUBLIC);
            if (nm != null) nm.createNotificationChannel(channel);
        }
    }

    private String getLanAddress() {
        return NetworkUtils.getLanAddress();
    }
}
