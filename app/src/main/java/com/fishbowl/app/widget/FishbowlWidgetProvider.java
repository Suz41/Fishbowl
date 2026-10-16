package com.fishbowl.app.widget;

import android.app.PendingIntent;
import android.appwidget.AppWidgetManager;
import android.appwidget.AppWidgetProvider;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.graphics.Color;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.widget.RemoteViews;

import com.fishbowl.app.JellyfinController;
import com.fishbowl.app.FishbowlActivity;
import com.fishbowl.app.JellyfinServerService;
import com.fishbowl.app.JellyfinWebActivity;
import com.fishbowl.app.model.ManagedService;
import com.fishbowl.app.services.MediaServiceManager;
import com.termux.R;

import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.NetworkInterface;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Android Home Screen Widget Provider for Fishbowl.
 * Provides glanceable server status, live endpoint details, active companion services,
 * real-time runtime/uptime counters, and quick controls (START, RESTART, STOP, WEB UI).
 */
public class FishbowlWidgetProvider extends AppWidgetProvider {

    private static final String TAG = "FishbowlWidgetProvider";

    private static class DownloadSnapshot {
        final boolean isDownloading;
        final int dlCount;
        final long totalRate;
        final String topName;
        final double topPct;

        DownloadSnapshot(boolean isDownloading, int dlCount, long totalRate, String topName, double topPct) {
            this.isDownloading = isDownloading;
            this.dlCount = dlCount;
            this.totalRate = totalRate;
            this.topName = topName;
            this.topPct = topPct;
        }
    }

    private static volatile DownloadSnapshot sLastDownloadSnapshot = null;
    private static final Handler sWidgetHandler = new Handler(Looper.getMainLooper());
    private static Runnable sDownloadRefreshRunnable = null;
    private static final ExecutorService sAsyncExecutor = Executors.newSingleThreadExecutor();
    private static volatile boolean sIsPollingDownloads = false;

    public static final String ACTION_START_JELLYFIN = "com.fishbowl.app.widget.ACTION_START_JELLYFIN";
    public static final String ACTION_START_ALL      = "com.fishbowl.app.widget.ACTION_START_ALL";
    public static final String ACTION_RESTART_ALL    = "com.fishbowl.app.widget.ACTION_RESTART_ALL";
    public static final String ACTION_STOP_ALL       = "com.fishbowl.app.widget.ACTION_STOP_ALL";
    public static final String ACTION_PIN_WIDGET     = "com.fishbowl.app.widget.ACTION_PIN_WIDGET";
    public static final String ACTION_TOGGLE_SERVER  = "com.fishbowl.app.widget.ACTION_TOGGLE_SERVER";
    public static final String ACTION_WIDGET_UPDATE  = "com.fishbowl.app.widget.ACTION_WIDGET_UPDATE";

    @Override
    public void onUpdate(Context context, AppWidgetManager appWidgetManager, int[] appWidgetIds) {
        for (int appWidgetId : appWidgetIds) {
            updateAppWidget(context, appWidgetManager, appWidgetId);
        }
    }

