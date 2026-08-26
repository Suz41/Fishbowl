package com.jellyfin.droid;

import android.content.SharedPreferences;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.Switch;
import android.widget.TextView;

import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.app.AppCompatDelegate;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowCompat;
import androidx.core.view.WindowInsetsCompat;
import androidx.core.view.WindowInsetsControllerCompat;

import com.termux.shared.termux.TermuxConstants;

import java.io.File;

public final class JellyfinSettingsActivity extends AppCompatActivity {
    private LinearLayout root;

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        setupSystemBars();

        ScrollView scroll = new ScrollView(this);
        scroll.setBackgroundColor(isDarkTheme() ? Color.parseColor("#121316") : Color.parseColor("#F8F9FA"));

        root = new LinearLayout(this);
        root.setPadding(dp(20), dp(20), dp(20), dp(20));
        root.setOrientation(LinearLayout.VERTICAL);

        ViewCompat.setOnApplyWindowInsetsListener(root, (v, insets) -> {
            androidx.core.graphics.Insets statusBarInset = insets.getInsets(WindowInsetsCompat.Type.statusBars());
            androidx.core.graphics.Insets navBarInset = insets.getInsets(WindowInsetsCompat.Type.navigationBars());
            v.setPadding(dp(20), statusBarInset.top + dp(16), dp(20), navBarInset.bottom + dp(16));
            return insets;
        });

        TextView title = new TextView(this);
        title.setText("Settings");
        title.setTextSize(24);
        title.setTypeface(Typeface.create("sans-serif-medium", Typeface.BOLD));
        title.setTextColor(isDarkTheme() ? Color.parseColor("#E6E8EE") : Color.parseColor("#1F2024"));
        title.setPadding(0, 0, 0, dp(16));
        root.addView(title);

        // Server Category Card
        LinearLayout serverCard = createCard();
        addSectionHeader(serverCard, "SERVER");
        addBodyText(serverCard, "Jellyfin Server: 10.11.11\nAddress: 127.0.0.1\nPort: 8096");

        SharedPreferences prefs = getSharedPreferences("jellyfindroid", MODE_PRIVATE);
        Switch auto = new Switch(this);
        auto.setText("Auto-start Jellyfin on boot");
        auto.setTextSize(14);
        auto.setTextColor(isDarkTheme() ? Color.parseColor("#E6E8EE") : Color.parseColor("#1F2024"));
        auto.setPadding(0, dp(10), 0, dp(10));
        auto.setChecked(prefs.getBoolean("auto_start", false));
        auto.setOnCheckedChangeListener((v, checked) -> prefs.edit().putBoolean("auto_start", checked).apply());
        serverCard.addView(auto);

        root.addView(serverCard);

        // Appearance Card
        LinearLayout themeCard = createCard();
        addSectionHeader(themeCard, "APPEARANCE");
        SharedPreferences settingsPrefs = getSharedPreferences("jellyfindroid_settings", MODE_PRIVATE);
        int currentMode = settingsPrefs.getInt("theme_mode", AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM);

        Button modeSys = createPixelButton("System Default", currentMode == AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM);
        modeSys.setOnClickListener(v -> setThemeMode(AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM));
        themeCard.addView(modeSys);

        Button modeLight = createPixelButton("Light Mode", currentMode == AppCompatDelegate.MODE_NIGHT_NO);
        modeLight.setOnClickListener(v -> setThemeMode(AppCompatDelegate.MODE_NIGHT_NO));
        themeCard.addView(modeLight);

        Button modeDark = createPixelButton("Dark Mode", currentMode == AppCompatDelegate.MODE_NIGHT_YES);
        modeDark.setOnClickListener(v -> setThemeMode(AppCompatDelegate.MODE_NIGHT_YES));
        themeCard.addView(modeDark);

        root.addView(themeCard);

