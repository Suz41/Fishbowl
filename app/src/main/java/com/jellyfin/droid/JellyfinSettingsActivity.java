package com.jellyfin.droid;

import android.content.SharedPreferences;
import android.os.Bundle;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Switch;
import androidx.appcompat.app.AppCompatActivity;
import com.termux.shared.termux.TermuxConstants;
import java.io.File;

public final class JellyfinSettingsActivity extends AppCompatActivity {
    @Override public void onCreate(Bundle state) {
        super.onCreate(state); LinearLayout root = new LinearLayout(this); root.setPadding(28,28,28,28); root.setOrientation(LinearLayout.VERTICAL);
        add(root, "Settings", 24); add(root, "Server", 18); add(root, "Jellyfin version: 10.11.11\nAddress: 127.0.0.1\nPort: 8096", 15);
        SharedPreferences prefs = getSharedPreferences("jellyfindroid", MODE_PRIVATE); Switch auto = new Switch(this); auto.setText("Auto-start Jellyfin"); 
        // Spec §5: Default auto-start to false
        auto.setChecked(prefs.getBoolean("auto_start", false)); auto.setOnCheckedChangeListener((v, checked) -> prefs.edit().putBoolean("auto_start", checked).apply()); root.addView(auto);
        add(root, "Runtime", 18); File prefix = TermuxConstants.TERMUX_PREFIX_DIR; add(root, "Bootstrap: " + (JellyfinBootstrapper.isInitialized(this) ? "ready" : "not initialized") + "\nStorage usage: " + (prefix.exists() ? prefix.length() / 1024 / 1024 : 0) + " MB", 15);
        Button restart = new Button(this); restart.setText("RESTART SERVER"); restart.setOnClickListener(v -> JellyfinController.getInstance().restart(this)); root.addView(restart);
        Button logs = new Button(this); logs.setText("OPEN LOGS"); logs.setOnClickListener(v -> startActivity(new android.content.Intent(this, JellyfinLogsActivity.class))); root.addView(logs); setContentView(root);
    }
    private void add(LinearLayout root, String value, int size) { TextView text = new TextView(this); text.setText(value); text.setTextSize(size); text.setPadding(0,12,0,12); root.addView(text); }
}
