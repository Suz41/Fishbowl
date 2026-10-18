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
import java.util.Enumeration;
import java.util.List;

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
    public  static final String ACTION_STOP           = "com.fishbowl.app.STOP";
    public  static final String ACTION_STOP_ALL       = "com.fishbowl.app.STOP_ALL";
    public  static final String ACTION_KEEP_ALIVE     = "com.fishbowl.app.KEEP_ALIVE";
    public  static final String CHANNEL_ID            = "jellyfin_server";
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
        promoteToForeground(buildNotification("Fishbowl Media Stack", "Starting services...", false));
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent != null) {
            String action = intent.getAction();
            if (ACTION_START_JELLYFIN.equals(action) || ACTION_START.equals(action)) {
                Log.i(TAG, "Service received START_JELLYFIN - launching Jellyfin server only");
                setStackActivePref(true);
                hasStarted = true;
                acquireLocks();
                promoteToForeground(buildNotification("Fishbowl Media Stack", "Starting Jellyfin...", false));
                controller.start(getApplicationContext());
                updateNotificationState();
            } else if (ACTION_STOP_JELLYFIN.equals(action)) {
                Log.i(TAG, "Service received STOP_JELLYFIN - stopping Jellyfin server only");
                controller.stop();
                updateNotificationState();
            } else if (ACTION_START_ALL.equals(action)) {
                Log.i(TAG, "Service received START_ALL - launching all media stack services");
                setStackActivePref(true);
                hasStarted = true;
                acquireLocks();
                promoteToForeground(buildNotification("Fishbowl Media Stack", "Starting stack...", false));
                if (serviceManager != null) {
                    serviceManager.startAllServices();
                } else {
                    controller.start(getApplicationContext());
                }
                updateNotificationState();
            } else if (ACTION_STOP_ALL.equals(action) || ACTION_STOP.equals(action)) {
                Log.i(TAG, "Service received STOP_ALL - stopping all media stack services");
                setStackActivePref(false);
                hasStarted = false;
                releaseLocks();
                if (serviceManager != null) {
                    serviceManager.stopAllServices();
                }
                controller.stop();
                updateNotificationState();
                stopSelf();
            } else if (ACTION_KEEP_ALIVE.equals(action)) {
                hasStarted = true;
                acquireLocks();
                updateNotificationState();
            }
        } else {
            // Intent is null: Restarted by Android OS after Low Memory Killer (START_STICKY)
            boolean wasActive = isStackActivePref();
            Log.i(TAG, "JellyfinServerService restarted by Android OS (intent=null, wasActive=" + wasActive + ")");
            if (wasActive) {
                hasStarted = true;
                acquireLocks();
                promoteToForeground(buildNotification("Fishbowl Media Stack", "Restoring services after OS kill...", false));
                if (serviceManager != null) {
                    serviceManager.startAllServices();
                } else {
                    controller.start(getApplicationContext());
                }
                updateNotificationState();
            } else {
                // User previously stopped services; dismiss any transient notification
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
            List<String> activeServices = new ArrayList<>();

            if (jfState == JellyfinController.State.RUNNING) {
                activeServices.add("Jellyfin");
            }
            if (serviceManager != null) {
                for (ManagedService ms : serviceManager.getAllServices()) {
                    if (!ms.getManifest().isCore() && ms.isRunning()) {
                        activeServices.add(ms.getDisplayName());
                    }
                }
            }

            boolean isInitializing = (jfState == JellyfinController.State.INITIALIZING);
            boolean isStarting = (jfState == JellyfinController.State.STARTING || isInitializing);
            boolean anyRunning = !activeServices.isEmpty();
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
                String cleanEp = fullUrl.replaceFirst("^https?://", "");

                String title;
                String shortText;
                StringBuilder expanded = new StringBuilder();

                if (isInitializing) {
                    int pct = controller != null ? controller.getBootstrapProgressPercent() : 0;
                    String bMsg = controller != null ? controller.getBootstrapProgressMessage() : "Extracting runtime...";
                    int displayPct = Math.max(5, pct);
                    title = "Fishbowl • Setup (" + displayPct + "%)";
                    shortText = (bMsg != null && !bMsg.isEmpty()) ? bMsg : "Configuring server environment...";
                    expanded.append("Initial installation in progress (").append(displayPct).append("%)\n");
                    expanded.append(shortText).append("\n\n");
                    expanded.append("Please keep Fishbowl open while runtime setup completes.");
                } else if (isStarting && activeServices.isEmpty()) {
                    title = "Fishbowl Media Server";
                    shortText = "Starting media stack...";
                    expanded.append("Initializing server runtime and companion services...");
                } else {
                    title = "Fishbowl Media Server";
                    shortText = activeServices.size() + (activeServices.size() == 1 ? " service" : " services") + " active • " + cleanEp;
                    expanded.append("Endpoint: ").append(fullUrl).append("\n\n");
                    expanded.append("Active Services (").append(activeServices.size()).append("):\n");
                    for (int i = 0; i < activeServices.size(); i++) {
                        expanded.append("• ").append(activeServices.get(i));
                        if (i < activeServices.size() - 1) expanded.append("\n");
                    }
                }

                Notification notification = buildNotification(title, shortText, expanded.toString(), anyRunning, isInitializing, isFailed);
                promoteToForeground(notification);
                com.fishbowl.app.widget.FishbowlWidgetProvider.updateAllWidgets(getApplicationContext());
            } else if (isFailed && hasStarted) {
                // Failure occurred - report real error instead of leaving "Starting..." or stopping silently
                serviceStartTimeMs = 0;
                releaseLocks();
                String err = (controller != null && controller.getLastError() != null) ? controller.getLastError() : "Server stopped unexpectedly";
                String title = (jfState == JellyfinController.State.CRASH_LOOP) ? "Fishbowl: Crash Loop" : "Fishbowl: Server Error";
                Notification failNotif = buildNotification(title, err, "Failure details: " + err + "\nTap to open Fishbowl dashboard.", false, false, true);
                promoteToForeground(failNotif);
                com.fishbowl.app.widget.FishbowlWidgetProvider.updateAllWidgets(getApplicationContext());
            } else if (hasStarted) {
                // Fully stopped cleanly after having been active
                serviceStartTimeMs = 0;
                hasStarted = false;
                releaseLocks();
                com.fishbowl.app.widget.FishbowlWidgetProvider.updateAllWidgets(getApplicationContext());
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
        return buildNotification(title, text, null, isRunning, false, false);
    }

    private Notification buildNotification(String title, String shortText, String expandedText, boolean isRunning) {
        return buildNotification(title, shortText, expandedText, isRunning, false, false);
    }

    private Notification buildNotification(String title, String shortText, String expandedText, boolean isRunning, boolean isInitializing, boolean isFailed) {
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
                .setSmallIcon(R.drawable.ic_notification_jellyfin)
                .setContentIntent(openPi)
                .setOngoing(true)
                .setOnlyAlertOnce(true)
                .setPriority(NotificationCompat.PRIORITY_LOW);

        if (isRunning) {
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
                    .setSummaryText(shortText)
                    .bigText(expandedText));
        }

        if (isRunning) {
            Intent webIntent = new Intent(this, JellyfinWebActivity.class);
            webIntent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_SINGLE_TOP);
            PendingIntent webPi = PendingIntent.getActivity(
                    this, 2, webIntent,
                    PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
            builder.addAction(R.drawable.ic_open_in_browser, "OPEN WEB UI", webPi);
            builder.addAction(R.drawable.ic_fishbowl_logo, "DASHBOARD", openPi);
            builder.addAction(R.drawable.ic_stop, "STOP ALL", stopPi);
        } else if (isFailed) {
            Intent retryIntent = new Intent(this, JellyfinServerService.class);
            retryIntent.setAction(ACTION_START);
            PendingIntent retryPi = PendingIntent.getService(
                    this, 3, retryIntent,
                    PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
            builder.addAction(R.drawable.ic_restart, "RETRY START", retryPi);
            builder.addAction(R.drawable.ic_fishbowl_logo, "DASHBOARD", openPi);
        } else {
            builder.addAction(R.drawable.ic_fishbowl_logo, "DASHBOARD", openPi);
            builder.addAction(R.drawable.ic_stop, "STOP", stopPi);
        }

        return builder.build();
    }

    private void createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel channel = new NotificationChannel(
                    CHANNEL_ID,
                    "Fishbowl Media Server",
                    NotificationManager.IMPORTANCE_LOW
            );
            channel.setDescription("Fishbowl media stack & background supervision");
            channel.setShowBadge(false);
            channel.enableVibration(false);
            channel.setSound(null, null);
            NotificationManager nm = getSystemService(NotificationManager.class);
            if (nm != null) nm.createNotificationChannel(channel);
        }
    }

    private String getLanAddress() {
        try {
            Enumeration<NetworkInterface> interfaces = NetworkInterface.getNetworkInterfaces();
            if (interfaces == null) return null;
            while (interfaces.hasMoreElements()) {
                NetworkInterface intf = interfaces.nextElement();
                if (!intf.isUp() || intf.isLoopback()) continue;
                Enumeration<java.net.InetAddress> addrs = intf.getInetAddresses();
                while (addrs.hasMoreElements()) {
                    java.net.InetAddress addr = addrs.nextElement();
                    if (!addr.isLoopbackAddress() && addr instanceof Inet4Address) {
                        return "http://" + addr.getHostAddress() + ":8096";
                    }
                }
            }
        } catch (Exception ignored) {}
        return null;
    }
}