        // Storage & Runtime Card
        LinearLayout runtimeCard = createCard();
        addSectionHeader(runtimeCard, "STORAGE & RUNTIME");
        File prefix = TermuxConstants.TERMUX_PREFIX_DIR;
        addBodyText(runtimeCard, "Bootstrap: " + (JellyfinBootstrapper.isInitialized(this) ? "Ready" : "Not initialized") + "\nRuntime size: " + (prefix.exists() ? prefix.length() / 1024 / 1024 : 0) + " MB");

        Button btnRestart = createPixelButton("RESTART SERVER", false);
        btnRestart.setOnClickListener(v -> JellyfinController.getInstance().restart(this));
        runtimeCard.addView(btnRestart);

        root.addView(runtimeCard);

        scroll.addView(root);
        setContentView(scroll);
    }

    private void setupSystemBars() {
        WindowCompat.setDecorFitsSystemWindows(getWindow(), false);
        WindowInsetsControllerCompat controllerCompat = new WindowInsetsControllerCompat(getWindow(), getWindow().getDecorView());
        boolean isDark = isDarkTheme();
        controllerCompat.setAppearanceLightStatusBars(!isDark);
        controllerCompat.setAppearanceLightNavigationBars(!isDark);
        int color = isDark ? Color.parseColor("#121316") : Color.parseColor("#F8F9FA");
        getWindow().setStatusBarColor(color);
        getWindow().setNavigationBarColor(color);
    }

    private void setThemeMode(int mode) {
        getSharedPreferences("jellyfindroid_settings", MODE_PRIVATE).edit().putInt("theme_mode", mode).apply();
        AppCompatDelegate.setDefaultNightMode(mode);
    }

    private boolean isDarkTheme() {
        int currentNightMode = getResources().getConfiguration().uiMode & android.content.res.Configuration.UI_MODE_NIGHT_MASK;
        return currentNightMode == android.content.res.Configuration.UI_MODE_NIGHT_YES;
    }

    private LinearLayout createCard() {
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setPadding(dp(18), dp(18), dp(18), dp(18));
        GradientDrawable gd = new GradientDrawable();
        gd.setColor(isDarkTheme() ? Color.parseColor("#1E2025") : Color.parseColor("#FFFFFF"));
        gd.setCornerRadius(dp(16));
        card.setBackground(gd);
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(-1, -2);
        p.topMargin = dp(14);
        card.setLayoutParams(p);
        return card;
    }

    private void addSectionHeader(LinearLayout card, String title) {
        TextView tv = new TextView(this);
        tv.setText(title);
        tv.setTextSize(14);
        tv.setTypeface(Typeface.create("sans-serif-medium", Typeface.BOLD));
        tv.setTextColor(Color.parseColor("#00A4DC"));
        tv.setPadding(0, 0, 0, dp(6));
        card.addView(tv);
    }

    private void addBodyText(LinearLayout card, String text) {
        TextView tv = new TextView(this);
        tv.setText(text);
        tv.setTextSize(14);
        tv.setTextColor(isDarkTheme() ? Color.parseColor("#9AA0A6") : Color.parseColor("#5F6368"));
        tv.setPadding(0, 0, 0, dp(10));
        card.addView(tv);
    }

    private Button createPixelButton(String text, boolean active) {
        Button b = new Button(this);
        b.setText(text);
        b.setAllCaps(false);
        b.setTextSize(14);
        b.setTextColor(active ? Color.WHITE : (isDarkTheme() ? Color.parseColor("#E6E8EE") : Color.parseColor("#1F2024")));
        GradientDrawable gd = new GradientDrawable();
        gd.setColor(active ? Color.parseColor("#00A4DC") : (isDarkTheme() ? Color.parseColor("#272930") : Color.parseColor("#F1F3F9")));
        gd.setCornerRadius(dp(20));
        b.setBackground(gd);
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(-1, dp(44));
        p.topMargin = dp(6);
        b.setLayoutParams(p);
        return b;
    }

    private int dp(int val) { return (int) (val * getResources().getDisplayMetrics().density); }
}
