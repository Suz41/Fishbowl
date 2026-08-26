package com.jellyfin.droid;

import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
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

    @Override
    public void onCreate(Bundle state) {
        super.onCreate(state);
        controller = JellyfinController.getInstance();

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(16), dp(16), dp(16), dp(16));
        root.setBackgroundColor(isDarkTheme() ? Color.parseColor("#121316") : Color.parseColor("#F8F9FA"));

        TextView title = new TextView(this);
        title.setText("Jellyfin Server Logs");
        title.setTextSize(22);
        title.setTypeface(Typeface.create("sans-serif-medium", Typeface.BOLD));
        title.setTextColor(isDarkTheme() ? Color.parseColor("#E6E8EE") : Color.parseColor("#1F2024"));
        title.setPadding(0, 0, 0, dp(12));
        root.addView(title);

        LinearLayout actions = new LinearLayout(this);
        actions.setOrientation(LinearLayout.HORIZONTAL);
        actions.setPadding(0, 0, 0, dp(12));

        Button btnRefresh = createPixelButton("REFRESH", isDarkTheme() ? Color.parseColor("#272930") : Color.parseColor("#FFFFFF"), isDarkTheme() ? Color.parseColor("#E6E8EE") : Color.parseColor("#1F2024"));
        btnRefresh.setOnClickListener(v -> refresh());
        LinearLayout.LayoutParams p1 = new LinearLayout.LayoutParams(0, -2, 1.0f);
        p1.rightMargin = dp(6);
        actions.addView(btnRefresh, p1);

        Button btnClear = createPixelButton("CLEAR DISPLAY", isDarkTheme() ? Color.parseColor("#272930") : Color.parseColor("#FFFFFF"), isDarkTheme() ? Color.parseColor("#E6E8EE") : Color.parseColor("#1F2024"));
        btnClear.setOnClickListener(v -> {
            controller.clearDisplayedLogs();
            refresh();
        });
        LinearLayout.LayoutParams p2 = new LinearLayout.LayoutParams(0, -2, 1.0f);
        p2.leftMargin = dp(6);
        actions.addView(btnClear, p2);

        root.addView(actions);

        ScrollView scroll = new ScrollView(this);
        GradientDrawable gd = new GradientDrawable();
        gd.setColor(Color.parseColor("#121316"));
        gd.setCornerRadius(dp(12));
        scroll.setBackground(gd);

        output = new TextView(this);
        output.setTextColor(Color.parseColor("#81C784"));
        output.setTextSize(12);
        output.setTypeface(Typeface.MONOSPACE);
        output.setPadding(dp(12), dp(12), dp(12), dp(12));
        scroll.addView(output);

        root.addView(scroll, new LinearLayout.LayoutParams(-1, 0, 1.0f));
        setContentView(root);
    }

    @Override
    protected void onStart() {
        super.onStart();
        controller.addListener(this);
        refresh();
    }

    @Override
    protected void onStop() {
        controller.removeListener(this);
        super.onStop();
    }

    private void refresh() {
        if (output != null && controller != null) {
            output.setText(controller.getLogs());
        }
    }

    @Override
    public void onServerChanged(JellyfinController.State state) {
        runOnUiThread(this::refresh);
    }

    private boolean isDarkTheme() {
        int currentNightMode = getResources().getConfiguration().uiMode & android.content.res.Configuration.UI_MODE_NIGHT_MASK;
        return currentNightMode == android.content.res.Configuration.UI_MODE_NIGHT_YES;
    }

    private Button createPixelButton(String text, int bgColor, int textColor) {
        Button b = new Button(this);
        b.setText(text);
        b.setAllCaps(false);
        b.setTextSize(14);
        b.setTextColor(textColor);
        b.setTypeface(Typeface.create("sans-serif-medium", Typeface.BOLD));
        GradientDrawable gd = new GradientDrawable();
        gd.setColor(bgColor);
        gd.setCornerRadius(dp(20));
        b.setBackground(gd);
        return b;
    }

    private int dp(int val) {
        return (int) (val * getResources().getDisplayMetrics().density);
    }
}
