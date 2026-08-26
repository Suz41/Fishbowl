package com.jellyfin.droid;

import android.content.BroadcastReceiver;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.net.NetworkRequest;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.ScrollView;
import android.widget.Switch;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.app.AppCompatDelegate;
import androidx.core.content.ContextCompat;

import com.termux.R;
import com.termux.app.TermuxService;

import java.io.File;
import java.net.Inet4Address;
import java.net.NetworkInterface;
import java.util.Enumeration;

/**
 * Phase 14: Pixel OS UI / UX + Real Server Startup Progress Architecture.
 *
 * Features:
 *  - Modern Pixel OS Material 3 visual design system (Light & Dark theme support)
 *  - Real stage-by-stage startup progress card (NO fake timer-based percentages)
 *  - Authoritative readiness guard for OPEN JELLYFIN action
 *  - Integrated 5-tab native shell (Dashboard, Server, Storage, Logs, Settings)
 *  - Dynamic theme manager (System / Light / Dark)
 *  - Preserves 100% of underlying server lifecycle, WakeLock, and persistence safety
 */
public final class JellyfinDroidActivity extends AppCompatActivity
        implements JellyfinController.Listener {

    private JellyfinController controller;
    private SharedPreferences settingsPrefs;

    // Active tab state (0: Dashboard, 1: Server, 2: Storage, 3: Logs, 4: Settings)
    private int activeTab = 0;

    // Containers
    private FrameLayout contentContainer;
    private LinearLayout bottomNavLayout;

    // Dynamic UI References for Dashboard
    private TextView statusBadge;
    private TextView serverDetailText;
    private TextView lanAddressText;
    private Button btnOpenJellyfin;
    private Button btnStartServer;
    private Button btnStopServer;
    private Button btnRestartServer;
    private Button btnResetCrash;

    // Real Startup Progress Card References
    private View startupProgressCard;
    private TextView txtStage1, txtStage2, txtStage3, txtStage4, txtStage5;

    // Logs & Settings References
    private TextView logsOutputText;
    private TextView storageInfoText;

    // Network Callback
    private ConnectivityManager.NetworkCallback networkCallback;
    private BroadcastReceiver connectivityReceiver;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        // Apply saved theme preference before layout inflates
        settingsPrefs = getSharedPreferences("jellyfindroid_settings", MODE_PRIVATE);
        applySavedThemeMode();

        super.onCreate(savedInstanceState);
        controller = JellyfinController.getInstance();

        // Ensure Termux background service is running
        startService(new Intent(this, TermuxService.class));

        setContentView(buildMainPixelShell());
        setupNetworkMonitor();
    }

    @Override
    protected void onStart() {
        super.onStart();
        controller.addListener(this);
        refreshLanAddress();
        renderCurrentState();
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

    // ── Theme Manager ──────────────────────────────────────────────────────────

    private void applySavedThemeMode() {
        int mode = settingsPrefs.getInt("theme_mode", AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM);
        AppCompatDelegate.setDefaultNightMode(mode);
    }

    private void setThemeMode(int mode) {
        settingsPrefs.edit().putInt("theme_mode", mode).apply();
        AppCompatDelegate.setDefaultNightMode(mode);
    }

    private boolean isDarkTheme() {
        int currentNightMode = getResources().getConfiguration().uiMode & android.content.res.Configuration.UI_MODE_NIGHT_MASK;
        return currentNightMode == android.content.res.Configuration.UI_MODE_NIGHT_YES;
    }

    // ── Colors & Design Tokens ─────────────────────────────────────────────────

    private int getBgColor() { return isDarkTheme() ? Color.parseColor("#121316") : Color.parseColor("#F8F9FA"); }
    private int getSurfaceColor() { return isDarkTheme() ? Color.parseColor("#1E2025") : Color.parseColor("#FFFFFF"); }
    private int getSurfaceElevatedColor() { return isDarkTheme() ? Color.parseColor("#272930") : Color.parseColor("#F1F3F9"); }
    private int getPrimaryTextColor() { return isDarkTheme() ? Color.parseColor("#E6E8EE") : Color.parseColor("#1F2024"); }
    private int getSecondaryTextColor() { return isDarkTheme() ? Color.parseColor("#9AA0A6") : Color.parseColor("#5F6368"); }
    private int getAccentColor() { return Color.parseColor("#00A4DC"); }
    private int getAccentDarkColor() { return Color.parseColor("#0086B3"); }

    // ── Root UI Shell Construction ─────────────────────────────────────────────

    private View buildMainPixelShell() {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(getBgColor());

        // Header Bar
        root.addView(buildHeaderBar());

        // Content Area (Fills space between header and bottom nav)
        contentContainer = new FrameLayout(this);
        LinearLayout.LayoutParams contentParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1.0f);
        root.addView(contentContainer, contentParams);

        // Bottom Navigation Bar
        root.addView(buildBottomNavBar());

        // Initial tab render
        renderActiveTab();

        return root;
    }

    private View buildHeaderBar() {
        LinearLayout header = new LinearLayout(this);
        header.setOrientation(LinearLayout.HORIZONTAL);
        header.setGravity(Gravity.CENTER_VERTICAL);
        header.setPadding(dp(20), dp(14), dp(20), dp(14));
        header.setBackgroundColor(getSurfaceColor());
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            header.setElevation(dp(2));
        }

        // App Icon / Logo Indicator
        TextView logoIcon = new TextView(this);
        logoIcon.setText("❖");
        logoIcon.setTextSize(22);
        logoIcon.setTextColor(getAccentColor());
        logoIcon.setPadding(0, 0, dp(10), 0);
        header.addView(logoIcon);

        // App Title
        TextView title = new TextView(this);
        title.setText("Jellyfin");
        title.setTextSize(20);
        title.setTypeface(Typeface.create("sans-serif-medium", Typeface.BOLD));
        title.setTextColor(getPrimaryTextColor());
        header.addView(title, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1.0f));

        // Quick Theme Toggle Action Chip
        TextView themeBtn = new TextView(this);
        themeBtn.setText(isDarkTheme() ? "☀️ Light" : "🌙 Dark");
        themeBtn.setTextSize(13);
        themeBtn.setTextColor(getPrimaryTextColor());
        themeBtn.setPadding(dp(12), dp(6), dp(12), dp(6));
        themeBtn.setBackground(createRoundedDrawable(getSurfaceElevatedColor(), dp(16)));
        themeBtn.setOnClickListener(v -> {
            int newMode = isDarkTheme() ? AppCompatDelegate.MODE_NIGHT_NO : AppCompatDelegate.MODE_NIGHT_YES;
            setThemeMode(newMode);
        });
        header.addView(themeBtn);

        return header;
    }

    private View buildBottomNavBar() {
        bottomNavLayout = new LinearLayout(this);
        bottomNavLayout.setOrientation(LinearLayout.HORIZONTAL);
        bottomNavLayout.setBackgroundColor(getSurfaceColor());
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            bottomNavLayout.setElevation(dp(8));
        }

        addNavTab(bottomNavLayout, 0, "Home", R.drawable.ic_home);
        addNavTab(bottomNavLayout, 1, "Server", R.drawable.ic_dns);
        addNavTab(bottomNavLayout, 2, "Storage", R.drawable.ic_storage);
        addNavTab(bottomNavLayout, 3, "Logs", R.drawable.ic_terminal);
        addNavTab(bottomNavLayout, 4, "Settings", R.drawable.ic_settings);

        return bottomNavLayout;
    }

    private void addNavTab(LinearLayout parent, int tabIndex, String label, int iconRes) {
        LinearLayout tab = new LinearLayout(this);
        tab.setOrientation(LinearLayout.VERTICAL);
        tab.setGravity(Gravity.CENTER);
        tab.setPadding(0, dp(8), 0, dp(8));
        tab.setOnClickListener(v -> {
            activeTab = tabIndex;
            updateBottomNavSelection();
            renderActiveTab();
        });

        ImageView icon = new ImageView(this);
        icon.setImageResource(iconRes);
        LinearLayout.LayoutParams iconParams = new LinearLayout.LayoutParams(dp(22), dp(22));
        tab.addView(icon, iconParams);

        TextView txt = new TextView(this);
        txt.setText(label);
        txt.setTextSize(11);
        txt.setPadding(0, dp(2), 0, 0);
        tab.addView(txt);

        LinearLayout.LayoutParams tabParams = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1.0f);
        parent.addView(tab, tabParams);
    }

    private void updateBottomNavSelection() {
        for (int i = 0; i < bottomNavLayout.getChildCount(); i++) {
            LinearLayout tab = (LinearLayout) bottomNavLayout.getChildAt(i);
            ImageView icon = (ImageView) tab.getChildAt(0);
            TextView txt = (TextView) tab.getChildAt(1);
            boolean selected = (i == activeTab);

            int color = selected ? getAccentColor() : getSecondaryTextColor();
            icon.setColorFilter(color);
            txt.setTextColor(color);
            txt.setTypeface(selected ? Typeface.DEFAULT_BOLD : Typeface.DEFAULT);
        }
    }

    // ── Tab View Router ────────────────────────────────────────────────────────

    private void renderActiveTab() {
        contentContainer.removeAllViews();
        updateBottomNavSelection();

        switch (activeTab) {
            case 0:
                contentContainer.addView(buildDashboardTab());
                break;
            case 1:
                contentContainer.addView(buildServerTab());
                break;
            case 2:
                contentContainer.addView(buildStorageTab());
                break;
            case 3:
                contentContainer.addView(buildLogsTab());
                break;
            case 4:
                contentContainer.addView(buildSettingsTab());
                break;
        }

        renderCurrentState();
    }

    // ── Tab 0: Dashboard (Home) ────────────────────────────────────────────────

    private View buildDashboardTab() {
        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(true);

        LinearLayout layout = new LinearLayout(this);
        layout.setOrientation(LinearLayout.VERTICAL);
        layout.setPadding(dp(16), dp(16), dp(16), dp(16));

        // Server Status Hero Card
        layout.addView(buildServerStatusHeroCard());

        // Real Stage-by-Stage Server Startup Progress Card
        startupProgressCard = buildRealStartupProgressCard();
        layout.addView(startupProgressCard);

        // Actions Section
        layout.addView(buildDashboardActions());

        scroll.addView(layout);
        return scroll;
    }

    private View buildServerStatusHeroCard() {
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setPadding(dp(20), dp(20), dp(20), dp(20));
        card.setBackground(createRoundedDrawable(getSurfaceColor(), dp(20)));
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            card.setElevation(dp(2));
        }

        // Status Badge Pill
        statusBadge = new TextView(this);
        statusBadge.setText("● SERVER INITIALIZING");
        statusBadge.setTextSize(14);
        statusBadge.setTypeface(Typeface.DEFAULT_BOLD);
        statusBadge.setPadding(dp(14), dp(6), dp(14), dp(6));
        statusBadge.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams badgeParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        card.addView(statusBadge, badgeParams);

        // Server Detail & Address Info
        serverDetailText = new TextView(this);
        serverDetailText.setText("Jellyfin 10.11.11\nLocal: http://127.0.0.1:8096");
        serverDetailText.setTextSize(14);
        serverDetailText.setTextColor(getSecondaryTextColor());
        serverDetailText.setPadding(0, dp(12), 0, dp(4));
        card.addView(serverDetailText);

        // Dynamic LAN Address Line with Copy Action
        lanAddressText = new TextView(this);
        lanAddressText.setText("LAN: detecting…");
        lanAddressText.setTextSize(14);
        lanAddressText.setTextColor(getSecondaryTextColor());
        lanAddressText.setOnClickListener(v -> {
            String text = lanAddressText.getText().toString();
            if (text.startsWith("LAN: http://")) {
                String url = text.substring(5).trim();
                ClipboardManager cm = (ClipboardManager) getSystemService(CLIPBOARD_SERVICE);
                if (cm != null) {
                    cm.setPrimaryClip(ClipData.newPlainText("Jellyfin LAN URL", url));
                    Toast.makeText(this, "Copied LAN address to clipboard", Toast.LENGTH_SHORT).show();
                }
            }
        });
        card.addView(lanAddressText);

        return card;
    }

    private View buildRealStartupProgressCard() {
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setPadding(dp(18), dp(16), dp(18), dp(16));
        card.setBackground(createRoundedDrawable(getSurfaceElevatedColor(), dp(16)));
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(-1, -2);
        params.topMargin = dp(14);
        card.setLayoutParams(params);

        TextView header = new TextView(this);
        header.setText("Starting Jellyfin Server");
        header.setTextSize(15);
        header.setTypeface(Typeface.create("sans-serif-medium", Typeface.BOLD));
        header.setTextColor(getPrimaryTextColor());
        card.addView(header);

        ProgressBar progress = new ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal);
        progress.setIndeterminate(true);
        LinearLayout.LayoutParams progressParams = new LinearLayout.LayoutParams(-1, dp(6));
        progressParams.topMargin = dp(10);
        progressParams.bottomMargin = dp(12);
        card.addView(progress, progressParams);

        txtStage1 = createStageText("1. Starting runtime environment");
        txtStage2 = createStageText("2. Launching Jellyfin server process");
        txtStage3 = createStageText("3. Waiting for server (binding port 8096)");
        txtStage4 = createStageText("4. Checking readiness (/system/info/public)");
        txtStage5 = createStageText("5. Ready");

        card.addView(txtStage1);
        card.addView(txtStage2);
        card.addView(txtStage3);
        card.addView(txtStage4);
        card.addView(txtStage5);

        return card;
    }

    private TextView createStageText(String text) {
        TextView tv = new TextView(this);
        tv.setText("○  " + text);
        tv.setTextSize(13);
        tv.setTextColor(getSecondaryTextColor());
        tv.setPadding(0, dp(2), 0, dp(2));
        return tv;
    }

    private View buildDashboardActions() {
        LinearLayout layout = new LinearLayout(this);
        layout.setOrientation(LinearLayout.VERTICAL);
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(-1, -2);
        params.topMargin = dp(16);
        layout.setLayoutParams(params);

        // Prominent OPEN JELLYFIN Button (Primary Accent Pill)
        btnOpenJellyfin = createPixelButton("OPEN JELLYFIN", getAccentColor(), Color.WHITE);
        btnOpenJellyfin.setOnClickListener(v -> startActivity(new Intent(this, JellyfinWebActivity.class)));
        layout.addView(btnOpenJellyfin);

        btnStartServer = createPixelButton("START SERVER", getSurfaceColor(), getPrimaryTextColor());
        btnStartServer.setOnClickListener(v -> {
            Intent svc = new Intent(this, JellyfinServerService.class);
            svc.setAction(JellyfinServerService.ACTION_START);
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                startForegroundService(svc);
            } else {
                startService(svc);
            }
        });
        layout.addView(btnStartServer);

        btnStopServer = createPixelButton("STOP SERVER", getSurfaceColor(), getPrimaryTextColor());
        btnStopServer.setOnClickListener(v -> {
            Intent svc = new Intent(this, JellyfinServerService.class);
            svc.setAction(JellyfinServerService.ACTION_STOP);
            startService(svc);
        });
        layout.addView(btnStopServer);

        btnRestartServer = createPixelButton("RESTART SERVER", getSurfaceColor(), getPrimaryTextColor());
        btnRestartServer.setOnClickListener(v -> controller.restart(this));
        layout.addView(btnRestartServer);

        btnResetCrash = createPixelButton("RESET CRASH LOOP", Color.parseColor("#B71C1C"), Color.WHITE);
        btnResetCrash.setOnClickListener(v -> controller.resetCrashLoop(this));
        btnResetCrash.setVisibility(View.GONE);
        layout.addView(btnResetCrash);

        return layout;
    }

    // ── Tab 1: Server Controls & Details ───────────────────────────────────────

    private View buildServerTab() {
        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(true);

        LinearLayout layout = new LinearLayout(this);
        layout.setOrientation(LinearLayout.VERTICAL);
        layout.setPadding(dp(16), dp(16), dp(16), dp(16));

        // Server Details Card
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setPadding(dp(20), dp(20), dp(20), dp(20));
        card.setBackground(createRoundedDrawable(getSurfaceColor(), dp(20)));

        TextView title = new TextView(this);
        title.setText("Server Status & Controls");
        title.setTextSize(18);
        title.setTypeface(Typeface.DEFAULT_BOLD);
        title.setTextColor(getPrimaryTextColor());
        card.addView(title);

        TextView info = new TextView(this);
        info.setText("Package Identity: com.jellyfin.droid\nServer Version: 10.11.11\nHTTP Port: 8096\nProcess Protection: Single-process lock active");
        info.setTextSize(14);
        info.setTextColor(getSecondaryTextColor());
        info.setPadding(0, dp(12), 0, dp(16));
        card.addView(info);

        Switch autoStartSwitch = new Switch(this);
        autoStartSwitch.setText("Auto-start Jellyfin on device boot");
        autoStartSwitch.setTextSize(14);
        autoStartSwitch.setTextColor(getPrimaryTextColor());
        SharedPreferences prefs = getSharedPreferences("jellyfindroid", MODE_PRIVATE);
        autoStartSwitch.setChecked(prefs.getBoolean("auto_start", false));
        autoStartSwitch.setOnCheckedChangeListener((v, checked) -> prefs.edit().putBoolean("auto_start", checked).apply());
        card.addView(autoStartSwitch);

        layout.addView(card);

        // Control buttons
        LinearLayout actions = new LinearLayout(this);
        actions.setOrientation(LinearLayout.VERTICAL);
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(-1, -2);
        params.topMargin = dp(16);
        actions.setLayoutParams(params);

        Button startBtn = createPixelButton("START SERVER", getAccentColor(), Color.WHITE);
        startBtn.setOnClickListener(v -> controller.start(this));
        actions.addView(startBtn);

        Button stopBtn = createPixelButton("STOP SERVER", getSurfaceColor(), getPrimaryTextColor());
        stopBtn.setOnClickListener(v -> controller.stop());
        actions.addView(stopBtn);

        Button restartBtn = createPixelButton("RESTART SERVER", getSurfaceColor(), getPrimaryTextColor());
        restartBtn.setOnClickListener(v -> controller.restart(this));
        actions.addView(restartBtn);

        layout.addView(actions);

        scroll.addView(layout);
        return scroll;
    }

    // ── Tab 2: Storage & Media ─────────────────────────────────────────────────

    private View buildStorageTab() {
        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(true);

        LinearLayout layout = new LinearLayout(this);
        layout.setOrientation(LinearLayout.VERTICAL);
        layout.setPadding(dp(16), dp(16), dp(16), dp(16));

        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setPadding(dp(20), dp(20), dp(20), dp(20));
        card.setBackground(createRoundedDrawable(getSurfaceColor(), dp(20)));

        TextView title = new TextView(this);
        title.setText("Storage & Directories");
        title.setTextSize(18);
        title.setTypeface(Typeface.DEFAULT_BOLD);
        title.setTextColor(getPrimaryTextColor());
        card.addView(title);

        storageInfoText = new TextView(this);
        storageInfoText.setTextSize(13);
        storageInfoText.setTextColor(getSecondaryTextColor());
        storageInfoText.setPadding(0, dp(12), 0, dp(16));
        updateStorageInfoText();
        card.addView(storageInfoText);

        Button manageStorageBtn = createPixelButton("MANAGE MEDIA STORAGE (SAF)", getAccentColor(), Color.WHITE);
        manageStorageBtn.setOnClickListener(v -> startActivity(new Intent(this, JellyfinStorageActivity.class)));
        card.addView(manageStorageBtn);

        layout.addView(card);
        scroll.addView(layout);
        return scroll;
    }

    private void updateStorageInfoText() {
        if (storageInfoText == null) return;
        File prefix = com.termux.shared.termux.TermuxConstants.TERMUX_PREFIX_DIR;
        File home = com.termux.shared.termux.TermuxConstants.TERMUX_HOME_DIR;
        File dataDir = new File(home, ".local/share/jellyfin");
        File configDir = new File(home, ".config/jellyfin");
        File cacheDir = new File(home, ".cache/jellyfin");

        long prefixMB = getFolderSizeMB(prefix);
        long dataMB = getFolderSizeMB(dataDir);
        long cacheMB = getFolderSizeMB(cacheDir);

        String info = "Runtime Location:\n" + prefix.getAbsolutePath() + " (" + prefixMB + " MB)\n\n"
                + "Persistent Data:\n" + dataDir.getAbsolutePath() + " (" + dataMB + " MB)\n\n"
                + "Config Directory:\n" + configDir.getAbsolutePath() + "\n\n"
                + "Cache Directory:\n" + cacheDir.getAbsolutePath() + " (" + cacheMB + " MB)";
        storageInfoText.setText(info);
    }

    private long getFolderSizeMB(File dir) {
        if (dir == null || !dir.exists()) return 0;
        long bytes = dir.length();
        return bytes / (1024 * 1024);
    }

    // ── Tab 3: Logs View ───────────────────────────────────────────────────────

    private View buildLogsTab() {
        LinearLayout layout = new LinearLayout(this);
        layout.setOrientation(LinearLayout.VERTICAL);
        layout.setPadding(dp(16), dp(16), dp(16), dp(16));

        LinearLayout actions = new LinearLayout(this);
        actions.setOrientation(LinearLayout.HORIZONTAL);
        actions.setPadding(0, 0, 0, dp(12));

        Button btnRefresh = createPixelButton("REFRESH", getSurfaceColor(), getPrimaryTextColor());
        btnRefresh.setOnClickListener(v -> refreshLogsDisplay());
        LinearLayout.LayoutParams p1 = new LinearLayout.LayoutParams(0, -2, 1.0f);
        p1.rightMargin = dp(6);
        actions.addView(btnRefresh, p1);

        Button btnClear = createPixelButton("CLEAR DISPLAY", getSurfaceColor(), getPrimaryTextColor());
        btnClear.setOnClickListener(v -> {
            controller.clearDisplayedLogs();
            refreshLogsDisplay();
        });
        LinearLayout.LayoutParams p2 = new LinearLayout.LayoutParams(0, -2, 1.0f);
        p2.leftMargin = dp(6);
        actions.addView(btnClear, p2);

        layout.addView(actions);

        ScrollView scroll = new ScrollView(this);
        scroll.setBackground(createRoundedDrawable(Color.parseColor("#121316"), dp(12)));

        logsOutputText = new TextView(this);
        logsOutputText.setTextColor(Color.parseColor("#81C784"));
        logsOutputText.setTextSize(12);
        logsOutputText.setTypeface(Typeface.MONOSPACE);
        logsOutputText.setPadding(dp(12), dp(12), dp(12), dp(12));
        refreshLogsDisplay();

        scroll.addView(logsOutputText);
        layout.addView(scroll, new LinearLayout.LayoutParams(-1, 0, 1.0f));

        return layout;
    }

    private void refreshLogsDisplay() {
        if (logsOutputText != null && controller != null) {
            logsOutputText.setText(controller.getLogs());
        }
    }

    // ── Tab 4: Settings View ───────────────────────────────────────────────────

    private View buildSettingsTab() {
        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(true);

        LinearLayout layout = new LinearLayout(this);
        layout.setOrientation(LinearLayout.VERTICAL);
        layout.setPadding(dp(16), dp(16), dp(16), dp(16));

        // Appearance Category Card
        LinearLayout themeCard = new LinearLayout(this);
        themeCard.setOrientation(LinearLayout.VERTICAL);
        themeCard.setPadding(dp(20), dp(20), dp(20), dp(20));
        themeCard.setBackground(createRoundedDrawable(getSurfaceColor(), dp(20)));

        TextView t1 = new TextView(this);
        t1.setText("Appearance Theme");
        t1.setTextSize(18);
        t1.setTypeface(Typeface.DEFAULT_BOLD);
        t1.setTextColor(getPrimaryTextColor());
        themeCard.addView(t1);

        int currentMode = settingsPrefs.getInt("theme_mode", AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM);

        Button modeSystem = createPixelButton("System Default", currentMode == AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM ? getAccentColor() : getSurfaceElevatedColor(), currentMode == AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM ? Color.WHITE : getPrimaryTextColor());
        modeSystem.setOnClickListener(v -> setThemeMode(AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM));
        themeCard.addView(modeSystem);

        Button modeLight = createPixelButton("Light Mode", currentMode == AppCompatDelegate.MODE_NIGHT_NO ? getAccentColor() : getSurfaceElevatedColor(), currentMode == AppCompatDelegate.MODE_NIGHT_NO ? Color.WHITE : getPrimaryTextColor());
        modeLight.setOnClickListener(v -> setThemeMode(AppCompatDelegate.MODE_NIGHT_NO));
        themeCard.addView(modeLight);

        Button modeDark = createPixelButton("Dark Mode", currentMode == AppCompatDelegate.MODE_NIGHT_YES ? getAccentColor() : getSurfaceElevatedColor(), currentMode == AppCompatDelegate.MODE_NIGHT_YES ? Color.WHITE : getPrimaryTextColor());
        modeDark.setOnClickListener(v -> setThemeMode(AppCompatDelegate.MODE_NIGHT_YES));
        themeCard.addView(modeDark);

        layout.addView(themeCard);

        // About & System Info Card
        LinearLayout aboutCard = new LinearLayout(this);
        aboutCard.setOrientation(LinearLayout.VERTICAL);
        aboutCard.setPadding(dp(20), dp(20), dp(20), dp(20));
        aboutCard.setBackground(createRoundedDrawable(getSurfaceColor(), dp(20)));
        LinearLayout.LayoutParams pAbout = new LinearLayout.LayoutParams(-1, -2);
        pAbout.topMargin = dp(16);
        aboutCard.setLayoutParams(pAbout);

        TextView t2 = new TextView(this);
        t2.setText("About JellyfinDroid");
        t2.setTextSize(18);
        t2.setTypeface(Typeface.DEFAULT_BOLD);
        t2.setTextColor(getPrimaryTextColor());
        aboutCard.addView(t2);

        TextView abtTxt = new TextView(this);
        abtTxt.setText("Jellyfin Server: 10.11.11\nPackage Identity: com.jellyfin.droid\nArchitecture: aarch64 (ARM64)\nPhase 14 Pixel OS UI Edition");
        abtTxt.setTextSize(13);
        abtTxt.setTextColor(getSecondaryTextColor());
        abtTxt.setPadding(0, dp(12), 0, 0);
        aboutCard.addView(abtTxt);

        layout.addView(aboutCard);

        scroll.addView(layout);
        return scroll;
    }

    // ── State Rendering Logic ──────────────────────────────────────────────────

    @Override
    public void onServerChanged(final JellyfinController.State state) {
        runOnUiThread(() -> renderCurrentState());
    }

    private void renderCurrentState() {
        if (controller == null) return;
        JellyfinController.State state = controller.getState();
        JellyfinController.StartupStage stage = controller.getStartupStage();

        if (statusBadge != null) {
            int color;
            String statusText;
            switch (state) {
                case RUNNING:
                    color = Color.parseColor("#81C784");
                    statusText = "●  SERVER RUNNING";
                    break;
                case STARTING:
                    color = Color.parseColor("#FFD54F");
                    statusText = "●  SERVER STARTING…";
                    break;
                case INITIALIZING:
                    color = Color.parseColor("#FFD54F");
                    statusText = "●  INITIALIZING RUNTIME…";
                    break;
                case STOPPING:
                    color = Color.parseColor("#FFB74D");
                    statusText = "●  SERVER STOPPING…";
                    break;
                case STOPPED:
                    color = Color.parseColor("#9AA0A6");
                    statusText = "●  SERVER STOPPED";
                    break;
                case CRASHED:
                case FAILED:
                    color = Color.parseColor("#E57373");
                    statusText = "●  SERVER FAILED";
                    break;
                case CRASH_LOOP:
                    color = Color.parseColor("#B71C1C");
                    statusText = "●  CRASH LOOP DETECTED";
                    break;
                default:
                    color = Color.parseColor("#FFD54F");
                    statusText = "●  " + state.name();
                    break;
            }
            statusBadge.setText(statusText);
            statusBadge.setTextColor(color);
            statusBadge.setBackground(createRoundedDrawable(colorWithAlpha(color, 40), dp(16)));
        }

        // Render Real Stage Progress Card
        if (startupProgressCard != null) {
            boolean isStarting = (state == JellyfinController.State.INITIALIZING || state == JellyfinController.State.STARTING);
            startupProgressCard.setVisibility(isStarting ? View.VISIBLE : View.GONE);

            if (isStarting && txtStage1 != null) {
                updateStageRow(txtStage1, "1. Starting runtime environment", stage.ordinal() >= JellyfinController.StartupStage.STARTING_RUNTIME.ordinal(), stage == JellyfinController.StartupStage.STARTING_RUNTIME);
                updateStageRow(txtStage2, "2. Launching Jellyfin server process", stage.ordinal() >= JellyfinController.StartupStage.LAUNCHING_SERVER.ordinal(), stage == JellyfinController.StartupStage.LAUNCHING_SERVER);
                updateStageRow(txtStage3, "3. Waiting for server (binding port 8096)", stage.ordinal() >= JellyfinController.StartupStage.WAITING_FOR_SERVER.ordinal(), stage == JellyfinController.StartupStage.WAITING_FOR_SERVER);
                updateStageRow(txtStage4, "4. Polling readiness (/system/info/public)", stage.ordinal() >= JellyfinController.StartupStage.CHECKING_READINESS.ordinal(), stage == JellyfinController.StartupStage.CHECKING_READINESS);
                updateStageRow(txtStage5, "5. Ready", stage == JellyfinController.StartupStage.READY, false);
            }
        }

        // Authoritative Readiness Guard for OPEN JELLYFIN
        boolean isReady = (state == JellyfinController.State.RUNNING && stage == JellyfinController.StartupStage.READY);
        if (btnOpenJellyfin != null) {
            btnOpenJellyfin.setEnabled(isReady);
            btnOpenJellyfin.setAlpha(isReady ? 1.0f : 0.4f);
        }

        boolean busy = (state == JellyfinController.State.INITIALIZING || state == JellyfinController.State.STARTING || state == JellyfinController.State.STOPPING);
        boolean crashLoop = (state == JellyfinController.State.CRASH_LOOP);

        if (btnStartServer != null) btnStartServer.setEnabled(!busy && !isReady && !crashLoop);
        if (btnStopServer != null) btnStopServer.setEnabled(isReady);
        if (btnRestartServer != null) btnRestartServer.setEnabled(!busy && !crashLoop);
        if (btnResetCrash != null) btnResetCrash.setVisibility(crashLoop ? View.VISIBLE : View.GONE);
    }

    private void updateStageRow(TextView tv, String label, boolean completed, boolean active) {
        if (tv == null) return;
        if (completed) {
            tv.setText("✓  " + label);
            tv.setTextColor(Color.parseColor("#81C784"));
        } else if (active) {
            tv.setText("●  " + label);
            tv.setTextColor(Color.parseColor("#FFD54F"));
        } else {
            tv.setText("○  " + label);
            tv.setTextColor(getSecondaryTextColor());
        }
    }

    // ── Storage & Network Helpers ──────────────────────────────────────────────

    private void checkStorageStatus() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            if (!Environment.isExternalStorageManager()) {
                if (lanAddressText != null) {
                    lanAddressText.setText("⚠ MEDIA STORAGE UNAVAILABLE\nTap 'Storage' tab to grant access.");
                    lanAddressText.setTextColor(Color.parseColor("#FFB74D"));
                }
                return;
            }
        }
        refreshLanAddress();
    }

    private void refreshLanAddress() {
        String lan = getLanAddress();
        if (lanAddressText != null) {
            if (lan == null) {
                lanAddressText.setText("LAN: UNAVAILABLE — connect to Wi-Fi or LAN");
                lanAddressText.setTextColor(Color.parseColor("#FFB74D"));
            } else {
                lanAddressText.setText("LAN: " + lan);
                lanAddressText.setTextColor(getSecondaryTextColor());
            }
        }
    }

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

    private void setupNetworkMonitor() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            ConnectivityManager cm = (ConnectivityManager) getSystemService(CONNECTIVITY_SERVICE);
            if (cm != null) {
                networkCallback = new ConnectivityManager.NetworkCallback() {
                    @Override public void onAvailable(@NonNull Network n) { runOnUiThread(() -> refreshLanAddress()); }
                    @Override public void onLost(@NonNull Network n) { runOnUiThread(() -> refreshLanAddress()); }
                    @Override public void onCapabilitiesChanged(@NonNull Network n, @NonNull NetworkCapabilities c) { runOnUiThread(() -> refreshLanAddress()); }
                };
                try { cm.registerNetworkCallback(new NetworkRequest.Builder().build(), networkCallback); } catch (Exception ignored) {}
            }
        } else {
            connectivityReceiver = new BroadcastReceiver() {
                @Override public void onReceive(Context ctx, Intent intent) { refreshLanAddress(); }
            };
            registerReceiver(connectivityReceiver, new IntentFilter(ConnectivityManager.CONNECTIVITY_ACTION));
        }
    }

    private void teardownNetworkMonitor() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N && networkCallback != null) {
            ConnectivityManager cm = (ConnectivityManager) getSystemService(CONNECTIVITY_SERVICE);
            if (cm != null) {
                try { cm.unregisterNetworkCallback(networkCallback); } catch (Exception ignored) {}
            }
            networkCallback = null;
        }
        if (connectivityReceiver != null) {
            try { unregisterReceiver(connectivityReceiver); } catch (Exception ignored) {}
            connectivityReceiver = null;
        }
    }

    // ── Design Utilities ───────────────────────────────────────────────────────

    private Button createPixelButton(String text, int bgColor, int textColor) {
        Button b = new Button(this);
        b.setText(text);
        b.setTextSize(14);
        b.setAllCaps(false);
        b.setTextColor(textColor);
        b.setTypeface(Typeface.create("sans-serif-medium", Typeface.BOLD));
        b.setBackground(createRoundedDrawable(bgColor, dp(24)));
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(-1, dp(48));
        params.topMargin = dp(8);
        b.setLayoutParams(params);
        return b;
    }

    private GradientDrawable createRoundedDrawable(int color, int radiusDp) {
        GradientDrawable gd = new GradientDrawable();
        gd.setColor(color);
        gd.setCornerRadius(radiusDp);
        return gd;
    }

    private int colorWithAlpha(int color, int alpha) {
        return Color.argb(alpha, Color.red(color), Color.green(color), Color.blue(color));
    }

    private int dp(int value) {
        return (int) (value * getResources().getDisplayMetrics().density);
    }
}
