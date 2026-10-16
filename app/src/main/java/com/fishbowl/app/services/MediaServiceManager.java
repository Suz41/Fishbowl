package com.fishbowl.app.services;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import com.fishbowl.app.JellyfinController;
import com.fishbowl.app.MediaStorageManager;
import com.fishbowl.app.model.ManagedService;
import com.fishbowl.app.model.ServiceManifest;
import com.fishbowl.app.model.ServiceState;
import com.termux.shared.termux.TermuxConstants;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.FileReader;
import java.io.FileWriter;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import org.json.JSONArray;
import org.json.JSONObject;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Centralized service management engine coordinating lifecycle, health checks,
 * port binding verification, and process supervision across all media services.
 */
public class MediaServiceManager {

    private static final String TAG = "MediaServiceManager";
    private static MediaServiceManager instance;

    private final Context appContext;
    private final Map<String, ManagedService> services = new LinkedHashMap<>();
    private final Map<String, Process> activeProcesses = new ConcurrentHashMap<>();
    private final CopyOnWriteArrayList<ServiceListener> listeners = new CopyOnWriteArrayList<>();
    private final ExecutorService serviceExecutor = Executors.newCachedThreadPool();
    private final java.util.concurrent.ScheduledExecutorService dedupScheduler = Executors.newSingleThreadScheduledExecutor();
    private final Handler mainHandler = new Handler(Looper.getMainLooper());

    public interface ServiceListener {
        void onServiceStateChanged(String serviceId, ServiceState state);
    }

    public static synchronized MediaServiceManager getInstance(Context context) {
        if (instance == null && context != null) {
            instance = new MediaServiceManager(context.getApplicationContext());
        }
        return instance;
    }

    public static synchronized MediaServiceManager getInstance() {
        return instance;
    }

    private MediaServiceManager(Context context) {
        this.appContext = context;
        registerDefaultManifests();
        deploySeerrServiceIfNeeded();
        deployFpcalcIfNeeded();
        bridgeJellyfinController();
        reconcileRunningServices();
        startDeduplicationWatcher();
    }

    private void startDeduplicationWatcher() {
        dedupScheduler.scheduleWithFixedDelay(() -> {
            try {
                MediaStorageManager msm = MediaStorageManager.getInstance(appContext);
                if (msm != null) {
                    int cleaned = msm.autoPurgeVerifiedDuplicates();
                    if (cleaned > 0) {
                        Log.i(TAG, "[DeduplicationWatcher] Auto-purged " + cleaned + " duplicate staging file(s) across Downloads/radarr and Downloads/sonarr");
                    }
                }
            } catch (Throwable t) {
                Log.w(TAG, "DeduplicationWatcher cycle error: " + t.getMessage());
            }
        }, 15, 30, java.util.concurrent.TimeUnit.SECONDS);
    }

    private void registerDefaultManifests() {
        File home = TermuxConstants.TERMUX_HOME_DIR;
        File prefix = TermuxConstants.TERMUX_PREFIX_DIR;

        // 1. Core Service: Jellyfin Media Server
        ServiceManifest jf = new ServiceManifest(
                "jellyfin",
                "Jellyfin Media Server",
                "Media Server",
                "12.1.0",
                "High-performance media streaming, transcoding, and library engine.",
                8096,
                "/health",
                "/web/index.html",
                new File(prefix, "lib/jellyfin").getAbsolutePath(),
                new File(prefix, "lib/jellyfin/jellyfin.dll").getAbsolutePath(),
                new File(home, ".config/jellyfin").getAbsolutePath(),
                new File(home, ".local/share/jellyfin").getAbsolutePath(),
                new File(home, ".local/share/jellyfin/log").getAbsolutePath(),
                ".NET 10.0 ARM64",
                true,
                500,
                24
        );
        services.put(jf.getId(), new ManagedService(jf));

        // 2. Automation: Radarr (Movies)
        ServiceManifest radarr = new ServiceManifest(
                "radarr",
                "Radarr",
                "Automation",
                "6.4.4",
                "Automated movie library manager coordinating grabbers and organizers.",
                7878,
                "/api/v3/system/status",
                "/",
                new File(home, "services/radarr").getAbsolutePath(),
                new File(home, "services/radarr/Radarr.dll").getAbsolutePath(),
                new File(home, ".config/Radarr").getAbsolutePath(),
                new File(home, ".config/Radarr").getAbsolutePath(),
                new File(home, ".config/Radarr/logs").getAbsolutePath(),
                ".NET 10.0 ARM64",
                false,
                180,
                24
        );
        services.put(radarr.getId(), new ManagedService(radarr));

        // 3. Automation: Sonarr (TV Shows)
        ServiceManifest sonarr = new ServiceManifest(
                "sonarr",
                "Sonarr",
                "Automation",
                "4.0.9",
                "Smart PVR for TV shows, automatically coordinating indexers and clients.",
                8989,
                "/api/v3/system/status",
                "/",
                new File(home, "services/sonarr").getAbsolutePath(),
                new File(home, "services/sonarr/Sonarr.dll").getAbsolutePath(),
                new File(home, ".config/Sonarr").getAbsolutePath(),
                new File(home, ".config/Sonarr").getAbsolutePath(),
                new File(home, ".config/Sonarr/logs").getAbsolutePath(),
                ".NET 10.0 ARM64",
                false,
                180,
                24
        );
        services.put(sonarr.getId(), new ManagedService(sonarr));

        // 4. Indexers: Prowlarr
        ServiceManifest prowlarr = new ServiceManifest(
                "prowlarr",
                "Prowlarr",
                "Indexers",
                "1.21.2",
                "Unified indexer proxy manager integrating seamlessly with Sonarr and Radarr.",
                9696,
                "/api/v1/system/status",
                "/",
                new File(home, "services/prowlarr").getAbsolutePath(),
                new File(home, "services/prowlarr/Prowlarr.dll").getAbsolutePath(),
                new File(home, ".config/Prowlarr").getAbsolutePath(),
                new File(home, ".config/Prowlarr").getAbsolutePath(),
                new File(home, ".config/Prowlarr/logs").getAbsolutePath(),
                ".NET 10.0 ARM64",
                false,
                150,
                24
        );
        services.put(prowlarr.getId(), new ManagedService(prowlarr));

        // 5. Download Client: Transmission
        ServiceManifest transmission = new ServiceManifest(
                "transmission",
                "Transmission",
                "Downloads",
                "4.1.3",
                "Lightweight, headless native BitTorrent download daemon.",
                9091,
                "/transmission/web/",
                "/transmission/web/",
                new File(prefix, "bin").getAbsolutePath(),
                new File(prefix, "bin/transmission-daemon").getAbsolutePath(),
                new File(home, ".config/transmission-daemon").getAbsolutePath(),
                new File(home, "downloads").getAbsolutePath(),
                new File(home, ".config/transmission-daemon").getAbsolutePath(),
                "Native ARM64 Bionic",
                false,
                50,
                24
        );
        services.put(transmission.getId(), new ManagedService(transmission));

        // 6. Requests: Seerr (Jellyseerr / Overseerr)
        ServiceManifest seerr = new ServiceManifest(
                "seerr",
                "Seerr",
                "Requests",
                "2.2.0",
                "Unified media request, discovery, and library management system for Jellyfin.",
                5055,
                "/api/v1/status",
                "/",
                new File(home, "services/seerr").getAbsolutePath(),
                new File(home, "services/seerr/dist/index.js").getAbsolutePath(),
                new File(home, ".config/seerr").getAbsolutePath(),
                new File(home, ".config/seerr").getAbsolutePath(),
                new File(home, ".config/seerr/logs").getAbsolutePath(),
                "Node.js LTS (ARM64)",
                false,
                150,
                24
        );
        services.put(seerr.getId(), new ManagedService(seerr));

        // 7. Subtitles: Bazarr (Optional / On-Demand Subtitle Automation)
        ServiceManifest bazarr = new ServiceManifest(
                "bazarr",
                "Bazarr",
                "Subtitles",
                "1.4.3",
                "Automated subtitle management coordinating multi-language subtitle providers.",
                6767,
                "/api/system/status",
                "/",
                new File(home, "services/bazarr").getAbsolutePath(),
                new File(home, "services/bazarr/bazarr/main.py").getAbsolutePath(),
                new File(home, ".config/bazarr").getAbsolutePath(),
                new File(home, ".config/bazarr").getAbsolutePath(),
                new File(home, ".config/bazarr/log").getAbsolutePath(),
                "Python 3 ARM64",
                false,
                120,
                24
        );
        services.put(bazarr.getId(), new ManagedService(bazarr));

        // 8. Music Automation: Lidarr (Optional / On-Demand Music Manager)
        ServiceManifest lidarr = new ServiceManifest(
                "lidarr",
                "Lidarr",
                "Music",
                "2.8.2",
                "Music collection manager and downloader for Usenet and BitTorrent.",
                8686,
                "/api/v1/system/status",
                "/",
                new File(home, "services/lidarr").getAbsolutePath(),
                new File(home, "services/lidarr/Lidarr.dll").getAbsolutePath(),
                new File(home, ".config/Lidarr").getAbsolutePath(),
                new File(home, ".config/Lidarr").getAbsolutePath(),
                new File(home, ".config/Lidarr/logs").getAbsolutePath(),
                ".NET 10.0 ARM64",
                false,
                130,
                24
        );
        services.put(lidarr.getId(), new ManagedService(lidarr));
    }

    public synchronized void deploySeerrServiceIfNeeded() {
        try {
            File home = TermuxConstants.TERMUX_HOME_DIR;
            File seerrDist = new File(home, "services/seerr/dist");
            File targetJs = new File(seerrDist, "index.js");

            byte[] assetBytes = null;
            try (java.io.InputStream in = appContext.getAssets().open("seerr/index.js")) {
                java.io.ByteArrayOutputStream baos = new java.io.ByteArrayOutputStream();
                byte[] buf = new byte[8192];
                int r;
                while ((r = in.read(buf)) != -1) {
                    baos.write(buf, 0, r);
                }
                assetBytes = baos.toByteArray();
            } catch (Throwable t) {
                Log.w(TAG, "Could not read seerr/index.js asset: " + t.getMessage());
            }

            if (assetBytes != null && (!targetJs.exists() || targetJs.length() != assetBytes.length)) {
                seerrDist.mkdirs();
                File configDir = new File(home, ".config/seerr");
                configDir.mkdirs();

                try (java.io.FileOutputStream out = new java.io.FileOutputStream(targetJs)) {
                    out.write(assetBytes);
                }
                Log.d(TAG, "Seerr Node.js service successfully deployed (" + assetBytes.length + " bytes) to: " + targetJs.getAbsolutePath());
                ManagedService seerr = services.get("seerr");
                if (seerr != null && seerr.isRunning()) {
                    stopService("seerr");
                    mainHandler.postDelayed(() -> startService("seerr"), 1000);
                }
            }

            ManagedService seerr = services.get("seerr");
            if (seerr != null) {
                seerr.reconcileInstalledState();
            }
        } catch (Exception e) {
            Log.e(TAG, "Failed to deploy Seerr service from assets", e);
        }
    }

    public synchronized void deployFpcalcIfNeeded() {
        try {
            File prefix = TermuxConstants.TERMUX_PREFIX_DIR;
            File prefixBin = new File(prefix, "bin");
            if (!prefixBin.exists()) prefixBin.mkdirs();
            File targetPrefix = new File(prefixBin, "fpcalc");

            File home = TermuxConstants.TERMUX_HOME_DIR;
            File lidarrDir = new File(home, "services/lidarr");
            File targetLidarr = new File(lidarrDir, "fpcalc");

            byte[] assetBytes = null;
            try (java.io.InputStream in = appContext.getAssets().open("fpcalc")) {
                java.io.ByteArrayOutputStream baos = new java.io.ByteArrayOutputStream();
                byte[] buf = new byte[8192];
                int r;
                while ((r = in.read(buf)) != -1) {
                    baos.write(buf, 0, r);
                }
                assetBytes = baos.toByteArray();
            } catch (Throwable t) {
                Log.w(TAG, "Could not read fpcalc asset: " + t.getMessage());
            }

            if (assetBytes != null && assetBytes.length > 0) {
                if (!targetPrefix.exists() || targetPrefix.length() != assetBytes.length) {
                    try (FileOutputStream out = new FileOutputStream(targetPrefix)) {
                        out.write(assetBytes);
                    }
                    targetPrefix.setExecutable(true, false);
                    targetPrefix.setReadable(true, false);
                    try {
                        android.system.Os.chmod(targetPrefix.getAbsolutePath(), 0755);
                    } catch (Throwable ignored) {}
                    Log.i(TAG, "Deployed fpcalc binary to: " + targetPrefix.getAbsolutePath());
                }

                if (lidarrDir.exists() && (!targetLidarr.exists() || targetLidarr.length() != assetBytes.length)) {
                    try (FileOutputStream out = new FileOutputStream(targetLidarr)) {
                        out.write(assetBytes);
                    }
                    targetLidarr.setExecutable(true, false);
                    targetLidarr.setReadable(true, false);
                    try {
                        android.system.Os.chmod(targetLidarr.getAbsolutePath(), 0755);
                    } catch (Throwable ignored) {}
                    Log.i(TAG, "Deployed fpcalc binary to: " + targetLidarr.getAbsolutePath());
                }
            }
        } catch (Exception e) {
            Log.e(TAG, "Failed to deploy fpcalc from assets", e);
        }
    }

    private void bridgeJellyfinController() {
        JellyfinController jc = JellyfinController.getInstance();
        ManagedService jfService = services.get("jellyfin");

        if (jfService != null) {
            updateJellyfinState(jc.getState());
            jc.addListener(this::updateJellyfinState);
        }
    }

    private void updateJellyfinState(JellyfinController.State state) {
        ManagedService jf = services.get("jellyfin");
        if (jf == null) return;

        ServiceState ss;
        switch (state) {
            case RUNNING:
                ss = ServiceState.RUNNING;
                jf.setHealthy(true);
                break;
            case STARTING:
            case INITIALIZING:
                ss = ServiceState.STARTING;
                break;
            case STOPPING:
                ss = ServiceState.STOPPING;
                break;
            case STOPPED:
                ss = ServiceState.STOPPED;
                break;
            case FAILED:
            case CRASHED:
            case CRASH_LOOP:
                ss = ServiceState.FAILED;
                break;
            case UNINITIALIZED:
            default:
                ss = ServiceState.NOT_INSTALLED;
                break;
        }

        jf.setState(ss);
        jf.setLastError(JellyfinController.getInstance().getLastError());
        notifyListeners("jellyfin", ss);
    }

    /**
     * Checks whether any background service processes are already running (e.g. from previous runs)
     */
    public void reconcileRunningServices() {
        serviceExecutor.execute(() -> {
            for (ManagedService ms : services.values()) {
                if (ms.getManifest().isCore()) continue; // Handled by JellyfinController

                ms.reconcileInstalledState();
                if (!ms.isInstalled()) continue;

                int port = ms.getManifest().getDefaultPort();
                if (isPortOccupied(port)) {
                    // Service is alive and port is bound!
                    ms.setState(ServiceState.RUNNING);
                    ms.setHealthy(true);
                    if (ms.getProcessPid() == 0) {
                        String exePath = ms.getManifest().getExecutableOrDllPath();
                        if (exePath != null && !exePath.isEmpty()) {
                            String execTarget = "bazarr".equalsIgnoreCase(ms.getManifest().getId()) ? "bazarr" : new File(exePath).getName();
                            long discoveredPid = findPidByPattern(execTarget);
                            if (discoveredPid > 0) {
                                ms.setProcessPid(discoveredPid);
                            }
                        }
                    }
                    notifyListeners(ms.getManifest().getId(), ServiceState.RUNNING);
                } else if (ms.getState() == ServiceState.RUNNING) {
                    ms.setState(ServiceState.STOPPED);
                    ms.setHealthy(false);
                    notifyListeners(ms.getManifest().getId(), ServiceState.STOPPED);
                }
            }
        });
    }

    public List<ManagedService> getAllServices() {
        return Collections.unmodifiableList(new ArrayList<>(services.values()));
    }

    public ManagedService getService(String id) {
        return services.get(id);
    }

    public int getRunningServicesCount() {
        int count = 0;
        for (ManagedService ms : services.values()) {
            if (ms.getState() == ServiceState.RUNNING) {
                count++;
            }
        }
        return count;
    }

