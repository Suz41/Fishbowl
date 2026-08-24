package com.jellyfin.droid;

import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.os.Bundle;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.appcompat.app.AppCompatActivity;

import com.termux.app.TermuxService;

/** Minimal launcher UI. The Termux activity remains available internally for debugging. */
public final class JellyfinDroidActivity extends AppCompatActivity implements JellyfinController.Listener {
    private JellyfinController controller;
    private TextView status;
    private TextView detail;
    private Button start;
    private Button stop;
    private Button restart;

    @Override public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        controller = JellyfinController.getInstance();
        startService(new Intent(this, TermuxService.class));
        setContentView(createContent());
    }

    @Override protected void onStart() { super.onStart(); controller.addListener(this); }
    @Override protected void onStop() { controller.removeListener(this); super.onStop(); }

    private View createContent() {
        int pad = dp(24);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL); root.setPadding(pad, pad, pad, pad);
        root.setGravity(Gravity.CENTER_HORIZONTAL); root.setBackgroundColor(Color.rgb(18, 24, 31));
        TextView title = text("JELLYFINDROID", 28, Color.WHITE); title.setLetterSpacing(.08f); root.addView(title);
        status = text("●  SERVER INITIALIZING", 18, Color.rgb(255, 193, 7));
        LinearLayout.LayoutParams gap = new LinearLayout.LayoutParams(-1, -2); gap.topMargin = dp(28); root.addView(status, gap);
        detail = text("Jellyfin 10.11.11\nLocal: http://127.0.0.1:8096\nLAN: " + getLanAddress(), 15, Color.LTGRAY); root.addView(detail);
        Button open = button("OPEN JELLYFIN"); open.setOnClickListener(v -> startActivity(new Intent(this, JellyfinWebActivity.class))); root.addView(open, buttonParams());
        Button storageBtn = button("MEDIA STORAGE & FOLDERS"); storageBtn.setOnClickListener(v -> startActivity(new Intent(this, JellyfinStorageActivity.class))); root.addView(storageBtn, buttonParams());
        start = button("START SERVER"); start.setOnClickListener(v -> controller.start(this)); root.addView(start, buttonParams());
        stop = button("STOP SERVER"); stop.setOnClickListener(v -> controller.stop()); root.addView(stop, buttonParams());
        restart = button("RESTART SERVER"); restart.setOnClickListener(v -> controller.restart(this)); root.addView(restart, buttonParams());
        Button logs = button("LOGS"); logs.setOnClickListener(v -> startActivity(new Intent(this, JellyfinLogsActivity.class))); root.addView(logs, buttonParams());
        Button settings = button("SETTINGS"); settings.setOnClickListener(v -> startActivity(new Intent(this, JellyfinSettingsActivity.class))); root.addView(settings, buttonParams());
        return root;
    }
    private String getLanAddress() {
        try {
            for (java.util.Enumeration<java.net.NetworkInterface> en = java.net.NetworkInterface.getNetworkInterfaces(); en.hasMoreElements();) {
                java.net.NetworkInterface intf = en.nextElement();
                for (java.util.Enumeration<java.net.InetAddress> enumIpAddr = intf.getInetAddresses(); enumIpAddr.hasMoreElements();) {
                    java.net.InetAddress inetAddress = enumIpAddr.nextElement();
                    if (!inetAddress.isLoopbackAddress() && inetAddress instanceof java.net.Inet4Address) {
                        return "http://" + inetAddress.getHostAddress() + ":8096";
                    }
                }
            }
        } catch (Exception ignored) {}
        return "http://127.0.0.1:8096";
    }
    private LinearLayout.LayoutParams buttonParams() { LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(-1, -2); p.topMargin = dp(8); return p; }
    private Button button(String label) { Button b = new Button(this); b.setText(label); b.setAllCaps(false); return b; }
    private TextView text(String value, int size, int color) { TextView t = new TextView(this); t.setText(value); t.setTextSize(size); t.setTextColor(color); t.setGravity(Gravity.CENTER); return t; }
    private int dp(int value) { return (int) (value * getResources().getDisplayMetrics().density); }
    @Override public void onServerChanged(final JellyfinController.State state) { runOnUiThread(() -> render(state)); }
    private void render(JellyfinController.State state) {
        if (status == null) return;
        int color = state == JellyfinController.State.RUNNING ? Color.rgb(76, 175, 80) : (state == JellyfinController.State.FAILED || state == JellyfinController.State.CRASHED ? Color.rgb(244, 67, 54) : Color.rgb(255, 193, 7));
        status.setText("●  SERVER " + state.name()); status.setTextColor(color);
        Integer exit = controller.getLastExitCode();
        detail.setText("Jellyfin 10.11.11\nLocal: http://127.0.0.1:8096\nLAN: " + getLanAddress() + (exit == null ? "" : "\nLast exit: " + exit));
        boolean busy = state == JellyfinController.State.INITIALIZING || state == JellyfinController.State.STARTING;
        start.setEnabled(!busy && state != JellyfinController.State.RUNNING);
        stop.setEnabled(!busy && state == JellyfinController.State.RUNNING);
        restart.setEnabled(!busy);
    }
}
