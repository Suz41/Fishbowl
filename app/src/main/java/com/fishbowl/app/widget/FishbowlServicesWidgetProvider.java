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
import android.view.View;
import android.widget.RemoteViews;

import com.fishbowl.app.JellyfinController;
import com.fishbowl.app.model.ManagedService;
import com.fishbowl.app.services.MediaServiceManager;
import com.termux.R;

/**
 * Dedicated Android Home Screen Widget for individual Fishbowl media services.
 * Renders glanceable, tappable service logos that dynamically reflect installed
 * availability and live status beacons (running vs stopped), allowing direct one-tap toggling.
 */
public class FishbowlServicesWidgetProvider extends AppWidgetProvider {

    private static final String TAG = "FishbowlServicesWidget";

    public static final String ACTION_SERVICES_WIDGET_UPDATE = "com.fishbowl.app.widget.ACTION_SERVICES_WIDGET_UPDATE";
    public static final String ACTION_TOGGLE_SERVICE         = "com.fishbowl.app.widget.ACTION_TOGGLE_SERVICE";
    public static final String ACTION_PIN_WIDGET             = "com.fishbowl.app.widget.ACTION_PIN_SERVICES_WIDGET";
    public static final String EXTRA_SERVICE_ID              = "com.fishbowl.app.widget.EXTRA_SERVICE_ID";

    private static class ServiceEntry {
        final String id;
        final int containerId;
        final int tileId;
        final int iconId;
        final int dotId;
        final int defaultPort;
        final String brandColor;

        ServiceEntry(String id, int containerId, int tileId, int iconId, int dotId, int defaultPort, String brandColor) {
            this.id = id;
            this.containerId = containerId;
            this.tileId = tileId;
            this.iconId = iconId;
            this.dotId = dotId;
            this.defaultPort = defaultPort;
            this.brandColor = brandColor;
        }
    }

    private static final ServiceEntry[] SERVICES = new ServiceEntry[] {
            new ServiceEntry("jellyfin",     R.id.svc_container_jellyfin,     R.id.svc_tile_jellyfin,     R.id.svc_icon_jellyfin,     R.id.svc_dot_jellyfin,     8096, "#AB03A9"),
            new ServiceEntry("radarr",       R.id.svc_container_radarr,       R.id.svc_tile_radarr,       R.id.svc_icon_radarr,       R.id.svc_dot_radarr,       7878, "#FF9E20"),
            new ServiceEntry("sonarr",       R.id.svc_container_sonarr,       R.id.svc_tile_sonarr,       R.id.svc_icon_sonarr,       R.id.svc_dot_sonarr,       8989, "#CCFBFA"),
            new ServiceEntry("prowlarr",     R.id.svc_container_prowlarr,     R.id.svc_tile_prowlarr,     R.id.svc_icon_prowlarr,     R.id.svc_dot_prowlarr,     9696, "#FFFC8C"),
            new ServiceEntry("transmission", R.id.svc_container_transmission, R.id.svc_tile_transmission, R.id.svc_icon_transmission, R.id.svc_dot_transmission, 9091, "#FF788D"),
            new ServiceEntry("seerr",        R.id.svc_container_seerr,        R.id.svc_tile_seerr,        R.id.svc_icon_seerr,        R.id.svc_dot_seerr,        5055, "#55A9A0"),
            new ServiceEntry("bazarr",       R.id.svc_container_bazarr,       R.id.svc_tile_bazarr,       R.id.svc_icon_bazarr,       R.id.svc_dot_bazarr,       6767, "#5D9253"),
            new ServiceEntry("lidarr",       R.id.svc_container_lidarr,       R.id.svc_tile_lidarr,       R.id.svc_icon_lidarr,       R.id.svc_dot_lidarr,       8686, "#769826")
    };

    @Override
    public void onUpdate(Context context, AppWidgetManager appWidgetManager, int[] appWidgetIds) {
        for (int appWidgetId : appWidgetIds) {
            updateAppWidget(context, appWidgetManager, appWidgetId);
        }
    }

    public static void updateAppWidget(Context context, AppWidgetManager appWidgetManager, int appWidgetId) {
        RemoteViews views = new RemoteViews(context.getPackageName(), R.layout.widget_fishbowl_services);

        JellyfinController jfCtrl = JellyfinController.getInstance();
        MediaServiceManager sm = MediaServiceManager.getInstance(context);

        int flags = PendingIntent.FLAG_UPDATE_CURRENT;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            flags |= PendingIntent.FLAG_IMMUTABLE;
        }