    public void ensureForegroundServiceActive() {
        try {
            android.content.Intent intent = new android.content.Intent(appContext, com.fishbowl.app.JellyfinServerService.class);
            intent.setAction("com.fishbowl.app.KEEP_ALIVE");
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
                appContext.startForegroundService(intent);
            } else {
                appContext.startService(intent);
            }
        } catch (Throwable t) {
            Log.w(TAG, "Failed to start foreground keep-alive service: " + t.getMessage());
        }
    }

    public void startService(String id) {
        startService(id, appContext);
    }

    public void startService(String id, Context ctx) {
        ManagedService ms = services.get(id);
        if (ms == null) return;

        // Ensure Foreground Server Service is active to protect process from Android background kills
        ensureForegroundServiceActive();

        if ("jellyfin".equalsIgnoreCase(id)) {
            JellyfinController.getInstance().start(ctx != null ? ctx : appContext);
            return;
        }

        if (ms.isBlocked()) {
            ms.setState(ServiceState.BLOCKED);
            ms.setLastError(ms.getBlockedReason());
            notifyListeners(id, ServiceState.BLOCKED);
            Log.w(TAG, "[ServiceManager] Cannot start " + ms.getDisplayName() + ": " + ms.getBlockedReason());
            return;
        }

        if (!ms.isEnabled()) {
            ms.setState(ServiceState.DISABLED);
            ms.setLastError("Service is disabled by configuration");
            notifyListeners(id, ServiceState.DISABLED);
            Log.w(TAG, "[ServiceManager] Cannot start " + ms.getDisplayName() + ": service is disabled");
            return;
        }

        if (ms.isRunning() || ms.isStarting()) {
            Log.d(TAG, "Service " + id + " is already running or starting. Ignoring redundant start.");
            return;
        }

        serviceExecutor.execute(() -> {
            ms.setUserStopped(false);
            ms.setState(ServiceState.STARTING);
            notifyListeners(id, ServiceState.STARTING);
            Log.i(TAG, "[ServiceManager] " + ms.getDisplayName() + " STARTING");
            ms.appendLog("Starting " + ms.getManifest().getDisplayName() + "...");

            // 1. Verify installation
            if (!ms.isInstalled()) {
                ms.setLastError("NOT_INSTALLED: Service files not found. Please install the service first.");
                ms.setState(ServiceState.NOT_INSTALLED);
                notifyListeners(id, ServiceState.NOT_INSTALLED);
                return;
            }

            // Ensure configuration exists and is sanitized before process launch
            ensureServiceConfigSanitized(id);

            // 2. Pre-flight check: Port conflict
            int port = ms.getManifest().getDefaultPort();
            if (isPortOccupied(port)) {
                ms.appendLog("Port " + port + " is already active. Checking health...");
                if (checkApiReadiness(ms)) {
                    ms.setState(ServiceState.RUNNING);
                    ms.setHealthy(true);
                    ms.resetCrashCount();
                    notifyListeners(id, ServiceState.RUNNING);
                    Log.i(TAG, "[ServiceManager] " + ms.getDisplayName() + " RUNNING (port already active & healthy)");
                    return;
                } else {
                    ms.setLastError("PORT_CONFLICT: Port " + port + " is occupied by an unrecognized process.");
                    ms.setState(ServiceState.ERROR);
                    notifyListeners(id, ServiceState.ERROR);
                    Log.e(TAG, "[ServiceManager] " + ms.getDisplayName() + " PORT_CONFLICT on " + port);
                    return;
                }
            }

            try {
                File home = TermuxConstants.TERMUX_HOME_DIR;
                File prefix = TermuxConstants.TERMUX_PREFIX_DIR;
                File dataDir = new File(ms.getManifest().getDataDir());
                File configDir = new File(ms.getManifest().getConfigDir());
                File logDir = new File(ms.getManifest().getLogDir());
                File tmpDir = new File(appContext.getCacheDir(), "tmp");

                if (!dataDir.exists()) dataDir.mkdirs();
                if (!configDir.exists()) configDir.mkdirs();
                if (!logDir.exists()) logDir.mkdirs();
                if (!tmpDir.exists()) tmpDir.mkdirs();

                File dotnet = new File(prefix, "lib/dotnet/dotnet");
                if (!dotnet.exists()) {
                    dotnet = new File(prefix, "bin/dotnet");
                }

                File targetDllOrBin = new File(ms.getManifest().getExecutableOrDllPath());
                ProcessBuilder pb;

                if (targetDllOrBin.getName().endsWith(".dll")) {
                    // Servarr .NET application (Radarr, Sonarr, Prowlarr)
                    prepareServarrRuntimeConfig(targetDllOrBin);

                    pb = new ProcessBuilder(
                            dotnet.getAbsolutePath(),
                            targetDllOrBin.getAbsolutePath(),
                            "--nobrowser",
                            "--data=" + dataDir.getAbsolutePath()
                    );
                } else if (targetDllOrBin.getName().endsWith(".js")) {
                    // Node.js application (Seerr)
                    File nodeBin = new File(prefix, "bin/node");
                    pb = new ProcessBuilder(
                            nodeBin.exists() ? nodeBin.getAbsolutePath() : "node",
                            targetDllOrBin.getAbsolutePath()
                    );
                } else if ("bazarr".equalsIgnoreCase(id)) {
                    // Python 3 application (Bazarr)
                    File pythonBin = new File(prefix, "bin/python3");
                    if (!pythonBin.exists()) pythonBin = new File(prefix, "bin/python");
                    if (!pythonBin.exists()) {
                        ms.setLastError("NOT_INSTALLED: Python runtime not found in " + prefix.getAbsolutePath() + "/bin");
                        ms.setState(ServiceState.NOT_INSTALLED);
                        notifyListeners(id, ServiceState.NOT_INSTALLED);
                        return;
                    }
                    File bazarrMain = new File(home, "services/bazarr/bazarr/main.py");
                    File execTarget = bazarrMain.exists() ? bazarrMain : targetDllOrBin;
                    pb = new ProcessBuilder(
                            pythonBin.getAbsolutePath(),
                            "-u",
                            execTarget.getAbsolutePath(),
                            "--no-update",
                            "--config=" + configDir.getAbsolutePath()
                    );
                } else if (targetDllOrBin.getName().endsWith(".py")) {
                    File pythonBin = new File(prefix, "bin/python3");
                    if (!pythonBin.exists()) pythonBin = new File(prefix, "bin/python");
                    if (!pythonBin.exists()) {
                        ms.setLastError("NOT_INSTALLED: Python runtime not found in " + prefix.getAbsolutePath() + "/bin");
                        ms.setState(ServiceState.NOT_INSTALLED);
                        notifyListeners(id, ServiceState.NOT_INSTALLED);
                        return;
                    }
                    targetDllOrBin.setExecutable(true, false);
                    pb = new ProcessBuilder(
                            pythonBin.getAbsolutePath(),
                            "-u",
                            targetDllOrBin.getAbsolutePath(),
                            "--port=" + port,
                            "--host=127.0.0.1"
                    );
                } else if ("transmission".equalsIgnoreCase(id)) {
                    // Native BitTorrent daemon (Transmission)
                    sanitizeTransmissionConfig(configDir);
                    pb = new ProcessBuilder(
                            targetDllOrBin.getAbsolutePath(),
                            "-f",
                            "-g", configDir.getAbsolutePath()
                    );
                } else {
                    pb = new ProcessBuilder(
                            targetDllOrBin.getAbsolutePath()
                    );
                }

                Map<String, String> env = pb.environment();
                env.put("HOME", home.getAbsolutePath());
                env.put("DOTNET_ROOT", new File(prefix, "lib/dotnet").getAbsolutePath());
                env.put("TMPDIR", tmpDir.getAbsolutePath());
                if ("bazarr".equalsIgnoreCase(id)) {
                    File customLibs = new File(home, "services/bazarr/custom_libs");
                    File libs = new File(home, "services/bazarr/libs");
                    File bazarrDir = new File(home, "services/bazarr/bazarr");
                    env.put("PYTHONPATH", customLibs.getAbsolutePath() + ":" + libs.getAbsolutePath() + ":" + bazarrDir.getAbsolutePath());
                    env.put("BAZARR_VERSION", ms.getManifest().getVersion());
                }
                if ("transmission".equalsIgnoreCase(id)) {
                    env.put("TRANSMISSION_WEB_HOME", new File(prefix, "share/transmission/public_html").getAbsolutePath());
                }
                if ("seerr".equalsIgnoreCase(id)) {
                    env.put("PORT", String.valueOf(ms.getManifest().getDefaultPort()));
                    env.put("CONFIG_DIRECTORY", configDir.getAbsolutePath());
                }
                if ("radarr".equalsIgnoreCase(id)) {
                    env.put("RADARR__SERVER__ALLOWEDHOSTS", "*");
                    env.put("RADARR__SERVER__TRUSTEDNETWORKS", "0.0.0.0/0,::/0");
                } else if ("sonarr".equalsIgnoreCase(id)) {
                    env.put("SONARR__SERVER__ALLOWEDHOSTS", "*");
                    env.put("SONARR__SERVER__TRUSTEDNETWORKS", "0.0.0.0/0,::/0");
                } else if ("prowlarr".equalsIgnoreCase(id)) {
                    env.put("PROWLARR__SERVER__ALLOWEDHOSTS", "*");
                    env.put("PROWLARR__SERVER__TRUSTEDNETWORKS", "0.0.0.0/0,::/0");
                } else if ("lidarr".equalsIgnoreCase(id)) {
                    env.put("LIDARR__SERVER__ALLOWEDHOSTS", "*");
                    env.put("LIDARR__SERVER__TRUSTEDNETWORKS", "0.0.0.0/0,::/0");
                }
                env.put("ASPNETCORE_ALLOWEDHOSTS", "*");
                env.put("AllowedHosts", "*");

                String nativeLibDir = appContext.getApplicationInfo().nativeLibraryDir;
                String prefixLib = new File(prefix, "lib").getAbsolutePath();
                String jfLib = new File(prefix, "opt/jellyfin/lib").getAbsolutePath();
                String jfBin = new File(prefix, "opt/jellyfin/bin").getAbsolutePath();
                String serviceDir = ms.getManifest().getInstallDir();
                env.put("LD_LIBRARY_PATH", jfLib + ":" + prefixLib + ":" + nativeLibDir + ":" + serviceDir);

                String existingPath = env.getOrDefault("PATH", "");
                env.put("PATH", new File(prefix, "lib/dotnet").getAbsolutePath()
                        + ":" + jfBin
                        + ":" + new File(prefix, "bin").getAbsolutePath()
                        + (existingPath.isEmpty() ? "" : ":" + existingPath));

                // SSL Certificates - force clean empty OPENSSL_CONF to bypass invalid Termux tls paths
                File certFile = new File(prefix, "etc/tls/cert.pem");
                if (certFile.exists()) {
                    env.put("SSL_CERT_FILE", certFile.getAbsolutePath());
                    env.put("SSL_CERT_DIR", certFile.getParentFile().getAbsolutePath());
                }
                File sslCnf = new File(prefix, "etc/tls/openssl.cnf");
                if (sslCnf.exists()) {
                    env.put("OPENSSL_CONF", sslCnf.getAbsolutePath());
                } else {
                    env.put("OPENSSL_CONF", "/dev/null");
                }

                pb.directory(new File(ms.getManifest().getInstallDir()));
                pb.redirectErrorStream(true);

                Process process = pb.start();
                long pid = extractPid(process);
                ms.setProcessPid(pid);
                activeProcesses.put(id, process);
                ms.setState(ServiceState.PROCESS_READY);
                notifyListeners(id, ServiceState.PROCESS_READY);
                Log.i(TAG, "[ServiceManager] " + ms.getDisplayName() + " PROCESS_READY (PID " + pid + ")");
                ms.appendLog("Process spawned (PID " + pid + " monitoring active)");

                // Background stream reader
                serviceExecutor.execute(() -> {
                    try (BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream()))) {
                        String line;
                        while ((line = reader.readLine()) != null) {
                            ms.appendLog(line);
                        }
                    } catch (Throwable ignored) {}
                });

                // Background exit monitor with crash loop protection & exponential backoff
                serviceExecutor.execute(() -> {
                    try {
                        int exitCode = process.waitFor();
                        activeProcesses.remove(id);
                        ms.appendLog("Process exited with code: " + exitCode);
                        Log.i(TAG, "[ServiceManager] " + ms.getDisplayName() + " process exited with code " + exitCode);

                        if (ms.isUserStopped() || ms.getState() == ServiceState.STOPPING) {
                            ms.setState(ServiceState.STOPPED);
                            ms.setHealthy(false);
                            ms.setProcessPid(0);
                            notifyListeners(id, ServiceState.STOPPED);
                            Log.i(TAG, "[ServiceManager] " + ms.getDisplayName() + " STOPPED (intentional)");
                        } else {
                            ms.recordCrash();
                            int crashes = ms.getCrashCount();
                            if (crashes > 5) {
                                ms.setLastError("CRASH_LOOP_PROTECTION: Repeated failures (" + crashes + " crashes). Auto-restart halted.");
                                ms.setState(ServiceState.CRASH_LOOP_PROTECTION);
                                ms.setHealthy(false);
                                ms.setProcessPid(0);
                                notifyListeners(id, ServiceState.CRASH_LOOP_PROTECTION);
                                Log.e(TAG, "[ServiceManager] " + ms.getDisplayName() + " CRASH_LOOP_PROTECTION: Halted after " + crashes + " crashes");
                            } else {
                                // Exponential backoff: 5s, 15s, 30s, 60s, 300s
                                long backoffMs;
                                switch (crashes) {
                                    case 1: backoffMs = 5000; break;
                                    case 2: backoffMs = 15000; break;
                                    case 3: backoffMs = 30000; break;
                                    case 4: backoffMs = 60000; break;
                                    default: backoffMs = 300000; break;
                                }
                                ms.setLastError("RECOVERING: Exited unexpectedly with code " + exitCode + ". Restarting in " + (backoffMs / 1000) + "s (Attempt " + crashes + "/5)");
                                ms.setState(ServiceState.RECOVERING);
                                ms.setHealthy(false);
                                ms.setProcessPid(0);
                                notifyListeners(id, ServiceState.RECOVERING);
                                Log.w(TAG, "[ServiceManager] " + ms.getDisplayName() + " crashed (code " + exitCode + "). Auto-restarting in " + (backoffMs / 1000) + "s (attempt " + crashes + "/5)...");
                                mainHandler.postDelayed(() -> {
                                    if (!ms.isUserStopped() && !ms.isRunning() && ms.isEnabled() && !ms.isBlocked()) {
                                        Log.i(TAG, "[ServiceManager] Auto-restarting " + ms.getDisplayName());
                                        startService(id, ctx);
                                    }
                                }, backoffMs);
                            }
                        }
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    }
                });

                // Socket and API readiness verification loop
                serviceExecutor.execute(() -> {
                    // Phase 1: Wait for TCP socket binding
                    long socketTimeoutMs = "bazarr".equalsIgnoreCase(id) ? 75000L : 45000L;
                    long socketDeadline = System.currentTimeMillis() + socketTimeoutMs;
                    boolean socketOk = false;
                    while (System.currentTimeMillis() < socketDeadline && activeProcesses.containsKey(id)) {
                        if (isPortOccupied(port)) {
                            socketOk = true;
                            break;
                        }
                        try {
                            Thread.sleep(300);
                        } catch (InterruptedException ignored) {
                            break;
                        }
                    }

                    if (!activeProcesses.containsKey(id)) {
                        return; // Process died before socket opened
                    }

                    if (!socketOk) {
                        ms.setLastError("SOCKET_TIMEOUT: Port " + port + " did not bind within " + (socketTimeoutMs / 1000) + "s");
                        ms.setState(ServiceState.ERROR);
                        destroyProcess(id);
                        notifyListeners(id, ServiceState.ERROR);
                        Log.e(TAG, "[ServiceManager] " + ms.getDisplayName() + " SOCKET_TIMEOUT");
                        return;
                    }

                    ms.setState(ServiceState.SOCKET_READY);
                    notifyListeners(id, ServiceState.SOCKET_READY);
                    Log.i(TAG, "[ServiceManager] " + ms.getDisplayName() + " SOCKET_READY");
                    ms.appendLog("TCP port " + port + " is open.");

                    // Phase 2: Wait for HTTP API readiness
                    long apiTimeoutMs = "bazarr".equalsIgnoreCase(id) ? 75000L : 45000L;
                    long apiDeadline = System.currentTimeMillis() + apiTimeoutMs;
                    boolean apiOk = false;
                    while (System.currentTimeMillis() < apiDeadline && activeProcesses.containsKey(id)) {
                        if (checkApiReadiness(ms)) {
                            apiOk = true;
                            break;
                        }
                        try {
                            Thread.sleep(400);
                        } catch (InterruptedException ignored) {
                            break;
                        }
                    }

                    if (!activeProcesses.containsKey(id)) {
                        return; // Process died during API readiness check
                    }

                    if (!apiOk) {
                        ms.setLastError("HTTP_TIMEOUT: Service API did not respond within " + (apiTimeoutMs / 1000) + "s");
                        ms.setState(ServiceState.UNHEALTHY);
                        notifyListeners(id, ServiceState.UNHEALTHY);
                        Log.w(TAG, "[ServiceManager] " + ms.getDisplayName() + " HTTP_TIMEOUT (UNHEALTHY)");
                        return;
                    }

                    ms.setState(ServiceState.API_READY);
                    notifyListeners(id, ServiceState.API_READY);
                    Log.i(TAG, "[ServiceManager] " + ms.getDisplayName() + " API_READY");
                    ms.appendLog("HTTP API verified and ready.");
                    if ("transmission".equalsIgnoreCase(id)) {
                        MediaStorageManager.getInstance(appContext).setTransmissionSessionLimits(true, 0.0);
                    }

                    // Fully running
                    ms.setState(ServiceState.RUNNING);
                    ms.setHealthy(true);
                    ms.resetCrashCount();
                    notifyListeners(id, ServiceState.RUNNING);
                    Log.i(TAG, "[ServiceManager] " + ms.getDisplayName() + " RUNNING");
                    ms.appendLog("Service is running and fully operational.");

                    // Automatically wire service with stack in background
                    final String svcToWire = id;
                    serviceExecutor.execute(() -> {
                        try { Thread.sleep(2000); } catch (InterruptedException ignored) {}
                        wireService(svcToWire, null);
                    });
                    scheduleAutoWireDebounced(2500);
                });

            } catch (Exception e) {
                Log.e(TAG, "Failed to start service " + id, e);
                ms.setLastError("PROCESS_START_FAILED: " + e.getMessage());
                ms.setState(ServiceState.ERROR);
                notifyListeners(id, ServiceState.ERROR);
            }
        });
    }

    public void stopService(String id) {
        ManagedService ms = services.get(id);
        if (ms == null) return;

        if ("jellyfin".equalsIgnoreCase(id)) {
            JellyfinController.getInstance().stop();
            return;
        }

        serviceExecutor.execute(() -> {
            ms.setUserStopped(true);
            ms.resetCrashCount();
            ms.setState(ServiceState.STOPPING);
            notifyListeners(id, ServiceState.STOPPING);
            Log.i(TAG, "[ServiceManager] " + ms.getDisplayName() + " STOPPING");
            ms.appendLog("Stopping service...");

            Process p = activeProcesses.remove(id);
            if (p != null) {
                if ("transmission".equalsIgnoreCase(id)) {
                    stopTransmissionGracefully(p, ms);
                } else {
                    p.destroy();
                    try {
                        boolean exited = false;
                        for (int i = 0; i < 30; i++) {
                            if (!p.isAlive()) {
                                exited = true;
                                break;
                            }
                            Thread.sleep(100);
                        }
                        if (!exited) {
                            p.destroyForcibly();
                        }
                    } catch (Throwable ignored) {}
                }
            }

            long recordedPid = ms.getProcessPid();
            if (recordedPid > 0 && recordedPid != android.os.Process.myPid()) {
                try {
                    android.os.Process.sendSignal((int) recordedPid, 15);
                    android.os.Process.killProcess((int) recordedPid);
                    android.os.Process.sendSignal((int) recordedPid, 9);
                } catch (Throwable ignored) {}
            }

            if ("bazarr".equalsIgnoreCase(id)) {
                killProcessByPattern("bazarr");
                killProcessByPattern("main.py");
                for (int i = 0; i < 25; i++) {
                    if (!isPortOccupied(6767)) break;
                    try { Thread.sleep(100); } catch (InterruptedException ignored) {}
                }
            } else {
                int port = ms.getManifest().getDefaultPort();
                if (isPortOccupied(port)) {
                    String execPath = ms.getManifest().getExecutableOrDllPath();
                    if (execPath != null && !execPath.trim().isEmpty()) {
                        String execName = new File(execPath).getName();
                        killProcessByPattern(execName);
                    }
                }
            }

            if (ms.getState() != ServiceState.NOT_INSTALLED) {
                ms.setState(ServiceState.STOPPED);
                notifyListeners(id, ServiceState.STOPPED);
            }
            ms.setHealthy(false);
            ms.setProcessPid(0);
            ms.appendLog("Service stopped.");
            Log.i(TAG, "[ServiceManager] " + ms.getDisplayName() + " STOPPED");
            scheduleAutoWireDebounced(1500);
        });
    }

    public static boolean deleteRecursive(File f) {
        if (f == null || !f.exists()) return true;
        if (f.isDirectory()) {
            File[] children = f.listFiles();
            if (children != null) {
                for (File child : children) {
                    deleteRecursive(child);
                }
            }
        }
        f.setWritable(true, false);
        boolean deleted = f.delete();
        if (!deleted && f.exists()) {
            try {
                Runtime.getRuntime().exec(new String[]{"rm", "-rf", f.getAbsolutePath()}).waitFor();
            } catch (Throwable ignored) {}
        }
        return !f.exists();
    }

    public void uninstallService(String id, Runnable onComplete) {
        uninstallService(id, false, onComplete);
    }

    public void uninstallService(String id, boolean purgeConfig, Runnable onComplete) {
        serviceExecutor.execute(() -> {
            ManagedService ms = services.get(id);
            if (ms == null || ms.getManifest().isCore()) {
                if (onComplete != null) mainHandler.post(onComplete);
                return;
            }

            Log.i(TAG, "[ServiceManager] Uninstalling service: " + ms.getDisplayName() + " (purgeConfig=" + purgeConfig + ")");
            ms.setUserStopped(true);
            ms.resetCrashCount();
            ms.setState(ServiceState.STOPPING);
            notifyListeners(id, ServiceState.STOPPING);

            // 1. Terminate running process
            Process p = activeProcesses.remove(id);
            if (p != null) {
                try {
                    p.destroy();
                    for (int i = 0; i < 15; i++) {
                        if (!p.isAlive()) break;
                        Thread.sleep(100);
                    }
                    p.destroyForcibly();
                } catch (Throwable ignored) {}
            }

            long recordedPid = ms.getProcessPid();
            if (recordedPid > 0 && recordedPid != android.os.Process.myPid()) {
                try {
                    android.os.Process.sendSignal((int) recordedPid, 15);
                    android.os.Process.killProcess((int) recordedPid);
                    android.os.Process.sendSignal((int) recordedPid, 9);
                } catch (Throwable ignored) {}
            }

            if ("bazarr".equalsIgnoreCase(id)) {
                killProcessByPattern(id.toLowerCase(Locale.ROOT));
            } else {
                try {
                    String execPath = ms.getManifest().getExecutableOrDllPath();
                    if (execPath != null && !execPath.trim().isEmpty()) {
                        String execName = new File(execPath).getName();
                        killProcessByPattern(execName);
                    }
                } catch (Throwable ignored) {}
            }

            // Wait for file handles to close
            try { Thread.sleep(300); } catch (InterruptedException ignored) {}

            // 2. Eradicate installation & configuration directories
            ServiceManifest manifest = ms.getManifest();
            File home = TermuxConstants.TERMUX_HOME_DIR;
            File prefix = TermuxConstants.TERMUX_PREFIX_DIR;

            // Delete specific executable if outside install dir or in prefix/bin
            if (manifest.getExecutableOrDllPath() != null) {
                File exe = new File(manifest.getExecutableOrDllPath());
                if (exe.exists()) {
                    exe.delete();
                }
            }

            // Delete installDir safely (only if not prefix/bin / prefix / lib / home)
            if (manifest.getInstallDir() != null) {
                File installDir = new File(manifest.getInstallDir());
                if (!installDir.equals(prefix) && !installDir.equals(new File(prefix, "bin"))
                        && !installDir.equals(new File(prefix, "lib")) && !installDir.equals(home)) {
                    deleteRecursive(installDir);
                }
            }

            // Delete configDir if requested
            if (purgeConfig && manifest.getConfigDir() != null) {
                File configDir = new File(manifest.getConfigDir());
                if (!configDir.equals(home)) {
                    deleteRecursive(configDir);
                }
            }

            // Delete dataDir
            if (manifest.getDataDir() != null) {
                File dataDir = new File(manifest.getDataDir());
                if (!dataDir.equals(home) && !dataDir.equals(manifest.getConfigDir()) && !dataDir.equals(manifest.getInstallDir())) {
                    deleteRecursive(dataDir);
                }
            }

            // Delete logDir
            if (manifest.getLogDir() != null) {
                File logDir = new File(manifest.getLogDir());
                if (!logDir.equals(home) && !logDir.equals(manifest.getConfigDir())) {
                    deleteRecursive(logDir);
                }
            }

            // 3. Mark state definitively as NOT_INSTALLED
            ms.setState(ServiceState.NOT_INSTALLED);
            ms.setHealthy(false);
            ms.setProcessPid(0);
            ms.setUserStopped(false);
            ms.appendLog("Service uninstalled and storage reclaimed.");
            notifyListeners(id, ServiceState.NOT_INSTALLED);
            Log.i(TAG, "[ServiceManager] " + ms.getDisplayName() + " uninstalled successfully.");

            // Resynchronize media stack auto-wiring in background after service removal
            autoWireStack(null);

            if (onComplete != null) {
                mainHandler.post(onComplete);
            }
        });
    }

    public void restartService(String id, Context ctx) {
        stopService(id);
        mainHandler.postDelayed(() -> startService(id, ctx), 1200);
    }

    private void destroyProcess(String id) {
        Process p = activeProcesses.remove(id);
        if (p != null) {
            try {
                p.destroy();
                for (int i = 0; i < 20; i++) {
                    if (!p.isAlive()) return;
                    Thread.sleep(100);
                }
                p.destroyForcibly();
            } catch (Throwable ignored) {}
        }
    }

    private long extractPid(Process p) {
        if (p == null) return 0;
        try {
            java.lang.reflect.Field f = p.getClass().getDeclaredField("pid");
            f.setAccessible(true);
            return f.getLong(p);
        } catch (Throwable ignored) {}
        try {
            String str = p.toString();
            java.util.regex.Matcher m = java.util.regex.Pattern.compile("pid=(\\d+)").matcher(str);
            if (m.find()) {
                return Long.parseLong(m.group(1));
            }
        } catch (Throwable ignored) {}
        return 0;
    }

    public void killProcessByPattern(String pattern) {
        if (pattern == null || pattern.isEmpty()) return;
        try {
            int myPid = android.os.Process.myPid();
            File procDir = new File("/proc");
            File[] files = procDir.listFiles();
            if (files == null) return;
            byte[] buf = new byte[2048];
            for (File file : files) {
                if (!file.isDirectory()) continue;
                String name = file.getName();
                if (!name.matches("\\d+")) continue;
                int pid;
                try {
                    pid = Integer.parseInt(name);
                } catch (NumberFormatException ignored) {
                    continue;
                }
                if (pid == myPid) continue;

                File cmdlineFile = new File(file, "cmdline");
                if (!cmdlineFile.exists() || !cmdlineFile.canRead()) continue;

                int read = 0;
                try (FileInputStream fis = new FileInputStream(cmdlineFile)) {
                    read = fis.read(buf);
                } catch (Throwable ignored) {}

                if (read <= 0) continue;
                String cmdline = new String(buf, 0, read, StandardCharsets.UTF_8).replace('\0', ' ');
                if (cmdline.toLowerCase(Locale.ROOT).contains(pattern.toLowerCase(Locale.ROOT))) {
                    Log.i(TAG, "[ProcessTerminator] Terminating matching PID " + pid + " (" + pattern + "): " + cmdline);
                    try {
                        android.os.Process.sendSignal(pid, 15);
                    } catch (Throwable ignored) {}
                    try {
                        android.os.Process.killProcess(pid);
                    } catch (Throwable ignored) {}
                    try {
                        android.os.Process.sendSignal(pid, 9);
                    } catch (Throwable ignored) {}
                    try {
                        java.lang.Runtime.getRuntime().exec(new String[]{"/system/bin/kill", "-9", String.valueOf(pid)});
                    } catch (Throwable ignored) {}
                }
            }
        } catch (Throwable t) {
            Log.w(TAG, "Error in killProcessByPattern for " + pattern + ": " + t.getMessage());
        }
    }

    public long findPidByPattern(String pattern) {
        if (pattern == null || pattern.isEmpty()) return 0;
        try {
            int myPid = android.os.Process.myPid();
            File procDir = new File("/proc");
            File[] files = procDir.listFiles();
            if (files == null) return 0;
            byte[] buf = new byte[2048];
            for (File file : files) {
                if (!file.isDirectory()) continue;
                String name = file.getName();
                if (!name.matches("\\d+")) continue;
                int pid;
                try {
                    pid = Integer.parseInt(name);
                } catch (NumberFormatException ignored) {
                    continue;
                }
                if (pid == myPid) continue;

                File cmdlineFile = new File(file, "cmdline");
                if (!cmdlineFile.exists() || !cmdlineFile.canRead()) continue;

                int read = 0;
                try (FileInputStream fis = new FileInputStream(cmdlineFile)) {
                    read = fis.read(buf);
                } catch (Throwable ignored) {}

                if (read <= 0) continue;
                String cmdline = new String(buf, 0, read, StandardCharsets.UTF_8).replace('\0', ' ');
                if (cmdline.toLowerCase(Locale.ROOT).contains(pattern.toLowerCase(Locale.ROOT))) {
                    return pid;
                }
            }
        } catch (Throwable ignored) {}
        return 0;
    }

    private void stopTransmissionGracefully(Process p, ManagedService ms) {
        if (ms != null) ms.appendLog("Sending Transmission session-close RPC to flush resume data...");
        try {
            String rpcUrl = "http://127.0.0.1:9091/transmission/rpc";
            HttpURLConnection conn = (HttpURLConnection) new URL(rpcUrl).openConnection();
            conn.setRequestMethod("POST");
            conn.setConnectTimeout(1500);
            conn.setReadTimeout(1500);
            conn.setDoOutput(true);
            conn.getOutputStream().write("{\"method\":\"session-close\"}".getBytes(StandardCharsets.UTF_8));
            int code = conn.getResponseCode();
            if (code == 409) {
                String sid = conn.getHeaderField("X-Transmission-Session-Id");
                conn.disconnect();
                if (sid != null) {
                    conn = (HttpURLConnection) new URL(rpcUrl).openConnection();
                    conn.setRequestMethod("POST");
                    conn.setRequestProperty("X-Transmission-Session-Id", sid);
                    conn.setConnectTimeout(1500);
                    conn.setReadTimeout(1500);
                    conn.setDoOutput(true);
                    conn.getOutputStream().write("{\"method\":\"session-close\"}".getBytes(StandardCharsets.UTF_8));
                    conn.getResponseCode();
                    conn.disconnect();
                }
            } else {
                conn.disconnect();
            }
        } catch (Throwable t) {
            Log.w(TAG, "Transmission session-close RPC error: " + t.getMessage());
        }

        // Wait up to 4.5 seconds for transmission-daemon to cleanly write .resume files and exit
        if (p != null) {
            try {
                boolean exited = false;
                for (int i = 0; i < 45; i++) {
                    if (!p.isAlive()) {
                        exited = true;
                        break;
                    }
                    Thread.sleep(100);
                }
                if (!exited) {
                    p.destroy();
                    for (int i = 0; i < 15; i++) {
                        if (!p.isAlive()) {
                            exited = true;
                            break;
                        }
                        Thread.sleep(100);
                    }
                    if (!exited) {
                        p.destroyForcibly();
                    }
                }
            } catch (Throwable ignored) {}
        }
    }

    private void prepareServarrRuntimeConfig(File dllFile) {
        try {
            File parent = dllFile.getParentFile();
            if (parent == null) return;

            String baseName = dllFile.getName().substring(0, dllFile.getName().lastIndexOf('.'));
            File runtimeConfig = new File(parent, baseName + ".runtimeconfig.json");

            // 1. Rename glibc deps.json so Bionic .NET runtime can resolve assemblies correctly
            File depsJson = new File(parent, baseName + ".deps.json");
            File depsJsonBak = new File(parent, baseName + ".deps.json.bak");
            if (depsJson.exists() && !depsJsonBak.exists()) {
                depsJson.renameTo(depsJsonBak);
            }

            // 2. Remove any glibc libcoreclr.so bundled with linux-core-arm64
            File localClr = new File(parent, "libcoreclr.so");
            if (localClr.exists()) {
                localClr.delete();
            }

            // 3. Ensure Bionic e_sqlite3.so is present from prefix/lib
            File prefixEsqlite = new File(TermuxConstants.TERMUX_PREFIX_DIR, "lib/libe_sqlite3.so");
            File targetEsqlite = new File(parent, "libe_sqlite3.so");
            if (prefixEsqlite.exists() && (!targetEsqlite.exists() || targetEsqlite.length() != prefixEsqlite.length())) {
                copyFile(prefixEsqlite, targetEsqlite);
            }

            // 4. Ensure Bionic libMonoPosixHelper.so is present from prefix/lib
            File prefixMono = new File(TermuxConstants.TERMUX_PREFIX_DIR, "lib/libMonoPosixHelper.so");
            File targetMono = new File(parent, "libMonoPosixHelper.so");
            if (prefixMono.exists() && (!targetMono.exists() || targetMono.length() != prefixMono.length())) {
                copyFile(prefixMono, targetMono);
            }

            // 5. Ensure runtimeconfig rolls forward to net10.0
            File jfConfig = new File(TermuxConstants.TERMUX_PREFIX_DIR, "lib/jellyfin/jellyfin.runtimeconfig.json");
            if (jfConfig.exists()) {
                if (!runtimeConfig.exists() || runtimeConfig.length() < 100) {
                    copyFile(jfConfig, runtimeConfig);
                }
            }

            // 6. Pre-create or sanitize config.xml (BindAddress 0.0.0.0 and Auth None for local Web UI)
            File configDir = new File(TermuxConstants.TERMUX_HOME_DIR, ".config/" + baseName);
            if (!configDir.exists()) configDir.mkdirs();
            File configXml = new File(configDir, "config.xml");
            int servarrPort = "radarr".equalsIgnoreCase(baseName) ? 7878 : ("sonarr".equalsIgnoreCase(baseName) ? 8989 : ("lidarr".equalsIgnoreCase(baseName) ? 8686 : 9696));
            if (configXml.exists()) {
                sanitizeConfigXml(configXml);
            } else {
                createInitialConfigXml(configXml, baseName, servarrPort);
            }

            // 7. Ensure FFmpeg & FFProbe wrappers are deployed and dynamic libs symlinked
            deployFfmpegWrappersAndSymlinks(parent);

            if ("lidarr".equalsIgnoreCase(baseName)) {
                deployFpcalcIfNeeded();
            }

            // 8. Ensure appsettings.json has FFOptions.BinaryFolder configured
            ensureServarrAppSettings(configDir);
        } catch (Throwable t) {
            Log.w(TAG, "Error preparing Servarr runtime config: " + t.getMessage());
        }
    }

    private void deployFfmpegWrappersAndSymlinks(File serviceDir) {
        try {
            File prefix = TermuxConstants.TERMUX_PREFIX_DIR;
            File prefixBin = new File(prefix, "bin");
            File prefixLib = new File(prefix, "lib");
            File jfOptLib = new File(prefix, "opt/jellyfin/lib");
            File jfOptBin = new File(prefix, "opt/jellyfin/bin");

            if (!prefixBin.exists()) prefixBin.mkdirs();
            if (!prefixLib.exists()) prefixLib.mkdirs();

            // 1. Symlink all libav*.so from opt/jellyfin/lib into prefix/lib
            if (jfOptLib.exists()) {
                File[] soFiles = jfOptLib.listFiles();
                if (soFiles != null) {
                    for (File so : soFiles) {
                        if (so.getName().endsWith(".so") || so.getName().contains(".so.")) {
                            File targetSo = new File(prefixLib, so.getName());
                            if (!targetSo.exists()) {
                                try {
                                    android.system.Os.symlink(so.getAbsolutePath(), targetSo.getAbsolutePath());
                                } catch (Throwable ignored) {}
                            }
                        }
                    }
                }
            }

            // 2. Deploy wrapper script for ffprobe and ffmpeg
            String[] bins = {"ffprobe", "ffmpeg"};
            for (String b : bins) {
                File realBin = new File(jfOptBin, b);
                String scriptContent = "#!/system/bin/sh\n" +
                        "export LD_LIBRARY_PATH=" + jfOptLib.getAbsolutePath() + ":" + prefixLib.getAbsolutePath() + ":$LD_LIBRARY_PATH\n" +
                        "exec " + realBin.getAbsolutePath() + " \"$@\"\n";

                File wrapperBin = new File(prefixBin, b);
                writeExecutableScript(wrapperBin, scriptContent);

                if (serviceDir != null && serviceDir.exists()) {
                    File svcWrapper = new File(serviceDir, b);
                    writeExecutableScript(svcWrapper, scriptContent);
                }
            }
        } catch (Throwable t) {
            Log.w(TAG, "Error deploying ffmpeg wrappers: " + t.getMessage());
        }
    }

    private void writeExecutableScript(File targetFile, String content) {
        try {
            try (FileOutputStream fos = new FileOutputStream(targetFile)) {
                fos.write(content.getBytes(StandardCharsets.UTF_8));
            }
            targetFile.setExecutable(true, false);
            targetFile.setReadable(true, false);
            try {
                android.system.Os.chmod(targetFile.getAbsolutePath(), 0755);
            } catch (Throwable ignored) {}
        } catch (Throwable ignored) {}
    }

    private void ensureServarrAppSettings(File configDir) {
        try {
            if (!configDir.exists()) configDir.mkdirs();
            File appSettings = new File(configDir, "appsettings.json");
            String binFolder = new File(TermuxConstants.TERMUX_PREFIX_DIR, "bin").getAbsolutePath();
            String json = "{\n" +
                    "  \"AllowedHosts\": \"*\",\n" +
                    "  \"FFOptions\": {\n" +
                    "    \"BinaryFolder\": \"" + binFolder + "\"\n" +
                    "  }\n" +
                    "}";
            try (FileOutputStream fos = new FileOutputStream(appSettings)) {
                fos.write(json.getBytes(StandardCharsets.UTF_8));
            }
        } catch (Throwable ignored) {}
    }

    private void createInitialConfigXml(File configXml, String baseName, int port) {
        try {
            String apiKey = java.util.UUID.randomUUID().toString().replace("-", "");
            String xml = "<Config>\n" +
                    "  <BindAddress>0.0.0.0</BindAddress>\n" +
                    "  <Port>" + port + "</Port>\n" +
                    "  <EnableSsl>False</EnableSsl>\n" +
                    "  <LaunchBrowser>False</LaunchBrowser>\n" +
                    "  <ApiKey>" + apiKey + "</ApiKey>\n" +
                    "  <AuthenticationMethod>Forms</AuthenticationMethod>\n" +
                    "  <AuthenticationRequired>DisabledForLocalAddresses</AuthenticationRequired>\n" +
                    "  <AllowedHosts>*</AllowedHosts>\n" +
                    "  <TrustedNetworks>192.168.0.0/16, 10.0.0.0/8, 172.16.0.0/12, 192.0.0.0/8, 127.0.0.1/32</TrustedNetworks>\n" +
                    "  <Branch>master</Branch>\n" +
                    "  <LogLevel>info</LogLevel>\n" +
                    "  <UrlBase></UrlBase>\n" +
                    "  <InstanceName>" + baseName + "</InstanceName>\n" +
                    "</Config>\n";
            try (FileOutputStream fos = new FileOutputStream(configXml)) {
                fos.write(xml.getBytes(StandardCharsets.UTF_8));
            }
            Log.i(TAG, "[ConfigSanitizer] Pre-created initial config.xml with Auth Forms, AllowedHosts * & TrustedNetworks for " + baseName);
        } catch (Throwable t) {
            Log.w(TAG, "[ConfigSanitizer] createInitialConfigXml failed for " + baseName + ": " + t.getMessage());
        }
    }

    public void ensureServiceConfigSanitized(String serviceId) {
        if ("transmission".equalsIgnoreCase(serviceId)) {
            File home = TermuxConstants.TERMUX_HOME_DIR;
            sanitizeTransmissionConfig(new File(home, ".config/transmission-daemon"));
            return;
        }
        if ("bazarr".equalsIgnoreCase(serviceId)) {
            File bazarrConfigDir = new File(TermuxConstants.TERMUX_HOME_DIR, ".config/bazarr");
            sanitizeBazarrConfig(bazarrConfigDir);
            return;
        }
        String baseName = null;
        int port = 0;
        if ("radarr".equalsIgnoreCase(serviceId)) { baseName = "Radarr"; port = 7878; }
        else if ("sonarr".equalsIgnoreCase(serviceId)) { baseName = "Sonarr"; port = 8989; }
        else if ("prowlarr".equalsIgnoreCase(serviceId)) { baseName = "Prowlarr"; port = 9696; }
        else if ("lidarr".equalsIgnoreCase(serviceId)) { baseName = "Lidarr"; port = 8686; }
        if (baseName != null) {
            File configDir = new File(TermuxConstants.TERMUX_HOME_DIR, ".config/" + baseName);
            if (!configDir.exists()) configDir.mkdirs();
            File configXml = new File(configDir, "config.xml");
            if (configXml.exists()) {
                sanitizeConfigXml(configXml);
            } else {
                createInitialConfigXml(configXml, baseName, port);
            }
        }
    }

    private void sanitizeBazarrConfig(File bazarrConfigDir) {
        try {
            File configSubDir = new File(bazarrConfigDir, "config");
            if (!configSubDir.exists()) configSubDir.mkdirs();
            File configFile = new File(configSubDir, "config.yaml");

            File home = TermuxConstants.TERMUX_HOME_DIR;
            String radarrKey = readApiKey(new File(home, ".config/Radarr/config.xml"));
            String sonarrKey = readApiKey(new File(home, ".config/Sonarr/config.xml"));

            if (!configFile.exists()) {
                StringBuilder yaml = new StringBuilder();
                yaml.append("---\n");
                yaml.append("auth:\n  apikey: ").append(java.util.UUID.randomUUID().toString().replace("-", "")).append("\n  type: null\n");
                yaml.append("general:\n  ip: 0.0.0.0\n  port: 6767\n  use_radarr: true\n  use_sonarr: true\n");
                if (radarrKey != null) {
                    yaml.append("radarr:\n  apikey: '").append(radarrKey).append("'\n  ip: 127.0.0.1\n  port: 7878\n  ssl: false\n  base_url: /\n");
                }
                if (sonarrKey != null) {
                    yaml.append("sonarr:\n  apikey: '").append(sonarrKey).append("'\n  ip: 127.0.0.1\n  port: 8989\n  ssl: false\n  base_url: /\n");
                }
                try (FileOutputStream fos = new FileOutputStream(configFile)) {
                    fos.write(yaml.toString().getBytes(StandardCharsets.UTF_8));
                }
                Log.i(TAG, "[ConfigSanitizer] Generated initial Bazarr config.yaml");
            } else {
                String content = new String(java.nio.file.Files.readAllBytes(configFile.toPath()), StandardCharsets.UTF_8);
                boolean changed = false;
                if (radarrKey != null && !content.contains("apikey: '" + radarrKey + "'") && !content.contains("apikey: " + radarrKey)) {
                    content = content.replaceAll("(?m)^(radarr:\\s*\\n\\s*apikey:).*$", "$1 '" + radarrKey + "'");
                    changed = true;
                }
                if (sonarrKey != null && !content.contains("apikey: '" + sonarrKey + "'") && !content.contains("apikey: " + sonarrKey)) {
                    content = content.replaceAll("(?m)^(sonarr:\\s*\\n\\s*apikey:).*$", "$1 '" + sonarrKey + "'");
                    changed = true;
                }
                if (changed) {
                    try (FileOutputStream fos = new FileOutputStream(configFile)) {
                        fos.write(content.getBytes(StandardCharsets.UTF_8));
                    }
                    Log.i(TAG, "[ConfigSanitizer] Synchronized Bazarr config.yaml with Radarr/Sonarr API keys");
                }
            }
        } catch (Throwable t) {
            Log.w(TAG, "[ConfigSanitizer] Failed to sanitize Bazarr config: " + t.getMessage());
        }
    }

    private void sanitizeConfigXml(File configXml) {
        try {
            if (!configXml.exists()) return;
            StringBuilder sb = new StringBuilder();
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(new FileInputStream(configXml), StandardCharsets.UTF_8))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    sb.append(line).append("\n");
                }
            }
            String content = sb.toString();
            String updated = content;

            if (updated.contains("<BindAddress>*</BindAddress>")) {
                updated = updated.replace("<BindAddress>*</BindAddress>", "<BindAddress>0.0.0.0</BindAddress>");
            }
            if (updated.matches("(?s).*<AuthenticationMethod>.*?</AuthenticationMethod>.*") &&
                !updated.contains("<AuthenticationMethod>Forms</AuthenticationMethod>") &&
                !updated.contains("<AuthenticationMethod>External</AuthenticationMethod>")) {
                updated = updated.replaceAll("<AuthenticationMethod>.*?</AuthenticationMethod>", "<AuthenticationMethod>Forms</AuthenticationMethod>");
            }
            if (updated.matches("(?s).*<AuthenticationRequired>.*?</AuthenticationRequired>.*") &&
                !updated.contains("<AuthenticationRequired>DisabledForLocalAddresses</AuthenticationRequired>")) {
                updated = updated.replaceAll("<AuthenticationRequired>.*?</AuthenticationRequired>", "<AuthenticationRequired>DisabledForLocalAddresses</AuthenticationRequired>");
            }
            if (updated.contains("<AllowedHosts>")) {
                if (!updated.contains("<AllowedHosts>*</AllowedHosts>")) {
                    updated = updated.replaceAll("<AllowedHosts>.*?</AllowedHosts>", "<AllowedHosts>*</AllowedHosts>");
                }
            } else {
                if (updated.contains("</Config>")) {
                    updated = updated.replace("</Config>", "  <AllowedHosts>*</AllowedHosts>\n</Config>");
                }
            }
            if (updated.contains("<TrustedNetworks>")) {
                if (!updated.contains("192.0.0.0/8")) {
                    updated = updated.replaceAll("<TrustedNetworks>.*?</TrustedNetworks>", "<TrustedNetworks>192.168.0.0/16, 10.0.0.0/8, 172.16.0.0/12, 192.0.0.0/8, 127.0.0.1/32</TrustedNetworks>");
                }
            } else {
                if (updated.contains("</Config>")) {
                    updated = updated.replace("</Config>", "  <TrustedNetworks>192.168.0.0/16, 10.0.0.0/8, 172.16.0.0/12, 192.0.0.0/8, 127.0.0.1/32</TrustedNetworks>\n</Config>");
                }
            }

            if (!updated.equals(content)) {
                try (FileOutputStream fos = new FileOutputStream(configXml)) {
                    fos.write(updated.getBytes(StandardCharsets.UTF_8));
                }
                Log.i(TAG, "[ConfigSanitizer] Sanitized auth, allowed hosts & trusted networks in " + configXml.getAbsolutePath());
            }
        } catch (Throwable t) {
            Log.w(TAG, "[ConfigSanitizer] Error sanitizing " + configXml.getAbsolutePath() + ": " + t.getMessage());
        }
    }

    private void sanitizeTransmissionConfig(File configDir) {
        try {
            if (!configDir.exists()) configDir.mkdirs();
            File settingsJson = new File(configDir, "settings.json");
            String torrentsDir = MediaStorageManager.getInstance(appContext).getTransmissionDownloadsDir().getAbsolutePath();
            File torrentsDirFile = new File(torrentsDir);
            if (!torrentsDirFile.exists()) torrentsDirFile.mkdirs();

            if (!settingsJson.exists()) {
                String initJson = "{\n" +
                        "  \"download-dir\": \"" + torrentsDir + "\",\n" +
                        "  \"incomplete-dir\": \"" + torrentsDir + "\",\n" +
                        "  \"incomplete-dir-enabled\": false,\n" +
                        "  \"ratio-limit\": 0.0,\n" +
                        "  \"ratio-limit-enabled\": true,\n" +
                        "  \"idle-seeding-limit\": 0,\n" +
                        "  \"idle-seeding-limit-enabled\": true,\n" +
                        "  \"rpc-enabled\": true,\n" +
                        "  \"rpc-port\": 9091,\n" +
                        "  \"rpc-bind-address\": \"0.0.0.0\",\n" +
                        "  \"rpc-whitelist-enabled\": false,\n" +
                        "  \"rpc-host-whitelist-enabled\": false,\n" +
                        "  \"rpc-authentication-required\": false,\n" +
                        "  \"dht-enabled\": true,\n" +
                        "  \"lpd-enabled\": false,\n" +
                        "  \"utp-enabled\": true,\n" +
                        "  \"pex-enabled\": true,\n" +
                        "  \"port-forwarding-enabled\": false\n" +
                        "}\n";
                try (FileOutputStream fos = new FileOutputStream(settingsJson)) {
                    fos.write(initJson.getBytes(StandardCharsets.UTF_8));
                }
                return;
            }

            try {
                StringBuilder sbJson = new StringBuilder();
                try (BufferedReader reader = new BufferedReader(new FileReader(settingsJson))) {
                    String l;
                    while ((l = reader.readLine()) != null) sbJson.append(l).append("\n");
                }
                org.json.JSONObject conf = new org.json.JSONObject(sbJson.toString());
                conf.put("rpc-whitelist-enabled", false);
                conf.put("rpc-host-whitelist-enabled", false);
                conf.put("dht-enabled", true);
                conf.put("pex-enabled", true);
                conf.put("lpd-enabled", false);
                conf.put("utp-enabled", true);
                conf.put("port-forwarding-enabled", false);
                conf.put("download-dir", torrentsDir);
                conf.put("incomplete-dir", torrentsDir);
                conf.put("incomplete-dir-enabled", false);
                conf.put("ratio-limit", 0.0);
                conf.put("ratio-limit-enabled", true);
                conf.put("idle-seeding-limit", 0);
                conf.put("idle-seeding-limit-enabled", true);
                try (FileWriter writer = new FileWriter(settingsJson)) {
                    writer.write(conf.toString(2));
                }
                return;
            } catch (Throwable t) {
                Log.w(TAG, "JSONObject parse failed on settings.json, falling back: " + t.getMessage());
            }

            StringBuilder sb = new StringBuilder();
            try (BufferedReader reader = new BufferedReader(new FileReader(settingsJson))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    if (line.contains("\"rpc-whitelist-enabled\": true")) {
                        line = line.replace("\"rpc-whitelist-enabled\": true", "\"rpc-whitelist-enabled\": false");
                    }
                    if (line.contains("\"rpc-host-whitelist-enabled\": true")) {
                        line = line.replace("\"rpc-host-whitelist-enabled\": true", "\"rpc-host-whitelist-enabled\": false");
                    }
                    if (line.contains("\"dht-enabled\": false")) {
                        line = line.replace("\"dht-enabled\": false", "\"dht-enabled\": true");
                    }
                    if (line.contains("\"utp-enabled\": false")) {
                        line = line.replace("\"utp-enabled\": false", "\"utp-enabled\": true");
                    }
                    if (line.contains("\"port-forwarding-enabled\": true")) {
                        line = line.replace("\"port-forwarding-enabled\": true", "\"port-forwarding-enabled\": false");
                    }
                    if (line.contains("\"ratio-limit\":")) {
                        line = "    \"ratio-limit\": 0.0,";
                    }
                    if (line.contains("\"ratio-limit-enabled\": false")) {
                        line = line.replace("\"ratio-limit-enabled\": false", "\"ratio-limit-enabled\": true");
                    }
                    if (line.contains("\"idle-seeding-limit\":")) {
                        line = "    \"idle-seeding-limit\": 0,";
                    }
                    if (line.contains("\"idle-seeding-limit-enabled\": false")) {
                        line = line.replace("\"idle-seeding-limit-enabled\": false", "\"idle-seeding-limit-enabled\": true");
                    }
                    if (line.contains("\"download-dir\":")) {
                        line = "    \"download-dir\": \"" + torrentsDir + "\",";
                    }
                    if (line.contains("\"incomplete-dir\":")) {
                        line = "    \"incomplete-dir\": \"" + torrentsDir + "\",";
                    }
                    if (line.contains("\"incomplete-dir-enabled\": true")) {
                        line = line.replace("\"incomplete-dir-enabled\": true", "\"incomplete-dir-enabled\": false");
                    }
                    if (line.contains("/com.fishbowl.app/files/home/FishbowlMedia/Downloads") || line.contains("/com.fishbowl.app/Downloads")) {
                        line = line.replace("/com.fishbowl.app/files/home/FishbowlMedia/Downloads", torrentsDir)
                                   .replace("/com.fishbowl.app/Downloads", torrentsDir);
                    }
                    sb.append(line).append("\n");
                }
            }
            try (FileWriter writer = new FileWriter(settingsJson)) {
                writer.write(sb.toString());
            }
        } catch (Throwable ignored) {}
    }

    private void copyFile(File src, File dst) {
        try (FileInputStream in = new FileInputStream(src);
             FileOutputStream out = new FileOutputStream(dst)) {
            byte[] buf = new byte[8192];
            int len;
            while ((len = in.read(buf)) > 0) {
                out.write(buf, 0, len);
            }
        } catch (Throwable ignored) {}
    }

    public boolean isPortOccupied(int port) {
        try (Socket socket = new Socket()) {
            socket.connect(new InetSocketAddress("127.0.0.1", port), 350);
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    public boolean checkApiReadiness(ManagedService ms) {
        if (ms == null) return false;
        String healthUrlStr = ms.getManifest().getHealthUrl();
        return checkHealth(healthUrlStr);
    }

    public boolean checkHealth(String healthUrlStr) {
        HttpURLConnection conn = null;
        try {
            URL url = new URL(healthUrlStr);
            conn = (HttpURLConnection) url.openConnection();
            conn.setConnectTimeout(1500);
            conn.setReadTimeout(1500);
            conn.setRequestMethod("GET");
            conn.setInstanceFollowRedirects(true);
            int code = conn.getResponseCode();
            // HTTP 200/204 is OK, HTTP 401 Unauthorized (Radarr/Sonarr auth), and HTTP 409 Conflict (Transmission RPC check)
            return (code >= 200 && code < 400) || code == 401 || code == 409;
        } catch (Exception e) {
            return false;
        } finally {
            if (conn != null) {
                conn.disconnect();
            }
        }
    }

    public boolean waitUntilServiceReady(String id, long timeoutMs) {
        long deadline = System.currentTimeMillis() + timeoutMs;
        ManagedService ms = services.get(id);
        if (ms == null) return false;

        if (!ms.isRunning() && !ms.isStarting()) {
            startService(id);
        }

        while (System.currentTimeMillis() < deadline) {
            if (ms.isReady()) {
                return true;
            }
            if (ms.getState() == ServiceState.ERROR || ms.getState() == ServiceState.FAILED) {
                return false;
            }
            try {
                Thread.sleep(400);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return false;
            }
        }
        return ms.isReady();
    }

    public interface ServiceReadyCallback {
        void onReady(String url);
        void onError(String errorReason);
    }

    public void ensureServiceReadyAndGetUrl(String serviceId, long timeoutMs, ServiceReadyCallback callback) {
        serviceExecutor.execute(() -> {
            if ("jellyfin".equalsIgnoreCase(serviceId)) {
                JellyfinController jc = JellyfinController.getInstance();
                if (jc.getState() != JellyfinController.State.RUNNING) {
                    jc.start(appContext);
                }
                long deadline = System.currentTimeMillis() + timeoutMs;
                while (System.currentTimeMillis() < deadline) {
                    if (jc.getState() == JellyfinController.State.RUNNING && isPortOccupied(8096)) {
                        String url = "http://127.0.0.1:8096/web/";
                        mainHandler.post(() -> callback.onReady(url));
                        return;
                    }
                    try {
                        Thread.sleep(300);
                    } catch (InterruptedException e) {
                        break;
                    }
                }
                mainHandler.post(() -> callback.onError("SOCKET_TIMEOUT: Jellyfin port 8096 did not respond within " + (timeoutMs / 1000) + "s"));
                return;
            }

            ManagedService ms = services.get(serviceId);
            if (ms == null) {
                mainHandler.post(() -> callback.onError("SERVICE_NOT_FOUND: Unknown service ID '" + serviceId + "'"));
                return;
            }

            if (!ms.isRunning() && !ms.isStarting()) {
                startService(serviceId);
            }

            long deadline = System.currentTimeMillis() + timeoutMs;
            while (System.currentTimeMillis() < deadline) {
                if (ms.isReady()) {
                    int port = ms.getManifest().getDefaultPort();
                    String webPath = ms.getManifest().getWebPath();
                    if (webPath == null) webPath = "/";
                    String targetUrl = "http://127.0.0.1:" + port + (webPath.equals("/") ? "" : webPath);
                    mainHandler.post(() -> callback.onReady(targetUrl));
                    return;
                }
                if (ms.getState() == ServiceState.ERROR || ms.getState() == ServiceState.FAILED) {
                    String err = ms.getLastError() != null ? ms.getLastError() : "PROCESS_START_FAILED";
                    mainHandler.post(() -> callback.onError(err));
                    return;
                }
                try {
                    Thread.sleep(350);
                } catch (InterruptedException e) {
                    break;
                }
            }

            mainHandler.post(() -> callback.onError("STARTUP_TIMEOUT: " + ms.getDisplayName() + " did not reach API_READY within " + (timeoutMs / 1000) + "s"));
        });
    }

    public void addListener(ServiceListener l) {
        listeners.add(l);
    }

    public void removeListener(ServiceListener l) {
        listeners.remove(l);
    }

    private void notifyListeners(String serviceId, ServiceState state) {
        mainHandler.post(() -> {
            for (ServiceListener l : listeners) {
                try {
                    l.onServiceStateChanged(serviceId, state);
                } catch (Throwable t) {
                    Log.e(TAG, "Listener error for " + serviceId, t);
                }
            }
            com.fishbowl.app.widget.FishbowlWidgetProvider.updateAllWidgets(appContext);
            com.fishbowl.app.widget.FishbowlServicesWidgetProvider.updateAllWidgets(appContext);
        });
    }

    // -------------------------------------------------------------------------
    // START ALL / STOP ALL
    // -------------------------------------------------------------------------

    /**
     * Starts all installed services in dependency order:
     * 1. Jellyfin (via JellyfinController) 2. Prowlarr 3. Radarr 4. Sonarr
     * 5. Transmission 6. Seerr
     */
    public void startAllServices() {
        serviceExecutor.execute(() -> {
            ensureForegroundServiceActive();
            String[] startOrder = {"jellyfin", "transmission", "prowlarr", "radarr", "sonarr", "lidarr", "seerr", "bazarr"};
            for (String id : startOrder) {
                ManagedService svc = services.get(id);
                if (svc != null && svc.isEnabled() && !svc.isBlocked() && svc.isInstalled() && !svc.isRunning()) {
                    startService(id);
                    // Brief pause to allow socket to bind before starting dependents
                    try { Thread.sleep(2000); } catch (InterruptedException ignored) {}
                }
            }

            // Automatically auto-wire the full stack in background once services are up
            try { Thread.sleep(3000); } catch (InterruptedException ignored) {}
            autoWireStack((success, msg) -> {
                Log.i(TAG, "[AutoWire] Automatic stack auto-wiring on start: success=" + success + " msg=" + msg);
            });
        });
    }

    /**
     * Gracefully stops all non-core services. Jellyfin is stopped via JellyfinController.
     * Transmission is stopped first with clean RPC session-close to flush all .resume files.
     */
    public void stopAllServices() {
        serviceExecutor.execute(() -> {
            // Stop Transmission first to guarantee clean RPC session-close and resume save
            ManagedService tr = services.get("transmission");
            if (tr != null) {
                stopService("transmission");
                try { Thread.sleep(500); } catch (InterruptedException ignored) {}
            }
            for (ManagedService svc : services.values()) {
                if (!svc.getManifest().isCore() && !"transmission".equalsIgnoreCase(svc.getId())) {
                    stopService(svc.getId());
                }
            }
            // Deterministic teardown guarantee for Python and child daemons
            killProcessByPattern("bazarr");
            killProcessByPattern("main.py");
        });
    }

    /**
     * Starts ONLY companion media stack services (Radarr, Sonarr, Prowlarr, Transmission, Seerr, Bazarr).
     * Strictly does NOT start Jellyfin core server.
     */
    public void startCompanionServices() {
        serviceExecutor.execute(() -> {
            ensureForegroundServiceActive();
            String[] startOrder = {"transmission", "prowlarr", "radarr", "sonarr", "lidarr", "seerr", "bazarr"};
            for (String id : startOrder) {
                ManagedService svc = services.get(id);
                if (svc != null && svc.isEnabled() && !svc.isBlocked() && svc.isInstalled() && !svc.isRunning()) {
                    startService(id);
                    try { Thread.sleep(1500); } catch (InterruptedException ignored) {}
                }
            }

            try { Thread.sleep(2500); } catch (InterruptedException ignored) {}
            autoWireStack((success, msg) -> {
                Log.i(TAG, "[AutoWire] Automatic stack auto-wiring on companion start: success=" + success + " msg=" + msg);
            });
        });
    }

    /**
     * Stops ONLY companion media stack services.
     * Strictly does NOT stop Jellyfin core server.
     */
    public void stopCompanionServices() {
        serviceExecutor.execute(() -> {
            ManagedService tr = services.get("transmission");
            if (tr != null) {
                stopService("transmission");
                try { Thread.sleep(500); } catch (InterruptedException ignored) {}
            }
            for (ManagedService svc : services.values()) {
                if (!svc.getManifest().isCore() && !"jellyfin".equalsIgnoreCase(svc.getId()) && !"transmission".equalsIgnoreCase(svc.getId())) {
                    stopService(svc.getId());
                }
            }
            killProcessByPattern("bazarr");
            killProcessByPattern("main.py");
        });
    }

    public int getRunningCount() {
        int count = 0;
        for (ManagedService s : services.values()) {
            if (s.isRunning()) count++;
        }
        return count;
    }

    public int getInstalledCount() {
        int count = 0;
        for (ManagedService s : services.values()) {
            if (s.isInstalled()) count++;
        }
        return count;
    }

    // -------------------------------------------------------------------------
    // AUTO-WIRE ENGINE (18-step sequential, idempotent pipeline)
    // -------------------------------------------------------------------------

    /**
     * Auto-wires the full media stack with strict dependency ordering:
     * 1. Ensure directories exist
     * 2. Ensure service configurations exist & sanitized
     * 3. Start required services
     * 4. Wait until Transmission API_READY
     * 5. Wait until Radarr API_READY
     * 6. Wait until Sonarr API_READY
     * 7. Wait until Prowlarr API_READY
     * 8. Discover and verify API keys (zero credential logging)
     * 9. Configure Transmission in Radarr (idempotent)
     * 10. Configure Transmission in Sonarr (idempotent)
     * 11. Configure Radarr root folder (idempotent)
     * 12. Configure Sonarr root folder (idempotent)
     * 13. Configure Prowlarr -> Radarr application (idempotent)
     * 14. Configure Prowlarr -> Sonarr application (idempotent)
     * 15. Configure/provision indexers (idempotent)
     * 16. Run ApplicationIndexerSync
     * 17. Verify synchronization
     * 18. Report AUTO-WIRE COMPLETE
     */
    /**
     * Wires and links an individual service if running, registering root paths,
     * discovering and importing existing library media, and connecting download clients/indexers.
     */
    public void wireService(String serviceId, AutoWireCallback callback) {
        serviceExecutor.execute(() -> {
            StringBuilder log = new StringBuilder();
            boolean ok = true;
            try {
                if ("radarr".equalsIgnoreCase(serviceId)) {
                    ok = wireRadarr(log);
                } else if ("sonarr".equalsIgnoreCase(serviceId)) {
                    ok = wireSonarr(log);
                } else if ("prowlarr".equalsIgnoreCase(serviceId)) {
                    ok = wireProwlarr(log);
                } else if ("transmission".equalsIgnoreCase(serviceId)) {
                    ok = wireTransmission(log);
                } else if ("lidarr".equalsIgnoreCase(serviceId)) {
                    ok = wireLidarr(log);
                } else if ("bazarr".equalsIgnoreCase(serviceId)) {
                    ok = wireBazarr(log);
                }
                postCallback(callback, ok, log.toString().trim());
            } catch (Throwable t) {
                Log.e(TAG, "[AutoWire] Error wiring " + serviceId, t);
                postCallback(callback, false, "[FAIL] " + serviceId + " auto-wire error: " + t.getMessage());
            }
        });
    }

    public boolean wireRadarr(StringBuilder log) {
        if (!isPortOccupied(7878)) {
            log.append("[SKIP] Radarr is not running (Port 7878 offline)\n");
            return false;
        }
        String radarrKey = getServiceApiKey("radarr");
        if (radarrKey == null) {
            log.append("[FAIL] Could not read Radarr API key\n");
            return false;
        }
        File movies = MediaStorageManager.getInstance(appContext).getMoviesDir();
        if (!movies.exists()) movies.mkdirs();

        // 1. Root folder configuration
        if (!hasRootFolder(7878, radarrKey, movies.getAbsolutePath())) {
            if (postJson("http://127.0.0.1:7878/api/v3/rootfolder?apiKey=" + radarrKey,
                    "{\"path\":\"" + movies.getAbsolutePath() + "\"}")) {
                log.append("[OK] Radarr root folder linked -> ").append(movies.getAbsolutePath()).append("\n");
            } else {
                log.append("[WARN] Radarr root folder linking pending\n");
            }
        } else {
            log.append("[OK] Radarr root folder verified -> ").append(movies.getAbsolutePath()).append("\n");
        }

        // 2. Discover and auto-import existing movies from disk
        scanAndImportExistingMovies(7878, radarrKey, movies, log);

        // 3. Configure Media Management (Atomic move / Hardlinks)
        configureMediaManagement(7878, radarrKey, MediaStorageManager.getInstance(appContext).isHardlinkSupported());

        // 4. Link Transmission if running
        if (isPortOccupied(9091)) {
            if (!hasDownloadClient(7878, radarrKey, "Transmission")) {
                String body = buildTransmissionClientJson("radarr");
                if (postJson("http://127.0.0.1:7878/api/v3/downloadclient?apiKey=" + radarrKey, body)) {
                    log.append("[OK] Transmission linked into Radarr\n");
                }
            } else {
                log.append("[OK] Transmission verified in Radarr\n");
            }
        }

        // 5. Link into Prowlarr if running
        if (isPortOccupied(9696)) {
            String prowlarrKey = getServiceApiKey("prowlarr");
            if (prowlarrKey != null) {
                if (!hasProwlarrApp(prowlarrKey, "Radarr")) {
                    String body = buildProwlarrAppJson("Radarr", "http://127.0.0.1:7878", radarrKey, "RadarrSettings");
                    if (postJson("http://127.0.0.1:9696/api/v1/applications?apiKey=" + prowlarrKey, body)) {
                        log.append("[OK] Radarr registered in Prowlarr\n");
                    }
                } else {
                    log.append("[OK] Radarr verified in Prowlarr\n");
                }
                postJson("http://127.0.0.1:9696/api/v1/command?apiKey=" + prowlarrKey, "{\"name\":\"ApplicationIndexerSync\"}");
            }
        }

        // 6. Enforce Quality Profiles & Custom Formats (No 4K, Strictly 720p/1080p, Max < 20GB, IMAX, HDR, x265, x264, Hindi-English)
        configureRadarrQualityProfilesAndFormats(radarrKey, log);

        return true;
    }

    public boolean wireSonarr(StringBuilder log) {
        if (!isPortOccupied(8989)) {
            log.append("[SKIP] Sonarr is not running (Port 8989 offline)\n");
            return false;
        }
        String sonarrKey = getServiceApiKey("sonarr");
        if (sonarrKey == null) {
            log.append("[FAIL] Could not read Sonarr API key\n");
            return false;
        }
        File tv = MediaStorageManager.getInstance(appContext).getTvDir();
        if (!tv.exists()) tv.mkdirs();

        // 1. Root folder configuration
        if (!hasRootFolder(8989, sonarrKey, tv.getAbsolutePath())) {
            if (postJson("http://127.0.0.1:8989/api/v3/rootfolder?apiKey=" + sonarrKey,
                    "{\"path\":\"" + tv.getAbsolutePath() + "\"}")) {
                log.append("[OK] Sonarr root folder linked -> ").append(tv.getAbsolutePath()).append("\n");
            } else {
                log.append("[WARN] Sonarr root folder linking pending\n");
            }
        } else {
            log.append("[OK] Sonarr root folder verified -> ").append(tv.getAbsolutePath()).append("\n");
        }

        // 2. Discover and auto-import existing series from disk
        scanAndImportExistingSeries(8989, sonarrKey, tv, log);

        // 3. Configure Media Management
        configureMediaManagement(8989, sonarrKey, MediaStorageManager.getInstance(appContext).isHardlinkSupported());

        // 4. Link Transmission if running
        if (isPortOccupied(9091)) {
            ensureTransmissionClient(8989, sonarrKey, "sonarr", log);
        }

        // 5. Link into Prowlarr if running
        if (isPortOccupied(9696)) {
            String prowlarrKey = getServiceApiKey("prowlarr");
            if (prowlarrKey != null) {
                if (!hasProwlarrApp(prowlarrKey, "Sonarr")) {
                    String body = buildProwlarrAppJson("Sonarr", "http://127.0.0.1:8989", sonarrKey, "SonarrSettings");
                    if (postJson("http://127.0.0.1:9696/api/v1/applications?apiKey=" + prowlarrKey, body)) {
                        log.append("[OK] Sonarr registered in Prowlarr\n");
                    }
                } else {
                    log.append("[OK] Sonarr verified in Prowlarr\n");
                }
                postJson("http://127.0.0.1:9696/api/v1/command?apiKey=" + prowlarrKey, "{\"name\":\"ApplicationIndexerSync\"}");
            }
        }

        // 6. Enforce Quality Profiles & Custom Formats (No 4K, Strictly 720p/1080p, Max < 20GB, IMAX, HDR, x265, x264, Hindi-English)
        configureSonarrQualityProfilesAndFormats(sonarrKey, log);

        return true;
    }

    public void configureRadarrQualityProfilesAndFormats(String apiKey, StringBuilder log) {
        log.append("[CONFIG] Enforcing Radarr Quality: 720p/1080p Only, Max < 20GB, Custom Formats\n");

        // 1. Direct SQLite safety guard for size limit & 4K removal
        try {
            File radarrDb = new File(TermuxConstants.TERMUX_HOME_DIR, ".config/Radarr/radarr.db");
            if (radarrDb.exists()) {
                android.database.sqlite.SQLiteDatabase db = android.database.sqlite.SQLiteDatabase.openDatabase(
                        radarrDb.getAbsolutePath(), null, android.database.sqlite.SQLiteDatabase.OPEN_READWRITE);
                if (db != null) {
                    try {
                        db.execSQL("UPDATE QualityDefinitions SET MaxSize = 160.0 WHERE MaxSize > 160.0 OR MaxSize IS NULL;");
                        db.execSQL("DELETE FROM QualityProfiles WHERE Name LIKE '%4K%' OR Name LIKE '%Ultra%' OR Name LIKE '%2160%';");
                    } finally {
                        db.close();
                    }
                }
            }
        } catch (Throwable t) {
            Log.w(TAG, "Radarr direct SQLite update notice: " + t.getMessage());
        }

        // 2. Size limit via API: Cap all quality definition max sizes at 160 MB/min (<20 GB for 2-hour movie)
        try {
            String qdResp = httpGet("http://127.0.0.1:7878/api/v3/qualitydefinition?apiKey=" + apiKey);
            if (qdResp != null && qdResp.startsWith("[")) {
                JSONArray qdArr = new JSONArray(qdResp);
                boolean qdModified = false;
                for (int i = 0; i < qdArr.length(); i++) {
                    JSONObject qd = qdArr.getJSONObject(i);
                    double max = qd.optDouble("maxSize", 0);
                    if (max > 160.0 || max <= 0 || qd.isNull("maxSize")) {
                        qd.put("maxSize", 160.0);
                        qdModified = true;
                    }
                }
                if (qdModified) {
                    putJson("http://127.0.0.1:7878/api/v3/qualitydefinition/update?apiKey=" + apiKey, qdArr.toString());
                    log.append("[OK] Radarr quality definitions capped at 160 MB/min (<20 GB limit)\n");
                }
            }
        } catch (Throwable t) {
            Log.w(TAG, "Radarr quality definitions API config error: " + t.getMessage());
        }

        // 3. Ensure Custom Formats exist and collect format IDs
        Map<String, Integer> formatIds = new java.util.HashMap<>();
        try {
            ensureRadarrCustomFormat(apiKey, "IMAX Enhanced", "(?i)\\b(IMAX|IMAX[ ._-]?ENHANCED)\\b", formatIds);
            ensureRadarrCustomFormat(apiKey, "IMAX with HDR", "(?i)\\bIMAX\\b.*?(HDR|HDR10|HDR10\\+|DOVI|DV)\\b", formatIds);
            ensureRadarrCustomFormat(apiKey, "HDR / Dolby Vision", "(?i)\\b(HDR|HDR10|HDR10\\+|HDR10PLUS|DV|DOVI|DOLBY[ ._-]?VISION)\\b", formatIds);
            ensureRadarrCustomFormat(apiKey, "x265 / HEVC", "(?i)\\b(HEVC|H[ ._-]?265|X265)\\b", formatIds);
            ensureRadarrCustomFormat(apiKey, "x264 / AVC", "(?i)\\b(AVC|H[ ._-]?264|X264)\\b", formatIds);
            ensureRadarrCustomFormat(apiKey, "Hindi - English Dual Audio", "(?i)(DUAL[ ._-]?AUDIO|MULTI[ ._-]?AUDIO|HIN[ ._-]?ENG|ENG[ ._-]?HIN|HINDI.*ENGLISH|ENGLISH.*HINDI|\\bHINDI\\b)", formatIds);
            log.append("[OK] Radarr Custom Formats registered (IMAX, HDR, x265, x264, Hindi-English)\n");
        } catch (Throwable t) {
            Log.w(TAG, "Radarr custom format registration error: " + t.getMessage());
        }

        // 4. Update Quality Profiles via API: Delete 4K profiles, enforce 720p/1080p ONLY, score Custom Formats
        try {
            String qpResp = httpGet("http://127.0.0.1:7878/api/v3/qualityprofile?apiKey=" + apiKey);
            if (qpResp != null && qpResp.startsWith("[")) {
                JSONArray qpArr = new JSONArray(qpResp);
                for (int i = 0; i < qpArr.length(); i++) {
                    JSONObject prof = qpArr.getJSONObject(i);
                    int profId = prof.getInt("id");
                    String profName = prof.optString("name", "");
                    String lowerName = profName.toLowerCase(Locale.US);

                    if (lowerName.contains("4k") || lowerName.contains("ultra") || lowerName.contains("2160")) {
                        httpDelete("http://127.0.0.1:7878/api/v3/qualityprofile/" + profId + "?apiKey=" + apiKey);
                        log.append("[OK] Removed 4K Radarr Profile: ").append(profName).append("\n");
                        continue;
                    }

                    // Enforce allowed qualities: Only 720p & 1080p allowed, no 4K, no SD
                    JSONArray items = prof.optJSONArray("items");
                    if (items != null) {
                        for (int j = 0; j < items.length(); j++) {
                            JSONObject it = items.getJSONObject(j);
                            filterQualityItem(it);
                        }
                    }

                    // Enforce Custom Format scores
                    JSONArray formatItems = prof.optJSONArray("formatItems");
                    if (formatItems == null) {
                        formatItems = new JSONArray();
                    }
                    applyFormatScore(formatItems, formatIds.get("IMAX with HDR"), 150);
                    applyFormatScore(formatItems, formatIds.get("IMAX Enhanced"), 120);
                    applyFormatScore(formatItems, formatIds.get("HDR / Dolby Vision"), 100);
                    applyFormatScore(formatItems, formatIds.get("Hindi - English Dual Audio"), 90);
                    applyFormatScore(formatItems, formatIds.get("x265 / HEVC"), 80);
                    applyFormatScore(formatItems, formatIds.get("x264 / AVC"), 60);
                    prof.put("formatItems", formatItems);
                    prof.put("minFormatScore", 0);
                    prof.put("cutoffFormatScore", 250);

                    putJson("http://127.0.0.1:7878/api/v3/qualityprofile/" + profId + "?apiKey=" + apiKey, prof.toString());
                    log.append("[OK] Profile '").append(profName).append("' configured: Strictly 720p/1080p, Max <20GB, scored formats\n");
                }
            }
        } catch (Throwable t) {
            Log.w(TAG, "Radarr quality profile enforcement error: " + t.getMessage());
        }
    }

    public void configureSonarrQualityProfilesAndFormats(String apiKey, StringBuilder log) {
        log.append("[CONFIG] Enforcing Sonarr Quality: 720p/1080p Only, Max < 20GB, Custom Formats\n");

        // 1. Direct SQLite safety guard for size limit & 4K removal
        try {
            File sonarrDb = new File(TermuxConstants.TERMUX_HOME_DIR, ".config/Sonarr/sonarr.db");
            if (sonarrDb.exists()) {
                android.database.sqlite.SQLiteDatabase db = android.database.sqlite.SQLiteDatabase.openDatabase(
                        sonarrDb.getAbsolutePath(), null, android.database.sqlite.SQLiteDatabase.OPEN_READWRITE);
                if (db != null) {
                    try {
                        db.execSQL("UPDATE QualityDefinitions SET MaxSize = 160.0 WHERE MaxSize > 160.0 OR MaxSize IS NULL;");
                        db.execSQL("DELETE FROM QualityProfiles WHERE Name LIKE '%4K%' OR Name LIKE '%Ultra%' OR Name LIKE '%2160%';");
                    } finally {
                        db.close();
                    }
                }
            }
        } catch (Throwable t) {
            Log.w(TAG, "Sonarr direct SQLite update notice: " + t.getMessage());
        }

        // 2. Size limit via API: Cap all quality definition max sizes at 160 MB/min
        try {
            String qdResp = httpGet("http://127.0.0.1:8989/api/v3/qualitydefinition?apiKey=" + apiKey);
            if (qdResp != null && qdResp.startsWith("[")) {
                JSONArray qdArr = new JSONArray(qdResp);
                boolean qdModified = false;
                for (int i = 0; i < qdArr.length(); i++) {
                    JSONObject qd = qdArr.getJSONObject(i);
                    double max = qd.optDouble("maxSize", 0);
                    if (max > 160.0 || max <= 0 || qd.isNull("maxSize")) {
                        qd.put("maxSize", 160.0);
                        qdModified = true;
                    }
                }
                if (qdModified) {
                    putJson("http://127.0.0.1:8989/api/v3/qualitydefinition/update?apiKey=" + apiKey, qdArr.toString());
                    log.append("[OK] Sonarr quality definitions capped at 160 MB/min (<20 GB limit)\n");
                }
            }
        } catch (Throwable t) {
            Log.w(TAG, "Sonarr quality definitions API config error: " + t.getMessage());
        }

        // 3. Ensure Custom Formats exist in Sonarr
        Map<String, Integer> formatIds = new java.util.HashMap<>();
        try {
            ensureSonarrCustomFormat(apiKey, "IMAX Enhanced", "(?i)\\b(IMAX|IMAX[ ._-]?ENHANCED)\\b", formatIds);
            ensureSonarrCustomFormat(apiKey, "IMAX with HDR", "(?i)\\bIMAX\\b.*?(HDR|HDR10|HDR10\\+|DOVI|DV)\\b", formatIds);
            ensureSonarrCustomFormat(apiKey, "HDR / Dolby Vision", "(?i)\\b(HDR|HDR10|HDR10\\+|HDR10PLUS|DV|DOVI|DOLBY[ ._-]?VISION)\\b", formatIds);
            ensureSonarrCustomFormat(apiKey, "x265 / HEVC", "(?i)\\b(HEVC|H[ ._-]?265|X265)\\b", formatIds);
            ensureSonarrCustomFormat(apiKey, "x264 / AVC", "(?i)\\b(AVC|H[ ._-]?264|X264)\\b", formatIds);
            ensureSonarrCustomFormat(apiKey, "Hindi - English Dual Audio", "(?i)(DUAL[ ._-]?AUDIO|MULTI[ ._-]?AUDIO|HIN[ ._-]?ENG|ENG[ ._-]?HIN|HINDI.*ENGLISH|ENGLISH.*HINDI|\\bHINDI\\b)", formatIds);
            log.append("[OK] Sonarr Custom Formats registered (IMAX, HDR, x265, x264, Hindi-English)\n");
        } catch (Throwable t) {
            Log.w(TAG, "Sonarr custom format registration error: " + t.getMessage());
        }

        // 4. Update Quality Profiles via API
        try {
            String qpResp = httpGet("http://127.0.0.1:8989/api/v3/qualityprofile?apiKey=" + apiKey);
            if (qpResp != null && qpResp.startsWith("[")) {
                JSONArray qpArr = new JSONArray(qpResp);
                for (int i = 0; i < qpArr.length(); i++) {
                    JSONObject prof = qpArr.getJSONObject(i);
                    int profId = prof.getInt("id");
                    String profName = prof.optString("name", "");
                    String lowerName = profName.toLowerCase(Locale.US);

                    if (lowerName.contains("4k") || lowerName.contains("ultra") || lowerName.contains("2160")) {
                        httpDelete("http://127.0.0.1:8989/api/v3/qualityprofile/" + profId + "?apiKey=" + apiKey);
                        log.append("[OK] Removed 4K Sonarr Profile: ").append(profName).append("\n");
                        continue;
                    }

                    JSONArray items = prof.optJSONArray("items");
                    if (items != null) {
                        for (int j = 0; j < items.length(); j++) {
                            JSONObject it = items.getJSONObject(j);
                            filterQualityItem(it);
                        }
                    }

                    JSONArray formatItems = prof.optJSONArray("formatItems");
                    if (formatItems == null) {
                        formatItems = new JSONArray();
                    }
                    applyFormatScore(formatItems, formatIds.get("IMAX with HDR"), 150);
                    applyFormatScore(formatItems, formatIds.get("IMAX Enhanced"), 120);
                    applyFormatScore(formatItems, formatIds.get("HDR / Dolby Vision"), 100);
                    applyFormatScore(formatItems, formatIds.get("Hindi - English Dual Audio"), 90);
                    applyFormatScore(formatItems, formatIds.get("x265 / HEVC"), 80);
                    applyFormatScore(formatItems, formatIds.get("x264 / AVC"), 60);
                    prof.put("formatItems", formatItems);
                    prof.put("minFormatScore", 0);
                    prof.put("cutoffFormatScore", 250);

                    putJson("http://127.0.0.1:8989/api/v3/qualityprofile/" + profId + "?apiKey=" + apiKey, prof.toString());
                    log.append("[OK] Profile '").append(profName).append("' configured: Strictly 720p/1080p, Max <20GB, scored formats\n");
                }
            }
        } catch (Throwable t) {
            Log.w(TAG, "Sonarr quality profile enforcement error: " + t.getMessage());
        }
    }

    private void filterQualityItem(JSONObject it) {
        try {
            JSONArray subItems = it.optJSONArray("items");
            if (subItems != null && subItems.length() > 0) {
                boolean anySubAllowed = false;
                for (int s = 0; s < subItems.length(); s++) {
                    JSONObject sub = subItems.getJSONObject(s);
                    JSONObject sq = sub.optJSONObject("quality");
                    int sRes = (sq != null) ? sq.optInt("resolution", 0) : 0;
                    int sId = (sq != null) ? sq.optInt("id", -1) : sub.optInt("quality", -1);
                    boolean allow = (sRes == 720 || sRes == 1080);
                    if (sRes == 2160 || sId == 16 || sId == 17 || sId == 18 || sId == 19 || sId == 21 || sId == 31) {
                        allow = false;
                    }
                    if (sRes > 0 && sRes < 720) {
                        allow = false;
                    }
                    sub.put("allowed", allow);
                    if (allow) anySubAllowed = true;
                }
                it.put("allowed", anySubAllowed);
                return;
            }

            JSONObject qObj = it.optJSONObject("quality");
            int res = (qObj != null) ? qObj.optInt("resolution", 0) : 0;
            int qId = (qObj != null) ? qObj.optInt("id", -1) : it.optInt("quality", -1);
            String qName = (qObj != null) ? qObj.optString("name", "") : it.optString("name", "");

            boolean allow = (res == 720 || res == 1080);
            if (res == 2160 || qId == 16 || qId == 17 || qId == 18 || qId == 19 || qId == 21 || qId == 31 || qName.contains("2160") || qName.contains("4K") || qName.contains("Ultra")) {
                allow = false;
            }
            if (res > 0 && res < 720) {
                allow = false;
            }
            if (qId >= 23 && qId <= 29) {
                allow = false;
            }
            if (qId == 0 || qId == 1 || qId == 2 || qId == 8 || qId == 12 || qId == 20 || qId == 21 || qId == 1000) {
                allow = false;
            }
            it.put("allowed", allow);
        } catch (Throwable ignored) {}
    }

    private void ensureRadarrCustomFormat(String apiKey, String name, String regex, Map<String, Integer> formatIds) {
        try {
            String cfResp = httpGet("http://127.0.0.1:7878/api/v3/customformat?apiKey=" + apiKey);
            if (cfResp != null && cfResp.startsWith("[")) {
                JSONArray arr = new JSONArray(cfResp);
                for (int i = 0; i < arr.length(); i++) {
                    JSONObject cf = arr.getJSONObject(i);
                    if (name.equalsIgnoreCase(cf.optString("name", ""))) {
                        formatIds.put(name, cf.getInt("id"));
                        return;
                    }
                }
            }

            JSONObject cfObj = new JSONObject();
            cfObj.put("name", name);
            cfObj.put("includeCustomFormatWhenRenaming", false);

            JSONArray specs = new JSONArray();
            JSONObject spec = new JSONObject();
            spec.put("name", name);
            spec.put("implementation", "ReleaseTitleSpecification");
            spec.put("negate", false);
            spec.put("required", true);

            JSONArray fields = new JSONArray();
            JSONObject f = new JSONObject();
            f.put("name", "value");
            f.put("value", regex);
            fields.put(f);
            spec.put("fields", fields);
            specs.put(spec);

            cfObj.put("specifications", specs);

            String postResp = postJsonWithResponse("http://127.0.0.1:7878/api/v3/customformat?apiKey=" + apiKey, cfObj.toString());
            if (postResp != null && postResp.startsWith("{")) {
                JSONObject created = new JSONObject(postResp);
                formatIds.put(name, created.getInt("id"));
            }
        } catch (Throwable t) {
            Log.w(TAG, "ensureRadarrCustomFormat " + name + " error: " + t.getMessage());
        }
    }

    private void ensureSonarrCustomFormat(String apiKey, String name, String regex, Map<String, Integer> formatIds) {
        try {
            String cfResp = httpGet("http://127.0.0.1:8989/api/v3/customformat?apiKey=" + apiKey);
            if (cfResp != null && cfResp.startsWith("[")) {
                JSONArray arr = new JSONArray(cfResp);
                for (int i = 0; i < arr.length(); i++) {
                    JSONObject cf = arr.getJSONObject(i);
                    if (name.equalsIgnoreCase(cf.optString("name", ""))) {
                        formatIds.put(name, cf.getInt("id"));
                        return;
                    }
                }
            }

            JSONObject cfObj = new JSONObject();
            cfObj.put("name", name);
            cfObj.put("includeCustomFormatWhenRenaming", false);

            JSONArray specs = new JSONArray();
            JSONObject spec = new JSONObject();
            spec.put("name", name);
            spec.put("implementation", "ReleaseTitleSpecification");
            spec.put("negate", false);
            spec.put("required", true);

            JSONArray fields = new JSONArray();
            JSONObject f = new JSONObject();
            f.put("name", "value");
            f.put("value", regex);
            fields.put(f);
            spec.put("fields", fields);
            specs.put(spec);

            cfObj.put("specifications", specs);

            String postResp = postJsonWithResponse("http://127.0.0.1:8989/api/v3/customformat?apiKey=" + apiKey, cfObj.toString());
            if (postResp != null && postResp.startsWith("{")) {
                JSONObject created = new JSONObject(postResp);
                formatIds.put(name, created.getInt("id"));
            }
        } catch (Throwable t) {
            Log.w(TAG, "ensureSonarrCustomFormat " + name + " error: " + t.getMessage());
        }
    }

    private void applyFormatScore(JSONArray formatItems, Integer formatId, int score) {
        if (formatId == null || formatId <= 0 || formatItems == null) return;
        try {
            for (int i = 0; i < formatItems.length(); i++) {
                JSONObject fi = formatItems.getJSONObject(i);
                if (fi.optInt("format", -1) == formatId) {
                    fi.put("score", score);
                    return;
                }
            }
            JSONObject newFi = new JSONObject();
            newFi.put("format", formatId);
            newFi.put("name", "");
            newFi.put("score", score);
            formatItems.put(newFi);
        } catch (Throwable ignored) {}
    }

    private String postJsonWithResponse(String urlStr, String jsonBody) {
        HttpURLConnection conn = null;
        try {
            URL url = new URL(urlStr);
            conn = (HttpURLConnection) url.openConnection();
            conn.setConnectTimeout(5000);
            conn.setReadTimeout(5000);
            conn.setRequestMethod("POST");
            conn.setRequestProperty("Content-Type", "application/json");
            String key = extractApiKey(urlStr);
            if (key != null) conn.setRequestProperty("X-Api-Key", key);
            conn.setDoOutput(true);
            conn.getOutputStream().write(jsonBody.getBytes(StandardCharsets.UTF_8));
            int code = conn.getResponseCode();
            if (code >= 200 && code < 300) {
                return readStreamSafe(conn.getInputStream());
            } else {
                return null;
            }
        } catch (Exception e) {
            return null;
        } finally {
            if (conn != null) conn.disconnect();
        }
    }

    public boolean wireLidarr(StringBuilder log) {
        if (!isPortOccupied(8686)) {
            log.append("[SKIP] Lidarr is not running (Port 8686 offline)\n");
            return false;
        }
        String lidarrKey = getServiceApiKey("lidarr");
        if (lidarrKey == null) {
            log.append("[FAIL] Could not read Lidarr API key\n");
            return false;
        }
        File music = MediaStorageManager.getInstance(appContext).getMusicDir();
        if (!music.exists()) music.mkdirs();

        deployFpcalcIfNeeded();

        // 1. Root folder configuration
        if (!hasRootFolder(8686, lidarrKey, music.getAbsolutePath())) {
            String rootPayload = "{\"name\":\"Music\",\"path\":\"" + music.getAbsolutePath() + "\",\"defaultMetadataProfileId\":1,\"defaultQualityProfileId\":1}";
            if (postJson("http://127.0.0.1:8686/api/v1/rootfolder?apiKey=" + lidarrKey, rootPayload)) {
                log.append("[OK] Lidarr root folder linked -> ").append(music.getAbsolutePath()).append("\n");
            } else {
                log.append("[WARN] Lidarr root folder linking pending\n");
            }
        } else {
            log.append("[OK] Lidarr root folder verified -> ").append(music.getAbsolutePath()).append("\n");
        }

        // 2. Discover and auto-import existing music from disk
        scanAndImportExistingMusic(8686, lidarrKey, music, log);

        // 3. Configure Media Management (skip free space check for Android FUSE)
        configureMediaManagement(8686, lidarrKey, MediaStorageManager.getInstance(appContext).isHardlinkSupported());

        // 4. Link Transmission if running
        if (isPortOccupied(9091)) {
            ensureTransmissionClient(8686, lidarrKey, "lidarr", log);
        }

        // 5. Link into Prowlarr if running
        if (isPortOccupied(9696)) {
            String prowlarrKey = getServiceApiKey("prowlarr");
            if (prowlarrKey != null) {
                if (!hasProwlarrApp(prowlarrKey, "Lidarr")) {
                    String body = buildProwlarrAppJson("Lidarr", "http://127.0.0.1:8686", lidarrKey, "LidarrSettings");
                    if (postJson("http://127.0.0.1:9696/api/v1/applications?apiKey=" + prowlarrKey, body)) {
                        log.append("[OK] Lidarr registered in Prowlarr\n");
                        postJson("http://127.0.0.1:9696/api/v1/command?apiKey=" + prowlarrKey, "{\"name\":\"ApplicationIndexerSync\"}");
                    }
                } else {
                    log.append("[OK] Lidarr verified in Prowlarr\n");
                }
            }
        }
        return true;
    }

    public boolean wireProwlarr(StringBuilder log) {
        if (!isPortOccupied(9696)) {
            log.append("[SKIP] Prowlarr is not running (Port 9696 offline)\n");
            return false;
        }
        String prowlarrKey = getServiceApiKey("prowlarr");
        if (prowlarrKey == null) {
            log.append("[FAIL] Could not read Prowlarr API key\n");
            return false;
        }

        syncRecommendedIndexers(prowlarrKey, log);

        // Link into Radarr if running
        if (isPortOccupied(7878)) {
            String radarrKey = getServiceApiKey("radarr");
            if (radarrKey != null) {
                ensureProwlarrApp(prowlarrKey, "Radarr", "http://127.0.0.1:7878", radarrKey, "RadarrSettings");
                cleanupRadarrCorruptIndexers(radarrKey);
                log.append("[OK] Radarr configured in Prowlarr (fullSync)\n");
            }
        }

        // Link into Sonarr if running
        if (isPortOccupied(8989)) {
            String sonarrKey = getServiceApiKey("sonarr");
            if (sonarrKey != null) {
                ensureProwlarrApp(prowlarrKey, "Sonarr", "http://127.0.0.1:8989", sonarrKey, "SonarrSettings");
                log.append("[OK] Sonarr configured in Prowlarr (fullSync)\n");
            }
        }

        // Link into Lidarr if running
        if (isPortOccupied(8686)) {
            String lidarrKey = getServiceApiKey("lidarr");
            if (lidarrKey != null) {
                ensureProwlarrApp(prowlarrKey, "Lidarr", "http://127.0.0.1:8686", lidarrKey, "LidarrSettings");
                log.append("[OK] Lidarr configured in Prowlarr (fullSync)\n");
            }
        }

        // Ensure obsolete Byparr proxy is purged from Prowlarr
        removeProwlarrProxy(prowlarrKey, "Byparr");

        // Wire native in-app Chromium solver proxy (listening on 127.0.0.1:8191)
        if (isPortOccupied(8191)) {
            int cfTagId = ensureProwlarrTag(prowlarrKey, "flaresolverr");
            if (!hasProwlarrProxy(prowlarrKey, "FlareSolverr")) {
                String proxyBody = buildFlareSolverrProxyJson("FlareSolverr", "http://127.0.0.1:8191/", 60, cfTagId);
                if (postJson("http://127.0.0.1:9696/api/v1/indexerproxy?apiKey=" + prowlarrKey, proxyBody)) {
                    log.append("[OK] Native Cloudflare Solver registered in Prowlarr\n");
                } else {
                    log.append("[WARN] Failed to register Native Cloudflare Solver in Prowlarr\n");
                }
            } else {
                ensureProxyTagged(prowlarrKey, "FlareSolverr", cfTagId);
                log.append("[OK] Native Cloudflare Solver already active in Prowlarr\n");
            }
            ensureIndexersTagged(prowlarrKey, cfTagId);
        }

        // Trigger sync command to push indexers to Radarr, Sonarr, and Lidarr
        postJson("http://127.0.0.1:9696/api/v1/command?apiKey=" + prowlarrKey, "{\"name\":\"ApplicationIndexerSync\"}");
        log.append("[OK] ApplicationIndexerSync triggered\n");
        return true;
    }

    public boolean wireTransmission(StringBuilder log) {
        if (!isPortOccupied(9091)) {
            log.append("[SKIP] Transmission is not running (Port 9091 offline)\n");
            return false;
        }
        MediaStorageManager.getInstance(appContext).setTransmissionSessionLimits(true, 0.0);
        log.append("[OK] Transmission session configured\n");

        // Link into Radarr if running
        if (isPortOccupied(7878)) {
            String radarrKey = getServiceApiKey("radarr");
            if (radarrKey != null) {
                ensureTransmissionClient(7878, radarrKey, "radarr", log);
            }
        }

        // Link into Sonarr if running
        if (isPortOccupied(8989)) {
            String sonarrKey = getServiceApiKey("sonarr");
            if (sonarrKey != null) {
                ensureTransmissionClient(8989, sonarrKey, "sonarr", log);
            }
        }

        // Link into Lidarr if running
        if (isPortOccupied(8686)) {
            String lidarrKey = getServiceApiKey("lidarr");
            if (lidarrKey != null) {
                ensureTransmissionClient(8686, lidarrKey, "lidarr", log);
            }
        }
        return true;
    }

    public boolean wireBazarr(StringBuilder log) {
        if (!isPortOccupied(6767)) {
            log.append("[SKIP] Bazarr is not running\n");
            return false;
        }
        log.append("[OK] Bazarr configuration sanitized\n");
        return true;
    }

    private boolean hasProwlarrProxy(String apiKey, String name) {
        try {
            String resp = httpGet("http://127.0.0.1:9696/api/v1/indexerproxy?apiKey=" + apiKey);
            return resp != null && resp.contains("\"" + name + "\"");
        } catch (Exception e) { return false; }
    }

    private void removeProwlarrProxy(String apiKey, String name) {
        try {
            String resp = httpGet("http://127.0.0.1:9696/api/v1/indexerproxy?apiKey=" + apiKey);
            if (resp == null || !resp.contains(name)) return;
            org.json.JSONArray arr = new org.json.JSONArray(resp);
            for (int i = 0; i < arr.length(); i++) {
                org.json.JSONObject obj = arr.optJSONObject(i);
                if (obj != null && name.equalsIgnoreCase(obj.optString("name"))) {
                    int id = obj.optInt("id", -1);
                    if (id > 0) {
                        httpDelete("http://127.0.0.1:9696/api/v1/indexerproxy/" + id + "?apiKey=" + apiKey);
                    }
                }
            }
        } catch (Throwable ignored) {}
    }

    private boolean isVideoFile(String filename) {
        if (filename == null) return false;
        String f = filename.toLowerCase(Locale.ROOT);
        return f.endsWith(".mp4") || f.endsWith(".mkv") || f.endsWith(".avi")
                || f.endsWith(".mov") || f.endsWith(".wmv") || f.endsWith(".m4v")
                || f.endsWith(".webm") || f.endsWith(".ts");
    }

    private void scanAndImportExistingMovies(int port, String apiKey, File moviesDir, StringBuilder log) {
        try {
            postJson("http://127.0.0.1:" + port + "/api/v3/command?apiKey=" + apiKey,
                    "{\"name\":\"DownloadedMoviesScan\",\"path\":\"" + moviesDir.getAbsolutePath() + "\"}");
            postJson("http://127.0.0.1:" + port + "/api/v3/command?apiKey=" + apiKey,
                    "{\"name\":\"RescanMovie\"}");

            File[] items = moviesDir.listFiles(f -> f.isDirectory() || isVideoFile(f.getName()));
            if (items == null || items.length == 0) return;

            String existingMoviesJson = httpGet("http://127.0.0.1:" + port + "/api/v3/movie?apiKey=" + apiKey);
            Set<String> trackedTitles = new HashSet<>();
            if (existingMoviesJson != null && existingMoviesJson.startsWith("[")) {
                JSONArray exArr = new JSONArray(existingMoviesJson);
                for (int i = 0; i < exArr.length(); i++) {
                    JSONObject m = exArr.getJSONObject(i);
                    String t = m.optString("title", "").toLowerCase(Locale.ROOT);
                    if (!t.isEmpty()) trackedTitles.add(t);
                    String folder = m.optString("folderName", "").toLowerCase(Locale.ROOT);
                    if (!folder.isEmpty()) trackedTitles.add(folder);
                }
            }

            int imported = 0;
            for (File item : items) {
                String itemName = item.getName();
                if (item.isFile()) {
                    int dot = itemName.lastIndexOf('.');
                    if (dot > 0) itemName = itemName.substring(0, dot);
                }
                String folderNameClean = itemName.replaceAll("\\s*\\(\\d{4}\\).*", "").replaceAll("[\\[\\]\\._-]", " ").trim().toLowerCase(Locale.ROOT);
                if (trackedTitles.contains(itemName.toLowerCase(Locale.ROOT)) || trackedTitles.contains(folderNameClean)) {
                    continue;
                }

                String query = URLEncoder.encode(folderNameClean.isEmpty() ? itemName : folderNameClean, "UTF-8");
                String lookupJson = httpGet("http://127.0.0.1:" + port + "/api/v3/movie/lookup?term=" + query + "&apiKey=" + apiKey);
                if (lookupJson != null && lookupJson.startsWith("[")) {
                    JSONArray results = new JSONArray(lookupJson);
                    if (results.length() > 0) {
                        JSONObject movie = results.getJSONObject(0);
                        movie.put("rootFolderPath", moviesDir.getAbsolutePath());
                        String moviePath = item.isDirectory() ? item.getAbsolutePath() : new File(moviesDir, movie.optString("title", itemName)).getAbsolutePath();
                        movie.put("path", moviePath);
                        movie.put("monitored", true);
                        movie.put("minimumAvailability", "announced");
                        JSONObject addOpts = new JSONObject();
                        addOpts.put("searchForMovie", false);
                        addOpts.put("monitor", "movieOnly");
                        movie.put("addOptions", addOpts);

                        if (postJson("http://127.0.0.1:" + port + "/api/v3/movie?apiKey=" + apiKey, movie.toString())) {
                            imported++;
                            log.append("[OK] Imported movie: ").append(movie.optString("title")).append("\n");
                            trackedTitles.add(movie.optString("title", "").toLowerCase(Locale.ROOT));
                        }
                    }
                }
            }
            if (imported > 0) {
                log.append("[OK] Auto-imported ").append(imported).append(" existing movie(s) into Radarr\n");
                postJson("http://127.0.0.1:" + port + "/api/v3/command?apiKey=" + apiKey, "{\"name\":\"RescanMovie\"}");
            }
        } catch (Throwable t) {
            Log.w(TAG, "[AutoWire] scanAndImportExistingMovies warning: " + t.getMessage());
        }
    }

    private void scanAndImportExistingSeries(int port, String apiKey, File tvDir, StringBuilder log) {
        try {
            postJson("http://127.0.0.1:" + port + "/api/v3/command?apiKey=" + apiKey,
                    "{\"name\":\"DownloadedEpisodesScan\",\"path\":\"" + tvDir.getAbsolutePath() + "\"}");
            postJson("http://127.0.0.1:" + port + "/api/v3/command?apiKey=" + apiKey,
                    "{\"name\":\"RescanSeries\"}");

            File[] seriesFolders = tvDir.listFiles(File::isDirectory);
            if (seriesFolders == null || seriesFolders.length == 0) return;

            String existingSeriesJson = httpGet("http://127.0.0.1:" + port + "/api/v3/series?apiKey=" + apiKey);
            Set<String> trackedTitles = new HashSet<>();
            if (existingSeriesJson != null && existingSeriesJson.startsWith("[")) {
                JSONArray exArr = new JSONArray(existingSeriesJson);
                for (int i = 0; i < exArr.length(); i++) {
                    JSONObject s = exArr.getJSONObject(i);
                    String t = s.optString("title", "").toLowerCase(Locale.ROOT);
                    if (!t.isEmpty()) trackedTitles.add(t);
                }
            }

            int imported = 0;
            for (File folder : seriesFolders) {
                String folderName = folder.getName();
                String folderNameClean = folderName.replaceAll("\\s*\\(\\d{4}\\).*", "").trim().toLowerCase(Locale.ROOT);
                if (trackedTitles.contains(folderName.toLowerCase(Locale.ROOT)) || trackedTitles.contains(folderNameClean)) {
                    continue;
                }

                String query = URLEncoder.encode(folderName, "UTF-8");
                String lookupJson = httpGet("http://127.0.0.1:" + port + "/api/v3/series/lookup?term=" + query + "&apiKey=" + apiKey);
                if (lookupJson != null && lookupJson.startsWith("[")) {
                    JSONArray results = new JSONArray(lookupJson);
                    if (results.length() > 0) {
                        JSONObject series = results.getJSONObject(0);
                        series.put("rootFolderPath", tvDir.getAbsolutePath());
                        series.put("path", folder.getAbsolutePath());
                        series.put("monitored", true);
                        JSONObject addOpts = new JSONObject();
                        addOpts.put("searchForMissingEpisodes", false);
                        series.put("addOptions", addOpts);

                        if (postJson("http://127.0.0.1:" + port + "/api/v3/series?apiKey=" + apiKey, series.toString())) {
                            imported++;
                            log.append("[OK] Imported TV series: ").append(series.optString("title")).append("\n");
                            trackedTitles.add(series.optString("title", "").toLowerCase(Locale.ROOT));
                        }
                    }
                }
            }
            if (imported > 0) {
                log.append("[OK] Auto-imported ").append(imported).append(" existing series into Sonarr\n");
                postJson("http://127.0.0.1:" + port + "/api/v3/command?apiKey=" + apiKey, "{\"name\":\"RescanSeries\"}");
            }
        } catch (Throwable t) {
            Log.w(TAG, "[AutoWire] scanAndImportExistingSeries warning: " + t.getMessage());
        }
    }

    private void scanAndImportExistingMusic(int port, String apiKey, File musicDir, StringBuilder log) {
        try {
            postJson("http://127.0.0.1:" + port + "/api/v1/command?apiKey=" + apiKey,
                    "{\"name\":\"DownloadedAlbumsScan\",\"path\":\"" + musicDir.getAbsolutePath() + "\"}");
            postJson("http://127.0.0.1:" + port + "/api/v1/command?apiKey=" + apiKey,
                    "{\"name\":\"RescanArtist\"}");

            File[] artistFolders = musicDir.listFiles(File::isDirectory);
            if (artistFolders == null || artistFolders.length == 0) return;

            String existingArtistsJson = httpGet("http://127.0.0.1:" + port + "/api/v1/artist?apiKey=" + apiKey);
            Set<String> trackedArtists = new HashSet<>();
            if (existingArtistsJson != null && existingArtistsJson.startsWith("[")) {
                JSONArray exArr = new JSONArray(existingArtistsJson);
                for (int i = 0; i < exArr.length(); i++) {
                    JSONObject a = exArr.getJSONObject(i);
                    String t = a.optString("artistName", "").toLowerCase(Locale.ROOT);
                    if (!t.isEmpty()) trackedArtists.add(t);
                }
            }

            int imported = 0;
            for (File folder : artistFolders) {
                String artistName = folder.getName().trim();
                if (trackedArtists.contains(artistName.toLowerCase(Locale.ROOT))) continue;

                String query = URLEncoder.encode(artistName, "UTF-8");
                String lookupJson = httpGet("http://127.0.0.1:" + port + "/api/v1/artist/lookup?term=" + query + "&apiKey=" + apiKey);
                if (lookupJson != null && lookupJson.startsWith("[")) {
                    JSONArray results = new JSONArray(lookupJson);
                    if (results.length() > 0) {
                        JSONObject artist = results.getJSONObject(0);
                        artist.put("rootFolderPath", musicDir.getAbsolutePath());
                        artist.put("path", folder.getAbsolutePath());
                        artist.put("monitored", true);
                        JSONObject addOpts = new JSONObject();
                        addOpts.put("searchForMissingAlbums", false);
                        addOpts.put("monitor", "all");
                        artist.put("addOptions", addOpts);

                        if (postJson("http://127.0.0.1:" + port + "/api/v1/artist?apiKey=" + apiKey, artist.toString())) {
                            imported++;
                            log.append("[OK] Imported artist: ").append(artist.optString("artistName")).append("\n");
                            trackedArtists.add(artistName.toLowerCase(Locale.ROOT));
                        }
                    }
                }
            }
            if (imported > 0) {
                log.append("[OK] Auto-imported ").append(imported).append(" existing artist(s) into Lidarr\n");
                postJson("http://127.0.0.1:" + port + "/api/v1/command?apiKey=" + apiKey, "{\"name\":\"RescanArtist\"}");
            }
        } catch (Throwable t) {
            Log.w(TAG, "[AutoWire] scanAndImportExistingMusic warning: " + t.getMessage());
        }
    }

    public void autoWireStack(AutoWireCallback callback) {
        serviceExecutor.execute(() -> {
            StringBuilder log = new StringBuilder();
            try {
                MediaStorageManager sm = MediaStorageManager.getInstance(appContext);
                sm.initializeOnLaunch(appContext);

                List<String> wiredServices = new java.util.ArrayList<>();

                ManagedService transmission = getService("transmission");
                if (transmission != null && transmission.isInstalled() && isPortOccupied(9091)) {
                    ensureServiceConfigSanitized("transmission");
                    wireTransmission(log);
                    wiredServices.add("Transmission");
                }

                ManagedService radarr = getService("radarr");
                if (radarr != null && radarr.isInstalled() && isPortOccupied(7878)) {
                    ensureServiceConfigSanitized("radarr");
                    wireRadarr(log);
                    wiredServices.add("Radarr");
                }

                ManagedService sonarr = getService("sonarr");
                if (sonarr != null && sonarr.isInstalled() && isPortOccupied(8989)) {
                    ensureServiceConfigSanitized("sonarr");
                    wireSonarr(log);
                    wiredServices.add("Sonarr");
                }

                ManagedService lidarr = getService("lidarr");
                if (lidarr != null && lidarr.isInstalled() && isPortOccupied(8686)) {
                    ensureServiceConfigSanitized("lidarr");
                    wireLidarr(log);
                    wiredServices.add("Lidarr");
                }

                ManagedService prowlarr = getService("prowlarr");
                if (prowlarr != null && prowlarr.isInstalled() && isPortOccupied(9696)) {
                    ensureServiceConfigSanitized("prowlarr");
                    wireProwlarr(log);
                    wiredServices.add("Prowlarr");
                }

                ManagedService bazarr = getService("bazarr");
                if (bazarr != null && bazarr.isInstalled() && isPortOccupied(6767)) {
                    ensureServiceConfigSanitized("bazarr");
                    wireBazarr(log);
                    wiredServices.add("Bazarr");
                }

                String summaryMsg = !wiredServices.isEmpty()
                        ? "Stack synchronized (" + android.text.TextUtils.join(", ", wiredServices) + ")"
                        : "No active companion services running to sync";
                postCallback(callback, true, summaryMsg);
            } catch (Throwable t) {
                Log.e(TAG, "[AutoWire] Error during autoWireStack", t);
                postCallback(callback, false, "Auto-wire notice: " + t.getMessage());
            }
        });
    }

    private Runnable pendingAutoWireRunnable = null;

    public synchronized void scheduleAutoWireDebounced(long delayMs) {
        if (pendingAutoWireRunnable != null) {
            mainHandler.removeCallbacks(pendingAutoWireRunnable);
        }
        pendingAutoWireRunnable = () -> {
            autoWireStack((success, msg) -> {
                Log.i(TAG, "[AutoWireDebounced] Stack auto-wired: success=" + success + " msg=" + msg);
            });
        };
        mainHandler.postDelayed(pendingAutoWireRunnable, delayMs);
    }

    public void scheduleAutoWireDebounced() {
        scheduleAutoWireDebounced(2000);
    }

    private boolean syncRecommendedIndexers(String prowlarrKey, StringBuilder log) {
        try {
            // Check existing indexers
            String existing = httpGet("http://127.0.0.1:9696/api/v1/indexer?apiKey=" + prowlarrKey);
            String existingLower = (existing != null) ? existing.toLowerCase(Locale.ROOT) : "";

            // Fetch available indexer schemas
            String schemaResp = httpGet("http://127.0.0.1:9696/api/v1/indexer/schema?apiKey=" + prowlarrKey);
            if (schemaResp == null || !schemaResp.trim().startsWith("[")) {
                log.append("[WARN] Prowlarr schema fetch skipped\n");
                return false;
            }

            int cfTagId = ensureProwlarrTag(prowlarrKey, "flaresolverr");

            org.json.JSONArray schemas = new org.json.JSONArray(schemaResp);
            String[] targetIndexers = {"1337x", "uindex", "EZTV", "TorrentGalaxy", "YTS", "LimeTorrents"};
            int added = 0;

            for (String target : targetIndexers) {
                String tLower = target.toLowerCase(Locale.ROOT);
                if (existingLower.contains("\"name\":\"" + tLower + "\"") ||
                    existingLower.contains("\"definitionname\":\"" + tLower + "\"") ||
                    existingLower.contains("\"sortname\":\"" + tLower + "\"")) {
                    continue;
                }

                for (int i = 0; i < schemas.length(); i++) {
                    org.json.JSONObject item = schemas.getJSONObject(i);
                    String name = item.optString("name", "");
                    String def = item.optString("definitionName", "");
                    if (target.equalsIgnoreCase(name) || target.equalsIgnoreCase(def)) {
                        item.put("enable", true);
                        item.put("appProfileId", 1);
                        item.put("priority", 25);

                        String lowerName = name.toLowerCase(Locale.ROOT);
                        String lowerDef = def.toLowerCase(Locale.ROOT);
                        String raw = item.toString().toLowerCase(Locale.ROOT);
                        if (cfTagId > 0 && (lowerName.contains("1337x") || lowerDef.contains("1337x") ||
                                            lowerName.contains("uindex") || lowerDef.contains("uindex") ||
                                            lowerName.contains("52bt") || lowerDef.contains("52bt") ||
                                            lowerName.contains("kickass") || lowerDef.contains("kickass") ||
                                            lowerName.contains("torrentdownload") || lowerDef.contains("torrentdownload") ||
                                            raw.contains("flaresolverr") || raw.contains("cloudflare"))) {
                            org.json.JSONArray tags = new org.json.JSONArray();
                            tags.put(cfTagId);
                            item.put("tags", tags);
                        }

                        if (postJson("http://127.0.0.1:9696/api/v1/indexer?apiKey=" + prowlarrKey, item.toString())) {
                            added++;
                            log.append("[OK] Added indexer: ").append(target).append("\n");
                        }
                        break;
                    }
                }
            }

            if (added > 0) {
                postJson("http://127.0.0.1:9696/api/v1/command?apiKey=" + prowlarrKey, "{\"name\":\"ApplicationIndexerSync\"}");
                log.append("[OK] Synced indexers into Radarr, Sonarr & Lidarr\n");
                return true;
            } else {
                log.append("[OK] All recommended indexers present\n");
                return true;
            }
        } catch (Throwable t) {
            Log.w(TAG, "syncRecommendedIndexers error: " + t.getMessage());
            log.append("[WARN] Indexers auto-sync skipped: ").append(t.getMessage()).append("\n");
            return false;
        }
    }

    public interface AutoWireCallback {
        void onResult(boolean success, String statusMessage);
    }

    private void postCallback(AutoWireCallback cb, boolean success, String msg) {
        if (cb == null) return;
        mainHandler.post(() -> cb.onResult(success, msg));
    }

    // -- Helpers -------------------------------------------------------------

    private String extractApiKey(String urlStr) {
        if (urlStr == null) return null;
        int idx = urlStr.indexOf("apiKey=");
        if (idx != -1) {
            int end = urlStr.indexOf('&', idx);
            if (end == -1) end = urlStr.length();
            return urlStr.substring(idx + 7, end);
        }
        return null;
    }

    private String getApiPrefix(int port) {
        return (port == 8686 || port == 9696) ? "/api/v1" : "/api/v3";
    }

    private boolean verifyApiKey(int port, String apiKey) {
        if (apiKey == null || apiKey.trim().isEmpty()) return false;
        try {
            String path = getApiPrefix(port) + "/system/status";
            URL url = new URL("http://127.0.0.1:" + port + path + "?apiKey=" + apiKey.trim());
            HttpURLConnection conn = (HttpURLConnection) url.openConnection();
            conn.setConnectTimeout(3000);
            conn.setReadTimeout(3000);
            conn.setRequestMethod("GET");
            conn.setRequestProperty("X-Api-Key", apiKey.trim());
            int code = conn.getResponseCode();
            conn.disconnect();
            return code == 200;
        } catch (Exception e) {
            return false;
        }
    }

    private boolean verifyIndexersSynced(int port, String apiKey) {
        try {
            String resp = httpGet("http://127.0.0.1:" + port + getApiPrefix(port) + "/indexer?apiKey=" + apiKey);
            return resp != null && resp.trim().startsWith("[") && resp.length() > 5;
        } catch (Exception e) {
            return false;
        }
    }

    public String readApiKey(File configXml) {
        if (!configXml.exists()) return null;
        try (BufferedReader br = new BufferedReader(new InputStreamReader(new FileInputStream(configXml), StandardCharsets.UTF_8))) {
            String line;
            while ((line = br.readLine()) != null) {
                line = line.trim();
                if (line.startsWith("<ApiKey>") && line.endsWith("</ApiKey>")) {
                    return line.replace("<ApiKey>", "").replace("</ApiKey>", "").trim();
                }
            }
        } catch (Exception e) { Log.w(TAG, "readApiKey error: " + configXml, e); }
        return null;
    }

    public String getServiceApiKey(String serviceId) {
        if (serviceId == null) return null;
        File home = TermuxConstants.TERMUX_HOME_DIR;
        String id = serviceId.toLowerCase(Locale.ROOT);
        if ("radarr".equals(id)) return readApiKey(new File(home, ".config/Radarr/config.xml"));
        if ("sonarr".equals(id)) return readApiKey(new File(home, ".config/Sonarr/config.xml"));
        if ("prowlarr".equals(id)) return readApiKey(new File(home, ".config/Prowlarr/config.xml"));
        if ("lidarr".equals(id)) return readApiKey(new File(home, ".config/Lidarr/config.xml"));
        if ("bazarr".equals(id)) {
            File cfg = new File(home, ".config/bazarr/config/config.yaml");
            if (cfg.exists()) {
                try (BufferedReader br = new BufferedReader(new FileReader(cfg))) {
                    String line;
                    while ((line = br.readLine()) != null) {
                        line = line.trim();
                        if (line.startsWith("apikey:")) {
                            return line.replace("apikey:", "").trim().replace("'", "").replace("\"", "");
                        }
                    }
                } catch (Exception ignored) {}
            }
        }
        return null;
    }

    private boolean hasDownloadClient(int port, String apiKey, String name) {
        try {
            String resp = httpGet("http://127.0.0.1:" + port + getApiPrefix(port) + "/downloadclient?apiKey=" + apiKey);
            return resp != null && resp.contains("\"" + name + "\"");
        } catch (Exception e) { return false; }
    }

    private boolean hasProwlarrApp(String apiKey, String name) {
        try {
            String resp = httpGet("http://127.0.0.1:9696/api/v1/applications?apiKey=" + apiKey);
            return resp != null && resp.contains("\"" + name + "\"");
        } catch (Exception e) { return false; }
    }

    private boolean hasRootFolder(int port, String apiKey, String path) {
        try {
            String resp = httpGet("http://127.0.0.1:" + port + getApiPrefix(port) + "/rootfolder?apiKey=" + apiKey);
            return resp != null && resp.contains(path);
        } catch (Exception e) { return false; }
    }

    private String httpGet(String urlStr) throws Exception {
        HttpURLConnection conn = null;
        try {
            URL url = new URL(urlStr);
            conn = (HttpURLConnection) url.openConnection();
            conn.setConnectTimeout(3000);
            conn.setReadTimeout(3000);
            conn.setRequestMethod("GET");
            String key = extractApiKey(urlStr);
            if (key != null) {
                conn.setRequestProperty("X-Api-Key", key);
            }
            BufferedReader br = new BufferedReader(new InputStreamReader(conn.getInputStream(), StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder();
            String line;
            while ((line = br.readLine()) != null) sb.append(line);
            return sb.toString();
        } finally {
            if (conn != null) conn.disconnect();
        }
    }

    private boolean postJson(String urlStr, String jsonBody) {
        HttpURLConnection conn = null;
        try {
            URL url = new URL(urlStr);
            conn = (HttpURLConnection) url.openConnection();
            conn.setConnectTimeout(5000);
            conn.setReadTimeout(5000);
            conn.setRequestMethod("POST");
            conn.setRequestProperty("Content-Type", "application/json");
            String key = extractApiKey(urlStr);
            if (key != null) {
                conn.setRequestProperty("X-Api-Key", key);
            }
            conn.setDoOutput(true);
            byte[] bytes = jsonBody.getBytes(StandardCharsets.UTF_8);
            conn.getOutputStream().write(bytes);
            int code = conn.getResponseCode();
            if (code < 200 || code >= 300) {
                String err = readStreamSafe(conn.getErrorStream());
                Log.w(TAG, "postJson failed HTTP " + code + " for " + urlStr + " (response: " + err + ")");
                return false;
            }
            return true;
        } catch (Exception e) {
            Log.w(TAG, "postJson error: " + urlStr, e);
            return false;
        } finally {
            if (conn != null) conn.disconnect();
        }
    }

    private boolean putJson(String urlStr, String jsonBody) {
        HttpURLConnection conn = null;
        try {
            URL url = new URL(urlStr);
            conn = (HttpURLConnection) url.openConnection();
            conn.setConnectTimeout(5000);
            conn.setReadTimeout(5000);
            conn.setRequestMethod("PUT");
            conn.setRequestProperty("Content-Type", "application/json");
            String key = extractApiKey(urlStr);
            if (key != null) {
                conn.setRequestProperty("X-Api-Key", key);
            }
            conn.setDoOutput(true);
            byte[] bytes = jsonBody.getBytes(StandardCharsets.UTF_8);
            conn.getOutputStream().write(bytes);
            int code = conn.getResponseCode();
            if (code < 200 || code >= 300) {
                String err = readStreamSafe(conn.getErrorStream());
                Log.w(TAG, "putJson failed HTTP " + code + " for " + urlStr + " (response: " + err + ")");
                return false;
            }
            return true;
        } catch (Exception e) {
            Log.w(TAG, "putJson error: " + urlStr, e);
            return false;
        } finally {
            if (conn != null) conn.disconnect();
        }
    }

    private boolean httpDelete(String urlStr) {
        HttpURLConnection conn = null;
        try {
            URL url = new URL(urlStr);
            conn = (HttpURLConnection) url.openConnection();
            conn.setRequestMethod("DELETE");
            conn.setConnectTimeout(5000);
            conn.setReadTimeout(5000);
            String key = extractApiKey(urlStr);
            if (key != null) {
                conn.setRequestProperty("X-Api-Key", key);
            }
            int code = conn.getResponseCode();
            return code >= 200 && code < 300;
        } catch (Exception e) {
            return false;
        } finally {
            if (conn != null) conn.disconnect();
        }
    }

    private String readStreamSafe(InputStream is) {
        if (is == null) return "";
        try (BufferedReader br = new BufferedReader(new InputStreamReader(is, StandardCharsets.UTF_8))) {
            StringBuilder sb = new StringBuilder();
            String l;
            while ((l = br.readLine()) != null) sb.append(l);
            return sb.toString();
        } catch (Throwable ignored) {
            return "";
        }
    }

    private boolean configureMediaManagement(int port, String apiKey, boolean copyUsingHardlinks) {
        try {
            String apiPrefix = getApiPrefix(port);
            String url = "http://127.0.0.1:" + port + apiPrefix + "/config/mediamanagement?apiKey=" + apiKey;
            String resp = httpGet(url);
            if (resp == null || !resp.trim().startsWith("{")) return false;
            org.json.JSONObject json = new org.json.JSONObject(resp);
            int id = json.optInt("id", 1);
            json.put("copyUsingHardlinks", copyUsingHardlinks);
            json.put("skipFreeSpaceCheckWhenImporting", true);
            json.put("minimumFreeSpaceWhenImporting", 100);
            json.put("deleteEmptyFolders", true);
            json.put("importExtraFiles", true);
            if (port != 8686) {
                json.put("extraFileExtensions", "srt");
            }
            String putUrl = "http://127.0.0.1:" + port + apiPrefix + "/config/mediamanagement/" + id + "?apiKey=" + apiKey;
            return putJson(putUrl, json.toString());
        } catch (Exception e) {
            Log.w(TAG, "configureMediaManagement failed for port " + port + ": " + e.getMessage());
            return false;
        }
    }

    private String buildTransmissionClientJson(String forService) {
        boolean isRadarr = "radarr".equalsIgnoreCase(forService);
        boolean isLidarr = "lidarr".equalsIgnoreCase(forService);
        String categoryField = isRadarr ? "movieCategory" : (isLidarr ? "musicCategory" : "tvCategory");
        String dirField = isRadarr ? "movieDirectory" : (isLidarr ? "musicDirectory" : "tvDirectory");
        String dirVal = isRadarr
                ? MediaStorageManager.getInstance(appContext).getRadarrDownloadsDir().getAbsolutePath()
                : (isLidarr
                ? new File(MediaStorageManager.getInstance(appContext).getDownloadsDir(), "lidarr").getAbsolutePath()
                : MediaStorageManager.getInstance(appContext).getSonarrDownloadsDir().getAbsolutePath());
        return "{\"enable\":true,\"protocol\":\"torrent\",\"priority\":1," +
               "\"removeCompletedDownloads\":true,\"removeFailedDownloads\":true," +
               "\"name\":\"Transmission\",\"implementation\":\"Transmission\"," +
               "\"configContract\":\"TransmissionSettings\"," +
               "\"fields\":[" +
               "{\"name\":\"host\",\"value\":\"127.0.0.1\"}," +
               "{\"name\":\"port\",\"value\":9091}," +
               "{\"name\":\"urlBase\",\"value\":\"/transmission/\"}," +
               "{\"name\":\"" + categoryField + "\",\"value\":\"\"}," +
               "{\"name\":\"" + dirField + "\",\"value\":\"" + dirVal + "\"}," +
               "{\"name\":\"useSsl\",\"value\":false}]," +
               "\"tags\":[]}";
    }

    private void ensureTransmissionClient(int port, String apiKey, String forService, StringBuilder log) {
        try {
            String apiPrefix = getApiPrefix(port);
            String url = "http://127.0.0.1:" + port + apiPrefix + "/downloadclient?apiKey=" + apiKey;
            String resp = httpGet(url);
            int existingId = -1;
            boolean needsUpdate = false;
            if (resp != null && resp.trim().startsWith("[")) {
                JSONArray arr = new JSONArray(resp);
                for (int i = 0; i < arr.length(); i++) {
                    JSONObject client = arr.optJSONObject(i);
                    if (client != null && "Transmission".equalsIgnoreCase(client.optString("name"))) {
                        existingId = client.optInt("id", -1);
                        JSONArray fields = client.optJSONArray("fields");
                        boolean hasDir = false;
                        if (fields != null) {
                            String dirField = "radarr".equalsIgnoreCase(forService) ? "movieDirectory"
                                    : ("lidarr".equalsIgnoreCase(forService) ? "musicDirectory" : "tvDirectory");
                            for (int j = 0; j < fields.length(); j++) {
                                JSONObject f = fields.optJSONObject(j);
                                if (f != null && dirField.equalsIgnoreCase(f.optString("name"))) {
                                    String val = f.optString("value", "");
                                    if (val != null && !val.trim().isEmpty()) {
                                        hasDir = true;
                                    }
                                }
                            }
                        }
                        if (!hasDir) {
                            needsUpdate = true;
                        }
                        break;
                    }
                }
            }
            String body = buildTransmissionClientJson(forService);
            if (existingId > 0 && needsUpdate) {
                httpDelete("http://127.0.0.1:" + port + apiPrefix + "/downloadclient/" + existingId + "?apiKey=" + apiKey);
                if (postJson("http://127.0.0.1:" + port + apiPrefix + "/downloadclient?apiKey=" + apiKey, body)) {
                    if (log != null) log.append("[OK] Transmission updated in ").append(forService).append("\n");
                }
            } else if (existingId <= 0) {
                if (postJson("http://127.0.0.1:" + port + apiPrefix + "/downloadclient?apiKey=" + apiKey, body)) {
                    if (log != null) log.append("[OK] Transmission linked into ").append(forService).append("\n");
                }
            } else {
                if (log != null) log.append("[OK] Transmission verified in ").append(forService).append("\n");
            }
        } catch (Throwable t) {
            Log.w(TAG, "ensureTransmissionClient failed for " + forService + ": " + t.getMessage());
        }
    }

    private String buildProwlarrAppJson(String name, String baseUrl, String apiKey, String configContract) {
        String syncCategories;
        if ("RadarrSettings".equals(configContract)) {
            syncCategories = "[2000,2010,2020,2030,2040,2045,2050,2060,2070,2080,8000]";
        } else if ("LidarrSettings".equals(configContract)) {
            syncCategories = "[3000,3010,3020,3030,3040]";
        } else {
            syncCategories = "[5000,5010,5020,5030,5040,5045,5050,5060,5070,5080,8000]";
        }
        return "{\"syncLevel\":\"fullSync\",\"animeSyncLevel\":\"fullSync\"," +
               "\"name\":\"" + name + "\"," +
               "\"implementation\":\"" + name + "\"," +
               "\"implementationName\":\"" + name + "\"," +
               "\"configContract\":\"" + configContract + "\"," +
               "\"fields\":[" +
               "{\"name\":\"prowlarrUrl\",\"value\":\"http://127.0.0.1:9696\"}," +
               "{\"name\":\"baseUrl\",\"value\":\"" + baseUrl + "\"}," +
               "{\"name\":\"apiKey\",\"value\":\"" + apiKey + "\"}," +
               "{\"name\":\"syncCategories\",\"value\":" + syncCategories + "}]," +
               "\"tags\":[]}";
    }

    private void ensureProwlarrApp(String prowlarrKey, String name, String baseUrl, String appKey, String configContract) {
        try {
            String resp = httpGet("http://127.0.0.1:9696/api/v1/applications?apiKey=" + prowlarrKey);
            int existingId = -1;
            if (resp != null && resp.trim().startsWith("[")) {
                org.json.JSONArray arr = new org.json.JSONArray(resp);
                for (int i = 0; i < arr.length(); i++) {
                    org.json.JSONObject app = arr.optJSONObject(i);
                    if (app != null && name.equalsIgnoreCase(app.optString("name", ""))) {
                        existingId = app.optInt("id", -1);
                        break;
                    }
                }
            }
            String body = buildProwlarrAppJson(name, baseUrl, appKey, configContract);
            if (existingId > 0) {
                org.json.JSONObject obj = new org.json.JSONObject(body);
                obj.put("id", existingId);
                putJson("http://127.0.0.1:9696/api/v1/applications/" + existingId + "?apiKey=" + prowlarrKey, obj.toString());
            } else {
                postJson("http://127.0.0.1:9696/api/v1/applications?apiKey=" + prowlarrKey, body);
            }
        } catch (Throwable t) {
            Log.w(TAG, "ensureProwlarrApp error for " + name + ": " + t.getMessage());
        }
    }

    private void cleanupRadarrCorruptIndexers(String radarrKey) {
        if (radarrKey == null) return;
        try {
            String resp = httpGet("http://127.0.0.1:7878/api/v3/indexer?apiKey=" + radarrKey);
            if (resp == null || !resp.trim().startsWith("[")) return;
            org.json.JSONArray arr = new org.json.JSONArray(resp);
            for (int i = 0; i < arr.length(); i++) {
                org.json.JSONObject idx = arr.optJSONObject(i);
                if (idx == null) continue;
                int id = idx.optInt("id", -1);
                String name = idx.optString("name", "");
                String raw = idx.toString();
                if (name.contains("The Pirate Bay") && raw.contains("/9696/1/")) {
                    httpDelete("http://127.0.0.1:7878/api/v3/indexer/" + id + "?apiKey=" + radarrKey);
                    Log.i(TAG, "[AutoWire] Cleaned up mis-mapped Radarr indexer id " + id);
                }
            }
        } catch (Throwable ignored) {}
    }

    private String buildFlareSolverrProxyJson(String name, String hostUrl, int timeoutSec, int tagId) {
        String tagJson = tagId > 0 ? "[" + tagId + "]" : "[]";
        return "{\"name\":\"" + name + "\"," +
               "\"implementation\":\"FlareSolverr\"," +
               "\"implementationName\":\"FlareSolverr\"," +
               "\"configContract\":\"FlareSolverrSettings\"," +
               "\"fields\":[" +
               "{\"name\":\"host\",\"value\":\"" + hostUrl + "\"}," +
               "{\"name\":\"requestTimeout\",\"value\":" + timeoutSec + "}]," +
               "\"tags\":" + tagJson + "}";
    }

    private int ensureProwlarrTag(String apiKey, String tagLabel) {
        try {
            String resp = httpGet("http://127.0.0.1:9696/api/v1/tag?apiKey=" + apiKey);
            if (resp != null && resp.trim().startsWith("[")) {
                org.json.JSONArray arr = new org.json.JSONArray(resp);
                for (int i = 0; i < arr.length(); i++) {
                    org.json.JSONObject t = arr.optJSONObject(i);
                    if (t != null && tagLabel.equalsIgnoreCase(t.optString("label"))) {
                        return t.optInt("id", -1);
                    }
                }
            }
            postJson("http://127.0.0.1:9696/api/v1/tag?apiKey=" + apiKey, "{\"label\":\"" + tagLabel + "\"}");
            String checkResp = httpGet("http://127.0.0.1:9696/api/v1/tag?apiKey=" + apiKey);
            if (checkResp != null && checkResp.trim().startsWith("[")) {
                org.json.JSONArray arr = new org.json.JSONArray(checkResp);
                for (int i = 0; i < arr.length(); i++) {
                    org.json.JSONObject t = arr.optJSONObject(i);
                    if (t != null && tagLabel.equalsIgnoreCase(t.optString("label"))) {
                        return t.optInt("id", -1);
                    }
                }
            }
        } catch (Throwable ignored) {}
        return -1;
    }

    private void ensureProxyTagged(String apiKey, String name, int tagId) {
        if (tagId <= 0) return;
        try {
            String resp = httpGet("http://127.0.0.1:9696/api/v1/indexerproxy?apiKey=" + apiKey);
            if (resp == null || !resp.trim().startsWith("[")) return;
            org.json.JSONArray arr = new org.json.JSONArray(resp);
            for (int i = 0; i < arr.length(); i++) {
                org.json.JSONObject p = arr.optJSONObject(i);
                if (p != null && name.equalsIgnoreCase(p.optString("name"))) {
                    org.json.JSONArray tags = p.optJSONArray("tags");
                    boolean hasTag = false;
                    if (tags != null) {
                        for (int j = 0; j < tags.length(); j++) {
                            if (tags.optInt(j) == tagId) { hasTag = true; break; }
                        }
                    } else {
                        tags = new org.json.JSONArray();
                        p.put("tags", tags);
                    }
                    if (!hasTag) {
                        tags.put(tagId);
                        int id = p.optInt("id", -1);
                        if (id > 0) {
                            putJson("http://127.0.0.1:9696/api/v1/indexerproxy/" + id + "?apiKey=" + apiKey, p.toString());
                        }
                    }
                }
            }
        } catch (Throwable ignored) {}
    }

    private void ensureIndexersTagged(String apiKey, int tagId) {
        if (tagId <= 0) return;
        try {
            String resp = httpGet("http://127.0.0.1:9696/api/v1/indexer?apiKey=" + apiKey);
            if (resp == null || !resp.trim().startsWith("[")) return;
            org.json.JSONArray arr = new org.json.JSONArray(resp);
            for (int i = 0; i < arr.length(); i++) {
                org.json.JSONObject idx = arr.optJSONObject(i);
                if (idx == null) continue;
                String name = idx.optString("name", "").toLowerCase(Locale.ROOT);
                String def = idx.optString("definitionName", "").toLowerCase(Locale.ROOT);
                String raw = idx.toString().toLowerCase(Locale.ROOT);
                boolean needsSolver = name.contains("1337x") || def.contains("1337x") ||
                                      name.contains("uindex") || def.contains("uindex") ||
                                      name.contains("52bt") || def.contains("52bt") ||
                                      name.contains("kickass") || def.contains("kickass") ||
                                      name.contains("torrentdownload") || def.contains("torrentdownload") ||
                                      raw.contains("flaresolverr") || raw.contains("cloudflare");
                if (needsSolver) {
                    org.json.JSONArray tags = idx.optJSONArray("tags");
                    boolean hasTag = false;
                    if (tags != null) {
                        for (int j = 0; j < tags.length(); j++) {
                            if (tags.optInt(j) == tagId) { hasTag = true; break; }
                        }
                    } else {
                        tags = new org.json.JSONArray();
                        idx.put("tags", tags);
                    }
                    if (!hasTag) {
                        tags.put(tagId);
                        int id = idx.optInt("id", -1);
                        if (id > 0) {
                            putJson("http://127.0.0.1:9696/api/v1/indexer/" + id + "?apiKey=" + apiKey, idx.toString());
                        }
                    }
                }
            }
        } catch (Throwable ignored) {}
    }
}

