package com.jellyfin.droid;

import android.graphics.Color;
import android.os.Bundle;
import android.view.View;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import androidx.appcompat.app.AppCompatActivity;

public final class JellyfinLogsActivity extends AppCompatActivity implements JellyfinController.Listener {
    private JellyfinController controller;
    private TextView output;
    @Override public void onCreate(Bundle state) {
        super.onCreate(state); controller = JellyfinController.getInstance();
        LinearLayout root = new LinearLayout(this); root.setPadding(20,20,20,20); root.setOrientation(LinearLayout.VERTICAL);
        TextView title = new TextView(this); title.setText("Jellyfin logs"); title.setTextSize(22); root.addView(title);
        LinearLayout actions = new LinearLayout(this); Button refresh = new Button(this); refresh.setText("REFRESH"); refresh.setOnClickListener(v -> refresh()); Button clear = new Button(this); clear.setText("CLEAR DISPLAY"); clear.setOnClickListener(v -> { controller.clearDisplayedLogs(); refresh(); }); actions.addView(refresh); actions.addView(clear); root.addView(actions);
        ScrollView scroll = new ScrollView(this); output = new TextView(this); output.setTextColor(Color.WHITE); output.setTextSize(12); output.setPadding(12,12,12,12); output.setBackgroundColor(Color.rgb(20,20,20)); scroll.addView(output); root.addView(scroll, new LinearLayout.LayoutParams(-1,0,1)); setContentView(root);
    }
    @Override protected void onStart() { super.onStart(); controller.addListener(this); refresh(); }
    @Override protected void onStop() { controller.removeListener(this); super.onStop(); }
    private void refresh() { if (output != null) output.setText(controller.getLogs()); }
    @Override public void onServerChanged(JellyfinController.State state) { runOnUiThread(this::refresh); }
}
