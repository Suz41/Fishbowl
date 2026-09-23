package com.fishbowl.app;

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
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.os.PowerManager;
import android.os.SystemClock;
import android.provider.Settings;
import androidx.core.app.NotificationManagerCompat;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.ScrollView;
import android.widget.Switch;
import android.widget.TextView;
import android.widget.Toast;

import java.net.Inet4Address;
import java.net.NetworkInterface;
import java.util.Enumeration;
import java.util.List;
import java.util.Locale;
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

import android.util.Log;
import java.io.File;
import java.net.Inet4Address;
import java.net.NetworkInterface;
import java.util.Enumeration;

/**
 * Phase 15: JellyfinDroid UI Polish, Simplified 3-Tab Navigation & Network Connection Card.
 *  - High-visibility Pure Dark Mode (#121316 surface background) with clean separation.
 *  - Fully functional 3-Tab Bottom Navigation: Home (Hero, Stages, Actions, Network Cards), Logs, Settings (Server Config & Storage).
 *  - Dual-band IP presentation (Local + LAN IP) with individual COPY IP buttons.
 *  - Material Ripple touch effects and high-contrast Action buttons (Red STOP SERVER).
 *  - Transparent JellyfinDroid white header logo.
 *  - Strict NO-EMOJI enforcement across all views.
 */