    public static void updateAppWidget(Context context, AppWidgetManager appWidgetManager, int appWidgetId) {
        RemoteViews views = new RemoteViews(context.getPackageName(), R.layout.widget_fishbowl_status);

        JellyfinController controller = JellyfinController.getInstance();
        MediaServiceManager serviceManager = MediaServiceManager.getInstance(context);

        JellyfinController.State jfState = (controller != null) ? controller.getState() : JellyfinController.State.STOPPED;

        // Build list of active services and detailed companion runtime info
        List<String> runningServices = new ArrayList<>();
        List<String> serviceRuntimeDetails = new ArrayList<>();

        long serverUptimeSec = 0;
        if (controller != null && jfState == JellyfinController.State.RUNNING) {
            serverUptimeSec = controller.getServerHealth().uptimeSeconds;
            runningServices.add("Jellyfin");
            String jfUp = (serverUptimeSec > 0) ? formatShortTime(serverUptimeSec) : "up";
            serviceRuntimeDetails.add("● Jellyfin:8096 (" + jfUp + ")");
        }

        int totalInstalled = 1; // Jellyfin is core installed
        if (serviceManager != null) {
            for (ManagedService ms : serviceManager.getAllServices()) {
                if (!ms.getManifest().isCore()) {
                    if (ms.isInstalled()) {
                        totalInstalled++;
                    }
                    if (ms.isRunning()) {
                        runningServices.add(ms.getDisplayName());
                        long compSec = ms.getUptimeSeconds();
                        if (compSec <= 0 && serverUptimeSec > 0) {
                            compSec = serverUptimeSec;
                        } else if (compSec > serverUptimeSec) {
                            serverUptimeSec = compSec;
                        }
                        String compUp = (compSec > 0) ? formatShortTime(compSec) : "up";
                        serviceRuntimeDetails.add("● " + ms.getDisplayName() + ":" + ms.getManifest().getDefaultPort() + " (" + compUp + ")");
                    }
                }
            }
        }

        boolean anyRunning = !runningServices.isEmpty();
        boolean isStarting = (jfState == JellyfinController.State.STARTING || jfState == JellyfinController.State.INITIALIZING);
        boolean isStopping = (jfState == JellyfinController.State.STOPPING);
        String lanEndpoint = getLanAddress();

        // 1. Header: Uptime & Status Beacon
        if (anyRunning) {
            views.setImageViewResource(R.id.widget_dot, R.drawable.widget_status_dot_green);
            views.setTextViewText(R.id.widget_status_text, "RUNNING");
            views.setTextColor(R.id.widget_status_text, Color.parseColor("#10B981"));

            views.setTextViewText(R.id.widget_uptime, formatUptime(serverUptimeSec));
            views.setTextColor(R.id.widget_uptime, Color.parseColor("#34D399"));

            views.setTextViewText(R.id.widget_text_endpoint, "http://" + lanEndpoint);
            views.setTextColor(R.id.widget_text_endpoint, Color.parseColor("#38BDF8"));

            String svcsHeader = runningServices.size() + " / " + totalInstalled + " Services Online";
            views.setTextViewText(R.id.widget_text_services, svcsHeader);
            views.setTextColor(R.id.widget_text_services, Color.parseColor("#E4E4E7"));

            // 2-column or formatted companion breakdown with individual runtime
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < serviceRuntimeDetails.size(); i++) {
                sb.append(serviceRuntimeDetails.get(i));
                if (i % 2 == 1 && i < serviceRuntimeDetails.size() - 1) {
                    sb.append("\n");
                } else if (i < serviceRuntimeDetails.size() - 1) {
                    sb.append("   ");
                }
            }
            views.setTextViewText(R.id.widget_text_services_detail, sb.toString());
            views.setTextColor(R.id.widget_text_services_detail, Color.parseColor("#A1A1AA"));

            // Action buttons states
            views.setTextColor(R.id.widget_btn_start, Color.parseColor("#52525B"));   // dimmed
            views.setTextColor(R.id.widget_btn_restart, Color.parseColor("#F59E0B")); // active amber
            views.setTextColor(R.id.widget_btn_stop, Color.parseColor("#E11D48"));    // active crimson
            views.setTextColor(R.id.widget_btn_web, Color.parseColor("#38BDF8"));     // active sky-blue
        } else if (isStarting) {
            views.setImageViewResource(R.id.widget_dot, R.drawable.widget_status_dot_amber);
            views.setTextViewText(R.id.widget_status_text, "STARTING...");
            views.setTextColor(R.id.widget_status_text, Color.parseColor("#F59E0B"));

            views.setTextViewText(R.id.widget_uptime, "BOOTING");
            views.setTextColor(R.id.widget_uptime, Color.parseColor("#F59E0B"));

            views.setTextViewText(R.id.widget_text_endpoint, "Initializing media stack...");
            views.setTextColor(R.id.widget_text_endpoint, Color.parseColor("#A1A1AA"));

            views.setTextViewText(R.id.widget_text_services, "Services starting up...");
            views.setTextColor(R.id.widget_text_services, Color.parseColor("#71717A"));

            views.setTextViewText(R.id.widget_text_services_detail, "Binding ports & launching daemons");
            views.setTextColor(R.id.widget_text_services_detail, Color.parseColor("#52525B"));

            views.setTextColor(R.id.widget_btn_start, Color.parseColor("#52525B"));
            views.setTextColor(R.id.widget_btn_restart, Color.parseColor("#F59E0B"));
            views.setTextColor(R.id.widget_btn_stop, Color.parseColor("#E11D48"));
            views.setTextColor(R.id.widget_btn_web, Color.parseColor("#52525B"));
        } else if (isStopping) {
            views.setImageViewResource(R.id.widget_dot, R.drawable.widget_status_dot_amber);
            views.setTextViewText(R.id.widget_status_text, "STOPPING...");
            views.setTextColor(R.id.widget_status_text, Color.parseColor("#F97316"));

            views.setTextViewText(R.id.widget_uptime, "--:--:--");
            views.setTextColor(R.id.widget_uptime, Color.parseColor("#52525B"));

            views.setTextViewText(R.id.widget_text_endpoint, "Terminating services...");
            views.setTextColor(R.id.widget_text_endpoint, Color.parseColor("#71717A"));

            views.setTextViewText(R.id.widget_text_services, "Flushing sessions & state");
            views.setTextColor(R.id.widget_text_services, Color.parseColor("#71717A"));

            views.setTextViewText(R.id.widget_text_services_detail, "Releasing process locks and network sockets");
            views.setTextColor(R.id.widget_text_services_detail, Color.parseColor("#52525B"));

            views.setTextColor(R.id.widget_btn_start, Color.parseColor("#52525B"));
            views.setTextColor(R.id.widget_btn_restart, Color.parseColor("#52525B"));
            views.setTextColor(R.id.widget_btn_stop, Color.parseColor("#52525B"));
            views.setTextColor(R.id.widget_btn_web, Color.parseColor("#52525B"));
        } else {
            views.setImageViewResource(R.id.widget_dot, R.drawable.widget_status_dot_red);
            views.setTextViewText(R.id.widget_status_text, "STOPPED");
            views.setTextColor(R.id.widget_status_text, Color.parseColor("#71717A"));

            views.setTextViewText(R.id.widget_uptime, "--:--:--");
            views.setTextColor(R.id.widget_uptime, Color.parseColor("#52525B"));

            views.setTextViewText(R.id.widget_text_endpoint, "Server offline");
            views.setTextColor(R.id.widget_text_endpoint, Color.parseColor("#71717A"));

            views.setTextViewText(R.id.widget_text_services, "All services offline (0/" + totalInstalled + " Active)");
            views.setTextColor(R.id.widget_text_services, Color.parseColor("#52525B"));

            views.setTextViewText(R.id.widget_text_services_detail, "Tap START to boot Jellyfin & companion daemons");
            views.setTextColor(R.id.widget_text_services_detail, Color.parseColor("#3F3F46"));

            views.setTextColor(R.id.widget_btn_start, Color.parseColor("#FFFFFF"));
            views.setTextColor(R.id.widget_btn_restart, Color.parseColor("#52525B"));
            views.setTextColor(R.id.widget_btn_stop, Color.parseColor("#52525B"));
            views.setTextColor(R.id.widget_btn_web, Color.parseColor("#52525B"));
        }