        for (int i = 0; i < SERVICES.length; i++) {
            ServiceEntry entry = SERVICES[i];
            boolean isInstalled = false;
            boolean isRunning = false;

            if ("jellyfin".equalsIgnoreCase(entry.id)) {
                isInstalled = true;
                isRunning = (jfCtrl != null && jfCtrl.getState() == JellyfinController.State.RUNNING);
            } else if (sm != null) {
                ManagedService ms = sm.getService(entry.id);
                if (ms != null) {
                    isInstalled = ms.isInstalled();
                    isRunning = ms.isRunning();
                }
            }

            if (!isInstalled) {
                // If service is not installed on device, hide entire container
                views.setViewVisibility(entry.containerId, View.GONE);
            } else {
                // Service is installed: display container with state-aware feedback
                views.setViewVisibility(entry.containerId, View.VISIBLE);

                if (isRunning) {
                    views.setInt(entry.tileId, "setBackgroundResource", R.drawable.widget_service_tile_active_bg);
                    views.setImageViewResource(entry.dotId, R.drawable.widget_status_dot_green);
                    views.setInt(entry.iconId, "setColorFilter", Color.parseColor(entry.brandColor));
                } else {
                    views.setInt(entry.tileId, "setBackgroundResource", R.drawable.widget_service_tile_bg);
                    views.setImageViewResource(entry.dotId, R.drawable.widget_status_dot_red);
                    views.setInt(entry.iconId, "setColorFilter", Color.parseColor("#52525B"));
                }

                // Attach individual toggle intent to the tile
                Intent toggleIntent = new Intent(context, FishbowlServicesWidgetProvider.class);
                toggleIntent.setAction(ACTION_TOGGLE_SERVICE);
                toggleIntent.putExtra(EXTRA_SERVICE_ID, entry.id);

                PendingIntent pi = PendingIntent.getBroadcast(context, 300 + i, toggleIntent, flags);
                views.setOnClickPendingIntent(entry.tileId, pi);
            }
        }

        appWidgetManager.updateAppWidget(appWidgetId, views);
    }

    @Override
    public void onReceive(Context context, Intent intent) {
        super.onReceive(context, intent);
        if (intent == null) return;

        String action = intent.getAction();
        Log.i(TAG, "onReceive action=" + action);

        if (ACTION_TOGGLE_SERVICE.equals(action)) {
            String serviceId = intent.getStringExtra(EXTRA_SERVICE_ID);
            Log.i(TAG, "Toggling service: " + serviceId);

            if (serviceId != null) {
                toggleService(context, serviceId);
            }
        } else if (ACTION_PIN_WIDGET.equals(action)) {
            Log.i(TAG, "Requesting launcher to pin Fishbowl Services widget");
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                try {
                    AppWidgetManager appWidgetManager = AppWidgetManager.getInstance(context);
                    ComponentName myProvider = new ComponentName(context, FishbowlServicesWidgetProvider.class);
                    if (appWidgetManager != null && appWidgetManager.isRequestPinAppWidgetSupported()) {
                        appWidgetManager.requestPinAppWidget(myProvider, null, null);
                    }
                } catch (Throwable t) {
                    Log.w(TAG, "requestPinAppWidget error: " + t.getMessage());
                }
            }
        } else if (ACTION_SERVICES_WIDGET_UPDATE.equals(action)
                || AppWidgetManager.ACTION_APPWIDGET_UPDATE.equals(action)) {
            updateAllWidgets(context);
        }
    }

    private void toggleService(Context context, String serviceId) {
        JellyfinController jfCtrl = JellyfinController.getInstance();
        MediaServiceManager sm = MediaServiceManager.getInstance(context);

        if ("jellyfin".equalsIgnoreCase(serviceId)) {
            if (jfCtrl != null && jfCtrl.getState() == JellyfinController.State.RUNNING) {
                Log.i(TAG, "Stopping Jellyfin via widget");
                jfCtrl.stop();
            } else if (jfCtrl != null) {
                Log.i(TAG, "Starting Jellyfin via widget");
                jfCtrl.start(context);
            }
        } else if (sm != null) {
            ManagedService ms = sm.getService(serviceId);
            if (ms != null) {
                if (ms.isRunning()) {
                    Log.i(TAG, "Stopping companion service via widget: " + serviceId);
                    sm.stopService(serviceId);
                } else {
                    Log.i(TAG, "Starting companion service via widget: " + serviceId);
                    sm.startService(serviceId, context);
                }
            }
        }

        // 1. Synchronously update widget states immediately
        updateAllWidgets(context);
        FishbowlWidgetProvider.updateAllWidgets(context);

        // 2. Delayed reconciliation to capture daemon process startup and port binding
        Handler handler = new Handler(Looper.getMainLooper());
        handler.postDelayed(() -> {
            updateAllWidgets(context);
            FishbowlWidgetProvider.updateAllWidgets(context);
        }, 1500);

        handler.postDelayed(() -> {
            updateAllWidgets(context);
            FishbowlWidgetProvider.updateAllWidgets(context);
        }, 3000);
    }

    /**
     * Broadcasts an immediate layout refresh to all active FishbowlServicesWidget instances.
     */
    public static void updateAllWidgets(Context context) {
        if (context == null) return;
        AppWidgetManager manager = AppWidgetManager.getInstance(context);
        if (manager == null) return;
        ComponentName cn = new ComponentName(context, FishbowlServicesWidgetProvider.class);
        int[] ids = manager.getAppWidgetIds(cn);
        if (ids != null && ids.length > 0) {
            for (int id : ids) {
                updateAppWidget(context, manager, id);
            }
        }
    }
}