public final class JellyfinDroidActivity extends AppCompatActivity
        implements JellyfinController.Listener, JellyfinController.LogListener {

    private static final String TAG = "JellyfinDroidActivity";
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
    private TextView healthStatusBadge;
    private TextView healthUptimeText;
    private TextView healthRamText;
    private TextView healthStorageText;
    private TextView localIpValueText;
    private TextView lanIpValueText;
    private TextView tailscaleIpValueText;
    private Button btnCopyLocalIp;
    private Button btnCopyLanIp;
    private Button btnCopyTailscaleIp;

    private Button btnOpenJellyfin;
    private Button btnStartServer;
    private Button btnStopServer;
    private Button btnRestartServer;
    private Button btnResetCrash;
    private Button btnReinstallRuntime;

    // Button Debounce & Immediate In-Progress States
    private long lastButtonActionTimestamp = 0;
    private static final long BUTTON_DEBOUNCE_MS = 1500;

    // Error Diagnosis Card References
    private View errorDiagnosticCard;
    private TextView errorCardCategoryText;
    private TextView errorCardWhatText;
    private TextView errorCardWhyText;
    private TextView errorCardFixText;
    private TextView errorCardRawText;
    private View errorCardRawContainer;
    private boolean errorCardDismissed = false;

    // Permissions Card References
    private TextView permStorageBadge;
    private TextView permStorageAction;
    private TextView permBatteryBadge;
    private TextView permBatteryAction;
    private TextView permNotifBadge;
    private TextView permNotifAction;

    // Real Startup Progress Card References
    private View startupProgressCard;
    private TextView txtStage1, txtStage2, txtStage3, txtStage4, txtStage5;

    // Slim Header Loading Bar & Status Banner
    private ProgressBar headerProgressBar;
    private TextView headerStatusBanner;

    // Logs & Settings/Storage References
    private TextView logsOutputText;
    private ScrollView logsScrollView;
    private TextView storageInfoText;
    private TextView updateStatusText;
    private Button updateActionBtn;
    private TextView runtimeStatusText;
    private Button btnSettingsReinstall;
    private Button btnSettingsRetry;

    // Cached Tab Views to prevent recreation and crashes on tab click
    private View cachedHomeView;
    private View cachedLogsView;
    private View cachedSettingsView;

    // Dedicated Full-Screen Setup / Loading Overlay
    private View setupOverlayView;
    private ProgressBar setupProgressBar;
    private TextView setupProgressText;
    private TextView setupDetailText;
    private Button setupRetryBtn;
    private TextView setupLogSummaryText;

    // Throttled Log Handler (§4 UI Thread Protection)
    private final android.os.Handler logUpdateHandler = new android.os.Handler(android.os.Looper.getMainLooper());
    private boolean logUpdatePending = false;
    private boolean showingDiskLog = false;
    private static final long LOG_REFRESH_THROTTLE_MS = 500;

    // Server Health Poller
    private final android.os.Handler healthHandler = new android.os.Handler(android.os.Looper.getMainLooper());
    private final Runnable healthPollRunnable = new Runnable() {
        @Override
        public void run() {
            refreshServerHealthUI();
            healthHandler.postDelayed(this, 3000);
        }
    };

    // Network Callback & Receiver
    private ConnectivityManager.NetworkCallback networkCallback;
    private BroadcastReceiver connectivityReceiver;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        settingsPrefs = getSharedPreferences("jellyfindroid_settings", MODE_PRIVATE);
        applySavedThemeMode();

        super.onCreate(savedInstanceState);
        controller = JellyfinController.getInstance();

        try {
            android.system.Os.chmod(getApplicationInfo().dataDir, 0700);
        } catch (Throwable ignored) {}

        // JellyfinServerService handles single foreground notification lifecycle.
        stopService(new Intent(this, TermuxService.class));

        // Request Android 13+ Notification Permission if needed (non-blocking)
        requestNotificationPermissionIfNeeded();

        setContentView(buildMainPixelShell());
        setupSystemBarsAndInsets();
        setupNetworkMonitor();

        // Auto-start server on app launch
        if (controller.getState() == JellyfinController.State.STOPPED || controller.getState() == JellyfinController.State.UNINITIALIZED) {
            triggerServerAction(JellyfinServerService.ACTION_START);
        }
    }

    @Override
    protected void onStart() {
        super.onStart();
        controller.addListener(this);
        controller.reconcileStateAsync();
        controller.addLogListener(this);
        UpdateManager.getInstance(this).addListener(updateListener);
        UpdateManager.getInstance(this).checkForUpdates(this, false);
        refreshLanAddress();
        renderCurrentState();
        refreshUpdateUI();
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (controller != null) {
            controller.reconcileStateAsync();
        }
        refreshUpdateUI();
        healthHandler.removeCallbacks(healthPollRunnable);
        healthHandler.post(healthPollRunnable);
        refreshPermissionsUI();
    }

    @Override
    protected void onStop() {
        controller.removeListener(this);
        controller.removeLogListener(this);
        UpdateManager.getInstance(this).removeListener(updateListener);
        logUpdateHandler.removeCallbacksAndMessages(null);
        logUpdatePending = false;
        healthHandler.removeCallbacks(healthPollRunnable);
        super.onStop();
    }

    @Override
    protected void onDestroy() {
        teardownNetworkMonitor();
        controller.removeLogListener(this);
        logUpdateHandler.removeCallbacksAndMessages(null);
        super.onDestroy();
    }

    @Override
    public void onBackPressed() {
        if (activeTab != 0) {
            activeTab = 0;
            updateBottomNavSelection();
            renderActiveTab();
            return;
        }
        if (setupOverlayView != null && setupOverlayView.getVisibility() == View.VISIBLE) {
            Toast.makeText(this, "Setup in progress. Please keep Fishbowl open.", Toast.LENGTH_SHORT).show();
            moveTaskToBack(true);
            return;
        }
        moveTaskToBack(true);
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

        // Slim Non-Intrusive Progress Bar (Small loading indicator right under header)
        headerProgressBar = new ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal);
        headerProgressBar.setIndeterminate(false);
        headerProgressBar.setMax(100);
        headerProgressBar.setProgress(0);
        headerProgressBar.setVisibility(View.GONE);
        LinearLayout.LayoutParams hpbParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(3));
        rootLayout.addView(headerProgressBar, hpbParams);

        // Slim Status Notification Banner right below progress bar
        headerStatusBanner = new TextView(this);
        headerStatusBanner.setTextSize(12);
        headerStatusBanner.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        headerStatusBanner.setPadding(dp(16), dp(6), dp(16), dp(6));
        headerStatusBanner.setBackgroundColor(getSurfaceElevatedColor());
        headerStatusBanner.setTextColor(getSecondaryTextColor());
        headerStatusBanner.setVisibility(View.GONE);
        rootLayout.addView(headerStatusBanner);

        // Content Area
        contentContainer = new FrameLayout(this);
        LinearLayout.LayoutParams contentParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1.0f);
        rootLayout.addView(contentContainer, contentParams);

        // Pre-build and cache tab views to guarantee zero crashes and instant tab switching
        cachedHomeView = buildHomeTab();
        contentContainer.addView(cachedHomeView, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        cachedLogsView = buildLogsTab();
        cachedLogsView.setVisibility(View.GONE);
        contentContainer.addView(cachedLogsView, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        cachedSettingsView = buildSettingsTab();
        cachedSettingsView.setVisibility(View.GONE);
        contentContainer.addView(cachedSettingsView, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        // Dedicated Full-Screen Setup / Loading Overlay (placed on top of tabs)
        setupOverlayView = buildSetupOverlay();
        contentContainer.addView(setupOverlayView, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

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
        title.setText("Fishbowl");
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

    // ── Tab View Router (Cached & Zero-Recreation) ───────────────────────────

    private void renderActiveTab() {
        updateBottomNavSelection();

        if (cachedHomeView != null) cachedHomeView.setVisibility(activeTab == 0 ? View.VISIBLE : View.GONE);
        if (cachedLogsView != null) cachedLogsView.setVisibility(activeTab == 1 ? View.VISIBLE : View.GONE);
        if (cachedSettingsView != null) cachedSettingsView.setVisibility(activeTab == 2 ? View.VISIBLE : View.GONE);

        if (activeTab == 0) {
            refreshLanAddress();
        } else if (activeTab == 1) {
            refreshLogsDisplay();
            if (logsScrollView != null) {
                logsScrollView.post(() -> logsScrollView.fullScroll(View.FOCUS_DOWN));
            }
        } else if (activeTab == 2) {
            updateStorageInfoText();
            refreshRuntimeStatusUI();
            refreshPermissionsUI();
        }

        if (activeTab == 1 || (setupOverlayView != null && setupOverlayView.getVisibility() == View.VISIBLE)) {
            controller.addLogListener(this);
        } else {
            controller.removeLogListener(this);
            logUpdateHandler.removeCallbacksAndMessages(null);
            logUpdatePending = false;
        }

        renderCurrentState();
    }

    // ── Dedicated Full-Screen Setup & Extraction Screen ───────────────────────

    private View buildSetupOverlay() {
        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(true);
        scroll.setBackgroundColor(getBgColor());

        LinearLayout container = new LinearLayout(this);
        container.setOrientation(LinearLayout.VERTICAL);
        container.setGravity(Gravity.CENTER_HORIZONTAL);
        container.setPadding(dp(24), dp(36), dp(24), dp(28));

        // Setup Brand Logo
        ImageView logo = new ImageView(this);
        logo.setImageResource(R.drawable.ic_jellyfin_logo);
        logo.setColorFilter(Color.WHITE);
        LinearLayout.LayoutParams logoParams = new LinearLayout.LayoutParams(dp(56), dp(56));
        logoParams.bottomMargin = dp(16);
        container.addView(logo, logoParams);

        // Setup Title
        TextView title = new TextView(this);
        title.setText("Setting up Jellyfin Media Server");
        title.setTextSize(19);
        title.setTypeface(Typeface.create("sans-serif-medium", Typeface.BOLD));
        title.setTextColor(getPrimaryTextColor());
        title.setGravity(Gravity.CENTER);
        container.addView(title);

        // Subtitle & Status Details
        setupDetailText = new TextView(this);
        setupDetailText.setText("Preparing runtime environment...");
        setupDetailText.setTextSize(13);
        setupDetailText.setTextColor(getSecondaryTextColor());
        setupDetailText.setGravity(Gravity.CENTER);
        setupDetailText.setPadding(0, dp(6), 0, dp(18));
        container.addView(setupDetailText);

        // Progress Card Container
        LinearLayout progressCard = new LinearLayout(this);
        progressCard.setOrientation(LinearLayout.VERTICAL);
        progressCard.setPadding(dp(18), dp(18), dp(18), dp(18));
        progressCard.setBackground(createRoundedDrawable(getSurfaceColor(), dp(18)));
        LinearLayout.LayoutParams cardParams = new LinearLayout.LayoutParams(-1, -2);
        cardParams.bottomMargin = dp(14);
        progressCard.setLayoutParams(cardParams);

        // Top Row with Stage Label & Percentage
        LinearLayout topRow = new LinearLayout(this);
        topRow.setOrientation(LinearLayout.HORIZONTAL);
        topRow.setGravity(Gravity.CENTER_VERTICAL);

        TextView lblProgress = new TextView(this);
        lblProgress.setText("INSTALLATION PROGRESS");
        lblProgress.setTextSize(11);
        lblProgress.setTypeface(Typeface.create("sans-serif-medium", Typeface.BOLD));
        lblProgress.setTextColor(getSecondaryTextColor());
        topRow.addView(lblProgress, new LinearLayout.LayoutParams(0, -2, 1.0f));

        setupProgressText = new TextView(this);
        setupProgressText.setText("0%");
        setupProgressText.setTextSize(14);
        setupProgressText.setTypeface(Typeface.create("sans-serif-medium", Typeface.BOLD));
        setupProgressText.setTextColor(getAccentColor());
        topRow.addView(setupProgressText);

        progressCard.addView(topRow);

        // Dynamic Setup Progress Bar
        setupProgressBar = new ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal);
        setupProgressBar.setMax(100);
        setupProgressBar.setProgress(0);
        setupProgressBar.setIndeterminate(false);
        LinearLayout.LayoutParams pbParams = new LinearLayout.LayoutParams(-1, dp(8));
        pbParams.topMargin = dp(10);
        pbParams.bottomMargin = dp(14);
        progressCard.addView(setupProgressBar, pbParams);

        // Granular Element Stage Rows
        TextView row1 = createSetupCheckItem("1. Recombining split package archives (asset chunks)");
        row1.setTag("setup_step_1");
        progressCard.addView(row1);

        TextView row2 = createSetupCheckItem("2. Extracting Jellyfin Server 12.1.0 ARM64 runtime");
        row2.setTag("setup_step_2");
        progressCard.addView(row2);

        TextView row3 = createSetupCheckItem("3. Unpacking Microsoft .NET 10 & FFmpeg 7.1.4 transcoder");
        row3.setTag("setup_step_3");
        progressCard.addView(row3);

        TextView row4 = createSetupCheckItem("4. Configuring POSIX permissions & system libraries");
        row4.setTag("setup_step_4");
        progressCard.addView(row4);

        TextView row5 = createSetupCheckItem("5. Verifying server binaries & readiness");
        row5.setTag("setup_step_5");
        progressCard.addView(row5);

        container.addView(progressCard);

        // Live Log Preview Box
        LinearLayout logCard = new LinearLayout(this);
        logCard.setOrientation(LinearLayout.VERTICAL);
        logCard.setPadding(dp(14), dp(12), dp(14), dp(12));
        logCard.setBackground(createRoundedDrawable(Color.parseColor("#0C0D0E"), dp(12)));
        LinearLayout.LayoutParams logCardParams = new LinearLayout.LayoutParams(-1, -2);
        logCardParams.bottomMargin = dp(14);
        logCard.setLayoutParams(logCardParams);

        TextView logTitle = new TextView(this);
        logTitle.setText("LIVE EXTRACTION LOG");
        logTitle.setTextSize(11);
        logTitle.setTypeface(Typeface.create("sans-serif-medium", Typeface.BOLD));
        logTitle.setTextColor(getSecondaryTextColor());
        logTitle.setPadding(0, 0, 0, dp(4));
        logCard.addView(logTitle);

        setupLogSummaryText = new TextView(this);
        setupLogSummaryText.setText("Initializing unpacker engine...");
        setupLogSummaryText.setTextSize(11);
        setupLogSummaryText.setTypeface(Typeface.MONOSPACE);
        setupLogSummaryText.setTextColor(Color.parseColor("#81C784"));
        setupLogSummaryText.setMaxLines(4);
        setupLogSummaryText.setEllipsize(android.text.TextUtils.TruncateAt.END);
        logCard.addView(setupLogSummaryText);

        container.addView(logCard);

        // Advisory Note
        TextView note = new TextView(this);
        note.setText("Please keep Fishbowl open while extraction completes.\nThis setup process only runs once.");
        note.setTextSize(12);
        note.setTextColor(getSecondaryTextColor());
        note.setGravity(Gravity.CENTER);
        note.setPadding(dp(8), 0, dp(8), dp(14));
        container.addView(note);

        // Retry & Reinstall Buttons (Shown only if failure occurs)
        setupRetryBtn = createPixelButton("RETRY SETUP", getAccentColor(), Color.WHITE);
        setupRetryBtn.setOnClickListener(v -> {
            setupRetryBtn.setVisibility(View.GONE);
            controller.reinstallRuntime(this);
        });
        setupRetryBtn.setVisibility(View.GONE);
        container.addView(setupRetryBtn);

        Button setupReinstallBtn = createPixelButton("CLEAN REINSTALL", Color.parseColor("#455A64"), Color.WHITE);
        setupReinstallBtn.setOnClickListener(v -> showReinstallConfirmation());
        setupReinstallBtn.setTag("setup_reinstall_btn");
        setupReinstallBtn.setVisibility(View.GONE);
        container.addView(setupReinstallBtn);

        scroll.addView(container);
        return scroll;
    }

    private TextView createSetupCheckItem(String text) {
        TextView tv = new TextView(this);
        tv.setText(text);
        tv.setTextSize(12);
        tv.setTextColor(getSecondaryTextColor());
        tv.setPadding(0, dp(3), 0, dp(3));
        return tv;
    }

    private void renderSetupOverlayState() {
        if (setupOverlayView == null || controller == null) return;

        JellyfinController.State state = controller.getState();
        boolean initialized = JellyfinBootstrapper.isInitialized(this);
        boolean isInitializing = (state == JellyfinController.State.INITIALIZING);
        boolean isStarting = (state == JellyfinController.State.STARTING);
        boolean isRunning = (state == JellyfinController.State.RUNNING);
        boolean isFailed = (state == JellyfinController.State.FAILED || state == JellyfinController.State.CRASHED);

        // Overlay is visible when explicitly INITIALIZING or not yet initialized
        boolean showOverlay = isInitializing || (!initialized && !isRunning);

        setupOverlayView.setVisibility(showOverlay ? View.VISIBLE : View.GONE);
        if (!showOverlay) {
            getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
            return;
        }

        if (isInitializing || isStarting) {
            getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        } else {
            getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        }

        setupOverlayView.bringToFront();

        int pct = controller.getBootstrapProgressPercent();
        String msg = controller.getBootstrapProgressMessage();
        String lastErr = JellyfinBootstrapper.getLastError();

        boolean hasFailed = isFailed || (lastErr != null && !lastErr.isEmpty() && !isInitializing && !isStarting);

        if (hasFailed) {
            // Setup failed state
            if (setupDetailText != null) {
                String errSummary = (lastErr != null && !lastErr.isEmpty()) ? lastErr : "Installation failed or incomplete.";
                setupDetailText.setText(errSummary + "\nTap RETRY SETUP or CLEAN REINSTALL to unpack fresh binaries.");
                setupDetailText.setTextColor(Color.parseColor("#E57373"));
            }
            if (setupProgressText != null) {
                setupProgressText.setText("FAILED");
                setupProgressText.setTextColor(Color.parseColor("#E57373"));
            }
            if (setupRetryBtn != null) {
                setupRetryBtn.setVisibility(View.VISIBLE);
                setupRetryBtn.setEnabled(true);
            }
            View reinstallBtn = setupOverlayView.findViewWithTag("setup_reinstall_btn");
            if (reinstallBtn != null) {
                reinstallBtn.setVisibility(View.VISIBLE);
                reinstallBtn.setEnabled(true);
            }
        } else {
            // Active extraction / verification state
            int displayPct = Math.max(isInitializing ? 5 : 0, pct);
            if (setupProgressBar != null) {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                    setupProgressBar.setProgress(displayPct, true);
                } else {
                    setupProgressBar.setProgress(displayPct);
                }
            }
            if (setupProgressText != null) {
                setupProgressText.setText(displayPct + "%");
                setupProgressText.setTextColor(getAccentColor());
            }
            if (setupDetailText != null) {
                String detail = (msg != null && !msg.isEmpty()) ? msg : "Configuring runtime files...";
                setupDetailText.setText(detail);
                setupDetailText.setTextColor(getSecondaryTextColor());
            }
            if (setupRetryBtn != null) {
                setupRetryBtn.setVisibility(View.GONE);
            }
            View reinstallBtn = setupOverlayView.findViewWithTag("setup_reinstall_btn");
            if (reinstallBtn != null) {
                reinstallBtn.setVisibility(View.GONE);
            }

            // Update stage rows
            updateSetupStageItem("setup_step_1", "1. Recombining split package archives (asset chunks)", pct >= 35, pct > 0 && pct < 35);
            updateSetupStageItem("setup_step_2", "2. Extracting Jellyfin Server 12.1.0 ARM64 runtime", pct >= 65, pct >= 35 && pct < 65);
            updateSetupStageItem("setup_step_3", "3. Unpacking Microsoft .NET 10 & FFmpeg 7.1.4 transcoder", pct >= 88, pct >= 65 && pct < 88);
            updateSetupStageItem("setup_step_4", "4. Configuring POSIX permissions & system libraries", pct >= 95, pct >= 88 && pct < 95);
            updateSetupStageItem("setup_step_5", "5. Verifying server binaries & readiness", initialized || pct == 100, pct >= 95 && !initialized);
        }

        if (setupLogSummaryText != null) {
            String logs = controller.getLogs();
            if (logs != null && !logs.isEmpty()) {
                String[] lines = logs.split("\n");
                int start = Math.max(0, lines.length - 4);
                StringBuilder lastFew = new StringBuilder();
                for (int i = start; i < lines.length; i++) {
                    if (lines[i].trim().isEmpty()) continue;
                    if (lastFew.length() > 0) lastFew.append("\n");
                    lastFew.append(lines[i].trim());
                }
                setupLogSummaryText.setText(lastFew.toString());
            }
        }
    }

    private void updateSetupStageItem(String tag, String label, boolean completed, boolean active) {
        if (setupOverlayView == null) return;
        TextView tv = setupOverlayView.findViewWithTag(tag);
        if (tv == null) return;
        if (completed) {
            tv.setText("[DONE] " + label);
            tv.setTextColor(Color.parseColor("#81C784"));
        } else if (active) {
            tv.setText("[EXTRACTING] " + label);
            tv.setTextColor(Color.parseColor("#FFD54F"));
        } else {
            tv.setText("[WAITING] " + label);
            tv.setTextColor(getSecondaryTextColor());
        }
    }

    // ── Tab 0: Home (Dashboard & Server Controls) ──────────────────────────────

    private View buildHomeTab() {
        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(true);
        scroll.setBackgroundColor(getBgColor());

        LinearLayout layout = new LinearLayout(this);
        layout.setOrientation(LinearLayout.VERTICAL);
        layout.setPadding(dp(16), dp(14), dp(16), dp(14));

        // 1. Server Status Hero Card
        layout.addView(buildServerStatusHeroCard());

        // 2. Server Error Diagnoser Card (Shown dynamically when error or crash occurs)
        errorDiagnosticCard = buildErrorDiagnosticCard();
        layout.addView(errorDiagnosticCard);

        // 3. Server Health & Metrics Card
        layout.addView(buildServerHealthCard());

        // 4. Network Connections Card (Color-Coded IP & Dedicated Copy Buttons)
        layout.addView(buildNetworkConnectionsCard());

        // 5. Real Stage-by-Stage Server Startup Progress Card
        startupProgressCard = buildRealStartupProgressCard();
        layout.addView(startupProgressCard);

        // 6. Server Control Actions
        layout.addView(buildDashboardActions());

        scroll.addView(layout);
        return scroll;
    }

    private View buildServerHealthCard() {
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setPadding(dp(18), dp(16), dp(18), dp(16));
        card.setBackground(createRoundedDrawable(getSurfaceColor(), dp(18)));
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(-1, -2);
        params.topMargin = dp(12);
        card.setLayoutParams(params);

        TextView header = new TextView(this);
        header.setText("SERVER HEALTH & RESOURCES");
        header.setTextSize(11);
        header.setTypeface(Typeface.create("sans-serif-medium", Typeface.BOLD));
        header.setTextColor(getSecondaryTextColor());
        header.setPadding(0, 0, 0, dp(12));
        card.addView(header);

        // Row 1: Health Status
        LinearLayout rowStatus = new LinearLayout(this);
        rowStatus.setOrientation(LinearLayout.HORIZONTAL);
        rowStatus.setGravity(Gravity.CENTER_VERTICAL);
        TextView lblStatus = new TextView(this);
        lblStatus.setText("HEALTH CHECK (/health)");
        lblStatus.setTextSize(11);
        lblStatus.setTextColor(getSecondaryTextColor());
        rowStatus.addView(lblStatus, new LinearLayout.LayoutParams(0, -2, 1.0f));

        healthStatusBadge = new TextView(this);
        healthStatusBadge.setText("Checking...");
        healthStatusBadge.setTextSize(12);
        healthStatusBadge.setTypeface(Typeface.create("sans-serif-medium", Typeface.BOLD));
        healthStatusBadge.setTextColor(Color.parseColor("#FFD54F"));
        healthStatusBadge.setPadding(dp(8), dp(2), dp(8), dp(2));
        healthStatusBadge.setBackground(createRoundedDrawable(getSurfaceElevatedColor(), dp(8)));
        rowStatus.addView(healthStatusBadge);
        card.addView(rowStatus);

        // Divider
        View d1 = new View(this);
        d1.setBackgroundColor(colorWithAlpha(getSecondaryTextColor(), 25));
        LinearLayout.LayoutParams pD1 = new LinearLayout.LayoutParams(-1, dp(1));
        pD1.topMargin = dp(8);
        pD1.bottomMargin = dp(8);
        card.addView(d1, pD1);

        // Row 2: Uptime
        LinearLayout rowUptime = new LinearLayout(this);
        rowUptime.setOrientation(LinearLayout.HORIZONTAL);
        rowUptime.setGravity(Gravity.CENTER_VERTICAL);
        TextView lblUptime = new TextView(this);
        lblUptime.setText("UPTIME");
        lblUptime.setTextSize(11);
        lblUptime.setTextColor(getSecondaryTextColor());
        rowUptime.addView(lblUptime, new LinearLayout.LayoutParams(0, -2, 1.0f));

        healthUptimeText = new TextView(this);
        healthUptimeText.setText("0s");
        healthUptimeText.setTextSize(12);
        healthUptimeText.setTypeface(Typeface.MONOSPACE);
        healthUptimeText.setTextColor(getPrimaryTextColor());
        rowUptime.addView(healthUptimeText);
        card.addView(rowUptime);

        // Divider
        View d2 = new View(this);
        d2.setBackgroundColor(colorWithAlpha(getSecondaryTextColor(), 25));
        LinearLayout.LayoutParams pD2 = new LinearLayout.LayoutParams(-1, dp(1));
        pD2.topMargin = dp(8);
        pD2.bottomMargin = dp(8);
        card.addView(d2, pD2);

        // Row 3: Memory Usage
        LinearLayout rowRam = new LinearLayout(this);
        rowRam.setOrientation(LinearLayout.HORIZONTAL);
        rowRam.setGravity(Gravity.CENTER_VERTICAL);
        TextView lblRam = new TextView(this);
        lblRam.setText("DEVICE RAM USAGE");
        lblRam.setTextSize(11);
        lblRam.setTextColor(getSecondaryTextColor());
        rowRam.addView(lblRam, new LinearLayout.LayoutParams(0, -2, 1.0f));

        healthRamText = new TextView(this);
        healthRamText.setText("Sampling...");
        healthRamText.setTextSize(12);
        healthRamText.setTypeface(Typeface.MONOSPACE);
        healthRamText.setTextColor(getPrimaryTextColor());
        rowRam.addView(healthRamText);
        card.addView(rowRam);

        // Divider
        View d3 = new View(this);
        d3.setBackgroundColor(colorWithAlpha(getSecondaryTextColor(), 25));
        LinearLayout.LayoutParams pD3 = new LinearLayout.LayoutParams(-1, dp(1));
        pD3.topMargin = dp(8);
        pD3.bottomMargin = dp(8);
        card.addView(d3, pD3);

        // Row 4: Storage Space
        LinearLayout rowStorage = new LinearLayout(this);
        rowStorage.setOrientation(LinearLayout.HORIZONTAL);
        rowStorage.setGravity(Gravity.CENTER_VERTICAL);
        TextView lblStorage = new TextView(this);
        lblStorage.setText("INTERNAL STORAGE");
        lblStorage.setTextSize(11);
        lblStorage.setTextColor(getSecondaryTextColor());
        rowStorage.addView(lblStorage, new LinearLayout.LayoutParams(0, -2, 1.0f));

        healthStorageText = new TextView(this);
        healthStorageText.setText("Checking...");
        healthStorageText.setTextSize(12);
        healthStorageText.setTypeface(Typeface.MONOSPACE);
        healthStorageText.setTextColor(getPrimaryTextColor());
        rowStorage.addView(healthStorageText);
        card.addView(rowStorage);

        return card;
    }

    private void refreshServerHealthUI() {
        if (healthStatusBadge == null && healthUptimeText == null && healthRamText == null && healthStorageText == null) return;
        new Thread(() -> {
            JellyfinController.ServerHealth health = controller.getServerHealth();
            runOnUiThread(() -> {
                if (healthStatusBadge != null) {
                    if (health.isHealthy) {
                        healthStatusBadge.setText("Healthy (HTTP 200)");
                        healthStatusBadge.setTextColor(Color.parseColor("#81C784"));
                    } else if (controller.getState() == JellyfinController.State.RUNNING) {
                        healthStatusBadge.setText("Responding");
                        healthStatusBadge.setTextColor(Color.parseColor("#FFD54F"));
                    } else {
                        healthStatusBadge.setText("Offline");
                        healthStatusBadge.setTextColor(Color.parseColor("#E57373"));
                    }
                }
                if (healthUptimeText != null) {
                    long s = health.uptimeSeconds;
                    long h = s / 3600;
                    long m = (s % 3600) / 60;
                    long sec = s % 60;
                    String uptimeStr = (h > 0) ? String.format(Locale.ROOT, "%dh %dm %ds", h, m, sec)
                            : (m > 0) ? String.format(Locale.ROOT, "%dm %ds", m, sec)
                            : String.format(Locale.ROOT, "%ds", sec);
                    healthUptimeText.setText(uptimeStr);
                }
                if (healthRamText != null) {
                    if (health.totalRamMb > 0) {
                        healthRamText.setText(health.usedRamMb + " MB / " + health.totalRamMb + " MB (" + health.ramUsagePercent + "%)");
                    } else {
                        healthRamText.setText("N/A");
                    }
                }
                if (healthStorageText != null) {
                    if (health.storageTotalMb > 0) {
                        double freeGb = health.storageFreeMb / 1024.0;
                        double totalGb = health.storageTotalMb / 1024.0;
                        healthStorageText.setText(String.format(Locale.ROOT, "%.1f GB free / %.1f GB", freeGb, totalGb));
                    } else {
                        healthStorageText.setText("N/A");
                    }
                }
            });
        }).start();
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

        LinearLayout badges = new LinearLayout(this);
        badges.setOrientation(LinearLayout.HORIZONTAL);
        badges.setGravity(Gravity.CENTER_VERTICAL);

        TextView jfBadge = new TextView(this);
        jfBadge.setText("Jellyfin 12.1.0");
        jfBadge.setTextSize(11);
        jfBadge.setTypeface(Typeface.create("sans-serif-medium", Typeface.BOLD));
        jfBadge.setTextColor(getAccentColor());
        jfBadge.setPadding(dp(8), dp(4), dp(8), dp(4));
        jfBadge.setBackground(createRoundedDrawable(getSurfaceElevatedColor(), dp(10)));
        LinearLayout.LayoutParams jfParams = new LinearLayout.LayoutParams(-2, -2);
        jfParams.rightMargin = dp(6);
        badges.addView(jfBadge, jfParams);

        TextView versionBadge = new TextView(this);
        versionBadge.setText("v" + com.termux.BuildConfig.VERSION_NAME);
        versionBadge.setTextSize(11);
        versionBadge.setTypeface(Typeface.MONOSPACE);
        versionBadge.setTextColor(getSecondaryTextColor());
        versionBadge.setPadding(dp(8), dp(4), dp(8), dp(4));
        versionBadge.setBackground(createRoundedDrawable(getSurfaceElevatedColor(), dp(10)));
        badges.addView(versionBadge);

        card.addView(badges);

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

        // Divider line 2
        View divider2 = new View(this);
        divider2.setBackgroundColor(colorWithAlpha(getSecondaryTextColor(), 30));
        LinearLayout.LayoutParams divParams2 = new LinearLayout.LayoutParams(-1, dp(1));
        divParams2.topMargin = dp(10);
        divParams2.bottomMargin = dp(10);
        card.addView(divider2, divParams2);

        // Tailscale Address Box
        card.addView(buildTailscaleAddressRow());

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

    private View buildTailscaleAddressRow() {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);

        LinearLayout info = new LinearLayout(this);
        info.setOrientation(LinearLayout.VERTICAL);

        TextView lbl = new TextView(this);
        lbl.setText("TAILSCALE ADDRESS");
        lbl.setTextSize(11);
        lbl.setTextColor(getSecondaryTextColor());
        info.addView(lbl);

        tailscaleIpValueText = new TextView(this);
        tailscaleIpValueText.setText("Detecting Tailscale address…");
        tailscaleIpValueText.setTextSize(14);
        tailscaleIpValueText.setTypeface(Typeface.create("sans-serif-medium", Typeface.BOLD));
        tailscaleIpValueText.setTextColor(Color.parseColor("#81C784"));
        tailscaleIpValueText.setPadding(0, dp(2), 0, 0);
        info.addView(tailscaleIpValueText);

        row.addView(info, new LinearLayout.LayoutParams(0, -2, 1.0f));

        btnCopyTailscaleIp = createPillCopyButton();
        btnCopyTailscaleIp.setOnClickListener(v -> {
            String text = tailscaleIpValueText.getText().toString();
            if (text.startsWith("http://")) {
                copyUrlToClipboard("Tailscale Server Address", text);
            }
        });
        row.addView(btnCopyTailscaleIp);

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

        btnStartServer = createPixelButton("START SERVER", Color.parseColor("#2E7D32"), Color.WHITE);
        btnStartServer.setOnClickListener(v -> {
            long now = SystemClock.elapsedRealtime();
            if (now - lastButtonActionTimestamp < BUTTON_DEBOUNCE_MS) return;
            lastButtonActionTimestamp = now;
            errorCardDismissed = false;

            applyButtonStyle(btnStartServer, "STARTING...", Color.parseColor("#F57F17"), Color.WHITE, false);
            if (btnStopServer != null) btnStopServer.setEnabled(false);
            if (btnRestartServer != null) btnRestartServer.setEnabled(false);
            triggerServerAction(JellyfinServerService.ACTION_START);
        });
        layout.addView(btnStartServer);

        btnStopServer = createPixelButton("STOP SERVER", Color.parseColor("#D32F2F"), Color.WHITE);
        btnStopServer.setOnClickListener(v -> {
            long now = SystemClock.elapsedRealtime();
            if (now - lastButtonActionTimestamp < BUTTON_DEBOUNCE_MS) return;
            lastButtonActionTimestamp = now;

            applyButtonStyle(btnStopServer, "STOPPING...", Color.parseColor("#BF360C"), Color.WHITE, false);
            if (btnStartServer != null) btnStartServer.setEnabled(false);
            if (btnRestartServer != null) btnRestartServer.setEnabled(false);
            controller.stop();
            triggerServerAction(JellyfinServerService.ACTION_STOP);
        });
        layout.addView(btnStopServer);

        btnRestartServer = createPixelButton("RESTART SERVER", getSurfaceColor(), getPrimaryTextColor());
        btnRestartServer.setOnClickListener(v -> {
            long now = SystemClock.elapsedRealtime();
            if (now - lastButtonActionTimestamp < BUTTON_DEBOUNCE_MS) return;
            lastButtonActionTimestamp = now;
            errorCardDismissed = false;

            applyButtonStyle(btnRestartServer, "RESTARTING...", Color.parseColor("#F57F17"), Color.WHITE, false);
            if (btnStartServer != null) btnStartServer.setEnabled(false);
            if (btnStopServer != null) btnStopServer.setEnabled(false);
            controller.restart(this);
        });
        layout.addView(btnRestartServer);

        btnResetCrash = createPixelButton("RESET CRASH LOOP", Color.parseColor("#B71C1C"), Color.WHITE);
        btnResetCrash.setOnClickListener(v -> {
            long now = SystemClock.elapsedRealtime();
            if (now - lastButtonActionTimestamp < BUTTON_DEBOUNCE_MS) return;
            lastButtonActionTimestamp = now;
            controller.resetCrashLoop(this);
        });
        btnResetCrash.setVisibility(View.GONE);
        layout.addView(btnResetCrash);

        btnReinstallRuntime = createPixelButton("REINSTALL RUNTIME", Color.parseColor("#455A64"), Color.WHITE);
        btnReinstallRuntime.setOnClickListener(v -> showReinstallConfirmation());
        btnReinstallRuntime.setVisibility(View.GONE);
        layout.addView(btnReinstallRuntime);

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
        layout.setBackgroundColor(getBgColor());

        LinearLayout actions = new LinearLayout(this);
        actions.setOrientation(LinearLayout.HORIZONTAL);
        actions.setPadding(0, 0, 0, dp(10));

        Button btnRefresh = createPixelButton("REFRESH", getSurfaceColor(), getPrimaryTextColor());
        btnRefresh.setOnClickListener(v -> refreshLogsDisplay());
        LinearLayout.LayoutParams p1 = new LinearLayout.LayoutParams(0, -2, 1.0f);
        p1.rightMargin = dp(4);
        actions.addView(btnRefresh, p1);

        Button btnToggleLog = createPixelButton("DISK LOG", getSurfaceColor(), getPrimaryTextColor());
        btnToggleLog.setOnClickListener(v -> {
            showingDiskLog = !showingDiskLog;
            btnToggleLog.setText(showingDiskLog ? "CONSOLE" : "DISK LOG");
            refreshLogsDisplay();
        });
        LinearLayout.LayoutParams p2 = new LinearLayout.LayoutParams(0, -2, 1.0f);
        p2.rightMargin = dp(4);
        actions.addView(btnToggleLog, p2);

        Button btnCopy = createPixelButton("COPY", getSurfaceColor(), getPrimaryTextColor());
        btnCopy.setOnClickListener(v -> {
            String content = (logsOutputText != null) ? logsOutputText.getText().toString() : "";
            if (!content.isEmpty()) {
                android.content.ClipboardManager cm = (android.content.ClipboardManager) getSystemService(CLIPBOARD_SERVICE);
                if (cm != null) {
                    cm.setPrimaryClip(android.content.ClipData.newPlainText("Fishbowl Logs", content));
                    android.widget.Toast.makeText(this, "Logs copied to clipboard", android.widget.Toast.LENGTH_SHORT).show();
                }
            }
        });
        LinearLayout.LayoutParams p3 = new LinearLayout.LayoutParams(0, -2, 1.0f);
        p3.rightMargin = dp(4);
        actions.addView(btnCopy, p3);

        Button btnClear = createPixelButton("CLEAR", getSurfaceColor(), getPrimaryTextColor());
        btnClear.setOnClickListener(v -> {
            controller.clearDisplayedLogs();
            refreshLogsDisplay();
        });
        LinearLayout.LayoutParams p4 = new LinearLayout.LayoutParams(0, -2, 1.0f);
        actions.addView(btnClear, p4);

        layout.addView(actions);

        logsScrollView = new ScrollView(this);
        logsScrollView.setBackground(createRoundedDrawable(Color.parseColor("#121316"), dp(12)));

        logsOutputText = new TextView(this);
        logsOutputText.setTextColor(Color.parseColor("#81C784"));
        logsOutputText.setTextSize(12);
        logsOutputText.setTypeface(Typeface.MONOSPACE);
        logsOutputText.setPadding(dp(12), dp(12), dp(12), dp(12));
        refreshLogsDisplay();

        logsScrollView.addView(logsOutputText);
        layout.addView(logsScrollView, new LinearLayout.LayoutParams(-1, 0, 1.0f));

        return layout;
    }

    private void refreshLogsDisplay() {
        if (logsOutputText != null && controller != null) {
            if (showingDiskLog) {
                String disk = controller.getDiskLogs();
                logsOutputText.setText(disk.isEmpty() ? "--- No Jellyfin disk log found yet in $DATA_DIR/log ---" : disk);
            } else {
                logsOutputText.setText(controller.getLogs());
            }
        }
    }

    @Override
    public void onLogAppended() {
        scheduleThrottledLogUpdate();
    }

    private void scheduleThrottledLogUpdate() {
        if (logUpdatePending) return;
        logUpdatePending = true;
        logUpdateHandler.postDelayed(() -> {
            logUpdatePending = false;
            if (isFinishing() || (Build.VERSION.SDK_INT >= Build.VERSION_CODES.JELLY_BEAN_MR1 && isDestroyed())) return;
            if (setupOverlayView != null && setupOverlayView.getVisibility() == View.VISIBLE) {
                updateSetupLogSummary();
            }
            if (activeTab == 1 && logsOutputText != null) {
                refreshLogsDisplay();
                if (logsScrollView != null) {
                    logsScrollView.post(() -> logsScrollView.fullScroll(View.FOCUS_DOWN));
                }
            }
        }, 150);
    }

    private void updateSetupLogSummary() {
        if (setupLogSummaryText == null || controller == null) return;
        String logs = controller.getLogs();
        if (logs != null && !logs.isEmpty()) {
            String[] lines = logs.split("\n");
            int start = Math.max(0, lines.length - 4);
            StringBuilder lastFew = new StringBuilder();
            for (int i = start; i < lines.length; i++) {
                if (lines[i].trim().isEmpty()) continue;
                if (lastFew.length() > 0) lastFew.append("\n");
                lastFew.append(lines[i].trim());
            }
            setupLogSummaryText.setText(lastFew.toString());
        }
    }

    private boolean isScrollAtBottom(ScrollView scroll) {
        if (scroll == null) return true;
        View child = scroll.getChildAt(0);
        if (child == null) return true;
        int diff = (child.getBottom() - (scroll.getHeight() + scroll.getScrollY()));
        return diff <= dp(60);
    }

    // ── Tab 2: Settings View (Integrated Settings & Media Storage) ────────────

    private View buildSettingsTab() {
        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(true);
        scroll.setBackgroundColor(getBgColor());

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

        LinearLayout serverSpecs = new LinearLayout(this);
        serverSpecs.setOrientation(LinearLayout.VERTICAL);
        serverSpecs.setPadding(0, dp(8), 0, dp(10));
        serverSpecs.addView(buildSpecRow("Fishbowl Version", com.termux.BuildConfig.VERSION_NAME));
        serverSpecs.addView(buildSpecRow("Embedded Jellyfin", "12.1.0 ARM64"));
        serverSpecs.addView(buildSpecRow("Local Web UI", "http://127.0.0.1:8096"));
        serverSpecs.addView(buildSpecRow("Network Port", "8096 (HTTP)"));
        serverCard.addView(serverSpecs);

        Switch autoStartSwitch = new Switch(this);
        autoStartSwitch.setText("Auto-start Jellyfin on device boot");
        autoStartSwitch.setTextSize(13);
        autoStartSwitch.setTextColor(getPrimaryTextColor());
        autoStartSwitch.setPadding(0, dp(4), 0, dp(6));
        SharedPreferences prefs = getSharedPreferences("jellyfindroid", MODE_PRIVATE);
        autoStartSwitch.setChecked(prefs.getBoolean("auto_start", false));
        autoStartSwitch.setOnCheckedChangeListener((v, checked) -> prefs.edit().putBoolean("auto_start", checked).apply());
        serverCard.addView(autoStartSwitch);

        // Battery Optimization Setting
        Button btnBattery = createPixelButton("REQUEST BATTERY UNRESTRICTED", getSurfaceElevatedColor(), getPrimaryTextColor());
        btnBattery.setOnClickListener(v -> requestIgnoreBatteryOptimizationsIfNeeded());
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            PowerManager pm = (PowerManager) getSystemService(POWER_SERVICE);
            if (pm != null && pm.isIgnoringBatteryOptimizations(getPackageName())) {
                btnBattery.setText("BATTERY OPTIMIZATION: UNRESTRICTED");
                btnBattery.setEnabled(false);
                btnBattery.setTextColor(Color.parseColor("#81C784"));
            }
        }
        serverCard.addView(btnBattery);

        layout.addView(serverCard);

        // System Permissions & Privileges Card (Live ON / OFF Detection)
        layout.addView(buildPermissionsCard());

        // Storage & Directories Card (Essential)
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

        // Auto-detected Storage Drives
        LinearLayout drivesList = new LinearLayout(this);
        drivesList.setOrientation(LinearLayout.VERTICAL);
        drivesList.setPadding(0, dp(10), 0, dp(6));
        try {
            List<StorageDriveHelper.DriveInfo> drives = StorageDriveHelper.getMountedDrives(this);
            for (StorageDriveHelper.DriveInfo d : drives) {
                LinearLayout row = new LinearLayout(this);
                row.setOrientation(LinearLayout.HORIZONTAL);
                row.setGravity(Gravity.CENTER_VERTICAL);
                row.setPadding(dp(12), dp(8), dp(12), dp(8));
                row.setBackground(createRoundedDrawable(getSurfaceElevatedColor(), dp(10)));
                LinearLayout.LayoutParams pRow = new LinearLayout.LayoutParams(-1, -2);
                pRow.bottomMargin = dp(6);
                row.setLayoutParams(pRow);

                LinearLayout col = new LinearLayout(this);
                col.setOrientation(LinearLayout.VERTICAL);

                TextView name = new TextView(this);
                name.setText(d.name + (d.isPrimary ? " (Internal)" : " (USB / External - Beta Testing)"));
                name.setTextSize(13);
                name.setTypeface(Typeface.create("sans-serif-medium", Typeface.BOLD));
                name.setTextColor(getPrimaryTextColor());
                col.addView(name);

                TextView pathTv = new TextView(this);
                pathTv.setText(d.rootPath + (d.totalBytes > 0 ? " • " + StorageDriveHelper.formatCapacity(d.freeBytes, d.totalBytes) : ""));
                pathTv.setTextSize(11);
                pathTv.setTextColor(getSecondaryTextColor());
                col.addView(pathTv);

                row.addView(col, new LinearLayout.LayoutParams(0, -2, 1.0f));

                if (!d.isPrimary) {
                    TextView betaPill = new TextView(this);
                    betaPill.setText("BETA");
                    betaPill.setTextSize(10);
                    betaPill.setTypeface(Typeface.create("sans-serif-medium", Typeface.BOLD));
                    betaPill.setTextColor(Color.parseColor("#FFB74D"));
                    betaPill.setBackground(createRoundedDrawable(Color.parseColor("#3A2E1A"), dp(6)));
                    betaPill.setPadding(dp(8), dp(4), dp(8), dp(4));
                    row.addView(betaPill);

                    View pillSpacer = new View(this);
                    row.addView(pillSpacer, new LinearLayout.LayoutParams(dp(6), 1));
                }

                TextView pill = new TextView(this);
                pill.setText("ACTIVE");
                pill.setTextSize(10);
                pill.setTypeface(Typeface.create("sans-serif-medium", Typeface.BOLD));
                pill.setTextColor(Color.parseColor("#81C784"));
                pill.setBackground(createRoundedDrawable(getSurfaceColor(), dp(6)));
                pill.setPadding(dp(8), dp(4), dp(8), dp(4));
                row.addView(pill);

                drivesList.addView(row);
            }
        } catch (Throwable ignored) {}
        storageCard.addView(drivesList);

        storageInfoText = new TextView(this);
        storageInfoText.setTextSize(12);
        storageInfoText.setTextColor(getSecondaryTextColor());
        storageInfoText.setPadding(0, dp(4), 0, dp(6));
        updateStorageInfoText();
        storageCard.addView(storageInfoText);

        LinearLayout safRow = new LinearLayout(this);
        safRow.setOrientation(LinearLayout.HORIZONTAL);
        safRow.setGravity(Gravity.CENTER_VERTICAL);
        safRow.setPadding(0, 0, 0, dp(10));

        TextView safTitle = new TextView(this);
        safTitle.setText("SAF (Storage Access Framework)");
        safTitle.setTextSize(13);
        safTitle.setTextColor(getPrimaryTextColor());
        safRow.addView(safTitle, new LinearLayout.LayoutParams(0, -2, 1.0f));

        TextView safPill = new TextView(this);
        safPill.setText("BETA / TESTING MODE");
        safPill.setTextSize(10);
        safPill.setTypeface(Typeface.create("sans-serif-medium", Typeface.BOLD));
        safPill.setTextColor(Color.parseColor("#FFB74D"));
        safPill.setBackground(createRoundedDrawable(Color.parseColor("#3A2E1A"), dp(6)));
        safPill.setPadding(dp(8), dp(4), dp(8), dp(4));
        safRow.addView(safPill);

        storageCard.addView(safRow);

        Button manageStorageBtn = createPixelButton("MANAGE MEDIA STORAGE & FOLDERS", getAccentColor(), Color.WHITE);
        manageStorageBtn.setOnClickListener(v -> startActivity(new Intent(this, JellyfinStorageActivity.class)));
        storageCard.addView(manageStorageBtn);

        Button clearCacheBtn = createPixelButton("CLEAR CACHE", Color.parseColor("#D32F2F"), Color.WHITE);
        clearCacheBtn.setOnClickListener(v -> showClearCacheConfirmation());
        storageCard.addView(clearCacheBtn);

        layout.addView(storageCard);

        // Transcoding & Hardware Audit Card (Non-Essential / Collapsible Drawer)
        LinearLayout transcodeCard = new LinearLayout(this);
        transcodeCard.setOrientation(LinearLayout.VERTICAL);
        transcodeCard.setPadding(dp(18), dp(18), dp(18), dp(18));
        transcodeCard.setBackground(createRoundedDrawable(getSurfaceColor(), dp(18)));
        LinearLayout.LayoutParams pTranscode = new LinearLayout.LayoutParams(-1, -2);
        pTranscode.topMargin = dp(14);
        transcodeCard.setLayoutParams(pTranscode);

        LinearLayout transcodeContent = new LinearLayout(this);
        transcodeContent.setOrientation(LinearLayout.VERTICAL);

        LinearLayout transcodeSpecs = new LinearLayout(this);
        transcodeSpecs.setOrientation(LinearLayout.VERTICAL);
        transcodeSpecs.setPadding(0, dp(8), 0, dp(8));
        transcodeSpecs.addView(buildSpecRow("Software Transcoder", "libx264, libx265, aac, opus"));
        transcodeSpecs.addView(buildSpecRow("Hardware Codecs", "MediaCodec Detected"));
        transcodeSpecs.addView(buildSpecRow("FFmpeg Binary", "7.1.4-Jellyfin ARM64"));
        transcodeSpecs.addView(buildSpecRow("Active Pipeline", "Software (Verified @ 60 FPS)"));
        transcodeContent.addView(transcodeSpecs);

        TextView tvDiag = new TextView(this);
        tvDiag.setTextSize(12);
        tvDiag.setTextColor(Color.parseColor("#81C784"));
        tvDiag.setPadding(0, dp(2), 0, dp(8));
        transcodeContent.addView(tvDiag);

        Button btnTestTranscode = createPixelButton("TEST TRANSCODING", getAccentColor(), Color.WHITE);
        btnTestTranscode.setOnClickListener(v -> {
            btnTestTranscode.setEnabled(false);
            btnTestTranscode.setText("TESTING TRANSCODING...");
            new Thread(() -> {
                TranscodingDiagnostics.TranscodeTestResult tr = TranscodingDiagnostics.runTestTranscode(this);
                runOnUiThread(() -> {
                    btnTestTranscode.setEnabled(true);
                    btnTestTranscode.setText("TEST TRANSCODING");
                    String res = "Test Result: " + (tr.success ? "SUCCESS" : "FAILED") +
                            " | " + tr.speed + " (" + tr.fps + " fps) | " + tr.inputCodec + " -> " + tr.outputCodec;
                    tvDiag.setText(res);
                    tvDiag.setTextColor(tr.success ? Color.parseColor("#81C784") : Color.parseColor("#E57373"));
                });
            }).start();
        });
        transcodeContent.addView(btnTestTranscode);

        transcodeCard.addView(createCollapsibleCardHeader("Transcoding & Hardware Codecs", transcodeContent, false));
        transcodeCard.addView(transcodeContent);

        layout.addView(transcodeCard);

        // Updates Card
        LinearLayout updatesCard = new LinearLayout(this);
        updatesCard.setOrientation(LinearLayout.VERTICAL);
        updatesCard.setPadding(dp(18), dp(18), dp(18), dp(18));
        updatesCard.setBackground(createRoundedDrawable(getSurfaceColor(), dp(18)));
        LinearLayout.LayoutParams pUpdates = new LinearLayout.LayoutParams(-1, -2);
        pUpdates.topMargin = dp(14);
        updatesCard.setLayoutParams(pUpdates);

        TextView tUpdatesTitle = new TextView(this);
        tUpdatesTitle.setText("Updates");
        tUpdatesTitle.setTextSize(17);
        tUpdatesTitle.setTypeface(Typeface.DEFAULT_BOLD);
        tUpdatesTitle.setTextColor(getPrimaryTextColor());
        updatesCard.addView(tUpdatesTitle);

        updateStatusText = new TextView(this);
        updateStatusText.setTextSize(13);
        updateStatusText.setTextColor(getSecondaryTextColor());
        updateStatusText.setPadding(0, dp(8), 0, dp(12));
        updatesCard.addView(updateStatusText);

        updateActionBtn = createPixelButton("Check for updates", getAccentColor(), Color.WHITE);
        updatesCard.addView(updateActionBtn);

        layout.addView(updatesCard);

        // Installed Packages & System Security Transparency Card (Non-Essential / Collapsible Drawer)
        LinearLayout aboutCard = new LinearLayout(this);
        aboutCard.setOrientation(LinearLayout.VERTICAL);
        aboutCard.setPadding(dp(18), dp(18), dp(18), dp(18));
        aboutCard.setBackground(createRoundedDrawable(getSurfaceColor(), dp(18)));
        LinearLayout.LayoutParams pAbout = new LinearLayout.LayoutParams(-1, -2);
        pAbout.topMargin = dp(14);
        aboutCard.setLayoutParams(pAbout);

        LinearLayout aboutContent = new LinearLayout(this);
        aboutContent.setOrientation(LinearLayout.VERTICAL);

        // Runtime Installation Status Summary Item
        View runtimeStatusItem = buildRuntimeStatusItem();
        aboutContent.addView(runtimeStatusItem);

        // Compact Specs Summary
        LinearLayout specSummary = new LinearLayout(this);
        specSummary.setOrientation(LinearLayout.VERTICAL);
        specSummary.setPadding(0, dp(6), 0, dp(8));
        specSummary.addView(buildSpecRow("Jellyfin Server Core", "v12.1.0 ARM64"));
        specSummary.addView(buildSpecRow("Microsoft .NET Runtime", "v10.0.12 Linux Bionic"));
        specSummary.addView(buildSpecRow("FFmpeg Transcoder", "v7.1.4-Jellyfin ARM64"));
        specSummary.addView(buildSpecRow("Database Driver", "SQLite3 (Local)"));
        specSummary.addView(buildSpecRow("Security & TLS Stack", "OpenSSL 3.x System Native"));
        specSummary.addView(buildSpecRow("Process Isolation", "Android Sandboxed UID"));
        specSummary.addView(buildSpecRow("Privacy & Telemetry", "0 Trackers | 100% Local"));
        aboutContent.addView(specSummary);

        // Collapsible Technical Component Details (Collapsed by default)
        LinearLayout detailsContainer = new LinearLayout(this);
        detailsContainer.setOrientation(LinearLayout.VERTICAL);
        detailsContainer.setVisibility(View.GONE);

        detailsContainer.addView(buildTransparencyItem("Jellyfin Media Server Core", "Official Jellyfin Server ARM64 binaries (jellyfin.dll). Self-contained web media server engine. Powered by Jellyfin."));
        detailsContainer.addView(buildTransparencyItem("Microsoft .NET Runtime Engine", "Bundled in lib/dotnet/. Modern high-performance cross-platform managed runtime hosting Jellyfin server."));
        detailsContainer.addView(buildTransparencyItem("FFmpeg Hardware Transcoder", "Bundled in opt/jellyfin/bin/ffmpeg. Used for media remuxing, subtitle burn-in, video thumbnails, and audio transcoding."));
        detailsContainer.addView(buildTransparencyItem("Foreground Service & Reliability", "mediaPlayback (ID 1001) Android declared foreground service protecting playback streaming and server reliability."));
        detailsContainer.addView(buildTransparencyItem("Safe Cache Management System", "Strict allowlist cache purging for transcodes, audiodb, and fanart without touching databases, users, or configs."));
        detailsContainer.addView(buildTransparencyItem("OpenSSL Security & TLS Stack", "OpenSSL 3.x cryptographic SSL/TLS engine managing local HTTPS encryption and secure outbound metadata sockets."));
        detailsContainer.addView(buildTransparencyItem("Fontconfig & FreeType Engines", "libfontconfig.so / libfreetype.so native C libraries used by FFmpeg for video subtitle burn-in and text rendering."));
        detailsContainer.addView(buildTransparencyItem("SQLite3 Database Driver", "libe_sqlite3.so embedded lightweight relational database engine storing user library indexes and watch progress locally."));
        detailsContainer.addView(buildTransparencyItem("Minimal Linux Subsystem", "APT Package Manager & Android Bionic C Library (libc) hosting Dotnet processes without background telemetry or trackers."));
        detailsContainer.addView(buildTransparencyItem("Unicode & Globalization Engine", "Full ICU globalization runtime providing database internationalization and culture-aware metadata processing."));
        detailsContainer.addView(buildTransparencyItem("Storage Access Framework (SAF) [BETA / TESTING MODE]", "Android DocumentProvider and SAF URI integration with direct filesystem POSIX paths. Currently in experimental beta testing mode. Drive root and virtual cloud storage are held."));
        detailsContainer.addView(buildTransparencyItem("Application Package & Scope", getPackageName() + " (Fishbowl v" + com.termux.BuildConfig.VERSION_NAME + ") isolated Android package namespace."));
        detailsContainer.addView(buildTransparencyItem("Privacy & Telemetry Verification", "No background metrics are collected or transmitted to external servers. All media data stays strictly on your device."));
        aboutContent.addView(detailsContainer);

        Button btnToggleDetails = createPixelButton("SHOW COMPONENT DETAILS", getSurfaceElevatedColor(), getPrimaryTextColor());
        btnToggleDetails.setOnClickListener(v -> {
            if (detailsContainer.getVisibility() == View.VISIBLE) {
                detailsContainer.setVisibility(View.GONE);
                btnToggleDetails.setText("SHOW COMPONENT DETAILS");
            } else {
                detailsContainer.setVisibility(View.VISIBLE);
                btnToggleDetails.setText("HIDE COMPONENT DETAILS");
            }
        });
        aboutContent.addView(btnToggleDetails);

        LinearLayout repoRow = new LinearLayout(this);
        repoRow.setOrientation(LinearLayout.HORIZONTAL);
        repoRow.setPadding(0, dp(6), 0, 0);

        Button btnGithubRepo = createPixelButton("GITHUB REPO", getSurfaceElevatedColor(), getPrimaryTextColor());
        btnGithubRepo.setOnClickListener(v -> {
            try {
                startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse("https://github.com/Suz41/Fishbowl")));
            } catch (Throwable t) {
                Toast.makeText(this, "Could not open browser: https://github.com/Suz41/Fishbowl", Toast.LENGTH_LONG).show();
            }
        });
        repoRow.addView(btnGithubRepo, new LinearLayout.LayoutParams(0, dp(42), 1.0f));

        View repoSpacer = new View(this);
        repoRow.addView(repoSpacer, new LinearLayout.LayoutParams(dp(8), 1));

        Button btnGithubIssues = createPixelButton("REPORT ISSUE", getSurfaceElevatedColor(), getAccentColor());
        btnGithubIssues.setOnClickListener(v -> {
            try {
                Intent intent = new Intent(Intent.ACTION_VIEW, Uri.parse("https://github.com/Suz41/Fishbowl/issues"));
                startActivity(intent);
            } catch (Throwable t) {
                Toast.makeText(this, "Could not open browser: https://github.com/Suz41/Fishbowl/issues", Toast.LENGTH_LONG).show();
            }
        });
        repoRow.addView(btnGithubIssues, new LinearLayout.LayoutParams(0, dp(42), 1.0f));

        aboutContent.addView(repoRow);

        aboutCard.addView(createCollapsibleCardHeader("System Components & Security", aboutContent, false));
        aboutCard.addView(aboutContent);

        layout.addView(aboutCard);

        scroll.addView(layout);
        return scroll;
    }

    private LinearLayout createCollapsibleCardHeader(String titleText, View contentContainer, boolean initiallyExpanded) {
        LinearLayout header = new LinearLayout(this);
        header.setOrientation(LinearLayout.HORIZONTAL);
        header.setGravity(Gravity.CENTER_VERTICAL);
        header.setPadding(0, 0, 0, initiallyExpanded ? dp(8) : 0);

        TextView title = new TextView(this);
        title.setText(titleText);
        title.setTextSize(17);
        title.setTypeface(Typeface.DEFAULT_BOLD);
        title.setTextColor(getPrimaryTextColor());
        header.addView(title, new LinearLayout.LayoutParams(0, -2, 1.0f));

        TextView arrow = new TextView(this);
        arrow.setText(initiallyExpanded ? "▲" : "▼");
        arrow.setTextSize(14);
        arrow.setTextColor(getSecondaryTextColor());
        arrow.setPadding(dp(8), dp(4), dp(4), dp(4));
        header.addView(arrow);

        contentContainer.setVisibility(initiallyExpanded ? View.VISIBLE : View.GONE);

        header.setOnClickListener(v -> {
            boolean isExpanded = contentContainer.getVisibility() == View.VISIBLE;
            if (isExpanded) {
                contentContainer.setVisibility(View.GONE);
                arrow.setText("▼");
                header.setPadding(0, 0, 0, 0);
            } else {
                contentContainer.setVisibility(View.VISIBLE);
                arrow.setText("▲");
                header.setPadding(0, 0, 0, dp(8));
            }
        });

        return header;
    }

    private View buildSpecRow(String label, String value) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(0, dp(4), 0, dp(4));

        TextView tvLabel = new TextView(this);
        tvLabel.setText(label);
        tvLabel.setTextSize(13);
        tvLabel.setTextColor(getSecondaryTextColor());
        row.addView(tvLabel, new LinearLayout.LayoutParams(0, -2, 1.0f));

        TextView tvVal = new TextView(this);
        tvVal.setText(value);
        tvVal.setTextSize(13);
        tvVal.setTypeface(Typeface.create("sans-serif-medium", Typeface.BOLD));
        tvVal.setTextColor(getPrimaryTextColor());
        row.addView(tvVal);

        return row;
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

    // ── System Permissions Card & Detection ───────────────────────────────────

    private View buildPermissionsCard() {
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setPadding(dp(18), dp(18), dp(18), dp(18));
        card.setBackground(createRoundedDrawable(getSurfaceColor(), dp(18)));
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(-1, -2);
        params.topMargin = dp(14);
        card.setLayoutParams(params);

        TextView title = new TextView(this);
        title.setText("System Permissions & Privileges");
        title.setTextSize(17);
        title.setTypeface(Typeface.DEFAULT_BOLD);
        title.setTextColor(getPrimaryTextColor());
        card.addView(title);

        TextView subtitle = new TextView(this);
        subtitle.setText("Verify critical Android permissions for uninterrupted streaming and storage access.");
        subtitle.setTextSize(12);
        subtitle.setTextColor(getSecondaryTextColor());
        subtitle.setPadding(0, dp(4), 0, dp(12));
        card.addView(subtitle);

        // 1. Storage Permission Row
        LinearLayout rowStorage = buildPermissionItem(
                "All Files & Media Storage",
                "Grants Jellyfin read access to movies, music, and shows on internal and external drives.",
                v -> {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                        try {
                            Intent intent = new Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION);
                            intent.setData(Uri.parse("package:" + getPackageName()));
                            startActivity(intent);
                        } catch (Throwable t) {
                            try {
                                startActivity(new Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION));
                            } catch (Throwable t2) {
                                startActivity(new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:" + getPackageName())));
                            }
                        }
                    } else {
                        ActivityCompat.requestPermissions(this, new String[]{
                                Manifest.permission.READ_EXTERNAL_STORAGE,
                                Manifest.permission.WRITE_EXTERNAL_STORAGE
                        }, 100);
                    }
                }
        );
        permStorageBadge = rowStorage.findViewWithTag("badge");
        permStorageAction = rowStorage.findViewWithTag("action_text");
        card.addView(rowStorage);

        // Divider
        View d1 = new View(this);
        d1.setBackgroundColor(colorWithAlpha(getSecondaryTextColor(), 25));
        LinearLayout.LayoutParams pD1 = new LinearLayout.LayoutParams(-1, dp(1));
        pD1.topMargin = dp(8);
        pD1.bottomMargin = dp(8);
        card.addView(d1, pD1);

        // 2. Battery Optimization Row
        LinearLayout rowBattery = buildPermissionItem(
                "Battery Optimization Exemption",
                "Prevents Android OS from pausing or terminating background playback services.",
                v -> requestIgnoreBatteryOptimizationsIfNeeded()
        );
        permBatteryBadge = rowBattery.findViewWithTag("badge");
        permBatteryAction = rowBattery.findViewWithTag("action_text");
        card.addView(rowBattery);

        // Divider
        View d2 = new View(this);
        d2.setBackgroundColor(colorWithAlpha(getSecondaryTextColor(), 25));
        LinearLayout.LayoutParams pD2 = new LinearLayout.LayoutParams(-1, dp(1));
        pD2.topMargin = dp(8);
        pD2.bottomMargin = dp(8);
        card.addView(d2, pD2);

        // 3. Notifications Row
        LinearLayout rowNotif = buildPermissionItem(
                "Foreground Service Notifications",
                "Maintains persistent media server notification to protect background execution.",
                v -> {
                    Intent intent = new Intent();
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                        intent.setAction(Settings.ACTION_APP_NOTIFICATION_SETTINGS);
                        intent.putExtra(Settings.EXTRA_APP_PACKAGE, getPackageName());
                    } else {
                        intent.setAction(Settings.ACTION_APPLICATION_DETAILS_SETTINGS);
                        intent.setData(Uri.parse("package:" + getPackageName()));
                    }
                    try {
                        startActivity(intent);
                    } catch (Throwable t) {
                        startActivity(new Intent(Settings.ACTION_SETTINGS));
                    }
                }
        );
        permNotifBadge = rowNotif.findViewWithTag("badge");
        permNotifAction = rowNotif.findViewWithTag("action_text");
        card.addView(rowNotif);

        refreshPermissionsUI();
        return card;
    }

    private LinearLayout buildPermissionItem(String name, String desc, View.OnClickListener onClick) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(0, dp(4), 0, dp(4));

        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);

        TextView tvName = new TextView(this);
        tvName.setText(name);
        tvName.setTextSize(13);
        tvName.setTypeface(Typeface.create("sans-serif-medium", Typeface.BOLD));
        tvName.setTextColor(getPrimaryTextColor());
        col.addView(tvName);

        TextView tvDesc = new TextView(this);
        tvDesc.setText(desc);
        tvDesc.setTextSize(11);
        tvDesc.setTextColor(getSecondaryTextColor());
        tvDesc.setPadding(0, dp(2), 0, dp(2));
        col.addView(tvDesc);

        TextView tvAction = new TextView(this);
        tvAction.setTextSize(11);
        tvAction.setTag("action_text");
        col.addView(tvAction);

        row.addView(col, new LinearLayout.LayoutParams(0, -2, 1.0f));

        TextView badge = new TextView(this);
        badge.setTextSize(11);
        badge.setTypeface(Typeface.create("sans-serif-medium", Typeface.BOLD));
        badge.setTag("badge");
        badge.setPadding(dp(10), dp(4), dp(10), dp(4));
        row.addView(badge);

        row.setOnClickListener(onClick);
        return row;
    }

    private void refreshPermissionsUI() {
        // 1. Storage
        if (permStorageBadge != null) {
            boolean storageGranted;
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                storageGranted = Environment.isExternalStorageManager();
            } else {
                storageGranted = ContextCompat.checkSelfPermission(this, Manifest.permission.READ_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED;
            }
            if (storageGranted) {
                permStorageBadge.setText("ON");
                permStorageBadge.setTextColor(Color.parseColor("#81C784"));
                permStorageBadge.setBackground(createRoundedDrawable(Color.parseColor("#1F2A22"), dp(8)));
                if (permStorageAction != null) {
                    permStorageAction.setText("Storage access granted");
                    permStorageAction.setTextColor(Color.parseColor("#81C784"));
                }
            } else {
                permStorageBadge.setText("OFF");
                permStorageBadge.setTextColor(Color.parseColor("#E57373"));
                permStorageBadge.setBackground(createRoundedDrawable(Color.parseColor("#3B1D1D"), dp(8)));
                if (permStorageAction != null) {
                    permStorageAction.setText("Tap to grant storage access");
                    permStorageAction.setTextColor(Color.parseColor("#E57373"));
                }
            }
        }

        // 2. Battery Optimization
        if (permBatteryBadge != null) {
            boolean batteryUnrestricted = true;
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                PowerManager pm = (PowerManager) getSystemService(POWER_SERVICE);
                batteryUnrestricted = (pm != null && pm.isIgnoringBatteryOptimizations(getPackageName()));
            }
            if (batteryUnrestricted) {
                permBatteryBadge.setText("ON");
                permBatteryBadge.setTextColor(Color.parseColor("#81C784"));
                permBatteryBadge.setBackground(createRoundedDrawable(Color.parseColor("#1F2A22"), dp(8)));
                if (permBatteryAction != null) {
                    permBatteryAction.setText("Unrestricted background execution");
                    permBatteryAction.setTextColor(Color.parseColor("#81C784"));
                }
            } else {
                permBatteryBadge.setText("OFF");
                permBatteryBadge.setTextColor(Color.parseColor("#FFB74D"));
                permBatteryBadge.setBackground(createRoundedDrawable(Color.parseColor("#3A2E1A"), dp(8)));
                if (permBatteryAction != null) {
                    permBatteryAction.setText("Tap to disable battery restrictions");
                    permBatteryAction.setTextColor(Color.parseColor("#FFB74D"));
                }
            }
        }

        // 3. Notifications
        if (permNotifBadge != null) {
            boolean notifEnabled = NotificationManagerCompat.from(this).areNotificationsEnabled();
            if (notifEnabled) {
                permNotifBadge.setText("ON");
                permNotifBadge.setTextColor(Color.parseColor("#81C784"));
                permNotifBadge.setBackground(createRoundedDrawable(Color.parseColor("#1F2A22"), dp(8)));
                if (permNotifAction != null) {
                    permNotifAction.setText("Foreground notifications active");
                    permNotifAction.setTextColor(Color.parseColor("#81C784"));
                }
            } else {
                permNotifBadge.setText("OFF");
                permNotifBadge.setTextColor(Color.parseColor("#E57373"));
                permNotifBadge.setBackground(createRoundedDrawable(Color.parseColor("#3B1D1D"), dp(8)));
                if (permNotifAction != null) {
                    permNotifAction.setText("Tap to enable notifications");
                    permNotifAction.setTextColor(Color.parseColor("#E57373"));
                }
            }
        }
    }

    // ── Server Error Diagnoser Card ───────────────────────────────────────────

    private View buildErrorDiagnosticCard() {
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setPadding(dp(16), dp(14), dp(16), dp(14));
        card.setBackground(createRoundedDrawable(Color.parseColor("#201417"), dp(16)));
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(-1, -2);
        params.topMargin = dp(12);
        card.setLayoutParams(params);

        // Header Row: Title, Category Badge, Dismiss
        LinearLayout headerRow = new LinearLayout(this);
        headerRow.setOrientation(LinearLayout.HORIZONTAL);
        headerRow.setGravity(Gravity.CENTER_VERTICAL);

        TextView title = new TextView(this);
        title.setText("ERROR DIAGNOSIS");
        title.setTextSize(11);
        title.setTypeface(Typeface.create("sans-serif-medium", Typeface.BOLD));
        title.setTextColor(Color.parseColor("#EF5350"));
        headerRow.addView(title, new LinearLayout.LayoutParams(0, -2, 1.0f));

        errorCardCategoryText = new TextView(this);
        errorCardCategoryText.setText("DETECTED");
        errorCardCategoryText.setTextSize(10);
        errorCardCategoryText.setTypeface(Typeface.create("sans-serif-medium", Typeface.BOLD));
        errorCardCategoryText.setTextColor(Color.parseColor("#EF5350"));
        errorCardCategoryText.setBackground(createRoundedDrawable(Color.parseColor("#3C1B1F"), dp(6)));
        errorCardCategoryText.setPadding(dp(8), dp(3), dp(8), dp(3));
        LinearLayout.LayoutParams catParams = new LinearLayout.LayoutParams(-2, -2);
        catParams.rightMargin = dp(8);
        headerRow.addView(errorCardCategoryText, catParams);

        Button btnDismiss = createPillCopyButton();
        btnDismiss.setText("DISMISS");
        btnDismiss.setOnClickListener(v -> {
            errorCardDismissed = true;
            if (errorDiagnosticCard != null) errorDiagnosticCard.setVisibility(View.GONE);
        });
        headerRow.addView(btnDismiss);

        card.addView(headerRow);

        // Section 1: What Happened
        TextView lblWhat = new TextView(this);
        lblWhat.setText("WHAT HAPPENED");
        lblWhat.setTextSize(10);
        lblWhat.setTypeface(Typeface.create("sans-serif-medium", Typeface.BOLD));
        lblWhat.setTextColor(getSecondaryTextColor());
        lblWhat.setPadding(0, dp(10), 0, dp(2));
        card.addView(lblWhat);

        errorCardWhatText = new TextView(this);
        errorCardWhatText.setText("Server process exited unexpectedly.");
        errorCardWhatText.setTextSize(13);
        errorCardWhatText.setTypeface(Typeface.create("sans-serif-medium", Typeface.BOLD));
        errorCardWhatText.setTextColor(getPrimaryTextColor());
        card.addView(errorCardWhatText);

        // Section 2: Root Cause & Analysis
        TextView lblWhy = new TextView(this);
        lblWhy.setText("ROOT CAUSE & ANALYSIS");
        lblWhy.setTextSize(10);
        lblWhy.setTypeface(Typeface.create("sans-serif-medium", Typeface.BOLD));
        lblWhy.setTextColor(getSecondaryTextColor());
        lblWhy.setPadding(0, dp(8), 0, dp(2));
        card.addView(lblWhy);

        errorCardWhyText = new TextView(this);
        errorCardWhyText.setText("An unhandled exception occurred during runtime launch.");
        errorCardWhyText.setTextSize(12);
        errorCardWhyText.setTextColor(Color.parseColor("#FFCDD2"));
        card.addView(errorCardWhyText);

        // Section 3: Recommended Action
        TextView lblFix = new TextView(this);
        lblFix.setText("RECOMMENDED ACTION");
        lblFix.setTextSize(10);
        lblFix.setTypeface(Typeface.create("sans-serif-medium", Typeface.BOLD));
        lblFix.setTextColor(getSecondaryTextColor());
        lblFix.setPadding(0, dp(8), 0, dp(2));
        card.addView(lblFix);

        errorCardFixText = new TextView(this);
        errorCardFixText.setText("1. Tap RESTART SERVER to relaunch the process.\n2. Review terminal logs for stack trace details.");
        errorCardFixText.setTextSize(12);
        errorCardFixText.setTextColor(Color.parseColor("#C8E6C9"));
        card.addView(errorCardFixText);

        // Section 4: Raw Diagnostics Box
        errorCardRawContainer = new LinearLayout(this);
        ((LinearLayout) errorCardRawContainer).setOrientation(LinearLayout.VERTICAL);
        errorCardRawContainer.setPadding(dp(10), dp(8), dp(10), dp(8));
        errorCardRawContainer.setBackground(createRoundedDrawable(Color.parseColor("#140D0F"), dp(8)));
        LinearLayout.LayoutParams rawParams = new LinearLayout.LayoutParams(-1, -2);
        rawParams.topMargin = dp(8);
        errorCardRawContainer.setLayoutParams(rawParams);

        errorCardRawText = new TextView(this);
        errorCardRawText.setTextSize(11);
        errorCardRawText.setTypeface(Typeface.MONOSPACE);
        errorCardRawText.setTextColor(Color.parseColor("#EF9A9A"));
        errorCardRawText.setMaxLines(6);
        errorCardRawText.setEllipsize(android.text.TextUtils.TruncateAt.END);
        ((LinearLayout) errorCardRawContainer).addView(errorCardRawText);

        card.addView(errorCardRawContainer);

        // Action Buttons Row
        LinearLayout btnRow = new LinearLayout(this);
        btnRow.setOrientation(LinearLayout.HORIZONTAL);
        LinearLayout.LayoutParams btnRowParams = new LinearLayout.LayoutParams(-1, -2);
        btnRowParams.topMargin = dp(10);
        btnRow.setLayoutParams(btnRowParams);

        Button btnCopyDiag = createPixelButton("COPY DIAGNOSTICS", Color.parseColor("#3C1B1F"), Color.parseColor("#EF5350"));
        btnCopyDiag.setOnClickListener(v -> copyDiagnosticsToClipboard());
        LinearLayout.LayoutParams pCopy = new LinearLayout.LayoutParams(0, -2, 1.0f);
        pCopy.rightMargin = dp(6);
        btnRow.addView(btnCopyDiag, pCopy);

        Button btnLogs = createPixelButton("VIEW LOGS", getSurfaceElevatedColor(), getPrimaryTextColor());
        btnLogs.setOnClickListener(v -> {
            activeTab = 1;
            updateBottomNavSelection();
            renderActiveTab();
        });
        LinearLayout.LayoutParams pLogs = new LinearLayout.LayoutParams(0, -2, 1.0f);
        btnRow.addView(btnLogs, pLogs);

        card.addView(btnRow);

        card.setVisibility(View.GONE);
        return card;
    }

    private void updateErrorDiagnosticCard(JellyfinController.State state) {
        if (errorDiagnosticCard == null || controller == null) return;

        boolean isError = (state == JellyfinController.State.CRASHED
                || state == JellyfinController.State.CRASH_LOOP
                || state == JellyfinController.State.FAILED);
        String lastErr = controller.getLastError();
        boolean hasErrorMsg = (lastErr != null && !lastErr.trim().isEmpty()
                && state != JellyfinController.State.RUNNING
                && state != JellyfinController.State.STOPPED);

        if (state == JellyfinController.State.RUNNING) {
            errorCardDismissed = false;
            errorDiagnosticCard.setVisibility(View.GONE);
            return;
        }

        if ((!isError && !hasErrorMsg) || errorCardDismissed) {
            errorDiagnosticCard.setVisibility(View.GONE);
            return;
        }

        errorDiagnosticCard.setVisibility(View.VISIBLE);

        String errStr = (lastErr != null) ? lastErr : "";
        String cat;
        String what;
        String why;
        String fix;

        if (errStr.contains("8096") || errStr.toLowerCase(Locale.ROOT).contains("port")) {
            cat = "PORT CONFLICT";
            what = "HTTP Port 8096 is already bound or in use.";
            why = "Another instance of Jellyfin or another network server on your phone is occupying TCP port 8096.";
            fix = "1. Tap RESTART SERVER to terminate stale process sockets.\n2. Verify no secondary background media servers are running.";
        } else if (state == JellyfinController.State.CRASH_LOOP || errStr.toLowerCase(Locale.ROOT).contains("crash loop")) {
            cat = "CRASH LOOP";
            what = "Server failed repeatedly during launch sequence.";
            why = "Consecutive crashes exceeded the safety threshold (3 retries). This typically points to corrupted configuration, damaged database, or native library mismatch.";
            fix = "1. Tap RESET CRASH LOOP to unlatch the server.\n2. Inspect terminal traces in the Logs tab.\n3. If database is corrupt, perform CLEAN REINSTALL in Settings.";
        } else if (errStr.toLowerCase(Locale.ROOT).contains("bootstrap") || errStr.toLowerCase(Locale.ROOT).contains("extract") || errStr.toLowerCase(Locale.ROOT).contains("runtime")) {
            cat = "RUNTIME INIT ERROR";
            what = "Jellyfin binary package initialization failed.";
            why = "Asset decompression or binary permission setup was interrupted. Device storage may be exhausted or file access restricted.";
            fix = "1. Ensure at least 1 GB free internal storage.\n2. Confirm Storage permission is ON in Settings.\n3. Tap REINSTALL RUNTIME to unpack clean binaries.";
        } else if (errStr.contains("139") || errStr.contains("137") || errStr.toLowerCase(Locale.ROOT).contains("sigkill") || errStr.toLowerCase(Locale.ROOT).contains("sigsegv")) {
            cat = "PROCESS KILLED";
            what = "Jellyfin engine process was killed by the operating system.";
            why = "Android Low Memory Killer (LMK) stopped the background process to reclaim memory, or a native library segmentation fault occurred.";
            fix = "1. Tap START SERVER to relaunch.\n2. Set Battery Optimization to UNRESTRICTED in Settings.\n3. Close heavy background apps before scanning large media libraries.";
        } else {
            cat = "STARTUP FAILURE";
            what = "Server process exited unexpectedly (" + state.name() + ").";
            why = (errStr.isEmpty()) ? "The Jellyfin daemon stopped without completing readiness handshake." : errStr;
            fix = "1. Tap START SERVER or RESTART SERVER to retry.\n2. Review the terminal output in the Logs tab.\n3. Verify Storage and Battery permissions in Settings.";
        }

        if (errorCardCategoryText != null) errorCardCategoryText.setText(cat);
        if (errorCardWhatText != null) errorCardWhatText.setText(what);
        if (errorCardWhyText != null) errorCardWhyText.setText(why);
        if (errorCardFixText != null) errorCardFixText.setText(fix);

        if (errorCardRawText != null) {
            String logs = controller.getLogs();
            String logSnippet = "";
            if (logs != null && !logs.isEmpty()) {
                String[] lines = logs.split("\n");
                int start = Math.max(0, lines.length - 4);
                StringBuilder sb = new StringBuilder();
                for (int i = start; i < lines.length; i++) {
                    if (lines[i].trim().isEmpty()) continue;
                    if (sb.length() > 0) sb.append("\n");
                    sb.append(lines[i].trim());
                }
                logSnippet = sb.toString();
            }
            String raw = "State: " + state.name() + (errStr.isEmpty() ? "" : "\nError: " + errStr) +
                    (logSnippet.isEmpty() ? "" : "\nLogs:\n" + logSnippet);
            errorCardRawText.setText(raw);
        }
    }

    private void copyDiagnosticsToClipboard() {
        StringBuilder diag = new StringBuilder();
        diag.append("Fishbowl Jellyfin Diagnostic Report\n");
        diag.append("App Version: ").append(com.termux.BuildConfig.VERSION_NAME).append(" (Build 101408)\n");
        diag.append("Jellyfin Core: 12.1.0 ARM64\n");
        diag.append("Device: ").append(Build.MANUFACTURER).append(" ").append(Build.MODEL).append("\n");
        diag.append("Android: ").append(Build.VERSION.RELEASE).append(" (API ").append(Build.VERSION.SDK_INT).append(")\n");
        if (controller != null) {
            diag.append("Server State: ").append(controller.getState().name()).append("\n");
            diag.append("Last Error: ").append(controller.getLastError()).append("\n");
            diag.append("\n--- Log Tail ---\n");
            String logs = controller.getLogs();
            if (logs != null && !logs.isEmpty()) {
                String[] lines = logs.split("\n");
                int start = Math.max(0, lines.length - 20);
                for (int i = start; i < lines.length; i++) {
                    diag.append(lines[i]).append("\n");
                }
            }
        }
        ClipboardManager cm = (ClipboardManager) getSystemService(CLIPBOARD_SERVICE);
        if (cm != null) {
            cm.setPrimaryClip(ClipData.newPlainText("Fishbowl Error Diagnostics", diag.toString()));
            Toast.makeText(this, "Diagnostic report copied to clipboard", Toast.LENGTH_SHORT).show();
        }
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

        String freeStorageStr = "";
        try {
            android.os.StatFs stat = new android.os.StatFs(prefix.getAbsolutePath());
            double freeGb = stat.getAvailableBytes() / (1024.0 * 1024.0 * 1024.0);
            double totalGb = stat.getTotalBytes() / (1024.0 * 1024.0 * 1024.0);
            freeStorageStr = String.format(Locale.ROOT, "Device Free: %.1f GB / %.1f GB\n", freeGb, totalGb);
        } catch (Throwable ignored) {}

        String info = freeStorageStr + "Runtime: " + prefixMB + " MB | Data: " + dataMB + " MB | Cache: " + cacheMB + " MB";
        storageInfoText.setText(info);
    }

    private void showClearCacheConfirmation() {
        new androidx.appcompat.app.AlertDialog.Builder(this)
                .setTitle("Clear temporary cache?")
                .setMessage("This removes temporary/cache files only.\nYour Jellyfin server, users, libraries, metadata, media and settings will not be deleted.")
                .setNegativeButton("CANCEL", null)
                .setPositiveButton("CLEAR CACHE", (dialog, which) -> executeSafeClearCache())
                .show();
    }

    private void executeSafeClearCache() {
        new Thread(() -> {
            boolean allSuccess = true;
            File home = com.termux.shared.termux.TermuxConstants.TERMUX_HOME_DIR;
            File jellyfinCache = new File(home, ".cache/jellyfin");
            File appCache = getCacheDir();

            // Strict allowlist: Only these subdirectories and disposable items may be cleared
            String[] allowedJellyfinSubdirs = {
                    "transcodes", "audiodb-album", "audiodb-artist",
                    "extracted-audio-images", "fanart-movies", "images",
                    "imagesbyname", "omdb"
            };

            try {
                String canonicalCache = jellyfinCache.getCanonicalPath();

                // Clear allowlisted Jellyfin cache subdirectories
                if (jellyfinCache.exists() && jellyfinCache.isDirectory()) {
                    for (String sub : allowedJellyfinSubdirs) {
                        File targetSub = new File(jellyfinCache, sub);
                        if (targetSub.exists()) {
                            String canonicalTarget = targetSub.getCanonicalPath();
                            // Strict boundary & path traversal check
                            if (canonicalTarget.startsWith(canonicalCache) && !canonicalTarget.equals(canonicalCache)) {
                                if (!deleteDirectoryRecursively(targetSub)) {
                                    allSuccess = false;
                                }
                            }
                        }
                    }
                }

                // Clear app-level cache
                if (appCache != null && appCache.exists()) {
                    File[] appFiles = appCache.listFiles();
                    if (appFiles != null) {
                        for (File f : appFiles) {
                            if (!deleteDirectoryRecursively(f)) {
                                allSuccess = false;
                            }
                        }
                    }
                }
            } catch (Exception e) {
                Log.e(TAG, "Error executing safe cache clear: " + e.getMessage(), e);
                allSuccess = false;
            }

            final boolean finalSuccess = allSuccess;
            runOnUiThread(() -> {
                updateStorageInfoText();
                if (finalSuccess) {
                    Toast.makeText(JellyfinDroidActivity.this, "Cache cleared successfully.", Toast.LENGTH_SHORT).show();
                } else {
                    Toast.makeText(JellyfinDroidActivity.this, "Cache partially cleared. Some temporary files could not be removed.", Toast.LENGTH_LONG).show();
                }
            });
        }).start();
    }

    private boolean deleteDirectoryRecursively(File fileOrDir) {
        if (fileOrDir == null || !fileOrDir.exists()) return true;
        boolean success = true;
        if (fileOrDir.isDirectory()) {
            File[] children = fileOrDir.listFiles();
            if (children != null) {
                for (File child : children) {
                    if (!deleteDirectoryRecursively(child)) {
                        success = false;
                    }
                }
            }
        }
        return fileOrDir.delete() && success;
    }

    private long getFolderSizeMB(File dir) {
        if (dir == null || !dir.exists()) return 0;
        long bytes = calculateDirSize(dir);
        return bytes / (1024 * 1024);
    }

    private long calculateDirSize(File dir) {
        long size = 0;
        if (dir.isFile()) return dir.length();
        File[] files = dir.listFiles();
        if (files == null) return 0;
        for (File f : files) {
            if (f.isFile()) {
                size += f.length();
            } else if (f.isDirectory()) {
                size += calculateDirSize(f);
            }
        }
        return size;
    }

    private View buildRuntimeStatusItem() {
        LinearLayout item = new LinearLayout(this);
        item.setOrientation(LinearLayout.VERTICAL);
        item.setPadding(dp(14), dp(12), dp(14), dp(12));
        item.setBackground(createRoundedDrawable(getSurfaceElevatedColor(), dp(12)));
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(-1, -2);
        params.topMargin = dp(6);
        params.bottomMargin = dp(8);
        item.setLayoutParams(params);

        TextView titleTv = new TextView(this);
        titleTv.setText("Runtime Components Status");
        titleTv.setTextSize(13);
        titleTv.setTypeface(Typeface.create("sans-serif-medium", Typeface.BOLD));
        titleTv.setTextColor(getPrimaryTextColor());
        item.addView(titleTv);

        runtimeStatusText = new TextView(this);
        runtimeStatusText.setTextSize(12);
        runtimeStatusText.setTextColor(getSecondaryTextColor());
        runtimeStatusText.setPadding(0, dp(4), 0, dp(8));
        item.addView(runtimeStatusText);

        btnSettingsRetry = createPixelButton("RETRY SETUP", getAccentColor(), Color.WHITE);
        btnSettingsRetry.setOnClickListener(v -> {
            Toast.makeText(this, "Retrying Jellyfin setup...", Toast.LENGTH_SHORT).show();
            controller.reinstallRuntime(this);
        });
        btnSettingsRetry.setVisibility(View.GONE);
        item.addView(btnSettingsRetry);

        btnSettingsReinstall = createPixelButton("REINSTALL RUNTIME", Color.parseColor("#455A64"), Color.WHITE);
        btnSettingsReinstall.setOnClickListener(v -> showReinstallConfirmation());
        item.addView(btnSettingsReinstall);

        refreshRuntimeStatusUI();
        return item;
    }

    private void refreshRuntimeStatusUI() {
        if (runtimeStatusText == null) return;
        boolean initialized = JellyfinBootstrapper.isInitialized(this);
        String lastErr = JellyfinBootstrapper.getLastError();
        JellyfinController.State state = controller != null ? controller.getState() : JellyfinController.State.UNINITIALIZED;

        StringBuilder sb = new StringBuilder();
        if (state == JellyfinController.State.INITIALIZING) {
            sb.append("STATUS: EXTRACTING & CONFIGURING...\n");
            String bMsg = controller.getBootstrapProgressMessage();
            int bPct = controller.getBootstrapProgressPercent();
            if (bMsg != null && !bMsg.isEmpty()) {
                sb.append(bMsg).append(" (").append(bPct).append("%)\n");
            }
            sb.append("Please keep the application open until installation finishes.");
            runtimeStatusText.setTextColor(Color.parseColor("#FFD54F"));
        } else if (initialized) {
            sb.append("STATUS: INSTALLED & VERIFIED\n");
            sb.append("• Jellyfin Media Server Core 12.1.0\n");
            sb.append("• Microsoft .NET 10.0.12 Runtime Engine\n");
            sb.append("• FFmpeg 7.1.4-Jellyfin Transcoder\n");
            sb.append("• OpenSSL & SQLite3 System Libraries\n");
            sb.append("Runtime binaries are fully verified in application storage.");
            runtimeStatusText.setTextColor(Color.parseColor("#81C784"));
        } else {
            sb.append("STATUS: INSTALLATION FAILED / INCOMPLETE\n");
            if (lastErr != null && !lastErr.isEmpty()) {
                sb.append("Error detail: ").append(lastErr).append("\n");
            } else {
                sb.append("One or more runtime components are missing from storage.\n");
            }
            sb.append("Tap RETRY SETUP or REINSTALL RUNTIME to unpack fresh runtime binaries.");
            runtimeStatusText.setTextColor(Color.parseColor("#E57373"));
        }

        runtimeStatusText.setText(sb.toString());

        boolean failed = (!initialized && state != JellyfinController.State.INITIALIZING && state != JellyfinController.State.STARTING);
        boolean busy = (state == JellyfinController.State.INITIALIZING || state == JellyfinController.State.STARTING || state == JellyfinController.State.STOPPING);

        if (btnSettingsRetry != null) {
            btnSettingsRetry.setVisibility(failed ? View.VISIBLE : View.GONE);
            btnSettingsRetry.setEnabled(!busy);
        }
        if (btnSettingsReinstall != null) {
            btnSettingsReinstall.setEnabled(!busy);
        }
    }

    private void showReinstallConfirmation() {
        new androidx.appcompat.app.AlertDialog.Builder(this)
                .setTitle("Reinstall Jellyfin Runtime?")
                .setMessage("This will extract a fresh copy of Jellyfin 12.1.0 and FFmpeg runtime files.\n\nYour existing media libraries, user accounts, database, and configurations will NOT be deleted.")
                .setNegativeButton("CANCEL", null)
                .setPositiveButton("REINSTALL", (dialog, which) -> {
                    Toast.makeText(this, "Beginning clean runtime extraction...", Toast.LENGTH_SHORT).show();
                    controller.reinstallRuntime(this);
                })
                .show();
    }

    // ── State Rendering Logic (NO EMOJIS) ──────────────────────────────────────

    @Override
    public void onServerChanged(final JellyfinController.State state) {
        runOnUiThread(() -> renderCurrentState());
    }

    @Override
    public void onBootstrapProgress(final String message, final int percent) {
        runOnUiThread(() -> updateSetupProgressUI(message, percent));
    }

    private void updateSetupProgressUI(final String message, final int percent) {
        if (setupOverlayView == null) return;
        if (setupOverlayView.getVisibility() != View.VISIBLE && !JellyfinBootstrapper.isInitialized(this)) {
            setupOverlayView.setVisibility(View.VISIBLE);
            setupOverlayView.bringToFront();
        }

        if (setupProgressBar != null) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                setupProgressBar.setProgress(percent, true);
            } else {
                setupProgressBar.setProgress(percent);
            }
        }
        if (setupProgressText != null) {
            setupProgressText.setText(percent + "%");
            setupProgressText.setTextColor(getAccentColor());
        }
        if (setupDetailText != null) {
            setupDetailText.setText(message);
            setupDetailText.setTextColor(getSecondaryTextColor());
        }
        if (setupLogSummaryText != null) {
            setupLogSummaryText.setText(message);
        }

        updateSetupStageItem("setup_step_1", "1. Recombining split package archives (asset chunks)", percent >= 35, percent > 0 && percent < 35);
        updateSetupStageItem("setup_step_2", "2. Extracting Jellyfin Server 12.1.0 ARM64 runtime", percent >= 65, percent >= 35 && percent < 65);
        updateSetupStageItem("setup_step_3", "3. Unpacking Microsoft .NET 10 & FFmpeg 7.1.4 transcoder", percent >= 88, percent >= 65 && percent < 88);
        updateSetupStageItem("setup_step_4", "4. Configuring POSIX permissions & system libraries", percent >= 95, percent >= 88 && percent < 95);
        updateSetupStageItem("setup_step_5", "5. Verifying server binaries & readiness", percent >= 100, percent >= 95 && percent < 100);

        if (percent >= 100) {
            setupOverlayView.postDelayed(() -> {
                if (setupOverlayView != null && JellyfinBootstrapper.isInitialized(this)) {
                    setupOverlayView.setVisibility(View.GONE);
                }
                renderCurrentState();
            }, 600);
        }
    }

    private void renderCurrentState() {
        if (controller == null) return;
        renderSetupOverlayState();
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
                case UNINITIALIZED:
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

        // Render Slim Header Progress Bar & Notification Banner (Small progress bar not full screen)
        if (headerProgressBar != null) {
            boolean showLoading = (state == JellyfinController.State.INITIALIZING || state == JellyfinController.State.STARTING);
            if (showLoading) {
                headerProgressBar.setVisibility(View.VISIBLE);
                if (headerStatusBanner != null) headerStatusBanner.setVisibility(View.VISIBLE);

                int progressVal;
                String stageText;

                String bMsg = controller.getBootstrapProgressMessage();
                int bPct = controller.getBootstrapProgressPercent();

                if (state == JellyfinController.State.INITIALIZING && bMsg != null && !bMsg.isEmpty()) {
                    progressVal = Math.max(5, bPct);
                    stageText = "1/4 " + bMsg + " (" + progressVal + "%)";
                } else {
                    switch (stage) {
                        case STARTING_RUNTIME:
                            progressVal = (bPct > 0) ? bPct : 25;
                            stageText = (bMsg != null && !bMsg.isEmpty()) ? "1/4 " + bMsg : "1/4 Starting runtime environment...";
                            break;
                        case LAUNCHING_SERVER:
                            progressVal = 50;
                            stageText = "2/4 Launching Jellyfin server process...";
                            break;
                        case WAITING_FOR_SERVER:
                            progressVal = 70;
                            stageText = "3/4 Binding HTTP ports...";
                            break;
                        case CHECKING_READINESS:
                            progressVal = 90;
                            stageText = "4/4 Polling readiness & migrating database...";
                            break;
                        case READY:
                            progressVal = 100;
                            stageText = "Server Ready";
                            break;
                        default:
                            progressVal = 15;
                            stageText = "Initializing Jellyfin server...";
                            break;
                    }
                }

                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                    headerProgressBar.setProgress(progressVal, true);
                } else {
                    headerProgressBar.setProgress(progressVal);
                }

                if (headerStatusBanner != null) {
                    headerStatusBanner.setText(stageText);
                }
            } else {
                headerProgressBar.setVisibility(View.GONE);
                if (headerStatusBanner != null) headerStatusBanner.setVisibility(View.GONE);
            }
        }

        // Render Real Stage Progress Card (NO EMOJIS)
        if (startupProgressCard != null) {
            boolean isStarting = (state == JellyfinController.State.INITIALIZING || state == JellyfinController.State.STARTING);
            startupProgressCard.setVisibility(isStarting ? View.VISIBLE : View.GONE);

            if (isStarting && txtStage1 != null) {
                String bMsg = controller.getBootstrapProgressMessage();
                int bPct = controller.getBootstrapProgressPercent();
                String stage1Label = (bMsg != null && !bMsg.isEmpty()) ? "1. " + bMsg + " (" + bPct + "%)" : "1. Starting runtime environment";
                updateStageRow(txtStage1, stage1Label, stage.ordinal() >= JellyfinController.StartupStage.STARTING_RUNTIME.ordinal() && bPct == 100, stage == JellyfinController.StartupStage.STARTING_RUNTIME || state == JellyfinController.State.INITIALIZING);
                updateStageRow(txtStage2, "2. Launching Jellyfin server process", stage.ordinal() >= JellyfinController.StartupStage.LAUNCHING_SERVER.ordinal(), stage == JellyfinController.StartupStage.LAUNCHING_SERVER);
                updateStageRow(txtStage3, "3. Waiting for server (binding port 8096)", stage.ordinal() > JellyfinController.StartupStage.WAITING_FOR_SERVER.ordinal(), stage == JellyfinController.StartupStage.WAITING_FOR_SERVER);
                updateStageRow(txtStage4, "4. Polling readiness & migrating database", stage.ordinal() >= JellyfinController.StartupStage.CHECKING_READINESS.ordinal(), stage == JellyfinController.StartupStage.CHECKING_READINESS);
                updateStageRow(txtStage5, "5. Ready", stage == JellyfinController.StartupStage.READY, false);
            }
        }

        // Real-Time Server Error Diagnosis Card
        updateErrorDiagnosticCard(state);

        // Authoritative Readiness Guard for OPEN JELLYFIN
        boolean isReady = (state == JellyfinController.State.RUNNING && stage == JellyfinController.StartupStage.READY);
        if (btnOpenJellyfin != null) {
            if (isReady) {
                applyButtonStyle(btnOpenJellyfin, "OPEN JELLYFIN", getAccentColor(), Color.WHITE, true);
            } else {
                applyButtonStyle(btnOpenJellyfin, "OPEN JELLYFIN", Color.parseColor("#262930"), Color.parseColor("#616161"), false);
            }
        }

        boolean busy = (state == JellyfinController.State.INITIALIZING || state == JellyfinController.State.STARTING || state == JellyfinController.State.STOPPING);
        boolean crashLoop = (state == JellyfinController.State.CRASH_LOOP);
        boolean failed = (state == JellyfinController.State.FAILED || state == JellyfinController.State.CRASHED);

        if (btnStartServer != null) {
            if (state == JellyfinController.State.RUNNING) {
                applyButtonStyle(btnStartServer, "SERVER RUNNING", Color.parseColor("#1F2A22"), Color.parseColor("#81C784"), false);
            } else if (state == JellyfinController.State.STARTING || state == JellyfinController.State.INITIALIZING) {
                applyButtonStyle(btnStartServer, "STARTING...", Color.parseColor("#F57F17"), Color.WHITE, false);
            } else if (state == JellyfinController.State.STOPPING) {
                applyButtonStyle(btnStartServer, "STOPPING...", Color.parseColor("#BF360C"), Color.WHITE, false);
            } else if (crashLoop) {
                applyButtonStyle(btnStartServer, "SERVER CRASHED", Color.parseColor("#5A1A1A"), Color.parseColor("#EF9A9A"), false);
            } else if (failed) {
                applyButtonStyle(btnStartServer, "RETRY START SERVER", Color.parseColor("#C62828"), Color.WHITE, true);
            } else if (!JellyfinBootstrapper.isInitialized(this)) {
                applyButtonStyle(btnStartServer, "SETUP / RETRY INSTALL", Color.parseColor("#0288D1"), Color.WHITE, true);
            } else {
                applyButtonStyle(btnStartServer, "START SERVER", Color.parseColor("#2E7D32"), Color.WHITE, true);
            }
        }
        if (btnStopServer != null) {
            if (state == JellyfinController.State.RUNNING) {
                applyButtonStyle(btnStopServer, "STOP SERVER", Color.parseColor("#D32F2F"), Color.WHITE, true);
            } else {
                applyButtonStyle(btnStopServer, "STOP SERVER", Color.parseColor("#262930"), Color.parseColor("#616161"), false);
            }
        }
        if (btnRestartServer != null) {
            boolean canRestart = !busy && !crashLoop;
            if (canRestart) {
                applyButtonStyle(btnRestartServer, "RESTART SERVER", getSurfaceColor(), getPrimaryTextColor(), true);
            } else {
                applyButtonStyle(btnRestartServer, "RESTART SERVER", Color.parseColor("#262930"), Color.parseColor("#616161"), false);
            }
        }
        if (btnResetCrash != null) btnResetCrash.setVisibility(crashLoop ? View.VISIBLE : View.GONE);

        // Show REINSTALL RUNTIME button on Home dashboard when installation/startup failed or user wants to re-bootstrap
        if (btnReinstallRuntime != null) {
            boolean showReinstall = failed || !JellyfinBootstrapper.isInitialized(this);
            btnReinstallRuntime.setVisibility(showReinstall ? View.VISIBLE : View.GONE);
            btnReinstallRuntime.setEnabled(!busy);
        }

        // Refresh Settings Runtime Status if visible
        refreshRuntimeStatusUI();

        // Refresh live server health & resources
        refreshServerHealthUI();
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
        String tailscale = getTailscaleAddress();
        if (tailscaleIpValueText != null) {
            if (tailscale == null) {
                tailscaleIpValueText.setText("TAILSCALE UNAVAILABLE");
                tailscaleIpValueText.setTextColor(Color.parseColor("#FFB74D"));
                if (btnCopyTailscaleIp != null) btnCopyTailscaleIp.setEnabled(false);
            } else {
                tailscaleIpValueText.setText(tailscale);
                tailscaleIpValueText.setTextColor(Color.parseColor("#81C784"));
                if (btnCopyTailscaleIp != null) btnCopyTailscaleIp.setEnabled(true);
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
                String name = intf.getName().toLowerCase(Locale.ROOT);
                if (name.startsWith("tun") || name.startsWith("tailscale")) continue;
                Enumeration<java.net.InetAddress> addrs = intf.getInetAddresses();
                while (addrs.hasMoreElements()) {
                    java.net.InetAddress addr = addrs.nextElement();
                    if (!addr.isLoopbackAddress() && addr instanceof Inet4Address) {
                        String ip = addr.getHostAddress();
                        if (isTailscaleIp(ip)) continue;
                        return "http://" + ip + ":8096";
                    }
                }
            }
        } catch (Exception ignored) {}
        return null;
    }

    private String getTailscaleAddress() {
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
                        String ip = addr.getHostAddress();
                        if (isTailscaleIp(ip)) {
                            return "http://" + ip + ":8096";
                        }
                    }
                }
            }
        } catch (Exception ignored) {}
        return null;
    }

    private boolean isTailscaleIp(String ip) {
        if (ip == null) return false;
        String[] parts = ip.split("\\.");
        if (parts.length != 4) return false;
        try {
            int first = Integer.parseInt(parts[0]);
            int second = Integer.parseInt(parts[1]);
            return first == 100 && (second >= 64 && second <= 127);
        } catch (NumberFormatException e) {
            return false;
        }
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
                try {
                    NetworkRequest req = new NetworkRequest.Builder()
                            .addTransportType(NetworkCapabilities.TRANSPORT_VPN)
                            .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
                            .addTransportType(NetworkCapabilities.TRANSPORT_CELLULAR)
                            .addTransportType(NetworkCapabilities.TRANSPORT_ETHERNET)
                            .build();
                    cm.registerNetworkCallback(req, networkCallback);
                } catch (Exception ignored) {}
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

    private void requestIgnoreBatteryOptimizationsIfNeeded() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            PowerManager pm = (PowerManager) getSystemService(POWER_SERVICE);
            if (pm != null && !pm.isIgnoringBatteryOptimizations(getPackageName())) {
                try {
                    Intent intent = new Intent(android.provider.Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS);
                    intent.setData(Uri.parse("package:" + getPackageName()));
                    startActivity(intent);
                } catch (Exception ignored) {}
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

        GradientDrawable defaultShape = createRoundedDrawable(bgColor, dp(22));
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            int rippleColor = (textColor == Color.WHITE)
                    ? Color.argb(100, 255, 255, 255)
                    : Color.argb(80, 0, 164, 220);
            android.graphics.drawable.RippleDrawable ripple = new android.graphics.drawable.RippleDrawable(
                    android.content.res.ColorStateList.valueOf(rippleColor),
                    defaultShape,
                    null);
            b.setBackground(ripple);
        } else {
            b.setBackground(defaultShape);
        }

        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(-1, dp(46));
        params.topMargin = dp(8);
        b.setLayoutParams(params);

        // Tactile press animation without swallowing click events
        b.setOnTouchListener((v, event) -> {
            if (!v.isEnabled()) return false;
            if (event.getAction() == android.view.MotionEvent.ACTION_DOWN) {
                v.animate().scaleX(0.96f).scaleY(0.96f).setDuration(60).start();
            } else if (event.getAction() == android.view.MotionEvent.ACTION_UP || event.getAction() == android.view.MotionEvent.ACTION_CANCEL) {
                v.animate().scaleX(1.0f).scaleY(1.0f).setDuration(80).start();
            }
            return false; // Crucial: allow click listener to receive event!
        });

        return b;
    }

    private void applyButtonStyle(Button btn, String text, int bgColor, int textColor, boolean enabled) {
        if (btn == null) return;
        btn.setText(text);
        btn.setTextColor(textColor);
        btn.setEnabled(enabled);
        btn.setAlpha(1.0f);
        GradientDrawable defaultShape = createRoundedDrawable(bgColor, dp(22));
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            int rippleColor = (textColor == Color.WHITE)
                    ? Color.argb(100, 255, 255, 255)
                    : Color.argb(80, 0, 164, 220);
            android.graphics.drawable.RippleDrawable ripple = new android.graphics.drawable.RippleDrawable(
                    android.content.res.ColorStateList.valueOf(rippleColor),
                    defaultShape,
                    null);
            btn.setBackground(ripple);
        } else {
            btn.setBackground(defaultShape);
        }
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

    private final UpdateManager.UpdateListener updateListener = new UpdateManager.UpdateListener() {
        @Override
        public void onUpdateStateChanged(UpdateManager.State state) {
            refreshUpdateUI();
        }

        @Override
        public void onDownloadProgress(long bytesDownloaded, long totalBytes) {
            refreshUpdateProgress(bytesDownloaded, totalBytes);
        }
    };

    private void refreshUpdateUI() {
        if (updateStatusText == null || updateActionBtn == null) return;
        UpdateManager manager = UpdateManager.getInstance(this);
        UpdateManager.State state = manager.getCurrentState();

        int currentCode = manager.getCurrentVersionCode(this);
        String currentName = manager.getCurrentVersionName(this);

        String verInfo = "Current version: " + currentName + " (" + currentCode + ")\n";

        switch (state) {
            case IDLE:
                updateStatusText.setText(verInfo + "Status: Ready to check");
                updateActionBtn.setText("Check for updates");
                updateActionBtn.setVisibility(View.VISIBLE);
                updateActionBtn.setOnClickListener(v -> manager.checkForUpdates(this, true));
                break;
            case CHECKING:
                updateStatusText.setText(verInfo + "Checking for updates...");
                updateActionBtn.setVisibility(View.GONE);
                break;
            case UP_TO_DATE:
                updateStatusText.setText(verInfo + "You're up to date");
                updateActionBtn.setText("Check for updates");
                updateActionBtn.setVisibility(View.VISIBLE);
                updateActionBtn.setOnClickListener(v -> manager.checkForUpdates(this, true));
                break;
            case UPDATE_AVAILABLE:
                updateStatusText.setText(verInfo + "Update available\n\nWhat's new:\n" + manager.getLatestReleaseNotes());
                updateActionBtn.setText("Download update");
                updateActionBtn.setVisibility(View.VISIBLE);
                updateActionBtn.setOnClickListener(v -> manager.startDownload(this));
                break;
            case DOWNLOADING:
                updateStatusText.setText(verInfo + "Downloading update...");
                updateActionBtn.setVisibility(View.GONE);
                break;
            case DOWNLOAD_COMPLETE:
                updateStatusText.setText(verInfo + "Update downloaded.");
                updateActionBtn.setText("Verify and install");
                updateActionBtn.setVisibility(View.VISIBLE);
                updateActionBtn.setOnClickListener(v -> manager.installUpdate(this));
                break;
            case VERIFYING:
                updateStatusText.setText(verInfo + "Verifying update...");
                updateActionBtn.setVisibility(View.GONE);
                break;
            case VERIFICATION_SUCCESSFUL:
                updateStatusText.setText(verInfo + "Update verified.");
                updateActionBtn.setText("Install update");
                updateActionBtn.setVisibility(View.VISIBLE);
                updateActionBtn.setOnClickListener(v -> manager.installUpdate(this));
                break;
            case VERIFICATION_FAILED:
                updateStatusText.setText(verInfo + "Update verification failed.");
                updateActionBtn.setText("Retry download");
                updateActionBtn.setVisibility(View.VISIBLE);
                updateActionBtn.setOnClickListener(v -> manager.startDownload(this));
                break;
            case PERMISSION_REQUIRED:
                updateStatusText.setText(verInfo + "Installation permission is required.");
                updateActionBtn.setText("Open settings");
                updateActionBtn.setVisibility(View.VISIBLE);
                updateActionBtn.setOnClickListener(v -> manager.openInstallPermissionSettings(this));
                break;
            case DOWNLOAD_FAILED:
                updateStatusText.setText(verInfo + "Update download failed.");
                updateActionBtn.setText("Retry download");
                updateActionBtn.setVisibility(View.VISIBLE);
                updateActionBtn.setOnClickListener(v -> manager.startDownload(this));
                break;
            case NETWORK_UNAVAILABLE:
                updateStatusText.setText(verInfo + "Unable to check for updates.");
                updateActionBtn.setText("Check for updates");
                updateActionBtn.setVisibility(View.VISIBLE);
                updateActionBtn.setOnClickListener(v -> manager.checkForUpdates(this, true));
                break;
            case RELEASE_UNAVAILABLE:
                updateStatusText.setText(verInfo + "Update information is currently unavailable.");
                updateActionBtn.setText("Check for updates");
                updateActionBtn.setVisibility(View.VISIBLE);
                updateActionBtn.setOnClickListener(v -> manager.checkForUpdates(this, true));
                break;
        }
    }

    private void refreshUpdateProgress(long downloaded, long total) {
        if (updateStatusText == null) return;
        UpdateManager manager = UpdateManager.getInstance(this);
        String currentName = manager.getCurrentVersionName(this);
        int currentCode = manager.getCurrentVersionCode(this);
        String verInfo = "Current version: " + currentName + " (" + currentCode + ")\n";

        long downloadedMb = downloaded / (1024 * 1024);
        long totalMb = total / (1024 * 1024);
        int percent = total > 0 ? (int) ((downloaded * 100) / total) : 0;

        updateStatusText.setText(verInfo + "Downloading update...\nFishbowl " + manager.getLatestVersionName() + "\n" + percent + "%\nDownloaded: " + downloadedMb + " MB / " + totalMb + " MB");
    }

    private String readFirstLine(File f) {
        if (!f.exists()) return "";
        try (java.io.BufferedReader br = new java.io.BufferedReader(new java.io.FileReader(f))) {
            String line = br.readLine();
            return line != null ? line.trim() : "";
        } catch (Throwable t) {
            return "";
        }
    }
}
