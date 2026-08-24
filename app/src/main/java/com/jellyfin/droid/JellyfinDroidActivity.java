package com.jellyfin.droid;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.graphics.Color;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.net.NetworkRequest;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;

import com.termux.app.TermuxService;

import java.net.Inet4Address;
import java.net.NetworkInterface;
import java.util.Enumeration;

/**
 * Phase 9: Reliability & Production Hardening
 *
 * Changes from Phase 8:
 *  - START also starts JellyfinServerService (foreground) for background survival
 *  - Dynamic LAN address refresh on network change via ConnectivityManager.NetworkCallback
 *  - Displays STOPPING / CRASH_LOOP / FAILED states with actionable messages
 *  - START disabled when server already running or starting (duplicate-start guard)
 *  - RESET CRASH LOOP button visible only during CRASH_LOOP state
 *  - Storage permission status checked on every render
 *  - Network callback unregistered in onDestroy (no resource leak)
 */
public final class JellyfinDroidActivity extends AppCompatActivity
        implements JellyfinController.Listener {

    private JellyfinController controller;
    private TextView status;
    private TextView detail;
    private TextView lanLine;
    private Button   start;
    private Button   stop;
    private Button   restart;
    private Button   resetCrash;

    // Network-change tracking
    private ConnectivityManager.NetworkCallback networkCallback;
    private BroadcastReceiver connectivityReceiver;

    // ── Lifecycle ──────────────────────────────────────────────────────────────

    @Override
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        controller = JellyfinController.getInstance();
        startService(new Intent(this, TermuxService.class));
        setContentView(createContent());
        setupNetworkMonitor();
    }

    @Override
    protected void onStart() {
        super.onStart();
        controller.addListener(this);
        refreshLanAddress();
    }

    @Override
    protected void onStop() {
        controller.removeListener(this);
        super.onStop();
    }

    @Override
    protected void onDestroy() {
        teardownNetworkMonitor();
        super.onDestroy();
    }

    // ── UI construction ────────────────────────────────────────────────────────

    private View createContent() {
        int pad = dp(20);

        LinearLayout inner = new LinearLayout(this);
        inner.setOrientation(LinearLayout.VERTICAL);
        inner.setPadding(pad, pad, pad, pad);
        inner.setGravity(Gravity.CENTER_HORIZONTAL);
        inner.setBackgroundColor(Color.rgb(18, 24, 31));

        // Title
        TextView title = text("JELLYFINDROID", 26, Color.WHITE);
        title.setLetterSpacing(.08f);
        inner.addView(title);

        // Status indicator
        status = text("●  SERVER INITIALIZING", 17, Color.rgb(255, 193, 7));
        LinearLayout.LayoutParams gapParams = new LinearLayout.LayoutParams(-1, -2);
        gapParams.topMargin = dp(24);
        inner.addView(status, gapParams);

        // Server detail
        detail = text("Jellyfin 10.11.11\nLocal: http://127.0.0.1:8096", 14, Color.LTGRAY);
        inner.addView(detail);

        // LAN line (refreshed on network change)
        lanLine = text("LAN: detecting…", 14, Color.LTGRAY);
        inner.addView(lanLine);

        // Buttons
        Button open = button("OPEN JELLYFIN");
        open.setOnClickListener(v -> startActivity(new Intent(this, JellyfinWebActivity.class)));
        inner.addView(open, buttonParams());

        Button storageBtn = button("MEDIA STORAGE & FOLDERS");
        storageBtn.setOnClickListener(v ->
                startActivity(new Intent(this, JellyfinStorageActivity.class)));
        inner.addView(storageBtn, buttonParams());

        start = button("START SERVER");
        start.setOnClickListener(v -> {
            // Start foreground service so Jellyfin survives backgrounding (Step 6)
            Intent svc = new Intent(this, JellyfinServerService.class);
            svc.setAction(JellyfinServerService.ACTION_START);
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                startForegroundService(svc);
            } else {
                startService(svc);
            }
        });
        inner.addView(start, buttonParams());

        stop = button("STOP SERVER");
        stop.setOnClickListener(v -> {
            Intent svc = new Intent(this, JellyfinServerService.class);
            svc.setAction(JellyfinServerService.ACTION_STOP);
            startService(svc);
        });
        inner.addView(stop, buttonParams());

        restart = button("RESTART SERVER");
        restart.setOnClickListener(v -> controller.restart(this));
        inner.addView(restart, buttonParams());

        resetCrash = button("RESET CRASH LOOP");
        resetCrash.setBackgroundColor(Color.rgb(183, 28, 28));
        resetCrash.setOnClickListener(v -> controller.resetCrashLoop(this));
        resetCrash.setVisibility(View.GONE);
        inner.addView(resetCrash, buttonParams());

        Button logs = button("LOGS");
        logs.setOnClickListener(v -> startActivity(new Intent(this, JellyfinLogsActivity.class)));
        inner.addView(logs, buttonParams());

        Button settings = button("SETTINGS");
        settings.setOnClickListener(v ->
                startActivity(new Intent(this, JellyfinSettingsActivity.class)));
        inner.addView(settings, buttonParams());

        ScrollView scroll = new ScrollView(this);
        scroll.setBackgroundColor(Color.rgb(18, 24, 31));
        scroll.addView(inner);
        return scroll;
    }

    // ── State rendering ────────────────────────────────────────────────────────

    @Override
    public void onServerChanged(final JellyfinController.State state) {
        runOnUiThread(() -> render(state));
    }

    private void render(JellyfinController.State state) {
        if (status == null) return;

        int    color;
        String statusText;
        String extraLine = "";

        switch (state) {
            case RUNNING:
                color = Color.rgb(76, 175, 80);
                statusText = "●  SERVER RUNNING";
                break;
            case STARTING:
                color = Color.rgb(255, 193, 7);
                statusText = "●  SERVER STARTING…";
                break;
            case INITIALIZING:
                color = Color.rgb(255, 193, 7);
                statusText = "●  INITIALIZING RUNTIME…";
                break;
            case STOPPING:
                color = Color.rgb(255, 152, 0);
                statusText = "●  SERVER STOPPING…";
                break;
            case STOPPED:
                color = Color.LTGRAY;
                statusText = "●  SERVER STOPPED";
                break;
            case CRASHED:
                color = Color.rgb(244, 67, 54);
                statusText = "●  SERVER CRASHED";
                extraLine = safeError();
                break;
            case CRASH_LOOP:
                color = Color.rgb(183, 28, 28);
                statusText = "●  CRASH LOOP DETECTED";
                extraLine = "Auto-restart disabled after "
                        + controller.getRestartCount()
                        + " attempts.\nTap RESET CRASH LOOP to retry.";
                break;
            case FAILED:
                color = Color.rgb(244, 67, 54);
                statusText = "●  SERVER FAILED";
                extraLine = safeError();
                break;
            default:
                color = Color.rgb(255, 193, 7);
                statusText = "●  " + state.name();
                break;
        }

        status.setText(statusText);
        status.setTextColor(color);

        String detailText = "Jellyfin 10.11.11\nLocal: http://127.0.0.1:8096";
        if (!extraLine.isEmpty()) detailText += "\n⚠ " + extraLine;
        Integer exit = controller.getLastExitCode();
        if (exit != null && (state == JellyfinController.State.CRASHED
                || state == JellyfinController.State.FAILED)) {
            detailText += "\nExit code: " + exit;
        }
        detail.setText(detailText);

        // Storage check
        checkStorageStatus();

        // Button enable/disable (Step 1: duplicate-start guard)
        boolean busy = state == JellyfinController.State.INITIALIZING
                || state == JellyfinController.State.STARTING
                || state == JellyfinController.State.STOPPING;
        boolean running = state == JellyfinController.State.RUNNING;
        boolean crashLoop = state == JellyfinController.State.CRASH_LOOP;

        start.setEnabled(!busy && !running && !crashLoop);
        stop.setEnabled(running);
        restart.setEnabled(!busy && !crashLoop);
        resetCrash.setVisibility(crashLoop ? View.VISIBLE : View.GONE);
    }

    // ── Storage permission (Step 11) ───────────────────────────────────────────

    private void checkStorageStatus() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            if (!Environment.isExternalStorageManager()) {
                lanLine.setText("⚠ MEDIA STORAGE UNAVAILABLE\nTap 'MEDIA STORAGE & FOLDERS' to grant access.");
                lanLine.setTextColor(Color.rgb(255, 152, 0));
                return;
            }
        }
        refreshLanAddress();
    }

    // ── LAN address (Steps 8, 9) ───────────────────────────────────────────────

    private void refreshLanAddress() {
        String lan = getLanAddress();
        if (lan == null) {
            lanLine.setText("LAN: UNAVAILABLE — connect to Wi-Fi or LAN");
            lanLine.setTextColor(Color.rgb(255, 152, 0));
        } else {
            lanLine.setText("LAN: " + lan);
            lanLine.setTextColor(Color.LTGRAY);
        }
    }

    /** Returns null when no non-loopback IPv4 interface is up. */
    private String getLanAddress() {
        try {
            Enumeration<NetworkInterface> ifaces = NetworkInterface.getNetworkInterfaces();
            if (ifaces == null) return null;
            while (ifaces.hasMoreElements()) {
                NetworkInterface intf = ifaces.nextElement();
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

    // ── Network monitor (Steps 8, 9) ───────────────────────────────────────────

    private void setupNetworkMonitor() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            ConnectivityManager cm =
                    (ConnectivityManager) getSystemService(CONNECTIVITY_SERVICE);
            if (cm != null) {
                networkCallback = new ConnectivityManager.NetworkCallback() {
                    @Override public void onAvailable(@NonNull Network n) {
                        runOnUiThread(() -> refreshLanAddress());
                    }
                    @Override public void onLost(@NonNull Network n) {
                        runOnUiThread(() -> refreshLanAddress());
                    }
                    @Override public void onCapabilitiesChanged(
                            @NonNull Network n, @NonNull NetworkCapabilities c) {
                        runOnUiThread(() -> refreshLanAddress());
                    }
                };
                try {
                    cm.registerNetworkCallback(new NetworkRequest.Builder().build(), networkCallback);
                } catch (Exception ignored) {}
            }
        } else {
            // Older API fallback
            connectivityReceiver = new BroadcastReceiver() {
                @Override public void onReceive(Context ctx, Intent intent) {
                    refreshLanAddress();
                }
            };
            //noinspection deprecation
            registerReceiver(connectivityReceiver,
                    new IntentFilter(ConnectivityManager.CONNECTIVITY_ACTION));
        }
    }

    private void teardownNetworkMonitor() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N && networkCallback != null) {
            ConnectivityManager cm =
                    (ConnectivityManager) getSystemService(CONNECTIVITY_SERVICE);
            if (cm != null) {
                try { cm.unregisterNetworkCallback(networkCallback); }
                catch (Exception ignored) {}
            }
            networkCallback = null;
        }
        if (connectivityReceiver != null) {
            try { unregisterReceiver(connectivityReceiver); }
            catch (Exception ignored) {}
            connectivityReceiver = null;
        }
    }

    // ── Helpers ────────────────────────────────────────────────────────────────

    private String safeError() {
        String e = controller.getLastError();
        return e != null ? e : "";
    }

    private LinearLayout.LayoutParams buttonParams() {
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(-1, -2);
        p.topMargin = dp(8);
        return p;
    }

    private Button button(String label) {
        Button b = new Button(this);
        b.setText(label);
        b.setAllCaps(false);
        return b;
    }

    private TextView text(String value, int size, int color) {
        TextView t = new TextView(this);
        t.setText(value);
        t.setTextSize(size);
        t.setTextColor(color);
        t.setGravity(Gravity.CENTER);
        return t;
    }

    private int dp(int value) {
        return (int) (value * getResources().getDisplayMetrics().density);
    }
}