        // Live Download Status Beacon (Visible ONLY when active downloading)
        DownloadSnapshot dlSnap = sLastDownloadSnapshot;
        if (dlSnap != null && dlSnap.isDownloading) {
            views.setViewVisibility(R.id.widget_download_container, android.view.View.VISIBLE);
            String progressStr = Math.round(dlSnap.topPct * 100) + "%";
            String label = dlSnap.dlCount + " DL (" + progressStr + ") " + (dlSnap.topName != null ? dlSnap.topName : "");
            views.setTextViewText(R.id.widget_download_text, label);
            views.setTextViewText(R.id.widget_download_speed, formatSpeed(dlSnap.totalRate));
        } else {
            views.setViewVisibility(R.id.widget_download_container, android.view.View.GONE);
        }

        // Manage background download polling if Transmission is running
        if (serviceManager != null) {
            ManagedService trSvc = serviceManager.getService("transmission");
            if (trSvc != null && trSvc.isRunning()) {
                startDownloadPollingIfRunning(context.getApplicationContext());
            } else {
                stopDownloadPolling();
            }
        }

        int flags = PendingIntent.FLAG_UPDATE_CURRENT;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            flags |= PendingIntent.FLAG_IMMUTABLE;
        }

        // Tap entire card -> open main Fishbowl dashboard
        Intent openAppIntent = new Intent(context, FishbowlActivity.class);
        openAppIntent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_SINGLE_TOP);
        PendingIntent piApp = PendingIntent.getActivity(context, 100, openAppIntent, flags);
        views.setOnClickPendingIntent(R.id.widget_root, piApp);

        // Tap START -> broadcast ACTION_START_JELLYFIN (starts ONLY Jellyfin as requested)
        Intent startIntent = new Intent(context, FishbowlWidgetProvider.class);
        startIntent.setAction(ACTION_START_JELLYFIN);
        PendingIntent piStart = PendingIntent.getBroadcast(context, 104, startIntent, flags);
        views.setOnClickPendingIntent(R.id.widget_btn_start, piStart);

        // Tap RESTART -> broadcast ACTION_RESTART_ALL
        Intent restartIntent = new Intent(context, FishbowlWidgetProvider.class);
        restartIntent.setAction(ACTION_RESTART_ALL);
        PendingIntent piRestart = PendingIntent.getBroadcast(context, 105, restartIntent, flags);
        views.setOnClickPendingIntent(R.id.widget_btn_restart, piRestart);

        // Tap STOP -> broadcast ACTION_STOP_ALL
        Intent stopIntent = new Intent(context, FishbowlWidgetProvider.class);
        stopIntent.setAction(ACTION_STOP_ALL);
        PendingIntent piStop = PendingIntent.getBroadcast(context, 106, stopIntent, flags);
        views.setOnClickPendingIntent(R.id.widget_btn_stop, piStop);

        // Tap WEB UI -> open internal web activity
        Intent openWebIntent = new Intent(context, JellyfinWebActivity.class);
        openWebIntent.putExtra(JellyfinWebActivity.EXTRA_URL, "http://127.0.0.1:8096");
        openWebIntent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        PendingIntent piWeb = PendingIntent.getActivity(context, 101, openWebIntent, flags);
        views.setOnClickPendingIntent(R.id.widget_btn_web, piWeb);

        appWidgetManager.updateAppWidget(appWidgetId, views);
    }

    @Override
    public void onReceive(Context context, Intent intent) {
        super.onReceive(context, intent);
        if (intent == null) return;

        String action = intent.getAction();
        Log.i(TAG, "onReceive action=" + action);

        JellyfinController controller = JellyfinController.getInstance();
        MediaServiceManager sm = MediaServiceManager.getInstance(context);

        if (ACTION_START_JELLYFIN.equals(action)) {
            Log.i(TAG, "Widget clicked START JELLYFIN (starting Jellyfin only)");
            Intent serviceIntent = new Intent(context, JellyfinServerService.class);
            serviceIntent.setAction(JellyfinServerService.ACTION_START_JELLYFIN);
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(serviceIntent);
            } else {
                context.startService(serviceIntent);
            }

            if (controller != null) {
                controller.start(context);
            }

            updateAllWidgets(context);
            new Handler(Looper.getMainLooper()).postDelayed(() -> updateAllWidgets(context), 1500);
            new Handler(Looper.getMainLooper()).postDelayed(() -> updateAllWidgets(context), 3500);

        } else if (ACTION_START_ALL.equals(action)) {
            Log.i(TAG, "Widget clicked START ALL");
            Intent serviceIntent = new Intent(context, JellyfinServerService.class);
            serviceIntent.setAction(JellyfinServerService.ACTION_START_ALL);
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(serviceIntent);
            } else {
                context.startService(serviceIntent);
            }

            if (sm != null) {
                sm.startAllServices();
            } else if (controller != null) {
                controller.start(context);
            }

            updateAllWidgets(context);
            new Handler(Looper.getMainLooper()).postDelayed(() -> updateAllWidgets(context), 1500);
            new Handler(Looper.getMainLooper()).postDelayed(() -> updateAllWidgets(context), 3500);

        } else if (ACTION_RESTART_ALL.equals(action)) {
            Log.i(TAG, "Widget clicked RESTART ALL");
            // 1. Immediately stop services
            Intent stopIntent = new Intent(context, JellyfinServerService.class);
            stopIntent.setAction(JellyfinServerService.ACTION_STOP_ALL);
            context.startService(stopIntent);

            if (sm != null) {
                sm.stopAllServices();
            }
            if (controller != null) {
                controller.stop();
            }

            updateAllWidgets(context);

            // 2. Delayed restart after processes gracefully exit
            new Handler(Looper.getMainLooper()).postDelayed(() -> {
                Log.i(TAG, "Widget restart executing delayed launch");
                Intent startIntent = new Intent(context, JellyfinServerService.class);
                startIntent.setAction(JellyfinServerService.ACTION_START_ALL);
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    context.startForegroundService(startIntent);
                } else {
                    context.startService(startIntent);
                }

                if (sm != null) {
                    sm.startAllServices();
                } else if (controller != null) {
                    controller.start(context);
                }

                updateAllWidgets(context);
                new Handler(Looper.getMainLooper()).postDelayed(() -> updateAllWidgets(context), 2500);
            }, 1600);

        } else if (ACTION_STOP_ALL.equals(action) || ACTION_TOGGLE_SERVER.equals(action)) {
            Log.i(TAG, "Widget clicked STOP ALL");
            Intent stopIntent = new Intent(context, JellyfinServerService.class);
            stopIntent.setAction(JellyfinServerService.ACTION_STOP_ALL);
            context.startService(stopIntent);

            if (sm != null) {
                sm.stopAllServices();
            }
            if (controller != null) {
                controller.stop();
            }

            updateAllWidgets(context);
            new Handler(Looper.getMainLooper()).postDelayed(() -> updateAllWidgets(context), 1500);

        } else if (ACTION_PIN_WIDGET.equals(action)) {
            Log.i(TAG, "Requesting launcher to pin Fishbowl widget");
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                try {
                    AppWidgetManager appWidgetManager = AppWidgetManager.getInstance(context);
                    ComponentName myProvider = new ComponentName(context, FishbowlWidgetProvider.class);
                    if (appWidgetManager != null && appWidgetManager.isRequestPinAppWidgetSupported()) {
                        appWidgetManager.requestPinAppWidget(myProvider, null, null);
                    }
                } catch (Throwable t) {
                    Log.w(TAG, "requestPinAppWidget error: " + t.getMessage());
                }
            }
        } else if (ACTION_WIDGET_UPDATE.equals(action) || AppWidgetManager.ACTION_APPWIDGET_UPDATE.equals(action)) {
            updateAllWidgets(context);
        }
    }

    public static void updateAllWidgets(Context context) {
        if (context == null) return;
        try {
            AppWidgetManager mgr = AppWidgetManager.getInstance(context);
            ComponentName cn = new ComponentName(context, FishbowlWidgetProvider.class);
            int[] ids = mgr.getAppWidgetIds(cn);
            if (ids != null && ids.length > 0) {
                for (int id : ids) {
                    updateAppWidget(context, mgr, id);
                }
            }
            FishbowlServicesWidgetProvider.updateAllWidgets(context);
        } catch (Throwable t) {
            Log.w(TAG, "updateAllWidgets failed: " + t.getMessage());
        }
    }

    private static String formatUptime(long sec) {
        if (sec <= 0) return "--:--:--";
        long h = sec / 3600;
        long m = (sec % 3600) / 60;
        long s = sec % 60;
        return String.format(Locale.ROOT, "%02d:%02d:%02d", h, m, s);
    }

    private static String formatShortTime(long sec) {
        if (sec <= 0) return "<1m";
        long h = sec / 3600;
        long m = (sec % 3600) / 60;
        long s = sec % 60;
        if (h > 0) {
            return String.format(Locale.ROOT, "%dh %02dm", h, m);
        }
        return String.format(Locale.ROOT, "%dm %02ds", m, s);
    }

    private static String getLanAddress() {
        try {
            Enumeration<NetworkInterface> interfaces = NetworkInterface.getNetworkInterfaces();
            while (interfaces.hasMoreElements()) {
                NetworkInterface iface = interfaces.nextElement();
                if (iface.isLoopback() || !iface.isUp()) continue;
                Enumeration<InetAddress> addresses = iface.getInetAddresses();
                while (addresses.hasMoreElements()) {
                    InetAddress addr = addresses.nextElement();
                    if (addr instanceof Inet4Address && !addr.isLoopbackAddress()) {
                        String host = addr.getHostAddress();
                        if (!host.startsWith("100.")) {
                            return host + ":8096";
                        }
                    }
                }
            }
        } catch (Throwable ignored) {}
        return "127.0.0.1:8096";
    }

    public static String formatSpeed(long bytesPerSec) {
        if (bytesPerSec <= 0) return "0 B/s";
        if (bytesPerSec < 1024) return bytesPerSec + " B/s";
        if (bytesPerSec < 1024 * 1024) return String.format(Locale.US, "%.1f KB/s", bytesPerSec / 1024.0);
        return String.format(Locale.US, "%.2f MB/s", bytesPerSec / (1024.0 * 1024.0));
    }

    public static synchronized void startDownloadPollingIfRunning(Context appContext) {
        if (sIsPollingDownloads) return;
        sIsPollingDownloads = true;
        scheduleNextDownloadPoll(appContext, 0);
    }

    public static synchronized void stopDownloadPolling() {
        sIsPollingDownloads = false;
        if (sDownloadRefreshRunnable != null) {
            sWidgetHandler.removeCallbacks(sDownloadRefreshRunnable);
            sDownloadRefreshRunnable = null;
        }
        if (sLastDownloadSnapshot != null && sLastDownloadSnapshot.isDownloading) {
            sLastDownloadSnapshot = new DownloadSnapshot(false, 0, 0, null, 0);
        }
    }

    public static void scheduleDownloadAutoRefresh(Context context) {
        if (context != null) {
            startDownloadPollingIfRunning(context.getApplicationContext());
        }
    }

    private static void scheduleNextDownloadPoll(Context appContext, long delayMs) {
        if (!sIsPollingDownloads) return;
        if (sDownloadRefreshRunnable != null) {
            sWidgetHandler.removeCallbacks(sDownloadRefreshRunnable);
        }
        sDownloadRefreshRunnable = () -> {
            if (!sIsPollingDownloads) return;
            sAsyncExecutor.execute(() -> {
                try {
                    MediaServiceManager sm = MediaServiceManager.getInstance(appContext);
                    ManagedService tr = sm != null ? sm.getService("transmission") : null;
                    if (tr == null || !tr.isRunning()) {
                        stopDownloadPolling();
                        sWidgetHandler.post(() -> updateStatusWidgetsOnly(appContext));
                        return;
                    }

                    com.fishbowl.app.MediaStorageManager msm = com.fishbowl.app.MediaStorageManager.getInstance(appContext);
                    List<com.fishbowl.app.MediaStorageManager.TorrentInfo> torrents = msm != null ? msm.queryTransmissionTorrents() : null;
                    int dlCount = 0;
                    long totalRate = 0;
                    String topName = null;
                    double topPct = 0;
                    if (torrents != null) {
                        for (com.fishbowl.app.MediaStorageManager.TorrentInfo t : torrents) {
                            if (t.getLifecycleState() == com.fishbowl.app.MediaStorageManager.DownloadLifecycleState.DOWNLOADING
                                    || t.rateDownload > 0
                                    || (t.status == 4 && t.percentDone < 1.0)) {
                                dlCount++;
                                totalRate += t.rateDownload;
                                if (topName == null) {
                                    topName = t.name != null ? t.name : "Torrent #" + t.id;
                                    topPct = t.percentDone;
                                }
                            }
                        }
                    }

                    boolean wasDownloading = sLastDownloadSnapshot != null && sLastDownloadSnapshot.isDownloading;
                    boolean isDownloading = dlCount > 0;
                    sLastDownloadSnapshot = new DownloadSnapshot(isDownloading, dlCount, totalRate, topName, topPct);

                    if (isDownloading || wasDownloading) {
                        sWidgetHandler.post(() -> updateStatusWidgetsOnly(appContext));
                    }

                    // Poll every 1 second when downloading; poll every 4 seconds when transmission is idle
                    long nextDelay = isDownloading ? 1000 : 4000;
                    sWidgetHandler.post(() -> scheduleNextDownloadPoll(appContext, nextDelay));
                } catch (Throwable t) {
                    Log.d(TAG, "Download polling exception: " + t.getMessage());
                    sWidgetHandler.post(() -> scheduleNextDownloadPoll(appContext, 5000));
                }
            });
        };
        sWidgetHandler.postDelayed(sDownloadRefreshRunnable, delayMs);
    }

    private static void updateStatusWidgetsOnly(Context context) {
        if (context == null) return;
        try {
            AppWidgetManager mgr = AppWidgetManager.getInstance(context);
            ComponentName cn = new ComponentName(context, FishbowlWidgetProvider.class);
            int[] ids = mgr.getAppWidgetIds(cn);
            if (ids != null && ids.length > 0) {
                for (int id : ids) {
                    updateAppWidget(context, mgr, id);
                }
            }
        } catch (Throwable t) {
            Log.w(TAG, "updateStatusWidgetsOnly failed: " + t.getMessage());
        }
    }
}
