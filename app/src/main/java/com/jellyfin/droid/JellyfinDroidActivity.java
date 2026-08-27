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
 * Phase 15: JellfinDroid UI Polish, Simplified 3-Tab Navigation & Network Connection Card.
 *
 * Changes:
 *  - 3 Footer Tabs: Home (Server + Controls), Logs, Settings (Settings + Storage).
 *  - Network Connections Card: Distinct colored IP boxes for Local and LAN with dedicated COPY buttons.
 *  - Transparent JellfinDroid white header logo.
 *  - Strict NO-EMOJI enforcement across all views.
 */
public final class JellyfinDroidActivity extends AppCompatActivity
        implements JellyfinController.Listener {

    private JellyfinController controller;
    private SharedPreferences settingsPrefs;

    // Active tab state (0: Home, 1: Logs, 2: Settings)
    private int activeTab = 0;

    // Containers
    private LinearLayout rootLayout;
    private View headerBar;
    private FrameLayout contentContainer;
    private LinearLayout bottomNavLayout;

    // Dynamic UI References for Dashboard
    private TextView statusBadge;
    private TextView localIpValueText;
    private TextView lanIpValueText;
    private Button btnCopyLocalIp;
    private Button btnCopyLanIp;

    private Button btnOpenJellyfin;
    private Button btnStartServer;
    private Button btnStopServer;
    private Button btnRestartServer;
    private Button btnResetCrash;

    // Real Startup Progress Card References
    private View startupProgressCard;
    private TextView txtStage1, txtStage2, txtStage3, txtStage4, txtStage5;

    // Logs & Settings/Storage References
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

        // JellyfinServerService handles single foreground notification lifecycle.
        stopService(new Intent(this, TermuxService.class));

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
        controllerCompat.setAppearanceLightStatusBars(false);
        controllerCompat.setAppearanceLightNavigationBars(false);

        int color = getSurfaceColor();
        window.setStatusBarColor(color);
        window.setNavigationBarColor(color);

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

    // ── Theme Engine (Pure Dark Mode Only) ───────────────────────────────────

    private void applySavedThemeMode() {
        AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_YES);
    }

    private boolean isDarkTheme() {
        return true;
    }

    // ── Colors & Design Tokens ─────────────────────────────────────────────────

    private int getBgColor() { return Color.parseColor("#121316"); }
    private int getSurfaceColor() { return Color.parseColor("#1E2025"); }
    private int getSurfaceElevatedColor() { return Color.parseColor("#272930"); }
    private int getPrimaryTextColor() { return Color.parseColor("#E6E8EE"); }
    private int getSecondaryTextColor() { return Color.parseColor("#9AA0A6"); }
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

        // Bottom Navigation Bar (3 Clean Destinations)
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

        // Logo Mark (Derived from Fellyfin logo white.svg, transparent background)
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

        return header;
    }

    private LinearLayout buildBottomNavBar() {
        LinearLayout nav = new LinearLayout(this);
        nav.setOrientation(LinearLayout.HORIZONTAL);
        nav.setBackgroundColor(getSurfaceColor());
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            nav.setElevation(dp(8));
        }

        // Clean 3-Tab Bottom Navigation
        addNavTab(nav, 0, "Home", R.drawable.ic_home);
        addNavTab(nav, 1, "Logs", R.drawable.ic_terminal);
        addNavTab(nav, 2, "Settings", R.drawable.ic_settings);

        return nav;
    }

    private void addNavTab(LinearLayout parent, int tabIndex, String label, int iconRes) {
        LinearLayout tab = new LinearLayout(this);
        tab.setOrientation(LinearLayout.VERTICAL);
        tab.setGravity(Gravity.CENTER);
        tab.setPadding(0, dp(6), 0, dp(6));
        tab.setOnClickListener(v -> {
            activeTab = tabIndex;
            updateBottomNavSelection();
            renderActiveTab();
        });

        FrameLayout iconWrapper = new FrameLayout(this);
        LinearLayout.LayoutParams wrapperParams = new LinearLayout.LayoutParams(dp(56), dp(28));
        wrapperParams.gravity = Gravity.CENTER_HORIZONTAL;
        wrapperParams.bottomMargin = dp(2);

        View pill = new View(this);
        pill.setTag("pill");
        pill.setVisibility(View.INVISIBLE);
        pill.setBackground(createRoundedDrawable(colorWithAlpha(getAccentColor(), 45), dp(14)));
        iconWrapper.addView(pill, new FrameLayout.LayoutParams(-1, -1));

        ImageView icon = new ImageView(this);
        icon.setImageResource(iconRes);
        FrameLayout.LayoutParams iconParams = new FrameLayout.LayoutParams(dp(22), dp(22));
        iconParams.gravity = Gravity.CENTER;
        iconWrapper.addView(icon, iconParams);

        tab.addView(iconWrapper, wrapperParams);

        TextView txt = new TextView(this);
        txt.setText(label);
        txt.setTextSize(12);
        txt.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams txtParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        tab.addView(txt, txtParams);

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
                contentContainer.addView(buildHomeTab());
                break;
            case 1:
                contentContainer.addView(buildLogsTab());
                break;
            case 2:
                contentContainer.addView(buildSettingsTab());
                break;
        }

        renderCurrentState();
    }

    // ── Tab 0: Home (Dashboard & Server Controls) ──────────────────────────────

    private View buildHomeTab() {
        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(true);

        LinearLayout layout = new LinearLayout(this);
        layout.setOrientation(LinearLayout.VERTICAL);
        layout.setPadding(dp(16), dp(14), dp(16), dp(14));

        // 1. Server Status Hero Card
        layout.addView(buildServerStatusHeroCard());

        // 2. Network Connections Card (Color-Coded IP & Dedicated Copy Buttons)
        layout.addView(buildNetworkConnectionsCard());

        // 3. Real Stage-by-Stage Server Startup Progress Card
        startupProgressCard = buildRealStartupProgressCard();
        layout.addView(startupProgressCard);

        // 4. Server Control Actions
        layout.addView(buildDashboardActions());

        scroll.addView(layout);
        return scroll;
    }

    private View buildServerStatusHeroCard() {
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.HORIZONTAL);
        card.setGravity(Gravity.CENTER_VERTICAL);
        card.setPadding(dp(18), dp(16), dp(18), dp(16));
        card.setBackground(createRoundedDrawable(getSurfaceColor(), dp(18)));
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            card.setElevation(dp(2));
        }

        LinearLayout left = new LinearLayout(this);
        left.setOrientation(LinearLayout.VERTICAL);

        TextView label = new TextView(this);
        label.setText("JELLYFIN SERVER STATUS");
        label.setTextSize(11);
        label.setTypeface(Typeface.create("sans-serif-medium", Typeface.BOLD));
        label.setTextColor(getSecondaryTextColor());
        left.addView(label);

        statusBadge = new TextView(this);
        statusBadge.setText("INITIALIZING");
        statusBadge.setTextSize(15);
        statusBadge.setTypeface(Typeface.create("sans-serif-medium", Typeface.BOLD));
        statusBadge.setPadding(0, dp(4), 0, 0);
        left.addView(statusBadge);

        card.addView(left, new LinearLayout.LayoutParams(0, -2, 1.0f));

        TextView versionBadge = new TextView(this);
        versionBadge.setText("v10.11.11");
        versionBadge.setTextSize(12);
        versionBadge.setTypeface(Typeface.MONOSPACE);
        versionBadge.setTextColor(getSecondaryTextColor());
        versionBadge.setPadding(dp(10), dp(4), dp(10), dp(4));
        versionBadge.setBackground(createRoundedDrawable(getSurfaceElevatedColor(), dp(12)));
        card.addView(versionBadge);

        return card;
    }

    private View buildNetworkConnectionsCard() {
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setPadding(dp(18), dp(16), dp(18), dp(16));
        card.setBackground(createRoundedDrawable(getSurfaceColor(), dp(18)));
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(-1, -2);
        params.topMargin = dp(12);
        card.setLayoutParams(params);

        TextView header = new TextView(this);
        header.setText("SERVER ADDRESSES");
        header.setTextSize(12);
        header.setTypeface(Typeface.create("sans-serif-medium", Typeface.BOLD));
        header.setTextColor(getSecondaryTextColor());
        header.setPadding(0, 0, 0, dp(12));
        card.addView(header);

        // Local Address Box
        card.addView(buildAddressRow("LOCAL ADDRESS", "http://127.0.0.1:8096", Color.parseColor("#00A4DC"), true));

        // Divider line
        View divider = new View(this);
        divider.setBackgroundColor(colorWithAlpha(getSecondaryTextColor(), 30));
        LinearLayout.LayoutParams divParams = new LinearLayout.LayoutParams(-1, dp(1));
        divParams.topMargin = dp(10);
        divParams.bottomMargin = dp(10);
        card.addView(divider, divParams);

        // LAN Address Box
        card.addView(buildLanAddressRow());

        return card;
    }

    private View buildAddressRow(String labelText, String urlText, int urlColor, boolean isLocal) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);

        LinearLayout info = new LinearLayout(this);
        info.setOrientation(LinearLayout.VERTICAL);

        TextView lbl = new TextView(this);
        lbl.setText(labelText);
        lbl.setTextSize(11);
        lbl.setTextColor(getSecondaryTextColor());
        info.addView(lbl);

        TextView url = new TextView(this);
        url.setText(urlText);
        url.setTextSize(14);
        url.setTypeface(Typeface.create("sans-serif-medium", Typeface.BOLD));
        url.setTextColor(urlColor);
        url.setPadding(0, dp(2), 0, 0);
        info.addView(url);

        if (isLocal) {
            localIpValueText = url;
        }

        row.addView(info, new LinearLayout.LayoutParams(0, -2, 1.0f));

        Button btnCopy = createPillCopyButton();
        btnCopy.setOnClickListener(v -> copyUrlToClipboard("Local Server Address", urlText));
        if (isLocal) {
            btnCopyLocalIp = btnCopy;
        }
        row.addView(btnCopy);

        return row;
    }

    private View buildLanAddressRow() {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);

        LinearLayout info = new LinearLayout(this);
        info.setOrientation(LinearLayout.VERTICAL);

        TextView lbl = new TextView(this);
        lbl.setText("LAN ADDRESS");
        lbl.setTextSize(11);
        lbl.setTextColor(getSecondaryTextColor());
        info.addView(lbl);

        lanIpValueText = new TextView(this);
        lanIpValueText.setText("Detecting Wi-Fi / LAN address…");
        lanIpValueText.setTextSize(14);
        lanIpValueText.setTypeface(Typeface.create("sans-serif-medium", Typeface.BOLD));
        lanIpValueText.setTextColor(Color.parseColor("#81C784"));
        lanIpValueText.setPadding(0, dp(2), 0, 0);
        info.addView(lanIpValueText);

        row.addView(info, new LinearLayout.LayoutParams(0, -2, 1.0f));

        btnCopyLanIp = createPillCopyButton();
        btnCopyLanIp.setOnClickListener(v -> {
            String text = lanIpValueText.getText().toString();
            if (text.startsWith("http://")) {
                copyUrlToClipboard("LAN Server Address", text);
            }
        });
        row.addView(btnCopyLanIp);

        return row;
    }



    private void copyUrlToClipboard(String label, String url) {
        ClipboardManager cm = (ClipboardManager) getSystemService(CLIPBOARD_SERVICE);
        if (cm != null) {
            cm.setPrimaryClip(ClipData.newPlainText(label, url));
            Toast.makeText(this, "Copied " + label + " to clipboard", Toast.LENGTH_SHORT).show();
        }
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

        btnStopServer = createPixelButton("STOP SERVER", Color.parseColor("#D32F2F"), Color.WHITE);
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

    // ── Tab 1: Logs View ───────────────────────────────────────────────────────

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

    // ── Tab 2: Settings View (Integrated Settings & Media Storage) ────────────

    private View buildSettingsTab() {
        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(true);

        LinearLayout layout = new LinearLayout(this);
        layout.setOrientation(LinearLayout.VERTICAL);
        layout.setPadding(dp(16), dp(14), dp(16), dp(14));

        // Server Category Card
        LinearLayout serverCard = new LinearLayout(this);
        serverCard.setOrientation(LinearLayout.VERTICAL);
        serverCard.setPadding(dp(18), dp(18), dp(18), dp(18));
        serverCard.setBackground(createRoundedDrawable(getSurfaceColor(), dp(18)));

        TextView t0 = new TextView(this);
        t0.setText("Server Configuration");
        t0.setTextSize(17);
        t0.setTypeface(Typeface.DEFAULT_BOLD);
        t0.setTextColor(getPrimaryTextColor());
        serverCard.addView(t0);

        Switch autoStartSwitch = new Switch(this);
        autoStartSwitch.setText("Auto-start Jellyfin on device boot");
        autoStartSwitch.setTextSize(13);
        autoStartSwitch.setTextColor(getPrimaryTextColor());
        autoStartSwitch.setPadding(0, dp(10), 0, dp(4));
        SharedPreferences prefs = getSharedPreferences("jellyfindroid", MODE_PRIVATE);
        autoStartSwitch.setChecked(prefs.getBoolean("auto_start", false));
        autoStartSwitch.setOnCheckedChangeListener((v, checked) -> prefs.edit().putBoolean("auto_start", checked).apply());
        serverCard.addView(autoStartSwitch);

        layout.addView(serverCard);

        // Media Storage Card (Integrated from Storage activity)
        LinearLayout storageCard = new LinearLayout(this);
        storageCard.setOrientation(LinearLayout.VERTICAL);
        storageCard.setPadding(dp(18), dp(18), dp(18), dp(18));
        storageCard.setBackground(createRoundedDrawable(getSurfaceColor(), dp(18)));
        LinearLayout.LayoutParams pStorage = new LinearLayout.LayoutParams(-1, -2);
        pStorage.topMargin = dp(14);
        storageCard.setLayoutParams(pStorage);

        TextView tStorage = new TextView(this);
        tStorage.setText("Storage & Directories");
        tStorage.setTextSize(17);
        tStorage.setTypeface(Typeface.DEFAULT_BOLD);
        tStorage.setTextColor(getPrimaryTextColor());
        storageCard.addView(tStorage);

        storageInfoText = new TextView(this);
        storageInfoText.setTextSize(13);
        storageInfoText.setTextColor(getSecondaryTextColor());
        storageInfoText.setPadding(0, dp(8), 0, dp(12));
        updateStorageInfoText();
        storageCard.addView(storageInfoText);

        Button manageStorageBtn = createPixelButton("MANAGE MEDIA STORAGE (SAF)", getAccentColor(), Color.WHITE);
        manageStorageBtn.setOnClickListener(v -> startActivity(new Intent(this, JellyfinStorageActivity.class)));
        storageCard.addView(manageStorageBtn);

        layout.addView(storageCard);

        // Installed Packages & System Security Transparency Card
        LinearLayout aboutCard = new LinearLayout(this);
        aboutCard.setOrientation(LinearLayout.VERTICAL);
        aboutCard.setPadding(dp(18), dp(18), dp(18), dp(18));
        aboutCard.setBackground(createRoundedDrawable(getSurfaceColor(), dp(18)));
        LinearLayout.LayoutParams pAbout = new LinearLayout.LayoutParams(-1, -2);
        pAbout.topMargin = dp(14);
        aboutCard.setLayoutParams(pAbout);

        TextView t2 = new TextView(this);
        t2.setText("Installed Packages & Security Transparency");
        t2.setTextSize(17);
        t2.setTypeface(Typeface.DEFAULT_BOLD);
        t2.setTextColor(getPrimaryTextColor());
        aboutCard.addView(t2);

        TextView abtSub = new TextView(this);
        abtSub.setText("Comprehensive breakdown of all bundled binaries, runtimes, and packages:");
        abtSub.setTextSize(12);
        abtSub.setTextColor(getSecondaryTextColor());
        abtSub.setPadding(0, dp(4), 0, dp(10));
        aboutCard.addView(abtSub);

        // Detailed Installed Component Items
        aboutCard.addView(buildTransparencyItem("Jellyfin Media Server Core", "v10.11.11\nOfficial Dotnet 8 Linux ARM64 binaries (jellyfin.dll). Self-contained web media server runtime."));
        aboutCard.addView(buildTransparencyItem("FFmpeg Hardware Encoder / Transcoder", "v6.x / v7.x Linux ARM64\nBundled in opt/jellyfin/bin/ffmpeg. Used exclusively for media file remuxing, video thumbnail generation, and audio transcoding."));
        aboutCard.addView(buildTransparencyItem("OpenSSL Security & TLS Stack", "OpenSSL 3.x System Libraries\nCryptographic SSL/TLS engine managing local HTTPS network encryption and secure sockets."));
        aboutCard.addView(buildTransparencyItem("Fontconfig & FreeType Engines", "libfontconfig.so / libfreetype.so\nNative C libraries used by FFmpeg for video subtitle burn-in and text rendering."));
        aboutCard.addView(buildTransparencyItem("SQLite3 Database Driver", "libe_sqlite3.so\nEmbedded lightweight relational database engine storing user library indexes, metadata, and watch progress locally."));
        aboutCard.addView(buildTransparencyItem("Minimal Linux Subsystem", "APT Package Manager & Android Bionic C Library (libc)\nMinimal Android-native POSIX execution container hosting Dotnet processes without background telemetry or tracker scripts."));
        aboutCard.addView(buildTransparencyItem("Application Package & Scope", "com.jellyfin.droid\nIsolated Android package namespace. Runs strictly under Android OS user sandboxing rules."));
        aboutCard.addView(buildTransparencyItem("Privacy & Telemetry Verification", "0 Remote Trackers | 0 Analytics | 100% Local Storage\nNo background metrics are collected or transmitted to external servers. All media data stays strictly on your device."));

        layout.addView(aboutCard);

        scroll.addView(layout);
        return scroll;
    }

    private View buildTransparencyItem(String title, String detail) {
        LinearLayout item = new LinearLayout(this);
        item.setOrientation(LinearLayout.VERTICAL);
        item.setPadding(dp(12), dp(10), dp(12), dp(10));
        item.setBackground(createRoundedDrawable(getSurfaceElevatedColor(), dp(12)));
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(-1, -2);
        params.topMargin = dp(5);
        params.bottomMargin = dp(5);
        item.setLayoutParams(params);

        TextView titleTv = new TextView(this);
        titleTv.setText(title);
        titleTv.setTextSize(13);
        titleTv.setTypeface(Typeface.create("sans-serif-medium", Typeface.BOLD));
        titleTv.setTextColor(getPrimaryTextColor());
        item.addView(titleTv);

        TextView detailTv = new TextView(this);
        detailTv.setText(detail);
        detailTv.setTextSize(12);
        detailTv.setTextColor(getSecondaryTextColor());
        detailTv.setPadding(0, dp(4), 0, 0);
        item.addView(detailTv);

        return item;
    }

    private void updateStorageInfoText() {
        if (storageInfoText == null) return;
        File prefix = com.termux.shared.termux.TermuxConstants.TERMUX_PREFIX_DIR;
        File home = com.termux.shared.termux.TermuxConstants.TERMUX_HOME_DIR;
        File dataDir = new File(home, ".local/share/jellyfin");
        File cacheDir = new File(home, ".cache/jellyfin");

        long prefixMB = getFolderSizeMB(prefix);
        long dataMB = getFolderSizeMB(dataDir);
        long cacheMB = getFolderSizeMB(cacheDir);

        String info = "Runtime: " + prefixMB + " MB | Data: " + dataMB + " MB | Cache: " + cacheMB + " MB";
        storageInfoText.setText(info);
    }

    private long getFolderSizeMB(File dir) {
        if (dir == null || !dir.exists()) return 0;
        long bytes = dir.length();
        return bytes / (1024 * 1024);
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
        if (lanIpValueText != null) {
            if (lan == null) {
                lanIpValueText.setText("LAN UNAVAILABLE (Connect Wi-Fi)");
                lanIpValueText.setTextColor(Color.parseColor("#FFB74D"));
                if (btnCopyLanIp != null) btnCopyLanIp.setEnabled(false);
            } else {
                lanIpValueText.setText(lan);
                lanIpValueText.setTextColor(Color.parseColor("#81C784"));
                if (btnCopyLanIp != null) btnCopyLanIp.setEnabled(true);
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

    private Button createPillCopyButton() {
        Button btn = new Button(this);
        btn.setText("COPY IP");
        btn.setTextSize(11);
        btn.setAllCaps(false);
        btn.setTypeface(Typeface.create("sans-serif-medium", Typeface.BOLD));
        btn.setTextColor(getPrimaryTextColor());

        GradientDrawable shape = createRoundedDrawable(getSurfaceElevatedColor(), dp(16));
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            android.graphics.drawable.RippleDrawable ripple = new android.graphics.drawable.RippleDrawable(
                    android.content.res.ColorStateList.valueOf(Color.argb(60, 0, 164, 220)),
                    shape,
                    null);
            btn.setBackground(ripple);
        } else {
            btn.setBackground(shape);
        }

        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(dp(76), dp(36));
        btn.setLayoutParams(p);
        return btn;
    }

    private Button createPixelButton(String text, int bgColor, int textColor) {
        Button b = new Button(this);
        b.setText(text);
        b.setTextSize(13);
        b.setAllCaps(false);
        b.setTextColor(textColor);
        b.setTypeface(Typeface.create("sans-serif-medium", Typeface.BOLD));

        GradientDrawable shape = createRoundedDrawable(bgColor, dp(22));
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            int rippleColor = (textColor == Color.WHITE)
                    ? Color.argb(80, 255, 255, 255)
                    : Color.argb(60, 0, 164, 220);
            android.graphics.drawable.RippleDrawable ripple = new android.graphics.drawable.RippleDrawable(
                    android.content.res.ColorStateList.valueOf(rippleColor),
                    shape,
                    null);
            b.setBackground(ripple);
        } else {
            b.setBackground(shape);
        }

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
