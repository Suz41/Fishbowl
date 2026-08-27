package com.jellyfin.droid;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Intent;
import android.os.Build;
import android.os.IBinder;
import android.os.PowerManager;
import android.util.Log;

import androidx.annotation.Nullable;
import androidx.core.app.NotificationCompat;

import com.termux.R;

import java.net.Inet4Address;
import java.net.NetworkInterface;
import java.util.Enumeration;

/**
 * Phase 12: Playback Hardened Foreground Service
 *
 * Keeps Jellyfin server process alive and manages a PARTIAL_WAKE_LOCK while RUNNING
 * to protect streaming playback & audio from CPU sleep / Doze throttling when the
 * screen locks or when the app moves to the background.
 */
public class JellyfinServerService extends Service implements JellyfinController.Listener {

    private static final String TAG              = "JellyfinServerService";
    public  static final String ACTION_START     = "com.jellyfin.droid.START";
    public  static final String ACTION_STOP      = "com.jellyfin.droid.STOP";
    public  static final String CHANNEL_ID       = "jellyfin_server";
    private static final int    NOTIFICATION_ID  = 1001;

    private JellyfinController controller;
    private PowerManager.WakeLock wakeLock;

    // ── Lifecycle ──────────────────────────────────────────────────────────────

    @Override
    public void onCreate() {
        super.onCreate();
        createNotificationChannel();
        controller = JellyfinController.getInstance();
        controller.addListener(this);
        promoteToForeground(buildNotification("Jellyfin Server", "Starting server..."));
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        // Always promote to foreground immediately to satisfy Android 12+ 5-second startForeground rule
        promoteToForeground(buildNotification("Jellyfin Server", "Starting server..."));

        if (intent != null) {
            String action = intent.getAction();
            if (ACTION_START.equals(action)) {
                Log.i(TAG, "Service received START");
                controller.start(getApplicationContext());
            } else if (ACTION_STOP.equals(action)) {
                Log.i(TAG, "Service received STOP");
                releaseWakeLock();
                controller.stop();
                stopSelf();
            }
        }

        return START_STICKY; // Restart service if killed by the OS
    }

    @Override
    public void onDestroy() {
        controller.removeListener(this);
        releaseWakeLock();
        super.onDestroy();
    }

    @Nullable
    @Override
    public IBinder onBind(Intent intent) { return null; }

    // ── JellyfinController.Listener ────────────────────────────────────────────

    @Override
    public void onServerChanged(final JellyfinController.State state) {
        new android.os.Handler(android.os.Looper.getMainLooper()).post(() -> {
            String title = "Jellyfin Server";
            String text;

            switch (state) {
                case RUNNING:
                    acquireWakeLock();
                    String lan = getLanAddress();
                    text = "Server is running" + (lan != null ? " (" + lan + ")" : " (http://127.0.0.1:8096)");
                    break;
                case STARTING:
                case INITIALIZING:
                    text = "Starting server...";
                    break;
                case STOPPING:
                    releaseWakeLock();
                    text = "Stopping server...";
                    break;
                case STOPPED:
                case CRASHED:
                case CRASH_LOOP:
                case FAILED:
                    releaseWakeLock();
                    stopSelf();
                    return;
                default:
                    text = "Server: " + state.name();
                    break;
            }

            Notification notification = buildNotification(title, text);
            promoteToForeground(notification);
        });
    }

    private void promoteToForeground(Notification notification) {
        try {
            startForeground(NOTIFICATION_ID, notification);
        } catch (Exception e) {
            Log.e(TAG, "startForeground error: " + e.getMessage(), e);
        }
    }

    // ── WakeLock Management ───────────────────────────────────────────────────

    private synchronized void acquireWakeLock() {
        if (wakeLock == null) {
            PowerManager pm = (PowerManager) getSystemService(POWER_SERVICE);
            if (pm != null) {
                wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "JellyfinDroid:ServerWakeLock");
                wakeLock.setReferenceCounted(false);
            }
        }
        if (wakeLock != null && !wakeLock.isHeld()) {
            // 10-minute max duration per acquire step — automatically renewed on state update
            wakeLock.acquire(10 * 60 * 1000L);
            Log.i(TAG, "CPU WakeLock acquired for server & streaming execution (with 10-min safety timeout)");
        }
    }

    private synchronized void releaseWakeLock() {
        if (wakeLock != null && wakeLock.isHeld()) {
            wakeLock.release();
            Log.i(TAG, "CPU WakeLock released");
        }
    }

    // ── Notification helpers ───────────────────────────────────────────────────

    private Notification buildNotification(String title, String text) {
        // Tap notification → open main activity
        Intent openIntent = new Intent(this, JellyfinDroidActivity.class);
        openIntent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        PendingIntent openPi = PendingIntent.getActivity(
                this, 0, openIntent,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);

        // STOP action
        Intent stopIntent = new Intent(this, JellyfinServerService.class);
        stopIntent.setAction(ACTION_STOP);
        PendingIntent stopPi = PendingIntent.getService(
                this, 1, stopIntent,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);

        return new NotificationCompat.Builder(this, CHANNEL_ID)
                .setContentTitle(title)
                .setContentText(text)
                .setSmallIcon(R.drawable.ic_notification_jellyfin)
                .setContentIntent(openPi)
                .setOngoing(true)
                .addAction(R.drawable.ic_dns, "STOP SERVER", stopPi)
                .setPriority(NotificationCompat.PRIORITY_LOW)
                .build();
    }

    private void createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel channel = new NotificationChannel(
                    CHANNEL_ID,
                    "Jellyfin Server",
                    NotificationManager.IMPORTANCE_LOW
            );
            channel.setDescription("JellyfinDroid server status");
            channel.setShowBadge(false);
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
