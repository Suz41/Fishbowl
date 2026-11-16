package com.fishbowl.app.services;

import android.content.Context;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.util.Log;

import com.fishbowl.app.model.ManagedService;
import com.fishbowl.app.model.ServiceState;

import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;

/**
 * Per-service "Wi-Fi only" policy.
 *
 * Android gives every Fishbowl child process the same UID, so traffic of one service cannot be
 * blocked on its own. Instead, a service marked "Wi-Fi only" is stopped while the active network is
 * mobile data and started again automatically when Wi-Fi (or Ethernet) returns. Going offline never
 * stops anything, because no mobile data is consumed.
 */
public final class NetworkPolicyEnforcer {

    private static final String TAG = "NetworkPolicyEnforcer";
    private static final long DEBOUNCE_MS = 3000L;

    // NOTE: ConcurrentHashMap.newKeySet() is missing from the desugared core library on some devices.
    private static final Set<String> selfStopping =
            java.util.Collections.newSetFromMap(new ConcurrentHashMap<String, Boolean>());
    private static volatile NetworkPolicyEnforcer instance;

    private final Context appContext;
    private final MediaServiceManager manager;
    private final ConnectivityManager cm;
    private final ScheduledExecutorService executor;
    private ScheduledFuture<?> pending;

    private NetworkPolicyEnforcer(Context ctx, MediaServiceManager manager) {
        this.appContext = ctx.getApplicationContext();
        this.manager = manager;
        this.cm = (ConnectivityManager) appContext.getSystemService(Context.CONNECTIVITY_SERVICE);
        this.executor = Executors.newSingleThreadScheduledExecutor(new ThreadFactory() {
            @Override
            public Thread newThread(Runnable r) {
                Thread t = new Thread(r, "Fishbowl-NetworkPolicy");
                t.setDaemon(true);
                return t;
            }
        });
    }

    public static synchronized void start(Context ctx, MediaServiceManager manager) {
        if (instance != null || ctx == null || manager == null) return;
        NetworkPolicyEnforcer e = new NetworkPolicyEnforcer(ctx, manager);
        instance = e;
        e.register();
        e.scheduleEvaluate(1000L);
    }

    /** Re-evaluates the policy now (called after the user changes a Wi-Fi only switch). */
    public static void reevaluate() {
        NetworkPolicyEnforcer e = instance;
        if (e != null) e.scheduleEvaluate(0L);
    }

    /**
     * Called at the top of stopService(): a manual stop must cancel a pending automatic resume,
     * but the enforcer's own stops must not.
     */
    public static void onStopRequested(Context ctx, String serviceId) {
        if (ctx == null || serviceId == null) return;
        if (selfStopping.contains(serviceId.toLowerCase(java.util.Locale.ROOT))) return;
        AutomationPrefs.removeWifiPaused(ctx, serviceId);
    }

    /** True when starting this service is currently allowed by its Wi-Fi only setting. */
    public static boolean isStartAllowed(Context ctx, String serviceId) {
        if (ctx == null || !AutomationPrefs.isWifiOnly(ctx, serviceId)) return true;
        return !isOnMobileData(ctx);
    }

    public static boolean isOnMobileData(Context ctx) {
        try {
            ConnectivityManager c = (ConnectivityManager) ctx.getApplicationContext()
                    .getSystemService(Context.CONNECTIVITY_SERVICE);
            if (c == null) return false;
            Network n = c.getActiveNetwork();
            if (n == null) return false;
            NetworkCapabilities caps = c.getNetworkCapabilities(n);
            if (caps == null) return false;
            return caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR)
                    && !caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)
                    && !caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET);
        } catch (Throwable t) {
            return false;
        }
    }

    public static boolean isOnWifiOrEthernet(Context ctx) {
        try {
            ConnectivityManager c = (ConnectivityManager) ctx.getApplicationContext()
                    .getSystemService(Context.CONNECTIVITY_SERVICE);
            if (c == null) return false;
            Network n = c.getActiveNetwork();
            if (n == null) return false;
            NetworkCapabilities caps = c.getNetworkCapabilities(n);
            if (caps == null) return false;
            return caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)
                    || caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET);
        } catch (Throwable t) {
            return false;
        }
    }

    private void register() {
        if (cm == null) return;
        try {
            cm.registerDefaultNetworkCallback(new ConnectivityManager.NetworkCallback() {
                @Override
                public void onAvailable(Network network) {
                    scheduleEvaluate(DEBOUNCE_MS);
                }

                @Override
                public void onCapabilitiesChanged(Network network, NetworkCapabilities caps) {
                    scheduleEvaluate(DEBOUNCE_MS);
                }

                @Override
                public void onLost(Network network) {
                    scheduleEvaluate(DEBOUNCE_MS);
                }
            });
        } catch (Throwable t) {
            Log.w(TAG, "Could not register network callback: " + t.getMessage());
        }
    }

    private synchronized void scheduleEvaluate(long delayMs) {
        if (pending != null) pending.cancel(false);
        pending = executor.schedule(new Runnable() {
            @Override
            public void run() {
                try {
                    evaluate();
                } catch (Throwable t) {
                    Log.w(TAG, "Policy evaluation failed: " + t.getMessage());
                }
            }
        }, delayMs, TimeUnit.MILLISECONDS);
    }

    private static boolean isActiveState(ServiceState s) {
        return s == ServiceState.STARTING || s == ServiceState.PROCESS_READY
                || s == ServiceState.SOCKET_READY || s == ServiceState.API_READY
                || s == ServiceState.RUNNING || s == ServiceState.UNHEALTHY
                || s == ServiceState.RECOVERING;
    }

    private void evaluate() {
        boolean mobile = isOnMobileData(appContext);
        boolean wifi = isOnWifiOrEthernet(appContext);

        if (mobile) {
            for (ManagedService ms : manager.getAllServices()) {
                String id = ms.getId();
                if (!AutomationPrefs.isWifiOnly(appContext, id)) continue;
                if (!isActiveState(ms.getState())) continue;
                String key = id.toLowerCase(java.util.Locale.ROOT);
                Log.i(TAG, "Mobile data active: stopping Wi-Fi only service " + id);
                ms.appendLog("[Wi-Fi only] Mobile data detected - stopping until Wi-Fi returns.");
                selfStopping.add(key);
                try {
                    manager.stopService(id, null);
                } finally {
                    selfStopping.remove(key);
                }
                AutomationPrefs.addWifiPaused(appContext, id);
            }
        } else if (wifi) {
            for (String id : AutomationPrefs.getWifiPaused(appContext)) {
                ManagedService ms = manager.getService(id);
                if (ms == null || !AutomationPrefs.isWifiOnly(appContext, id)) {
                    AutomationPrefs.removeWifiPaused(appContext, id);
                    continue;
                }
                AutomationPrefs.removeWifiPaused(appContext, id);
                if (isActiveState(ms.getState())) continue;
                Log.i(TAG, "Wi-Fi available: resuming service " + id);
                ms.appendLog("[Wi-Fi only] Wi-Fi available - resuming service.");
                manager.startService(id, appContext, null);
            }
        }

        // A switch turned off must not leave a stale pause behind.
        for (String id : AutomationPrefs.getWifiPaused(appContext)) {
            if (!AutomationPrefs.isWifiOnly(appContext, id)) AutomationPrefs.removeWifiPaused(appContext, id);
        }
    }
}
