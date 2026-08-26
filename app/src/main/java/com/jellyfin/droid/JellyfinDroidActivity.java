package com.jellyfin.droid;

import android.Manifest;
import android.content.BroadcastReceiver;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
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
import android.view.Window;
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
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowCompat;
import androidx.core.view.WindowInsetsCompat;
import androidx.core.view.WindowInsetsControllerCompat;

import com.termux.R;
import com.termux.app.TermuxService;

import java.io.File;
import java.net.Inet4Address;
import java.net.NetworkInterface;
import java.util.Enumeration;

/**
 * Phase 15: JellfinDroid UI Polish, Branding, Logo, System UI & Server Notification.
 *
 * Requirements:
 *  - STRICT UI RULE: ABSOLUTELY NO EMOJIS anywhere in native UI or text.
 *  - BRANDING: JellfinDroid branding with Fellyfin logo.svg derived vector asset.
 *  - SYSTEM UI: Correct status bar & navigation bar inset handling (no red status bar!).
 *  - FOREGROUND NOTIFICATION: Synchronized with real server lifecycle states.
 *  - 5-TAB BOTTOM NAV: Polished Material 3 bottom navigation bar with active pill indicator.
 */
public final class JellyfinDroidActivity extends AppCompatActivity
        implements JellyfinController.Listener {

    private JellyfinController controller;
    private SharedPreferences settingsPrefs;

    // Active tab state (0: Home, 1: Server, 2: Storage, 3: Logs, 4: Settings)
    private int activeTab = 0;

    // Containers
    private LinearLayout rootLayout;
    private View headerBar;
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

    // Logs & Storage References
    private TextView logsOutputText;
    private TextView storageInfoText;

    // Network Callback & Receiver
    private ConnectivityManager.NetworkCallback networkCallback;
    private BroadcastReceiver connectivityReceiver;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        settingsPrefs = getSharedPreferences("jellyfindroid_settings", MODE_PRIVATE);
        applySavedThemeMode();

        super.onCreate(savedInstanceState);
        controller = JellyfinController.getInstance();

        // Start background helper service
        startService(new Intent(this, TermuxService.class));

        // Request Android 13+ Notification Permission if needed (non-blocking)
        requestNotificationPermissionIfNeeded();

        setContentView(buildMainPixelShell());
        setupSystemBarsAndInsets();
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

    // ── System Bar & Inset Management ─────────────────────────────────────────

    private void setupSystemBarsAndInsets() {
        Window window = getWindow();
        WindowCompat.setDecorFitsSystemWindows(window, false);

        WindowInsetsControllerCompat controllerCompat = new WindowInsetsControllerCompat(window, window.getDecorView());
        boolean isDark = isDarkTheme();
        controllerCompat.setAppearanceLightStatusBars(!isDark);
        controllerCompat.setAppearanceLightNavigationBars(!isDark);

        int navBarColor = getSurfaceColor();
        int statusBarColor = getSurfaceColor();
        window.setStatusBarColor(statusBarColor);
        window.setNavigationBarColor(navBarColor);

        ViewCompat.setOnApplyWindowInsetsListener(rootLayout, (v, insets) -> {
            androidx.core.graphics.Insets statusBarInset = insets.getInsets(WindowInsetsCompat.Type.statusBars());
            androidx.core.graphics.Insets navBarInset = insets.getInsets(WindowInsetsCompat.Type.navigationBars());

            if (headerBar != null) {
                headerBar.setPadding(dp(16), statusBarInset.top + dp(10), dp(16), dp(10));
            }
            if (bottomNavLayout != null) {
                bottomNavLayout.setPadding(0, dp(6), 0, navBarInset.bottom + dp(6));
            }
            return insets;
        });
    }

    // ── Theme Engine ───────────────────────────────────────────────────────────

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

    // ── Root UI Shell Construction ─────────────────────────────────────────────

    private View buildMainPixelShell() {
        rootLayout = new LinearLayout(this);
        rootLayout.setOrientation(LinearLayout.VERTICAL);
        rootLayout.setBackgroundColor(getBgColor());

        // Header Bar
        headerBar = buildHeaderBar();
        rootLayout.addView(headerBar);

        // Content Area
        contentContainer = new FrameLayout(this);
        LinearLayout.LayoutParams contentParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1.0f);
        rootLayout.addView(contentContainer, contentParams);

        // Bottom Navigation Bar
        bottomNavLayout = buildBottomNavBar();
        rootLayout.addView(bottomNavLayout);

        // Initial tab render
        renderActiveTab();

        return rootLayout;
    }

    private View buildHeaderBar() {
        LinearLayout header = new LinearLayout(this);
        header.setOrientation(LinearLayout.HORIZONTAL);
        header.setGravity(Gravity.CENTER_VERTICAL);
        header.setPadding(dp(16), dp(10), dp(16), dp(10));
        header.setBackgroundColor(getSurfaceColor());
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            header.setElevation(dp(2));
        }

        // Logo Mark (Derived from Fellyfin logo white.svg)
        ImageView logo = new ImageView(this);
        logo.setImageResource(R.drawable.ic_jellyfin_logo);
        logo.setColorFilter(getPrimaryTextColor());
        LinearLayout.LayoutParams logoParams = new LinearLayout.LayoutParams(dp(28), dp(28));
        logoParams.rightMargin = dp(10);
        header.addView(logo, logoParams);

        // Exact App Branding Name
        TextView title = new TextView(this);
        title.setText("JellfinDroid");
        title.setTextSize(19);
        title.setTypeface(Typeface.create("sans-serif-medium", Typeface.BOLD));
        title.setTextColor(getPrimaryTextColor());
        header.addView(title, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1.0f));

        // Quick Theme Switch Button (NO EMOJIS)
        TextView themeBtn = new TextView(this);
        themeBtn.setText(isDarkTheme() ? "LIGHT" : "DARK");
        themeBtn.setTextSize(12);
        themeBtn.setTypeface(Typeface.create("sans-serif-medium", Typeface.BOLD));
        themeBtn.setTextColor(getPrimaryTextColor());
        themeBtn.setPadding(dp(14), dp(6), dp(14), dp(6));
        themeBtn.setBackground(createRoundedDrawable(getSurfaceElevatedColor(), dp(16)));
        themeBtn.setOnClickListener(v -> {
            int newMode = isDarkTheme() ? AppCompatDelegate.MODE_NIGHT_NO : AppCompatDelegate.MODE_NIGHT_YES;
            setThemeMode(newMode);
        });
        header.addView(themeBtn);

        return header;
    }

    private LinearLayout buildBottomNavBar() {
        LinearLayout nav = new LinearLayout(this);
        nav.setOrientation(LinearLayout.HORIZONTAL);
        nav.setBackgroundColor(getSurfaceColor());
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            nav.setElevation(dp(8));
        }

        addNavTab(nav, 0, "Home", R.drawable.ic_home);
        addNavTab(nav, 1, "Server", R.drawable.ic_dns);
        addNavTab(nav, 2, "Storage", R.drawable.ic_storage);
        addNavTab(nav, 3, "Logs", R.drawable.ic_terminal);
        addNavTab(nav, 4, "Settings", R.drawable.ic_settings);

        return nav;
    }

    private void addNavTab(LinearLayout parent, int tabIndex, String label, int iconRes) {
        LinearLayout tab = new LinearLayout(this);
        tab.setOrientation(LinearLayout.VERTICAL);
        tab.setGravity(Gravity.CENTER);
        tab.setPadding(0, dp(4), 0, dp(4));
        tab.setOnClickListener(v -> {
            activeTab = tabIndex;
            updateBottomNavSelection();
            renderActiveTab();
        });

        FrameLayout iconWrapper = new FrameLayout(this);
        LinearLayout.LayoutParams wrapperParams = new LinearLayout.LayoutParams(dp(54), dp(28));
        wrapperParams.bottomMargin = dp(2);

        View pill = new View(this);
        pill.setTag("pill");
        pill.setVisibility(View.INVISIBLE);
        pill.setBackground(createRoundedDrawable(colorWithAlpha(getAccentColor(), 45), dp(14)));
        iconWrapper.addView(pill, new FrameLayout.LayoutParams(-1, -1));

        ImageView icon = new ImageView(this);
        icon.setImageResource(iconRes);
        FrameLayout.LayoutParams iconParams = new FrameLayout.LayoutParams(dp(20), dp(20));
        iconParams.gravity = Gravity.CENTER;
        iconWrapper.addView(icon, iconParams);

        tab.addView(iconWrapper, wrapperParams);

        TextView txt = new TextView(this);
        txt.setText(label);
        txt.setTextSize(11);
        tab.addView(txt);

        LinearLayout.LayoutParams tabParams = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1.0f);
        parent.addView(tab, tabParams);
    }

    private void updateBottomNavSelection() {
        if (bottomNavLayout == null) return;
        for (int i = 0; i < bottomNavLayout.getChildCount(); i++) {
            LinearLayout tab = (LinearLayout) bottomNavLayout.getChildAt(i);
            FrameLayout wrapper = (FrameLayout) tab.getChildAt(0);
            View pill = wrapper.findViewWithTag("pill");
            ImageView icon = (ImageView) wrapper.getChildAt(1);
            TextView txt = (TextView) tab.getChildAt(1);

            boolean selected = (i == activeTab);
            if (pill != null) pill.setVisibility(selected ? View.VISIBLE : View.INVISIBLE);

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

    // ── Tab 0: Home (Dashboard) ────────────────────────────────────────────────

    private View buildDashboardTab() {
        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(true);

        LinearLayout layout = new LinearLayout(this);
        layout.setOrientation(LinearLayout.VERTICAL);
        layout.setPadding(dp(16), dp(14), dp(16), dp(14));

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
        card.setPadding(dp(18), dp(18), dp(18), dp(18));
        card.setBackground(createRoundedDrawable(getSurfaceColor(), dp(18)));
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            card.setElevation(dp(2));
        }

        // Status Badge Pill (NO EMOJIS)
        statusBadge = new TextView(this);
        statusBadge.setText("SERVER INITIALIZING");
        statusBadge.setTextSize(13);
        statusBadge.setTypeface(Typeface.create("sans-serif-medium", Typeface.BOLD));
        statusBadge.setPadding(dp(14), dp(5), dp(14), dp(5));
        statusBadge.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams badgeParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        card.addView(statusBadge, badgeParams);

        // Server Detail & Address Info
        serverDetailText = new TextView(this);
        serverDetailText.setText("Jellyfin Server 10.11.11\nLocal: http://127.0.0.1:8096");
        serverDetailText.setTextSize(13);
        serverDetailText.setTextColor(getSecondaryTextColor());
        serverDetailText.setPadding(0, dp(10), 0, dp(2));
        card.addView(serverDetailText);

        // Dynamic LAN Address Line with Copy Action
        lanAddressText = new TextView(this);
        lanAddressText.setText("LAN: detecting address…");
        lanAddressText.setTextSize(13);
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
        card.setPadding(dp(16), dp(14), dp(16), dp(14));
        card.setBackground(createRoundedDrawable(getSurfaceElevatedColor(), dp(16)));
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(-1, -2);
        params.topMargin = dp(12);
        card.setLayoutParams(params);

        TextView header = new TextView(this);
        header.setText("Starting Jellyfin Server");
        header.setTextSize(14);
        header.setTypeface(Typeface.create("sans-serif-medium", Typeface.BOLD));
        header.setTextColor(getPrimaryTextColor());
        card.addView(header);

        ProgressBar progress = new ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal);
        progress.setIndeterminate(true);
        LinearLayout.LayoutParams progressParams = new LinearLayout.LayoutParams(-1, dp(5));
        progressParams.topMargin = dp(8);
        progressParams.bottomMargin = dp(10);
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
        tv.setText(text);
        tv.setTextSize(13);
        tv.setTextColor(getSecondaryTextColor());
        tv.setPadding(0, dp(2), 0, dp(2));
        return tv;
    }

    private View buildDashboardActions() {
        LinearLayout layout = new LinearLayout(this);
        layout.setOrientation(LinearLayout.VERTICAL);
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(-1, -2);
        params.topMargin = dp(14);
        layout.setLayoutParams(params);

        // Prominent OPEN JELLYFIN Button
        btnOpenJellyfin = createPixelButton("OPEN JELLYFIN", getAccentColor(), Color.WHITE);
        btnOpenJellyfin.setOnClickListener(v -> startActivity(new Intent(this, JellyfinWebActivity.class)));
        layout.addView(btnOpenJellyfin);

        btnStartServer = createPixelButton("START SERVER", getSurfaceColor(), getPrimaryTextColor());
        btnStartServer.setOnClickListener(v -> triggerServerAction(JellyfinServerService.ACTION_START));
        layout.addView(btnStartServer);

        btnStopServer = createPixelButton("STOP SERVER", getSurfaceColor(), getPrimaryTextColor());
        btnStopServer.setOnClickListener(v -> triggerServerAction(JellyfinServerService.ACTION_STOP));
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

    private void triggerServerAction(String action) {
        Intent svc = new Intent(this, JellyfinServerService.class);
        svc.setAction(action);
        if (JellyfinServerService.ACTION_START.equals(action)) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                startForegroundService(svc);
            } else {
                startService(svc);
            }
        } else {
            startService(svc);
        }
    }

    // ── Tab 1: Server Controls & Details ───────────────────────────────────────

    private View buildServerTab() {
        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(true);

        LinearLayout layout = new LinearLayout(this);
        layout.setOrientation(LinearLayout.VERTICAL);
        layout.setPadding(dp(16), dp(14), dp(16), dp(14));

        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setPadding(dp(18), dp(18), dp(18), dp(18));
        card.setBackground(createRoundedDrawable(getSurfaceColor(), dp(18)));

        TextView title = new TextView(this);
        title.setText("Server Status & Controls");
        title.setTextSize(17);
        title.setTypeface(Typeface.DEFAULT_BOLD);
        title.setTextColor(getPrimaryTextColor());
        card.addView(title);

        TextView info = new TextView(this);
        info.setText("Package Identity: com.jellyfin.droid\nServer Version: 10.11.11\nHTTP Port: 8096\nProcess Protection: Single-process lock active");
        info.setTextSize(13);
        info.setTextColor(getSecondaryTextColor());
        info.setPadding(0, dp(10), 0, dp(14));
        card.addView(info);

        Switch autoStartSwitch = new Switch(this);
        autoStartSwitch.setText("Auto-start Jellyfin on device boot");
        autoStartSwitch.setTextSize(13);
        autoStartSwitch.setTextColor(getPrimaryTextColor());
        SharedPreferences prefs = getSharedPreferences("jellyfindroid", MODE_PRIVATE);
        autoStartSwitch.setChecked(prefs.getBoolean("auto_start", false));
        autoStartSwitch.setOnCheckedChangeListener((v, checked) -> prefs.edit().putBoolean("auto_start", checked).apply());
        card.addView(autoStartSwitch);

        layout.addView(card);

        LinearLayout actions = new LinearLayout(this);
        actions.setOrientation(LinearLayout.VERTICAL);
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(-1, -2);
        params.topMargin = dp(14);
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
        layout.setPadding(dp(16), dp(14), dp(16), dp(14));

        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setPadding(dp(18), dp(18), dp(18), dp(18));
        card.setBackground(createRoundedDrawable(getSurfaceColor(), dp(18)));

        TextView title = new TextView(this);
        title.setText("Storage & Directories");
        title.setTextSize(17);
        title.setTypeface(Typeface.DEFAULT_BOLD);
        title.setTextColor(getPrimaryTextColor());
        card.addView(title);

        storageInfoText = new TextView(this);
        storageInfoText.setTextSize(13);
        storageInfoText.setTextColor(getSecondaryTextColor());
        storageInfoText.setPadding(0, dp(10), 0, dp(14));
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
        layout.setPadding(dp(16), dp(14), dp(16), dp(14));

        LinearLayout actions = new LinearLayout(this);
        actions.setOrientation(LinearLayout.HORIZONTAL);
        actions.setPadding(0, 0, 0, dp(10));

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
        layout.setPadding(dp(16), dp(14), dp(16), dp(14));

        LinearLayout themeCard = new LinearLayout(this);
        themeCard.setOrientation(LinearLayout.VERTICAL);
        themeCard.setPadding(dp(18), dp(18), dp(18), dp(18));
        themeCard.setBackground(createRoundedDrawable(getSurfaceColor(), dp(18)));

        TextView t1 = new TextView(this);
        t1.setText("Appearance Theme");
        t1.setTextSize(17);
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

        LinearLayout aboutCard = new LinearLayout(this);
        aboutCard.setOrientation(LinearLayout.VERTICAL);
        aboutCard.setPadding(dp(18), dp(18), dp(18), dp(18));
        aboutCard.setBackground(createRoundedDrawable(getSurfaceColor(), dp(18)));
        LinearLayout.LayoutParams pAbout = new LinearLayout.LayoutParams(-1, -2);
        pAbout.topMargin = dp(14);
        aboutCard.setLayoutParams(pAbout);

        TextView t2 = new TextView(this);
        t2.setText("About JellfinDroid");
        t2.setTextSize(17);
        t2.setTypeface(Typeface.DEFAULT_BOLD);
        t2.setTextColor(getPrimaryTextColor());
        aboutCard.addView(t2);

        TextView abtTxt = new TextView(this);
        abtTxt.setText("Jellyfin Server: 10.11.11\nPackage Identity: com.jellyfin.droid\nArchitecture: aarch64 (ARM64)\nPhase 15 Branding & UI Polish");
        abtTxt.setTextSize(13);
        abtTxt.setTextColor(getSecondaryTextColor());
        abtTxt.setPadding(0, dp(10), 0, 0);
        aboutCard.addView(abtTxt);

        layout.addView(aboutCard);

        scroll.addView(layout);
        return scroll;
    }

    // ── State Rendering Logic (NO EMOJIS) ──────────────────────────────────────

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
                    statusText = "SERVER RUNNING";
                    break;
                case STARTING:
                    color = Color.parseColor("#FFD54F");
                    statusText = "SERVER STARTING";
                    break;
                case INITIALIZING:
                    color = Color.parseColor("#FFD54F");
                    statusText = "INITIALIZING RUNTIME";
                    break;
                case STOPPING:
                    color = Color.parseColor("#FFB74D");
                    statusText = "SERVER STOPPING";
                    break;
                case STOPPED:
                    color = Color.parseColor("#9AA0A6");
                    statusText = "SERVER STOPPED";
                    break;
                case CRASHED:
                case FAILED:
                    color = Color.parseColor("#E57373");
                    statusText = "SERVER FAILED";
                    break;
                case CRASH_LOOP:
                    color = Color.parseColor("#B71C1C");
                    statusText = "CRASH LOOP DETECTED";
                    break;
                default:
                    color = Color.parseColor("#FFD54F");
                    statusText = state.name();
                    break;
            }
            statusBadge.setText(statusText);
            statusBadge.setTextColor(color);
            statusBadge.setBackground(createRoundedDrawable(colorWithAlpha(color, 40), dp(16)));
        }

        // Render Real Stage Progress Card (NO EMOJIS)
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
            tv.setText("[READY]  " + label);
            tv.setTextColor(Color.parseColor("#81C784"));
        } else if (active) {
            tv.setText("[IN PROGRESS]  " + label);
            tv.setTextColor(Color.parseColor("#FFD54F"));
        } else {
            tv.setText("[WAITING]  " + label);
            tv.setTextColor(getSecondaryTextColor());
        }
    }

    // ── Storage & Network Helpers ──────────────────────────────────────────────

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

    private void requestNotificationPermissionIfNeeded() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
                    != PackageManager.PERMISSION_GRANTED) {
                ActivityCompat.requestPermissions(this, new String[]{Manifest.permission.POST_NOTIFICATIONS}, 102);
            }
        }
    }

    // ── Design Utilities ───────────────────────────────────────────────────────

    private Button createPixelButton(String text, int bgColor, int textColor) {
        Button b = new Button(this);
        b.setText(text);
        b.setTextSize(13);
        b.setAllCaps(false);
        b.setTextColor(textColor);
        b.setTypeface(Typeface.create("sans-serif-medium", Typeface.BOLD));
        b.setBackground(createRoundedDrawable(bgColor, dp(22)));
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(-1, dp(46));
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
