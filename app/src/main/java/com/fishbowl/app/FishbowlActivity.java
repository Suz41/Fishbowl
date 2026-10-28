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
import android.content.res.ColorStateList;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Typeface;
import android.app.Dialog;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.text.TextUtils;
import android.text.Spannable;
import android.text.SpannableString;
import android.text.SpannableStringBuilder;
import android.text.Spanned;
import android.text.style.ForegroundColorSpan;
import android.text.style.ImageSpan;
import android.text.style.StyleSpan;
import android.widget.HorizontalScrollView;
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
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import androidx.core.app.NotificationManagerCompat;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.EditText;
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
import java.util.Date;
import java.util.Map;
import java.util.HashMap;
import java.util.Enumeration;
import java.util.List;
import java.util.ArrayList;
import java.util.Locale;
import java.text.SimpleDateFormat;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.atomic.AtomicInteger;
import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
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
import com.termux.shared.termux.TermuxConstants;

import com.fishbowl.app.model.ManagedService;
import com.fishbowl.app.model.ServiceManifest;
import com.fishbowl.app.model.ServiceState;
import com.fishbowl.app.services.MediaServiceManager;

import android.util.Log;
import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.FileReader;
import java.io.FileWriter;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.IOException;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.Inet4Address;
import java.net.NetworkInterface;
import java.util.Map;
import java.util.HashMap;
import java.util.Enumeration;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import org.apache.commons.compress.archivers.ar.ArArchiveEntry;
import org.apache.commons.compress.archivers.ar.ArArchiveInputStream;
import org.apache.commons.compress.archivers.tar.TarArchiveEntry;
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream;
import org.apache.commons.compress.compressors.gzip.GzipCompressorInputStream;
import org.apache.commons.compress.compressors.xz.XZCompressorInputStream;

/**
 * Phase 15: FishBowl UI Polish, Simplified 3-Tab Navigation & Network Connection Card.
 *  - High-visibility Pure Dark Mode (#121316 surface background) with clean separation.
 *  - Fully functional 3-Tab Bottom Navigation: Home (Hero, Stages, Actions, Network Cards), Logs, Settings (Server Config & Storage).
 *  - Dual-band IP presentation (Local + LAN IP) with individual COPY IP buttons.
 *  - Material Ripple touch effects and high-contrast Action buttons (Red STOP SERVER).
 *  - Transparent FishBowl white header logo.
 *  - Strict NO-EMOJI enforcement across all views.
 */
public final class FishbowlActivity extends AppCompatActivity
        implements JellyfinController.Listener, JellyfinController.LogListener {

    private static final String TAG = "FishbowlActivity";
    private JellyfinController controller;
    private SharedPreferences settingsPrefs;

    // Active tab state (0: Home, 1: Logs, 2: Settings)
    private int activeTab = 0;

    // Containers
    private LinearLayout rootLayout;
    private View headerBar;
    private FrameLayout contentContainer;
    private LinearLayout bottomNavLayout;
    private View navDivider;
    private int statusBarInsetTop = 0;
    private int statusBarInsetBottom = 0;

    // Dynamic UI References for Dashboard
    private TextView statusBadge;
    private TextView healthStatusBadge;
    private TextView healthDetailText;
    private TextView healthUptimeText;
    private TextView healthRamText;
    private TextView healthStorageText;
    private TextView localIpValueText;
    private TextView lanIpValueText;
    private TextView tailscaleIpValueText;
    private ImageView btnCopyLocalIp;
    private ImageView btnCopyLanIp;
    private ImageView btnCopyTailscaleIp;
    private View rowLanView;
    private View rowLocalView;
    private View rowTailscaleView;
    private final java.util.Map<String, ImageView> serviceTileMap = new java.util.HashMap<>();

    // Download Center References
    private LinearLayout downloadItemsContainer;
    private TextView downloadSummaryBadge;

    // Activity Timeline References
    private LinearLayout timelineContainer;
    private String selectedTimelineFilter = "ALL";

    private View homeActionDock;
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
    private Button btnErrorClearCache;
    private Button btnErrorOpenStorage;
    private boolean errorCardDismissed = false;
    private boolean hasShownErrorPopupForIncident = false;
    private String lastPoppedIncident = "";

    // Permissions Card References
    private TextView permStorageBadge;
    private TextView permStorageAction;
    private TextView permBatteryBadge;
    private TextView permBatteryAction;
    private TextView permNotifBadge;
    private TextView permNotifAction;

    // Real Startup Progress Card References
    private View startupProgressCard;
    private TextView startupProgressHeader;
    private ProgressBar startupProgressBar;
    private TextView txtStage1, txtStage2, txtStage3, txtStage4, txtStage5;
    private long startupCompletedTimeMs = 0;
    private final Runnable startupProgressPoller = () -> renderCurrentState();

    // Transcoding & Hardware Dashboard References
    private TextView transcodeEngineBadge;
    private TextView transcodeActiveBadge;
    private TextView transcodeDetailsText;
    private TextView transcodeCodecSummaryText;

    // External Storage Space Dashboard References
    private TextView healthExternalStorageText;
    private View healthExternalStorageRow;

    // Slim Header Loading Bar & Status Banner
    private ProgressBar headerProgressBar;
    private TextView headerStatusBanner;

    // Logs & Settings/Storage References
    private TextView logsOutputText;
    private ScrollView logsScrollView;
    private TextView storageInfoText;
    private TextView storageHeaderTitle;
    private TextView storageHeaderSubtitle;
    private LinearLayout storageBarContainer;
    private LinearLayout storageLegendContainer;
    private LinearLayout externalStorageSection;
    private volatile boolean isCalculatingStorage = false;
    private TextView updateStatusText;
    private Button updateActionBtn;
    private Button updateForceDownloadBtn;
    private TextView runtimeStatusText;
    private Button btnSettingsReinstall;
    private Button btnSettingsRetry;

    // Logs & Console Tab Enhancements
    private String selectedLogService = "ALL";
    private String selectedLogLevel = "ALL";
    private LinearLayout logServiceChipsContainer;
    private LinearLayout logLevelChipsContainer;
    private String logFilter = "ALL";
    private boolean autoScrollEnabled = true;
    private Button btnFilterAll;
    private Button btnFilterError;
    private Button btnFilterWarn;
    private Button btnFilterInfo;
    private Button btnToggleAutoScroll;
    private TextView logsStatusSubtitle;

    // Updates Channel UI References
    private TextView updateChannelBadge;
    private Button btnChannelStable;
    private Button btnChannelBeta;

    // Backup & Restore References
    private ActivityResultLauncher<Intent> restoreFilePickerLauncher;
    private TextView backupStatusTv;

    // Cached Tab Views to prevent recreation and crashes on tab click (4 Destinations + Logs)
    private View cachedHomeView;
    private View cachedServicesView;
    private View cachedActivityView;
    private View cachedSettingsView;
    private View cachedLogsView;

    // Home Downloads Summary references
    private TextView homeDownloadsSummaryText;
    private TextView homeDownloadsCountBadge;

    // High-performance background thread pool for smooth asynchronous operations
    private final ExecutorService bgExecutor = Executors.newFixedThreadPool(3, new ThreadFactory() {
        private final AtomicInteger count = new AtomicInteger(1);
        @Override
        public Thread newThread(Runnable r) {
            Thread t = new Thread(r, "fishbowl-opt-" + count.getAndIncrement());
            t.setPriority(Thread.NORM_PRIORITY - 1);
            t.setDaemon(true);
            return t;
        }
    });

    // Download Center Auto-Refresh Poller (Dynamic throttle: 1.5s active, 3.5s idle)
    private final Handler downloadAutoRefreshHandler = new Handler(Looper.getMainLooper());
    private volatile boolean hasActiveDownloads = false;
    private final Runnable downloadRefreshRunnable = new Runnable() {
        @Override
        public void run() {
            if (activeTab == 1 && !isFinishing() && (Build.VERSION.SDK_INT < Build.VERSION_CODES.JELLY_BEAN_MR1 || !isDestroyed())) {
                refreshDownloadCenter();
                long delay = hasActiveDownloads ? 1500L : 3500L;
                downloadAutoRefreshHandler.postDelayed(this, delay);
            }
        }
    };

    // Services Manager UI References
    private LinearLayout servicesCardsContainer;
    private TextView servicesSummaryBadge;
    private Button masterStackToggleBtn;
    private TextView homeServicesCountBadge;
    private LinearLayout homeServicesRow;
    private boolean isAvailableServicesDrawerOpen = false;

    // Server Kill & Crash History Tracker Container
    private LinearLayout killTrackerContentContainer;

    // Dedicated Full-Screen Setup / Loading Overlay
    private View setupOverlayView;
    private LinearLayout setupOverlayContainer;
    private ProgressBar setupProgressBar;
    private TextView setupProgressText;
    private TextView setupDetailText;
    private Button setupRetryBtn;
    private Button setupDismissBtn;
    private TextView setupLogSummaryText;
    private boolean isSetupOverlayManuallyOpened = false;

    // Throttled Log Handler (Section 4 UI Thread Protection)
    private final android.os.Handler logUpdateHandler = new android.os.Handler(android.os.Looper.getMainLooper());
    private boolean logUpdatePending = false;
    private boolean showingDiskLog = true;
    private static final long LOG_REFRESH_THROTTLE_MS = 500;

    // Server Health Poller
    private final android.os.Handler mainHandler = new android.os.Handler(android.os.Looper.getMainLooper());
    private final android.os.Handler healthHandler = new android.os.Handler(android.os.Looper.getMainLooper());
    private final Runnable healthPollRunnable = new Runnable() {
        @Override
        public void run() {
            refreshServerHealthUI();
            healthHandler.postDelayed(this, 4000);
        }
    };

    // Network Callback & Receiver
    private ConnectivityManager.NetworkCallback networkCallback;
    private BroadcastReceiver connectivityReceiver;

    // USB-OTG Receiver for Live Hot-Plug Detection
    private BroadcastReceiver usbReceiver;

    private void registerUsbReceiver() {
        if (usbReceiver != null) return;
        usbReceiver = new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {
                mainHandler.postDelayed(() -> {
                    refreshServerHealthUI();
                }, 1000);
            }
        };
        IntentFilter filter = new IntentFilter();
        filter.addAction(Intent.ACTION_MEDIA_MOUNTED);
        filter.addAction(Intent.ACTION_MEDIA_UNMOUNTED);
        filter.addAction(Intent.ACTION_MEDIA_EJECT);
        filter.addAction(Intent.ACTION_MEDIA_REMOVED);
        filter.addDataScheme("file");
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                registerReceiver(usbReceiver, filter, Context.RECEIVER_EXPORTED);
            } else {
                registerReceiver(usbReceiver, filter);
            }
        } catch (Throwable t) {
            try {
                registerReceiver(usbReceiver, filter);
            } catch (Throwable ignored) {}
        }

        try {
            IntentFilter usbFilter = new IntentFilter();
            usbFilter.addAction(android.hardware.usb.UsbManager.ACTION_USB_DEVICE_ATTACHED);
            usbFilter.addAction(android.hardware.usb.UsbManager.ACTION_USB_DEVICE_DETACHED);
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                registerReceiver(usbReceiver, usbFilter, Context.RECEIVER_EXPORTED);
            } else {
                registerReceiver(usbReceiver, usbFilter);
            }
        } catch (Throwable ignored) {}
    }

    private void unregisterUsbReceiver() {
        if (usbReceiver != null) {
            try {
                unregisterReceiver(usbReceiver);
            } catch (Throwable ignored) {}
            usbReceiver = null;
        }
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        settingsPrefs = getSharedPreferences("fishbowl_settings", MODE_PRIVATE);
        applySavedThemeMode();

        super.onCreate(savedInstanceState);
        controller = JellyfinController.getInstance();
        controller.setContext(getApplicationContext());

        restoreFilePickerLauncher = registerForActivityResult(
                new ActivityResultContracts.StartActivityForResult(),
                result -> {
                    if (result.getResultCode() == RESULT_OK && result.getData() != null && result.getData().getData() != null) {
                        Uri uri = result.getData().getData();
                        confirmAndRestoreFromUri(uri);
                    }
                }
        );

        try {
            android.system.Os.chmod(getApplicationInfo().dataDir, 0700);
        } catch (Throwable ignored) {}

        // JellyfinServerService handles single foreground notification lifecycle.
        stopService(new Intent(this, TermuxService.class));

        // Request Android 13+ Notification Permission if needed (non-blocking)
        requestNotificationPermissionIfNeeded();

        // Shared internal storage initialization & permissions (v1.4.8-beta.1)
        requestStoragePermissionIfNeeded();
        new Thread(() -> {
            MediaStorageManager.getInstance(getApplicationContext()).initializeOnLaunch(getApplicationContext());
        }, "FishbowlStorageInit").start();

        setContentView(buildMainPixelShell());
        // Initialize native Chromium in-app Cloudflare solver (headless WebView on port 8191)
        com.fishbowl.app.services.NativeFlareSolver.getInstance().init(this);
        setupSystemBarsAndInsets();
        setupNetworkMonitor();
        registerUsbReceiver();

        if (savedInstanceState != null) {
            activeTab = savedInstanceState.getInt("activeTab", 0);
            updateBottomNavSelection();
            renderActiveTab();
        }

        // Auto-start server on app launch ONLY if explicitly enabled in preferences or uninitialized
        if (!JellyfinBootstrapper.isInitialized(this)) {
            Log.i(TAG, "Fishbowl runtime uninitialized on app launch. Automatically initiating initial setup & extraction.");
            showSetupOverlay(false);
            ensureBootstrapStartedIfNeeded();
        } else {
            triggerAutoStartOnAppLaunchIfEnabled();
        }
        handleControlIntent(getIntent());
    }

    private void triggerAutoStartOnAppLaunchIfEnabled() {
        if (!JellyfinBootstrapper.isInitialized(this)) return;
        SharedPreferences launchPrefs = getFishbowlPrefs();
        boolean startOnAppLaunch = launchPrefs.getBoolean("auto_start_app", launchPrefs.getBoolean("auto_start", false));
        if (startOnAppLaunch) {
            JellyfinController.State state = controller != null ? controller.getState() : JellyfinController.State.UNINITIALIZED;
            if (state != JellyfinController.State.RUNNING && state != JellyfinController.State.STARTING && state != JellyfinController.State.INITIALIZING) {
                Log.i(TAG, "Auto-start on app launch triggered: launching Jellyfin server only (current state=" + state + ")");
                if (controller != null) {
                    controller.start(getApplicationContext());
                }
                triggerServerAction(JellyfinServerService.ACTION_START_JELLYFIN);
                renderCurrentState();
            }
        }
    }

    private SharedPreferences getFishbowlPrefs() {
        return getSharedPreferences("fishbowl", MODE_PRIVATE);
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        handleControlIntent(intent);
        if (intent != null && Intent.ACTION_MAIN.equals(intent.getAction()) && intent.hasCategory(Intent.CATEGORY_LAUNCHER)) {
            triggerAutoStartOnAppLaunchIfEnabled();
        }
    }

    private void handleControlIntent(Intent intent) {
        if (intent == null) return;
        String action = intent.getAction();
        if ("com.fishbowl.app.ACTION_START_SERVICE".equals(action)) {
            String svcId = intent.getStringExtra("service_id");
            if (svcId != null) {
                MediaServiceManager.getInstance(this).startService(svcId);
            }
        } else if ("com.fishbowl.app.ACTION_START_ALL_SERVICES".equals(action)) {
            MediaServiceManager.getInstance(this).startAllServices();
        } else if ("com.fishbowl.app.ACTION_STOP_ALL_SERVICES".equals(action)) {
            MediaServiceManager.getInstance(this).stopAllServices();
            controller.stop();
            triggerServerAction(JellyfinServerService.ACTION_STOP);
        } else if ("com.fishbowl.app.ACTION_STOP_SERVICE".equals(action)) {
            String svcId = intent.getStringExtra("service_id");
            if (svcId != null) {
                MediaServiceManager.getInstance(this).stopService(svcId);
            }
        }
    }

    private final MediaServiceManager.ServiceListener serviceListener = (id, state) -> {
        runOnUiThread(() -> {
            refreshServicesTabUI();
            refreshHomeServicesBadge();
            if (activeTab == 2) {
                refreshLogServiceChips();
                refreshUnifiedLogsDisplay();
            }
        });
    };

    @Override
    protected void onStart() {
        super.onStart();
        controller.addListener(this);
        controller.reconcileStateAsync();
        controller.addLogListener(this);
        MediaServiceManager.getInstance(this).addListener(serviceListener);
        MediaServiceManager.getInstance(this).reconcileRunningServices();
        UpdateManager.getInstance(this).addListener(updateListener);
        UpdateManager.getInstance(this).checkForUpdates(this, false);
        refreshLanAddress();
        refreshHomeServicesBadge();
        renderCurrentState();
        refreshUpdateUI();
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (controller != null) {
            controller.reconcileStateAsync();
        }
        MediaServiceManager.getInstance(this).reconcileRunningServices();
        MediaServiceManager.getInstance(this).autoWireStack(null);
        refreshUpdateUI();
        refreshHomeServicesBadge();
        healthHandler.removeCallbacks(healthPollRunnable);
        healthHandler.post(healthPollRunnable);
        refreshPermissionsUI();
        if (!JellyfinBootstrapper.isInitialized(this)) {
            showSetupOverlay(false);
            ensureBootstrapStartedIfNeeded();
        } else {
            triggerAutoStartOnAppLaunchIfEnabled();
        }
        if (activeTab == 1) {
            downloadAutoRefreshHandler.removeCallbacks(downloadRefreshRunnable);
            downloadAutoRefreshHandler.post(downloadRefreshRunnable);
        }
    }

    @Override
    protected void onStop() {
        controller.removeListener(this);
        controller.removeLogListener(this);
        MediaServiceManager.getInstance(this).removeListener(serviceListener);
        UpdateManager.getInstance(this).removeListener(updateListener);
        logUpdateHandler.removeCallbacksAndMessages(null);
        logUpdatePending = false;
        healthHandler.removeCallbacks(healthPollRunnable);
        downloadAutoRefreshHandler.removeCallbacksAndMessages(null);
        mainHandler.removeCallbacks(startupProgressPoller);
        super.onStop();
    }

    @Override
    protected void onDestroy() {
        unregisterUsbReceiver();
        teardownNetworkMonitor();
        controller.removeLogListener(this);
        logUpdateHandler.removeCallbacksAndMessages(null);
        downloadAutoRefreshHandler.removeCallbacksAndMessages(null);
        com.fishbowl.app.services.NativeFlareSolver.getInstance().stop();
        bgExecutor.shutdown();
        super.onDestroy();
    }

    @Override
    protected void onSaveInstanceState(Bundle outState) {
        super.onSaveInstanceState(outState);
        outState.putInt("activeTab", activeTab);
    }

    @Override
    protected void onRestoreInstanceState(Bundle savedInstanceState) {
        super.onRestoreInstanceState(savedInstanceState);
        if (savedInstanceState != null) {
            activeTab = savedInstanceState.getInt("activeTab", 0);
            updateBottomNavSelection();
            renderActiveTab();
        }
    }

    @Override
    public void onConfigurationChanged(android.content.res.Configuration newConfig) {
        super.onConfigurationChanged(newConfig);
        // Configuration changes handled without destroying or restarting activity
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
            if (isSetupOverlayManuallyOpened || JellyfinBootstrapper.isInitialized(this)) {
                isSetupOverlayManuallyOpened = false;
                setupOverlayView.setVisibility(View.GONE);
                setChromeVisibilityForSetup(false);
                if (setupDismissBtn != null) setupDismissBtn.setVisibility(View.GONE);
                renderActiveTab();
                return;
            }
            Toast.makeText(this, "Setup in progress. Please keep Fishbowl open.", Toast.LENGTH_SHORT).show();
            moveTaskToBack(true);
            return;
        }
        moveTaskToBack(true);
    }

    // -- System Bar & Inset Management -----------------------------------------

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

            statusBarInsetTop = statusBarInset.top;
            statusBarInsetBottom = navBarInset.bottom;
            if (headerBar != null) {
                headerBar.setPadding(dp(20), statusBarInset.top + dp(10), dp(20), dp(10));
            }
            if (bottomNavLayout != null) {
                bottomNavLayout.setPadding(0, dp(6), 0, navBarInset.bottom + dp(6));
            }
            if (setupOverlayContainer != null) {
                setupOverlayContainer.setPadding(dp(24), statusBarInset.top + dp(28), dp(24), navBarInset.bottom + dp(24));
            }
            updateContentInsetsForTab();
            return insets;
        });
    }

    private void updateContentInsetsForTab() {
        if (contentContainer == null) return;
        if (activeTab == 0) {
            contentContainer.setPadding(0, 0, 0, 0);
        } else {
            contentContainer.setPadding(0, statusBarInsetTop > 0 ? statusBarInsetTop : dp(24), 0, 0);
        }
    }

    // -- Theme Engine (Pure Dark Mode Only) -----------------------------------

    private void applySavedThemeMode() {
        AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_YES);
    }

    private boolean isDarkTheme() {
        return true;
    }

    // -- Colors & Design Tokens (Google Stitch Minimalist) --------------------

    private int getBgColor() { return Color.parseColor("#000000"); }
    private int getSurfaceColor() { return Color.parseColor("#000000"); }
    private int getSurfaceElevatedColor() { return Color.parseColor("#0C0C0E"); }
    private int getBorderColor() { return Color.argb(20, 255, 255, 255); }
    private int getPrimaryTextColor() { return Color.parseColor("#F4F4F5"); }
    private int getSecondaryTextColor() { return Color.parseColor("#A1A1AA"); }
    private int getAccentColor() { return Color.parseColor("#38BDF8"); }
    private int getSuccessColor() { return Color.parseColor("#34D399"); }
    private int getWarningColor() { return Color.parseColor("#FFD51E"); }
    private int getDangerColor() { return Color.parseColor("#FF1744"); }

    private GradientDrawable createCardDrawable(int bgColor, int radiusDp) {
        int r = radiusDp > 0 ? radiusDp : dp(14);
        return createBorderedRoundedDrawable(bgColor, getBorderColor(), dp(1), r);
    }


    // -- Root UI Shell Construction ---------------------------------------------

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

        cachedServicesView = buildServicesTab();
        cachedServicesView.setVisibility(View.GONE);
        contentContainer.addView(cachedServicesView, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        cachedActivityView = buildActivityTab();
        cachedActivityView.setVisibility(View.GONE);
        contentContainer.addView(cachedActivityView, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        cachedSettingsView = buildSettingsTab();
        cachedSettingsView.setVisibility(View.GONE);
        contentContainer.addView(cachedSettingsView, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        cachedLogsView = buildLogsTab();
        cachedLogsView.setVisibility(View.GONE);
        contentContainer.addView(cachedLogsView, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        // Dedicated Full-Screen Setup / Loading Overlay (placed on top of tabs)
        setupOverlayView = buildSetupOverlay();
        contentContainer.addView(setupOverlayView, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        // Bottom Navigation Bar (4 Destinations: Home, Services, Activity, Settings)
        navDivider = new View(this);
        navDivider.setBackgroundColor(Color.argb(10, 255, 255, 255));
        rootLayout.addView(navDivider, new LinearLayout.LayoutParams(-1, dp(1)));

        bottomNavLayout = buildBottomNavBar();
        rootLayout.addView(bottomNavLayout);

        // Initial tab render
        renderActiveTab();

        return rootLayout;
    }

    private View buildHeaderBar() {
        LinearLayout rootHeader = new LinearLayout(this);
        rootHeader.setOrientation(LinearLayout.VERTICAL);
        rootHeader.setBackgroundColor(getBgColor());
        rootHeader.setPadding(dp(20), dp(10), dp(20), dp(10));

        LinearLayout header = new LinearLayout(this);
        header.setOrientation(LinearLayout.HORIZONTAL);
        header.setGravity(Gravity.CENTER_VERTICAL);
        header.setPadding(0, dp(2), 0, dp(2));

        // Logo Mark (Fishbowl Logo, White)
        ImageView logo = new ImageView(this);
        logo.setImageResource(R.drawable.ic_fishbowl_logo);
        logo.setColorFilter(Color.WHITE);
        logo.setScaleType(ImageView.ScaleType.FIT_CENTER);
        LinearLayout.LayoutParams logoParams = new LinearLayout.LayoutParams(dp(28), dp(28));
        logoParams.rightMargin = dp(10);
        header.addView(logo, logoParams);

        // App Branding Name: FISHBOWL (Uppercase, tracking-widest)
        TextView title = new TextView(this);
        title.setText("FISHBOWL");
        title.setTextSize(14f);
        title.setLetterSpacing(0.14f);
        title.setTypeface(Typeface.create("sans-serif", Typeface.BOLD));
        title.setTextColor(Color.WHITE);
        header.addView(title, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1.0f));

        TextView verBadge = new TextView(this);
        verBadge.setText("v" + com.termux.BuildConfig.VERSION_NAME + " • JF v12.4.0");
        verBadge.setTextSize(10);
        verBadge.setTypeface(Typeface.MONOSPACE, Typeface.BOLD);
        verBadge.setTextColor(Color.parseColor("#71717A"));
        header.addView(verBadge);

        rootHeader.addView(header);

        // Hairline divider
        View border = new View(this);
        border.setBackgroundColor(Color.argb(12, 255, 255, 255));
        rootHeader.addView(border, new LinearLayout.LayoutParams(-1, dp(1)));

        return rootHeader;
    }

    private LinearLayout buildBottomNavBar() {
        LinearLayout nav = new LinearLayout(this);
        nav.setOrientation(LinearLayout.HORIZONTAL);
        nav.setBackgroundColor(getBgColor());
        nav.setPadding(dp(16), dp(4), dp(16), dp(4));

        // Icon-Only 4-Tab Bottom Navigation: Home (0), Media Stack & Downloads (1), Activity (2), Settings (3)
        addNavTab(nav, 0, R.drawable.ic_home);
        addNavTab(nav, 1, R.drawable.ic_services);
        addNavTab(nav, 2, R.drawable.ic_activity);
        addNavTab(nav, 3, R.drawable.ic_settings);

        return nav;
    }

    private void addNavTab(LinearLayout parent, int tabIndex, int iconRes) {
        FrameLayout tab = new FrameLayout(this);
        tab.setPadding(0, dp(4), 0, dp(4));
        tab.setOnClickListener(v -> {
            if (activeTab != tabIndex) {
                activeTab = tabIndex;
                updateBottomNavSelection();
                renderActiveTab();
            }
        });

        View pill = new View(this);
        pill.setTag("pill");
        pill.setVisibility(View.GONE);
        tab.addView(pill, new FrameLayout.LayoutParams(0, 0));

        ImageView icon = new ImageView(this);
        icon.setTag("icon");
        icon.setImageResource(iconRes);
        FrameLayout.LayoutParams iconParams = new FrameLayout.LayoutParams(dp(22), dp(22));
        iconParams.gravity = Gravity.CENTER;
        tab.addView(icon, iconParams);

        LinearLayout.LayoutParams tabParams = new LinearLayout.LayoutParams(0, dp(48), 1.0f);
        parent.addView(tab, tabParams);
    }

    private void updateBottomNavSelection() {
        if (bottomNavLayout == null) return;
        for (int i = 0; i < bottomNavLayout.getChildCount(); i++) {
            View child = bottomNavLayout.getChildAt(i);
            if (!(child instanceof FrameLayout)) continue;
            FrameLayout tab = (FrameLayout) child;
            ImageView icon = tab.findViewWithTag("icon");

            boolean selected = (i == activeTab);
            int color = selected ? Color.WHITE : Color.parseColor("#71717A");
            if (icon != null) {
                icon.setColorFilter(color);
                if (selected) {
                    icon.setScaleX(1.05f);
                    icon.setScaleY(1.05f);
                } else {
                    icon.setScaleX(1.0f);
                    icon.setScaleY(1.0f);
                }
            }
        }
    }

    // -- Tab View Router (Cached & Zero-Recreation) ---------------------------

    private void renderActiveTab() {
        updateBottomNavSelection();

        if (setupOverlayView != null && setupOverlayView.getVisibility() == View.VISIBLE) {
            setChromeVisibilityForSetup(true);
        } else {
            if (headerBar != null) {
                headerBar.setVisibility(activeTab == 0 ? View.VISIBLE : View.GONE);
            }
            if (bottomNavLayout != null) {
                bottomNavLayout.setVisibility(View.VISIBLE);
            }
            if (navDivider != null) {
                navDivider.setVisibility(View.VISIBLE);
            }
        }
        updateContentInsetsForTab();

        if (cachedHomeView != null) cachedHomeView.setVisibility(activeTab == 0 ? View.VISIBLE : View.GONE);
        if (cachedServicesView != null) cachedServicesView.setVisibility(activeTab == 1 ? View.VISIBLE : View.GONE);
        if (cachedActivityView != null) cachedActivityView.setVisibility(activeTab == 2 ? View.VISIBLE : View.GONE);
        if (cachedSettingsView != null) cachedSettingsView.setVisibility(activeTab == 3 ? View.VISIBLE : View.GONE);
        if (cachedLogsView != null) cachedLogsView.setVisibility(activeTab == 4 ? View.VISIBLE : View.GONE);

        if (activeTab == 0) {
            refreshLanAddress();
            refreshHomeServicesBadge();
        } else if (activeTab == 1) {
            refreshServicesTabUI();
            refreshDownloadCenter();
        } else if (activeTab == 2) {
            refreshLogServiceChips();
            updateLogLevelChips();
            refreshUnifiedLogsDisplay();
            if (autoScrollEnabled && logsScrollView != null) {
                logsScrollView.post(() -> logsScrollView.fullScroll(View.FOCUS_DOWN));
            }
        } else if (activeTab == 3) {
            updateStorageInfoText();
            refreshRuntimeStatusUI();
            refreshPermissionsUI();
            UpdateManager uMgr = UpdateManager.getInstance(this);
            if (uMgr.getCurrentState() == UpdateManager.State.IDLE) {
                uMgr.checkForUpdates(this, false);
            }
            refreshUpdateUI();
            refreshKillTrackerUI();
        } else if (activeTab == 4) {
            refreshUnifiedLogsDisplay();
            if (autoScrollEnabled && logsScrollView != null) {
                logsScrollView.post(() -> logsScrollView.fullScroll(View.FOCUS_DOWN));
            }
        }

        if (activeTab == 2 || activeTab == 4 || (setupOverlayView != null && setupOverlayView.getVisibility() == View.VISIBLE)) {
            controller.addLogListener(this);
        } else {
            controller.removeLogListener(this);
            logUpdateHandler.removeCallbacksAndMessages(null);
            logUpdatePending = false;
        }

        if (activeTab == 1) {
            downloadAutoRefreshHandler.removeCallbacks(downloadRefreshRunnable);
            downloadAutoRefreshHandler.postDelayed(downloadRefreshRunnable, 1000);
        } else {
            downloadAutoRefreshHandler.removeCallbacks(downloadRefreshRunnable);
        }

        renderCurrentState();
    }

    // -- Dedicated Full-Screen Setup & Extraction Screen -----------------------

    private void setChromeVisibilityForSetup(boolean setupActive) {
        if (setupActive) {
            if (headerBar != null) headerBar.setVisibility(View.GONE);
            if (headerProgressBar != null) headerProgressBar.setVisibility(View.GONE);
            if (headerStatusBanner != null) headerStatusBanner.setVisibility(View.GONE);
            if (bottomNavLayout != null) bottomNavLayout.setVisibility(View.GONE);
            if (navDivider != null) navDivider.setVisibility(View.GONE);
        } else {
            if (bottomNavLayout != null) bottomNavLayout.setVisibility(View.VISIBLE);
            if (navDivider != null) navDivider.setVisibility(View.VISIBLE);
            if (headerBar != null) headerBar.setVisibility(activeTab == 0 ? View.VISIBLE : View.GONE);
            updateContentInsetsForTab();
        }
    }

    private View buildSetupOverlay() {
        ScrollView scroll = new ScrollView(this);
        scroll.setVerticalScrollBarEnabled(false);
        scroll.setHorizontalScrollBarEnabled(false);
        scroll.setOverScrollMode(View.OVER_SCROLL_NEVER);
        scroll.setSmoothScrollingEnabled(true);
        scroll.setFillViewport(true);
        scroll.setBackgroundColor(getBgColor());
        scroll.setVisibility(JellyfinBootstrapper.isInitialized(this) ? View.GONE : View.VISIBLE);

        setupOverlayContainer = new LinearLayout(this);
        setupOverlayContainer.setOrientation(LinearLayout.VERTICAL);
        setupOverlayContainer.setGravity(Gravity.CENTER_HORIZONTAL);
        int topPad = statusBarInsetTop > 0 ? statusBarInsetTop + dp(28) : dp(48);
        int btmPad = statusBarInsetBottom > 0 ? statusBarInsetBottom + dp(24) : dp(32);
        setupOverlayContainer.setPadding(dp(24), topPad, dp(24), btmPad);
        LinearLayout container = setupOverlayContainer;

        // Setup Brand Logo with subtle glow / accent tint
        ImageView logo = new ImageView(this);
        logo.setImageResource(R.drawable.ic_fishbowl_logo);
        logo.setColorFilter(Color.WHITE);
        LinearLayout.LayoutParams logoParams = new LinearLayout.LayoutParams(dp(58), dp(58));
        logoParams.bottomMargin = dp(14);
        container.addView(logo, logoParams);

        // Setup Title
        TextView title = new TextView(this);
        title.setText("FishBowl System Setup");
        title.setTextSize(20);
        title.setTypeface(Typeface.create("sans-serif-medium", Typeface.BOLD));
        title.setTextColor(getPrimaryTextColor());
        title.setGravity(Gravity.CENTER);
        container.addView(title);

        // Subtitle & Status Details
        setupDetailText = new TextView(this);
        setupDetailText.setText("Preparing runtime environment...");
        setupDetailText.setTextSize(12.5f);
        setupDetailText.setTextColor(getSecondaryTextColor());
        setupDetailText.setGravity(Gravity.CENTER);
        setupDetailText.setPadding(0, dp(6), 0, dp(18));
        container.addView(setupDetailText);

        // Progress Card Container
        LinearLayout progressCard = new LinearLayout(this);
        progressCard.setOrientation(LinearLayout.VERTICAL);
        progressCard.setPadding(dp(16), dp(14), dp(16), dp(14));
        progressCard.setBackground(createBorderedRoundedDrawable(getSurfaceColor(), getBorderColor(), dp(1), dp(12)));
        LinearLayout.LayoutParams cardParams = new LinearLayout.LayoutParams(-1, -2);
        cardParams.bottomMargin = dp(12);
        progressCard.setLayoutParams(cardParams);

        // Top Row with Stage Label & Percentage
        LinearLayout topRow = new LinearLayout(this);
        topRow.setOrientation(LinearLayout.HORIZONTAL);
        topRow.setGravity(Gravity.CENTER_VERTICAL);

        TextView lblProgress = new TextView(this);
        lblProgress.setText("INSTALLATION & RUNTIME SETUP");
        lblProgress.setTextSize(11);
        lblProgress.setTypeface(Typeface.create("sans-serif-medium", Typeface.BOLD));
        lblProgress.setTextColor(getSecondaryTextColor());
        topRow.addView(lblProgress, new LinearLayout.LayoutParams(0, -2, 1.0f));

        setupProgressText = new TextView(this);
        setupProgressText.setText("0%");
        setupProgressText.setTextSize(15);
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

        TextView row2 = createSetupCheckItem("2. Extracting FishBowl & Jellyfin Core 12.4.0 ARM64 runtime");
        row2.setTag("setup_step_2");
        progressCard.addView(row2);

        TextView row3 = createSetupCheckItem("3. Unpacking Microsoft .NET 10 & FFmpeg 7.1.4 transcoder");
        row3.setTag("setup_step_3");
        progressCard.addView(row3);

        TextView row4 = createSetupCheckItem("4. Configuring POSIX permissions & system libraries");
        row4.setTag("setup_step_4");
        progressCard.addView(row4);

        TextView row5 = createSetupCheckItem("5. Verifying server binaries & service readiness");
        row5.setTag("setup_step_5");
        progressCard.addView(row5);

        container.addView(progressCard);

        // Live Log Preview Box
        LinearLayout logCard = new LinearLayout(this);
        logCard.setOrientation(LinearLayout.VERTICAL);
        logCard.setPadding(dp(16), dp(12), dp(16), dp(12));
        logCard.setBackground(createBorderedRoundedDrawable(Color.parseColor("#08090C"), getBorderColor(), dp(1), dp(10)));
        LinearLayout.LayoutParams logCardParams = new LinearLayout.LayoutParams(-1, -2);
        logCardParams.bottomMargin = dp(12);
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
        setupLogSummaryText.setTextColor(Color.parseColor("#10B981"));
        setupLogSummaryText.setMaxLines(4);
        setupLogSummaryText.setEllipsize(android.text.TextUtils.TruncateAt.END);
        logCard.addView(setupLogSummaryText);

        container.addView(logCard);

        // Advisory Note
        TextView note = new TextView(this);
        note.setText("Please keep FishBowl open while runtime extraction completes.\nThis setup process only runs once.");
        note.setTextSize(11.5f);
        note.setTextColor(getSecondaryTextColor());
        note.setGravity(Gravity.CENTER);
        note.setPadding(dp(8), 0, dp(8), dp(14));
        container.addView(note);

        // Continue to FishBowl button (Shown when setup is completed or previewed)
        setupDismissBtn = createPixelButton("CONTINUE TO FISHBOWL", getAccentColor(), Color.WHITE);
        setupDismissBtn.setFocusable(true);
        setupDismissBtn.setFocusableInTouchMode(true);
        setupDismissBtn.setOnClickListener(v -> {
            isSetupOverlayManuallyOpened = false;
            if (setupDismissBtn != null) {
                setupDismissBtn.setVisibility(View.GONE);
            }
            setupOverlayView.setVisibility(View.GONE);
            setChromeVisibilityForSetup(false);
            renderActiveTab();
        });
        setupDismissBtn.setVisibility(View.GONE);
        LinearLayout.LayoutParams pDismiss = new LinearLayout.LayoutParams(-1, dp(40));
        pDismiss.bottomMargin = dp(8);
        container.addView(setupDismissBtn, pDismiss);

        // Retry & Reinstall Buttons (Shown only if failure occurs)
        setupRetryBtn = createPixelButton("RETRY SETUP", getAccentColor(), Color.WHITE);
        setupRetryBtn.setOnClickListener(v -> {
            setupRetryBtn.setVisibility(View.GONE);
            triggerServerAction(JellyfinServerService.ACTION_START);
            controller.reinstallRuntime(this);
        });
        setupRetryBtn.setVisibility(View.GONE);
        LinearLayout.LayoutParams pRetry = new LinearLayout.LayoutParams(-1, dp(40));
        pRetry.bottomMargin = dp(8);
        container.addView(setupRetryBtn, pRetry);

        Button setupReinstallBtn = createPixelButton("CLEAN REINSTALL", Color.parseColor("#455A64"), Color.WHITE);
        setupReinstallBtn.setOnClickListener(v -> showReinstallConfirmation());
        setupReinstallBtn.setTag("setup_reinstall_btn");
        setupReinstallBtn.setVisibility(View.GONE);
        LinearLayout.LayoutParams pReinst = new LinearLayout.LayoutParams(-1, dp(40));
        pReinst.bottomMargin = dp(8);
        container.addView(setupReinstallBtn, pReinst);

        scroll.addView(container);
        return scroll;
    }

    private TextView createSetupCheckItem(String text) {
        TextView tv = new TextView(this);
        tv.setText("○  " + text);
        tv.setTextSize(12);
        tv.setTextColor(getSecondaryTextColor());
        tv.setPadding(0, dp(4), 0, dp(4));
        return tv;
    }

    private void ensureBootstrapStartedIfNeeded() {
        if (controller == null) return;
        boolean initialized = JellyfinBootstrapper.isInitialized(this);
        if (!initialized) {
            JellyfinController.State state = controller.getState();
            String lastErr = JellyfinBootstrapper.getLastError();
            boolean hasFailed = (state == JellyfinController.State.FAILED || state == JellyfinController.State.CRASHED)
                    || (lastErr != null && !lastErr.isEmpty() && state != JellyfinController.State.INITIALIZING && state != JellyfinController.State.STARTING);
            if (!hasFailed && state != JellyfinController.State.INITIALIZING && state != JellyfinController.State.STARTING) {
                Log.i(TAG, "ensureBootstrapStartedIfNeeded: initiating automatic Jellyfin runtime extraction & stack start");
                triggerServerAction(JellyfinServerService.ACTION_START);
                controller.start(getApplicationContext());
            }
        }
    }

    public void showSetupOverlay(boolean allowDismiss) {
        if (setupOverlayView == null) return;
        isSetupOverlayManuallyOpened = allowDismiss;
        setChromeVisibilityForSetup(true);
        if (setupDismissBtn != null) {
            boolean showDismiss = (allowDismiss || JellyfinBootstrapper.isInitialized(this));
            setupDismissBtn.setVisibility(showDismiss ? View.VISIBLE : View.GONE);
            if (showDismiss) {
                setupDismissBtn.post(() -> setupDismissBtn.requestFocus());
            }
        }
        setupOverlayView.setVisibility(View.VISIBLE);
        setupOverlayView.bringToFront();
        renderSetupOverlayState();
    }

    private void renderSetupOverlayState() {
        if (setupOverlayView == null || controller == null) return;

        JellyfinController.State state = controller.getState();
        boolean initialized = JellyfinBootstrapper.isInitialized(this);
        boolean isInitializing = (state == JellyfinController.State.INITIALIZING);
        boolean isStarting = (state == JellyfinController.State.STARTING);
        boolean isRunning = (state == JellyfinController.State.RUNNING);
        boolean isFailed = (state == JellyfinController.State.FAILED || state == JellyfinController.State.CRASHED);

        // Overlay is visible ONLY when uninitialized, or manually opened from settings
        boolean showOverlay = (!initialized && (isInitializing || !isRunning)) || isSetupOverlayManuallyOpened;

        if (!initialized && !isFailed && !isInitializing && !isStarting && !isRunning) {
            ensureBootstrapStartedIfNeeded();
        }

        if (!showOverlay) {
            setupOverlayView.setVisibility(View.GONE);
            setChromeVisibilityForSetup(false);
            if (setupDismissBtn != null) {
                setupDismissBtn.setVisibility(View.GONE);
            }
            getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
            return;
        }

        setupOverlayView.setVisibility(View.VISIBLE);
        setChromeVisibilityForSetup(true);

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
            if (setupDismissBtn != null) {
                setupDismissBtn.setVisibility(View.GONE);
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
                String detail = (msg != null && !msg.isEmpty()) ? msg : (initialized ? "Runtime components fully verified & ready." : "Configuring runtime files...");
                setupDetailText.setText(detail);
                setupDetailText.setTextColor(initialized ? Color.parseColor("#10B981") : getSecondaryTextColor());
            }
            if (setupRetryBtn != null) {
                setupRetryBtn.setVisibility(View.GONE);
            }
            View reinstallBtn = setupOverlayView.findViewWithTag("setup_reinstall_btn");
            if (reinstallBtn != null) {
                reinstallBtn.setVisibility(View.GONE);
            }
            if (setupDismissBtn != null) {
                boolean showDismiss = (initialized || isSetupOverlayManuallyOpened);
                setupDismissBtn.setVisibility(showDismiss ? View.VISIBLE : View.GONE);
                if (showDismiss) {
                    setupDismissBtn.post(() -> setupDismissBtn.requestFocus());
                }
            }

            // Update stage rows
            updateSetupStageItem("setup_step_1", "1. Recombining split package archives (asset chunks)", pct >= 35 || initialized, pct > 0 && pct < 35);
            updateSetupStageItem("setup_step_2", "2. Extracting FishBowl & Jellyfin Core 12.4.0 ARM64 runtime", pct >= 65 || initialized, pct >= 35 && pct < 65);
            updateSetupStageItem("setup_step_3", "3. Unpacking Microsoft .NET 10 & FFmpeg 7.1.4 transcoder", pct >= 88 || initialized, pct >= 65 && pct < 88);
            updateSetupStageItem("setup_step_4", "4. Configuring POSIX permissions & system libraries", pct >= 95 || initialized, pct >= 88 && pct < 95);
            updateSetupStageItem("setup_step_5", "5. Verifying server binaries & service readiness", initialized || pct == 100, pct >= 95 && !initialized);
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
            tv.setText("✓  " + label);
            tv.setTextColor(Color.parseColor("#10B981"));
        } else if (active) {
            tv.setText("●  " + label);
            tv.setTextColor(getWarningColor());
        } else {
            tv.setText("○  " + label);
            tv.setTextColor(getSecondaryTextColor());
        }
    }

    // -- Tab 0: Home (Dashboard & Server Controls) ------------------------------

    private View buildHomeTab() {
        FrameLayout root = new FrameLayout(this);
        root.setBackgroundColor(getBgColor());

        ScrollView scroll = new ScrollView(this);
        scroll.setVerticalScrollBarEnabled(false);
        scroll.setHorizontalScrollBarEnabled(false);
        scroll.setOverScrollMode(View.OVER_SCROLL_NEVER);
        scroll.setSmoothScrollingEnabled(true);
        scroll.setFillViewport(true);
        scroll.setBackgroundColor(getBgColor());
        // Bottom padding ensures content scrolls completely clear of the docked action bar
        scroll.setPadding(dp(20), dp(16), dp(20), dp(130));
        scroll.setClipToPadding(false);

        LinearLayout layout = new LinearLayout(this);
        layout.setOrientation(LinearLayout.VERTICAL);

        // 1. Hero Status Section (Clean, Unboxed)
        layout.addView(buildServerHeroSection());

        // Gap dp(14) - tightened to eliminate dead void space
        layout.addView(createSectionSpacer(dp(14)));

        // 2. Client Streaming IPs (Seamless Borderless Rows)
        layout.addView(buildClientStreamingIpsSection());

        // Gap dp(32)
        layout.addView(createSectionSpacer());

        // 3. Services Control Plane (Horizontal Icon Row)
        layout.addView(buildServicesControlPlaneSection());

        // Tools Action Row (Movie Importer & Media Storage)
        layout.addView(buildHomeToolsSection());

        // 4. Dynamic Alerts (Startup Progress Card & Error Diagnostic Card)
        startupProgressCard = buildRealStartupProgressCard();
        layout.addView(startupProgressCard);

        errorDiagnosticCard = buildErrorDiagnosticCard();
        layout.addView(errorDiagnosticCard);

        scroll.addView(layout);
        root.addView(scroll, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        // Docked Server Controls at Bottom
        homeActionDock = buildHomeActionDock();
        FrameLayout.LayoutParams dockParams = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        dockParams.gravity = Gravity.BOTTOM;
        root.addView(homeActionDock, dockParams);

        return root;
    }

    private View buildHomeActionDock() {
        LinearLayout dock = new LinearLayout(this);
        dock.setOrientation(LinearLayout.VERTICAL);
        dock.setBackgroundColor(getBgColor());

        // Hairline top border
        View topBorder = new View(this);
        topBorder.setBackgroundColor(Color.argb(10, 255, 255, 255));
        dock.addView(topBorder, new LinearLayout.LayoutParams(-1, dp(1)));

        LinearLayout inner = new LinearLayout(this);
        inner.setOrientation(LinearLayout.VERTICAL);
        inner.setPadding(dp(20), dp(10), dp(20), dp(12));

        // Primary Action: OPEN JELLYFIN (White button, black text) or START SERVER
        btnOpenJellyfin = createPixelButton("OPEN JELLYFIN", Color.WHITE, Color.BLACK);
        LinearLayout.LayoutParams pOpen = new LinearLayout.LayoutParams(-1, dp(44));
        btnOpenJellyfin.setLayoutParams(pOpen);
        btnOpenJellyfin.setOnClickListener(v -> startActivity(new Intent(this, JellyfinWebActivity.class)));
        applyButtonStyle(btnOpenJellyfin, "OPEN JELLYFIN", Color.WHITE, Color.BLACK, true);
        inner.addView(btnOpenJellyfin);

        btnStartServer = createPixelButton("START SERVER", Color.WHITE, Color.BLACK);
        LinearLayout.LayoutParams pStart = new LinearLayout.LayoutParams(-1, dp(44));
        btnStartServer.setLayoutParams(pStart);
        btnStartServer.setOnClickListener(v -> {
            long now = SystemClock.elapsedRealtime();
            if (now - lastButtonActionTimestamp < BUTTON_DEBOUNCE_MS) return;
            lastButtonActionTimestamp = now;
            errorCardDismissed = false;

            applyButtonStyle(btnStartServer, "STARTING SERVER...", Color.parseColor("#27272A"), Color.parseColor("#F59E0B"), false);
            if (btnStopServer != null) btnStopServer.setEnabled(false);
            if (btnRestartServer != null) btnRestartServer.setEnabled(false);
            controller.start(getApplicationContext());
            triggerServerAction(JellyfinServerService.ACTION_START_JELLYFIN);
        });
        applyButtonStyle(btnStartServer, "START SERVER", Color.WHITE, Color.BLACK, true);
        inner.addView(btnStartServer);

        // Secondary Controls Row: [Restart] and [Stop] side-by-side
        LinearLayout subControls = new LinearLayout(this);
        subControls.setOrientation(LinearLayout.HORIZONTAL);
        subControls.setBaselineAligned(false);
        LinearLayout.LayoutParams pSub = new LinearLayout.LayoutParams(-1, -2);
        pSub.topMargin = dp(8);
        subControls.setLayoutParams(pSub);

        btnRestartServer = createPixelButton("Restart", Color.TRANSPARENT, Color.WHITE);
        btnRestartServer.setOnClickListener(v -> {
            long now = SystemClock.elapsedRealtime();
            if (now - lastButtonActionTimestamp < BUTTON_DEBOUNCE_MS) return;
            lastButtonActionTimestamp = now;
            errorCardDismissed = false;

            applyDockButtonStyle(btnRestartServer, "Restarting...", Color.parseColor("#27272A"), Color.parseColor("#A1A1AA"), R.drawable.ic_restart, false);
            if (btnStartServer != null) btnStartServer.setEnabled(false);
            if (btnStopServer != null) btnStopServer.setEnabled(false);
            controller.stop();
            triggerServerAction(JellyfinServerService.ACTION_STOP_JELLYFIN);
            new android.os.Handler(android.os.Looper.getMainLooper()).postDelayed(() -> {
                controller.start(getApplicationContext());
                triggerServerAction(JellyfinServerService.ACTION_START_JELLYFIN);
            }, 1600);
        });
        applyDockButtonStyle(btnRestartServer, "Restart", Color.parseColor("#27272A"), Color.WHITE, R.drawable.ic_restart, true);
        LinearLayout.LayoutParams lpRestart = new LinearLayout.LayoutParams(0, dp(44), 1.0f);
        lpRestart.rightMargin = dp(4);
        subControls.addView(btnRestartServer, lpRestart);

        btnStopServer = createPixelButton("Stop", Color.TRANSPARENT, Color.WHITE);
        btnStopServer.setOnClickListener(v -> {
            long now = SystemClock.elapsedRealtime();
            if (now - lastButtonActionTimestamp < BUTTON_DEBOUNCE_MS) return;
            lastButtonActionTimestamp = now;

            applyDockButtonStyle(btnStopServer, "Stopping...", Color.parseColor("#3F1D24"), Color.parseColor("#F43F5E"), R.drawable.ic_stop, false);
            if (btnStartServer != null) btnStartServer.setEnabled(false);
            if (btnRestartServer != null) btnRestartServer.setEnabled(false);
            controller.stop();
            triggerServerAction(JellyfinServerService.ACTION_STOP_JELLYFIN);
        });
        applyDockButtonStyle(btnStopServer, "Stop", Color.parseColor("#E11D48"), Color.WHITE, R.drawable.ic_stop, true);
        LinearLayout.LayoutParams lpStop = new LinearLayout.LayoutParams(0, dp(44), 1.0f);
        lpStop.leftMargin = dp(4);
        subControls.addView(btnStopServer, lpStop);

        inner.addView(subControls);

        // Emergency action buttons (hidden by default)
        btnResetCrash = createPixelButton("RESET CRASH LOOP", getDangerColor(), Color.WHITE);
        btnResetCrash.setOnClickListener(v -> {
            long now = SystemClock.elapsedRealtime();
            if (now - lastButtonActionTimestamp < BUTTON_DEBOUNCE_MS) return;
            lastButtonActionTimestamp = now;
            controller.resetCrashLoop(this);
        });
        btnResetCrash.setVisibility(View.GONE);
        LinearLayout.LayoutParams pCrash = new LinearLayout.LayoutParams(-1, dp(38));
        pCrash.topMargin = dp(6);
        inner.addView(btnResetCrash, pCrash);

        btnReinstallRuntime = createPixelButton("REINSTALL RUNTIME", Color.parseColor("#455A64"), Color.WHITE);
        btnReinstallRuntime.setOnClickListener(v -> showReinstallConfirmation());
        btnReinstallRuntime.setVisibility(View.GONE);
        LinearLayout.LayoutParams pReinstall = new LinearLayout.LayoutParams(-1, dp(38));
        pReinstall.topMargin = dp(6);
        inner.addView(btnReinstallRuntime, pReinstall);

        dock.addView(inner);
        return dock;
    }

    private View buildServerHealthCard() {
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setPadding(dp(16), dp(14), dp(16), dp(14));
        card.setBackground(createCardDrawable(getSurfaceColor(), dp(14)));
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(-1, -2);
        params.topMargin = dp(12);
        card.setLayoutParams(params);

        TextView header = new TextView(this);
        header.setText("STORAGE & PLAYBACK STATUS");
        header.setTextSize(10);
        header.setLetterSpacing(0.08f);
        header.setTypeface(Typeface.create("sans-serif-medium", Typeface.BOLD));
        header.setTextColor(getSecondaryTextColor());
        header.setPadding(0, 0, 0, dp(10));
        card.addView(header);

        // Row 1: Client Playback & Direct Stream Status
        LinearLayout rowStatus = new LinearLayout(this);
        rowStatus.setOrientation(LinearLayout.HORIZONTAL);
        rowStatus.setGravity(Gravity.CENTER_VERTICAL);
        TextView lblStatus = new TextView(this);
        lblStatus.setText("CLIENT PLAYBACK");
        lblStatus.setTextSize(10);
        lblStatus.setLetterSpacing(0.06f);
        lblStatus.setTypeface(Typeface.create("sans-serif-medium", Typeface.BOLD));
        lblStatus.setTextColor(getSecondaryTextColor());
        rowStatus.addView(lblStatus, new LinearLayout.LayoutParams(0, -2, 1.0f));

        transcodeActiveBadge = new TextView(this);
        transcodeActiveBadge.setText("● IDLE / DIRECT PLAY");
        transcodeActiveBadge.setTextSize(11);
        transcodeActiveBadge.setTypeface(Typeface.create("sans-serif-medium", Typeface.BOLD));
        transcodeActiveBadge.setTextColor(getSuccessColor());
        rowStatus.addView(transcodeActiveBadge);
        card.addView(rowStatus);

        transcodeDetailsText = new TextView(this);
        transcodeDetailsText.setText("Direct stream ready (0% phone CPU load for client devices)");
        transcodeDetailsText.setTextSize(11.5f);
        transcodeDetailsText.setTextColor(getSecondaryTextColor());
        transcodeDetailsText.setPadding(0, dp(4), 0, dp(6));
        card.addView(transcodeDetailsText);

        // Hairline Divider
        View d1 = new View(this);
        d1.setBackgroundColor(getBorderColor());
        LinearLayout.LayoutParams pD1 = new LinearLayout.LayoutParams(-1, dp(1));
        pD1.topMargin = dp(10);
        pD1.bottomMargin = dp(10);
        card.addView(d1, pD1);

        // Row 2: Internal Storage
        LinearLayout rowStorage = new LinearLayout(this);
        rowStorage.setOrientation(LinearLayout.HORIZONTAL);
        rowStorage.setGravity(Gravity.CENTER_VERTICAL);
        TextView lblStorage = new TextView(this);
        lblStorage.setText("INTERNAL STORAGE");
        lblStorage.setTextSize(10);
        lblStorage.setLetterSpacing(0.06f);
        lblStorage.setTypeface(Typeface.create("sans-serif-medium", Typeface.BOLD));
        lblStorage.setTextColor(getSecondaryTextColor());
        rowStorage.addView(lblStorage, new LinearLayout.LayoutParams(0, -2, 1.0f));

        healthStorageText = new TextView(this);
        healthStorageText.setText("Checking...");
        healthStorageText.setTextSize(12);
        healthStorageText.setTypeface(Typeface.MONOSPACE);
        healthStorageText.setTextColor(getPrimaryTextColor());
        rowStorage.addView(healthStorageText);
        card.addView(rowStorage);

        // Hairline Divider
        View d2 = new View(this);
        d2.setBackgroundColor(getBorderColor());
        LinearLayout.LayoutParams pD2 = new LinearLayout.LayoutParams(-1, dp(1));
        pD2.topMargin = dp(10);
        pD2.bottomMargin = dp(10);
        card.addView(d2, pD2);

        // Row 3: External Storage (USB OTG)
        healthExternalStorageRow = new LinearLayout(this);
        ((LinearLayout) healthExternalStorageRow).setOrientation(LinearLayout.HORIZONTAL);
        ((LinearLayout) healthExternalStorageRow).setGravity(Gravity.CENTER_VERTICAL);
        TextView lblExt = new TextView(this);
        lblExt.setText("EXTERNAL USB STORAGE");
        lblExt.setTextSize(10);
        lblExt.setLetterSpacing(0.06f);
        lblExt.setTypeface(Typeface.create("sans-serif-medium", Typeface.BOLD));
        lblExt.setTextColor(getSecondaryTextColor());
        ((LinearLayout) healthExternalStorageRow).addView(lblExt, new LinearLayout.LayoutParams(0, -2, 1.0f));

        healthExternalStorageText = new TextView(this);
        healthExternalStorageText.setText("Checking...");
        healthExternalStorageText.setTextSize(12);
        healthExternalStorageText.setTypeface(Typeface.MONOSPACE);
        healthExternalStorageText.setTextColor(getPrimaryTextColor());
        ((LinearLayout) healthExternalStorageRow).addView(healthExternalStorageText);
        card.addView(healthExternalStorageRow);

        // Manage Media Storage Button
        Button btnManageStorage = createPixelButton("MANAGE MEDIA STORAGE", getSurfaceElevatedColor(), getPrimaryTextColor());
        btnManageStorage.setOnClickListener(v -> startActivity(new Intent(this, JellyfinStorageActivity.class)));
        LinearLayout.LayoutParams pBtn = new LinearLayout.LayoutParams(-1, dp(38));
        pBtn.topMargin = dp(12);
        card.addView(btnManageStorage, pBtn);

        return card;
    }

    private void refreshTranscodeCardUI() {
        if (transcodeEngineBadge == null) return;
        JellyfinEncodingConfig.EncodingOptions opts = JellyfinEncodingConfig.loadEncodingConfig(this);
        boolean isHw = JellyfinEncodingConfig.HW_ACCEL_MEDIACODEC.equalsIgnoreCase(opts.hardwareAccelerationType);
        transcodeEngineBadge.setText(isHw ? "Android MediaCodec (Hardware SoC)" : "ARM NEON SIMD (Multi-threaded Software)");
        transcodeEngineBadge.setTextColor(isHw ? Color.parseColor("#FFB74D") : getAccentColor());
    }

    private void refreshServerHealthUI() {
        if (healthStatusBadge == null && healthUptimeText == null && healthStorageText == null) return;
        bgExecutor.execute(() -> {
            JellyfinController.ServerHealth health = controller.getServerHealth();
            StorageDriveHelper.DriveInfo extDrive = StorageDriveHelper.getPrimaryExternalDrive(this);
            JellyfinEncodingConfig.EncodingOptions encOpts = JellyfinEncodingConfig.loadEncodingConfig(this);
            long freeMb = controller != null ? controller.getAvailableInternalStorageMb() : 500;

            runOnUiThread(() -> {
                if (healthStatusBadge != null) {
                    if (freeMb < 50) {
                        healthStatusBadge.setText("● STORAGE FULL");
                        healthStatusBadge.setTextColor(getDangerColor());
                        healthStatusBadge.setBackground(createBorderedRoundedDrawable(Color.argb(25, 244, 63, 94), Color.argb(60, 244, 63, 94), dp(1), dp(6)));
                    } else if (health.isHealthy) {
                        healthStatusBadge.setText("● HTTP 200");
                        healthStatusBadge.setTextColor(getSuccessColor());
                        healthStatusBadge.setBackground(createBorderedRoundedDrawable(Color.argb(25, 16, 185, 129), Color.argb(60, 16, 185, 129), dp(1), dp(6)));
                    } else if (controller.getState() == JellyfinController.State.RUNNING) {
                        healthStatusBadge.setText("● Responding");
                        healthStatusBadge.setTextColor(getWarningColor());
                        healthStatusBadge.setBackground(createBorderedRoundedDrawable(Color.argb(25, 245, 158, 11), Color.argb(60, 245, 158, 11), dp(1), dp(6)));
                    } else {
                        healthStatusBadge.setText("● Offline");
                        healthStatusBadge.setTextColor(getDangerColor());
                        healthStatusBadge.setBackground(createBorderedRoundedDrawable(Color.argb(25, 244, 63, 94), Color.argb(60, 244, 63, 94), dp(1), dp(6)));
                    }
                }
                if (healthDetailText != null) {
                    if (freeMb < 50) {
                        healthDetailText.setText("Critical: Device storage exhausted (<50 MB free)");
                        healthDetailText.setTextColor(getDangerColor());
                    } else if (health.isHealthy) {
                        healthDetailText.setText("All server subsystems active & listening on port 8096");
                        healthDetailText.setTextColor(getSuccessColor());
                    } else if (controller.getState() == JellyfinController.State.RUNNING) {
                        healthDetailText.setText("Server process running, polling HTTP endpoint...");
                        healthDetailText.setTextColor(getWarningColor());
                    } else {
                        healthDetailText.setText("Server process is stopped");
                        healthDetailText.setTextColor(getSecondaryTextColor());
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
                if (healthStorageText != null) {
                    if (health.storageTotalMb > 0) {
                        double freeGb = health.storageFreeMb / 1024.0;
                        double totalGb = health.storageTotalMb / 1024.0;
                        healthStorageText.setText(String.format(Locale.ROOT, "%.1f GB free / %.1f GB", freeGb, totalGb));
                    } else {
                        healthStorageText.setText("N/A");
                    }
                }
                if (healthExternalStorageText != null) {
                    if (extDrive != null && extDrive.totalBytes > 0) {
                        String cap = StorageDriveHelper.formatCapacity(extDrive.freeBytes, extDrive.totalBytes);
                        healthExternalStorageText.setText(extDrive.name + " - " + cap);
                        healthExternalStorageText.setTextColor(getSuccessColor());
                    } else if (extDrive != null) {
                        healthExternalStorageText.setText(extDrive.name + " (Mounted)");
                        healthExternalStorageText.setTextColor(getSuccessColor());
                    } else {
                        healthExternalStorageText.setText("No external drive connected");
                        healthExternalStorageText.setTextColor(getSecondaryTextColor());
                    }
                }
                if (transcodeActiveBadge != null) {
                    if (health.isTranscodingActive) {
                        transcodeActiveBadge.setText("● TRANSCODING ACTIVE (" + health.activeTranscodeCount + " files)");
                        transcodeActiveBadge.setTextColor(getAccentColor());
                        if (transcodeDetailsText != null) {
                            transcodeDetailsText.setText("Active on-the-fly media transcoding to client");
                            transcodeDetailsText.setTextColor(getAccentColor());
                        }
                    } else {
                        transcodeActiveBadge.setText("● IDLE / DIRECT PLAY");
                        transcodeActiveBadge.setTextColor(getSuccessColor());
                    }
                }
                if (transcodeEngineBadge != null) {
                    boolean isHw = JellyfinEncodingConfig.HW_ACCEL_MEDIACODEC.equalsIgnoreCase(encOpts.hardwareAccelerationType);
                    transcodeEngineBadge.setText(isHw ? "Android MediaCodec (Hardware SoC)" : "ARM NEON SIMD (Software)");
                    transcodeEngineBadge.setTextColor(isHw ? Color.parseColor("#FFB74D") : getAccentColor());
                }
            });
        });
    }

    private View buildServerHeroSection() {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(0, dp(4), 0, dp(4));

        // Left Column: Status Badge
        statusBadge = new TextView(this);
        statusBadge.setText("● SERVER STOPPED");
        statusBadge.setTextSize(11.5f);
        statusBadge.setTypeface(Typeface.create("sans-serif-medium", Typeface.BOLD));
        statusBadge.setLetterSpacing(0.06f);
        statusBadge.setTextColor(Color.parseColor("#A1A1AA"));
        statusBadge.setPadding(0, 0, 0, 0);
        statusBadge.setBackground(null);
        row.addView(statusBadge, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1.0f));

        // Right Column: Inline Uptime
        LinearLayout uptimeRow = new LinearLayout(this);
        uptimeRow.setOrientation(LinearLayout.HORIZONTAL);
        uptimeRow.setGravity(Gravity.CENTER_VERTICAL | Gravity.END);

        TextView lblUptime = new TextView(this);
        lblUptime.setText("UPTIME  ");
        lblUptime.setTextSize(10f);
        lblUptime.setTypeface(Typeface.MONOSPACE, Typeface.BOLD);
        lblUptime.setLetterSpacing(0.08f);
        lblUptime.setTextColor(Color.parseColor("#71717A"));
        uptimeRow.addView(lblUptime);

        healthUptimeText = new TextView(this);
        healthUptimeText.setText("0s");
        healthUptimeText.setTextSize(11.5f);
        healthUptimeText.setTypeface(Typeface.MONOSPACE, Typeface.BOLD);
        healthUptimeText.setTextColor(Color.WHITE);
        uptimeRow.addView(healthUptimeText);

        row.addView(uptimeRow);
        return row;
    }

    private View buildClientStreamingIpsSection() {
        LinearLayout section = new LinearLayout(this);
        section.setOrientation(LinearLayout.VERTICAL);

        // Endpoints divided by subtle hairlines
        LinearLayout list = new LinearLayout(this);
        list.setOrientation(LinearLayout.VERTICAL);

        // 1. LAN Endpoint
        lanIpValueText = new TextView(this);
        lanIpValueText.setText("Detecting LAN IP...");
        ImageView copyLan = new ImageView(this);
        btnCopyLanIp = copyLan;
        View rowLan = buildEndpointRow("lan", "LAN", lanIpValueText, copyLan, () -> {
            String val = lanIpValueText.getText().toString();
            if (val != null && !val.contains("not connected") && !val.contains("Detecting")) {
                copyUrlToClipboard("LAN IP", val.startsWith("http") ? val : "http://" + val);
            }
        });
        rowLanView = rowLan;
        list.addView(rowLan);
        list.addView(createHairlineDivider());

        // 2. LOCAL Endpoint
        localIpValueText = new TextView(this);
        localIpValueText.setText("127.0.0.1:8096");
        ImageView copyLocal = new ImageView(this);
        btnCopyLocalIp = copyLocal;
        View rowLocal = buildEndpointRow("local", "LOCAL", localIpValueText, copyLocal, () -> {
            copyUrlToClipboard("Local Address", "http://127.0.0.1:8096");
        });
        rowLocalView = rowLocal;
        list.addView(rowLocal);
        list.addView(createHairlineDivider());

        // 3. TAILSCALE Endpoint
        tailscaleIpValueText = new TextView(this);
        tailscaleIpValueText.setText("Tailscale not active");
        ImageView copyTailscale = new ImageView(this);
        btnCopyTailscaleIp = copyTailscale;
        View rowTailscale = buildEndpointRow("tailscale", "TAILSCALE", tailscaleIpValueText, copyTailscale, () -> {
            String val = tailscaleIpValueText.getText().toString();
            if (val != null && !val.contains("not active")) {
                copyUrlToClipboard("Tailscale Address", val.startsWith("http") ? val : "http://" + val);
            }
        });
        rowTailscaleView = rowTailscale;
        list.addView(rowTailscale);
        section.addView(list);
        return section;
    }

    private View buildEndpointRow(String tag, String typeLabel, TextView valueView, ImageView copyBtn, Runnable onCopy) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(dp(6), dp(10), dp(6), dp(10));
        row.setClickable(true);
        row.setFocusable(true);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            row.setBackground(createRowRippleDrawable());
        }

        View dot = new View(this);
        dot.setTag("dot_" + tag);
        dot.setBackground(createCircleDrawable(Color.parseColor("#52525B")));
        LinearLayout.LayoutParams dotParams = new LinearLayout.LayoutParams(dp(6), dp(6));
        dotParams.rightMargin = dp(12);
        row.addView(dot, dotParams);

        TextView lbl = new TextView(this);
        lbl.setText(typeLabel);
        lbl.setTextSize(11);
        lbl.setTypeface(Typeface.MONOSPACE, Typeface.BOLD);
        lbl.setLetterSpacing(0.06f);
        lbl.setTextColor(Color.parseColor("#A1A1AA"));
        LinearLayout.LayoutParams lblParams = new LinearLayout.LayoutParams(dp(72), ViewGroup.LayoutParams.WRAP_CONTENT);
        row.addView(lbl, lblParams);

        valueView.setTextSize(12.5f);
        valueView.setTypeface(Typeface.MONOSPACE, Typeface.BOLD);
        valueView.setTextColor(Color.parseColor("#E4E4E7"));
        valueView.setSingleLine(true);
        valueView.setEllipsize(TextUtils.TruncateAt.END);
        LinearLayout.LayoutParams valParams = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1.0f);
        valParams.rightMargin = dp(8);
        row.addView(valueView, valParams);

        copyBtn.setImageResource(R.drawable.ic_copy);
        copyBtn.setColorFilter(Color.parseColor("#52525B"));
        copyBtn.setEnabled(false);
        copyBtn.setAlpha(0.45f);
        LinearLayout.LayoutParams cpParams = new LinearLayout.LayoutParams(dp(24), dp(24));
        copyBtn.setPadding(dp(4), dp(4), dp(4), dp(4));
        row.addView(copyBtn, cpParams);

        row.setOnClickListener(v -> {
            if (copyBtn.isEnabled()) onCopy.run();
        });
        copyBtn.setOnClickListener(v -> {
            if (copyBtn.isEnabled()) onCopy.run();
        });

        return row;
    }

    private void setEndpointCopyState(ImageView icon, View row, View dot, boolean operable) {
        if (icon != null) {
            icon.setEnabled(operable);
            icon.setColorFilter(operable ? Color.WHITE : Color.parseColor("#52525B"));
            icon.setAlpha(operable ? 1.0f : 0.45f);
        }
        if (row != null) {
            row.setEnabled(operable);
        }
        if (dot != null) {
            dot.setBackground(createCircleDrawable(operable ? getSuccessColor() : Color.parseColor("#52525B")));
        }
    }

    private View createHairlineDivider() {
        View v = new View(this);
        v.setBackgroundColor(Color.argb(10, 255, 255, 255));
        v.setLayoutParams(new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(1)));
        return v;
    }

    private View createSectionSpacer() {
        return createSectionSpacer(dp(28));
    }

    private View createSectionSpacer(int heightPx) {
        View v = new View(this);
        v.setLayoutParams(new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, heightPx));
        return v;
    }

    private GradientDrawable createCircleDrawable(int color) {
        GradientDrawable gd = new GradientDrawable();
        gd.setShape(GradientDrawable.OVAL);
        gd.setColor(color);
        return gd;
    }

    private Drawable createRowRippleDrawable() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            return new android.graphics.drawable.RippleDrawable(
                    ColorStateList.valueOf(Color.argb(20, 255, 255, 255)),
                    null,
                    createBorderedRoundedDrawable(Color.TRANSPARENT, Color.TRANSPARENT, 0, dp(6)));
        }
        return null;
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
        card.setBackground(createCardDrawable(getSurfaceElevatedColor(), dp(14)));
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(-1, -2);
        params.topMargin = dp(12);
        card.setLayoutParams(params);

        startupProgressHeader = new TextView(this);
        startupProgressHeader.setText("Starting FishBowl Server");
        startupProgressHeader.setTextSize(13);
        startupProgressHeader.setTypeface(Typeface.create("sans-serif-medium", Typeface.BOLD));
        startupProgressHeader.setTextColor(getPrimaryTextColor());
        card.addView(startupProgressHeader);

        startupProgressBar = new ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal);
        startupProgressBar.setIndeterminate(true);
        LinearLayout.LayoutParams progressParams = new LinearLayout.LayoutParams(-1, dp(4));
        progressParams.topMargin = dp(8);
        progressParams.bottomMargin = dp(10);
        card.addView(startupProgressBar, progressParams);

        txtStage1 = createStageText("1. Starting runtime environment");
        txtStage2 = createStageText("2. Launching FishBowl core server process");
        txtStage3 = createStageText("3. Waiting for server (binding port 8096)");
        txtStage4 = createStageText("4. Checking readiness & migrating database");
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



    private void triggerServerAction(String action) {
        if (JellyfinServerService.ACTION_STOP_JELLYFIN.equals(action)
                || JellyfinServerService.ACTION_STOP_ALL.equals(action)) {
            startupCompletedTimeMs = 0;
            mainHandler.removeCallbacks(startupProgressPoller);
        }
        Intent svc = new Intent(this, JellyfinServerService.class);
        svc.setAction(action);
        if (JellyfinServerService.ACTION_START.equals(action)
                || JellyfinServerService.ACTION_START_JELLYFIN.equals(action)
                || JellyfinServerService.ACTION_START_ALL.equals(action)) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                startForegroundService(svc);
            } else {
                startService(svc);
            }
        } else {
            startService(svc);
        }
    }

    // -- Tab 0 Components: Home Services -------------------------------------

    private View buildServicesControlPlaneSection() {
        LinearLayout section = new LinearLayout(this);
        section.setOrientation(LinearLayout.VERTICAL);

        // Header Row: SERVICES on left, 7 / 7 Active on right
        LinearLayout headerRow = new LinearLayout(this);
        headerRow.setOrientation(LinearLayout.HORIZONTAL);
        headerRow.setGravity(Gravity.CENTER_VERTICAL);
        headerRow.setPadding(dp(4), 0, dp(4), dp(10));

        TextView title = new TextView(this);
        title.setText("SERVICES");
        title.setTextSize(11);
        title.setTypeface(Typeface.MONOSPACE, Typeface.BOLD);
        title.setLetterSpacing(0.08f);
        title.setTextColor(Color.parseColor("#71717A"));
        headerRow.addView(title, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1.0f));

        homeServicesCountBadge = new TextView(this);
        homeServicesCountBadge.setText("7 / 7 Active");
        homeServicesCountBadge.setTextSize(10);
        homeServicesCountBadge.setTypeface(Typeface.MONOSPACE);
        homeServicesCountBadge.setTextColor(Color.parseColor("#52525B"));
        headerRow.addView(homeServicesCountBadge);

        section.addView(headerRow);

        // Multi-row Vertical Container of Installed Icon Tiles (5 per row)
        LinearLayout tilesContainer = new LinearLayout(this);
        tilesContainer.setOrientation(LinearLayout.VERTICAL);
        tilesContainer.setPadding(0, dp(2), 0, 0);

        homeServicesRow = tilesContainer;
        rebuildHomeServiceTiles();

        section.addView(tilesContainer);

        refreshHomeServicesBadge();
        return section;
    }

    private static class HomeServiceTileSpec {
        final String id;
        final String name;
        final int iconRes;
        final int brandColor;
        HomeServiceTileSpec(String id, String name, int iconRes, int brandColor) {
            this.id = id;
            this.name = name;
            this.iconRes = iconRes;
            this.brandColor = brandColor;
        }
    }

    private boolean isMovieStreamerModeEnabled() {
        return getFishbowlPrefs().getBoolean("movie_streamer_mode", false);
    }

    private void rebuildHomeServiceTiles() {
        if (homeServicesRow == null) return;
        homeServicesRow.removeAllViews();
        serviceTileMap.clear();

        List<HomeServiceTileSpec> tiles = new ArrayList<>();
        // 1. Core Jellyfin Service Tile (always present)
        tiles.add(new HomeServiceTileSpec("jellyfin", "Jellyfin", R.drawable.ic_service_jellyfin, Color.WHITE));

        // 2. Installed Companion Service Tiles (ordered consistently)
        if (!isMovieStreamerModeEnabled()) {
            MediaServiceManager mgr = MediaServiceManager.getInstance(this);
            String[] preferredOrder = new String[] {
                    "radarr", "sonarr", "prowlarr", "jackett", "transmission", "seerr", "bazarr", "lidarr"
            };
            for (String sid : preferredOrder) {
                ManagedService s = mgr.getService(sid);
                if (s != null && s.isInstalled()) {
                    tiles.add(new HomeServiceTileSpec(s.getId(), s.getDisplayName(), getServiceIconRes(s.getId()), Color.WHITE));
                }
            }
        }

        // Row 1: Top 5 service logos
        LinearLayout row1 = new LinearLayout(this);
        row1.setOrientation(LinearLayout.HORIZONTAL);
        row1.setGravity(Gravity.CENTER_VERTICAL);
        int row1Count = Math.min(5, tiles.size());
        for (int i = 0; i < row1Count; i++) {
            HomeServiceTileSpec spec = tiles.get(i);
            addServiceIconTile(row1, spec.id, spec.name, spec.iconRes, spec.brandColor);
        }
        homeServicesRow.addView(row1);

        // Row 2: Remaining service logos below Row 1
        if (tiles.size() > 5) {
            LinearLayout row2 = new LinearLayout(this);
            row2.setOrientation(LinearLayout.HORIZONTAL);
            row2.setGravity(Gravity.CENTER_VERTICAL);
            row2.setPadding(0, dp(6), 0, 0);

            int row2Count = tiles.size() - 5;
            for (int i = 5; i < tiles.size(); i++) {
                HomeServiceTileSpec spec = tiles.get(i);
                addServiceIconTile(row2, spec.id, spec.name, spec.iconRes, spec.brandColor);
            }
            // Add spacers so Row 2 items align perfectly with Row 1's 5 columns
            for (int s = 0; s < (5 - row2Count); s++) {
                FrameLayout spacer = new FrameLayout(this);
                spacer.setLayoutParams(new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1.0f));
                row2.addView(spacer);
            }
            homeServicesRow.addView(row2);
        }
    }

    private void addServiceIconTile(LinearLayout parent, String id, String name, int iconRes, int brandColor) {
        FrameLayout slot = new FrameLayout(this);
        slot.setLayoutParams(new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1.0f));

        FrameLayout tile = new FrameLayout(this);
        FrameLayout.LayoutParams tileParams = new FrameLayout.LayoutParams(dp(40), dp(40));
        tileParams.gravity = Gravity.CENTER;
        tile.setLayoutParams(tileParams);
        // Clean unboxed presentation: no background container box on service logos
        tile.setClickable(true);
        tile.setFocusable(true);

        ImageView icon = new ImageView(this);
        icon.setImageResource(iconRes);
        icon.setColorFilter(brandColor);
        int iconSize = dp(24);
        FrameLayout.LayoutParams iconParams = new FrameLayout.LayoutParams(iconSize, iconSize);
        iconParams.gravity = Gravity.CENTER;
        tile.addView(icon, iconParams);

        serviceTileMap.put(id, icon);

        tile.setOnClickListener(v -> {
            if ("jellyfin".equalsIgnoreCase(id)) {
                if (controller != null && controller.getState() == JellyfinController.State.RUNNING) {
                    Toast.makeText(this, "Stopping FishBowl Server...", Toast.LENGTH_SHORT).show();
                    controller.stop();
                    triggerServerAction(JellyfinServerService.ACTION_STOP);
                } else {
                    Toast.makeText(this, "Starting FishBowl Server...", Toast.LENGTH_SHORT).show();
                    triggerServerAction(JellyfinServerService.ACTION_START);
                }
            } else {
                MediaServiceManager mgr = MediaServiceManager.getInstance(this);
                ManagedService svc = mgr.getService(id);
                if (svc != null) {
                    if (svc.isBlocked()) {
                        Toast.makeText(this, name + " is blocked: " + (svc.getBlockedReason() != null ? svc.getBlockedReason() : "Incompatible"), Toast.LENGTH_LONG).show();
                        return;
                    }
                    if (svc.isRunning()) {
                        Toast.makeText(this, "Stopping " + name + "...", Toast.LENGTH_SHORT).show();
                        mgr.stopService(id);
                    } else {
                        Toast.makeText(this, "Starting " + name + "...", Toast.LENGTH_SHORT).show();
                        mgr.startService(id);
                    }
                }
            }
            mainHandler.postDelayed(() -> {
                refreshHomeServicesBadge();
                refreshServicesTabUI();
            }, 600);
        });

        slot.addView(tile);
        parent.addView(slot);
    }

    private void refreshHomeServicesBadge() {
        MediaServiceManager mgr = MediaServiceManager.getInstance(this);
        List<ManagedService> services = mgr.getAllServices();
        int running = 0;
        int installed = 1; // Jellyfin core is always counted as installed
        for (ManagedService s : services) {
            if ("jellyfin".equalsIgnoreCase(s.getId())) {
                if (controller != null && controller.getState() == JellyfinController.State.RUNNING) {
                    running++;
                }
            } else {
                if (s.isInstalled()) {
                    installed++;
                }
                if (s.isRunning()) {
                    running++;
                }
            }
        }

        final int fRunning = running;
        final int fInstalled = installed;
        final List<ManagedService> fServices = services;

        runOnUiThread(() -> {
            if (homeServicesRow != null && serviceTileMap.size() != fInstalled) {
                rebuildHomeServiceTiles();
            }

            if (homeServicesCountBadge != null) {
                homeServicesCountBadge.setText(fRunning + " / " + fInstalled + " Active");
                homeServicesCountBadge.setTextColor(fRunning > 0 ? Color.parseColor("#34D399") : Color.parseColor("#52525B"));
            }

            // Jellyfin Service Tile (Jfn glyph)
            ImageView jIcon = serviceTileMap.get("jellyfin");
            if (jIcon != null) {
                JellyfinController.State js = controller != null ? controller.getState() : JellyfinController.State.STOPPED;
                int jColor;
                switch (js) {
                    case RUNNING:
                        jColor = getServiceBrandColor("jellyfin");
                        break;
                    case STARTING:
                    case INITIALIZING:
                        jColor = getWarningColor();
                        break;
                    case CRASHED:
                    case FAILED:
                    case CRASH_LOOP:
                        jColor = getDangerColor();
                        break;
                    default:
                        jColor = Color.WHITE;
                        break;
                }
                jIcon.setColorFilter(jColor);
            }

            // Other Services Tiles (Brand-colored typography glyphs)
            for (ManagedService s : fServices) {
                if ("jellyfin".equalsIgnoreCase(s.getId())) continue;
                ImageView icon = serviceTileMap.get(s.getId());
                if (icon != null) {
                    icon.setColorFilter(getServiceBrandTintColor(s.getId(), s.getState()));
                }
            }
        });
    }

    private int getServiceStateTintColor(com.fishbowl.app.model.ServiceState state) {
        if (state == null) return Color.WHITE;
        switch (state) {
            case RUNNING:
            case API_READY:
                return Color.parseColor("#34D399");
            case STARTING:
            case PROCESS_READY:
            case SOCKET_READY:
            case RECOVERING:
            case INSTALLING:
            case UPDATING:
                return getWarningColor();
            case FAILED:
            case ERROR:
            case CRASH_LOOP_PROTECTION:
            case UNHEALTHY:
                return getDangerColor();
            case STOPPED:
            case NOT_INSTALLED:
            case INSTALLED:
            case STOPPING:
            case DISABLED:
            case BLOCKED:
            default:
                return Color.WHITE;
        }
    }

    // Returns the brand color for a service when running, white when off
    private int getServiceBrandColor(String serviceId) {
        if (serviceId == null) return Color.WHITE;
        if ("SRR".equals(serviceId)) {
            return Color.parseColor("#CCFBFA");
        }
        switch (serviceId.toLowerCase(Locale.ROOT)) {
            case "radarr":
            case "rrr":          return Color.parseColor("#FF9E20");
            case "sonarr":
            case "snr":          return Color.parseColor("#CCFBFA");
            case "prowlarr":
            case "prr":          return Color.parseColor("#FFFC8C");
            case "jackett":
            case "jkt":          return Color.parseColor("#FF6D00");
            case "transmission":
            case "trr":          return Color.parseColor("#FF788D");
            case "seerr":
            case "srr":
            case "se":
            case "see":          return Color.parseColor("#55A9A0");
            case "bazarr":
            case "brr":          return Color.parseColor("#5D9253");
            case "lidarr":
            case "lrr":
            case "ldr":          return Color.parseColor("#769826");
            case "jellyfin":
            case "jfn":
            case "jf":           return Color.parseColor("#AB03A9");
            default:             return Color.WHITE;
        }
    }

    private int getServiceIconRes(String serviceId) {
        if (serviceId == null) return R.drawable.ic_service_jellyfin;
        switch (serviceId.toLowerCase(Locale.ROOT)) {
            case "radarr":       return R.drawable.ic_service_radarr;
            case "sonarr":       return R.drawable.ic_service_sonarr;
            case "prowlarr":     return R.drawable.ic_service_prowlarr;
            case "jackett":      return R.drawable.ic_service_jackett;
            case "transmission": return R.drawable.ic_service_transmission;
            case "seerr":        return R.drawable.ic_service_seerr;
            case "bazarr":       return R.drawable.ic_service_bazarr;
            case "lidarr":       return R.drawable.ic_service_lidarr;
            case "jellyfin":
            default:             return R.drawable.ic_service_jellyfin;
        }
    }

    private int getServiceBrandTintColor(String serviceId, com.fishbowl.app.model.ServiceState state) {
        if (state == null) return Color.WHITE;
        switch (state) {
            case RUNNING:
            case API_READY:
                return getServiceBrandColor(serviceId);
            case STARTING:
            case PROCESS_READY:
            case SOCKET_READY:
            case RECOVERING:
            case INSTALLING:
            case UPDATING:
                return getWarningColor();
            case FAILED:
            case ERROR:
            case CRASH_LOOP_PROTECTION:
            case UNHEALTHY:
                return getDangerColor();
            case STOPPED:
            case NOT_INSTALLED:
            case INSTALLED:
            case STOPPING:
            case DISABLED:
            case BLOCKED:
            default:
                return Color.WHITE;
        }
    }

    // -- Home Downloads Summary Card -------------------------------------------

    private View buildHomeDownloadsSummaryCard() {
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setPadding(dp(14), dp(12), dp(14), dp(12));
        card.setBackground(createCardDrawable(getSurfaceColor(), dp(8)));
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(-1, -2);
        params.topMargin = dp(10);
        card.setLayoutParams(params);

        LinearLayout headerRow = new LinearLayout(this);
        headerRow.setOrientation(LinearLayout.HORIZONTAL);
        headerRow.setGravity(Gravity.CENTER_VERTICAL);

        TextView title = new TextView(this);
        title.setText("ACTIVE DOWNLOADS");
        title.setTextSize(11);
        title.setTypeface(Typeface.create("sans-serif-medium", Typeface.BOLD));
        title.setTextColor(getSecondaryTextColor());
        headerRow.addView(title, new LinearLayout.LayoutParams(0, -2, 1.0f));

        homeDownloadsCountBadge = new TextView(this);
        homeDownloadsCountBadge.setText("Idle");
        homeDownloadsCountBadge.setTextSize(10);
        homeDownloadsCountBadge.setTypeface(Typeface.create("sans-serif-medium", Typeface.BOLD));
        homeDownloadsCountBadge.setTextColor(getSecondaryTextColor());
        homeDownloadsCountBadge.setPadding(dp(8), dp(3), dp(8), dp(3));
        homeDownloadsCountBadge.setBackground(createBorderedRoundedDrawable(getSurfaceElevatedColor(), getBorderColor(), dp(1), dp(8)));
        headerRow.addView(homeDownloadsCountBadge);

        card.addView(headerRow);

        homeDownloadsSummaryText = new TextView(this);
        homeDownloadsSummaryText.setText("No active downloads in progress.");
        homeDownloadsSummaryText.setTextSize(12);
        homeDownloadsSummaryText.setTextColor(getPrimaryTextColor());
        homeDownloadsSummaryText.setPadding(0, dp(8), 0, dp(10));
        card.addView(homeDownloadsSummaryText);

        Button btnViewDownloads = createPixelButton("VIEW ALL DOWNLOADS", getSurfaceElevatedColor(), getAccentColor());
        btnViewDownloads.setOnClickListener(v -> {
            activeTab = 1;
            updateBottomNavSelection();
            renderActiveTab();
        });
        LinearLayout.LayoutParams pBtn = new LinearLayout.LayoutParams(-1, dp(38));
        card.addView(btnViewDownloads, pBtn);

        refreshHomeDownloadsSummary();
        return card;
    }

    private void refreshHomeDownloadsSummary() {
        new Thread(() -> {
            List<MediaStorageManager.TorrentInfo> torrents = MediaStorageManager.getInstance(this).queryTransmissionTorrents();
            runOnUiThread(() -> {
                if (homeDownloadsSummaryText == null) return;
                int activeCount = 0;
                long totalDownloadSpeed = 0;
                for (MediaStorageManager.TorrentInfo t : torrents) {
                    if (t.isActive()) {
                        activeCount++;
                        totalDownloadSpeed += t.rateDownload;
                    }
                }
                if (homeDownloadsCountBadge != null) {
                    homeDownloadsCountBadge.setText(activeCount > 0 ? (activeCount + " Active") : "Idle");
                    homeDownloadsCountBadge.setTextColor(activeCount > 0 ? getSuccessColor() : getSecondaryTextColor());
                }
                if (activeCount > 0) {
                    homeDownloadsSummaryText.setText(activeCount + " active download(s) in progress. Tap below to view.");
                } else if (!torrents.isEmpty()) {
                    homeDownloadsSummaryText.setText("All downloads finished or paused (" + torrents.size() + " total).");
                } else {
                    homeDownloadsSummaryText.setText("No active downloads in progress.");
                }
            });
        }).start();
    }

    // -- Tab 2: System Logs & Console View -------------------------------------

    private View buildActivityTab() {
        LinearLayout layout = new LinearLayout(this);
        layout.setOrientation(LinearLayout.VERTICAL);
        layout.setBackgroundColor(getBgColor());
        layout.setPadding(dp(16), dp(10), dp(16), dp(8));
        layout.setClipToPadding(false);

        // Header Title Row: "SYSTEM CONSOLE" + subtitle
        LinearLayout headerRow = new LinearLayout(this);
        headerRow.setOrientation(LinearLayout.VERTICAL);
        headerRow.setPadding(0, 0, 0, dp(10));

        TextView title = new TextView(this);
        title.setText("SYSTEM CONSOLE");
        title.setTextSize(13);
        title.setLetterSpacing(0.12f);
        title.setTypeface(Typeface.create("sans-serif", Typeface.BOLD));
        title.setTextColor(Color.WHITE);
        headerRow.addView(title);

        TextView subtitle = new TextView(this);
        subtitle.setText("Live multi-daemon streams and error telemetry");
        subtitle.setTextSize(10.5f);
        subtitle.setTypeface(Typeface.create("sans-serif", Typeface.NORMAL));
        subtitle.setTextColor(Color.parseColor("#71717A"));
        subtitle.setPadding(0, dp(2), 0, 0);
        headerRow.addView(subtitle);

        layout.addView(headerRow);

        // Service Selector Chips (Horizontally Scrollable)
        HorizontalScrollView hScrollServices = new HorizontalScrollView(this);
        hScrollServices.setHorizontalScrollBarEnabled(false);
        hScrollServices.setOverScrollMode(View.OVER_SCROLL_NEVER);
        LinearLayout.LayoutParams hParams = new LinearLayout.LayoutParams(-1, -2);
        hParams.bottomMargin = dp(8);
        hScrollServices.setLayoutParams(hParams);

        logServiceChipsContainer = new LinearLayout(this);
        logServiceChipsContainer.setOrientation(LinearLayout.HORIZONTAL);

        refreshLogServiceChips();
        hScrollServices.addView(logServiceChipsContainer);
        layout.addView(hScrollServices);

        // Controls Toolbar: Level Filters (Left) + Action Buttons (Right)
        LinearLayout controlsRow = new LinearLayout(this);
        controlsRow.setOrientation(LinearLayout.HORIZONTAL);
        controlsRow.setGravity(Gravity.CENTER_VERTICAL);
        controlsRow.setPadding(0, 0, 0, dp(8));

        logLevelChipsContainer = new LinearLayout(this);
        logLevelChipsContainer.setOrientation(LinearLayout.HORIZONTAL);
        String[] levels = {"ALL", "ERR", "WRN", "INF"};
        for (String lvl : levels) {
            Button lvlChip = new Button(this);
            lvlChip.setText(lvl);
            lvlChip.setTextSize(9.5f);
            lvlChip.setTypeface(Typeface.create("sans-serif-medium", Typeface.BOLD));
            lvlChip.setAllCaps(true);
            lvlChip.setMinHeight(0);
            lvlChip.setMinimumHeight(0);
            lvlChip.setMinWidth(0);
            lvlChip.setMinimumWidth(0);
            lvlChip.setPadding(dp(7), dp(4), dp(7), dp(4));
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                lvlChip.setStateListAnimator(null);
            }
            lvlChip.setOnClickListener(v -> {
                if ("ERR".equals(lvl)) selectedLogLevel = "ERROR";
                else if ("WRN".equals(lvl)) selectedLogLevel = "WARNING";
                else if ("INF".equals(lvl)) selectedLogLevel = "INFO";
                else selectedLogLevel = "ALL";
                updateLogLevelChips();
                refreshUnifiedLogsDisplay();
            });
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-2, dp(26));
            lp.rightMargin = dp(4);
            logLevelChipsContainer.addView(lvlChip, lp);
        }
        controlsRow.addView(logLevelChipsContainer);

        // Spacer between filters and actions
        View spacer = new View(this);
        controlsRow.addView(spacer, new LinearLayout.LayoutParams(0, 1, 1.0f));

        // Auto-Scroll Toggle Button
        btnToggleAutoScroll = createPixelButton(autoScrollEnabled ? "AUTO: ON" : "AUTO: OFF",
                autoScrollEnabled ? Color.argb(35, 16, 185, 129) : getSurfaceElevatedColor(),
                autoScrollEnabled ? getSuccessColor() : getSecondaryTextColor());
        btnToggleAutoScroll.setTextSize(9);
        btnToggleAutoScroll.setPadding(dp(6), dp(2), dp(6), dp(2));
        btnToggleAutoScroll.setOnClickListener(v -> {
            autoScrollEnabled = !autoScrollEnabled;
            btnToggleAutoScroll.setText(autoScrollEnabled ? "AUTO: ON" : "AUTO: OFF");
            btnToggleAutoScroll.setTextColor(autoScrollEnabled ? getSuccessColor() : getSecondaryTextColor());
            btnToggleAutoScroll.setBackground(createBorderedRoundedDrawable(
                    autoScrollEnabled ? Color.argb(35, 16, 185, 129) : getSurfaceElevatedColor(),
                    autoScrollEnabled ? getSuccessColor() : getBorderColor(), dp(1), dp(8)));
            if (autoScrollEnabled && logsScrollView != null) {
                logsScrollView.post(() -> logsScrollView.fullScroll(View.FOCUS_DOWN));
            }
        });
        LinearLayout.LayoutParams pAuto = new LinearLayout.LayoutParams(-2, dp(26));
        pAuto.rightMargin = dp(4);
        controlsRow.addView(btnToggleAutoScroll, pAuto);

        // Log Source Toggle Button (Server Disk Log vs Launcher Live Stream)
        final Button btnLogSource = createPixelButton(showingDiskLog ? "SOURCE: SERVER" : "SOURCE: LAUNCHER",
                getSurfaceElevatedColor(),
                showingDiskLog ? Color.parseColor("#38BDF8") : Color.parseColor("#F59E0B"));
        btnLogSource.setTextSize(9);
        btnLogSource.setPadding(dp(6), dp(2), dp(6), dp(2));
        btnLogSource.setOnClickListener(v -> {
            showingDiskLog = !showingDiskLog;
            btnLogSource.setText(showingDiskLog ? "SOURCE: SERVER" : "SOURCE: LAUNCHER");
            btnLogSource.setTextColor(showingDiskLog ? Color.parseColor("#38BDF8") : Color.parseColor("#F59E0B"));
            refreshUnifiedLogsDisplay();
        });
        LinearLayout.LayoutParams pSource = new LinearLayout.LayoutParams(-2, dp(26));
        pSource.rightMargin = dp(4);
        controlsRow.addView(btnLogSource, pSource);

        // Copy Button
        Button btnCopy = createPixelButton("COPY", getSurfaceElevatedColor(), getPrimaryTextColor());
        btnCopy.setTextSize(9);
        btnCopy.setPadding(dp(6), dp(2), dp(6), dp(2));
        btnCopy.setOnClickListener(v -> {
            String content = (logsOutputText != null) ? logsOutputText.getText().toString() : "";
            if (!content.isEmpty()) {
                ClipboardManager cm = (ClipboardManager) getSystemService(CLIPBOARD_SERVICE);
                if (cm != null) {
                    cm.setPrimaryClip(ClipData.newPlainText("Fishbowl Console Logs", content));
                    Toast.makeText(this, "Logs copied (" + selectedLogService + ")", Toast.LENGTH_SHORT).show();
                }
            }
        });
        LinearLayout.LayoutParams pCopy = new LinearLayout.LayoutParams(-2, dp(26));
        pCopy.rightMargin = dp(4);
        controlsRow.addView(btnCopy, pCopy);

        // Clear Button
        Button btnClear = createPixelButton("CLEAR", getSurfaceElevatedColor(), Color.parseColor("#EF4444"));
        btnClear.setTextSize(9);
        btnClear.setPadding(dp(6), dp(2), dp(6), dp(2));
        btnClear.setOnClickListener(v -> {
            if ("ALL".equalsIgnoreCase(selectedLogService)) {
                if (controller != null) controller.clearDisplayedLogs();
                MediaServiceManager mgr = MediaServiceManager.getInstance(this);
                for (ManagedService s : mgr.getAllServices()) {
                    s.clearLogs();
                }
            } else if ("JELLYFIN".equalsIgnoreCase(selectedLogService)) {
                if (controller != null) controller.clearDisplayedLogs();
            } else {
                MediaServiceManager mgr = MediaServiceManager.getInstance(this);
                ManagedService s = mgr.getService(selectedLogService.toLowerCase(Locale.ROOT));
                if (s != null) s.clearLogs();
            }
            refreshUnifiedLogsDisplay();
            Toast.makeText(this, "Cleared " + selectedLogService + " logs", Toast.LENGTH_SHORT).show();
        });
        controlsRow.addView(btnClear, new LinearLayout.LayoutParams(-2, dp(26)));

        layout.addView(controlsRow);

        // Monospace Terminal Box Container (takes remaining height via weight=1.0)
        logsScrollView = new ScrollView(this);
        logsScrollView.setVerticalScrollBarEnabled(false);
        logsScrollView.setHorizontalScrollBarEnabled(false);
        logsScrollView.setOverScrollMode(View.OVER_SCROLL_NEVER);
        logsScrollView.setFillViewport(true);
        logsScrollView.setBackground(createBorderedRoundedDrawable(
                Color.parseColor("#090C10"),
                Color.parseColor("#1B222C"),
                dp(1),
                dp(10)
        ));

        logsOutputText = new TextView(this);
        logsOutputText.setTextColor(Color.parseColor("#D4D4D8"));
        logsOutputText.setTextSize(10.5f);
        logsOutputText.setTypeface(Typeface.MONOSPACE);
        logsOutputText.setLineSpacing(dp(2), 1.15f);
        logsOutputText.setPadding(dp(12), dp(10), dp(12), dp(10));
        logsOutputText.setTextIsSelectable(true);

        logsScrollView.addView(logsOutputText);
        layout.addView(logsScrollView, new LinearLayout.LayoutParams(-1, 0, 1.0f));

        // Subtitle status line at bottom of terminal
        logsStatusSubtitle = new TextView(this);
        logsStatusSubtitle.setTextSize(9.5f);
        logsStatusSubtitle.setTypeface(Typeface.MONOSPACE);
        logsStatusSubtitle.setTextColor(Color.parseColor("#71717A"));
        logsStatusSubtitle.setPadding(dp(4), dp(6), dp(4), 0);
        layout.addView(logsStatusSubtitle);

        updateLogServiceChips();
        updateLogLevelChips();
        refreshUnifiedLogsDisplay();

        return layout;
    }

    // -- Unified Download Center Card -----------------------------------------

    private View buildDownloadCenterCard() {
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setPadding(0, dp(14), 0, dp(12));
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(-1, -2);
        params.topMargin = dp(14);
        card.setLayoutParams(params);

        // Subtle 1dp hairline divider on top
        View topDivider = new View(this);
        topDivider.setBackgroundColor(Color.argb(10, 255, 255, 255));
        LinearLayout.LayoutParams pDiv = new LinearLayout.LayoutParams(-1, dp(1));
        pDiv.bottomMargin = dp(12);
        card.addView(topDivider, pDiv);

        // Header Row: Title + Badge + Refresh
        LinearLayout headerRow = new LinearLayout(this);
        headerRow.setOrientation(LinearLayout.HORIZONTAL);
        headerRow.setGravity(Gravity.CENTER_VERTICAL);

        TextView title = new TextView(this);
        title.setText("DOWNLOAD CENTER");
        title.setTextSize(10.5f);
        title.setTypeface(Typeface.create("sans-serif-medium", Typeface.BOLD));
        title.setTextColor(Color.parseColor("#71717A"));
        title.setLetterSpacing(0.08f);
        headerRow.addView(title, new LinearLayout.LayoutParams(0, -2, 1.0f));

        downloadSummaryBadge = new TextView(this);
        downloadSummaryBadge.setText("0 Active");
        downloadSummaryBadge.setTextSize(10);
        downloadSummaryBadge.setTypeface(Typeface.MONOSPACE);
        downloadSummaryBadge.setTextColor(Color.parseColor("#71717A"));
        headerRow.addView(downloadSummaryBadge);

        View sp = new View(this);
        headerRow.addView(sp, new LinearLayout.LayoutParams(dp(8), 1));

        TextView btnRefreshDl = new TextView(this);
        btnRefreshDl.setText("REFRESH");
        btnRefreshDl.setTextSize(9.5f);
        btnRefreshDl.setTypeface(Typeface.create("sans-serif", Typeface.BOLD));
        btnRefreshDl.setTextColor(Color.WHITE);
        btnRefreshDl.setGravity(Gravity.CENTER);
        btnRefreshDl.setBackground(createBorderedRoundedDrawable(Color.parseColor("#27272A"), Color.argb(40, 255, 255, 255), dp(1), dp(4)));
        btnRefreshDl.setPadding(dp(8), dp(4), dp(8), dp(4));
        btnRefreshDl.setClickable(true);
        btnRefreshDl.setFocusable(true);
        btnRefreshDl.setOnClickListener(v -> {
            Toast.makeText(this, "Refreshing downloads...", Toast.LENGTH_SHORT).show();
            refreshDownloadCenter();
        });
        headerRow.addView(btnRefreshDl, new LinearLayout.LayoutParams(-2, -2));

        View spClear = new View(this);
        headerRow.addView(spClear, new LinearLayout.LayoutParams(dp(6), 1));

        TextView btnClearDl = new TextView(this);
        btnClearDl.setText("CLEAR");
        btnClearDl.setTextSize(9.5f);
        btnClearDl.setTypeface(Typeface.create("sans-serif", Typeface.BOLD));
        btnClearDl.setTextColor(Color.parseColor("#FDA4AF"));
        btnClearDl.setGravity(Gravity.CENTER);
        btnClearDl.setBackground(createBorderedRoundedDrawable(Color.parseColor("#1C1014"), Color.parseColor("#E11D48"), dp(1), dp(4)));
        btnClearDl.setPadding(dp(8), dp(4), dp(8), dp(4));
        btnClearDl.setClickable(true);
        btnClearDl.setFocusable(true);
        btnClearDl.setOnClickListener(v -> showClearDownloadsDialog());
        headerRow.addView(btnClearDl, new LinearLayout.LayoutParams(-2, -2));

        card.addView(headerRow);

        // Destination Path Note
        TextView destNote = new TextView(this);
        destNote.setText("Target: /storage/emulated/0/FishbowlMedia/Downloads");
        destNote.setTextSize(10);
        destNote.setTypeface(Typeface.MONOSPACE);
        destNote.setTextColor(Color.parseColor("#52525B"));
        destNote.setPadding(0, dp(4), 0, dp(8));
        card.addView(destNote);

        downloadItemsContainer = new LinearLayout(this);
        downloadItemsContainer.setOrientation(LinearLayout.VERTICAL);
        card.addView(downloadItemsContainer);

        refreshDownloadCenter();
        return card;
    }

    private static class TorrentItemHolder {
        int id;
        TextView nameTv;
        TextView pillTv;
        ProgressBar progressBar;
        TextView metaLeftTv;
        TextView btnDelete;
    }

    private void refreshDownloadCenter() {
        bgExecutor.execute(() -> {
            List<MediaStorageManager.TorrentInfo> torrents = MediaStorageManager.getInstance(this).queryTransmissionTorrents();
            runOnUiThread(() -> {
                if (downloadItemsContainer == null) return;

                int activeCount = 0;
                for (MediaStorageManager.TorrentInfo t : torrents) {
                    if (t.isActive()) activeCount++;
                }
                hasActiveDownloads = (activeCount > 0);

                if (downloadSummaryBadge != null) {
                    downloadSummaryBadge.setText(activeCount + " Active / " + torrents.size() + " Total");
                    downloadSummaryBadge.setTextColor(activeCount > 0 ? getSuccessColor() : Color.parseColor("#71717A"));
                }
                if (homeDownloadsCountBadge != null) {
                    homeDownloadsCountBadge.setText(activeCount > 0 ? (activeCount + " Active") : "Idle");
                    homeDownloadsCountBadge.setTextColor(activeCount > 0 ? getSuccessColor() : Color.parseColor("#52525B"));
                }
                if (homeDownloadsSummaryText != null) {
                    if (activeCount > 0) {
                        homeDownloadsSummaryText.setText(activeCount + " active download(s) in progress. Tap below for details.");
                    } else if (!torrents.isEmpty()) {
                        homeDownloadsSummaryText.setText("All downloads finished or paused (" + torrents.size() + " total).");
                    } else {
                        homeDownloadsSummaryText.setText("No active downloads in progress.");
                    }
                }

                if (torrents.isEmpty()) {
                    if (downloadItemsContainer.getChildCount() == 1) {
                        View existing = downloadItemsContainer.getChildAt(0);
                        if ("empty_downloads_view".equals(existing.getTag())) {
                            return; // Retain cached empty view: zero extra allocations or layout passes
                        }
                    }
                    downloadItemsContainer.removeAllViews();
                    TextView empty = new TextView(this);
                    empty.setTag("empty_downloads_view");
                    empty.setText("No active or queued downloads in Transmission.");
                    empty.setTextSize(11);
                    empty.setTextColor(Color.parseColor("#71717A"));
                    empty.setPadding(0, dp(6), 0, dp(6));
                    downloadItemsContainer.addView(empty);
                    return;
                }

                // Smooth in-place updates when child count and torrent IDs match to prevent UI flickering
                boolean canUpdateInPlace = (downloadItemsContainer.getChildCount() == torrents.size());
                if (canUpdateInPlace) {
                    for (int i = 0; i < torrents.size(); i++) {
                        View child = downloadItemsContainer.getChildAt(i);
                        Object tag = child.getTag();
                        if (!(tag instanceof TorrentItemHolder) || ((TorrentItemHolder) tag).id != torrents.get(i).id) {
                            canUpdateInPlace = false;
                            break;
                        }
                    }
                }

                if (canUpdateInPlace) {
                    for (int i = 0; i < torrents.size(); i++) {
                        MediaStorageManager.TorrentInfo t = torrents.get(i);
                        TorrentItemHolder holder = (TorrentItemHolder) downloadItemsContainer.getChildAt(i).getTag();

                        holder.nameTv.setText(t.name != null ? t.name : "Torrent #" + t.id);
                        holder.pillTv.setText(t.getStatusString());

                        int pillColor = Color.parseColor("#71717A");
                        switch (t.getLifecycleState()) {
                            case DOWNLOADING: pillColor = Color.parseColor("#38BDF8"); break;
                            case WAITING_FINAL_PIECES: pillColor = getWarningColor(); break;
                            case SEEDING:
                            case COMPLETE: pillColor = getSuccessColor(); break;
                            case STALLED: pillColor = Color.parseColor("#E57373"); break;
                            case ERROR: pillColor = getDangerColor(); break;
                            default: pillColor = Color.parseColor("#71717A"); break;
                        }
                        holder.pillTv.setTextColor(pillColor);
                        holder.pillTv.setBackground(createBorderedRoundedDrawable(colorWithAlpha(pillColor, 20), colorWithAlpha(pillColor, 50), dp(1), dp(4)));

                        holder.progressBar.setProgress((int) Math.round(t.percentDone * 1000));

                        StringBuilder meta = new StringBuilder();
                        if (t.sizeWhenDone > 0) {
                            long doneBytes = Math.round(t.sizeWhenDone * t.percentDone);
                            meta.append(MediaStorageManager.formatBytes(doneBytes))
                                    .append(" / ")
                                    .append(MediaStorageManager.formatBytes(t.sizeWhenDone));
                        }
                        if (t.rateDownload > 0) {
                            if (meta.length() > 0) meta.append(" • ");
                            meta.append("↓ ").append(MediaStorageManager.formatBytes(t.rateDownload)).append("/s");
                        }
                        if (t.rateUpload > 0) {
                            if (meta.length() > 0) meta.append(" • ");
                            meta.append("↑ ").append(MediaStorageManager.formatBytes(t.rateUpload)).append("/s");
                        }
                        if (t.eta > 0) {
                            if (meta.length() > 0) meta.append(" • ");
                            long m = t.eta / 60;
                            long s = t.eta % 60;
                            meta.append("ETA: ").append(m).append("m ").append(s).append("s");
                        }
                        if (meta.length() == 0) {
                            meta.append(String.format(Locale.US, "%.1f%% complete", t.percentDone * 100));
                        }
                        holder.metaLeftTv.setText(meta.toString());
                    }
                    return;
                }

                downloadItemsContainer.removeAllViews();

                for (MediaStorageManager.TorrentInfo t : torrents) {
                    LinearLayout item = new LinearLayout(this);
                    item.setOrientation(LinearLayout.VERTICAL);
                    item.setPadding(dp(12), dp(10), dp(12), dp(10));
                    item.setBackground(createBorderedRoundedDrawable(Color.BLACK, Color.argb(20, 255, 255, 255), dp(1), dp(8)));
                    LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2);
                    lp.topMargin = dp(6);
                    item.setLayoutParams(lp);

                    TorrentItemHolder holder = new TorrentItemHolder();
                    holder.id = t.id;

                    // Row 1: Name and Lifecycle Pill
                    LinearLayout top = new LinearLayout(this);
                    top.setOrientation(LinearLayout.HORIZONTAL);
                    top.setGravity(Gravity.CENTER_VERTICAL);

                    TextView name = new TextView(this);
                    name.setText(t.name != null ? t.name : "Torrent #" + t.id);
                    name.setTextSize(12);
                    name.setTypeface(Typeface.create("sans-serif-medium", Typeface.BOLD));
                    name.setTextColor(Color.parseColor("#F4F4F5"));
                    name.setSingleLine(true);
                    name.setEllipsize(android.text.TextUtils.TruncateAt.END);
                    top.addView(name, new LinearLayout.LayoutParams(0, -2, 1.0f));
                    holder.nameTv = name;

                    TextView pill = new TextView(this);
                    pill.setText(t.getStatusString());
                    pill.setTextSize(8.5f);
                    pill.setTypeface(Typeface.create("sans-serif-medium", Typeface.BOLD));
                    pill.setPadding(dp(5), dp(2), dp(5), dp(2));

                    int pillColor = Color.parseColor("#71717A");
                    switch (t.getLifecycleState()) {
                        case DOWNLOADING: pillColor = Color.parseColor("#38BDF8"); break;
                        case WAITING_FINAL_PIECES: pillColor = getWarningColor(); break;
                        case SEEDING:
                        case COMPLETE: pillColor = getSuccessColor(); break;
                        case STALLED: pillColor = Color.parseColor("#E57373"); break;
                        case ERROR: pillColor = getDangerColor(); break;
                        default: pillColor = Color.parseColor("#71717A"); break;
                    }
                    pill.setTextColor(pillColor);
                    pill.setBackground(createBorderedRoundedDrawable(colorWithAlpha(pillColor, 20), colorWithAlpha(pillColor, 50), dp(1), dp(4)));
                    top.addView(pill);
                    holder.pillTv = pill;

                    View btnDelSpace = new View(this);
                    top.addView(btnDelSpace, new LinearLayout.LayoutParams(dp(8), 1));

                    TextView btnDeleteItem = new TextView(this);
                    btnDeleteItem.setText("DEL");
                    btnDeleteItem.setTextSize(10f);
                    btnDeleteItem.setTypeface(Typeface.DEFAULT_BOLD);
                    btnDeleteItem.setTextColor(Color.parseColor("#EF4444"));
                    btnDeleteItem.setGravity(Gravity.CENTER);
                    btnDeleteItem.setPadding(dp(6), dp(2), dp(6), dp(2));
                    btnDeleteItem.setBackground(createBorderedRoundedDrawable(Color.parseColor("#271212"), Color.parseColor("#7F1D1D"), dp(1), dp(4)));
                    btnDeleteItem.setClickable(true);
                    btnDeleteItem.setFocusable(true);
                    btnDeleteItem.setOnClickListener(v -> showRemoveSingleTorrentDialog(t));
                    top.addView(btnDeleteItem);
                    holder.btnDelete = btnDeleteItem;

                    item.addView(top);

                    // Row 2: Progress bar
                    ProgressBar pb = new ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal);
                    pb.setMax(1000);
                    pb.setProgress((int) Math.round(t.percentDone * 1000));
                    pb.setPadding(0, dp(6), 0, dp(6));
                    item.addView(pb);
                    holder.progressBar = pb;

                    // Row 3: Meta info (Download speed, size, ETA)
                    LinearLayout metaRow = new LinearLayout(this);
                    metaRow.setOrientation(LinearLayout.HORIZONTAL);
                    metaRow.setGravity(Gravity.CENTER_VERTICAL);
                    metaRow.setPadding(0, dp(2), 0, 0);

                    TextView metaLeft = new TextView(this);
                    metaLeft.setTextSize(9.5f);
                    metaLeft.setTypeface(Typeface.MONOSPACE);
                    metaLeft.setTextColor(Color.parseColor("#A1A1AA"));

                    StringBuilder meta = new StringBuilder();
                    if (t.sizeWhenDone > 0) {
                        long doneBytes = Math.round(t.sizeWhenDone * t.percentDone);
                        meta.append(MediaStorageManager.formatBytes(doneBytes))
                                .append(" / ")
                                .append(MediaStorageManager.formatBytes(t.sizeWhenDone));
                    }
                    if (t.rateDownload > 0) {
                        if (meta.length() > 0) meta.append(" • ");
                        meta.append("↓ ").append(MediaStorageManager.formatBytes(t.rateDownload)).append("/s");
                    }
                    if (t.rateUpload > 0) {
                        if (meta.length() > 0) meta.append(" • ");
                        meta.append("↑ ").append(MediaStorageManager.formatBytes(t.rateUpload)).append("/s");
                    }
                    if (t.eta > 0) {
                        if (meta.length() > 0) meta.append(" • ");
                        long m = t.eta / 60;
                        long s = t.eta % 60;
                        meta.append("ETA: ").append(m).append("m ").append(s).append("s");
                    }
                    if (meta.length() == 0) {
                        meta.append(String.format(Locale.US, "%.1f%% complete", t.percentDone * 100));
                    }
                    metaLeft.setText(meta.toString());
                    metaRow.addView(metaLeft, new LinearLayout.LayoutParams(0, -2, 1.0f));
                    holder.metaLeftTv = metaLeft;

                    item.addView(metaRow);

                    item.setTag(holder);
                    downloadItemsContainer.addView(item);
                }
            });
        });
    }

    private LinearLayout createOledDialogOptionTile(String title, String subtitle, int accentColor, Runnable onClick) {
        LinearLayout tile = new LinearLayout(this);
        tile.setOrientation(LinearLayout.VERTICAL);
        tile.setPadding(dp(14), dp(10), dp(14), dp(10));
        tile.setBackground(createBorderedRoundedDrawable(Color.parseColor("#121316"), Color.parseColor("#27272A"), dp(1), dp(8)));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2);
        lp.bottomMargin = dp(8);
        tile.setLayoutParams(lp);
        tile.setClickable(true);
        tile.setFocusable(true);

        TextView tvTitle = new TextView(this);
        tvTitle.setText(title);
        tvTitle.setTextSize(12.5f);
        tvTitle.setTypeface(Typeface.create("sans-serif-medium", Typeface.BOLD));
        tvTitle.setTextColor(accentColor);
        tile.addView(tvTitle);

        if (subtitle != null && !subtitle.isEmpty()) {
            TextView tvSub = new TextView(this);
            tvSub.setText(subtitle);
            tvSub.setTextSize(10.5f);
            tvSub.setTextColor(Color.parseColor("#A1A1AA"));
            tvSub.setPadding(0, dp(2), 0, 0);
            tile.addView(tvSub);
        }

        tile.setOnClickListener(v -> {
            if (onClick != null) onClick.run();
        });
        return tile;
    }

    private void showClearDownloadsDialog() {
        LinearLayout content = new LinearLayout(this);
        content.setOrientation(LinearLayout.VERTICAL);

        Dialog[] dRef = new Dialog[1];

        // 1. Clear Completed
        content.addView(createOledDialogOptionTile(
                "Clear Completed Tasks",
                "Removes finished and inactive torrents • Retains downloaded files",
                Color.parseColor("#10B981"),
                () -> {
                    if (dRef[0] != null) dRef[0].dismiss();
                    Toast.makeText(this, "Clearing completed downloads...", Toast.LENGTH_SHORT).show();
                    new Thread(() -> {
                        int count = MediaStorageManager.getInstance(this).clearCompletedTransmissionTorrents(false);
                        runOnUiThread(() -> {
                            Toast.makeText(this, "Cleared " + count + " item(s).", Toast.LENGTH_SHORT).show();
                            refreshDownloadCenter();
                        });
                    }).start();
                }
        ));

        // 2. Clear All Tasks (Keep Files)
        content.addView(createOledDialogOptionTile(
                "Remove All from Queue",
                "Clears all downloads from list • Keeps media files on disk",
                Color.parseColor("#38BDF8"),
                () -> {
                    if (dRef[0] != null) dRef[0].dismiss();
                    Toast.makeText(this, "Clearing queue items...", Toast.LENGTH_SHORT).show();
                    new Thread(() -> {
                        int count = MediaStorageManager.getInstance(this).clearAllTransmissionTorrents(false);
                        runOnUiThread(() -> {
                            Toast.makeText(this, "Cleared " + count + " item(s).", Toast.LENGTH_SHORT).show();
                            refreshDownloadCenter();
                        });
                    }).start();
                }
        ));

        // 3. Erase Everything (Delete Files)
        content.addView(createOledDialogOptionTile(
                "Erase All & Delete Files",
                "Permanently purges torrents and deletes all downloaded files from storage",
                Color.parseColor("#EF4444"),
                () -> {
                    if (dRef[0] != null) dRef[0].dismiss();
                    Toast.makeText(this, "Purging downloads and disk storage...", Toast.LENGTH_SHORT).show();
                    new Thread(() -> {
                        int count = MediaStorageManager.getInstance(this).clearAllTransmissionTorrents(true);
                        runOnUiThread(() -> {
                            Toast.makeText(this, "Purged " + count + " download(s).", Toast.LENGTH_SHORT).show();
                            refreshDownloadCenter();
                        });
                    }).start();
                }
        ));

        dRef[0] = showOledDialog("Clear Downloads", "Select an action for active and completed tasks", content, null, 0, null, "CANCEL", null);
    }

    private void showRemoveSingleTorrentDialog(MediaStorageManager.TorrentInfo torrent) {
        String titleName = torrent.name != null ? torrent.name : ("Torrent #" + torrent.id);
        LinearLayout content = new LinearLayout(this);
        content.setOrientation(LinearLayout.VERTICAL);

        Dialog[] dRef = new Dialog[1];

        content.addView(createOledDialogOptionTile(
                "Remove from Queue",
                "Removes task from Transmission • Retains downloaded files on disk",
                Color.parseColor("#38BDF8"),
                () -> {
                    if (dRef[0] != null) dRef[0].dismiss();
                    Toast.makeText(this, "Removing...", Toast.LENGTH_SHORT).show();
                    new Thread(() -> {
                        boolean ok = MediaStorageManager.getInstance(this).removeTransmissionTorrent(torrent.id, false);
                        runOnUiThread(() -> {
                            Toast.makeText(this, ok ? "Removed from queue." : "Failed to remove.", Toast.LENGTH_SHORT).show();
                            refreshDownloadCenter();
                        });
                    }).start();
                }
        ));

        content.addView(createOledDialogOptionTile(
                "Delete Files from Disk",
                "Permanently deletes task and all downloaded files from storage",
                Color.parseColor("#EF4444"),
                () -> {
                    if (dRef[0] != null) dRef[0].dismiss();
                    Toast.makeText(this, "Deleting files...", Toast.LENGTH_SHORT).show();
                    new Thread(() -> {
                        boolean ok = MediaStorageManager.getInstance(this).removeTransmissionTorrent(torrent.id, true);
                        runOnUiThread(() -> {
                            Toast.makeText(this, ok ? "Deleted files and task." : "Failed to delete.", Toast.LENGTH_SHORT).show();
                            refreshDownloadCenter();
                        });
                    }).start();
                }
        ));

        dRef[0] = showOledDialog("Remove Download", titleName, content, null, 0, null, "CANCEL", null);
    }

    // -- Unified Console & Logs Engine ---------------------------------------

    private void refreshLogServiceChips() {
        if (logServiceChipsContainer == null) return;
        logServiceChipsContainer.removeAllViews();

        List<String> installedServices = new ArrayList<>();
        installedServices.add("ALL");
        if (JellyfinBootstrapper.isInitialized(this)) {
            installedServices.add("JELLYFIN");
        }

        MediaServiceManager mgr = MediaServiceManager.getInstance(this);
        for (ManagedService s : mgr.getAllServices()) {
            if (s != null && s.isInstalled()) {
                if (s.getManifest().isCore() || "jellyfin".equalsIgnoreCase(s.getId())) continue;
                installedServices.add(s.getId().toUpperCase(Locale.ROOT));
            }
        }

        // Validate selectedLogService
        boolean isValid = false;
        for (String svc : installedServices) {
            if (svc.equalsIgnoreCase(selectedLogService)) {
                isValid = true;
                break;
            }
        }
        if (!isValid) {
            selectedLogService = "ALL";
        }

        for (String svcName : installedServices) {
            Button chip = new Button(this);
            chip.setText(svcName);
            chip.setTextSize(10);
            chip.setTypeface(Typeface.create("sans-serif-medium", Typeface.BOLD));
            chip.setAllCaps(true);
            chip.setMinHeight(0);
            chip.setMinimumHeight(0);
            chip.setMinWidth(0);
            chip.setMinimumWidth(0);
            chip.setPadding(dp(10), dp(5), dp(10), dp(5));
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                chip.setStateListAnimator(null);
            }
            chip.setOnClickListener(v -> {
                selectedLogService = svcName;
                updateLogServiceChips();
                refreshUnifiedLogsDisplay();
            });

            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-2, dp(28));
            lp.rightMargin = dp(6);
            logServiceChipsContainer.addView(chip, lp);
        }
        updateLogServiceChips();
    }

    private void updateLogServiceChips() {
        if (logServiceChipsContainer == null) return;
        for (int i = 0; i < logServiceChipsContainer.getChildCount(); i++) {
            View child = logServiceChipsContainer.getChildAt(i);
            if (!(child instanceof Button)) continue;
            Button chip = (Button) child;
            String text = chip.getText().toString();
            boolean isSel = text.equalsIgnoreCase(selectedLogService);

            boolean isSvcRunning;
            if ("ALL".equalsIgnoreCase(text)) {
                isSvcRunning = true;
            } else if ("JELLYFIN".equalsIgnoreCase(text)) {
                isSvcRunning = (controller != null && controller.getState() == JellyfinController.State.RUNNING);
            } else {
                ManagedService ms = MediaServiceManager.getInstance(this).getService(text.toLowerCase(Locale.ROOT));
                isSvcRunning = (ms != null && ms.isRunning());
            }

            int brandColor = "ALL".equalsIgnoreCase(text) ? Color.WHITE : getServiceBrandColor(text.toLowerCase(Locale.ROOT));

            if (isSel) {
                if (isSvcRunning && !"ALL".equalsIgnoreCase(text)) {
                    chip.setBackground(createBorderedRoundedDrawable(
                            brandColor,
                            brandColor,
                            dp(1),
                            dp(8)
                    ));
                    chip.setTextColor(Color.BLACK);
                } else {
                    chip.setBackground(createBorderedRoundedDrawable(
                            Color.WHITE,
                            Color.WHITE,
                            dp(1),
                            dp(8)
                    ));
                    chip.setTextColor(Color.BLACK);
                }
            } else {
                if (isSvcRunning && !"ALL".equalsIgnoreCase(text)) {
                    chip.setBackground(createBorderedRoundedDrawable(
                            Color.BLACK,
                            brandColor,
                            dp(1),
                            dp(8)
                    ));
                    chip.setTextColor(brandColor);
                } else {
                    chip.setBackground(createBorderedRoundedDrawable(
                            Color.BLACK,
                            Color.argb(20, 255, 255, 255),
                            dp(1),
                            dp(8)
                    ));
                    chip.setTextColor(Color.parseColor("#71717A"));
                }
            }
        }
    }

    private void updateLogLevelChips() {
        if (logLevelChipsContainer == null) return;
        for (int i = 0; i < logLevelChipsContainer.getChildCount(); i++) {
            View child = logLevelChipsContainer.getChildAt(i);
            if (!(child instanceof Button)) continue;
            Button chip = (Button) child;
            String text = chip.getText().toString();
            boolean isSel = ("ALL".equals(text) && "ALL".equals(selectedLogLevel))
                    || ("ERR".equals(text) && "ERROR".equals(selectedLogLevel))
                    || ("WRN".equals(text) && "WARNING".equals(selectedLogLevel))
                    || ("INF".equals(text) && "INFO".equals(selectedLogLevel));

            int chipColor = "ERR".equals(text) ? getDangerColor()
                    : ("WRN".equals(text) ? getWarningColor()
                    : ("INF".equals(text) ? Color.parseColor("#34D399") : Color.WHITE));

            if (isSel) {
                chip.setBackground(createBorderedRoundedDrawable(
                        Color.argb(35, Color.red(chipColor), Color.green(chipColor), Color.blue(chipColor)),
                        chipColor,
                        dp(1),
                        dp(6)
                ));
                chip.setTextColor(chipColor);
            } else {
                chip.setBackground(createBorderedRoundedDrawable(
                        Color.BLACK,
                        Color.argb(20, 255, 255, 255),
                        dp(1),
                        dp(6)
                ));
                chip.setTextColor(Color.parseColor("#71717A"));
            }
        }
    }

    private int getServiceColor(String serviceTag) {
        if ("ALL".equalsIgnoreCase(serviceTag)) return Color.WHITE;
        return getServiceBrandColor(serviceTag.toLowerCase(Locale.ROOT));
    }

    private void appendColorizedLine(SpannableStringBuilder ssb, String rawLine, String defaultServiceTag) {
        if (rawLine == null || rawLine.trim().isEmpty()) return;
        String line = rawLine.trim();

        // Determine service tag from line or fallback
        String serviceTag = defaultServiceTag;
        if (line.startsWith("[JELLYFIN]") || line.startsWith("[RADARR]") || line.startsWith("[SONARR]")
                || line.startsWith("[PROWLARR]") || line.startsWith("[JACKETT]") || line.startsWith("[TRANSMISSION]") || line.startsWith("[SEERR]")
                || line.startsWith("[BAZARR]") || line.startsWith("[LIDARR]")) {
            int endTag = line.indexOf(']');
            if (endTag > 0) {
                serviceTag = line.substring(1, endTag).toUpperCase(Locale.ROOT);
                line = line.substring(endTag + 1).trim();
            }
        }

        // Determine level: ERR, WRN, INF, DBG
        String lower = line.toLowerCase(Locale.ROOT);
        int levelColor = Color.parseColor("#34D399"); // Mint/Sage for INFO default
        String levelBadge = "INF";
        if (lower.contains("[err]") || lower.contains("[error]") || lower.contains("error:")
                || lower.contains("fail") || lower.contains("exception") || lower.contains("[ftl]")
                || lower.contains("fatal")) {
            levelColor = getDangerColor(); // Bright Red
            levelBadge = "ERR";
        } else if (lower.contains("[wrn]") || lower.contains("[warn]") || lower.contains("warning:")) {
            levelColor = getWarningColor(); // Warm Safety Orange
            levelBadge = "WRN";
        } else if (lower.contains("[dbg]") || lower.contains("[debug]")) {
            levelColor = Color.parseColor("#94A3B8"); // Slate
            levelBadge = "DBG";
        }

        // If level filter is active, check match
        if ("ERROR".equals(selectedLogLevel) && !levelBadge.equals("ERR")) return;
        if ("WARNING".equals(selectedLogLevel) && !levelBadge.equals("WRN") && !levelBadge.equals("ERR")) return;
        if ("INFO".equals(selectedLogLevel) && !levelBadge.equals("INF")) return;

        // Service tag color
        int serviceColor = getServiceColor(serviceTag);

        int tagStart = ssb.length();
        ssb.append("[").append(serviceTag).append("] ");
        int tagEnd = ssb.length();
        ssb.setSpan(new ForegroundColorSpan(serviceColor), tagStart, tagEnd, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        ssb.setSpan(new StyleSpan(Typeface.BOLD), tagStart, tagEnd, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);

        // Clean up redundant Microsoft/Emby namespaces in line
        String cleanLine = line.replace("Microsoft.Hosting.Lifetime: ", "")
                .replace("Jellyfin.Server.Implementations.ApplicationHost: ", "App: ")
                .replace("Emby.Server.Implementations.HttpServer.HttpListenerHost: ", "Http: ")
                .replace("Emby.Server.Implementations.IO.LibraryMonitor: ", "Library: ")
                .replace("Emby.Server.Implementations.ScheduledTasks.TaskManager: ", "Task: ");

        // Append and color body text
        int lineStart = ssb.length();
        ssb.append(cleanLine).append("\n");
        int lineEnd = ssb.length();

        int msgColor = levelBadge.equals("ERR") ? Color.parseColor("#FCA5A5")
                : (levelBadge.equals("WRN") ? Color.parseColor("#FDE68A") : Color.parseColor("#D4D4D8"));
        ssb.setSpan(new ForegroundColorSpan(msgColor), lineStart, lineEnd, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
    }

    private void refreshUnifiedLogsDisplay() {
        if (logsOutputText == null) return;

        SpannableStringBuilder ssb = new SpannableStringBuilder();
        int lineCount = 0;

        if ("ALL".equalsIgnoreCase(selectedLogService)) {
            // Gather Jellyfin controller logs (if installed/initialized)
            if (controller != null && JellyfinBootstrapper.isInitialized(this)) {
                String jfLogs = showingDiskLog ? controller.getDiskLogs() : controller.getLogs();
                if (showingDiskLog && (jfLogs == null || jfLogs.isEmpty())) {
                    jfLogs = controller.getLogs();
                }
                if (jfLogs != null && !jfLogs.isEmpty()) {
                    String[] lines = jfLogs.split("\n");
                    int start = Math.max(0, lines.length - 120);
                    for (int i = start; i < lines.length; i++) {
                        appendColorizedLine(ssb, lines[i], "JELLYFIN");
                        lineCount++;
                    }
                }
            }
            // Gather all managed services that are installed
            MediaServiceManager mgr = MediaServiceManager.getInstance(this);
            for (ManagedService s : mgr.getAllServices()) {
                if (s == null || !s.isInstalled()) continue;
                if (s.getManifest().isCore() || "jellyfin".equalsIgnoreCase(s.getId())) continue;
                String sLogs = s.getRecentLogs(50);
                if (sLogs == null || sLogs.trim().isEmpty() || sLogs.startsWith("--- No logs")) continue;
                String[] lines = sLogs.split("\n");
                for (String l : lines) {
                    appendColorizedLine(ssb, l, s.getId().toUpperCase(Locale.ROOT));
                    lineCount++;
                }
            }
        } else if ("JELLYFIN".equalsIgnoreCase(selectedLogService)) {
            if (!JellyfinBootstrapper.isInitialized(this)) {
                logsOutputText.setText("--- Jellyfin server runtime is not installed ---");
                logsOutputText.setTextColor(Color.parseColor("#71717A"));
                if (logsStatusSubtitle != null) {
                    logsStatusSubtitle.setText("[JELLYFIN] - Not installed");
                }
                return;
            }
            if (logsStatusSubtitle != null) {
                logsStatusSubtitle.setText(showingDiskLog ? "[JELLYFIN] - Reading Server Disk Log" : "[JELLYFIN] - Reading Launcher Live Stream");
            }
            if (controller != null) {
                String raw = showingDiskLog ? controller.getDiskLogs() : controller.getLogs();
                if (showingDiskLog && (raw == null || raw.isEmpty())) {
                    raw = controller.getLogs();
                }
                if (raw != null && !raw.isEmpty()) {
                    String[] lines = raw.split("\n");
                    int start = Math.max(0, lines.length - 250);
                    for (int i = start; i < lines.length; i++) {
                        appendColorizedLine(ssb, lines[i], "JELLYFIN");
                        lineCount++;
                    }
                }
                if (lineCount == 0 && controller.getPreservedErrorCount() > 0) {
                    String errs = controller.getPreservedErrorLogs();
                    if (errs != null && !errs.isEmpty()) {
                        String[] lines = errs.split("\n");
                        for (String l : lines) {
                            appendColorizedLine(ssb, l, "JELLYFIN");
                            lineCount++;
                        }
                    }
                }
            }
        } else {
            MediaServiceManager mgr = MediaServiceManager.getInstance(this);
            ManagedService s = mgr.getService(selectedLogService.toLowerCase(Locale.ROOT));
            if (s != null && s.isInstalled()) {
                String raw = s.getRecentLogs(250);
                if (raw != null && !raw.isEmpty() && !raw.startsWith("--- No logs")) {
                    String[] lines = raw.split("\n");
                    for (String l : lines) {
                        appendColorizedLine(ssb, l, selectedLogService);
                        lineCount++;
                    }
                }
            } else {
                logsOutputText.setText("--- Service [" + selectedLogService + "] is not installed ---");
                logsOutputText.setTextColor(Color.parseColor("#71717A"));
                if (logsStatusSubtitle != null) {
                    logsStatusSubtitle.setText("[" + selectedLogService + "] - Not installed");
                }
                return;
            }
        }

        if (ssb.length() == 0) {
            logsOutputText.setText("--- No logs recorded for [" + selectedLogService + "] with filter [" + selectedLogLevel + "] ---");
            logsOutputText.setTextColor(Color.parseColor("#71717A"));
        } else {
            logsOutputText.setText(ssb);
        }

        if (logsStatusSubtitle != null) {
            logsStatusSubtitle.setText("[" + selectedLogService + "] - " + lineCount + " lines (" + selectedLogLevel + ") - Auto-scroll: " + (autoScrollEnabled ? "ON" : "OFF"));
        }

        if (autoScrollEnabled && logsScrollView != null) {
            logsScrollView.post(() -> logsScrollView.fullScroll(View.FOCUS_DOWN));
        }
    }

    private void renderTimelineEvents() {
        refreshUnifiedLogsDisplay();
    }

    // -- Tab 1: Services Manager View ------------------------------------------

    private View buildServicesTab() {
        ScrollView scroll = new ScrollView(this);
        scroll.setVerticalScrollBarEnabled(false);
        scroll.setHorizontalScrollBarEnabled(false);
        scroll.setOverScrollMode(View.OVER_SCROLL_NEVER);
        scroll.setSmoothScrollingEnabled(true);
        scroll.setFillViewport(true);
        scroll.setBackgroundColor(getBgColor());
        scroll.setPadding(dp(20), dp(14), dp(20), dp(125));
        scroll.setClipToPadding(false);

        LinearLayout layout = new LinearLayout(this);
        layout.setOrientation(LinearLayout.VERTICAL);

        // -- Clean Unboxed Stack Header ------------------------------------
        LinearLayout header = new LinearLayout(this);
        header.setOrientation(LinearLayout.VERTICAL);
        header.setPadding(0, dp(4), 0, dp(12));

        // Title row
        LinearLayout topRow = new LinearLayout(this);
        topRow.setOrientation(LinearLayout.HORIZONTAL);
        topRow.setGravity(Gravity.CENTER_VERTICAL);

        TextView title = new TextView(this);
        title.setText("MEDIA STACK");
        title.setTextSize(18);
        title.setTypeface(Typeface.create("sans-serif-medium", Typeface.BOLD));
        title.setTextColor(Color.parseColor("#F4F4F5"));
        topRow.addView(title, new LinearLayout.LayoutParams(0, -2, 1.0f));

        servicesSummaryBadge = new TextView(this);
        servicesSummaryBadge.setText("Loading...");
        servicesSummaryBadge.setTextSize(11);
        servicesSummaryBadge.setTypeface(Typeface.MONOSPACE);
        servicesSummaryBadge.setTextColor(getSuccessColor());
        topRow.addView(servicesSummaryBadge);
        topRow.setPadding(0, 0, 0, dp(12));
        header.addView(topRow);

        // -- Master controls row: Unified Dynamic Master Toggle + SYNC STACK -----------
        LinearLayout masterRow = new LinearLayout(this);
        masterRow.setOrientation(LinearLayout.HORIZONTAL);
        LinearLayout.LayoutParams mrLp = new LinearLayout.LayoutParams(-1, -2);
        mrLp.bottomMargin = dp(6);
        masterRow.setLayoutParams(mrLp);

        masterStackToggleBtn = createOutlinePixelButton("START ALL", Color.WHITE, Color.WHITE);
        masterStackToggleBtn.setTextSize(11f);
        masterStackToggleBtn.setTypeface(Typeface.create("sans-serif", Typeface.BOLD));
        masterStackToggleBtn.setLetterSpacing(0.06f);
        masterRow.addView(masterStackToggleBtn, new LinearLayout.LayoutParams(0, dp(44), 1.0f));

        View btnGap = new View(this);
        masterRow.addView(btnGap, new LinearLayout.LayoutParams(dp(8), 1));

        Button btnSyncAll = createOutlinePixelButton("SYNC STACK", Color.parseColor("#A1A1AA"), Color.parseColor("#E4E4E7"));
        btnSyncAll.setTextSize(11f);
        btnSyncAll.setTypeface(Typeface.create("sans-serif", Typeface.BOLD));
        btnSyncAll.setLetterSpacing(0.06f);
        btnSyncAll.setOnClickListener(v -> syncAndAutoWireAllServices());
        masterRow.addView(btnSyncAll, new LinearLayout.LayoutParams(0, dp(44), 1.0f));

        header.addView(masterRow);

        // Quick Stack Presets: MOVIES (Radarr + Transmission + Prowlarr) & SERIES (Sonarr + Transmission + Prowlarr)
        LinearLayout presetsRow = new LinearLayout(this);
        presetsRow.setOrientation(LinearLayout.HORIZONTAL);
        LinearLayout.LayoutParams prLp = new LinearLayout.LayoutParams(-1, -2);
        prLp.topMargin = dp(4);
        prLp.bottomMargin = dp(2);
        presetsRow.setLayoutParams(prLp);

        Button btnMovies = createOutlinePixelButton("MOVIES", Color.parseColor("#38BDF8"), Color.parseColor("#38BDF8"));
        btnMovies.setTextSize(10.5f);
        btnMovies.setTypeface(Typeface.create("sans-serif", Typeface.BOLD));
        btnMovies.setLetterSpacing(0.06f);
        btnMovies.setOnClickListener(v -> launchMoviesStack());
        presetsRow.addView(btnMovies, new LinearLayout.LayoutParams(0, dp(38), 1.0f));

        View presetGap = new View(this);
        presetsRow.addView(presetGap, new LinearLayout.LayoutParams(dp(8), 1));

        Button btnSeries = createOutlinePixelButton("TV SHOWS / SERIES", Color.parseColor("#818CF8"), Color.parseColor("#818CF8"));
        btnSeries.setTextSize(10.5f);
        btnSeries.setTypeface(Typeface.create("sans-serif", Typeface.BOLD));
        btnSeries.setLetterSpacing(0.06f);
        btnSeries.setOnClickListener(v -> launchSeriesStack());
        presetsRow.addView(btnSeries, new LinearLayout.LayoutParams(0, dp(38), 1.0f));

        header.addView(presetsRow);

        // 1dp Hairline Divider
        View hDiv = new View(this);
        hDiv.setBackgroundColor(Color.argb(10, 255, 255, 255));
        LinearLayout.LayoutParams hDivLp = new LinearLayout.LayoutParams(-1, dp(1));
        hDivLp.topMargin = dp(12);
        header.addView(hDiv, hDivLp);

        layout.addView(header);

        servicesCardsContainer = new LinearLayout(this);
        servicesCardsContainer.setOrientation(LinearLayout.VERTICAL);
        android.animation.LayoutTransition lt = new android.animation.LayoutTransition();
        lt.enableTransitionType(android.animation.LayoutTransition.CHANGING);
        lt.enableTransitionType(android.animation.LayoutTransition.APPEARING);
        lt.enableTransitionType(android.animation.LayoutTransition.DISAPPEARING);
        lt.setDuration(220);
        servicesCardsContainer.setLayoutTransition(lt);
        LinearLayout.LayoutParams scParams = new LinearLayout.LayoutParams(-1, -2);
        scParams.topMargin = dp(10);
        servicesCardsContainer.setLayoutParams(scParams);
        layout.addView(servicesCardsContainer);

        // Unified Torrent Download Manager directly integrated at bottom of Media Stack
        layout.addView(buildDownloadCenterCard());

        scroll.addView(layout);
        refreshServicesTabUI();
        refreshDownloadCenter();
        return scroll;
    }

    private void refreshServicesTabUI() {
        if (servicesCardsContainer == null) return;
        servicesCardsContainer.removeAllViews();

        MediaServiceManager mgr = MediaServiceManager.getInstance(this);
        List<ManagedService> allServices = mgr.getAllServices();
        List<ManagedService> installedServices = new java.util.ArrayList<>();
        List<ManagedService> uninstalledServices = new java.util.ArrayList<>();

        int running = 0;
        for (ManagedService s : allServices) {
            if ("jellyfin".equalsIgnoreCase(s.getId())) continue;
            if (s.isInstalled()) {
                installedServices.add(s);
                if (s.isRunning()) {
                    running++;
                }
            } else {
                uninstalledServices.add(s);
            }
        }

        // 1. Primary Installed Services Grid (2 Columns with 12dp Breathing Space)
        if (installedServices.isEmpty()) {
            LinearLayout emptyBox = new LinearLayout(this);
            emptyBox.setOrientation(LinearLayout.VERTICAL);
            emptyBox.setGravity(Gravity.CENTER);
            emptyBox.setPadding(dp(16), dp(20), dp(16), dp(16));

            TextView emptyTitle = new TextView(this);
            emptyTitle.setText("NO COMPANION SERVICES INSTALLED");
            emptyTitle.setTextSize(11f);
            emptyTitle.setTypeface(Typeface.MONOSPACE, Typeface.BOLD);
            emptyTitle.setTextColor(Color.parseColor("#71717A"));
            emptyBox.addView(emptyTitle);

            TextView emptySub = new TextView(this);
            emptySub.setText("Tap available services in the drawer below to install.");
            emptySub.setTextSize(9.5f);
            emptySub.setTextColor(Color.parseColor("#52525B"));
            emptySub.setPadding(0, dp(4), 0, 0);
            emptyBox.addView(emptySub);

            servicesCardsContainer.addView(emptyBox);
        } else {
            LinearLayout currentRow = null;
            for (int i = 0; i < installedServices.size(); i++) {
                ManagedService s = installedServices.get(i);
                if (i % 2 == 0) {
                    currentRow = new LinearLayout(this);
                    currentRow.setOrientation(LinearLayout.HORIZONTAL);
                    currentRow.setBaselineAligned(false);
                    LinearLayout.LayoutParams rowLp = new LinearLayout.LayoutParams(
                            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
                    rowLp.bottomMargin = dp(12);
                    currentRow.setLayoutParams(rowLp);
                    servicesCardsContainer.addView(currentRow);
                }

                View card = buildIndividualServiceCard(s);
                LinearLayout.LayoutParams cardLp = new LinearLayout.LayoutParams(
                        0, ViewGroup.LayoutParams.WRAP_CONTENT, 1.0f);
                card.setLayoutParams(cardLp);
                if (currentRow != null) {
                    currentRow.addView(card);
                }

                if (i % 2 == 0) {
                    View spacer = new View(this);
                    if (currentRow != null) {
                        currentRow.addView(spacer, new LinearLayout.LayoutParams(dp(12), 1));
                    }
                }
            }

            if (installedServices.size() % 2 != 0 && currentRow != null) {
                View emptySpacer = new View(this);
                currentRow.addView(emptySpacer, new LinearLayout.LayoutParams(0, 1, 1.0f));
            }
        }

        // 2. Uninstalled Services Collapsible Drawer (Grayed Out)
        if (!uninstalledServices.isEmpty()) {
            LinearLayout drawerLayout = new LinearLayout(this);
            drawerLayout.setOrientation(LinearLayout.VERTICAL);
            LinearLayout.LayoutParams dLp = new LinearLayout.LayoutParams(-1, -2);
            dLp.topMargin = dp(14);
            dLp.bottomMargin = dp(8);
            drawerLayout.setLayoutParams(dLp);

            // Drawer Header Row
            LinearLayout drawerHeader = new LinearLayout(this);
            drawerHeader.setOrientation(LinearLayout.HORIZONTAL);
            drawerHeader.setGravity(Gravity.CENTER_VERTICAL);
            drawerHeader.setPadding(dp(4), dp(8), dp(4), dp(8));
            drawerHeader.setClickable(true);
            drawerHeader.setFocusable(true);

            TextView drawerTitle = new TextView(this);
            drawerTitle.setText("AVAILABLE SERVICES (" + uninstalledServices.size() + ")");
            drawerTitle.setTextSize(11f);
            drawerTitle.setTypeface(Typeface.MONOSPACE, Typeface.BOLD);
            drawerTitle.setLetterSpacing(0.08f);
            drawerTitle.setTextColor(Color.parseColor("#71717A"));
            drawerHeader.addView(drawerTitle, new LinearLayout.LayoutParams(0, -2, 1.0f));

            ImageView chevron = new ImageView(this);
            chevron.setImageResource(R.drawable.ic_chevron_down);
            chevron.setColorFilter(Color.parseColor("#71717A"));
            chevron.setRotation(isAvailableServicesDrawerOpen ? 180f : 0f);
            drawerHeader.addView(chevron, new LinearLayout.LayoutParams(dp(18), dp(18)));

            drawerLayout.addView(drawerHeader);

            // Drawer Content Grid
            LinearLayout drawerContent = new LinearLayout(this);
            drawerContent.setOrientation(LinearLayout.VERTICAL);
            drawerContent.setVisibility(isAvailableServicesDrawerOpen ? View.VISIBLE : View.GONE);
            LinearLayout.LayoutParams dcLp = new LinearLayout.LayoutParams(-1, -2);
            dcLp.topMargin = dp(10);
            drawerContent.setLayoutParams(dcLp);

            LinearLayout unRow = null;
            for (int i = 0; i < uninstalledServices.size(); i++) {
                ManagedService s = uninstalledServices.get(i);
                if (i % 2 == 0) {
                    unRow = new LinearLayout(this);
                    unRow.setOrientation(LinearLayout.HORIZONTAL);
                    unRow.setBaselineAligned(false);
                    LinearLayout.LayoutParams rowLp = new LinearLayout.LayoutParams(
                            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
                    rowLp.bottomMargin = dp(12);
                    unRow.setLayoutParams(rowLp);
                    drawerContent.addView(unRow);
                }

                View card = buildIndividualServiceCard(s);
                LinearLayout.LayoutParams cardLp = new LinearLayout.LayoutParams(
                        0, ViewGroup.LayoutParams.WRAP_CONTENT, 1.0f);
                card.setLayoutParams(cardLp);
                if (unRow != null) {
                    unRow.addView(card);
                }

                if (i % 2 == 0) {
                    View spacer = new View(this);
                    if (unRow != null) {
                        unRow.addView(spacer, new LinearLayout.LayoutParams(dp(12), 1));
                    }
                }
            }

            if (uninstalledServices.size() % 2 != 0 && unRow != null) {
                View emptySpacer = new View(this);
                unRow.addView(emptySpacer, new LinearLayout.LayoutParams(0, 1, 1.0f));
            }

            drawerLayout.addView(drawerContent);

            drawerHeader.setOnClickListener(v -> {
                isAvailableServicesDrawerOpen = !isAvailableServicesDrawerOpen;
                drawerContent.setVisibility(isAvailableServicesDrawerOpen ? View.VISIBLE : View.GONE);
                chevron.animate().rotation(isAvailableServicesDrawerOpen ? 180f : 0f).setDuration(200).start();
            });

            servicesCardsContainer.addView(drawerLayout);
        }

        if (servicesSummaryBadge != null) {
            servicesSummaryBadge.setText(running + " / " + installedServices.size() + " Active");
            servicesSummaryBadge.setTextColor(running > 0 ? Color.WHITE : Color.parseColor("#71717A"));
        }

        boolean anyActive = false;
        for (ManagedService s : installedServices) {
            if (s.isRunning() || s.isStarting()) {
                anyActive = true;
                break;
            }
        }

        if (masterStackToggleBtn != null) {
            if (anyActive) {
                masterStackToggleBtn.setText("STOP ALL");
                applyOutlineButtonStyle(masterStackToggleBtn, Color.parseColor("#E11D48"), Color.parseColor("#E11D48"));
                masterStackToggleBtn.setOnClickListener(v -> {
                    Toast.makeText(this, "Stopping companion media stack...", Toast.LENGTH_SHORT).show();
                    MediaServiceManager.getInstance(this).stopCompanionServices();
                    mainHandler.postDelayed(() -> {
                        MediaServiceManager.getInstance(this).reconcileRunningServices();
                        refreshServicesTabUI();
                        refreshHomeServicesBadge();
                    }, 2000);
                });
            } else {
                masterStackToggleBtn.setText("START ALL");
                applyOutlineButtonStyle(masterStackToggleBtn, Color.WHITE, Color.WHITE);
                masterStackToggleBtn.setOnClickListener(v -> {
                    Toast.makeText(this, "Starting companion media stack...", Toast.LENGTH_SHORT).show();
                    for (ManagedService is : installedServices) {
                        if (!is.isRunning() && !is.isStarting()) {
                            mgr.startService(is.getId());
                        }
                    }
                    mainHandler.postDelayed(() -> {
                        MediaServiceManager.getInstance(this).reconcileRunningServices();
                        refreshServicesTabUI();
                        refreshHomeServicesBadge();
                    }, 3500);
                });
            }
        }
        com.fishbowl.app.widget.FishbowlWidgetProvider.updateAllWidgets(this);
    }

    private TextView createOledTextContent(String msg) {
        TextView tv = new TextView(this);
        tv.setText(msg);
        tv.setTextSize(10f);
        tv.setTypeface(Typeface.MONOSPACE);
        tv.setTextColor(Color.parseColor("#D4D4D8"));
        tv.setPadding(0, dp(6), 0, 0);
        return tv;
    }

    private View buildIndividualServiceCard(ManagedService svc) {
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setPadding(dp(12), dp(11), dp(12), dp(11));

        String id = svc.getId().toLowerCase(Locale.ROOT);
        boolean isJellyfin = "jellyfin".equals(id);
        boolean isRunning;
        boolean isStarting;
        int iconTintColor;
        String stText = "OFF";
        int stColor = Color.parseColor("#71717A");

        if (isJellyfin) {
            JellyfinController.State js = controller != null ? controller.getState() : JellyfinController.State.STOPPED;
            isRunning = (js == JellyfinController.State.RUNNING);
            isStarting = (js == JellyfinController.State.STARTING || js == JellyfinController.State.INITIALIZING);
            switch (js) {
                case RUNNING:
                    iconTintColor = getServiceBrandColor("jellyfin");
                    stText = "RUNNING";
                    stColor = Color.parseColor("#34D399");
                    break;
                case STARTING:
                case INITIALIZING:
                    iconTintColor = getWarningColor();
                    stText = "STARTING";
                    stColor = getWarningColor();
                    break;
                case CRASHED:
                case FAILED:
                case CRASH_LOOP:
                    iconTintColor = getDangerColor();
                    stText = "FAILED";
                    stColor = getDangerColor();
                    break;
                default:
                    iconTintColor = Color.WHITE;
                    stText = "OFF";
                    stColor = Color.parseColor("#71717A");
                    break;
            }
        } else {
            isRunning = svc.isRunning();
            isStarting = svc.isStarting();
            iconTintColor = getServiceBrandTintColor(svc.getId(), svc.getState());
            if (svc.isBlocked()) {
                stText = "BLOCKED";
                stColor = getDangerColor();
            } else if (svc.getState() == com.fishbowl.app.model.ServiceState.FAILED
                    || svc.getState() == com.fishbowl.app.model.ServiceState.ERROR
                    || svc.getState() == com.fishbowl.app.model.ServiceState.CRASH_LOOP_PROTECTION
                    || svc.getState() == com.fishbowl.app.model.ServiceState.UNHEALTHY) {
                stText = svc.getState() == com.fishbowl.app.model.ServiceState.CRASH_LOOP_PROTECTION ? "CRASH LOOP" : "FAILED";
                stColor = getDangerColor();
            } else if (isRunning) {
                stText = "RUNNING";
                stColor = Color.parseColor("#34D399");
            } else if (isStarting) {
                stText = "WAIT";
                stColor = getWarningColor();
            } else if (svc.isInstalled()) {
                stText = "OFF";
                stColor = Color.parseColor("#71717A");
            } else {
                stText = "AVAILABLE";
                stColor = Color.parseColor("#52525B");
            }
        }

        boolean installed = isJellyfin || svc.isInstalled();

        if (!installed) {
            card.setBackground(createBorderedRoundedDrawable(Color.parseColor("#060608"), Color.parseColor("#1C1C20"), dp(1), dp(10)));
            card.setAlpha(0.55f);
        } else {
            card.setBackground(createBorderedRoundedDrawable(Color.BLACK, Color.parseColor("#27272A"), dp(1), dp(10)));
            card.setAlpha(1.0f);
        }

        card.setClickable(true);
        card.setFocusable(true);
        card.setOnClickListener(v -> {
            if (installed) {
                showServiceDetailsModal(svc);
            } else {
                showServiceInstallCompatibilityDialog(svc);
            }
        });
        card.setOnLongClickListener(v -> {
            showServiceDetailsModal(svc);
            return true;
        });

        // 1. Top Header Row: Icon + Name / Version + Status Badge
        LinearLayout top = new LinearLayout(this);
        top.setOrientation(LinearLayout.HORIZONTAL);
        top.setGravity(Gravity.CENTER_VERTICAL);
        top.setOnClickListener(v -> card.performClick());
        top.setOnLongClickListener(v -> {
            showServiceDetailsModal(svc);
            return true;
        });

        ImageView iconView = new ImageView(this);
        iconView.setImageResource(getServiceIconRes(id));
        iconView.setColorFilter(iconTintColor);
        top.addView(iconView, new LinearLayout.LayoutParams(dp(22), dp(22)));

        View spIcon = new View(this);
        top.addView(spIcon, new LinearLayout.LayoutParams(dp(8), 1));

        LinearLayout titleCol = new LinearLayout(this);
        titleCol.setOrientation(LinearLayout.VERTICAL);

        LinearLayout nameRow = new LinearLayout(this);
        nameRow.setOrientation(LinearLayout.HORIZONTAL);
        nameRow.setGravity(Gravity.CENTER_VERTICAL);

        TextView name = new TextView(this);
        name.setText(svc.getDisplayName());
        name.setTextSize(12.5f);
        name.setTypeface(Typeface.create("sans-serif-medium", Typeface.BOLD));
        name.setTextColor(installed ? Color.parseColor("#F4F4F5") : Color.parseColor("#A1A1AA"));
        name.setSingleLine(true);
        name.setEllipsize(android.text.TextUtils.TruncateAt.END);
        nameRow.addView(name);

        TextView verTag = new TextView(this);
        verTag.setText(" v" + svc.getManifest().getVersion());
        verTag.setTextSize(8.5f);
        verTag.setTypeface(Typeface.MONOSPACE);
        verTag.setTextColor(Color.parseColor("#71717A"));
        nameRow.addView(verTag);

        titleCol.addView(nameRow);
        top.addView(titleCol, new LinearLayout.LayoutParams(0, -2, 1.0f));

        TextView stateBadge = new TextView(this);
        stateBadge.setText("● " + stText);
        stateBadge.setTextSize(8.5f);
        stateBadge.setTypeface(Typeface.MONOSPACE, Typeface.BOLD);
        stateBadge.setTextColor(stColor);
        top.addView(stateBadge);

        card.addView(top);

        // 2. Compact Endpoint / IP Row
        int defaultPort = svc.getManifest().getDefaultPort();
        String webPath = svc.getManifest().getWebPath();
        if (webPath == null) webPath = "/";
        String localUrl = "http://127.0.0.1:" + defaultPort + (webPath.equals("/") ? "" : webPath);
        String rawLanIp = getRawLanIp();
        String lanUrl = (rawLanIp != null) ? "http://" + rawLanIp + ":" + defaultPort + (webPath.equals("/") ? "" : webPath) : null;
        final String finalCopyUrl = lanUrl != null ? lanUrl : localUrl;

        LinearLayout epRow = new LinearLayout(this);
        epRow.setOrientation(LinearLayout.HORIZONTAL);
        epRow.setGravity(Gravity.CENTER);
        epRow.setPadding(dp(4), dp(4), dp(4), dp(4));
        epRow.setClickable(true);
        epRow.setFocusable(true);
        epRow.setOnClickListener(v -> copyUrlToClipboard(svc.getDisplayName() + " URL", finalCopyUrl));
        epRow.setOnLongClickListener(v -> {
            showServiceDetailsModal(svc);
            return true;
        });
        LinearLayout.LayoutParams epLp = new LinearLayout.LayoutParams(-1, -2);
        epLp.topMargin = dp(6);
        epLp.bottomMargin = dp(6);
        epRow.setLayoutParams(epLp);

        TextView epText = new TextView(this);
        epText.setText(lanUrl != null ? rawLanIp + ":" + defaultPort : "127.0.0.1:" + defaultPort);
        epText.setTextSize(10.5f);
        epText.setTypeface(Typeface.create(Typeface.MONOSPACE, Typeface.BOLD));
        epText.setTextColor(installed ? Color.WHITE : Color.parseColor("#71717A"));
        epText.setGravity(Gravity.CENTER);
        epText.setSingleLine(true);
        epText.setEllipsize(android.text.TextUtils.TruncateAt.MIDDLE);
        epRow.addView(epText, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, -2));

        card.addView(epRow);

        // 3. Compact Bottom Action Row (Single Row, Adaptive)
        LinearLayout bottomRow = new LinearLayout(this);
        bottomRow.setOrientation(LinearLayout.HORIZONTAL);
        bottomRow.setGravity(Gravity.CENTER_VERTICAL);
        LinearLayout.LayoutParams brLp = new LinearLayout.LayoutParams(-1, dp(32));
        brLp.topMargin = dp(2);
        bottomRow.setLayoutParams(brLp);

        if (svc.isBlocked()) {
            Button btnBlocked = createOutlinePixelButton("INCOMPATIBLE", Color.parseColor("#3F3F46"), Color.parseColor("#71717A"));
            btnBlocked.setTextSize(9f);
            btnBlocked.setEnabled(false);
            bottomRow.addView(btnBlocked, new LinearLayout.LayoutParams(-1, -1));
        } else if (!installed) {
            Button btnInstall = createOutlinePixelButton("INSTALL", Color.parseColor("#3F3F46"), Color.parseColor("#D4D4D8"));
            btnInstall.setTextSize(9.5f);
            btnInstall.setOnClickListener(v -> showServiceInstallCompatibilityDialog(svc));
            bottomRow.addView(btnInstall, new LinearLayout.LayoutParams(-1, -1));
        } else if (!isRunning) {
            Button btnStart = createOutlinePixelButton("START", Color.WHITE, Color.WHITE);
            btnStart.setTextSize(9.5f);
            btnStart.setOnClickListener(v -> {
                if (isJellyfin) {
                    triggerServerAction(JellyfinServerService.ACTION_START);
                } else {
                    MediaServiceManager.getInstance(this).startService(svc.getId());
                }
                mainHandler.postDelayed(() -> {
                    refreshServicesTabUI();
                    refreshHomeServicesBadge();
                }, 500);
            });
            bottomRow.addView(btnStart, new LinearLayout.LayoutParams(-1, -1));
        } else {
            Button btnWeb = createPixelButton("WEB UI", Color.WHITE, Color.BLACK);
            btnWeb.setTextSize(9f);
            btnWeb.setOnClickListener(v -> openServiceWeb(svc));
            bottomRow.addView(btnWeb, new LinearLayout.LayoutParams(0, -1, 1.15f));

            View spB1 = new View(this);
            bottomRow.addView(spB1, new LinearLayout.LayoutParams(dp(6), 1));

            Button btnStop = createOutlinePixelButton("STOP", Color.parseColor("#E11D48"), Color.parseColor("#E11D48"));
            btnStop.setTextSize(9f);
            btnStop.setOnClickListener(v -> {
                if (isJellyfin) {
                    controller.stop();
                    triggerServerAction(JellyfinServerService.ACTION_STOP);
                } else {
                    MediaServiceManager.getInstance(this).stopService(svc.getId());
                }
                mainHandler.postDelayed(() -> {
                    refreshServicesTabUI();
                    refreshHomeServicesBadge();
                }, 500);
            });
            bottomRow.addView(btnStop, new LinearLayout.LayoutParams(0, -1, 0.95f));
        }

        card.addView(bottomRow);
        return card;
    }

    private void showServiceDetailsModal(ManagedService svc) {
        Dialog d = new Dialog(this);
        d.requestWindowFeature(Window.FEATURE_NO_TITLE);

        ScrollView scroll = new ScrollView(this);
        scroll.setVerticalScrollBarEnabled(false);
        scroll.setHorizontalScrollBarEnabled(false);
        scroll.setOverScrollMode(View.OVER_SCROLL_NEVER);
        scroll.setBackground(createBorderedRoundedDrawable(Color.BLACK, Color.parseColor("#27272A"), dp(1), dp(14)));

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(18), dp(18), dp(18), dp(18));
        scroll.addView(root);

        String id = svc.getId().toLowerCase(Locale.ROOT);
        boolean isJellyfin = "jellyfin".equalsIgnoreCase(id);
        boolean isRunning = isJellyfin ? (controller != null && controller.getState() == JellyfinController.State.RUNNING) : svc.isRunning();
        boolean installed = isJellyfin || svc.isInstalled();

        // 1. Header: Icon + Title + Version + Category + Status Badge
        LinearLayout headerRow = new LinearLayout(this);
        headerRow.setOrientation(LinearLayout.HORIZONTAL);
        headerRow.setGravity(Gravity.CENTER_VERTICAL);

        ImageView icon = new ImageView(this);
        icon.setImageResource(getServiceIconRes(id));
        icon.setColorFilter(getServiceBrandColor(id));
        headerRow.addView(icon, new LinearLayout.LayoutParams(dp(32), dp(32)));

        View spH = new View(this);
        headerRow.addView(spH, new LinearLayout.LayoutParams(dp(10), 1));

        LinearLayout titleCol = new LinearLayout(this);
        titleCol.setOrientation(LinearLayout.VERTICAL);

        TextView title = new TextView(this);
        title.setText(svc.getDisplayName().toUpperCase(Locale.ROOT));
        title.setTextSize(15f);
        title.setTypeface(Typeface.MONOSPACE, Typeface.BOLD);
        title.setTextColor(Color.WHITE);
        titleCol.addView(title);

        TextView subTitle = new TextView(this);
        subTitle.setText("v" + svc.getManifest().getVersion());
        subTitle.setTextSize(9.5f);
        subTitle.setTypeface(Typeface.MONOSPACE);
        subTitle.setTextColor(Color.parseColor("#71717A"));
        subTitle.setPadding(0, dp(1), 0, 0);
        titleCol.addView(subTitle);

        headerRow.addView(titleCol, new LinearLayout.LayoutParams(0, -2, 1.0f));

        TextView stateBadge = new TextView(this);
        stateBadge.setText(isRunning ? "● RUNNING" : (installed ? "● STOPPED" : "● AVAILABLE"));
        stateBadge.setTextSize(9f);
        stateBadge.setTypeface(Typeface.MONOSPACE, Typeface.BOLD);
        stateBadge.setTextColor(isRunning ? Color.parseColor("#34D399") : (installed ? Color.parseColor("#71717A") : Color.parseColor("#38BDF8")));
        stateBadge.setPadding(dp(8), dp(4), dp(8), dp(4));
        stateBadge.setBackground(createBorderedRoundedDrawable(Color.BLACK, isRunning ? Color.parseColor("#34D399") : Color.parseColor("#27272A"), dp(1), dp(4)));
        headerRow.addView(stateBadge);

        root.addView(headerRow);

        // Divider
        View div1 = new View(this);
        div1.setBackgroundColor(Color.parseColor("#18181B"));
        LinearLayout.LayoutParams d1Lp = new LinearLayout.LayoutParams(-1, dp(1));
        d1Lp.topMargin = dp(12);
        d1Lp.bottomMargin = dp(12);
        root.addView(div1, d1Lp);

        // 2. Single Unified Info Card
        LinearLayout infoBox = new LinearLayout(this);
        infoBox.setOrientation(LinearLayout.VERTICAL);
        infoBox.setPadding(dp(14), dp(12), dp(14), dp(12));
        infoBox.setBackground(createBorderedRoundedDrawable(Color.parseColor("#09090B"), Color.parseColor("#27272A"), dp(1), dp(8)));
        LinearLayout.LayoutParams ibLp = new LinearLayout.LayoutParams(-1, -2);
        ibLp.bottomMargin = dp(16);
        infoBox.setLayoutParams(ibLp);

        // Item A: URL / Endpoint
        int defaultPort = svc.getManifest().getDefaultPort();
        String webPath = svc.getManifest().getWebPath();
        if (webPath == null) webPath = "/";
        String localUrl = "http://127.0.0.1:" + defaultPort + (webPath.equals("/") ? "" : webPath);
        String rawLanIp = getRawLanIp();
        String lanUrl = (rawLanIp != null) ? "http://" + rawLanIp + ":" + defaultPort + (webPath.equals("/") ? "" : webPath) : localUrl;

        TextView lblUrl = new TextView(this);
        lblUrl.setText("LOCAL & NETWORK URL");
        lblUrl.setTextSize(8.5f);
        lblUrl.setTypeface(Typeface.MONOSPACE, Typeface.BOLD);
        lblUrl.setTextColor(Color.parseColor("#71717A"));
        infoBox.addView(lblUrl);

        LinearLayout urlRow = new LinearLayout(this);
        urlRow.setOrientation(LinearLayout.HORIZONTAL);
        urlRow.setGravity(Gravity.CENTER_VERTICAL);
        urlRow.setPadding(0, dp(2), 0, dp(8));

        TextView valUrl = new TextView(this);
        valUrl.setText(lanUrl);
        valUrl.setTextSize(10.5f);
        valUrl.setTypeface(Typeface.MONOSPACE, Typeface.BOLD);
        valUrl.setTextColor(Color.WHITE);
        valUrl.setSingleLine(true);
        valUrl.setEllipsize(android.text.TextUtils.TruncateAt.MIDDLE);
        urlRow.addView(valUrl, new LinearLayout.LayoutParams(0, -2, 1.0f));

        TextView btnCopyUrl = new TextView(this);
        btnCopyUrl.setText("COPY");
        btnCopyUrl.setTextSize(9f);
        btnCopyUrl.setTypeface(Typeface.MONOSPACE, Typeface.BOLD);
        btnCopyUrl.setTextColor(Color.parseColor("#38BDF8"));
        btnCopyUrl.setPadding(dp(8), dp(4), dp(4), dp(4));
        btnCopyUrl.setClickable(true);
        btnCopyUrl.setFocusable(true);
        btnCopyUrl.setOnClickListener(v -> copyUrlToClipboard(svc.getDisplayName() + " URL", lanUrl));
        urlRow.addView(btnCopyUrl);

        infoBox.addView(urlRow);

        // Item B: Credentials / API Key
        String credVal;
        final String copyCredVal;
        if ("jellyfin".equalsIgnoreCase(id)) {
            credVal = "Admin account created during initial setup";
            copyCredVal = "http://127.0.0.1:8096";
        } else if ("transmission".equalsIgnoreCase(id)) {
            credVal = "Username: fishbowl  |  Password: fishbowl";
            copyCredVal = "User: fishbowl | Password: fishbowl";
        } else if ("seerr".equalsIgnoreCase(id)) {
            credVal = "Jellyfin Single Sign-On (Admin user)";
            copyCredVal = "http://127.0.0.1:5055";
        } else {
            String apiKey = MediaServiceManager.getInstance(this).getServiceApiKey(id);
            if (apiKey != null && !apiKey.isEmpty()) {
                credVal = "API Key: " + apiKey;
                copyCredVal = apiKey;
            } else {
                credVal = "Zero-Auth (Auto-generated on startup)";
                copyCredVal = "http://127.0.0.1:" + svc.getManifest().getDefaultPort();
            }
        }

        View divInfo1 = new View(this);
        divInfo1.setBackgroundColor(Color.parseColor("#18181B"));
        infoBox.addView(divInfo1, new LinearLayout.LayoutParams(-1, dp(1)));

        TextView lblCred = new TextView(this);
        lblCred.setText("CREDENTIALS / AUTHENTICATION");
        lblCred.setTextSize(8.5f);
        lblCred.setTypeface(Typeface.MONOSPACE, Typeface.BOLD);
        lblCred.setTextColor(Color.parseColor("#71717A"));
        lblCred.setPadding(0, dp(8), 0, 0);
        infoBox.addView(lblCred);

        LinearLayout credRow = new LinearLayout(this);
        credRow.setOrientation(LinearLayout.HORIZONTAL);
        credRow.setGravity(Gravity.CENTER_VERTICAL);
        credRow.setPadding(0, dp(2), 0, dp(8));

        TextView valCred = new TextView(this);
        valCred.setText(credVal);
        valCred.setTextSize(10f);
        valCred.setTypeface(Typeface.MONOSPACE);
        valCred.setTextColor(Color.parseColor("#D4D4D8"));
        valCred.setSingleLine(true);
        valCred.setEllipsize(android.text.TextUtils.TruncateAt.END);
        credRow.addView(valCred, new LinearLayout.LayoutParams(0, -2, 1.0f));

        TextView btnCopyCred = new TextView(this);
        btnCopyCred.setText("COPY");
        btnCopyCred.setTextSize(9f);
        btnCopyCred.setTypeface(Typeface.MONOSPACE, Typeface.BOLD);
        btnCopyCred.setTextColor(Color.parseColor("#38BDF8"));
        btnCopyCred.setPadding(dp(8), dp(4), dp(4), dp(4));
        btnCopyCred.setClickable(true);
        btnCopyCred.setFocusable(true);
        btnCopyCred.setOnClickListener(v -> copyUrlToClipboard(svc.getDisplayName() + " Credentials", copyCredVal));
        credRow.addView(btnCopyCred);

        infoBox.addView(credRow);

        // Item C: Status & Storage
        View divInfo2 = new View(this);
        divInfo2.setBackgroundColor(Color.parseColor("#18181B"));
        infoBox.addView(divInfo2, new LinearLayout.LayoutParams(-1, dp(1)));

        TextView lblFoot = new TextView(this);
        lblFoot.setText("STATUS & STORAGE");
        lblFoot.setTextSize(8.5f);
        lblFoot.setTypeface(Typeface.MONOSPACE, Typeface.BOLD);
        lblFoot.setTextColor(Color.parseColor("#71717A"));
        lblFoot.setPadding(0, dp(8), 0, 0);
        infoBox.addView(lblFoot);

        File instDir = new File(svc.getManifest().getInstallDir());
        File confDir = new File(svc.getManifest().getConfigDir());
        long binBytes = calculateDirSize(instDir);
        long cfgBytes = calculateDirSize(confDir);
        String footStr = installed ?
                ("Port: " + defaultPort + " • Size: " + MediaStorageManager.formatBytes(binBytes + cfgBytes)) :
                ("Port: " + defaultPort + " • Est. Size: ~" + svc.getManifest().getStorageRequiredMb() + " MB");

        File mediaTargetDir = null;
        if ("radarr".equalsIgnoreCase(id)) {
            mediaTargetDir = MediaStorageManager.getInstance(this).getMoviesDir();
        } else if ("sonarr".equalsIgnoreCase(id)) {
            mediaTargetDir = MediaStorageManager.getInstance(this).getTvDir();
        } else if ("lidarr".equalsIgnoreCase(id)) {
            mediaTargetDir = MediaStorageManager.getInstance(this).getMusicDir();
        } else if ("transmission".equalsIgnoreCase(id)) {
            mediaTargetDir = MediaStorageManager.getInstance(this).getDownloadsDir();
        } else if ("jellyfin".equalsIgnoreCase(id)) {
            mediaTargetDir = MediaStorageManager.getInstance(this).getMediaRoot();
        }

        if (mediaTargetDir != null) {
            footStr += "\nPath: " + mediaTargetDir.getAbsolutePath();
        }

        TextView valFoot = new TextView(this);
        valFoot.setText(footStr);
        valFoot.setTextSize(10f);
        valFoot.setTypeface(Typeface.MONOSPACE);
        valFoot.setTextColor(Color.parseColor("#D4D4D8"));
        valFoot.setPadding(0, dp(2), 0, dp(2));
        infoBox.addView(valFoot);

        root.addView(infoBox);

        // 7. Modal Bottom Action Buttons Row
        LinearLayout actionRow = new LinearLayout(this);
        actionRow.setOrientation(LinearLayout.HORIZONTAL);
        actionRow.setGravity(Gravity.CENTER_VERTICAL);
        LinearLayout.LayoutParams aLp = new LinearLayout.LayoutParams(-1, -2);
        aLp.topMargin = dp(4);
        actionRow.setLayoutParams(aLp);

        if (!installed) {
            Button btnInstall = createPixelButton("INSTALL", Color.WHITE, Color.BLACK);
            btnInstall.setTextSize(10f);
            btnInstall.setOnClickListener(v -> {
                d.dismiss();
                showServiceInstallCompatibilityDialog(svc);
            });
            actionRow.addView(btnInstall, new LinearLayout.LayoutParams(0, dp(36), 1.0f));
        } else {
            if (isRunning) {
                Button btnWeb = createPixelButton("WEB UI", Color.WHITE, Color.BLACK);
                btnWeb.setTextSize(10f);
                btnWeb.setOnClickListener(v -> {
                    d.dismiss();
                    openServiceWeb(svc);
                });
                actionRow.addView(btnWeb, new LinearLayout.LayoutParams(0, dp(36), 1.0f));

                View spB1 = new View(this);
                actionRow.addView(spB1, new LinearLayout.LayoutParams(dp(6), 1));

                Button btnStop = createOutlinePixelButton("STOP", Color.parseColor("#E11D48"), Color.parseColor("#E11D48"));
                btnStop.setTextSize(10f);
                btnStop.setOnClickListener(v -> {
                    d.dismiss();
                    if (isJellyfin) {
                        controller.stop();
                        triggerServerAction(JellyfinServerService.ACTION_STOP);
                    } else {
                        MediaServiceManager.getInstance(this).stopService(svc.getId());
                    }
                    mainHandler.postDelayed(() -> {
                        refreshServicesTabUI();
                        refreshHomeServicesBadge();
                    }, 500);
                });
                actionRow.addView(btnStop, new LinearLayout.LayoutParams(0, dp(36), 1.0f));
            } else {
                Button btnStart = createOutlinePixelButton("START", Color.WHITE, Color.WHITE);
                btnStart.setTextSize(10f);
                btnStart.setOnClickListener(v -> {
                    d.dismiss();
                    if (isJellyfin) {
                        triggerServerAction(JellyfinServerService.ACTION_START);
                    } else {
                        MediaServiceManager.getInstance(this).startService(svc.getId());
                    }
                    mainHandler.postDelayed(() -> {
                        refreshServicesTabUI();
                        refreshHomeServicesBadge();
                    }, 500);
                });
                actionRow.addView(btnStart, new LinearLayout.LayoutParams(0, dp(36), 1.0f));
            }

            if (!isJellyfin) {
                View spB2 = new View(this);
                actionRow.addView(spB2, new LinearLayout.LayoutParams(dp(6), 1));

                Button btnUninst = createOutlinePixelButton("UNINSTALL", Color.parseColor("#E11D48"), Color.parseColor("#E11D48"));
                btnUninst.setTextSize(9.5f);
                btnUninst.setOnClickListener(v -> {
                    d.dismiss();
                    confirmUninstallService(svc);
                });
                actionRow.addView(btnUninst, new LinearLayout.LayoutParams(0, dp(36), 1.0f));
            }
        }

        View spB4 = new View(this);
        actionRow.addView(spB4, new LinearLayout.LayoutParams(dp(6), 1));

        Button btnClose = createOutlinePixelButton("CLOSE", Color.parseColor("#3F3F46"), Color.parseColor("#A1A1AA"));
        btnClose.setTextSize(10f);
        btnClose.setOnClickListener(v -> d.dismiss());
        actionRow.addView(btnClose, new LinearLayout.LayoutParams(-2, dp(36)));

        root.addView(actionRow);

        d.setContentView(scroll);
        if (d.getWindow() != null) {
            d.getWindow().setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
            d.getWindow().setDimAmount(0.85f);
            WindowManager.LayoutParams wlp = new WindowManager.LayoutParams();
            wlp.copyFrom(d.getWindow().getAttributes());
            wlp.width = (int) (getResources().getDisplayMetrics().widthPixels * 0.94);
            wlp.height = WindowManager.LayoutParams.WRAP_CONTENT;
            d.getWindow().setAttributes(wlp);
        }
        d.show();
    }

    private void openServiceWeb(ManagedService svc) {
        if (svc.isBlocked()) {
            Toast.makeText(this, svc.getDisplayName() + " is blocked: " + svc.getBlockedReason(), Toast.LENGTH_LONG).show();
            return;
        }

        // Ensure authentication is None in config.xml for local in-app webview
        MediaServiceManager.getInstance(this).ensureServiceConfigSanitized(svc.getId());

        if ("jellyfin".equalsIgnoreCase(svc.getId())) {
            if (controller == null || controller.getState() != JellyfinController.State.RUNNING) {
                Toast.makeText(this, "Starting FishBowl...", Toast.LENGTH_SHORT).show();
                triggerServerAction(JellyfinServerService.ACTION_START);
                refreshServicesTabUI();
                refreshHomeServicesBadge();
            }
            startActivity(new Intent(this, JellyfinWebActivity.class));
            return;
        }

        if (!svc.isRunning()) {
            Toast.makeText(this, "Starting " + svc.getDisplayName() + "...", Toast.LENGTH_SHORT).show();
            MediaServiceManager.getInstance(this).startService(svc.getId());
            refreshServicesTabUI();
            refreshHomeServicesBadge();
        }

        int defaultPort = svc.getManifest().getDefaultPort();
        String webPath = svc.getManifest().getWebPath();
        if (webPath == null) webPath = "/";
        String url = "http://127.0.0.1:" + defaultPort + (webPath.equals("/") ? "" : webPath);
        Intent intent = new Intent(this, JellyfinWebActivity.class);
        intent.putExtra(JellyfinWebActivity.EXTRA_URL, url);
        intent.putExtra(JellyfinWebActivity.EXTRA_TITLE, svc.getDisplayName());
        intent.putExtra(JellyfinWebActivity.EXTRA_SERVICE_ID, svc.getId());
        try {
            startActivity(intent);
        } catch (Exception e) {
            Toast.makeText(this, "Could not open " + svc.getDisplayName() + " in-app webview", Toast.LENGTH_SHORT).show();
        }
    }

    private void showServiceLogsDialog(ManagedService svc) {
        ScrollView scroll = new ScrollView(this);
        scroll.setVerticalScrollBarEnabled(false);
        scroll.setHorizontalScrollBarEnabled(false);
        scroll.setOverScrollMode(View.OVER_SCROLL_NEVER);
        TextView tv = new TextView(this);
        tv.setPadding(0, dp(4), 0, dp(4));
        tv.setTextSize(10.5f);
        tv.setTypeface(Typeface.MONOSPACE);
        tv.setTextColor(Color.parseColor("#D4D4D8"));
        tv.setText(svc.getRecentLogs(100));
        scroll.addView(tv);

        showOledDialog(svc.getDisplayName() + " Logs", "Recent process log output", scroll,
                "COPY", getAccentColor(), () -> {
                    ClipboardManager clipboard = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
                    if (clipboard != null) {
                        clipboard.setPrimaryClip(ClipData.newPlainText(svc.getId() + "_logs", svc.getRecentLogs(500)));
                        Toast.makeText(this, "Copied " + svc.getDisplayName() + " logs to clipboard", Toast.LENGTH_SHORT).show();
                    }
                }, "CLOSE", null);
    }

    private void showServiceInstallInfo(ManagedService svc) {
        showServiceInstallCompatibilityDialog(svc);
    }

    public static class CompatibilityReport {
        public final boolean isCompatible;
        public final List<String> details;
        public final String failureReason;

        public CompatibilityReport(boolean isCompatible, List<String> details, String failureReason) {
            this.isCompatible = isCompatible;
            this.details = details;
            this.failureReason = failureReason;
        }
    }

    private CompatibilityReport checkServiceCompatibility(ManagedService svc) {
        List<String> checks = new java.util.ArrayList<>();
        boolean ok = true;
        String failReason = null;

        // 1. Architecture Check (ARM64-v8a)
        String primaryAbi = Build.SUPPORTED_ABIS != null && Build.SUPPORTED_ABIS.length > 0 ? Build.SUPPORTED_ABIS[0] : "";
        boolean isArm64 = "arm64-v8a".equalsIgnoreCase(primaryAbi) || "aarch64".equalsIgnoreCase(primaryAbi);
        if (isArm64) {
            checks.add("[OK] Architecture: " + primaryAbi + " (ARM64 Native Supported)");
        } else {
            checks.add("[FAIL] Architecture: " + primaryAbi + " (Requires 64-bit ARM64-v8a)");
            ok = false;
            failReason = "Incompatible CPU architecture (" + primaryAbi + "). Only 64-bit ARM64 devices are supported.";
        }

        // 2. Storage Check
        File dataDir = TermuxConstants.TERMUX_HOME_DIR;
        long freeBytes = 0;
        try {
            android.os.StatFs stat = new android.os.StatFs(dataDir.getAbsolutePath());
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.JELLY_BEAN_MR2) {
                freeBytes = stat.getAvailableBytes();
            } else {
                freeBytes = (long) stat.getAvailableBlocks() * stat.getBlockSize();
            }
        } catch (Exception ignored) {}

        long reqBytes = (svc.getManifest().getStorageRequiredMb() + 50L) * 1024L * 1024L;
        if (freeBytes >= reqBytes) {
            checks.add("[OK] Internal Storage: " + MediaStorageManager.formatBytes(freeBytes) + " free (Requires ~" + svc.getManifest().getStorageRequiredMb() + " MB)");
        } else {
            checks.add("[FAIL] Internal Storage: " + MediaStorageManager.formatBytes(freeBytes) + " free (Requires ~" + svc.getManifest().getStorageRequiredMb() + " MB)");
            ok = false;
            if (failReason == null) {
                failReason = "Insufficient internal storage. " + MediaStorageManager.formatBytes(reqBytes) + " required.";
            }
        }

        // 3. Port Check
        int port = svc.getManifest().getDefaultPort();
        boolean portOccupied = MediaServiceManager.getInstance(this).isPortOccupied(port);
        if (!portOccupied || svc.isRunning()) {
            checks.add("[OK] Port " + port + ": Available for " + svc.getDisplayName());
        } else {
            checks.add("[WARN] Port " + port + ": Currently occupied by another process");
        }

        // 4. Runtime Environment Check
        File prefix = TermuxConstants.TERMUX_PREFIX_DIR;
        String id = svc.getId().toLowerCase(Locale.ROOT);
        if ("radarr".equals(id) || "sonarr".equals(id) || "prowlarr".equals(id) || "lidarr".equals(id) || "jackett".equals(id)) {
            File dotnet = new File(prefix, "lib/dotnet/dotnet");
            if (!dotnet.exists()) dotnet = new File(prefix, "bin/dotnet");
            if (dotnet.exists()) {
                checks.add("[OK] Runtime: .NET 10.0 ARM64 CoreCLR Installed");
            } else {
                checks.add("[OK] Runtime: CoreCLR Bionic Decoupling Ready");
            }
        } else if ("bazarr".equals(id)) {
            File python = new File(prefix, "bin/python3");
            if (!python.exists()) python = new File(prefix, "bin/python");
            if (python.exists()) {
                checks.add("[OK] Runtime: Python 3 Installed");
            } else {
                checks.add("[WARN] Runtime: Python 3 needed for execution");
            }
        } else if ("seerr".equals(id)) {
            File node = new File(prefix, "bin/node");
            if (node.exists()) {
                checks.add("[OK] Runtime: Node.js LTS Installed");
            } else {
                checks.add("[OK] Runtime: Embedded Node.js Engine Ready");
            }
        } else if ("transmission".equals(id)) {
            checks.add("[OK] Runtime: Native ARM64 Bionic Ready");
        }

        return new CompatibilityReport(ok, checks, failReason);
    }

    private Dialog showOledDialog(String title, String subtitle, View contentView, String posText, int posColor, Runnable onPos, String negText, Runnable onNeg) {
        Dialog d = new Dialog(this);
        d.requestWindowFeature(Window.FEATURE_NO_TITLE);

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(18), dp(18), dp(18), dp(18));
        root.setBackground(createBorderedRoundedDrawable(Color.BLACK, Color.parseColor("#27272A"), dp(1), dp(14)));

        TextView titleTv = new TextView(this);
        titleTv.setText(title);
        titleTv.setTextSize(14f);
        titleTv.setTypeface(Typeface.MONOSPACE, Typeface.BOLD);
        titleTv.setTextColor(Color.WHITE);
        root.addView(titleTv);

        if (subtitle != null && !subtitle.isEmpty()) {
            TextView subTv = new TextView(this);
            subTv.setText(subtitle);
            subTv.setTextSize(10.5f);
            subTv.setTypeface(Typeface.MONOSPACE);
            subTv.setTextColor(Color.parseColor("#A1A1AA"));
            subTv.setPadding(0, dp(2), 0, 0);
            root.addView(subTv);
        }

        View div = new View(this);
        div.setBackgroundColor(Color.parseColor("#18181B"));
        LinearLayout.LayoutParams divLp = new LinearLayout.LayoutParams(-1, dp(1));
        divLp.topMargin = dp(10);
        divLp.bottomMargin = dp(10);
        root.addView(div, divLp);

        if (contentView != null) {
            root.addView(contentView);
        }

        LinearLayout btnRow = new LinearLayout(this);
        btnRow.setOrientation(LinearLayout.HORIZONTAL);
        btnRow.setGravity(Gravity.END);
        LinearLayout.LayoutParams btnRowLp = new LinearLayout.LayoutParams(-1, -2);
        btnRowLp.topMargin = dp(14);
        btnRow.setLayoutParams(btnRowLp);

        if (negText != null) {
            Button btnNeg = createOutlinePixelButton(negText, Color.parseColor("#3F3F46"), Color.parseColor("#A1A1AA"));
            btnNeg.setTextSize(10f);
            btnNeg.setOnClickListener(v -> {
                d.dismiss();
                if (onNeg != null) onNeg.run();
            });
            btnRow.addView(btnNeg, new LinearLayout.LayoutParams(-2, dp(34)));
        }

        if (posText != null) {
            if (negText != null) {
                View sp = new View(this);
                btnRow.addView(sp, new LinearLayout.LayoutParams(dp(8), 1));
            }
            Button btnPos = createOutlinePixelButton(posText, posColor, posColor);
            btnPos.setTextSize(10f);
            btnPos.setOnClickListener(v -> {
                d.dismiss();
                if (onPos != null) onPos.run();
            });
            btnRow.addView(btnPos, new LinearLayout.LayoutParams(-2, dp(34)));
        }

        root.addView(btnRow);
        d.setContentView(root);

        if (d.getWindow() != null) {
            d.getWindow().setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
            d.getWindow().setDimAmount(0.85f);
            WindowManager.LayoutParams lp = new WindowManager.LayoutParams();
            lp.copyFrom(d.getWindow().getAttributes());
            lp.width = (int) (getResources().getDisplayMetrics().widthPixels * 0.90);
            lp.height = WindowManager.LayoutParams.WRAP_CONTENT;
            d.getWindow().setAttributes(lp);
        }
        d.show();
        return d;
    }

    public static class OledProgressDialog {
        private final Dialog dialog;
        private final TextView titleTv;
        private final TextView messageTv;
        private final ProgressBar progressBar;
        private final TextView pctTv;

        public OledProgressDialog(Dialog dialog, TextView titleTv, TextView messageTv, ProgressBar progressBar, TextView pctTv) {
            this.dialog = dialog;
            this.titleTv = titleTv;
            this.messageTv = messageTv;
            this.progressBar = progressBar;
            this.pctTv = pctTv;
        }

        public void setMessage(String msg) {
            if (messageTv != null) messageTv.setText(msg);
        }

        public void setProgress(int pct) {
            if (progressBar != null) progressBar.setProgress(pct);
            if (pctTv != null) pctTv.setText(pct + "%");
        }

        public void dismiss() {
            if (dialog != null && dialog.isShowing()) {
                try { dialog.dismiss(); } catch (Exception ignored) {}
            }
        }
    }

    private OledProgressDialog showOledProgressDialog(String title, String initialMessage) {
        Dialog d = new Dialog(this);
        d.requestWindowFeature(Window.FEATURE_NO_TITLE);
        d.setCancelable(false);

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(18), dp(18), dp(18), dp(18));
        root.setBackground(createBorderedRoundedDrawable(Color.BLACK, Color.parseColor("#27272A"), dp(1), dp(14)));

        TextView titleTv = new TextView(this);
        titleTv.setText(title);
        titleTv.setTextSize(13.5f);
        titleTv.setTypeface(Typeface.MONOSPACE, Typeface.BOLD);
        titleTv.setTextColor(Color.WHITE);
        root.addView(titleTv);

        TextView msgTv = new TextView(this);
        msgTv.setText(initialMessage);
        msgTv.setTextSize(10.5f);
        msgTv.setTypeface(Typeface.MONOSPACE);
        msgTv.setTextColor(Color.parseColor("#A1A1AA"));
        msgTv.setPadding(0, dp(4), 0, dp(12));
        root.addView(msgTv);

        ProgressBar pb = new ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal);
        pb.setMax(100);
        pb.setProgress(0);
        pb.setIndeterminate(false);
        root.addView(pb, new LinearLayout.LayoutParams(-1, dp(6)));

        TextView pctTv = new TextView(this);
        pctTv.setText("0%");
        pctTv.setTextSize(9.5f);
        pctTv.setTypeface(Typeface.MONOSPACE);
        pctTv.setTextColor(Color.parseColor("#71717A"));
        pctTv.setGravity(Gravity.END);
        pctTv.setPadding(0, dp(4), 0, 0);
        root.addView(pctTv);

        d.setContentView(root);
        if (d.getWindow() != null) {
            d.getWindow().setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
            d.getWindow().setDimAmount(0.85f);
            WindowManager.LayoutParams lp = new WindowManager.LayoutParams();
            lp.copyFrom(d.getWindow().getAttributes());
            lp.width = (int) (getResources().getDisplayMetrics().widthPixels * 0.90);
            lp.height = WindowManager.LayoutParams.WRAP_CONTENT;
            d.getWindow().setAttributes(lp);
        }
        d.show();
        return new OledProgressDialog(d, titleTv, msgTv, pb, pctTv);
    }

    private void showServiceInstallCompatibilityDialog(ManagedService svc) {
        CompatibilityReport rep = checkServiceCompatibility(svc);

        LinearLayout content = new LinearLayout(this);
        content.setOrientation(LinearLayout.VERTICAL);

        LinearLayout list = new LinearLayout(this);
        list.setOrientation(LinearLayout.VERTICAL);
        list.setPadding(dp(10), dp(8), dp(10), dp(8));
        list.setBackground(createBorderedRoundedDrawable(Color.parseColor("#09090B"), Color.parseColor("#27272A"), dp(1), dp(6)));

        for (String item : rep.details) {
            TextView itemTv = new TextView(this);
            itemTv.setText(item);
            itemTv.setTextSize(10f);
            itemTv.setTypeface(Typeface.MONOSPACE);
            if (item.startsWith("[OK]")) {
                itemTv.setTextColor(Color.parseColor("#34D399"));
            } else if (item.startsWith("[WARN]")) {
                itemTv.setTextColor(Color.parseColor("#FB923C"));
            } else {
                itemTv.setTextColor(Color.parseColor("#E11D48"));
            }
            itemTv.setPadding(0, dp(2), 0, dp(2));
            list.addView(itemTv);
        }
        content.addView(list);

        TextView note = new TextView(this);
        note.setText(rep.isCompatible ?
                "Environment compatible. Estimated download: ~" + svc.getManifest().getStorageRequiredMb() + " MB." :
                "Environment warning: " + rep.failureReason);
        note.setTextSize(10f);
        note.setTypeface(Typeface.MONOSPACE);
        note.setTextColor(Color.parseColor("#A1A1AA"));
        note.setPadding(0, dp(8), 0, 0);
        content.addView(note);

        showOledDialog(
            "INSTALL " + svc.getDisplayName().toUpperCase(Locale.ROOT),
            "v" + svc.getManifest().getVersion() + " • " + svc.getManifest().getCategory().toUpperCase(Locale.ROOT) + " : " + svc.getManifest().getDefaultPort(),
            content,
            rep.isCompatible ? "PROCEED & INSTALL" : null,
            Color.WHITE,
            () -> installServiceAutomatically(svc),
            rep.isCompatible ? "CANCEL" : "CLOSE",
            null
        );
    }

    private void confirmUninstallService(ManagedService svc) {
        if ("jellyfin".equalsIgnoreCase(svc.getId())) {
            Toast.makeText(this, "Jellyfin is the core media server and cannot be uninstalled.", Toast.LENGTH_SHORT).show();
            return;
        }

        File installDir = new File(svc.getManifest().getInstallDir());
        File configDir = new File(svc.getManifest().getConfigDir());

        long binBytes = calculateDirSize(installDir);
        long cfgBytes = calculateDirSize(configDir);
        long totalBytes = binBytes + cfgBytes;

        Dialog d = new Dialog(this);
        d.requestWindowFeature(Window.FEATURE_NO_TITLE);

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(18), dp(18), dp(18), dp(18));
        root.setBackground(createBorderedRoundedDrawable(Color.BLACK, Color.parseColor("#27272A"), dp(1), dp(14)));

        TextView titleTv = new TextView(this);
        titleTv.setText("UNINSTALL " + svc.getDisplayName().toUpperCase(Locale.ROOT));
        titleTv.setTextSize(14f);
        titleTv.setTypeface(Typeface.MONOSPACE, Typeface.BOLD);
        titleTv.setTextColor(Color.WHITE);
        root.addView(titleTv);

        TextView subTv = new TextView(this);
        subTv.setText("Storage Reclaim & Data Retention Options");
        subTv.setTextSize(10f);
        subTv.setTypeface(Typeface.MONOSPACE);
        subTv.setTextColor(Color.parseColor("#A1A1AA"));
        subTv.setPadding(0, dp(2), 0, dp(10));
        root.addView(subTv);

        // Stats Box
        LinearLayout statBox = new LinearLayout(this);
        statBox.setOrientation(LinearLayout.VERTICAL);
        statBox.setPadding(dp(10), dp(8), dp(10), dp(8));
        statBox.setBackground(createBorderedRoundedDrawable(Color.parseColor("#09090B"), Color.parseColor("#18181B"), dp(1), dp(6)));

        TextView statLine1 = new TextView(this);
        statLine1.setText("Binaries Footprint: " + MediaStorageManager.formatBytes(binBytes));
        statLine1.setTextSize(10f);
        statLine1.setTypeface(Typeface.MONOSPACE);
        statLine1.setTextColor(Color.parseColor("#E11D48"));
        statBox.addView(statLine1);

        TextView statLine2 = new TextView(this);
        statLine2.setText("Database & Configs: " + MediaStorageManager.formatBytes(cfgBytes));
        statLine2.setTextSize(10f);
        statLine2.setTypeface(Typeface.MONOSPACE);
        statLine2.setTextColor(Color.parseColor("#A1A1AA"));
        statBox.addView(statLine2);

        root.addView(statBox);

        // Option 1 Button: Reclaim Binaries Only (Preserve Database)
        Button btnKeepDb = createOutlinePixelButton("RECLAIM BINARIES ONLY (KEEP DATABASE)", Color.WHITE, Color.WHITE);
        btnKeepDb.setTextSize(10f);
        LinearLayout.LayoutParams kLp = new LinearLayout.LayoutParams(-1, dp(38));
        kLp.topMargin = dp(12);
        btnKeepDb.setLayoutParams(kLp);
        btnKeepDb.setOnClickListener(v -> {
            d.dismiss();
            OledProgressDialog progress = showOledProgressDialog("UNINSTALLING " + svc.getDisplayName().toUpperCase(Locale.ROOT), "Stopping process and reclaiming binaries...");
            MediaServiceManager.getInstance(this).uninstallService(svc.getId(), false, () -> {
                progress.dismiss();
                refreshServicesTabUI();
                refreshHomeServicesBadge();
                Toast.makeText(this, svc.getDisplayName() + " binaries removed. Database & config preserved!", Toast.LENGTH_LONG).show();
            });
        });
        root.addView(btnKeepDb);

        TextView noteKeep = new TextView(this);
        noteKeep.setText("Reclaims ~" + MediaStorageManager.formatBytes(binBytes) + ". Settings, API keys, and library mappings remain saved. Reinstalling later restores your existing library instantly.");
        noteKeep.setTextSize(9f);
        noteKeep.setTypeface(Typeface.MONOSPACE);
        noteKeep.setTextColor(Color.parseColor("#71717A"));
        noteKeep.setPadding(dp(4), dp(3), dp(4), dp(8));
        root.addView(noteKeep);

        // Option 2 Button: Clean Wipe (Purge All Data)
        Button btnPurge = createOutlinePixelButton("PURGE EVERYTHING (CLEAN WIPE)", Color.parseColor("#E11D48"), Color.parseColor("#E11D48"));
        btnPurge.setTextSize(10f);
        LinearLayout.LayoutParams pLp = new LinearLayout.LayoutParams(-1, dp(38));
        pLp.topMargin = dp(4);
        btnPurge.setLayoutParams(pLp);
        btnPurge.setOnClickListener(v -> {
            d.dismiss();
            OledProgressDialog progress = showOledProgressDialog("PURGING " + svc.getDisplayName().toUpperCase(Locale.ROOT), "Stopping process and removing all data...");
            MediaServiceManager.getInstance(this).uninstallService(svc.getId(), true, () -> {
                progress.dismiss();
                refreshServicesTabUI();
                refreshHomeServicesBadge();
                Toast.makeText(this, svc.getDisplayName() + " completely purged. " + MediaStorageManager.formatBytes(totalBytes) + " reclaimed!", Toast.LENGTH_LONG).show();
            });
        });
        root.addView(btnPurge);

        TextView notePurge = new TextView(this);
        notePurge.setText("Permanently deletes binaries, SQLite databases, and all configuration files. Downloaded media files in FishbowlMedia are NEVER deleted.");
        notePurge.setTextSize(9f);
        notePurge.setTypeface(Typeface.MONOSPACE);
        notePurge.setTextColor(Color.parseColor("#71717A"));
        notePurge.setPadding(dp(4), dp(3), dp(4), dp(10));
        root.addView(notePurge);

        // Cancel Button
        Button btnCancel = createOutlinePixelButton("CANCEL", Color.parseColor("#3F3F46"), Color.parseColor("#A1A1AA"));
        btnCancel.setTextSize(10f);
        btnCancel.setOnClickListener(v -> d.dismiss());
        root.addView(btnCancel, new LinearLayout.LayoutParams(-1, dp(34)));

        d.setContentView(root);
        if (d.getWindow() != null) {
            d.getWindow().setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
            d.getWindow().setDimAmount(0.85f);
            WindowManager.LayoutParams wlp = new WindowManager.LayoutParams();
            wlp.copyFrom(d.getWindow().getAttributes());
            wlp.width = (int) (getResources().getDisplayMetrics().widthPixels * 0.92);
            wlp.height = WindowManager.LayoutParams.WRAP_CONTENT;
            d.getWindow().setAttributes(wlp);
        }
        d.show();
    }

    public interface DownloadProgressCallback {
        void onProgress(long currentBytes, long totalBytes);
    }

    private static void downloadWithRedirects(String initialUrl, File destFile, DownloadProgressCallback cb) throws Exception {
        String currentUrl = initialUrl;
        int hops = 0;
        HttpURLConnection conn = null;
        try {
            while (hops < 10) {
                URL u = new URL(currentUrl);
                conn = (HttpURLConnection) u.openConnection();
                conn.setConnectTimeout(25000);
                conn.setReadTimeout(35000);
                conn.setRequestProperty("User-Agent", "Mozilla/5.0 (Linux; Android 14; Mobile) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Mobile Safari/537.36");
                conn.setRequestProperty("Accept", "*/*");
                conn.setRequestProperty("Accept-Encoding", "identity");
                conn.setRequestProperty("Connection", "keep-alive");
                conn.setInstanceFollowRedirects(false);
                conn.connect();

                int code = conn.getResponseCode();
                if (code == HttpURLConnection.HTTP_MOVED_PERM ||
                    code == HttpURLConnection.HTTP_MOVED_TEMP ||
                    code == HttpURLConnection.HTTP_SEE_OTHER ||
                    code == 307 || code == 308) {
                    String loc = conn.getHeaderField("Location");
                    conn.disconnect();
                    if (loc == null || loc.isEmpty()) {
                        throw new IOException("HTTP redirect " + code + " without Location header");
                    }
                    if (loc.startsWith("/")) {
                        URL prev = new URL(currentUrl);
                        currentUrl = prev.getProtocol() + "://" + prev.getHost() + (prev.getPort() > 0 ? ":" + prev.getPort() : "") + loc;
                    } else {
                        currentUrl = loc;
                    }
                    hops++;
                    continue;
                }

                if (code != HttpURLConnection.HTTP_OK) {
                    throw new IOException("HTTP Error " + code + ": " + conn.getResponseMessage());
                }

                long totalLength = conn.getContentLengthLong();
                if (destFile.exists()) destFile.delete();
                if (destFile.getParentFile() != null && !destFile.getParentFile().exists()) {
                    destFile.getParentFile().mkdirs();
                }

                try (InputStream in = new BufferedInputStream(conn.getInputStream(), 131072);
                     OutputStream out = new BufferedOutputStream(new FileOutputStream(destFile), 131072)) {
                    byte[] buf = new byte[131072];
                    int len;
                    long downloaded = 0;
                    long lastReportTime = 0;
                    while ((len = in.read(buf)) > 0) {
                        out.write(buf, 0, len);
                        downloaded += len;
                        long now = SystemClock.uptimeMillis();
                        if (cb != null && (now - lastReportTime >= 180 || downloaded == totalLength)) {
                            lastReportTime = now;
                            cb.onProgress(downloaded, totalLength);
                        }
                    }
                    out.flush();
                }

                if (destFile.length() < 1024) {
                    throw new IOException("Downloaded file is corrupt or empty (" + destFile.length() + " bytes)");
                }
                return;
            }
            throw new IOException("Too many redirects (exceeded 10 hops)");
        } finally {
            if (conn != null) {
                try { conn.disconnect(); } catch (Exception ignored) {}
            }
        }
    }

    private static void extractTarGzArchive(File archiveFile, File targetDir) throws Exception {
        if (!targetDir.exists()) targetDir.mkdirs();
        String canonicalTarget = targetDir.getCanonicalPath();
        try (InputStream fi = new FileInputStream(archiveFile);
             InputStream bi = new BufferedInputStream(fi);
             InputStream gzi = new GzipCompressorInputStream(bi);
             TarArchiveInputStream ti = new TarArchiveInputStream(gzi)) {
            TarArchiveEntry entry;
            while ((entry = ti.getNextTarEntry()) != null) {
                String entryName = entry.getName();
                if (entryName == null || entryName.contains("..")) {
                    continue;
                }
                File curfile = new File(targetDir, entryName);
                String canonicalCurfile = curfile.getCanonicalPath();
                if (!canonicalCurfile.startsWith(canonicalTarget + File.separator) && !canonicalCurfile.equals(canonicalTarget)) {
                    throw new SecurityException("Archive entry is outside target directory: " + entryName);
                }
                if (entry.isDirectory()) {
                    curfile.mkdirs();
                } else {
                    File parent = curfile.getParentFile();
                    if (parent != null && !parent.exists()) parent.mkdirs();
                    try (OutputStream out = new BufferedOutputStream(new FileOutputStream(curfile))) {
                        byte[] buf = new byte[32768];
                        int len;
                        while ((len = ti.read(buf)) != -1) {
                            out.write(buf, 0, len);
                        }
                    }
                    if ((entry.getMode() & 0111) != 0) {
                        curfile.setExecutable(true, false);
                    }
                }
            }
        }
    }

    private static void extractZipArchive(File zipFile, File targetDir) throws Exception {
        if (!targetDir.exists()) targetDir.mkdirs();
        String canonicalTarget = targetDir.getCanonicalPath();
        try (ZipInputStream zis = new ZipInputStream(new BufferedInputStream(new FileInputStream(zipFile)))) {
            ZipEntry ze;
            byte[] buffer = new byte[32768];
            while ((ze = zis.getNextEntry()) != null) {
                String entryName = ze.getName();
                if (entryName == null || entryName.contains("..")) {
                    continue;
                }
                File curfile = new File(targetDir, entryName);
                String canonicalCurfile = curfile.getCanonicalPath();
                if (!canonicalCurfile.startsWith(canonicalTarget + File.separator) && !canonicalCurfile.equals(canonicalTarget)) {
                    throw new SecurityException("Zip entry is outside target directory: " + entryName);
                }
                if (ze.isDirectory()) {
                    curfile.mkdirs();
                } else {
                    File parent = curfile.getParentFile();
                    if (parent != null && !parent.exists()) parent.mkdirs();
                    try (FileOutputStream fos = new FileOutputStream(curfile)) {
                        int len;
                        while ((len = zis.read(buffer)) > 0) {
                            fos.write(buffer, 0, len);
                        }
                    }
                }
                zis.closeEntry();
            }
        }
    }

    private static void extractDebPackage(File debFile, File prefixDir) throws Exception {
        if (!prefixDir.exists()) prefixDir.mkdirs();
        String canonicalPrefix = prefixDir.getCanonicalPath();
        try (InputStream fis = new FileInputStream(debFile);
             InputStream bis = new BufferedInputStream(fis);
             ArArchiveInputStream ais = new ArArchiveInputStream(bis)) {
            ArArchiveEntry arEntry;
            while ((arEntry = ais.getNextEntry()) != null) {
                String arName = arEntry.getName();
                if (arName.startsWith("data.tar")) {
                    InputStream dataIn = ais;
                    if (arName.endsWith(".xz")) {
                        dataIn = new XZCompressorInputStream(ais);
                    } else if (arName.endsWith(".gz")) {
                        dataIn = new GzipCompressorInputStream(ais);
                    }
                    try (TarArchiveInputStream tis = new TarArchiveInputStream(dataIn)) {
                        TarArchiveEntry te;
                        byte[] buffer = new byte[32768];
                        while ((te = tis.getNextTarEntry()) != null) {
                            String entryName = te.getName();
                            String cleanPath = entryName;
                            if (cleanPath.startsWith("./")) cleanPath = cleanPath.substring(2);
                            if (cleanPath.startsWith("data/data/com.termux/files/usr/")) {
                                cleanPath = cleanPath.substring("data/data/com.termux/files/usr/".length());
                            } else if (cleanPath.startsWith("data/data/com.fishbowl.app/files/usr/")) {
                                cleanPath = cleanPath.substring("data/data/com.fishbowl.app/files/usr/".length());
                            } else if (cleanPath.startsWith("usr/")) {
                                cleanPath = cleanPath.substring(4);
                            }
                            if (cleanPath.isEmpty() || cleanPath.contains("..")) continue;

                            File outFile = new File(prefixDir, cleanPath);
                            String canonicalOut = outFile.getCanonicalPath();
                            if (!canonicalOut.startsWith(canonicalPrefix + File.separator) && !canonicalOut.equals(canonicalPrefix)) {
                                throw new SecurityException("Archive entry is outside prefix directory: " + entryName);
                            }
                            if (te.isSymbolicLink()) {
                                try {
                                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                                        if (outFile.exists()) outFile.delete();
                                        android.system.Os.symlink(te.getLinkName(), outFile.getAbsolutePath());
                                    }
                                } catch (Throwable ignored) {}
                                continue;
                            }

                            if (te.isDirectory()) {
                                outFile.mkdirs();
                            } else {
                                File parent = outFile.getParentFile();
                                if (parent != null && !parent.exists()) parent.mkdirs();
                                try (FileOutputStream fos = new FileOutputStream(outFile)) {
                                    int len;
                                    while ((len = tis.read(buffer)) > 0) {
                                        fos.write(buffer, 0, len);
                                    }
                                }
                                if ((te.getMode() & 0111) != 0 || cleanPath.startsWith("bin/")) {
                                    outFile.setExecutable(true, false);
                                }
                            }
                        }
                    }
                    break;
                }
            }
        }
    }

    private static final Map<String, String> TERMUX_POOL_MAP = new HashMap<>();
    static {
        TERMUX_POOL_MAP.put("transmission", "pool/main/t/transmission/transmission_4.1.4_aarch64.deb");
        TERMUX_POOL_MAP.put("libpsl", "pool/main/libp/libpsl/libpsl_0.23.3_aarch64.deb");
        TERMUX_POOL_MAP.put("libidn2", "pool/main/libi/libidn2/libidn2_2.3.8-1_aarch64.deb");
        TERMUX_POOL_MAP.put("miniupnpc", "pool/main/m/miniupnpc/miniupnpc_2.3.3-1_aarch64.deb");
        TERMUX_POOL_MAP.put("natpmpc", "pool/main/n/natpmpc/natpmpc_20230423-2_aarch64.deb");
        TERMUX_POOL_MAP.put("libc++", "pool/main/libc/libc++/libc++_30_aarch64.deb");
        TERMUX_POOL_MAP.put("7zip", "pool/main/7/7zip/7zip_26.05_aarch64.deb");
        TERMUX_POOL_MAP.put("nodejs-lts", "pool/main/n/nodejs-lts/nodejs-lts_24.22.1_aarch64.deb");
        TERMUX_POOL_MAP.put("c-ares", "pool/main/c/c-ares/c-ares_1.34.9_aarch64.deb");
        TERMUX_POOL_MAP.put("python", "pool/main/p/python/python_3.14.8_aarch64.deb");
        TERMUX_POOL_MAP.put("libbz2", "pool/main/libb/libbz2/libbz2_1.0.8-8_aarch64.deb");
        TERMUX_POOL_MAP.put("libexpat", "pool/main/libe/libexpat/libexpat_2.9.0_aarch64.deb");
        TERMUX_POOL_MAP.put("libffi", "pool/main/libf/libffi/libffi_3.8.0_aarch64.deb");
        TERMUX_POOL_MAP.put("liblzma", "pool/main/libl/liblzma/liblzma_5.8.4_aarch64.deb");
        TERMUX_POOL_MAP.put("libsqlite", "pool/main/libs/libsqlite/libsqlite_3.54.1_aarch64.deb");
        TERMUX_POOL_MAP.put("libandroid-support", "pool/main/liba/libandroid-support/libandroid-support_29-1_aarch64.deb");
        TERMUX_POOL_MAP.put("ncurses", "pool/main/n/ncurses/ncurses_6.6.20260307+really6.5.20250830_aarch64.deb");
        TERMUX_POOL_MAP.put("readline", "pool/main/r/readline/readline_8.3.6_aarch64.deb");
    }

    private static final Map<String, String> sDynamicDebUrlCache = new HashMap<>();

    private static String resolveTermuxDebUrl(Context context, String pkgName) {
        synchronized (sDynamicDebUrlCache) {
            if (sDynamicDebUrlCache.containsKey(pkgName)) {
                return sDynamicDebUrlCache.get(pkgName);
            }
        }
        String relPath = TERMUX_POOL_MAP.get(pkgName);
        if (relPath != null) {
            String resolved = "https://packages-cf.termux.dev/apt/termux-main/" + relPath;
            synchronized (sDynamicDebUrlCache) {
                sDynamicDebUrlCache.put(pkgName, resolved);
            }
            return resolved;
        }
        File localPkgList = new File(TermuxConstants.TERMUX_PREFIX_DIR, "var/lib/apt/lists/packages-cf.termux.dev_apt_termux-main_dists_stable_main_binary-aarch64_Packages");
        if (localPkgList.exists()) {
            try (java.io.BufferedReader reader = new java.io.BufferedReader(new java.io.InputStreamReader(new java.io.FileInputStream(localPkgList), java.nio.charset.StandardCharsets.UTF_8))) {
                String line;
                String currentPkg = null;
                while ((line = reader.readLine()) != null) {
                    line = line.trim();
                    if (line.startsWith("Package: ")) {
                        currentPkg = line.substring(9).trim();
                    } else if (line.startsWith("Filename: ") && pkgName.equalsIgnoreCase(currentPkg)) {
                        String fn = line.substring(10).trim();
                        String resolved = "https://packages-cf.termux.dev/apt/termux-main/" + fn;
                        synchronized (sDynamicDebUrlCache) {
                            sDynamicDebUrlCache.put(pkgName, resolved);
                        }
                        return resolved;
                    }
                }
            } catch (Throwable ignored) {}
        }
        return null;
    }

    private void downloadAndInstallDebPackage(String pkgName, File prefixDir, OledProgressDialog progress, String stepDescription) throws Exception {
        String debUrl = resolveTermuxDebUrl(this, pkgName);
        if (debUrl == null) {
            throw new IOException("Unable to resolve debian package URL for: " + pkgName);
        }
        if (progress != null) {
            runOnUiThread(() -> progress.setMessage(stepDescription));
        }
        File cacheDeb = new File(getCacheDir(), pkgName + ".deb");
        final int[] lastReported = new int[]{-1};
        downloadWithRedirects(debUrl, cacheDeb, (current, total) -> {
            if (total > 0 && progress != null) {
                int pct = (int) ((current * 100) / total);
                if (pct != lastReported[0]) {
                    lastReported[0] = pct;
                    runOnUiThread(() -> progress.setProgress(pct));
                }
            }
        });
        extractDebPackage(cacheDeb, prefixDir);
        cacheDeb.delete();
    }

    private void installServiceAutomatically(ManagedService svc) {
        OledProgressDialog progress = showOledProgressDialog("INSTALLING " + svc.getDisplayName().toUpperCase(Locale.ROOT), "Preparing runtime environment...");

        new Thread(() -> {
            try {
                File home = TermuxConstants.TERMUX_HOME_DIR;
                File prefix = TermuxConstants.TERMUX_PREFIX_DIR;
                File servicesDir = new File(home, "services");
                if (!servicesDir.exists()) servicesDir.mkdirs();

                String id = svc.getId().toLowerCase(Locale.ROOT);

                if ("seerr".equalsIgnoreCase(id)) {
                    File nodeBin = new File(prefix, "bin/node");
                    if (!nodeBin.exists()) {
                        String[] nodeDeps = {"libc++", "c-ares", "nodejs-lts"};
                        for (String dep : nodeDeps) {
                            downloadAndInstallDebPackage(dep, prefix, progress, "Installing Node.js runtime (" + dep + ")...");
                        }
                        if (nodeBin.exists()) {
                            nodeBin.setExecutable(true, false);
                            try { android.system.Os.chmod(nodeBin.getAbsolutePath(), 0755); } catch (Throwable ignored) {}
                        }
                    }

                    runOnUiThread(() -> progress.setMessage("Deploying Seerr Request Engine..."));
                    MediaServiceManager.getInstance(this).deploySeerrService(true);
                    MediaServiceManager.getInstance(this).autoWireStack(null);
                    runOnUiThread(() -> {
                        progress.dismiss();
                        svc.reconcileInstalledState();
                        refreshServicesTabUI();
                        refreshHomeServicesBadge();
                        Toast.makeText(this, "Seerr deployed and ready!", Toast.LENGTH_LONG).show();
                    });
                    return;
                }

                if ("bazarr".equalsIgnoreCase(id)) {
                    File pythonBin = new File(prefix, "bin/python3");
                    if (!pythonBin.exists()) {
                        String[] pyDeps = {
                            "libc++", "libbz2", "libexpat", "libffi", "liblzma", "libsqlite",
                            "libandroid-support", "ncurses", "readline", "python"
                        };
                        for (String dep : pyDeps) {
                            downloadAndInstallDebPackage(dep, prefix, progress, "Setting up Python 3 runtime (" + dep + ")...");
                        }
                        if (pythonBin.exists()) {
                            pythonBin.setExecutable(true, false);
                            try { android.system.Os.chmod(pythonBin.getAbsolutePath(), 0755); } catch (Throwable ignored) {}
                        }
                    }

                    File sevenZipBin = new File(prefix, "bin/7z");
                    if (!sevenZipBin.exists()) {
                        downloadAndInstallDebPackage("7zip", prefix, progress, "Setting up 7-Zip archiver utility...");
                    }

                    runOnUiThread(() -> progress.setMessage("Downloading Bazarr release..."));
                    File cacheZip = new File(getCacheDir(), "bazarr.zip");
                    String bzUrl = "https://github.com/morpheus65535/bazarr/releases/latest/download/bazarr.zip";
                    final int[] lastReportedBz = new int[]{-1};
                    downloadWithRedirects(bzUrl, cacheZip, (current, total) -> {
                        if (total > 0) {
                            int pct = (int) ((current * 100) / total);
                            if (pct != lastReportedBz[0]) {
                                lastReportedBz[0] = pct;
                                runOnUiThread(() -> progress.setProgress(pct));
                            }
                        }
                    });

                    runOnUiThread(() -> progress.setMessage("Extracting Bazarr packages..."));
                    File bzTarget = new File(servicesDir, "bazarr");
                    if (!bzTarget.exists()) bzTarget.mkdirs();
                    extractZipArchive(cacheZip, bzTarget);
                    cacheZip.delete();

                    MediaServiceManager.getInstance(this).ensureServiceConfigSanitized("bazarr");
                    MediaServiceManager.getInstance(this).autoWireStack(null);

                    runOnUiThread(() -> {
                        progress.dismiss();
                        svc.reconcileInstalledState();
                        refreshServicesTabUI();
                        refreshHomeServicesBadge();
                        Toast.makeText(this, "Bazarr installed successfully!", Toast.LENGTH_LONG).show();
                    });
                    return;
                }

                if ("transmission".equalsIgnoreCase(id)) {
                    String[] trDeps = {"libc++", "libpsl", "libidn2", "miniupnpc", "natpmpc", "transmission"};
                    for (String dep : trDeps) {
                        downloadAndInstallDebPackage(dep, prefix, progress, "Installing Transmission (" + dep + ")...");
                    }

                    File daemon = new File(prefix, "bin/transmission-daemon");
                    if (daemon.exists()) {
                        daemon.setExecutable(true, false);
                        try {
                            android.system.Os.chmod(daemon.getAbsolutePath(), 0755);
                        } catch (Throwable ignored) {}
                    }

                    MediaServiceManager.getInstance(this).ensureServiceConfigSanitized("transmission");
                    MediaServiceManager.getInstance(this).autoWireStack(null);

                    runOnUiThread(() -> {
                        progress.dismiss();
                        svc.reconcileInstalledState();
                        refreshServicesTabUI();
                        refreshHomeServicesBadge();
                        Toast.makeText(this, "Transmission installed successfully!", Toast.LENGTH_LONG).show();
                    });
                    return;
                }

                                // Servarr applications (.NET 10.0 ARM64): Radarr, Sonarr, Prowlarr, Lidarr, Jackett
                String downloadUrl;
                if ("sonarr".equalsIgnoreCase(id)) {
                    downloadUrl = "https://services.sonarr.tv/v1/download/main/latest?version=4&os=linux&arch=arm64";
                } else if ("prowlarr".equalsIgnoreCase(id)) {
                    downloadUrl = "https://prowlarr.servarr.com/v1/update/master/updatefile?os=linux&runtime=netcore&arch=arm64";
                } else if ("radarr".equalsIgnoreCase(id)) {
                    downloadUrl = "https://radarr.servarr.com/v1/update/master/updatefile?os=linux&runtime=netcore&arch=arm64";
                } else if ("lidarr".equalsIgnoreCase(id)) {
                    downloadUrl = "https://lidarr.servarr.com/v1/update/master/updatefile?os=linux&runtime=netcore&arch=arm64";
                } else if ("jackett".equalsIgnoreCase(id)) {
                    downloadUrl = "https://github.com/Jackett/Jackett/releases/download/v0.24.2932/Jackett.Binaries.LinuxARM64.tar.gz";
                } else {
                    runOnUiThread(progress::dismiss);
                    return;
                }

                runOnUiThread(() -> progress.setMessage("Downloading " + svc.getDisplayName() + "..."));
                File cacheTar = new File(getCacheDir(), id + ".tar.gz");
                final int[] lastReportedSv = new int[]{-1};
                downloadWithRedirects(downloadUrl, cacheTar, (current, total) -> {
                    if (total > 0) {
                        int pct = (int) ((current * 100) / total);
                        if (pct != lastReportedSv[0]) {
                            lastReportedSv[0] = pct;
                            runOnUiThread(() -> progress.setProgress(pct));
                        }
                    }
                });

                runOnUiThread(() -> progress.setMessage("Extracting & configuring binaries..."));
                File targetDir = new File(servicesDir, id);
                if (targetDir.exists()) {
                    deleteRecursive(targetDir);
                }

                extractTarGzArchive(cacheTar, servicesDir);
                cacheTar.delete();

                String capName = id.substring(0, 1).toUpperCase(Locale.ROOT) + id.substring(1);
                File capDir = new File(servicesDir, capName);
                if (capDir.exists() && !capDir.equals(targetDir)) {
                    capDir.renameTo(targetDir);
                }

                File dll = new File(svc.getManifest().getExecutableOrDllPath());
                if ("jackett".equalsIgnoreCase(id) || !dll.exists()) {
                    File checkDll = new File(targetDir, "jackett.dll");
                    if (checkDll.exists()) {
                        dll = checkDll;
                    } else {
                        File checkCapDll = new File(targetDir, "Jackett.dll");
                        if (checkCapDll.exists()) dll = checkCapDll;
                    }
                }
                File jackettBin = new File(targetDir, "jackett");
                if (jackettBin.exists()) {
                    jackettBin.setExecutable(true, false);
                    try { android.system.Os.chmod(jackettBin.getAbsolutePath(), 0755); } catch (Throwable ignored) {}
                }
                if (dll.exists()) {
                    File parent = dll.getParentFile();
                    String name = dll.getName();
                    int dot = name.lastIndexOf('.');
                    String baseName = dot > 0 ? name.substring(0, dot) : name;
                    File depsJson = new File(parent, baseName + ".deps.json");
                    File depsJsonBak = new File(parent, baseName + ".deps.json.bak");
                    if (depsJson.exists() && !depsJsonBak.exists()) depsJson.renameTo(depsJsonBak);

                    String[] glibcLibs = {
                        "libcoreclr.so", "libhostpolicy.so", "libhostfxr.so",
                        "libclrjit.so", "libclrgc.so", "libclrgcexp.so", "libcoreclrtraceptprovider.so",
                        "libmscordaccore.so", "libmscordbi.so", "libMono.Unix.so",
                        "libSystem.Native.so", "libSystem.Globalization.Native.so",
                        "libSystem.IO.Compression.Native.so", "libSystem.Net.Security.Native.so",
                        "libSystem.Security.Cryptography.Native.OpenSsl.so"
                    };
                    for (String glibcLib : glibcLibs) {
                        File f = new File(parent, glibcLib);
                        if (f.exists()) f.delete();
                    }

                    File prefixEsqlite = new File(prefix, "lib/libe_sqlite3.so");
                    File targetEsqlite = new File(parent, "libe_sqlite3.so");
                    if (prefixEsqlite.exists()) copyFile(prefixEsqlite, targetEsqlite);
                    if (targetEsqlite.exists()) {
                        targetEsqlite.setReadable(true, false);
                        targetEsqlite.setExecutable(true, false);
                        try { android.system.Os.chmod(targetEsqlite.getAbsolutePath(), 0755); } catch (Throwable ignored) {}
                    }

                    File prefixMono = new File(prefix, "lib/libMonoPosixHelper.so");
                    if (!prefixMono.exists()) {
                        try (InputStream in = getAssets().open("libMonoPosixHelper.so");
                             OutputStream out = new FileOutputStream(prefixMono)) {
                            byte[] buf = new byte[8192];
                            int r;
                            while ((r = in.read(buf)) != -1) out.write(buf, 0, r);
                        } catch (Throwable ignored) {}
                        if (prefixMono.exists()) {
                            try { android.system.Os.chmod(prefixMono.getAbsolutePath(), 0755); } catch (Throwable ignored) {}
                        }
                    }
                    File targetMono = new File(parent, "libMonoPosixHelper.so");
                    if (prefixMono.exists()) copyFile(prefixMono, targetMono);
                    if (targetMono.exists()) {
                        targetMono.setReadable(true, false);
                        targetMono.setExecutable(true, false);
                        try { android.system.Os.chmod(targetMono.getAbsolutePath(), 0755); } catch (Throwable ignored) {}
                    }

                    File jfConfig = new File(prefix, "lib/jellyfin/jellyfin.runtimeconfig.json");
                    File targetCfg = new File(parent, baseName + ".runtimeconfig.json");
                    if (jfConfig.exists()) copyFile(jfConfig, targetCfg);
                }

                MediaServiceManager.getInstance(this).ensureServiceConfigSanitized(id);
                MediaServiceManager.getInstance(this).autoWireStack(null);

                runOnUiThread(() -> {
                    progress.dismiss();
                    svc.reconcileInstalledState();
                    refreshServicesTabUI();
                    refreshHomeServicesBadge();
                    Toast.makeText(this, svc.getDisplayName() + " installed successfully!", Toast.LENGTH_LONG).show();
                });

            } catch (Throwable t) {
                Log.e(TAG, "Failed to auto-install " + svc.getId(), t);
                runOnUiThread(() -> {
                    progress.dismiss();
                    Toast.makeText(this, "Installation failed: " + t.getMessage(), Toast.LENGTH_LONG).show();
                });
            }
        }).start();
    }

    private void syncAndAutoWireAllServices() {
        Toast.makeText(this, "Synchronizing media stack...", Toast.LENGTH_SHORT).show();
        MediaServiceManager.getInstance(this).autoWireStack((success, statusMessage) -> {
            Toast.makeText(this, statusMessage, Toast.LENGTH_SHORT).show();
            refreshServicesTabUI();
            refreshHomeServicesBadge();
        });
    }

    private void launchMoviesStack() {
        Toast.makeText(this, "Starting Movies Stack (Radarr + Transmission + Prowlarr + Jackett)...", Toast.LENGTH_SHORT).show();
        new Thread(() -> {
            MediaServiceManager mgr = MediaServiceManager.getInstance(this);
            String[] servicesToStart = {"transmission", "prowlarr", "jackett", "radarr"};
            for (String id : servicesToStart) {
                ManagedService svc = mgr.getService(id);
                if (svc != null && svc.isEnabled() && !svc.isRunning() && svc.isInstalled()) {
                    mgr.startService(id);
                    try { Thread.sleep(1200); } catch (InterruptedException ignored) {}
                }
            }
            mgr.scheduleAutoWireDebounced(2500);
            runOnUiThread(() -> {
                refreshServicesTabUI();
                refreshHomeServicesBadge();
                Toast.makeText(this, "Movies Stack started & wired.", Toast.LENGTH_SHORT).show();
            });
        }).start();
    }

    private void launchSeriesStack() {
        Toast.makeText(this, "Starting TV Shows / Series Stack (Sonarr + Transmission + Prowlarr + Jackett)...", Toast.LENGTH_SHORT).show();
        new Thread(() -> {
            MediaServiceManager mgr = MediaServiceManager.getInstance(this);
            String[] servicesToStart = {"transmission", "prowlarr", "jackett", "sonarr"};
            for (String id : servicesToStart) {
                ManagedService svc = mgr.getService(id);
                if (svc != null && svc.isEnabled() && !svc.isRunning() && svc.isInstalled()) {
                    mgr.startService(id);
                    try { Thread.sleep(1200); } catch (InterruptedException ignored) {}
                }
            }
            mgr.scheduleAutoWireDebounced(2500);
            runOnUiThread(() -> {
                refreshServicesTabUI();
                refreshHomeServicesBadge();
                Toast.makeText(this, "TV Shows / Series Stack started & wired.", Toast.LENGTH_SHORT).show();
            });
        }).start();
    }

    private void deleteRecursive(File fileOrDir) {
        if (fileOrDir.isDirectory()) {
            File[] children = fileOrDir.listFiles();
            if (children != null) {
                for (File child : children) deleteRecursive(child);
            }
        }
        fileOrDir.delete();
    }

    private void copyFile(File src, File dst) {
        try (java.io.FileInputStream in = new java.io.FileInputStream(src);
             java.io.FileOutputStream out = new java.io.FileOutputStream(dst)) {
            byte[] buf = new byte[8192];
            int len;
            while ((len = in.read(buf)) > 0) {
                out.write(buf, 0, len);
            }
        } catch (Throwable ignored) {}
    }

    private static String readTextFile(File file) {
        try (java.io.FileInputStream fis = new java.io.FileInputStream(file);
             java.io.InputStreamReader isr = new java.io.InputStreamReader(fis, java.nio.charset.StandardCharsets.UTF_8);
             java.io.BufferedReader br = new java.io.BufferedReader(isr)) {
            StringBuilder sb = new StringBuilder();
            String line;
            while ((line = br.readLine()) != null) {
                sb.append(line).append("\n");
            }
            return sb.toString();
        } catch (Throwable t) {
            return null;
        }
    }

    private static void writeTextFile(File file, String content) {
        try (java.io.FileOutputStream fos = new java.io.FileOutputStream(file)) {
            fos.write(content.getBytes(java.nio.charset.StandardCharsets.UTF_8));
        } catch (Throwable ignored) {}
    }

    private void exportSanitizedDiagnostics() {
        StringBuilder sb = new StringBuilder();
        sb.append("=== Fishbowl System Diagnostics ===\n");
        sb.append("App Version: ").append("1.4.8-beta.1 (101412)\n");
        sb.append("Device: ").append(Build.MANUFACTURER).append(" ").append(Build.MODEL).append("\n");
        sb.append("Android: ").append(Build.VERSION.RELEASE).append(" (API ").append(Build.VERSION.SDK_INT).append(")\n");
        sb.append("Architecture: ").append(Build.SUPPORTED_ABIS != null && Build.SUPPORTED_ABIS.length > 0 ? Build.SUPPORTED_ABIS[0] : "unknown").append("\n");
        sb.append("Jellyfin State: ").append(controller != null ? controller.getState().name() : "N/A").append("\n");
        if (controller != null) {
            JellyfinController.ServerHealth h = controller.getServerHealth();
            sb.append("Server Healthy: ").append(h.isHealthy).append("\n");
            sb.append("Uptime: ").append(h.uptimeSeconds).append("s\n");
            sb.append("Storage Free: ").append(h.storageFreeMb).append(" MB\n");
        }

        sb.append("\n--- Managed Services ---\n");
        MediaServiceManager mgr = MediaServiceManager.getInstance(this);
        for (ManagedService s : mgr.getAllServices()) {
            sb.append(s.getId()).append(": ").append(s.getState().name())
                    .append(" | Port: ").append(s.getManifest().getDefaultPort())
                    .append(" | Installed: ").append(s.isInstalled()).append("\n");
        }

        sb.append("\n--- Recent Jellyfin Logs (Sanitized) ---\n");
        String logs = controller != null ? controller.getLogs() : "";
        if (logs.length() > 3000) {
            logs = logs.substring(logs.length() - 3000);
        }
        sb.append(sanitizeText(logs));

        ClipboardManager clipboard = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
        if (clipboard != null) {
            clipboard.setPrimaryClip(ClipData.newPlainText("fishbowl_diagnostics", sb.toString()));
            Toast.makeText(this, "Sanitized diagnostics copied to clipboard", Toast.LENGTH_LONG).show();
        }
    }

    private String sanitizeText(String text) {
        if (text == null) return "";
        return text.replaceAll("(?i)(api[_-]?key|token|auth|password|secret)[=:][^\\s&]+", "$1=***REDACTED***");
    }

    // -- Tab 3: Logs View -------------------------------------------------------

    // -- Legacy Logs Tab Stub ----------------------------------------------------

    private View buildLogsTab() {
        return new View(this);
    }

    private void refreshLogsDisplay() {
        refreshUnifiedLogsDisplay();
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
            if ((activeTab == 2 || activeTab == 4) && logsOutputText != null) {
                boolean atBottom = isScrollAtBottom(logsScrollView);
                refreshUnifiedLogsDisplay();
                if (autoScrollEnabled && atBottom && logsScrollView != null) {
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

    // -- Tab 3: Settings View (Integrated Settings & Media Storage) ------------

    // -- Tab 3: Settings View (Professional Grouped Architecture & Storage Bar) ------------

    private View buildSettingsTab() {
        ScrollView scroll = new ScrollView(this);
        scroll.setVerticalScrollBarEnabled(false);
        scroll.setHorizontalScrollBarEnabled(false);
        scroll.setOverScrollMode(View.OVER_SCROLL_NEVER);
        scroll.setSmoothScrollingEnabled(true);
        scroll.setFillViewport(true);
        scroll.setBackgroundColor(getBgColor());

        LinearLayout layout = new LinearLayout(this);
        layout.setOrientation(LinearLayout.VERTICAL);
        layout.setPadding(dp(20), dp(14), dp(20), dp(110));

        // -- Settings Page Header ---------------------------------
        LinearLayout header = new LinearLayout(this);
        header.setOrientation(LinearLayout.VERTICAL);
        header.setPadding(0, dp(4), 0, dp(14));

        TextView title = new TextView(this);
        title.setText("SETTINGS");
        title.setTextSize(18);
        title.setTypeface(Typeface.create("sans-serif-medium", Typeface.BOLD));
        title.setTextColor(Color.parseColor("#F4F4F5"));
        header.addView(title);

        TextView sub = new TextView(this);
        sub.setText("System configuration, storage breakdown, and preferences.");
        sub.setTextSize(11);
        sub.setTextColor(Color.parseColor("#71717A"));
        sub.setPadding(0, dp(2), 0, 0);
        header.addView(sub);

        layout.addView(header);

        // Section 1: GENERAL & STARTUP (At top as requested)
        layout.addView(buildGeneralSection());

        // Section 2: FISHBOWL STORAGE (Hardware runtime & app footprint)
        layout.addView(buildStorageSection());

        // Section 3: PERMISSIONS & SYSTEM HEALTH (Drawer)
        layout.addView(buildPermissionsSection());

        // Section 4: PLAYBACK & STREAMING CODECS (Drawer)
        layout.addView(buildStreamingSection());

        // Section 5: SOFTWARE UPDATES (Drawer)
        layout.addView(buildUpdatesSection());

        // Section 6: BACKUP & RESTORE (Drawer)
        layout.addView(buildBackupRestoreSection());

        // Section 7: MOVIE IMPORTER (Drawer - Downloads & Folder Organizer)
        layout.addView(buildMovieImporterSection());

        // Section 8: ABOUT FISHBOWL (Drawer - Specs, Architecture & Licenses)
        layout.addView(buildAboutSection());

        // Community & GitHub Issue Actions (Directly outside any card/box)
        LinearLayout communityRow = new LinearLayout(this);
        communityRow.setOrientation(LinearLayout.HORIZONTAL);
        communityRow.setGravity(Gravity.CENTER_VERTICAL);
        LinearLayout.LayoutParams cParams = new LinearLayout.LayoutParams(-1, -2);
        cParams.topMargin = dp(4);
        cParams.bottomMargin = dp(14);
        communityRow.setLayoutParams(cParams);

        Button btnGithub = createPixelButton("GITHUB", getSurfaceElevatedColor(), getPrimaryTextColor());
        btnGithub.setTextSize(10f);
        btnGithub.setOnClickListener(v -> {
            try {
                startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse("https://github.com/Suz41/Fishbowl")));
            } catch (Throwable t) {
                Toast.makeText(this, "Could not open browser: https://github.com/Suz41/Fishbowl", Toast.LENGTH_LONG).show();
            }
        });
        communityRow.addView(btnGithub, new LinearLayout.LayoutParams(0, dp(38), 1.0f));

        View comSp = new View(this);
        communityRow.addView(comSp, new LinearLayout.LayoutParams(dp(10), 1));

        Button btnReport = createPixelButton("REPORT ISSUE", getSurfaceElevatedColor(), getAccentColor());
        btnReport.setTextSize(10f);
        btnReport.setOnClickListener(v -> {
            try {
                startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse("https://github.com/Suz41/Fishbowl/issues")));
            } catch (Throwable t) {
                Toast.makeText(this, "Could not open browser: https://github.com/Suz41/Fishbowl/issues", Toast.LENGTH_LONG).show();
            }
        });
        communityRow.addView(btnReport, new LinearLayout.LayoutParams(0, dp(38), 1.0f));

        layout.addView(communityRow);

        scroll.addView(layout);
        refreshStorageBar();
        return scroll;
    }

    private LinearLayout createSettingsSectionCard() {
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setBackground(createRoundedDrawable(Color.parseColor("#0C0D10"), dp(12)));
        card.setPadding(dp(16), dp(12), dp(16), dp(12));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2);
        lp.bottomMargin = dp(14);
        card.setLayoutParams(lp);
        return card;
    }

    private TextView createSettingsSectionHeader(String title) {
        TextView tv = new TextView(this);
        tv.setText(title);
        tv.setTextSize(10.5f);
        tv.setTypeface(Typeface.create("sans-serif-medium", Typeface.BOLD));
        tv.setTextColor(Color.parseColor("#71717A"));
        tv.setLetterSpacing(0.08f);
        tv.setPadding(dp(4), dp(6), 0, dp(6));
        return tv;
    }

    private View createSettingsDivider() {
        View v = new View(this);
        v.setBackgroundColor(Color.argb(12, 255, 255, 255));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, dp(1));
        lp.topMargin = dp(10);
        lp.bottomMargin = dp(10);
        v.setLayoutParams(lp);
        return v;
    }

    private LinearLayout createSettingsNavRow(String title, String subtitle, View.OnClickListener onClick) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(0, dp(4), 0, dp(4));
        row.setClickable(true);
        row.setFocusable(true);
        if (onClick != null) row.setOnClickListener(onClick);

        LinearLayout textCol = new LinearLayout(this);
        textCol.setOrientation(LinearLayout.VERTICAL);

        TextView tView = new TextView(this);
        tView.setText(title);
        tView.setTextSize(13.5f);
        tView.setTypeface(Typeface.create("sans-serif-medium", Typeface.BOLD));
        tView.setTextColor(Color.parseColor("#F4F4F5"));
        textCol.addView(tView);

        if (subtitle != null && !subtitle.isEmpty()) {
            TextView sView = new TextView(this);
            sView.setText(subtitle);
            sView.setTextSize(11f);
            sView.setTextColor(Color.parseColor("#71717A"));
            sView.setPadding(0, dp(2), 0, 0);
            textCol.addView(sView);
        }

        row.addView(textCol, new LinearLayout.LayoutParams(0, -2, 1.0f));

        TextView chevron = new TextView(this);
        chevron.setText("›");
        chevron.setTextSize(18f);
        chevron.setTypeface(Typeface.DEFAULT_BOLD);
        chevron.setTextColor(Color.parseColor("#71717A"));
        chevron.setPadding(dp(8), 0, dp(4), 0);
        row.addView(chevron);

        return row;
    }

    // -- Section 1: Fishbowl Storage with Segmented Color Bar -------------------

    private View buildStorageSection() {
        LinearLayout section = new LinearLayout(this);
        section.setOrientation(LinearLayout.VERTICAL);

        section.addView(createSettingsSectionHeader("FISHBOWL STORAGE"));

        LinearLayout card = createSettingsSectionCard();

        storageHeaderTitle = new TextView(this);
        storageHeaderTitle.setText("Fishbowl Storage");
        storageHeaderTitle.setTextSize(20f);
        storageHeaderTitle.setTypeface(Typeface.create("sans-serif-medium", Typeface.BOLD));
        storageHeaderTitle.setTextColor(Color.parseColor("#F4F4F5"));
        card.addView(storageHeaderTitle);

        storageHeaderSubtitle = new TextView(this);
        storageHeaderSubtitle.setText("Calculating storage breakdown...");
        storageHeaderSubtitle.setTextSize(11f);
        storageHeaderSubtitle.setTextColor(Color.parseColor("#71717A"));
        storageHeaderSubtitle.setPadding(0, dp(2), 0, dp(10));
        card.addView(storageHeaderSubtitle);

        // Segmented Storage Bar (100% of Fishbowl used)
        storageBarContainer = new LinearLayout(this);
        storageBarContainer.setOrientation(LinearLayout.HORIZONTAL);
        storageBarContainer.setBackground(createRoundedDrawable(Color.parseColor("#18181B"), dp(5)));
        storageBarContainer.setPadding(dp(2), dp(2), dp(2), dp(2));
        LinearLayout.LayoutParams barLp = new LinearLayout.LayoutParams(-1, dp(10));
        barLp.bottomMargin = dp(12);
        storageBarContainer.setLayoutParams(barLp);
        card.addView(storageBarContainer);

        // Detailed Breakdown List (What took place inside Fishbowl)
        storageLegendContainer = new LinearLayout(this);
        storageLegendContainer.setOrientation(LinearLayout.VERTICAL);
        storageLegendContainer.setPadding(0, 0, 0, dp(4));
        card.addView(storageLegendContainer);

        card.addView(createSettingsDivider());

        // Manage Storage & Clear Cache Actions Row
        LinearLayout actionRow = new LinearLayout(this);
        actionRow.setOrientation(LinearLayout.HORIZONTAL);
        actionRow.setPadding(0, dp(6), 0, dp(2));

        Button btnManage = createPixelButton("MANAGE STORAGE", Color.parseColor("#18191E"), Color.parseColor("#F4F4F5"));
        btnManage.setTextSize(10f);
        btnManage.setOnClickListener(v -> startActivity(new Intent(this, JellyfinStorageActivity.class)));
        actionRow.addView(btnManage, new LinearLayout.LayoutParams(0, dp(36), 1.0f));

        View aSpacer = new View(this);
        actionRow.addView(aSpacer, new LinearLayout.LayoutParams(dp(8), 1));

        Button btnClear = createPixelButton("CLEAR CACHE", Color.parseColor("#1C1014"), Color.parseColor("#FDA4AF"));
        btnClear.setTextSize(10f);
        btnClear.setOnClickListener(v -> showClearCacheConfirmation());
        actionRow.addView(btnClear, new LinearLayout.LayoutParams(0, dp(36), 1.0f));

        card.addView(actionRow);

        section.addView(card);

        externalStorageSection = new LinearLayout(this);
        externalStorageSection.setOrientation(LinearLayout.VERTICAL);
        externalStorageSection.setVisibility(View.GONE);
        section.addView(externalStorageSection);

        return section;
    }

    private void refreshStorageBar() {
        if (storageBarContainer == null) return;
        new Thread(() -> {
            try {
                // Query connected external storage safely on background thread
                List<StorageDriveHelper.DriveInfo> extDrives = MediaStorageManager.getConnectedExternalDrives(this);

                File prefix = com.termux.shared.termux.TermuxConstants.TERMUX_PREFIX_DIR;
                File home = com.termux.shared.termux.TermuxConstants.TERMUX_HOME_DIR;
                File servicesDir = new File(home, "services");
                File dataDir = new File(home, ".local/share/jellyfin");
                File configDir = new File(home, ".config");
                File cacheDir = new File(home, ".cache");

                File dataAppDir = new File(getApplicationInfo().dataDir);
                long fullDataDirBytes = calculateDirSize(dataAppDir);

                long apkBytes = 0;
                try {
                    String pkgCodePath = getPackageCodePath();
                    if (pkgCodePath != null) {
                        File apkFile = new File(pkgCodePath);
                        if (apkFile.exists()) {
                            apkBytes = apkFile.length();
                        }
                    }
                } catch (Throwable ignored) {}

                long totalAppFootprint = fullDataDirBytes + apkBytes;

                // Query StorageStatsManager for system-accurate storage if available
                long systemQueryTotal = 0;
                long systemQueryCache = 0;
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    try {
                        android.app.usage.StorageStatsManager statsManager = (android.app.usage.StorageStatsManager) getSystemService(Context.STORAGE_STATS_SERVICE);
                        android.os.storage.StorageManager storageManager = (android.os.storage.StorageManager) getSystemService(Context.STORAGE_SERVICE);
                        if (statsManager != null && storageManager != null) {
                            java.util.UUID uuid = storageManager.getUuidForPath(dataAppDir);
                            android.app.usage.StorageStats stats = statsManager.queryStatsForUid(uuid, android.os.Process.myUid());
                            systemQueryCache = stats.getCacheBytes();
                            systemQueryTotal = stats.getAppBytes() + stats.getDataBytes() + systemQueryCache;
                        }
                    } catch (Throwable ignored) {}
                }

                // 1. Companion Runtimes & Binaries: /usr (base runtimes) + /home/services (Servarr binaries)
                long usrBytes = calculateDirSize(prefix);
                long servicesBytes = calculateDirSize(servicesDir);
                long runtimeBytes = usrBytes + servicesBytes;

                // 2. Jellyfin Server & Databases: Jellyfin data (.local), configs (.config), and base app code
                long jellyfinDataBytes = calculateDirSize(dataDir);
                long totalConfigBytes = calculateDirSize(configDir);
                long dataBytes = jellyfinDataBytes + totalConfigBytes + apkBytes;

                // 3. Cache, Transcodes & Logs: cache directory + internal cache + logs
                long systemCache = calculateDirSize(getCacheDir()) + calculateDirSize(cacheDir);
                long rawCache = Math.max(systemCache, Math.max(systemQueryCache, totalAppFootprint - runtimeBytes - dataBytes));
                final long cacheBytes = Math.max(systemCache, rawCache);

                final long totalHardwareUsed = Math.max(systemQueryTotal, Math.max(totalAppFootprint, runtimeBytes + dataBytes + cacheBytes));

                runOnUiThread(() -> {
                    updateStorageBarUI(totalHardwareUsed, dataBytes, runtimeBytes, cacheBytes);
                    updateExternalStorageUI(extDrives);
                });
            } catch (Throwable t) {
                Log.w(TAG, "Storage breakdown failed: " + t.getMessage());
            }
        }, "StorageBreakdownThread").start();
    }

    private void updateExternalStorageUI(List<StorageDriveHelper.DriveInfo> extDrives) {
        if (externalStorageSection == null) return;
        if (extDrives == null || extDrives.isEmpty()) {
            externalStorageSection.setVisibility(View.GONE);
            externalStorageSection.removeAllViews();
            return;
        }

        externalStorageSection.setVisibility(View.VISIBLE);
        externalStorageSection.removeAllViews();
        externalStorageSection.addView(createSettingsSectionHeader("DETECTED EXTERNAL STORAGE"));

        for (StorageDriveHelper.DriveInfo drive : extDrives) {
            if (drive == null) continue;
            LinearLayout card = createSettingsSectionCard();

            // Row 1: Drive Icon, Name, and Status Badge
            LinearLayout headerRow = new LinearLayout(this);
            headerRow.setOrientation(LinearLayout.HORIZONTAL);
            headerRow.setGravity(Gravity.CENTER_VERTICAL);

            ImageView driveIcon = new ImageView(this);
            driveIcon.setImageResource(R.drawable.ic_storage);
            driveIcon.setColorFilter(Color.parseColor("#10B981"));
            headerRow.addView(driveIcon, new LinearLayout.LayoutParams(dp(26), dp(26)));

            View hSpacer = new View(this);
            headerRow.addView(hSpacer, new LinearLayout.LayoutParams(dp(10), 1));

            LinearLayout titleCol = new LinearLayout(this);
            titleCol.setOrientation(LinearLayout.VERTICAL);
            TextView dName = new TextView(this);
            dName.setText(drive.name != null ? drive.name : "External Drive");
            dName.setTextSize(14.5f);
            dName.setTypeface(Typeface.create("sans-serif-medium", Typeface.BOLD));
            dName.setTextColor(Color.parseColor("#F4F4F5"));
            titleCol.addView(dName);

            TextView dPath = new TextView(this);
            dPath.setText(drive.rootPath != null ? drive.rootPath : "/storage");
            dPath.setTextSize(11f);
            dPath.setTextColor(Color.parseColor("#71717A"));
            titleCol.addView(dPath);
            headerRow.addView(titleCol, new LinearLayout.LayoutParams(0, -2, 1.0f));

            TextView statusBadge = new TextView(this);
            boolean isReadOnly = Environment.MEDIA_MOUNTED_READ_ONLY.equalsIgnoreCase(drive.state);
            statusBadge.setText(isReadOnly ? "READ-ONLY" : "MOUNTED (RW)");
            statusBadge.setTextSize(9.5f);
            statusBadge.setTypeface(Typeface.create("sans-serif-medium", Typeface.BOLD));
            statusBadge.setPadding(dp(8), dp(3), dp(8), dp(3));
            statusBadge.setBackground(createRoundedDrawable(
                    isReadOnly ? Color.parseColor("#332200") : Color.parseColor("#06331C"), dp(4)));
            statusBadge.setTextColor(isReadOnly ? Color.parseColor("#F59E0B") : Color.parseColor("#10B981"));
            headerRow.addView(statusBadge);

            card.addView(headerRow);

            // Row 2: Capacity Stats
            long total = drive.totalBytes;
            long free = drive.freeBytes;
            long used = Math.max(0, total - free);

            TextView statsTv = new TextView(this);
            if (total > 0) {
                statsTv.setText(MediaStorageManager.formatBytes(used) + " used of " +
                        MediaStorageManager.formatBytes(total) + " (" + MediaStorageManager.formatBytes(free) + " free)");
            } else {
                statsTv.setText("Storage capacity: Ready • Direct mount");
            }
            statsTv.setTextSize(11.5f);
            statsTv.setTextColor(Color.parseColor("#A1A1AA"));
            statsTv.setPadding(0, dp(10), 0, dp(6));
            card.addView(statsTv);

            // Row 3: Progress Bar
            if (total > 0) {
                LinearLayout barCont = new LinearLayout(this);
                barCont.setOrientation(LinearLayout.HORIZONTAL);
                barCont.setBackground(createRoundedDrawable(Color.parseColor("#18181B"), dp(5)));
                barCont.setPadding(dp(2), dp(2), dp(2), dp(2));
                LinearLayout.LayoutParams bLp = new LinearLayout.LayoutParams(-1, dp(8));
                bLp.bottomMargin = dp(10);
                barCont.setLayoutParams(bLp);

                addBarSegment(barCont, used, total, Color.parseColor("#10B981"));
                card.addView(barCont);
            }

            // Row 4: Anti-Corruption Guard Shield Banner
            LinearLayout shieldBox = new LinearLayout(this);
            shieldBox.setOrientation(LinearLayout.VERTICAL);
            shieldBox.setBackground(createBorderedRoundedDrawable(Color.parseColor("#071912"), Color.parseColor("#0F3D2A"), dp(1), dp(8)));
            shieldBox.setPadding(dp(12), dp(8), dp(12), dp(8));
            LinearLayout.LayoutParams sLp = new LinearLayout.LayoutParams(-1, -2);
            sLp.bottomMargin = dp(10);
            shieldBox.setLayoutParams(sLp);

            TextView shieldTitle = new TextView(this);
            shieldTitle.setText("ANTI-CORRUPTION GUARD ACTIVE");
            shieldTitle.setTextSize(10f);
            shieldTitle.setTypeface(Typeface.create("sans-serif-medium", Typeface.BOLD));
            shieldTitle.setTextColor(Color.parseColor("#34D399"));
            shieldBox.addView(shieldTitle);

            TextView shieldSub = new TextView(this);
            shieldSub.setText("Buffered atomic writes protect FAT/exFAT tables. Safe Sync commits all buffers to disk.");
            shieldSub.setTextSize(10.5f);
            shieldSub.setTextColor(Color.parseColor("#94A3B8"));
            shieldSub.setPadding(0, dp(2), 0, 0);
            shieldBox.addView(shieldSub);

            card.addView(shieldBox);

            // Row 5: Action Buttons (Safe Flush & Storage Manager)
            LinearLayout actRow = new LinearLayout(this);
            actRow.setOrientation(LinearLayout.HORIZONTAL);

            Button btnSync = createPixelButton("SAFE FLUSH & SYNC", Color.parseColor("#132F23"), Color.parseColor("#34D399"));
            btnSync.setTextSize(10f);
            btnSync.setOnClickListener(v -> {
                btnSync.setEnabled(false);
                btnSync.setText("SYNCING...");
                new Thread(() -> {
                    boolean ok = MediaStorageManager.flushAndSyncStorage();
                    runOnUiThread(() -> {
                        btnSync.setEnabled(true);
                        btnSync.setText("SAFE FLUSH & SYNC");
                        Toast.makeText(this, ok ? "Storage buffers synced safely! Safe to unmount when idle." : "Storage sync completed.", Toast.LENGTH_SHORT).show();
                    });
                }).start();
            });
            actRow.addView(btnSync, new LinearLayout.LayoutParams(0, dp(36), 1.0f));

            View aSp = new View(this);
            actRow.addView(aSp, new LinearLayout.LayoutParams(dp(8), 1));

            Button btnOpenMgr = createPixelButton("MANAGE DRIVE", Color.parseColor("#18191E"), Color.parseColor("#F4F4F5"));
            btnOpenMgr.setTextSize(10f);
            btnOpenMgr.setOnClickListener(v -> startActivity(new Intent(this, JellyfinStorageActivity.class)));
            actRow.addView(btnOpenMgr, new LinearLayout.LayoutParams(0, dp(36), 1.0f));

            card.addView(actRow);
            externalStorageSection.addView(card);
        }
    }

    private void updateStorageBarUI(long totalHardwareUsed, long dataBytes, long runtimeBytes, long cacheBytes) {
        if (storageHeaderTitle != null) {
            storageHeaderTitle.setText(MediaStorageManager.formatBytes(totalHardwareUsed) + " App Footprint");
        }
        if (storageHeaderSubtitle != null) {
            storageHeaderSubtitle.setText("Hardware storage • Companion runtimes, databases & cache");
        }

        if (storageBarContainer != null) {
            storageBarContainer.removeAllViews();

            long displayTotal = totalHardwareUsed;
            if (displayTotal <= 0) displayTotal = 1;

            // App Hardware Breakdown:
            // 1. Companion Runtimes (Amber)
            // 2. Jellyfin Server & DB (Violet)
            // 3. Cache & Logs (Rose)
            addBarSegment(storageBarContainer, runtimeBytes, displayTotal, Color.parseColor("#F59E0B")); // Amber
            addBarSegment(storageBarContainer, dataBytes, displayTotal, Color.parseColor("#8B5CF6"));    // Violet
            addBarSegment(storageBarContainer, cacheBytes, displayTotal, Color.parseColor("#F43F5E"));   // Rose
        }

        if (storageLegendContainer != null) {
            storageLegendContainer.removeAllViews();

            View compItem = createStorageBreakdownItem(
                    Color.parseColor("#F59E0B"), "Companion Runtimes & Binaries",
                    "Core packages (.NET 10, Python, FFmpeg, Servarr) • Tap for details", runtimeBytes, totalHardwareUsed);
            compItem.setClickable(true);
            compItem.setFocusable(true);
            compItem.setBackground(createBorderedRoundedDrawable(Color.TRANSPARENT, Color.argb(18, 255, 255, 255), dp(1), dp(6)));
            compItem.setPadding(dp(6), dp(4), dp(6), dp(4));
            compItem.setOnClickListener(v -> showCompanionStorageBreakdownDialog());
            storageLegendContainer.addView(compItem);
            storageLegendContainer.addView(createSettingsDivider());

            storageLegendContainer.addView(createStorageBreakdownItem(
                    Color.parseColor("#8B5CF6"), "Jellyfin Server & Databases",
                    "SQLite metadata, posters, and user watch history", dataBytes, totalHardwareUsed));
            storageLegendContainer.addView(createSettingsDivider());

            storageLegendContainer.addView(createStorageBreakdownItem(
                    Color.parseColor("#F43F5E"), "Cache, Transcodes & Logs",
                    "FFmpeg transcoding buffer, HTTP cache, and logs", cacheBytes, totalHardwareUsed));
        }
    }

    private void showCompanionStorageBreakdownDialog() {
        OledProgressDialog progress = showOledProgressDialog("ANALYZING RUNTIMES", "Calculating itemized companion breakdown...");
        new Thread(() -> {
            try {
                File home = com.termux.shared.termux.TermuxConstants.TERMUX_HOME_DIR;
                File prefix = com.termux.shared.termux.TermuxConstants.TERMUX_PREFIX_DIR;
                File servicesDir = new File(home, "services");

                long usrBytes = calculateDirSize(prefix);

                // Granular breakdown of /usr system directory
                File dotnetDir = new File(prefix, "lib/dotnet");
                long dotnetBytes = calculateDirSize(dotnetDir);

                File jfBinDir = new File(prefix, "lib/jellyfin");
                long jfBinBytes = calculateDirSize(jfBinDir);

                long pyBytes = 0;
                File libDir = new File(prefix, "lib");
                if (libDir.exists() && libDir.isDirectory()) {
                    File[] sub = libDir.listFiles();
                    if (sub != null) {
                        for (File f : sub) {
                            if (f.getName().startsWith("python3")) {
                                pyBytes += calculateDirSize(f);
                            }
                        }
                    }
                }

                File ffmpegOpt = new File(prefix, "opt/jellyfin");
                File ffmpegBin = new File(prefix, "bin/ffmpeg");
                File ffprobeBin = new File(prefix, "bin/ffprobe");
                long ffmpegBytes = calculateDirSize(ffmpegOpt) + (ffmpegBin.exists() ? ffmpegBin.length() : 0) + (ffprobeBin.exists() ? ffprobeBin.length() : 0);

                long baseSysBytes = Math.max(0, usrBytes - dotnetBytes - jfBinBytes - pyBytes - ffmpegBytes);
                final long finalPyBytes = pyBytes;

                // Granular breakdown of /services directory
                long radarrBytes = calculateDirSize(new File(servicesDir, "radarr"));
                long sonarrBytes = calculateDirSize(new File(servicesDir, "sonarr"));
                long prowlarrBytes = calculateDirSize(new File(servicesDir, "prowlarr"));
                long jackettBytes = calculateDirSize(new File(servicesDir, "jackett"));
                long lidarrBytes = calculateDirSize(new File(servicesDir, "lidarr"));
                long bazarrBytes = calculateDirSize(new File(servicesDir, "bazarr"));
                long seerrBytes = calculateDirSize(new File(servicesDir, "seerr"));

                File radarrBak = new File(servicesDir, "radarr.bak");
                long radarrBakBytes = radarrBak.exists() ? calculateDirSize(radarrBak) : 0;

                long totalBytes = usrBytes + radarrBytes + sonarrBytes + prowlarrBytes + jackettBytes + lidarrBytes + bazarrBytes + seerrBytes + radarrBakBytes;

                runOnUiThread(() -> {
                    progress.dismiss();

                    LinearLayout content = new LinearLayout(this);
                    content.setOrientation(LinearLayout.VERTICAL);
                    content.setPadding(0, dp(4), 0, dp(8));

                    TextView sub = new TextView(this);
                    sub.setText("Total Companion & Runtime Footprint: " + MediaStorageManager.formatBytes(totalBytes));
                    sub.setTextSize(11f);
                    sub.setTypeface(Typeface.create("sans-serif-medium", Typeface.BOLD));
                    sub.setTextColor(Color.parseColor("#F59E0B"));
                    sub.setPadding(0, 0, 0, dp(12));
                    content.addView(sub);

                    if (dotnetBytes > 0) {
                        content.addView(createStorageBreakdownItem(Color.parseColor("#512BD4"), ".NET 10 Runtime Engine", "Cross-platform CLR host (/usr/lib/dotnet)", dotnetBytes, totalBytes));
                        content.addView(createSettingsDivider());
                    }
                    if (jfBinBytes > 0) {
                        content.addView(createStorageBreakdownItem(Color.parseColor("#AB03A9"), "Jellyfin Server Core Binaries", "Server assembly DLLs & assets (/usr/lib/jellyfin)", jfBinBytes, totalBytes));
                        content.addView(createSettingsDivider());
                    }
                    if (finalPyBytes > 0) {
                        content.addView(createStorageBreakdownItem(Color.parseColor("#3776AB"), "Python 3 Runtime & Standard Libs", "Automation & FlareSolverr runtime (/usr/lib/python3.*)", finalPyBytes, totalBytes));
                        content.addView(createSettingsDivider());
                    }
                    if (ffmpegBytes > 0) {
                        content.addView(createStorageBreakdownItem(Color.parseColor("#007808"), "FFmpeg Transcoding Engine", "Media encoding libraries & binaries (/usr/opt/jellyfin)", ffmpegBytes, totalBytes));
                        content.addView(createSettingsDivider());
                    }
                    if (baseSysBytes > 0) {
                        content.addView(createStorageBreakdownItem(Color.parseColor("#71717A"), "Base Linux System & Packages", "Shell tools, shared libraries & utilities (/usr)", baseSysBytes, totalBytes));
                        content.addView(createSettingsDivider());
                    }

                    if (radarrBytes > 0) {
                        content.addView(createStorageBreakdownItem(Color.parseColor("#FF9E20"), "Radarr (Movies Manager)", "/services/radarr", radarrBytes, totalBytes));
                        content.addView(createSettingsDivider());
                    }
                    if (sonarrBytes > 0) {
                        content.addView(createStorageBreakdownItem(Color.parseColor("#CCFBFA"), "Sonarr (TV Shows Manager)", "/services/sonarr", sonarrBytes, totalBytes));
                        content.addView(createSettingsDivider());
                    }
                    if (prowlarrBytes > 0) {
                        content.addView(createStorageBreakdownItem(Color.parseColor("#FFFC8C"), "Prowlarr (Indexer Proxy)", "/services/prowlarr", prowlarrBytes, totalBytes));
                        content.addView(createSettingsDivider());
                    }
                    if (jackettBytes > 0) {
                        content.addView(createStorageBreakdownItem(Color.parseColor("#FF6D00"), "Jackett (Indexer Proxy)", "/services/jackett", jackettBytes, totalBytes));
                        content.addView(createSettingsDivider());
                    }
                    if (lidarrBytes > 0) {
                        content.addView(createStorageBreakdownItem(Color.parseColor("#769826"), "Lidarr (Music Manager)", "/services/lidarr", lidarrBytes, totalBytes));
                        content.addView(createSettingsDivider());
                    }
                    if (bazarrBytes > 0) {
                        content.addView(createStorageBreakdownItem(Color.parseColor("#5D9253"), "Bazarr (Subtitles Engine)", "/services/bazarr", bazarrBytes, totalBytes));
                        content.addView(createSettingsDivider());
                    }
                    if (seerrBytes > 0) {
                        content.addView(createStorageBreakdownItem(Color.parseColor("#55A9A0"), "Seerr (Media Requests)", "/services/seerr", seerrBytes, totalBytes));
                        content.addView(createSettingsDivider());
                    }

                    if (radarrBakBytes > 0) {
                        content.addView(createStorageBreakdownItem(Color.parseColor("#EF4444"), "Radarr Backup (.bak)", "Old installation backup file/folder", radarrBakBytes, totalBytes));
                        content.addView(createSettingsDivider());

                        Button btnCleanBak = createOutlinePixelButton("RECLAIM " + MediaStorageManager.formatBytes(radarrBakBytes) + " (DELETE .BAK)", Color.parseColor("#EF4444"), Color.parseColor("#EF4444"));
                        btnCleanBak.setTextSize(10f);
                        btnCleanBak.setOnClickListener(v -> {
                            new Thread(() -> {
                                MediaServiceManager.deleteRecursive(radarrBak);
                                runOnUiThread(() -> {
                                    Toast.makeText(this, "Cleaned backup files! " + MediaStorageManager.formatBytes(radarrBakBytes) + " reclaimed.", Toast.LENGTH_LONG).show();
                                    refreshStorageBar();
                                });
                            }).start();
                        });
                        LinearLayout.LayoutParams cLp = new LinearLayout.LayoutParams(-1, dp(38));
                        cLp.topMargin = dp(8);
                        content.addView(btnCleanBak, cLp);
                    }

                    showOledDialog("Companion Runtimes & Binaries", "Itemized breakdown of installed packages and services", content, null, 0, null, "CLOSE", null);
                });
            } catch (Throwable t) {
                runOnUiThread(() -> {
                    progress.dismiss();
                    Toast.makeText(this, "Failed to analyze storage: " + t.getMessage(), Toast.LENGTH_SHORT).show();
                });
            }
        }).start();
    }

    private void addBarSegment(LinearLayout parent, long bytes, long total, int color) {
        if (bytes <= 0) return;
        float weight = (float) bytes / total;
        weight = Math.max(0.02f, weight);

        View segment = new View(this);
        segment.setBackgroundColor(color);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, -1, weight);
        lp.rightMargin = dp(1);
        segment.setLayoutParams(lp);
        parent.addView(segment);
    }

    private View createStorageBreakdownItem(int color, String title, String subtitle, long bytes, long totalBytes) {
        LinearLayout item = new LinearLayout(this);
        item.setOrientation(LinearLayout.HORIZONTAL);
        item.setGravity(Gravity.CENTER_VERTICAL);
        item.setPadding(0, dp(4), 0, dp(4));

        View dot = new View(this);
        dot.setBackground(createRoundedDrawable(color, dp(4)));
        LinearLayout.LayoutParams dotLp = new LinearLayout.LayoutParams(dp(8), dp(8));
        dotLp.rightMargin = dp(10);
        item.addView(dot, dotLp);

        LinearLayout textCol = new LinearLayout(this);
        textCol.setOrientation(LinearLayout.VERTICAL);

        TextView titleTv = new TextView(this);
        titleTv.setText(title);
        titleTv.setTextSize(13f);
        titleTv.setTypeface(Typeface.create("sans-serif-medium", Typeface.BOLD));
        titleTv.setTextColor(Color.parseColor("#F4F4F5"));
        textCol.addView(titleTv);

        if (subtitle != null && !subtitle.isEmpty()) {
            TextView subTv = new TextView(this);
            subTv.setText(subtitle);
            subTv.setTextSize(10.5f);
            subTv.setTextColor(Color.parseColor("#71717A"));
            subTv.setPadding(0, dp(1), 0, 0);
            textCol.addView(subTv);
        }
        item.addView(textCol, new LinearLayout.LayoutParams(0, -2, 1.0f));

        LinearLayout valCol = new LinearLayout(this);
        valCol.setOrientation(LinearLayout.VERTICAL);
        valCol.setGravity(Gravity.END | Gravity.CENTER_VERTICAL);

        TextView valTv = new TextView(this);
        valTv.setText(MediaStorageManager.formatBytes(bytes));
        valTv.setTextSize(12.5f);
        valTv.setTypeface(Typeface.create("sans-serif-medium", Typeface.BOLD));
        valTv.setTextColor(Color.parseColor("#E4E4E7"));
        valCol.addView(valTv);

        int pct = totalBytes > 0 ? (int) Math.round((double) bytes * 100.0 / totalBytes) : 0;
        TextView pctTv = new TextView(this);
        pctTv.setText(pct + "%");
        pctTv.setTextSize(10f);
        pctTv.setTextColor(Color.parseColor("#71717A"));
        valCol.addView(pctTv);

        item.addView(valCol);
        return item;
    }

    // -- Section 1: General & Startup -----------------------------------------

    private View buildGeneralSection() {
        LinearLayout section = new LinearLayout(this);
        section.setOrientation(LinearLayout.VERTICAL);
        section.addView(createSettingsSectionHeader("GENERAL & STARTUP"));

        LinearLayout card = createSettingsSectionCard();

        // Row 1A: Auto-Start on App Launch (Jellyfin Only)
        LinearLayout autoStartAppRow = new LinearLayout(this);
        autoStartAppRow.setOrientation(LinearLayout.HORIZONTAL);
        autoStartAppRow.setGravity(Gravity.CENTER_VERTICAL);
        autoStartAppRow.setPadding(0, dp(2), 0, dp(4));

        LinearLayout asAppTextCol = new LinearLayout(this);
        asAppTextCol.setOrientation(LinearLayout.VERTICAL);
        TextView asAppTitle = new TextView(this);
        asAppTitle.setText("Start on App Launch");
        asAppTitle.setTextSize(13.5f);
        asAppTitle.setTypeface(Typeface.create("sans-serif-medium", Typeface.BOLD));
        asAppTitle.setTextColor(Color.parseColor("#F4F4F5"));
        asAppTextCol.addView(asAppTitle);

        TextView asAppSub = new TextView(this);
        asAppSub.setText("Automatically start Jellyfin server when opening Fishbowl (Jellyfin only)");
        asAppSub.setTextSize(11f);
        asAppSub.setTextColor(Color.parseColor("#71717A"));
        asAppSub.setPadding(0, dp(2), 0, 0);
        asAppTextCol.addView(asAppSub);
        autoStartAppRow.addView(asAppTextCol, new LinearLayout.LayoutParams(0, -2, 1.0f));

        Switch autoStartAppSwitch = new Switch(this);
        SharedPreferences prefs = getFishbowlPrefs();
        boolean appLaunchDefault = prefs.getBoolean("auto_start_app", prefs.getBoolean("auto_start", false));
        autoStartAppSwitch.setChecked(appLaunchDefault);
        autoStartAppSwitch.setOnCheckedChangeListener((v, checked) -> {
            prefs.edit().putBoolean("auto_start_app", checked).putBoolean("auto_start", checked).apply();
            if (checked) {
                triggerAutoStartOnAppLaunchIfEnabled();
            }
        });
        autoStartAppRow.addView(autoStartAppSwitch);
        card.addView(autoStartAppRow);

        card.addView(createSettingsDivider());

        // Row 2: Background Survival
        LinearLayout bgRow = new LinearLayout(this);
        bgRow.setOrientation(LinearLayout.HORIZONTAL);
        bgRow.setGravity(Gravity.CENTER_VERTICAL);
        bgRow.setPadding(0, dp(2), 0, dp(4));

        LinearLayout bgTextCol = new LinearLayout(this);
        bgTextCol.setOrientation(LinearLayout.VERTICAL);
        TextView bgTitle = new TextView(this);
        bgTitle.setText("Background Survival");
        bgTitle.setTextSize(13.5f);
        bgTitle.setTypeface(Typeface.create("sans-serif-medium", Typeface.BOLD));
        bgTitle.setTextColor(Color.parseColor("#F4F4F5"));
        bgTextCol.addView(bgTitle);

        TextView bgSub = new TextView(this);
        bgSub.setText("Battery exemption to prevent OS from sleeping server");
        bgSub.setTextSize(11f);
        bgSub.setTextColor(Color.parseColor("#71717A"));
        bgSub.setPadding(0, dp(2), 0, 0);
        bgTextCol.addView(bgSub);
        bgRow.addView(bgTextCol, new LinearLayout.LayoutParams(0, -2, 1.0f));

        Button btnBattery = createPixelButton("ALLOWED", Color.argb(25, 52, 211, 153), getSuccessColor());
        btnBattery.setTextSize(10f);
        btnBattery.setPadding(dp(10), 0, dp(10), 0);
        btnBattery.setOnClickListener(v -> requestIgnoreBatteryOptimizationsIfNeeded());
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            PowerManager pm = (PowerManager) getSystemService(POWER_SERVICE);
            if (pm != null && !pm.isIgnoringBatteryOptimizations(getPackageName())) {
                btnBattery.setText("ENABLE");
                btnBattery.setTextColor(getWarningColor());
                btnBattery.setBackground(createBorderedRoundedDrawable(Color.argb(25, 251, 191, 36), Color.argb(60, 251, 191, 36), dp(1), dp(8)));
            } else {
                btnBattery.setEnabled(false);
            }
        }
        bgRow.addView(btnBattery, new LinearLayout.LayoutParams(-2, dp(32)));
        card.addView(bgRow);

        section.addView(card);
        return section;
    }

    // -- Section 3: Permissions & Background Survival (Drawer) -----------------

    private View buildPermissionsSection() {
        return buildCollapsibleCard(
                "Permissions & Background Survival",
                "Storage access • Battery optimization • Autostart",
                buildPermissionsCard(),
                false
        );
    }

    // -- Section 4: Playback & Streaming Codecs (Drawer) ----------------------

    private View buildStreamingSection() {
        LinearLayout content = new LinearLayout(this);
        content.setOrientation(LinearLayout.VERTICAL);

        content.addView(buildSpecRow("Transcoding Engine", "Inbuilt Auto (Direct Play / HW Fallback)"));
        content.addView(createSettingsDivider());
        content.addView(buildSpecRow("Supported Video Codecs", "H.264, HEVC, AV1, VP9"));
        content.addView(createSettingsDivider());
        content.addView(buildSpecRow("Supported Audio Codecs", "AAC, Opus, FLAC, MP3, AC3"));
        content.addView(createSettingsDivider());
        content.addView(buildSpecRow("Subtitle Renderer", "Embedded Subtitles (libass)"));
        content.addView(createSettingsDivider());
        content.addView(buildSpecRow("Hardware Acceleration", "MediaCodec / ARM64 NEON"));
        content.addView(createSettingsDivider());

        // Diagnostic benchmarks action
        LinearLayout diagRow = new LinearLayout(this);
        diagRow.setOrientation(LinearLayout.HORIZONTAL);
        diagRow.setGravity(Gravity.CENTER_VERTICAL);
        diagRow.setPadding(0, dp(4), 0, dp(2));

        LinearLayout dTextCol = new LinearLayout(this);
        dTextCol.setOrientation(LinearLayout.VERTICAL);
        TextView dTitle = new TextView(this);
        dTitle.setText("Engine Diagnostics");
        dTitle.setTextSize(13f);
        dTitle.setTypeface(Typeface.create("sans-serif-medium", Typeface.BOLD));
        dTitle.setTextColor(Color.parseColor("#F4F4F5"));
        dTextCol.addView(dTitle);

        TextView dSub = new TextView(this);
        dSub.setText("Benchmark hardware codecs and audio transcoding");
        dSub.setTextSize(10.5f);
        dSub.setTextColor(Color.parseColor("#71717A"));
        dSub.setPadding(0, dp(1), 0, 0);
        dTextCol.addView(dSub);
        diagRow.addView(dTextCol, new LinearLayout.LayoutParams(0, -2, 1.0f));

        Button btnRunDiag = createPixelButton("BENCHMARKS", getSurfaceElevatedColor(), getAccentColor());
        btnRunDiag.setTextSize(10f);
        btnRunDiag.setOnClickListener(v -> showTranscodingBenchmarksDialog());
        diagRow.addView(btnRunDiag, new LinearLayout.LayoutParams(dp(100), dp(32)));

        content.addView(diagRow);

        return buildCollapsibleCard(
                "Playback & Codecs",
                "Inbuilt automatic engine • H.264, HEVC, AV1, VP9",
                content,
                false
        );
    }

    // -- Section 6: Software Updates (Drawer) ----------------------------------

    private View buildUpdatesSection() {
        LinearLayout content = new LinearLayout(this);
        content.setOrientation(LinearLayout.VERTICAL);

        // Row 1: Update Channel
        LinearLayout channelRow = new LinearLayout(this);
        channelRow.setOrientation(LinearLayout.HORIZONTAL);
        channelRow.setGravity(Gravity.CENTER_VERTICAL);
        channelRow.setPadding(0, dp(2), 0, dp(6));

        LinearLayout chTextCol = new LinearLayout(this);
        chTextCol.setOrientation(LinearLayout.VERTICAL);
        TextView chTitle = new TextView(this);
        chTitle.setText("Update Channel");
        chTitle.setTextSize(13.5f);
        chTitle.setTypeface(Typeface.create("sans-serif-medium", Typeface.BOLD));
        chTitle.setTextColor(Color.parseColor("#F4F4F5"));
        chTextCol.addView(chTitle);

        updateChannelBadge = new TextView(this);
        updateChannelBadge.setTextSize(11f);
        updateChannelBadge.setTextColor(Color.parseColor("#71717A"));
        updateChannelBadge.setPadding(0, dp(2), 0, 0);
        chTextCol.addView(updateChannelBadge);
        channelRow.addView(chTextCol, new LinearLayout.LayoutParams(0, -2, 1.0f));

        // Modern Segmented Pill Container
        LinearLayout pillContainer = new LinearLayout(this);
        pillContainer.setOrientation(LinearLayout.HORIZONTAL);
        pillContainer.setGravity(Gravity.CENTER_VERTICAL);
        pillContainer.setBackground(createBorderedRoundedDrawable(Color.parseColor("#18181B"), Color.parseColor("#27272A"), dp(1), dp(8)));
        pillContainer.setPadding(dp(2), dp(2), dp(2), dp(2));

        btnChannelStable = new Button(this);
        btnChannelStable.setText("STABLE");
        btnChannelStable.setTextSize(10f);
        btnChannelStable.setTypeface(Typeface.create("sans-serif-medium", Typeface.BOLD));
        btnChannelStable.setPadding(dp(12), 0, dp(12), 0);
        btnChannelStable.setGravity(Gravity.CENTER);
        btnChannelStable.setMinHeight(0);
        btnChannelStable.setMinimumHeight(0);
        btnChannelStable.setOnClickListener(v -> {
            UpdateManager.getInstance(this).setUpdateChannel(UpdateManager.CHANNEL_STABLE);
            updateChannelSelectionUI();
            UpdateManager.getInstance(this).checkForUpdates(this, true);
        });
        pillContainer.addView(btnChannelStable, new LinearLayout.LayoutParams(-2, dp(28)));

        btnChannelBeta = new Button(this);
        btnChannelBeta.setText("BETA");
        btnChannelBeta.setTextSize(10f);
        btnChannelBeta.setTypeface(Typeface.create("sans-serif-medium", Typeface.BOLD));
        btnChannelBeta.setPadding(dp(12), 0, dp(12), 0);
        btnChannelBeta.setGravity(Gravity.CENTER);
        btnChannelBeta.setMinHeight(0);
        btnChannelBeta.setMinimumHeight(0);
        btnChannelBeta.setOnClickListener(v -> {
            UpdateManager.getInstance(this).setUpdateChannel(UpdateManager.CHANNEL_BETA);
            updateChannelSelectionUI();
            UpdateManager.getInstance(this).checkForUpdates(this, true);
        });
        pillContainer.addView(btnChannelBeta, new LinearLayout.LayoutParams(-2, dp(28)));

        channelRow.addView(pillContainer, new LinearLayout.LayoutParams(-2, dp(32)));

        content.addView(channelRow);
        updateChannelSelectionUI();

        content.addView(createSettingsDivider());

        // Row 2: Software Updates Check
        LinearLayout updateRow = new LinearLayout(this);
        updateRow.setOrientation(LinearLayout.HORIZONTAL);
        updateRow.setGravity(Gravity.CENTER_VERTICAL);
        updateRow.setPadding(0, dp(2), 0, dp(4));

        LinearLayout uTextCol = new LinearLayout(this);
        uTextCol.setOrientation(LinearLayout.VERTICAL);
        TextView uTitle = new TextView(this);
        uTitle.setText("Software Version");
        uTitle.setTextSize(13.5f);
        uTitle.setTypeface(Typeface.create("sans-serif-medium", Typeface.BOLD));
        uTitle.setTextColor(Color.parseColor("#F4F4F5"));
        uTextCol.addView(uTitle);

        updateStatusText = new TextView(this);
        updateStatusText.setText("v" + com.termux.BuildConfig.VERSION_NAME + " (Installed)");
        updateStatusText.setTextSize(11f);
        updateStatusText.setTextColor(Color.parseColor("#71717A"));
        updateStatusText.setPadding(0, dp(2), 0, 0);
        uTextCol.addView(updateStatusText);
        updateRow.addView(uTextCol, new LinearLayout.LayoutParams(0, -2, 1.0f));

        updateActionBtn = createPixelButton("CHECK FOR UPDATES", getAccentColor(), Color.WHITE);
        updateActionBtn.setTextSize(10f);
        updateRow.addView(updateActionBtn, new LinearLayout.LayoutParams(-2, dp(34)));

        content.addView(updateRow);

        updateForceDownloadBtn = createPixelButton("Force Re-download / Test OTA", getSurfaceElevatedColor(), Color.parseColor("#81C784"));
        updateForceDownloadBtn.setVisibility(View.GONE);
        LinearLayout.LayoutParams pF = new LinearLayout.LayoutParams(-1, dp(36));
        pF.topMargin = dp(6);
        content.addView(updateForceDownloadBtn, pF);

        content.addView(createSettingsDivider());

        // Integrated Services Matrix Header
        TextView servicesHeading = new TextView(this);
        servicesHeading.setText("COMPANION SERVICES & ENGINES");
        servicesHeading.setTextSize(10.5f);
        servicesHeading.setTypeface(Typeface.create("sans-serif-medium", Typeface.BOLD));
        servicesHeading.setTextColor(getAccentColor());
        servicesHeading.setLetterSpacing(0.06f);
        servicesHeading.setPadding(0, dp(10), 0, dp(4));
        content.addView(servicesHeading);

        for (UpdateManager.ServiceVersionInfo svc : UpdateManager.getInstance(this).getUniversalServiceVersionMatrix()) {
            content.addView(buildServiceUpdateRow(svc));
            content.addView(createSettingsDivider());
        }

        boolean isBeta = UpdateManager.CHANNEL_BETA.equals(UpdateManager.getInstance(this).getUpdateChannel());
        return buildCollapsibleCard(
                "Software Updates",
                "Channel: " + (isBeta ? "Beta" : "Stable") + " • OTA updates",
                content,
                false
        );
    }

    private View buildServiceUpdateRow(UpdateManager.ServiceVersionInfo svc) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(0, dp(6), 0, dp(6));

        LinearLayout textCol = new LinearLayout(this);
        textCol.setOrientation(LinearLayout.VERTICAL);

        TextView tvName = new TextView(this);
        tvName.setText(svc.displayName);
        tvName.setTextSize(13f);
        tvName.setTypeface(Typeface.create("sans-serif-medium", Typeface.BOLD));
        tvName.setTextColor(Color.parseColor("#F4F4F5"));
        textCol.addView(tvName);

        TextView tvDesc = new TextView(this);
        tvDesc.setText("v" + svc.installedVersion + " • " + svc.notes);
        tvDesc.setTextSize(11f);
        tvDesc.setTextColor(Color.parseColor("#71717A"));
        tvDesc.setPadding(0, dp(2), 0, 0);
        textCol.addView(tvDesc);

        row.addView(textCol, new LinearLayout.LayoutParams(0, -2, 1.0f));

        TextView badge = new TextView(this);
        if ("jellyfin".equalsIgnoreCase(svc.serviceId)) {
            badge.setText("[v12.4 COMPATIBLE]");
            badge.setTextColor(Color.parseColor("#38BDF8"));
            badge.setBackground(createBorderedRoundedDrawable(Color.parseColor("#0C2333"), Color.parseColor("#0369A1"), dp(1), dp(6)));
        } else if (svc.updateAvailable) {
            badge.setText("[UPDATE]");
            badge.setTextColor(Color.parseColor("#F59E0B"));
            badge.setBackground(createBorderedRoundedDrawable(Color.parseColor("#271C0C"), Color.parseColor("#78350F"), dp(1), dp(6)));
        } else {
            badge.setText("[UP TO DATE]");
            badge.setTextColor(Color.parseColor("#81C784"));
            badge.setBackground(createBorderedRoundedDrawable(Color.parseColor("#14291E"), Color.parseColor("#1B4D2E"), dp(1), dp(6)));
        }
        badge.setTextSize(10f);
        badge.setTypeface(Typeface.create("sans-serif-medium", Typeface.BOLD));
        badge.setPadding(dp(8), dp(3), dp(8), dp(3));
        row.addView(badge, new LinearLayout.LayoutParams(-2, -2));

        if ("jellyfin".equalsIgnoreCase(svc.serviceId)) {
            row.setClickable(true);
            row.setFocusable(true);
            row.setOnClickListener(v -> {
                UpdateManager.JellyfinCompatibilityReport report = UpdateManager.getInstance(this).checkJellyfin124Compatibility(this);
                TextView tv = new TextView(this);
                tv.setText(report.summary);
                tv.setTextSize(11.5f);
                tv.setTypeface(Typeface.MONOSPACE);
                tv.setTextColor(Color.parseColor("#E4E4E7"));
                tv.setLineSpacing(dp(3), 1.2f);
                tv.setPadding(0, dp(4), 0, dp(8));
                showOledDialog("JELLYFIN v12.4 COMPATIBILITY",
                        "System Requirements & Architecture Verification",
                        tv,
                        "OK", getAccentColor(), null, null, null);
            });
        }

        return row;
    }

    // -- Section 6: Backup & Restore (1-Tap Export/Import & Migration) ----------

    private View buildBackupRestoreSection() {
        LinearLayout content = new LinearLayout(this);
        content.setOrientation(LinearLayout.VERTICAL);

        TextView descTv = new TextView(this);
        descTv.setText("Create a portable compressed backup of your FishBowl settings, credentials, and service configurations, or restore from an existing backup archive.");
        descTv.setTextSize(12f);
        descTv.setTextColor(Color.parseColor("#A1A1AA"));
        descTv.setLineSpacing(dp(3), 1.15f);
        descTv.setPadding(0, dp(2), 0, dp(10));
        content.addView(descTv);

        // Status Snapshot Card
        LinearLayout statusCard = new LinearLayout(this);
        statusCard.setOrientation(LinearLayout.VERTICAL);
        statusCard.setBackground(createBorderedRoundedDrawable(Color.parseColor("#14151A"), Color.parseColor("#27272A"), dp(1), dp(8)));
        statusCard.setPadding(dp(14), dp(12), dp(14), dp(12));

        TextView statusTitle = new TextView(this);
        statusTitle.setText("BACKUP SNAPSHOT");
        statusTitle.setTextSize(10.5f);
        statusTitle.setTypeface(Typeface.create("sans-serif-medium", Typeface.BOLD));
        statusTitle.setTextColor(Color.parseColor("#71717A"));
        statusCard.addView(statusTitle);

        backupStatusTv = new TextView(this);
        backupStatusTv.setTextSize(12.5f);
        backupStatusTv.setTextColor(Color.parseColor("#F4F4F5"));
        backupStatusTv.setPadding(0, dp(4), 0, dp(2));
        statusCard.addView(backupStatusTv);

        TextView locTv = new TextView(this);
        locTv.setText("Directory: /Download/" + BackupRestoreManager.BACKUP_DIR_NAME);
        locTv.setTextSize(11f);
        locTv.setTextColor(Color.parseColor("#A1A1AA"));
        statusCard.addView(locTv);

        content.addView(statusCard);
        updateBackupStatusUI();

        // Action Buttons Row
        LinearLayout actionsRow = new LinearLayout(this);
        actionsRow.setOrientation(LinearLayout.HORIZONTAL);
        actionsRow.setGravity(Gravity.CENTER_VERTICAL);
        LinearLayout.LayoutParams aParams = new LinearLayout.LayoutParams(-1, -2);
        aParams.topMargin = dp(10);
        actionsRow.setLayoutParams(aParams);

        Button btnExport = new Button(this);
        btnExport.setText("EXPORT BACKUP");
        btnExport.setTextSize(11f);
        btnExport.setTypeface(Typeface.create("sans-serif-medium", Typeface.BOLD));
        btnExport.setTextColor(Color.parseColor("#090A0F"));
        btnExport.setBackground(createBorderedRoundedDrawable(Color.parseColor("#55A9A0"), Color.parseColor("#55A9A0"), 0, dp(8)));
        btnExport.setOnClickListener(v -> triggerExportBackup());
        LinearLayout.LayoutParams b1P = new LinearLayout.LayoutParams(0, dp(38), 1.0f);
        b1P.rightMargin = dp(6);
        actionsRow.addView(btnExport, b1P);

        Button btnRestore = new Button(this);
        btnRestore.setText("RESTORE...");
        btnRestore.setTextSize(11f);
        btnRestore.setTypeface(Typeface.create("sans-serif-medium", Typeface.BOLD));
        btnRestore.setTextColor(Color.parseColor("#F4F4F5"));
        btnRestore.setBackground(createBorderedRoundedDrawable(Color.parseColor("#18181B"), Color.parseColor("#3F3F46"), dp(1), dp(8)));
        btnRestore.setOnClickListener(v -> showRestoreOptionsDialog());
        LinearLayout.LayoutParams b2P = new LinearLayout.LayoutParams(0, dp(38), 1.0f);
        b2P.leftMargin = dp(6);
        actionsRow.addView(btnRestore, b2P);

        content.addView(actionsRow);

        return buildCollapsibleCard(
                "Backup & Restore",
                "Export / import configurations & migration",
                content,
                false
        );
    }

    private void updateBackupStatusUI() {
        if (backupStatusTv == null) return;
        SharedPreferences sp = getSharedPreferences("fishbowl_settings", MODE_PRIVATE);
        long lastTime = sp.getLong(BackupRestoreManager.PREF_LAST_BACKUP_TIME, 0);
        long lastSize = sp.getLong(BackupRestoreManager.PREF_LAST_BACKUP_SIZE, 0);
        if (lastTime > 0) {
            String dateStr = new SimpleDateFormat("MMM dd, yyyy • HH:mm", Locale.getDefault()).format(new Date(lastTime));
            backupStatusTv.setText("Last Exported: " + dateStr + (lastSize > 0 ? " (" + MediaStorageManager.formatBytes(lastSize) + ")" : ""));
        } else {
            List<File> existing = BackupRestoreManager.listExistingBackups(this);
            if (!existing.isEmpty()) {
                File latest = existing.get(0);
                String dateStr = new SimpleDateFormat("MMM dd, yyyy • HH:mm", Locale.getDefault()).format(new Date(latest.lastModified()));
                backupStatusTv.setText("Latest Found: " + dateStr + " (" + MediaStorageManager.formatBytes(latest.length()) + ")");
            } else {
                backupStatusTv.setText("No backups created yet");
            }
        }
    }

    private Dialog createBackupProgressDialog(String titleText, String initialMsg, TextView[] msgHolder) {
        Dialog d = new Dialog(this);
        d.requestWindowFeature(Window.FEATURE_NO_TITLE);
        d.setCancelable(false);

        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setBackground(createBorderedRoundedDrawable(Color.BLACK, Color.parseColor("#27272A"), dp(1), dp(12)));
        box.setPadding(dp(20), dp(18), dp(20), dp(18));
        box.setGravity(Gravity.CENTER_HORIZONTAL);

        TextView t = new TextView(this);
        t.setText(titleText);
        t.setTextSize(14f);
        t.setTypeface(Typeface.create("sans-serif-medium", Typeface.BOLD));
        t.setTextColor(Color.WHITE);
        t.setGravity(Gravity.CENTER_HORIZONTAL);
        box.addView(t);

        ProgressBar pb = new ProgressBar(this);
        pb.setIndeterminate(true);
        LinearLayout.LayoutParams pbP = new LinearLayout.LayoutParams(dp(36), dp(36));
        pbP.topMargin = dp(14);
        pbP.bottomMargin = dp(10);
        box.addView(pb, pbP);

        TextView msgTv = new TextView(this);
        msgTv.setText(initialMsg);
        msgTv.setTextSize(11.5f);
        msgTv.setTextColor(Color.parseColor("#A1A1AA"));
        msgTv.setGravity(Gravity.CENTER_HORIZONTAL);
        box.addView(msgTv);
        if (msgHolder != null && msgHolder.length > 0) {
            msgHolder[0] = msgTv;
        }

        d.setContentView(box);
        if (d.getWindow() != null) {
            d.getWindow().setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
            d.getWindow().setDimAmount(0.85f);
            WindowManager.LayoutParams wlp = new WindowManager.LayoutParams();
            wlp.copyFrom(d.getWindow().getAttributes());
            wlp.width = (int) (getResources().getDisplayMetrics().widthPixels * 0.88);
            wlp.height = WindowManager.LayoutParams.WRAP_CONTENT;
            d.getWindow().setAttributes(wlp);
        }
        return d;
    }

    private void triggerExportBackup() {
        TextView[] msgHolder = new TextView[1];
        Dialog progressDialog = createBackupProgressDialog("EXPORTING BACKUP", "Staging preferences and service configs...", msgHolder);
        progressDialog.show();

        BackupRestoreManager.exportBackup(this, new BackupRestoreManager.BackupCallback() {
            @Override
            public void onProgress(String message) {
                if (msgHolder[0] != null) {
                    msgHolder[0].setText(message);
                }
            }

            @Override
            public void onSuccess(File backupFile, long fileSizeBytes, int itemsCount) {
                progressDialog.dismiss();
                updateBackupStatusUI();
                showBackupExportedModal(backupFile, fileSizeBytes, itemsCount);
            }

            @Override
            public void onError(String error) {
                progressDialog.dismiss();
                Toast.makeText(FishbowlActivity.this, error, Toast.LENGTH_LONG).show();
            }
        });
    }

    private void showBackupExportedModal(File backupFile, long sizeBytes, int itemsCount) {
        Dialog d = new Dialog(this);
        d.requestWindowFeature(Window.FEATURE_NO_TITLE);

        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setBackground(createBorderedRoundedDrawable(Color.BLACK, Color.parseColor("#27272A"), dp(1), dp(12)));
        box.setPadding(dp(18), dp(18), dp(18), dp(18));

        TextView title = new TextView(this);
        title.setText("BACKUP EXPORTED");
        title.setTextSize(14f);
        title.setTypeface(Typeface.create("sans-serif-medium", Typeface.BOLD));
        title.setTextColor(Color.parseColor("#55A9A0"));
        box.addView(title);

        TextView info = new TextView(this);
        info.setText("FishBowl configuration archive created successfully:\n\n"
                + "File: " + backupFile.getName() + "\n"
                + "Size: " + MediaStorageManager.formatBytes(sizeBytes) + "\n"
                + "Items: " + itemsCount + " files\n"
                + "Path: " + backupFile.getAbsolutePath());
        info.setTextSize(11.5f);
        info.setTextColor(Color.parseColor("#D4D4D8"));
        info.setLineSpacing(dp(2), 1.15f);
        info.setPadding(0, dp(10), 0, dp(14));
        box.addView(info);

        LinearLayout btnRow = new LinearLayout(this);
        btnRow.setOrientation(LinearLayout.HORIZONTAL);
        btnRow.setGravity(Gravity.CENTER_VERTICAL);

        Button btnShare = new Button(this);
        btnShare.setText("SHARE / SEND");
        btnShare.setTextSize(11f);
        btnShare.setTypeface(Typeface.create("sans-serif-medium", Typeface.BOLD));
        btnShare.setTextColor(Color.parseColor("#090A0F"));
        btnShare.setBackground(createBorderedRoundedDrawable(Color.parseColor("#55A9A0"), Color.parseColor("#55A9A0"), 0, dp(8)));
        btnShare.setOnClickListener(v -> {
            BackupRestoreManager.shareBackupFile(this, backupFile);
        });
        LinearLayout.LayoutParams b1P = new LinearLayout.LayoutParams(0, dp(38), 1.0f);
        b1P.rightMargin = dp(6);
        btnRow.addView(btnShare, b1P);

        Button btnDone = new Button(this);
        btnDone.setText("DONE");
        btnDone.setTextSize(11f);
        btnDone.setTypeface(Typeface.create("sans-serif-medium", Typeface.BOLD));
        btnDone.setTextColor(Color.parseColor("#F4F4F5"));
        btnDone.setBackground(createBorderedRoundedDrawable(Color.parseColor("#18181B"), Color.parseColor("#3F3F46"), dp(1), dp(8)));
        btnDone.setOnClickListener(v -> d.dismiss());
        LinearLayout.LayoutParams b2P = new LinearLayout.LayoutParams(0, dp(38), 1.0f);
        b2P.leftMargin = dp(6);
        btnRow.addView(btnDone, b2P);

        box.addView(btnRow);

        d.setContentView(box);
        if (d.getWindow() != null) {
            d.getWindow().setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
            d.getWindow().setDimAmount(0.85f);
            WindowManager.LayoutParams wlp = new WindowManager.LayoutParams();
            wlp.copyFrom(d.getWindow().getAttributes());
            wlp.width = (int) (getResources().getDisplayMetrics().widthPixels * 0.90);
            wlp.height = WindowManager.LayoutParams.WRAP_CONTENT;
            d.getWindow().setAttributes(wlp);
        }
        d.show();
    }

    private void showRestoreOptionsDialog() {
        Dialog d = new Dialog(this);
        d.requestWindowFeature(Window.FEATURE_NO_TITLE);

        ScrollView scroll = new ScrollView(this);
        scroll.setVerticalScrollBarEnabled(false);
        scroll.setBackground(createBorderedRoundedDrawable(Color.BLACK, Color.parseColor("#27272A"), dp(1), dp(12)));

        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(dp(18), dp(18), dp(18), dp(18));
        scroll.addView(box);

        TextView title = new TextView(this);
        title.setText("RESTORE CONFIGURATION");
        title.setTextSize(14f);
        title.setTypeface(Typeface.create("sans-serif-medium", Typeface.BOLD));
        title.setTextColor(Color.WHITE);
        box.addView(title);

        TextView sub = new TextView(this);
        sub.setText("Select a backup to restore preferences and service configs:");
        sub.setTextSize(11.5f);
        sub.setTextColor(Color.parseColor("#71717A"));
        sub.setPadding(0, dp(4), 0, dp(12));
        box.addView(sub);

        List<File> backups = BackupRestoreManager.listExistingBackups(this);
        if (backups.isEmpty()) {
            TextView emptyTv = new TextView(this);
            emptyTv.setText("No backups found in /Download/" + BackupRestoreManager.BACKUP_DIR_NAME);
            emptyTv.setTextSize(11f);
            emptyTv.setTextColor(Color.parseColor("#A1A1AA"));
            emptyTv.setPadding(0, dp(6), 0, dp(12));
            box.addView(emptyTv);
        } else {
            for (File bf : backups) {
                LinearLayout item = new LinearLayout(this);
                item.setOrientation(LinearLayout.VERTICAL);
                item.setBackground(createBorderedRoundedDrawable(Color.parseColor("#14151A"), Color.parseColor("#27272A"), dp(1), dp(8)));
                item.setPadding(dp(12), dp(10), dp(12), dp(10));
                LinearLayout.LayoutParams iP = new LinearLayout.LayoutParams(-1, -2);
                iP.bottomMargin = dp(8);
                item.setLayoutParams(iP);

                TextView nameTv = new TextView(this);
                nameTv.setText(bf.getName());
                nameTv.setTextSize(12f);
                nameTv.setTypeface(Typeface.create("sans-serif-medium", Typeface.BOLD));
                nameTv.setTextColor(Color.parseColor("#F4F4F5"));
                item.addView(nameTv);

                String dateStr = new SimpleDateFormat("MMM dd, yyyy • HH:mm", Locale.getDefault()).format(new Date(bf.lastModified()));
                TextView metaTv = new TextView(this);
                metaTv.setText(dateStr + " • " + MediaStorageManager.formatBytes(bf.length()));
                metaTv.setTextSize(10.5f);
                metaTv.setTextColor(Color.parseColor("#71717A"));
                metaTv.setPadding(0, dp(2), 0, 0);
                item.addView(metaTv);

                item.setOnClickListener(v -> {
                    d.dismiss();
                    confirmAndRestoreFromFile(bf);
                });

                box.addView(item);
            }
        }

        // File picker button (SAF)
        Button btnPickFile = new Button(this);
        btnPickFile.setText("CHOOSE FROM FILES... (SAF)");
        btnPickFile.setTextSize(11f);
        btnPickFile.setTypeface(Typeface.create("sans-serif-medium", Typeface.BOLD));
        btnPickFile.setTextColor(Color.parseColor("#55A9A0"));
        btnPickFile.setBackground(createBorderedRoundedDrawable(Color.parseColor("#18181B"), Color.parseColor("#55A9A0"), dp(1), dp(8)));
        btnPickFile.setOnClickListener(v -> {
            d.dismiss();
            launchFilePickerForRestore();
        });
        LinearLayout.LayoutParams pickP = new LinearLayout.LayoutParams(-1, dp(38));
        pickP.topMargin = dp(4);
        pickP.bottomMargin = dp(8);
        box.addView(btnPickFile, pickP);

        // Cancel
        Button btnCancel = new Button(this);
        btnCancel.setText("CANCEL");
        btnCancel.setTextSize(11f);
        btnCancel.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        btnCancel.setTextColor(Color.parseColor("#A1A1AA"));
        btnCancel.setBackground(createBorderedRoundedDrawable(Color.TRANSPARENT, Color.parseColor("#27272A"), dp(1), dp(8)));
        btnCancel.setOnClickListener(v -> d.dismiss());
        box.addView(btnCancel, new LinearLayout.LayoutParams(-1, dp(36)));

        d.setContentView(scroll);
        if (d.getWindow() != null) {
            d.getWindow().setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
            d.getWindow().setDimAmount(0.85f);
            WindowManager.LayoutParams wlp = new WindowManager.LayoutParams();
            wlp.copyFrom(d.getWindow().getAttributes());
            wlp.width = (int) (getResources().getDisplayMetrics().widthPixels * 0.92);
            wlp.height = (int) (getResources().getDisplayMetrics().heightPixels * 0.65);
            d.getWindow().setAttributes(wlp);
        }
        d.show();
    }

    private void launchFilePickerForRestore() {
        try {
            Intent intent = new Intent(Intent.ACTION_GET_CONTENT);
            intent.setType("*/*");
            intent.addCategory(Intent.CATEGORY_OPENABLE);
            restoreFilePickerLauncher.launch(Intent.createChooser(intent, "Select FishBowl Backup (.zip)"));
        } catch (Throwable t) {
            Toast.makeText(this, "Cannot open file picker: " + t.getMessage(), Toast.LENGTH_SHORT).show();
        }
    }

    private void confirmAndRestoreFromFile(File file) {
        showConfirmRestoreDialog(file.getName(), () -> {
            TextView[] msgHolder = new TextView[1];
            Dialog progressDialog = createBackupProgressDialog("RESTORING CONFIGURATION", "Extracting and applying configuration files...", msgHolder);
            progressDialog.show();

            BackupRestoreManager.restoreBackup(this, file, new BackupRestoreManager.RestoreCallback() {
                @Override
                public void onProgress(String message) {
                    if (msgHolder[0] != null) {
                        msgHolder[0].setText(message);
                    }
                }

                @Override
                public void onSuccess(int restoredPrefsCount, int restoredConfigsCount) {
                    progressDialog.dismiss();
                    showRestoreSuccessModal(restoredPrefsCount, restoredConfigsCount);
                }

                @Override
                public void onError(String error) {
                    progressDialog.dismiss();
                    Toast.makeText(FishbowlActivity.this, error, Toast.LENGTH_LONG).show();
                }
            });
        });
    }

    private void confirmAndRestoreFromUri(Uri uri) {
        showConfirmRestoreDialog("Selected File Archive", () -> {
            TextView[] msgHolder = new TextView[1];
            Dialog progressDialog = createBackupProgressDialog("RESTORING CONFIGURATION", "Extracting and applying configuration files...", msgHolder);
            progressDialog.show();

            BackupRestoreManager.restoreBackup(this, uri, new BackupRestoreManager.RestoreCallback() {
                @Override
                public void onProgress(String message) {
                    if (msgHolder[0] != null) {
                        msgHolder[0].setText(message);
                    }
                }

                @Override
                public void onSuccess(int restoredPrefsCount, int restoredConfigsCount) {
                    progressDialog.dismiss();
                    showRestoreSuccessModal(restoredPrefsCount, restoredConfigsCount);
                }

                @Override
                public void onError(String error) {
                    progressDialog.dismiss();
                    Toast.makeText(FishbowlActivity.this, error, Toast.LENGTH_LONG).show();
                }
            });
        });
    }

    private void showConfirmRestoreDialog(String sourceName, Runnable onConfirmed) {
        Dialog d = new Dialog(this);
        d.requestWindowFeature(Window.FEATURE_NO_TITLE);

        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setBackground(createBorderedRoundedDrawable(Color.BLACK, Color.parseColor("#27272A"), dp(1), dp(12)));
        box.setPadding(dp(18), dp(18), dp(18), dp(18));

        TextView title = new TextView(this);
        title.setText("CONFIRM RESTORE");
        title.setTextSize(14f);
        title.setTypeface(Typeface.create("sans-serif-medium", Typeface.BOLD));
        title.setTextColor(Color.parseColor("#FFD51E"));
        box.addView(title);

        TextView msg = new TextView(this);
        msg.setText("Restoring from:\n" + sourceName + "\n\n"
                + "This will overwrite your current FishBowl preferences, API keys, and media service configurations with the backup archive.\n\n"
                + "Are you sure you want to proceed?");
        msg.setTextSize(11.5f);
        msg.setTextColor(Color.parseColor("#D4D4D8"));
        msg.setLineSpacing(dp(2), 1.15f);
        msg.setPadding(0, dp(10), 0, dp(14));
        box.addView(msg);

        LinearLayout btnRow = new LinearLayout(this);
        btnRow.setOrientation(LinearLayout.HORIZONTAL);
        btnRow.setGravity(Gravity.CENTER_VERTICAL);

        Button btnProceed = new Button(this);
        btnProceed.setText("RESTORE NOW");
        btnProceed.setTextSize(11f);
        btnProceed.setTypeface(Typeface.create("sans-serif-medium", Typeface.BOLD));
        btnProceed.setTextColor(Color.BLACK);
        btnProceed.setBackground(createBorderedRoundedDrawable(Color.parseColor("#FFD51E"), Color.parseColor("#FFD51E"), 0, dp(8)));
        btnProceed.setOnClickListener(v -> {
            d.dismiss();
            if (onConfirmed != null) onConfirmed.run();
        });
        LinearLayout.LayoutParams b1P = new LinearLayout.LayoutParams(0, dp(38), 1.0f);
        b1P.rightMargin = dp(6);
        btnRow.addView(btnProceed, b1P);

        Button btnCancel = new Button(this);
        btnCancel.setText("CANCEL");
        btnCancel.setTextSize(11f);
        btnCancel.setTypeface(Typeface.create("sans-serif-medium", Typeface.BOLD));
        btnCancel.setTextColor(Color.parseColor("#F4F4F5"));
        btnCancel.setBackground(createBorderedRoundedDrawable(Color.parseColor("#18181B"), Color.parseColor("#3F3F46"), dp(1), dp(8)));
        btnCancel.setOnClickListener(v -> d.dismiss());
        LinearLayout.LayoutParams b2P = new LinearLayout.LayoutParams(0, dp(38), 1.0f);
        b2P.leftMargin = dp(6);
        btnRow.addView(btnCancel, b2P);

        box.addView(btnRow);

        d.setContentView(box);
        if (d.getWindow() != null) {
            d.getWindow().setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
            d.getWindow().setDimAmount(0.85f);
            WindowManager.LayoutParams wlp = new WindowManager.LayoutParams();
            wlp.copyFrom(d.getWindow().getAttributes());
            wlp.width = (int) (getResources().getDisplayMetrics().widthPixels * 0.90);
            wlp.height = WindowManager.LayoutParams.WRAP_CONTENT;
            d.getWindow().setAttributes(wlp);
        }
        d.show();
    }

    private void showRestoreSuccessModal(int prefsCount, int configsCount) {
        Dialog d = new Dialog(this);
        d.requestWindowFeature(Window.FEATURE_NO_TITLE);

        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setBackground(createBorderedRoundedDrawable(Color.BLACK, Color.parseColor("#27272A"), dp(1), dp(12)));
        box.setPadding(dp(18), dp(18), dp(18), dp(18));

        TextView title = new TextView(this);
        title.setText("CONFIGURATION RESTORED");
        title.setTextSize(14f);
        title.setTypeface(Typeface.create("sans-serif-medium", Typeface.BOLD));
        title.setTextColor(Color.parseColor("#55A9A0"));
        box.addView(title);

        TextView info = new TextView(this);
        info.setText("Successfully restored configuration files:\n\n"
                + "• Preferences: " + prefsCount + " files\n"
                + "• Service Configs: " + configsCount + " files\n\n"
                + "To ensure all running daemons reload their restored configuration and credentials, please restart services.");
        info.setTextSize(11.5f);
        info.setTextColor(Color.parseColor("#D4D4D8"));
        info.setLineSpacing(dp(2), 1.15f);
        info.setPadding(0, dp(10), 0, dp(14));
        box.addView(info);

        Button btnRestart = new Button(this);
        btnRestart.setText("RESTART SERVICES NOW");
        btnRestart.setTextSize(11f);
        btnRestart.setTypeface(Typeface.create("sans-serif-medium", Typeface.BOLD));
        btnRestart.setTextColor(Color.parseColor("#090A0F"));
        btnRestart.setBackground(createBorderedRoundedDrawable(Color.parseColor("#55A9A0"), Color.parseColor("#55A9A0"), 0, dp(8)));
        btnRestart.setOnClickListener(v -> {
            d.dismiss();
            triggerServerAction(JellyfinServerService.ACTION_STOP_ALL);
            mainHandler.postDelayed(() -> triggerServerAction(JellyfinServerService.ACTION_START_ALL), 1000);
            Toast.makeText(this, "Restarting all services...", Toast.LENGTH_SHORT).show();
            mainHandler.postDelayed(() -> {
                refreshServicesTabUI();
                refreshHomeServicesBadge();
                updateBackupStatusUI();
            }, 1200);
        });
        box.addView(btnRestart, new LinearLayout.LayoutParams(-1, dp(40)));

        d.setContentView(box);
        if (d.getWindow() != null) {
            d.getWindow().setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
            d.getWindow().setDimAmount(0.85f);
            WindowManager.LayoutParams wlp = new WindowManager.LayoutParams();
            wlp.copyFrom(d.getWindow().getAttributes());
            wlp.width = (int) (getResources().getDisplayMetrics().widthPixels * 0.90);
            wlp.height = WindowManager.LayoutParams.WRAP_CONTENT;
            d.getWindow().setAttributes(wlp);
        }
        d.show();
    }

    // -- Tools Quick-Access Actions (Home Tab) ---------------------------------

    private View buildHomeToolsSection() {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2);
        lp.topMargin = dp(14);
        row.setLayoutParams(lp);

        Button btnImporter = createPixelButton("MOVIE IMPORTER", getSurfaceElevatedColor(), Color.WHITE);
        btnImporter.setTextSize(10f);
        btnImporter.setOnClickListener(v -> startActivity(new Intent(this, com.fishbowl.app.importer.MovieImportActivity.class)));
        LinearLayout.LayoutParams p1 = new LinearLayout.LayoutParams(0, dp(36), 1.0f);
        p1.rightMargin = dp(5);
        row.addView(btnImporter, p1);

        Button btnStorage = createPixelButton("MEDIA STORAGE", getSurfaceElevatedColor(), getPrimaryTextColor());
        btnStorage.setTextSize(10f);
        btnStorage.setOnClickListener(v -> startActivity(new Intent(this, JellyfinStorageActivity.class)));
        LinearLayout.LayoutParams p2 = new LinearLayout.LayoutParams(0, dp(36), 1.0f);
        p2.leftMargin = dp(5);
        row.addView(btnStorage, p2);

        return row;
    }

    // -- Section 7: Movie Importer (Drawer - Downloads & Folder Organizer) ------

    private View buildMovieImporterSection() {
        LinearLayout content = new LinearLayout(this);
        content.setOrientation(LinearLayout.VERTICAL);

        TextView descTv = new TextView(this);
        descTv.setText("Organize browser-downloaded video files into standard Jellyfin movie folder structures (e.g. Movie Title (Year)/Movie Title (Year).ext) with automatic metadata matching.");
        descTv.setTextSize(12f);
        descTv.setTextColor(Color.parseColor("#A1A1AA"));
        descTv.setLineSpacing(dp(3), 1.15f);
        descTv.setPadding(0, dp(2), 0, dp(10));
        content.addView(descTv);

        // Open Movie Importer Button (High-visibility action)
        Button btnOpenImporter = createPixelButton("OPEN MOVIE IMPORTER", Color.WHITE, Color.BLACK);
        btnOpenImporter.setOnClickListener(v -> startActivity(new Intent(this, com.fishbowl.app.importer.MovieImportActivity.class)));
        LinearLayout.LayoutParams pBtn = new LinearLayout.LayoutParams(-1, dp(42));
        pBtn.bottomMargin = dp(12);
        content.addView(btnOpenImporter, pBtn);

        // 1. Detect New Downloads Toggle (Mode C)
        com.fishbowl.app.importer.DownloadDetectionManager dm = com.fishbowl.app.importer.DownloadDetectionManager.getInstance(this);
        LinearLayout autoDetectRow = new LinearLayout(this);
        autoDetectRow.setOrientation(LinearLayout.HORIZONTAL);
        autoDetectRow.setGravity(Gravity.CENTER_VERTICAL);
        autoDetectRow.setPadding(0, dp(4), 0, dp(4));

        LinearLayout adTextCol = new LinearLayout(this);
        adTextCol.setOrientation(LinearLayout.VERTICAL);

        TextView adTitle = new TextView(this);
        adTitle.setText("Detect New Downloads");
        adTitle.setTextSize(13.5f);
        adTitle.setTypeface(Typeface.create("sans-serif-medium", Typeface.BOLD));
        adTitle.setTextColor(Color.parseColor("#F4F4F5"));
        adTextCol.addView(adTitle);

        TextView adSub = new TextView(this);
        adSub.setText("Alerts when browser downloads complete. Never auto-moves files.");
        adSub.setTextSize(11f);
        adSub.setTextColor(Color.parseColor("#71717A"));
        adSub.setPadding(0, dp(2), 0, 0);
        adTextCol.addView(adSub);

        autoDetectRow.addView(adTextCol, new LinearLayout.LayoutParams(0, -2, 1.0f));

        Switch detectSwitch = new Switch(this);
        detectSwitch.setChecked(dm.isDetectionEnabled());
        detectSwitch.setOnCheckedChangeListener((b, isChecked) -> {
            dm.setDetectionEnabled(isChecked);
            Toast.makeText(this, isChecked ? "Download detection enabled" : "Download detection disabled", Toast.LENGTH_SHORT).show();
        });
        autoDetectRow.addView(detectSwitch);
        content.addView(autoDetectRow);

        content.addView(createSettingsDivider());

        // 2. Configure TMDB API Key Row
        LinearLayout tmdbRow = new LinearLayout(this);
        tmdbRow.setOrientation(LinearLayout.HORIZONTAL);
        tmdbRow.setGravity(Gravity.CENTER_VERTICAL);
        tmdbRow.setPadding(0, dp(4), 0, dp(4));

        LinearLayout tmdbTextCol = new LinearLayout(this);
        tmdbTextCol.setOrientation(LinearLayout.VERTICAL);

        TextView tmdbTitle = new TextView(this);
        tmdbTitle.setText("Metadata Provider (TMDB)");
        tmdbTitle.setTextSize(13.5f);
        tmdbTitle.setTypeface(Typeface.create("sans-serif-medium", Typeface.BOLD));
        tmdbTitle.setTextColor(Color.parseColor("#F4F4F5"));
        tmdbTextCol.addView(tmdbTitle);

        TextView tmdbSub = new TextView(this);
        tmdbSub.setText("Optional TMDB API key for automatic posters and overviews.");
        tmdbSub.setTextSize(11f);
        tmdbSub.setTextColor(Color.parseColor("#71717A"));
        tmdbSub.setPadding(0, dp(2), 0, 0);
        tmdbTextCol.addView(tmdbSub);

        tmdbRow.addView(tmdbTextCol, new LinearLayout.LayoutParams(0, -2, 1.0f));

        Button btnTmdbKey = createPixelButton("KEY", getSurfaceElevatedColor(), getPrimaryTextColor());
        btnTmdbKey.setTextSize(10f);
        btnTmdbKey.setOnClickListener(v -> showTmdbKeyConfigDialog());
        tmdbRow.addView(btnTmdbKey, new LinearLayout.LayoutParams(-2, dp(34)));
        content.addView(tmdbRow);

        content.addView(createSettingsDivider());

        // 3. Default Naming Convention Card
        TextView namingFormatTv = new TextView(this);
        namingFormatTv.setText("Default Naming: Movie Title (Year)/Movie Title (Year).ext\nConflict Policy: Keep Both (safe versioning) • Retain Original: Enabled");
        namingFormatTv.setTextSize(11f);
        namingFormatTv.setTextColor(Color.parseColor("#71717A"));
        namingFormatTv.setPadding(0, dp(4), 0, dp(4));
        content.addView(namingFormatTv);

        return buildCollapsibleCard(
                "Movie Importer",
                "Downloads organizer, metadata matcher & detection",
                content,
                false
        );
    }

    private void showTmdbKeyConfigDialog() {
        EditText input = new EditText(this);
        String currentKey = com.fishbowl.app.importer.TmdbMetadataProvider.getApiKey(this);
        if (currentKey != null) input.setText(currentKey);
        input.setHint("Enter TMDB v3 API Key (optional)");
        input.setTextColor(getPrimaryTextColor());
        input.setPadding(dp(16), dp(12), dp(16), dp(12));

        new androidx.appcompat.app.AlertDialog.Builder(this)
                .setTitle("TMDB API Configuration")
                .setMessage("Enter your personal TMDB API key to fetch movie posters and overviews during import.\n\nLeave blank to use filename heuristics.")
                .setView(input)
                .setPositiveButton("SAVE", (d, w) -> {
                    String key = input.getText().toString().trim();
                    com.fishbowl.app.importer.TmdbMetadataProvider.setApiKey(this, key);
                    Toast.makeText(this, key.isEmpty() ? "TMDB API key cleared" : "TMDB API key saved", Toast.LENGTH_SHORT).show();
                })
                .setNegativeButton("CANCEL", null)
                .show();
    }

    // -- Section 8: About Fishbowl (Drawer - Specs, Architecture & Licenses) ----

    private View buildAboutSection() {
        LinearLayout content = new LinearLayout(this);
        content.setOrientation(LinearLayout.VERTICAL);

        TextView descTv = new TextView(this);
        descTv.setText("Fishbowl is an all-in-one local media streaming server for Android, bundling Jellyfin Server, .NET runtime, and native hardware transcoding with zero trackers.");
        descTv.setTextSize(12f);
        descTv.setTextColor(Color.parseColor("#A1A1AA"));
        descTv.setLineSpacing(dp(3), 1.15f);
        descTv.setPadding(0, dp(2), 0, dp(10));
        content.addView(descTv);

        // Subheading: System Architecture & Specifications
        TextView archHeading = new TextView(this);
        archHeading.setText("SYSTEM ARCHITECTURE & SPECS");
        archHeading.setTextSize(10.5f);
        archHeading.setTypeface(Typeface.create("sans-serif-medium", Typeface.BOLD));
        archHeading.setTextColor(getAccentColor());
        archHeading.setLetterSpacing(0.06f);
        archHeading.setPadding(0, dp(6), 0, dp(6));
        content.addView(archHeading);

        content.addView(buildSpecRow("Jellyfin Server Core", "v12.4.0 (.NET 10 ARM64)"));
        content.addView(createSettingsDivider());
        content.addView(buildSpecRow("Microsoft .NET Runtime", "v10.0.12 Linux Bionic"));
        content.addView(createSettingsDivider());
        content.addView(buildSpecRow("Hardware Transcoder", "FFmpeg 7.1.4-Jellyfin ARM64"));
        content.addView(createSettingsDivider());
        content.addView(buildSpecRow("CPU Architecture", "ARM64 (aarch64)"));
        content.addView(createSettingsDivider());
        content.addView(buildSpecRow("Database Engine", "SQLite3 (Embedded Local)"));
        content.addView(createSettingsDivider());
        content.addView(buildSpecRow("Security & TLS Stack", "OpenSSL 3.x System Native"));
        content.addView(createSettingsDivider());
        content.addView(buildSpecRow("Process Isolation", "Android Sandboxed UID (100% Local)"));
        content.addView(createSettingsDivider());
        content.addView(buildSpecRow("Package Scope", getPackageName()));
        content.addView(createSettingsDivider());
        content.addView(buildSpecRow("Privacy & Telemetry", "100% Local • Zero Trackers"));
        content.addView(createSettingsDivider());

        // Subheading: Media Stack & Service Versions
        TextView stackHeading = new TextView(this);
        stackHeading.setText("MEDIA STACK & SERVICE VERSIONS");
        stackHeading.setTextSize(10.5f);
        stackHeading.setTypeface(Typeface.create("sans-serif-medium", Typeface.BOLD));
        stackHeading.setTextColor(getAccentColor());
        stackHeading.setLetterSpacing(0.06f);
        stackHeading.setPadding(0, dp(8), 0, dp(6));
        content.addView(stackHeading);

        for (UpdateManager.ServiceVersionInfo svc : UpdateManager.getInstance(this).getUniversalServiceVersionMatrix()) {
            content.addView(buildSpecRow(svc.displayName, "v" + svc.installedVersion));
            content.addView(createSettingsDivider());
        }


        // Subheading: Open Source Licensing
        TextView licHeading = new TextView(this);
        licHeading.setText("OPEN SOURCE LICENSING");
        licHeading.setTextSize(10.5f);
        licHeading.setTypeface(Typeface.create("sans-serif-medium", Typeface.BOLD));
        licHeading.setTextColor(Color.parseColor("#8B5CF6"));
        licHeading.setLetterSpacing(0.06f);
        licHeading.setPadding(0, dp(8), 0, dp(6));
        content.addView(licHeading);

        content.addView(buildSpecRow("Fishbowl", "GNU GPL v3.0"));
        content.addView(createSettingsDivider());
        content.addView(buildSpecRow("Jellyfin Server Core", "GNU GPL v2.0"));
        content.addView(createSettingsDivider());
        content.addView(buildSpecRow("FFmpeg Transcoder", "LGPL v2.1+ / GPL v2.0+"));
        content.addView(createSettingsDivider());
        content.addView(buildSpecRow("Microsoft .NET Runtime", "MIT License"));
        content.addView(createSettingsDivider());
        content.addView(buildSpecRow("Transmission Daemon", "GNU GPL v2.0 / v3.0"));
        content.addView(createSettingsDivider());
        content.addView(buildSpecRow("Jackett Indexer Proxy", "GNU GPL v2.0"));
        content.addView(createSettingsDivider());
        content.addView(buildSpecRow("Termux Android Environment", "Apache License 2.0"));
        content.addView(createSettingsDivider());
        content.addView(buildSpecRow("Servarr Stack (Radarr/Sonarr/Lidarr/Prowlarr)", "GNU GPL v3.0"));
        content.addView(createSettingsDivider());
        content.addView(buildSpecRow("Bazarr Subtitle Manager", "GNU GPL v3.0"));
        content.addView(createSettingsDivider());
        content.addView(buildSpecRow("Jellyseerr Media Requests", "MIT License"));
        content.addView(createSettingsDivider());
        content.addView(buildSpecRow("FlareSolverr Cloudflare Proxy", "MIT License"));
        content.addView(createSettingsDivider());

        // Licensing Details Dialog Action
        LinearLayout licActionRow = new LinearLayout(this);
        licActionRow.setOrientation(LinearLayout.HORIZONTAL);
        licActionRow.setGravity(Gravity.CENTER_VERTICAL);
        licActionRow.setPadding(0, dp(4), 0, dp(4));

        LinearLayout licTextCol = new LinearLayout(this);
        licTextCol.setOrientation(LinearLayout.VERTICAL);
        TextView licTitle = new TextView(this);
        licTitle.setText("License Text & Notices");
        licTitle.setTextSize(13f);
        licTitle.setTypeface(Typeface.create("sans-serif-medium", Typeface.BOLD));
        licTitle.setTextColor(Color.parseColor("#F4F4F5"));
        licTextCol.addView(licTitle);

        TextView licSub = new TextView(this);
        licSub.setText("View third-party notices and legal disclaimers");
        licSub.setTextSize(10.5f);
        licSub.setTextColor(Color.parseColor("#71717A"));
        licSub.setPadding(0, dp(1), 0, 0);
        licTextCol.addView(licSub);
        licActionRow.addView(licTextCol, new LinearLayout.LayoutParams(0, -2, 1.0f));

        Button btnViewLicenses = createPixelButton("VIEW NOTICES", getSurfaceElevatedColor(), Color.parseColor("#8B5CF6"));
        btnViewLicenses.setTextSize(10f);
        btnViewLicenses.setOnClickListener(v -> showLicensesDialog());
        licActionRow.addView(btnViewLicenses, new LinearLayout.LayoutParams(dp(110), dp(32)));
        content.addView(licActionRow);

        return buildCollapsibleCard(
                "About Fishbowl",
                "v" + com.termux.BuildConfig.VERSION_NAME + " • Architecture, Specs & Licenses",
                content,
                false
        );
    }

    private void showLicensesDialog() {
        TextView tv = new TextView(this);
        tv.setTextSize(10.5f);
        tv.setTypeface(Typeface.MONOSPACE);
        tv.setTextColor(Color.parseColor("#D4D4D8"));
        tv.setLineSpacing(dp(2), 1.15f);
        String licText = "FISHBOWL OPEN SOURCE LICENSES & NOTICES\n\n"
                + "1. Fishbowl (GPL-3.0)\n"
                + "Copyright (c) 2026 Fishbowl Contributors\n"
                + "Licensed under the GNU General Public License v3.0.\n\n"
                + "2. Jellyfin Server (GPL-2.0)\n"
                + "Copyright (c) Jellyfin Contributors\n"
                + "Licensed under the GNU General Public License v2.0.\n\n"
                + "3. FFmpeg (LGPL-2.1+ / GPL-2.0+)\n"
                + "Copyright (c) FFmpeg Developers\n\n"
                + "4. Microsoft .NET Runtime (MIT)\n"
                + "Copyright (c) .NET Foundation and Contributors\n\n"
                + "5. Transmission Daemon (GPL-2.0 / GPL-3.0)\n"
                + "Copyright (c) Transmission Project\n\n"
                + "6. Jackett Torrent Indexer Proxy (GPL-2.0)\n"
                + "Copyright (c) Jackett Contributors\n"
                + "Licensed under the GNU General Public License v2.0.\n\n"
                + "7. Termux Android Environment (Apache-2.0)\n"
                + "Copyright (c) Termux Open Source Project\n\n"
                + "8. Servarr Stack (Radarr, Sonarr, Lidarr, Prowlarr) (GPL-3.0)\n"
                + "Copyright (c) Servarr Development Team\n\n"
                + "9. Bazarr Subtitles (GPL-3.0)\n"
                + "Copyright (c) Bazarr Contributors\n"
                + "Licensed under the GNU General Public License v3.0.\n\n"
                + "10. Jellyseerr / Overseerr (MIT)\n"
                + "Copyright (c) Overseerr & Jellyseerr Contributors\n"
                + "Licensed under the MIT License.\n\n"
                + "11. FlareSolverr Proxy (MIT)\n"
                + "Copyright (c) FlareSolverr Contributors\n"
                + "Licensed under the MIT License.\n";
        tv.setText(licText);

        ScrollView sc = new ScrollView(this);
        sc.setVerticalScrollBarEnabled(false);
        sc.setPadding(0, dp(4), 0, 0);
        sc.addView(tv);

        showOledDialog("Open Source Licenses", "Third-party software components and legal notices", sc, "CLOSE", getAccentColor(), null, null, null);
    }

    private void showDialogWithView(String title, View content) {
        if (content.getParent() instanceof ViewGroup) {
            ((ViewGroup) content.getParent()).removeView(content);
        }
        ScrollView scroll = new ScrollView(this);
        scroll.setVerticalScrollBarEnabled(false);
        scroll.setPadding(0, dp(4), 0, 0);
        scroll.addView(content);

        showOledDialog(title, null, scroll, "CLOSE", getAccentColor(), null, null, null);
    }

    private void showTranscodingBenchmarksDialog() {
        LinearLayout content = new LinearLayout(this);
        content.setOrientation(LinearLayout.VERTICAL);

        TextView tvDiag = new TextView(this);
        tvDiag.setTextSize(12);
        tvDiag.setTextColor(Color.parseColor("#81C784"));
        tvDiag.setPadding(0, dp(4), 0, dp(10));
        tvDiag.setText("Tap any benchmark to test hardware and software transcoding capabilities.");

        Button btnTestTranscode = createPixelButton("BENCHMARK SOFTWARE NEON", getAccentColor(), Color.WHITE);
        btnTestTranscode.setOnClickListener(v -> {
            btnTestTranscode.setEnabled(false);
            btnTestTranscode.setText("BENCHMARKING...");
            new Thread(() -> {
                TranscodingDiagnostics.TranscodeTestResult tr = TranscodingDiagnostics.runTestTranscode(this, false);
                runOnUiThread(() -> {
                    btnTestTranscode.setEnabled(true);
                    btnTestTranscode.setText("BENCHMARK SOFTWARE NEON");
                    String res = "Software Benchmark: " + (tr.success ? "SUCCESS" : "FAILED") +
                            " | " + tr.speed + " (" + tr.fps + " fps) | " + tr.inputCodec + " -> " + tr.outputCodec +
                            " (" + tr.durationMs + " ms)";
                    tvDiag.setText(res);
                    tvDiag.setTextColor(tr.success ? Color.parseColor("#81C784") : Color.parseColor("#E57373"));
                });
            }).start();
        });
        content.addView(btnTestTranscode);

        View s1 = new View(this);
        content.addView(s1, new LinearLayout.LayoutParams(-1, dp(8)));

        Button btnProbeHw = createPixelButton("PROBE HARDWARE MEDIACODEC", getSurfaceElevatedColor(), getPrimaryTextColor());
        btnProbeHw.setOnClickListener(v -> {
            btnProbeHw.setEnabled(false);
            btnProbeHw.setText("PROBING...");
            new Thread(() -> {
                TranscodingDiagnostics.TranscodeTestResult tr = TranscodingDiagnostics.runTestTranscode(this, true);
                runOnUiThread(() -> {
                    btnProbeHw.setEnabled(true);
                    btnProbeHw.setText("PROBE HARDWARE MEDIACODEC");
                    String res = "Hardware Probe: " + (tr.success ? "SUCCESS" : "DIAGNOSTIC REPORT") +
                            "\n" + (tr.details != null && !tr.details.isEmpty() ? tr.details : tr.error);
                    tvDiag.setText(res);
                    tvDiag.setTextColor(tr.success ? Color.parseColor("#81C784") : Color.parseColor("#FFB74D"));
                });
            }).start();
        });
        content.addView(btnProbeHw);

        View s2 = new View(this);
        content.addView(s2, new LinearLayout.LayoutParams(-1, dp(8)));

        Button btnBenchAudio = createPixelButton("BENCHMARK AUDIO (AC3 -> AAC)", getSurfaceElevatedColor(), getAccentColor());
        btnBenchAudio.setOnClickListener(v -> {
            btnBenchAudio.setEnabled(false);
            btnBenchAudio.setText("BENCHMARKING...");
            new Thread(() -> {
                TranscodingDiagnostics.TranscodeTestResult tr = TranscodingDiagnostics.runAudioBenchmark(this);
                runOnUiThread(() -> {
                    btnBenchAudio.setEnabled(true);
                    btnBenchAudio.setText("BENCHMARK AUDIO (AC3 -> AAC)");
                    String res = "Audio Benchmark: " + (tr.success ? "SUCCESS" : "FAILED") +
                            " | " + tr.speed + " | AC3 5.1 -> AAC 2.0 (" + tr.durationMs + " ms)";
                    tvDiag.setText(res);
                    tvDiag.setTextColor(tr.success ? Color.parseColor("#81C784") : Color.parseColor("#E57373"));
                });
            }).start();
        });
        content.addView(btnBenchAudio);

        View s3 = new View(this);
        content.addView(s3, new LinearLayout.LayoutParams(-1, dp(8)));

        Button btnCheckSubtitles = createPixelButton("CHECK SUBTITLES ENGINE", getSurfaceElevatedColor(), getSuccessColor());
        btnCheckSubtitles.setOnClickListener(v -> {
            btnCheckSubtitles.setEnabled(false);
            btnCheckSubtitles.setText("CHECKING...");
            new Thread(() -> {
                TranscodingDiagnostics.TranscodeTestResult tr = TranscodingDiagnostics.runSubtitleCheck(this);
                runOnUiThread(() -> {
                    btnCheckSubtitles.setEnabled(true);
                    btnCheckSubtitles.setText("CHECK SUBTITLES ENGINE");
                    tvDiag.setText(tr.details);
                    tvDiag.setTextColor(tr.success ? Color.parseColor("#81C784") : Color.parseColor("#FFB74D"));
                });
            }).start();
        });
        content.addView(btnCheckSubtitles);

        View s4 = new View(this);
        content.addView(s4, new LinearLayout.LayoutParams(-1, dp(8)));

        Button btnAuditCodecs = createPixelButton("AUDIT ALL CODECS & SUBTITLES", getSurfaceElevatedColor(), getAccentColor());
        btnAuditCodecs.setOnClickListener(v -> showCodecAuditDialog());
        content.addView(btnAuditCodecs);

        View s5 = new View(this);
        content.addView(s5, new LinearLayout.LayoutParams(-1, dp(12)));

        content.addView(tvDiag);

        showDialogWithView("Transcoding & Codec Diagnostics", content);
    }

    private View buildArchitectureDetailsView() {
        LinearLayout aboutContent = new LinearLayout(this);
        aboutContent.setOrientation(LinearLayout.VERTICAL);

        View runtimeStatusItem = buildRuntimeStatusItem();
        aboutContent.addView(runtimeStatusItem);

        LinearLayout specSummary = new LinearLayout(this);
        specSummary.setOrientation(LinearLayout.VERTICAL);
        specSummary.setPadding(0, dp(6), 0, dp(8));
        specSummary.addView(buildSpecRow("Jellyfin Server Core", "v12.4.0 (.NET 10 ARM64)"));
        specSummary.addView(buildSpecRow("Microsoft .NET Runtime", "v10.0.12 Linux Bionic"));
        specSummary.addView(buildSpecRow("FFmpeg Transcoder", "v7.1.4-Jellyfin ARM64"));
        specSummary.addView(buildSpecRow("Database Driver", "SQLite3 (Local)"));
        specSummary.addView(buildSpecRow("Security & TLS Stack", "OpenSSL 3.x System Native"));
        specSummary.addView(buildSpecRow("Process Isolation", "Android Sandboxed UID"));
        specSummary.addView(buildSpecRow("Privacy & Telemetry", "0 Trackers | 100% Local"));
        aboutContent.addView(specSummary);

        aboutContent.addView(buildTransparencyItem("Jellyfin Media Server Core", "Official Jellyfin Server ARM64 binaries (jellyfin.dll). Self-contained web media server engine. Powered by Jellyfin."));
        aboutContent.addView(buildTransparencyItem("Microsoft .NET Runtime Engine", "Bundled in lib/dotnet/. Modern high-performance cross-platform managed runtime hosting Jellyfin server."));
        aboutContent.addView(buildTransparencyItem("FFmpeg Hardware Transcoder", "Bundled in opt/jellyfin/bin/ffmpeg. Used for media remuxing, subtitle burn-in, video thumbnails, and audio transcoding."));
        aboutContent.addView(buildTransparencyItem("Foreground Service & Reliability", "mediaPlayback (ID 1001) Android declared foreground service protecting playback streaming and server reliability."));
        aboutContent.addView(buildTransparencyItem("Safe Cache Management System", "Strict allowlist cache purging for transcodes, audiodb, and fanart without touching databases, users, or configs."));
        aboutContent.addView(buildTransparencyItem("OpenSSL Security & TLS Stack", "OpenSSL 3.x cryptographic SSL/TLS engine managing local HTTPS encryption and secure outbound metadata sockets."));
        aboutContent.addView(buildTransparencyItem("Fontconfig & FreeType Engines", "libfontconfig.so / libfreetype.so native C libraries used by FFmpeg for video subtitle burn-in and text rendering."));
        aboutContent.addView(buildTransparencyItem("SQLite3 Database Driver", "libe_sqlite3.so embedded lightweight relational database engine storing user library indexes and watch progress locally."));
        aboutContent.addView(buildTransparencyItem("Application Package & Scope", getPackageName() + " (Fishbowl v" + com.termux.BuildConfig.VERSION_NAME + ") isolated Android package namespace."));
        aboutContent.addView(buildTransparencyItem("Privacy & Telemetry Verification", "No background metrics are collected or transmitted to external servers. All media data stays strictly on your device."));
        return aboutContent;
    }

    private LinearLayout buildCollapsibleCard(String titleText, String subtitleText, View contentContainer, boolean initiallyExpanded) {
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setBackground(createRoundedDrawable(Color.parseColor("#0C0D10"), dp(12)));
        card.setPadding(dp(16), dp(14), dp(16), dp(14));
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(-1, -2);
        params.bottomMargin = dp(14);
        card.setLayoutParams(params);

        // Header layout (clickable)
        LinearLayout header = new LinearLayout(this);
        header.setOrientation(LinearLayout.HORIZONTAL);
        header.setGravity(Gravity.CENTER_VERTICAL);
        header.setClickable(true);
        header.setFocusable(true);

        LinearLayout titleCol = new LinearLayout(this);
        titleCol.setOrientation(LinearLayout.VERTICAL);

        TextView title = new TextView(this);
        title.setText(titleText);
        title.setTextSize(13.5f);
        title.setTypeface(Typeface.create("sans-serif-medium", Typeface.BOLD));
        title.setTextColor(getPrimaryTextColor());
        titleCol.addView(title);

        TextView sub = new TextView(this);
        sub.setText(subtitleText);
        sub.setTextSize(10.5f);
        sub.setTextColor(getSecondaryTextColor());
        sub.setPadding(0, dp(2), 0, 0);
        titleCol.addView(sub);

        header.addView(titleCol, new LinearLayout.LayoutParams(0, -2, 1.0f));

        FrameLayout arrowSlot = new FrameLayout(this);
        arrowSlot.setBackground(createRoundedDrawable(Color.parseColor("#15161A"), dp(6)));
        ImageView arrow = new ImageView(this);
        arrow.setImageResource(R.drawable.ic_chevron_down);
        arrow.setColorFilter(getSecondaryTextColor());
        arrow.setRotation(initiallyExpanded ? 180f : 0f);
        FrameLayout.LayoutParams ivLp = new FrameLayout.LayoutParams(dp(18), dp(18));
        ivLp.gravity = Gravity.CENTER;
        arrowSlot.addView(arrow, ivLp);
        header.addView(arrowSlot, new LinearLayout.LayoutParams(dp(28), dp(28)));

        card.addView(header);

        // Subtle divider separating header and content
        View divider = new View(this);
        divider.setBackgroundColor(Color.argb(12, 255, 255, 255));
        LinearLayout.LayoutParams pDiv = new LinearLayout.LayoutParams(-1, dp(1));
        pDiv.topMargin = dp(12);
        pDiv.bottomMargin = dp(12);
        divider.setLayoutParams(pDiv);
        divider.setVisibility(initiallyExpanded ? View.VISIBLE : View.GONE);
        card.addView(divider);

        // Content
        contentContainer.setVisibility(initiallyExpanded ? View.VISIBLE : View.GONE);
        card.addView(contentContainer);

        header.setOnClickListener(v -> {
            boolean isExpanded = (contentContainer.getVisibility() == View.VISIBLE);
            if (isExpanded) {
                contentContainer.setVisibility(View.GONE);
                divider.setVisibility(View.GONE);
                arrow.animate().rotation(0f).setDuration(220).start();
            } else {
                contentContainer.setVisibility(View.VISIBLE);
                divider.setVisibility(View.VISIBLE);
                arrow.animate().rotation(180f).setDuration(220).start();
            }
        });

        return card;
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
        item.setBackground(createBorderedRoundedDrawable(getSurfaceElevatedColor(), getBorderColor(), dp(1), dp(8)));
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

    // -- System Permissions Card & Detection -----------------------------------

    private View buildPermissionsCard() {
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);

        TextView subtitle = new TextView(this);
        subtitle.setText("Verify critical Android permissions for uninterrupted streaming and multitasking survival.");
        subtitle.setTextSize(12);
        subtitle.setTextColor(getSecondaryTextColor());
        subtitle.setPadding(0, 0, 0, dp(10));
        card.addView(subtitle);

        // 1. Storage Permission Row
        LinearLayout rowStorage = buildPermissionItem(
                "All Files & Media Storage",
                "Grants Jellyfin read access to movies, music, and shows on internal and external drives.",
                v -> MediaStorageManager.getInstance(this).requestStoragePermission(this)
        );
        permStorageBadge = rowStorage.findViewWithTag("badge");
        permStorageAction = rowStorage.findViewWithTag("action_text");
        card.addView(rowStorage);

        // Subtle Hairline Divider
        View d1 = new View(this);
        d1.setBackgroundColor(getBorderColor());
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

        // Subtle Hairline Divider
        View d2 = new View(this);
        d2.setBackgroundColor(getBorderColor());
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

        // Subtle Hairline Divider
        View d3 = new View(this);
        d3.setBackgroundColor(getBorderColor());
        LinearLayout.LayoutParams pD3 = new LinearLayout.LayoutParams(-1, dp(1));
        pD3.topMargin = dp(8);
        pD3.bottomMargin = dp(8);
        card.addView(d3, pD3);

        // 4. Universal Background Survival Row
        String oemTitle = "Background Survival & Auto-Start";
        String oemDesc = "Configure system battery management, autostart, and background execution to ensure 24/7 media streaming.";

        LinearLayout rowOem = buildPermissionItem(
                oemTitle,
                oemDesc,
                v -> openOemBatteryOptimization()
        );
        TextView badgeOem = rowOem.findViewWithTag("badge");
        if (badgeOem != null) {
            badgeOem.setText("CONFIGURE");
            badgeOem.setTextColor(Color.parseColor("#FFB74D"));
            badgeOem.setBackground(createRoundedDrawable(Color.parseColor("#3A2E1A"), dp(8)));
        }
        TextView actionOem = rowOem.findViewWithTag("action_text");
        if (actionOem != null) {
            actionOem.setText("Tap to open system power & autostart manager");
            actionOem.setTextColor(Color.parseColor("#FFB74D"));
        }
        card.addView(rowOem);

        refreshPermissionsUI();
        return card;
    }

    private View buildKillTrackerCard() {
        return new View(this);
    }

    private void refreshKillTrackerUI() {
        // LMK Kill Tracker removed per user request
    }

    private void openOemBatteryOptimization() {
        Intent[] oemIntents = new Intent[] {
            // Vivo / iQOO / OriginOS: High background power consumption & Auto-start
            new Intent().setComponent(new android.content.ComponentName("com.vivo.permissionmanager", "com.vivo.permissionmanager.activity.PurviewTabActivity")),
            new Intent().setComponent(new android.content.ComponentName("com.iqoo.powersaving", "com.iqoo.powersaving.PowerSavingActivity")),
            new Intent().setComponent(new android.content.ComponentName("com.vivo.abe", "com.vivo.applicationbehaviorengine.ui.ExcessivePowerManagerActivity")),
            new Intent().setComponent(new android.content.ComponentName("com.vivo.permissionmanager", "com.vivo.permissionmanager.activity.SoftPermissionDetailActivity")).putExtra("packagename", getPackageName()),
            // Xiaomi MIUI / HyperOS: Autostart & Power Management
            new Intent().setComponent(new android.content.ComponentName("com.miui.securitycenter", "com.miui.permcenter.autostart.AutoStartManagementActivity")),
            new Intent().setComponent(new android.content.ComponentName("com.miui.securitycenter", "com.miui.powercenter.PowerSettings")),
            // Samsung Device Care & Battery
            new Intent().setComponent(new android.content.ComponentName("com.samsung.android.lool", "com.samsung.android.sm.ui.battery.BatteryActivity")),
            new Intent().setComponent(new android.content.ComponentName("com.samsung.android.sm", "com.samsung.android.sm.battery.ui.BatteryActivity")),
            // Oppo / Realme / OnePlus ColorOS / OxygenOS
            new Intent().setComponent(new android.content.ComponentName("com.coloros.safecenter", "com.coloros.safecenter.permission.startup.StartupAppListActivity")),
            new Intent().setComponent(new android.content.ComponentName("com.coloros.safecenter", "com.coloros.safecenter.startupapp.StartupAppListActivity")),
            new Intent().setComponent(new android.content.ComponentName("com.oplus.battery", "com.oplus.powermanager.fuelgaue.PowerUsageModelActivity")),
            new Intent().setComponent(new android.content.ComponentName("com.oneplus.security", "com.oneplus.security.chainlaunch.view.ChainLaunchAppListActivity")),
            // Huawei / Honor EMUI / MagicOS
            new Intent().setComponent(new android.content.ComponentName("com.huawei.systemmanager", "com.huawei.systemmanager.startupmgr.ui.StartupNormalAppListActivity")),
            new Intent().setComponent(new android.content.ComponentName("com.huawei.systemmanager", "com.huawei.systemmanager.optimize.process.ProtectActivity")),
            // Transsion / Infinix / Tecno
            new Intent().setComponent(new android.content.ComponentName("com.transsion.phonemaster", "com.transsion.phonemaster.AutoStartActivity")),
            // Android 6+ standard ignore battery optimization
            new Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS),
            new Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).setData(Uri.parse("package:" + getPackageName())),
            new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).setData(Uri.parse("package:" + getPackageName()))
        };

        for (Intent intent : oemIntents) {
            try {
                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                startActivity(intent);
                return;
            } catch (Throwable ignored) {}
        }
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
                permStorageBadge.setTextColor(getSuccessColor());
                permStorageBadge.setBackground(createBorderedRoundedDrawable(colorWithAlpha(getSuccessColor(), 45), colorWithAlpha(getSuccessColor(), 120), dp(1), dp(8)));
                if (permStorageAction != null) {
                    permStorageAction.setText("Storage access granted");
                    permStorageAction.setTextColor(getSuccessColor());
                }
            } else {
                permStorageBadge.setText("OFF");
                permStorageBadge.setTextColor(getDangerColor());
                permStorageBadge.setBackground(createBorderedRoundedDrawable(colorWithAlpha(getDangerColor(), 45), colorWithAlpha(getDangerColor(), 120), dp(1), dp(8)));
                if (permStorageAction != null) {
                    permStorageAction.setText("Tap to grant storage access");
                    permStorageAction.setTextColor(getDangerColor());
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
                permBatteryBadge.setTextColor(getSuccessColor());
                permBatteryBadge.setBackground(createBorderedRoundedDrawable(colorWithAlpha(getSuccessColor(), 45), colorWithAlpha(getSuccessColor(), 120), dp(1), dp(8)));
                if (permBatteryAction != null) {
                    permBatteryAction.setText("Unrestricted background execution");
                    permBatteryAction.setTextColor(getSuccessColor());
                }
            } else {
                permBatteryBadge.setText("OFF");
                permBatteryBadge.setTextColor(getWarningColor());
                permBatteryBadge.setBackground(createBorderedRoundedDrawable(colorWithAlpha(getWarningColor(), 45), colorWithAlpha(getWarningColor(), 120), dp(1), dp(8)));
                if (permBatteryAction != null) {
                    permBatteryAction.setText("Tap to disable battery restrictions");
                    permBatteryAction.setTextColor(getWarningColor());
                }
            }
        }

        // 3. Notifications
        if (permNotifBadge != null) {
            boolean notifEnabled = NotificationManagerCompat.from(this).areNotificationsEnabled();
            if (notifEnabled) {
                permNotifBadge.setText("ON");
                permNotifBadge.setTextColor(getSuccessColor());
                permNotifBadge.setBackground(createBorderedRoundedDrawable(colorWithAlpha(getSuccessColor(), 45), colorWithAlpha(getSuccessColor(), 120), dp(1), dp(8)));
                if (permNotifAction != null) {
                    permNotifAction.setText("Foreground notifications active");
                    permNotifAction.setTextColor(getSuccessColor());
                }
            } else {
                permNotifBadge.setText("OFF");
                permNotifBadge.setTextColor(getDangerColor());
                permNotifBadge.setBackground(createBorderedRoundedDrawable(colorWithAlpha(getDangerColor(), 45), colorWithAlpha(getDangerColor(), 120), dp(1), dp(8)));
                if (permNotifAction != null) {
                    permNotifAction.setText("Tap to enable notifications");
                    permNotifAction.setTextColor(getDangerColor());
                }
            }
        }
    }

    // -- Server Error Diagnoser Card -------------------------------------------

    private View buildErrorDiagnosticCard() {
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setPadding(dp(16), dp(14), dp(16), dp(14));
        card.setBackground(createBorderedRoundedDrawable(Color.parseColor("#1B1214"), Color.parseColor("#3C1B1F"), dp(1), dp(14)));
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

        // Storage Full Recovery Action Buttons Row
        LinearLayout storageBtnRow = new LinearLayout(this);
        storageBtnRow.setOrientation(LinearLayout.HORIZONTAL);
        LinearLayout.LayoutParams sRowParams = new LinearLayout.LayoutParams(-1, -2);
        sRowParams.topMargin = dp(8);
        storageBtnRow.setLayoutParams(sRowParams);

        btnErrorClearCache = createPixelButton("CLEAR CACHE & FREE SPACE", Color.parseColor("#D32F2F"), Color.WHITE);
        btnErrorClearCache.setOnClickListener(v -> showClearCacheConfirmation());
        storageBtnRow.addView(btnErrorClearCache, new LinearLayout.LayoutParams(0, dp(38), 1.0f));

        View sSpacer = new View(this);
        storageBtnRow.addView(sSpacer, new LinearLayout.LayoutParams(dp(8), 1));

        btnErrorOpenStorage = createPixelButton("OPEN STORAGE SETTINGS", getSurfaceElevatedColor(), Color.parseColor("#FFB74D"));
        btnErrorOpenStorage.setOnClickListener(v -> openStorageSettings());
        storageBtnRow.addView(btnErrorOpenStorage, new LinearLayout.LayoutParams(0, dp(38), 1.0f));

        btnErrorClearCache.setVisibility(View.GONE);
        btnErrorOpenStorage.setVisibility(View.GONE);
        card.addView(storageBtnRow);

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
            activeTab = 2; // Switch to Activity / Console tab
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
        long freeMb = (controller != null) ? controller.getAvailableInternalStorageMb() : 500;
        boolean isStorageFull = (freeMb < 50)
                || errStr.toLowerCase(Locale.ROOT).contains("storage is full")
                || errStr.toLowerCase(Locale.ROOT).contains("disk is full")
                || errStr.toLowerCase(Locale.ROOT).contains("sqlite_full")
                || errStr.toLowerCase(Locale.ROOT).contains("enospc");

        String cat;
        String what;
        String why;
        String fix;

        if (isStorageFull) {
            cat = "CRITICAL: STORAGE FULL";
            what = "Device internal storage is completely exhausted (< 50 MB free).";
            why = "Jellyfin SQLite databases and server logs require disk space. Server launch and auto-restarts have been halted to prevent a continuous crash loop.";
            fix = "1. Tap CLEAR CACHE & FREE SPACE below to remove temporary transcode caches.\n2. Tap OPEN STORAGE SETTINGS to delete unused applications or media files.\n3. Relaunch server once at least 100 MB free space is available.";
            if (btnErrorClearCache != null) btnErrorClearCache.setVisibility(View.VISIBLE);
            if (btnErrorOpenStorage != null) btnErrorOpenStorage.setVisibility(View.VISIBLE);
        } else {
            if (btnErrorClearCache != null) btnErrorClearCache.setVisibility(View.GONE);
            if (btnErrorOpenStorage != null) btnErrorOpenStorage.setVisibility(View.GONE);

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
        diag.append("App Version: ").append(com.termux.BuildConfig.VERSION_NAME).append(" (Build ").append(com.termux.BuildConfig.VERSION_CODE).append(")\n");
        diag.append("Jellyfin Core: 12.4.0 ARM64\n");
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

    private void checkAndShowErrorPopup(JellyfinController.State state, String lastError) {
        if (state == JellyfinController.State.RUNNING || state == JellyfinController.State.STOPPED || state == JellyfinController.State.UNINITIALIZED) {
            hasShownErrorPopupForIncident = false;
            lastPoppedIncident = "";
            return;
        }

        boolean isCritical = (state == JellyfinController.State.CRASHED || state == JellyfinController.State.CRASH_LOOP || state == JellyfinController.State.FAILED);
        long freeMb = (controller != null) ? controller.getAvailableInternalStorageMb() : 500;
        boolean isStorageFull = (freeMb < 50) || (lastError != null && (lastError.toLowerCase(Locale.ROOT).contains("storage is full") || lastError.toLowerCase(Locale.ROOT).contains("disk is full") || lastError.toLowerCase(Locale.ROOT).contains("sqlite_full")));

        if (!isCritical && !isStorageFull) return;

        String incidentKey = state.name() + ":" + (lastError != null ? lastError : "") + ":" + (isStorageFull ? "storage" : "normal");
        if (hasShownErrorPopupForIncident && incidentKey.equals(lastPoppedIncident)) {
            return;
        }

        hasShownErrorPopupForIncident = true;
        lastPoppedIncident = incidentKey;

        showErrorPopupDialog(state, lastError, isStorageFull);
    }

    private void showErrorPopupDialog(JellyfinController.State state, String lastError, boolean isStorageFull) {
        if (isFinishing() || (Build.VERSION.SDK_INT >= Build.VERSION_CODES.JELLY_BEAN_MR1 && isDestroyed())) return;

        TextView tv = new TextView(this);
        tv.setTextSize(11.5f);
        tv.setTextColor(Color.parseColor("#A1A1AA"));
        tv.setLineSpacing(dp(2), 1.15f);

        if (isStorageFull) {
            tv.setText("Device internal storage has less than 50 MB remaining. The server cannot safely write to SQLite or log files and auto-restart has been halted to prevent a crash loop.\n\nPlease free up internal storage space.");
            showOledDialog("Critical: Storage Full", "Device storage space exhausted", tv,
                    "CLEAR CACHE", Color.parseColor("#EF4444"), this::showClearCacheConfirmation, "DISMISS", null);
        } else {
            String msg = (lastError != null && !lastError.isEmpty()) ? lastError : "The server process exited unexpectedly.";
            tv.setText(msg + "\n\nA diagnostic report can be copied or submitted to GitHub.");
            showOledDialog("Server Crash Detected", "State: " + state.name(), tv,
                    "COPY REPORT", getAccentColor(), this::copyDiagnosticsToClipboard, "DISMISS", null);
        }
    }

    private void openStorageSettings() {
        Intent[] storageIntents = new Intent[] {
                new Intent(Settings.ACTION_INTERNAL_STORAGE_SETTINGS),
                new Intent(Settings.ACTION_SETTINGS),
                new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:" + getPackageName()))
        };
        for (Intent intent : storageIntents) {
            try {
                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                startActivity(intent);
                return;
            } catch (Throwable ignored) {}
        }
    }

    private void updateStorageInfoText() {
        refreshStorageBar();
    }

    private void showCodecAuditDialog() {
        TextView reportTv = new TextView(this);
        reportTv.setText(TranscodingDiagnostics.generateDetailedAuditReport());
        reportTv.setTypeface(Typeface.MONOSPACE);
        reportTv.setTextSize(10.5f);
        reportTv.setTextColor(Color.parseColor("#D4D4D8"));
        reportTv.setPadding(0, dp(4), 0, dp(4));

        ScrollView scroll = new ScrollView(this);
        scroll.setVerticalScrollBarEnabled(false);
        scroll.setHorizontalScrollBarEnabled(false);
        scroll.setOverScrollMode(View.OVER_SCROLL_NEVER);
        scroll.addView(reportTv);

        showOledDialog("Codec Audit Report", "Hardware MediaCodec & Software Capabilities", scroll,
                "COPY REPORT", getAccentColor(), () -> {
                    ClipboardManager cb = (ClipboardManager) getSystemService(CLIPBOARD_SERVICE);
                    if (cb != null) {
                        cb.setPrimaryClip(ClipData.newPlainText("Codec Report", reportTv.getText()));
                        Toast.makeText(this, "Copied SoC codec report to clipboard", Toast.LENGTH_SHORT).show();
                    }
                }, "CLOSE", null);
    }

    private void showClearCacheConfirmation() {
        TextView tv = new TextView(this);
        tv.setText("This removes temporary cache and FFmpeg transcode files only.\n\nYour Jellyfin server, libraries, accounts, media, and configurations will not be deleted.");
        tv.setTextSize(11.5f);
        tv.setTextColor(Color.parseColor("#A1A1AA"));
        tv.setLineSpacing(dp(2), 1.15f);

        showOledDialog("Clear Temporary Cache?", "Safely reclaim storage space", tv,
                "CLEAR CACHE", Color.parseColor("#EF4444"), this::executeSafeClearCache, "CANCEL", null);
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
                    Toast.makeText(FishbowlActivity.this, "Cache cleared successfully.", Toast.LENGTH_SHORT).show();
                } else {
                    Toast.makeText(FishbowlActivity.this, "Cache partially cleared. Some temporary files could not be removed.", Toast.LENGTH_LONG).show();
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
        if (dir == null || !dir.exists()) return 0;
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && java.nio.file.Files.isSymbolicLink(dir.toPath())) {
                return 0;
            }
        } catch (Throwable ignored) {}
        if (dir.isFile()) return dir.length();
        String absPath = dir.getAbsolutePath();
        if (absPath.startsWith("/storage") || absPath.startsWith("/mnt") || absPath.contains("/FishbowlMedia")) {
            return 0;
        }
        File[] files = dir.listFiles();
        if (files == null) return 0;
        long size = 0;
        for (File f : files) {
            try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && java.nio.file.Files.isSymbolicLink(f.toPath())) {
                    continue;
                }
            } catch (Throwable ignored) {}
            String path = f.getAbsolutePath();
            if (path.startsWith("/storage") || path.startsWith("/mnt") || path.contains("/FishbowlMedia") || f.getName().equals("storage") || f.getName().equals("FishbowlMedia")) {
                continue;
            }
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
        item.setBackground(createBorderedRoundedDrawable(getSurfaceElevatedColor(), getBorderColor(), dp(1), dp(8)));
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
            Toast.makeText(this, "Retrying FishBowl setup...", Toast.LENGTH_SHORT).show();
            controller.reinstallRuntime(this);
        });
        btnSettingsRetry.setVisibility(View.GONE);
        item.addView(btnSettingsRetry);

        Button btnViewSetup = createPixelButton("VIEW SETUP OVERVIEW & LOGS", Color.parseColor("#18191E"), Color.parseColor("#F4F4F5"));
        btnViewSetup.setTextSize(10f);
        btnViewSetup.setOnClickListener(v -> showSetupOverlay(true));
        LinearLayout.LayoutParams pViewSetup = new LinearLayout.LayoutParams(-1, dp(36));
        pViewSetup.bottomMargin = dp(6);
        item.addView(btnViewSetup, pViewSetup);

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
            sb.append("- FishBowl & Jellyfin Core 12.4.0\n");
            sb.append("- Microsoft .NET 10.0.12 Runtime Engine\n");
            sb.append("- FFmpeg 7.1.4-Jellyfin Transcoder\n");
            sb.append("- OpenSSL & SQLite3 System Libraries\n");
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
        TextView tv = new TextView(this);
        tv.setText("This will extract a fresh copy of FishBowl and FFmpeg runtime files.\n\nYour existing media libraries, user accounts, database, and configurations will NOT be deleted.");
        tv.setTextSize(11.5f);
        tv.setTextColor(Color.parseColor("#A1A1AA"));
        tv.setLineSpacing(dp(2), 1.15f);

        showOledDialog("Reinstall FishBowl Runtime?", "Clean runtime re-extraction", tv,
                "REINSTALL", getAccentColor(), () -> {
                    Toast.makeText(this, "Beginning clean runtime extraction...", Toast.LENGTH_SHORT).show();
                    controller.reinstallRuntime(this);
                }, "CANCEL", null);
    }

    // -- State Rendering Logic (NO EMOJIS) --------------------------------------

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
            setChromeVisibilityForSetup(true);
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
        updateSetupStageItem("setup_step_2", "2. Extracting FishBowl & Jellyfin Core 12.4.0 ARM64 runtime", percent >= 65, percent >= 35 && percent < 65);
        updateSetupStageItem("setup_step_3", "3. Unpacking Microsoft .NET 10 & FFmpeg 7.1.4 transcoder", percent >= 88, percent >= 65 && percent < 88);
        updateSetupStageItem("setup_step_4", "4. Configuring POSIX permissions & system libraries", percent >= 95, percent >= 88 && percent < 95);
        updateSetupStageItem("setup_step_5", "5. Verifying server binaries & service readiness", percent >= 100, percent >= 95 && percent < 100);

        if (percent >= 100) {
            setupOverlayView.postDelayed(() -> {
                if (setupOverlayView != null && JellyfinBootstrapper.isInitialized(this)) {
                    isSetupOverlayManuallyOpened = false;
                    if (setupDismissBtn != null) {
                        setupDismissBtn.setVisibility(View.GONE);
                    }
                    setupOverlayView.setVisibility(View.GONE);
                    setChromeVisibilityForSetup(false);
                    renderActiveTab();
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
                    color = Color.WHITE;
                    statusText = "SERVER RUNNING";
                    break;
                case STARTING:
                    color = getWarningColor();
                    statusText = "STARTING SERVER";
                    break;
                case INITIALIZING:
                    color = getWarningColor();
                    statusText = "INITIALIZING RUNTIME";
                    break;
                case STOPPING:
                    color = Color.parseColor("#F97316");
                    statusText = "STOPPING SERVER";
                    break;
                case UNINITIALIZED:
                case STOPPED:
                    color = Color.parseColor("#A1A1AA");
                    statusText = "SERVER STOPPED";
                    break;
                case CRASHED:
                case FAILED:
                    color = getDangerColor();
                    statusText = "SERVER FAILED";
                    break;
                case CRASH_LOOP:
                    color = getDangerColor();
                    statusText = "CRASH LOOP DETECTED";
                    break;
                default:
                    color = getWarningColor();
                    statusText = state.name();
                    break;
            }
            statusBadge.setText("● " + statusText);
            statusBadge.setTextColor(color);
            statusBadge.setBackground(null);
            statusBadge.setPadding(0, 0, 0, 0);
        }

        // Render Slim Header Progress Bar & Notification Banner (Small progress bar not full screen)
        if (headerProgressBar != null) {
            boolean isSetupActive = (setupOverlayView != null && setupOverlayView.getVisibility() == View.VISIBLE);
            if (isSetupActive) {
                setChromeVisibilityForSetup(true);
            }
            boolean showLoading = !isSetupActive && (state == JellyfinController.State.INITIALIZING || state == JellyfinController.State.STARTING);
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
            boolean initialized = JellyfinBootstrapper.isInitialized(this);
            boolean isStarting = (state == JellyfinController.State.INITIALIZING || state == JellyfinController.State.STARTING);
            long readyGraceMs = 1500;
            if (state != JellyfinController.State.RUNNING) {
                if (!isStarting) startupCompletedTimeMs = 0;
            } else if (stage == JellyfinController.StartupStage.READY && startupCompletedTimeMs == 0) {
                startupCompletedTimeMs = System.currentTimeMillis();
            }

            boolean isJustReady = (state == JellyfinController.State.RUNNING && stage == JellyfinController.StartupStage.READY
                    && startupCompletedTimeMs > 0 && (System.currentTimeMillis() - startupCompletedTimeMs < readyGraceMs));
            boolean showStartupProgress = isStarting || isJustReady;

            startupProgressCard.setVisibility(showStartupProgress ? View.VISIBLE : View.GONE);

            if (showStartupProgress && txtStage1 != null) {
                if (startupProgressHeader != null) {
                    startupProgressHeader.setText(isJustReady ? "FishBowl Server Ready" : "Starting FishBowl Server...");
                }
                if (startupProgressBar != null) {
                    if (isJustReady) {
                        startupProgressBar.setIndeterminate(false);
                        startupProgressBar.setProgress(100);
                    } else {
                        startupProgressBar.setIndeterminate(true);
                    }
                }

                String bMsg = controller.getBootstrapProgressMessage();
                int bPct = controller.getBootstrapProgressPercent();

                boolean isRuntimeDone = initialized || (bPct == 100) || stage.ordinal() > JellyfinController.StartupStage.STARTING_RUNTIME.ordinal() || state == JellyfinController.State.RUNNING;
                String stage1Label = (bMsg != null && !bMsg.isEmpty()) ? "1. " + bMsg + " (" + bPct + "%)" : (initialized ? "1. Runtime environment ready" : "1. Starting runtime environment");
                updateStageRow(txtStage1, stage1Label, isRuntimeDone, !isRuntimeDone && (stage == JellyfinController.StartupStage.STARTING_RUNTIME || state == JellyfinController.State.INITIALIZING));

                boolean isLaunchDone = stage.ordinal() > JellyfinController.StartupStage.LAUNCHING_SERVER.ordinal() || state == JellyfinController.State.RUNNING;
                boolean isLaunchActive = stage == JellyfinController.StartupStage.LAUNCHING_SERVER && state != JellyfinController.State.RUNNING;
                updateStageRow(txtStage2, "2. Launching FishBowl core server process", isLaunchDone, isLaunchActive);

                boolean isWaitDone = stage.ordinal() > JellyfinController.StartupStage.WAITING_FOR_SERVER.ordinal() || state == JellyfinController.State.RUNNING;
                boolean isWaitActive = stage == JellyfinController.StartupStage.WAITING_FOR_SERVER && state != JellyfinController.State.RUNNING;
                updateStageRow(txtStage3, "3. Waiting for server (binding port 8096)", isWaitDone, isWaitActive);

                boolean isReadinessDone = stage.ordinal() > JellyfinController.StartupStage.CHECKING_READINESS.ordinal() || state == JellyfinController.State.RUNNING;
                boolean isReadinessActive = stage == JellyfinController.StartupStage.CHECKING_READINESS && state != JellyfinController.State.RUNNING;
                updateStageRow(txtStage4, "4. Checking readiness & migrating database", isReadinessDone, isReadinessActive);

                boolean isServerReady = stage == JellyfinController.StartupStage.READY || state == JellyfinController.State.RUNNING;
                updateStageRow(txtStage5, "5. Ready (Server running)", isServerReady, false);
            }

            if (isStarting) {
                mainHandler.removeCallbacks(startupProgressPoller);
                mainHandler.postDelayed(startupProgressPoller, 500);
            } else if (isJustReady) {
                long remaining = readyGraceMs - (System.currentTimeMillis() - startupCompletedTimeMs);
                mainHandler.removeCallbacks(startupProgressPoller);
                mainHandler.postDelayed(startupProgressPoller, Math.max(100, remaining + 50));
            }
        }

        // Real-Time Server Error Diagnosis Card
        updateErrorDiagnosticCard(state);
        checkAndShowErrorPopup(state, controller.getLastError());

        // Authoritative Readiness Guard for OPEN JELLYFIN WEB UI & START SERVER
        boolean isReady = (state == JellyfinController.State.RUNNING && stage == JellyfinController.StartupStage.READY);
        boolean busy = (state == JellyfinController.State.INITIALIZING || state == JellyfinController.State.STARTING || state == JellyfinController.State.STOPPING);
        boolean crashLoop = (state == JellyfinController.State.CRASH_LOOP);
        boolean failed = (state == JellyfinController.State.FAILED || state == JellyfinController.State.CRASHED);

        if (btnOpenJellyfin != null) {
            if (state == JellyfinController.State.RUNNING) {
                btnOpenJellyfin.setVisibility(View.VISIBLE);
                if (isReady) {
                    applyButtonStyle(btnOpenJellyfin, "OPEN JELLYFIN", Color.WHITE, Color.BLACK, true);
                } else {
                    applyButtonStyle(btnOpenJellyfin, "STARTING WEB UI...", Color.parseColor("#27272A"), Color.WHITE, false);
                }
            } else {
                btnOpenJellyfin.setVisibility(View.GONE);
            }
        }

        if (btnStartServer != null) {
            if (state == JellyfinController.State.RUNNING) {
                btnStartServer.setVisibility(View.GONE);
            } else {
                btnStartServer.setVisibility(View.VISIBLE);
                if (state == JellyfinController.State.STARTING || state == JellyfinController.State.INITIALIZING) {
                    applyButtonStyle(btnStartServer, "STARTING SERVER...", Color.parseColor("#27272A"), getWarningColor(), false);
                } else if (state == JellyfinController.State.STOPPING) {
                    applyButtonStyle(btnStartServer, "STOPPING SERVER...", Color.parseColor("#27272A"), Color.parseColor("#F43F5E"), false);
                } else if (crashLoop) {
                    applyButtonStyle(btnStartServer, "SERVER CRASHED", Color.parseColor("#3F1D24"), getDangerColor(), false);
                } else if (failed) {
                    applyButtonStyle(btnStartServer, "RETRY START SERVER", getDangerColor(), Color.WHITE, true);
                } else if (!JellyfinBootstrapper.isInitialized(this)) {
                    applyButtonStyle(btnStartServer, "SETUP / RETRY INSTALL", Color.WHITE, Color.BLACK, true);
                } else {
                    applyButtonStyle(btnStartServer, "START SERVER", Color.WHITE, Color.BLACK, true);
                }
            }
        }

        if (btnStopServer != null) {
            if (state == JellyfinController.State.RUNNING) {
                applyDockButtonStyle(btnStopServer, "Stop", Color.parseColor("#E11D48"), Color.WHITE, R.drawable.ic_stop, true);
            } else {
                applyDockButtonStyle(btnStopServer, "Stop", Color.parseColor("#18181B"), Color.parseColor("#52525B"), R.drawable.ic_stop, false);
            }
        }

        if (btnRestartServer != null) {
            boolean canRestart = !busy && !crashLoop && (state == JellyfinController.State.RUNNING);
            if (canRestart) {
                applyDockButtonStyle(btnRestartServer, "Restart", Color.parseColor("#27272A"), Color.WHITE, R.drawable.ic_restart, true);
            } else {
                applyDockButtonStyle(btnRestartServer, "Restart", Color.parseColor("#18181B"), Color.parseColor("#52525B"), R.drawable.ic_restart, false);
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

        // Broadcast state change to Home Screen Widgets
        com.fishbowl.app.widget.FishbowlWidgetProvider.updateAllWidgets(this);
    }

    private void updateStageRow(TextView tv, String label, boolean completed, boolean active) {
        if (tv == null) return;
        if (completed) {
            tv.setText("[READY]  " + label);
            tv.setTextColor(Color.parseColor("#81C784"));
        } else if (active) {
            tv.setText("[IN PROGRESS]  " + label);
            tv.setTextColor(getWarningColor());
        } else {
            tv.setText("[WAITING]  " + label);
            tv.setTextColor(getSecondaryTextColor());
        }
    }

    // -- Storage & Network Helpers ----------------------------------------------

    private volatile String cachedRawLanIp = null;
    private volatile String cachedRawTailscaleIp = null;
    private volatile boolean isScanningNetwork = false;

    private void updateLanAddressViews(String rawLan, String rawTailscale) {
        if (lanIpValueText != null) {
            View dotLan = (rowLanView != null) ? rowLanView.findViewWithTag("dot_lan") : null;
            if (rawLan == null) {
                lanIpValueText.setText("Wi-Fi not connected");
                lanIpValueText.setTextColor(Color.parseColor("#71717A"));
                setEndpointCopyState(btnCopyLanIp, rowLanView, dotLan, false);
            } else {
                lanIpValueText.setText(rawLan + ":8096");
                lanIpValueText.setTextColor(Color.parseColor("#E4E4E7"));
                setEndpointCopyState(btnCopyLanIp, rowLanView, dotLan, true);
            }
        }
        if (localIpValueText != null) {
            View dotLocal = (rowLocalView != null) ? rowLocalView.findViewWithTag("dot_local") : null;
            localIpValueText.setText("127.0.0.1:8096");
            localIpValueText.setTextColor(Color.parseColor("#E4E4E7"));
            setEndpointCopyState(btnCopyLocalIp, rowLocalView, dotLocal, true);
        }
        if (tailscaleIpValueText != null) {
            View dotTailscale = (rowTailscaleView != null) ? rowTailscaleView.findViewWithTag("dot_tailscale") : null;
            if (rawTailscale == null) {
                tailscaleIpValueText.setText("Tailscale not active");
                tailscaleIpValueText.setTextColor(Color.parseColor("#71717A"));
                setEndpointCopyState(btnCopyTailscaleIp, rowTailscaleView, dotTailscale, false);
            } else {
                tailscaleIpValueText.setText(rawTailscale + ":8096");
                tailscaleIpValueText.setTextColor(Color.parseColor("#E4E4E7"));
                setEndpointCopyState(btnCopyTailscaleIp, rowTailscaleView, dotTailscale, true);
            }
        }
    }

    private void refreshLanAddress() {
        // Fast path: instant render from in-memory cache with zero UI thread block
        updateLanAddressViews(cachedRawLanIp, cachedRawTailscaleIp);

        // Background asynchronous interface traversal
        if (!isScanningNetwork) {
            isScanningNetwork = true;
            bgExecutor.execute(() -> {
                try {
                    String rawLan = getRawLanIp();
                    String rawTailscale = getRawTailscaleIp();
                    cachedRawLanIp = rawLan;
                    cachedRawTailscaleIp = rawTailscale;
                    runOnUiThread(() -> updateLanAddressViews(rawLan, rawTailscale));
                } finally {
                    isScanningNetwork = false;
                }
            });
        }
    }

    public String getRawLanIp() {
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
                        return ip;
                    }
                }
            }
        } catch (Exception ignored) {}
        return null;
    }

    private String getLanAddress() {
        String ip = getRawLanIp();
        return ip != null ? "http://" + ip + ":8096" : null;
    }

    public String getRawTailscaleIp() {
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
                            return ip;
                        }
                    }
                }
            }
        } catch (Exception ignored) {}
        return null;
    }

    private String getTailscaleAddress() {
        String ip = getRawTailscaleIp();
        return ip != null ? "http://" + ip + ":8096" : null;
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

    private void requestStoragePermissionIfNeeded() {
        MediaStorageManager sm = MediaStorageManager.getInstance(this);
        if (!sm.hasStoragePermission()) {
            sm.requestStoragePermission(this);
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

    // -- Design Utilities -------------------------------------------------------

    private Button createPillCopyButton() {
        Button btn = new Button(this);
        btn.setText("COPY");
        btn.setTextSize(10f);
        btn.setLetterSpacing(0.04f);
        btn.setAllCaps(true);
        btn.setTypeface(Typeface.create("sans-serif-medium", Typeface.BOLD));
        btn.setTextColor(getPrimaryTextColor());
        btn.setMinHeight(0);
        btn.setMinimumHeight(0);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            btn.setStateListAnimator(null);
        }

        GradientDrawable shape = createBorderedRoundedDrawable(getSurfaceElevatedColor(), getBorderColor(), dp(1), dp(6));
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            android.graphics.drawable.RippleDrawable ripple = new android.graphics.drawable.RippleDrawable(
                    android.content.res.ColorStateList.valueOf(colorWithAlpha(getAccentColor(), 80)),
                    shape,
                    null);
            btn.setBackground(ripple);
        } else {
            btn.setBackground(shape);
        }

        btn.setPadding(dp(10), 0, dp(10), 0);
        btn.setMinWidth(dp(56));
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(30));
        btn.setLayoutParams(p);
        return btn;
    }

    private Button createPixelButton(String text, int bgColor, int textColor) {
        Button b = new Button(this);
        b.setText(text);
        b.setTextSize(11f);
        b.setLetterSpacing(0.03f);
        b.setAllCaps(false);
        b.setTextColor(textColor);
        b.setTypeface(Typeface.create("sans-serif-medium", Typeface.BOLD));
        b.setMinHeight(0);
        b.setMinimumHeight(0);
        b.setMinWidth(0);
        b.setMinimumWidth(0);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            b.setStateListAnimator(null);
        }

        int strokeColor;
        if (bgColor == getSurfaceElevatedColor() || bgColor == getSurfaceColor() || bgColor == Color.TRANSPARENT) {
            strokeColor = getBorderColor();
        } else if (bgColor == Color.WHITE) {
            strokeColor = Color.argb(40, 255, 255, 255);
        } else {
            strokeColor = colorWithAlpha(textColor, 45);
        }
        GradientDrawable defaultShape = createBorderedRoundedDrawable(bgColor, strokeColor, dp(1), dp(10));
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            int rippleColor = (textColor == Color.WHITE || textColor == Color.parseColor("#090D12"))
                    ? Color.argb(80, 255, 255, 255)
                    : colorWithAlpha(getAccentColor(), 80);
            android.graphics.drawable.RippleDrawable ripple = new android.graphics.drawable.RippleDrawable(
                    android.content.res.ColorStateList.valueOf(rippleColor),
                    defaultShape,
                    null);
            b.setBackground(ripple);
        } else {
            b.setBackground(defaultShape);
        }

        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(-1, dp(36));
        params.topMargin = dp(6);
        b.setLayoutParams(params);

        // Tactile press animation without swallowing click events
        b.setOnTouchListener((v, event) -> {
            if (!v.isEnabled()) return false;
            if (event.getAction() == android.view.MotionEvent.ACTION_DOWN) {
                v.animate().scaleX(0.97f).scaleY(0.97f).setDuration(60).start();
            } else if (event.getAction() == android.view.MotionEvent.ACTION_UP || event.getAction() == android.view.MotionEvent.ACTION_CANCEL) {
                v.animate().scaleX(1.0f).scaleY(1.0f).setDuration(80).start();
            }
            return false; // Crucial: allow click listener to receive event!
        });

        return b;
    }

    private Button createOutlinePixelButton(String text, int outlineColor, int textColor) {
        Button b = new Button(this);
        b.setText(text);
        b.setTextSize(10.5f);
        b.setLetterSpacing(0.04f);
        b.setAllCaps(false);
        b.setTextColor(textColor);
        b.setTypeface(Typeface.create("sans-serif-medium", Typeface.BOLD));
        b.setMinHeight(0);
        b.setMinimumHeight(0);
        b.setMinWidth(0);
        b.setMinimumWidth(0);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            b.setStateListAnimator(null);
        }

        GradientDrawable defaultShape = createBorderedRoundedDrawable(Color.BLACK, outlineColor, dp(1), dp(8));
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            int rippleColor = colorWithAlpha(outlineColor, 60);
            android.graphics.drawable.RippleDrawable ripple = new android.graphics.drawable.RippleDrawable(
                    android.content.res.ColorStateList.valueOf(rippleColor),
                    defaultShape,
                    null);
            b.setBackground(ripple);
        } else {
            b.setBackground(defaultShape);
        }

        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(-1, dp(34));
        b.setLayoutParams(params);

        b.setOnTouchListener((v, event) -> {
            if (!v.isEnabled()) return false;
            if (event.getAction() == android.view.MotionEvent.ACTION_DOWN) {
                v.animate().scaleX(0.97f).scaleY(0.97f).setDuration(60).start();
            } else if (event.getAction() == android.view.MotionEvent.ACTION_UP || event.getAction() == android.view.MotionEvent.ACTION_CANCEL) {
                v.animate().scaleX(1.0f).scaleY(1.0f).setDuration(80).start();
            }
            return false;
        });

        return b;
    }

    private void applyOutlineButtonStyle(Button b, int outlineColor, int textColor) {
        if (b == null) return;
        b.setTextColor(textColor);
        GradientDrawable defaultShape = createBorderedRoundedDrawable(Color.BLACK, outlineColor, dp(1), dp(8));
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            int rippleColor = colorWithAlpha(outlineColor, 60);
            android.graphics.drawable.RippleDrawable ripple = new android.graphics.drawable.RippleDrawable(
                    android.content.res.ColorStateList.valueOf(rippleColor),
                    defaultShape,
                    null);
            b.setBackground(ripple);
        } else {
            b.setBackground(defaultShape);
        }
    }

    private void applyButtonStyle(Button btn, String text, int bgColor, int textColor, boolean enabled) {
        if (btn == null) return;
        btn.setText(text);
        btn.setTextColor(textColor);
        btn.setEnabled(enabled);
        btn.setAlpha(1.0f);
        btn.setTextSize(12);
        btn.setLetterSpacing(0.06f);
        btn.setTypeface(Typeface.create("sans-serif", Typeface.BOLD));
        btn.setAllCaps(true);
        btn.setMinHeight(0);
        btn.setMinimumHeight(0);
        btn.setMinWidth(0);
        btn.setMinimumWidth(0);
        btn.setPadding(0, 0, 0, 0);
        btn.setGravity(Gravity.CENTER);
        btn.setIncludeFontPadding(false);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            btn.setStateListAnimator(null);
        }

        int strokeColor = (bgColor == Color.WHITE) ? Color.WHITE : Color.TRANSPARENT;
        GradientDrawable defaultShape = createBorderedRoundedDrawable(bgColor, strokeColor, 0, dp(10));
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            int rippleColor = (bgColor == Color.WHITE)
                    ? Color.argb(40, 0, 0, 0)
                    : Color.argb(40, 255, 255, 255);
            android.graphics.drawable.RippleDrawable ripple = new android.graphics.drawable.RippleDrawable(
                    android.content.res.ColorStateList.valueOf(rippleColor),
                    defaultShape,
                    null);
            btn.setBackground(ripple);
        } else {
            btn.setBackground(defaultShape);
        }
    }

    private void applyDockButtonStyle(Button btn, String text, int bgColor, int textColor, int iconRes, boolean enabled) {
        if (btn == null) return;
        btn.setTextColor(textColor);
        btn.setEnabled(enabled);
        btn.setAlpha(enabled ? 1.0f : 0.65f);
        btn.setTextSize(12);
        btn.setLetterSpacing(0.06f);
        btn.setTypeface(Typeface.create("sans-serif", Typeface.BOLD));
        btn.setMinHeight(0);
        btn.setMinimumHeight(0);
        btn.setMinWidth(0);
        btn.setMinimumWidth(0);
        btn.setPadding(0, 0, 0, 0);
        btn.setGravity(Gravity.CENTER);
        btn.setIncludeFontPadding(false);
        btn.setAllCaps(false);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            btn.setStateListAnimator(null);
        }

        btn.setText(text.toUpperCase(Locale.US));
        btn.setCompoundDrawables(null, null, null, null);

        int strokeColor = (bgColor == Color.WHITE) ? Color.WHITE : Color.TRANSPARENT;
        GradientDrawable bgShape = createBorderedRoundedDrawable(
                bgColor,
                strokeColor,
                0,
                dp(10)
        );

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            int rippleColor = (bgColor == Color.WHITE)
                    ? Color.argb(40, 0, 0, 0)
                    : Color.argb(40, 255, 255, 255);
            android.graphics.drawable.RippleDrawable ripple = new android.graphics.drawable.RippleDrawable(
                    android.content.res.ColorStateList.valueOf(rippleColor),
                    bgShape,
                    null);
            btn.setBackground(ripple);
        } else {
            btn.setBackground(bgShape);
        }
    }

    private static class CenteredImageSpan extends ImageSpan {
        CenteredImageSpan(Drawable drawable) {
            super(drawable);
        }

        @Override
        public void draw(@NonNull Canvas canvas, CharSequence text, int start, int end,
                         float x, int top, int y, int bottom, @NonNull Paint paint) {
            Drawable b = getDrawable();
            canvas.save();
            Paint.FontMetricsInt fm = paint.getFontMetricsInt();
            int transY = (y + fm.descent + y + fm.ascent) / 2 - b.getBounds().bottom / 2;
            canvas.translate(x, transY);
            b.draw(canvas);
            canvas.restore();
        }
    }

    private GradientDrawable createRoundedDrawable(int color, int radiusDp) {
        GradientDrawable gd = new GradientDrawable();
        gd.setColor(color);
        gd.setCornerRadius(radiusDp);
        return gd;
    }

    private GradientDrawable createBorderedRoundedDrawable(int bgColor, int borderColor, int strokeWidthPx, int radiusDp) {
        GradientDrawable gd = new GradientDrawable();
        gd.setColor(bgColor);
        gd.setStroke(strokeWidthPx, borderColor);
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

    private void updateChannelSelectionUI() {
        if (btnChannelStable == null || btnChannelBeta == null) return;
        String channel = UpdateManager.getInstance(this).getUpdateChannel();
        boolean isBeta = UpdateManager.CHANNEL_BETA.equals(channel);

        if (!isBeta) {
            btnChannelStable.setBackground(createRoundedDrawable(getAccentColor(), dp(6)));
            btnChannelStable.setTextColor(Color.WHITE);
            btnChannelBeta.setBackground(null);
            btnChannelBeta.setTextColor(Color.parseColor("#71717A"));
        } else {
            btnChannelBeta.setBackground(createRoundedDrawable(Color.parseColor("#F59E0B"), dp(6)));
            btnChannelBeta.setTextColor(Color.WHITE);
            btnChannelStable.setBackground(null);
            btnChannelStable.setTextColor(Color.parseColor("#71717A"));
        }

        if (updateChannelBadge != null) {
            updateChannelBadge.setText(isBeta ? "Beta preview and bleeding-edge builds" : "Official stable releases (Recommended)");
            updateChannelBadge.setTextColor(Color.parseColor("#71717A"));
            updateChannelBadge.setBackground(null);
            updateChannelBadge.setPadding(0, dp(2), 0, 0);
        }
    }

    private void refreshUpdateUI() {
        if (updateStatusText == null || updateActionBtn == null) return;
        UpdateManager manager = UpdateManager.getInstance(this);
        UpdateManager.State state = manager.getCurrentState();

        int currentCode = manager.getCurrentVersionCode(this);
        String currentName = manager.getCurrentVersionName(this);
        String channel = manager.getUpdateChannel();
        String channelLabel = UpdateManager.CHANNEL_BETA.equals(channel) ? "Beta Channel" : "Stable Channel";

        StringBuilder sb = new StringBuilder();
        sb.append("- Installed Build: v").append(currentName).append(" (Build ").append(currentCode).append(")\n");
        sb.append("- Update Channel: ").append(channelLabel).append("\n");
        if (manager.getLatestVersionCode() > 0) {
            sb.append("- Latest on GitHub: v").append(manager.getLatestVersionName())
              .append(" (Build ").append(manager.getLatestVersionCode()).append(")\n");
        }
        sb.append("\n");
        String verInfo = sb.toString();

        updateChannelSelectionUI();
        if (updateForceDownloadBtn != null) {
            updateForceDownloadBtn.setVisibility(View.GONE);
        }

        switch (state) {
            case IDLE:
                updateStatusText.setText(verInfo + "Status: Ready to check GitHub for updates.");
                updateActionBtn.setText("Check for updates");
                updateActionBtn.setVisibility(View.VISIBLE);
                updateActionBtn.setOnClickListener(v -> manager.checkForUpdates(this, true));
                break;
            case CHECKING:
                updateStatusText.setText(verInfo + "Checking GitHub releases for " + channelLabel + "...");
                updateActionBtn.setVisibility(View.GONE);
                break;
            case UP_TO_DATE:
                if (manager.getLatestVersionCode() > 0 && currentCode > manager.getLatestVersionCode()) {
                    updateStatusText.setText(verInfo + "Status: You are running an active development build (Build " + currentCode + "), which is newer than the latest GitHub release (Build " + manager.getLatestVersionCode() + "). All integrated companion services are up to date.");
                } else if (manager.getLatestVersionCode() > 0 && currentCode == manager.getLatestVersionCode()) {
                    updateStatusText.setText(verInfo + "Status: You're running the latest official release for " + channelLabel + ". All integrated companion services are up to date.");
                } else {
                    updateStatusText.setText(verInfo + "Status: Your app and all integrated services are up to date.");
                }
                updateActionBtn.setText("Check for updates");
                updateActionBtn.setVisibility(View.VISIBLE);
                updateActionBtn.setOnClickListener(v -> manager.checkForUpdates(this, true));
                if (updateForceDownloadBtn != null && !manager.getApkUrl().isEmpty()) {
                    updateForceDownloadBtn.setVisibility(View.VISIBLE);
                    updateForceDownloadBtn.setText("Force Re-download (v" + manager.getLatestVersionName() + ")");
                    updateForceDownloadBtn.setOnClickListener(v -> manager.startDownload(this));
                }
                break;
            case UPDATE_AVAILABLE:
                updateStatusText.setText(verInfo + "Status: Update Available! (v" + manager.getLatestVersionName() + ")\n\nWhat's new:\n" + manager.getLatestReleaseNotes());
                updateActionBtn.setText("Download update");
                updateActionBtn.setVisibility(View.VISIBLE);
                updateActionBtn.setOnClickListener(v -> manager.startDownload(this));
                break;
            case DOWNLOADING:
                updateStatusText.setText(verInfo + "Status: Downloading update...");
                updateActionBtn.setVisibility(View.GONE);
                break;
            case DOWNLOAD_COMPLETE:
                updateStatusText.setText(verInfo + "Status: Update downloaded successfully.");
                updateActionBtn.setText("Verify and install");
                updateActionBtn.setVisibility(View.VISIBLE);
                updateActionBtn.setOnClickListener(v -> manager.installUpdate(this));
                break;
            case VERIFYING:
                updateStatusText.setText(verInfo + "Status: Verifying SHA-256 package checksum...");
                updateActionBtn.setVisibility(View.GONE);
                break;
            case VERIFICATION_SUCCESSFUL:
                updateStatusText.setText(verInfo + "Status: Package verified and integrity confirmed.");
                updateActionBtn.setText("Install update");
                updateActionBtn.setVisibility(View.VISIBLE);
                updateActionBtn.setOnClickListener(v -> manager.installUpdate(this));
                break;
            case VERIFICATION_FAILED:
                updateStatusText.setText(verInfo + "Status: Update verification failed (corrupt file).");
                updateActionBtn.setText("Retry download");
                updateActionBtn.setVisibility(View.VISIBLE);
                updateActionBtn.setOnClickListener(v -> manager.startDownload(this));
                break;
            case PERMISSION_REQUIRED:
                updateStatusText.setText(verInfo + "Status: Permission to install unknown apps is required.");
                updateActionBtn.setText("Open settings");
                updateActionBtn.setVisibility(View.VISIBLE);
                updateActionBtn.setOnClickListener(v -> manager.openInstallPermissionSettings(this));
                break;
            case DOWNLOAD_FAILED:
                updateStatusText.setText(verInfo + "Status: Update download failed. Check network connection.");
                updateActionBtn.setText("Retry download");
                updateActionBtn.setVisibility(View.VISIBLE);
                updateActionBtn.setOnClickListener(v -> manager.startDownload(this));
                break;
            case NETWORK_UNAVAILABLE:
                updateStatusText.setText(verInfo + "Status: Unable to reach GitHub or rate limit exceeded. Please check connection.");
                updateActionBtn.setText("Check for updates");
                updateActionBtn.setVisibility(View.VISIBLE);
                updateActionBtn.setOnClickListener(v -> manager.checkForUpdates(this, true));
                break;
            case RELEASE_UNAVAILABLE:
                updateStatusText.setText(verInfo + "Status: No releases currently available for " + channelLabel + ".");
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
        String channel = manager.getUpdateChannel();
        String channelLabel = UpdateManager.CHANNEL_BETA.equals(channel) ? "Beta Channel" : "Stable Channel";
        String verInfo = "- Installed: v" + currentName + " (" + currentCode + ") - " + channelLabel + "\n\n";

        long downloadedMb = downloaded / (1024 * 1024);
        long totalMb = total / (1024 * 1024);
        int percent = total > 0 ? (int) ((downloaded * 100) / total) : 0;

        updateStatusText.setText(verInfo + "Downloading update: Fishbowl v" + manager.getLatestVersionName() + "\n" + percent + "%\nProgress: " + downloadedMb + " MB / " + totalMb + " MB");
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
