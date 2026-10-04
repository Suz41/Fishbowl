package com.fishbowl.app;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.StatFs;
import android.util.Log;

import com.termux.BuildConfig;
import com.termux.shared.termux.TermuxConstants;

import java.io.BufferedReader;
import java.io.File;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.URL;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.LinkedList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArraySet;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Phase 9: Reliability & Production Hardening
 *
 * Single-process Jellyfin server controller.
 *
 *  - Duplicate-start protection: only one dotnet process ever
 *  - Full state machine: UNINITIALIZED→INITIALIZING→STARTING→RUNNING
 *                        →STOPPING→STOPPED / FAILED / CRASHED / CRASH_LOOP
 *  - Health check = sole RUNNING signal; polling stops once healthy
 *  - Crash vs. normal-stop differentiation
 *  - Crash recovery: max 3 auto-retries, then CRASH_LOOP
 *  - STOP kills process tree (Jellyfin + child FFmpeg)
 *  - Port-conflict pre-check
 *  - Persistent Jellyfin data separated from replaceable runtime via --datadir
 *  - No leaked executors on re-launch
 *  - Timestamped structured logging; clearDisplayedLogs only clears UI buffer
 *  - All ProcessBuilder, no shell invocation
 */
public class JellyfinController {

    private static final String TAG = "JellyfinController";
    private static final int MAX_LOG_CHARS      = 30_000;
    private static final int MAX_AUTO_RESTARTS  = 3;
    private static final int HEALTH_INITIAL_DELAY_S = 2;
    private static final int HEALTH_PERIOD_S        = 3;
    private static final int STOP_TIMEOUT_S         = 15;
    private static final int PORT_RELEASE_TIMEOUT_S = 10;

    private static JellyfinController instance;

    // ── State machine ──────────────────────────────────────────────────────────
    public enum State {
        UNINITIALIZED,
        INITIALIZING,
        STARTING,
        RUNNING,
        STOPPING,
        STOPPED,
        FAILED,
        CRASHED,
        CRASH_LOOP
    }

    public enum StartupStage {
        NONE,
        STARTING_RUNTIME,
        LAUNCHING_SERVER,
        WAITING_FOR_SERVER,
        CHECKING_READINESS,
        READY,
        FAILED
    }

    public interface Listener {
        void onServerChanged(State state);
        default void onBootstrapProgress(String message, int percent) {}
    }
    public interface LogListener { void onLogAppended(); }

    // ── Singleton ──────────────────────────────────────────────────────────────
    public static synchronized JellyfinController getInstance() {
        if (instance == null) instance = new JellyfinController();
        return instance;
    }

    // ── Fields ─────────────────────────────────────────────────────────────────
    /** Single-threaded command executor — serializes start/stop/restart. */
    private final ExecutorService executor = Executors.newSingleThreadExecutor();

    /** Per-launch workers for output reader + exit monitor. */
    private ExecutorService processWorkers;

    /** Health-check scheduler. */
    private ScheduledExecutorService healthCheckExecutor;
    private ScheduledFuture<?> healthCheckFuture;

    private final CopyOnWriteArraySet<Listener> listeners = new CopyOnWriteArraySet<>();
    private final CopyOnWriteArraySet<LogListener> logListeners = new CopyOnWriteArraySet<>();
    private final List<String> liveLines = new ArrayList<>();
    private static final int MAX_PRESERVED_ERRORS = 500;
    private final List<String> preservedErrorLogs = new ArrayList<>();
    private final SimpleDateFormat sdf =
            new SimpleDateFormat("HH:mm:ss", Locale.getDefault());

    private State currentState = State.UNINITIALIZED;
    private StartupStage currentStage = StartupStage.NONE;
    private Process jellyfinProcess;
    private boolean stopRequested;
    private Integer lastExitCode;
    private String  lastError;
    private String  bootstrapProgressMessage = "";
    private int     bootstrapProgressPercent = 0;
    private final AtomicInteger autoRestartCount = new AtomicInteger(0);
    private final AtomicInteger healthPollAttempt = new AtomicInteger(0);
    private long serverStartTimeMs = 0;

    public static class KillEvent {
        public final long timestamp;
        public final String reason;
        public final int exitCode;
        public final long uptimeSeconds;
        public final long freeStorageMb;
        public final long freeRamMb;

        public KillEvent(long timestamp, String reason, int exitCode, long uptimeSeconds, long freeStorageMb, long freeRamMb) {
            this.timestamp = timestamp;
            this.reason = reason;
            this.exitCode = exitCode;
            this.uptimeSeconds = uptimeSeconds;
            this.freeStorageMb = freeStorageMb;
            this.freeRamMb = freeRamMb;
        }

        public String toFormattedString() {
            SimpleDateFormat df = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault());
            return df.format(new Date(timestamp)) + " | " + reason +
                    (exitCode != 0 ? " (Exit " + exitCode + ")" : "") +
                    " | Uptime: " + uptimeSeconds + "s | Free Disk: " + freeStorageMb + " MB | Free RAM: " + freeRamMb + " MB";
        }
    }

    public static class ServerHealth {
        public final boolean isHealthy;
        public final String status;
        public final long uptimeSeconds;
        public final long totalRamMb;
        public final long usedRamMb;
        public final int ramUsagePercent;
        public final long storageFreeMb;
        public final long storageTotalMb;
        public final boolean isTranscodingActive;
        public final int activeTranscodeCount;

        public ServerHealth(boolean isHealthy, String status, long uptimeSeconds, long totalRamMb, long usedRamMb, int ramUsagePercent, long storageFreeMb, long storageTotalMb, boolean isTranscodingActive, int activeTranscodeCount) {
            this.isHealthy = isHealthy;
            this.status = status;
            this.uptimeSeconds = uptimeSeconds;
            this.totalRamMb = totalRamMb;
            this.usedRamMb = usedRamMb;
            this.ramUsagePercent = ramUsagePercent;
            this.storageFreeMb = storageFreeMb;
            this.storageTotalMb = storageTotalMb;
            this.isTranscodingActive = isTranscodingActive;
            this.activeTranscodeCount = activeTranscodeCount;
        }
    }

    public static class TranscodeStatus {
        public final boolean isActive;
        public final int activeFileCount;
        public final long cacheBytes;

        public TranscodeStatus(boolean isActive, int activeFileCount, long cacheBytes) {
            this.isActive = isActive;
            this.activeFileCount = activeFileCount;
            this.cacheBytes = cacheBytes;
        }
    }

    public TranscodeStatus getTranscodeStatus() {
        boolean active = false;
        int activeCount = 0;
        long totalBytes = 0;
        try {
            File transcodeDir = new File(TermuxConstants.TERMUX_HOME_DIR, ".cache/jellyfin/transcodes");
            if (transcodeDir.exists() && transcodeDir.isDirectory()) {
                File[] files = transcodeDir.listFiles();
                if (files != null) {
                    long now = System.currentTimeMillis();
                    for (File f : files) {
                        totalBytes += f.length();
                        if (now - f.lastModified() < 5000) {
                            active = true;
                            activeCount++;
                        }
                    }
                }
            }
        } catch (Throwable ignored) {}
        return new TranscodeStatus(active, activeCount, totalBytes);
    }

    public boolean isTranscodingActive() {
        return getTranscodeStatus().isActive;
    }

    /** Real-time server health and system resource sampler (ultra-lightweight, zero CPU pressure). */
    public ServerHealth getServerHealth() {
        boolean healthy = isHealthy();
        String status = healthy ? "Healthy" : (currentState == State.RUNNING ? "Responding" : currentState.name());
        long uptime = 0;
        if (serverStartTimeMs > 0 && (currentState == State.RUNNING || healthy)) {
            uptime = Math.max(0, (System.currentTimeMillis() - serverStartTimeMs) / 1000);
        }

        long totalKb = 0;
        long availKb = 0;
        try (BufferedReader br = new BufferedReader(new java.io.FileReader("/proc/meminfo"))) {
            String line;
            while ((line = br.readLine()) != null) {
                if (line.startsWith("MemTotal:")) {
                    totalKb = parseMemKb(line);
                } else if (line.startsWith("MemAvailable:")) {
                    availKb = parseMemKb(line);
                }
                if (totalKb > 0 && availKb > 0) break;
            }
        } catch (Throwable ignored) {}

        long totalMb = totalKb / 1024;
        long usedMb = Math.max(0, (totalKb - availKb) / 1024);
        int percent = totalMb > 0 ? (int) ((usedMb * 100) / totalMb) : 0;

        long storageFreeMb = 0;
        long storageTotalMb = 0;
        try {
            android.os.StatFs stat = new android.os.StatFs(TermuxConstants.TERMUX_FILES_DIR.getAbsolutePath());
            storageFreeMb = stat.getAvailableBytes() / (1024 * 1024);
            storageTotalMb = stat.getTotalBytes() / (1024 * 1024);
        } catch (Throwable ignored) {}

        TranscodeStatus ts = getTranscodeStatus();
        return new ServerHealth(healthy, status, uptime, totalMb, usedMb, percent, storageFreeMb, storageTotalMb, ts.isActive, ts.activeFileCount);
    }

    private static long parseMemKb(String line) {
        try {
            String[] parts = line.split("\\s+");
            if (parts.length >= 2) {
                return Long.parseLong(parts[1]);
            }
        } catch (Throwable ignored) {}
        return 0;
    }

    // ── Public accessors ───────────────────────────────────────────────────────
    public synchronized State getState()           { return currentState; }
    public synchronized StartupStage getStartupStage() { return currentStage; }
    public synchronized boolean isRunning()        { return currentState == State.RUNNING; }
    public synchronized Integer getLastExitCode()  { return lastExitCode; }
    public synchronized String  getLastError()     { return lastError; }
    public synchronized String  getBootstrapProgressMessage() { return bootstrapProgressMessage; }
    public synchronized int     getBootstrapProgressPercent() { return bootstrapProgressPercent; }
    public synchronized String getLogs() {
        StringBuilder sb = new StringBuilder();
        for (String line : liveLines) {
            sb.append(line).append('\n');
        }
        return sb.toString();
    }

    public synchronized String getPreservedErrorLogs() {
        if (preservedErrorLogs.isEmpty()) {
            return "--- No server errors recorded ---";
        }
        StringBuilder sb = new StringBuilder();
        for (String line : preservedErrorLogs) {
            sb.append(line).append('\n');
        }
        return sb.toString();
    }

    public synchronized int getPreservedErrorCount() {
        return preservedErrorLogs.size();
    }

    public synchronized int getRestartCount()  { return autoRestartCount.get(); }
    public synchronized long    getServerStartTimeMs() { return serverStartTimeMs; }

    public static long getAvailableInternalStorageMb() {
        try {
            StatFs stat = new StatFs(TermuxConstants.TERMUX_FILES_DIR.getAbsolutePath());
            return stat.getAvailableBytes() / (1024 * 1024);
        } catch (Throwable t) {
            return 1024;
        }
    }

    public static void recordKillEvent(Context ctx, String reason, int exitCode, long uptimeSeconds) {
        if (ctx == null) return;
        try {
            long freeStorage = getAvailableInternalStorageMb();
            long freeRam = 0;
            try (BufferedReader br = new BufferedReader(new java.io.FileReader("/proc/meminfo"))) {
                String line;
                while ((line = br.readLine()) != null) {
                    if (line.startsWith("MemAvailable:")) {
                        freeRam = parseMemKb(line) / 1024;
                        break;
                    }
                }
            } catch (Throwable ignored) {}

            SharedPreferences sp = ctx.getSharedPreferences("jellyfin_kill_history", Context.MODE_PRIVATE);
            String existing = sp.getString("events", "");
            long now = System.currentTimeMillis();
            String entry = now + "::" + reason + "::" + exitCode + "::" + uptimeSeconds + "::" + freeStorage + "::" + freeRam;
            String updated;
            if (existing.isEmpty()) {
                updated = entry;
            } else {
                String[] items = existing.split(";;;");
                StringBuilder sb = new StringBuilder(entry);
                int limit = Math.min(items.length, 9); // keep last 10
                for (int i = 0; i < limit; i++) {
                    sb.append(";;;").append(items[i]);
                }
                updated = sb.toString();
            }
            sp.edit().putString("events", updated).apply();
            Log.w(TAG, "Recorded kill event: " + entry);
        } catch (Throwable t) {
            Log.e(TAG, "Failed to record kill event", t);
        }
    }

    public static List<KillEvent> getKillHistory(Context ctx) {
        List<KillEvent> list = new ArrayList<>();
        if (ctx == null) return list;
        try {
            SharedPreferences sp = ctx.getSharedPreferences("jellyfin_kill_history", Context.MODE_PRIVATE);
            String raw = sp.getString("events", "");
            if (raw.isEmpty()) return list;
            String[] items = raw.split(";;;");
            for (String item : items) {
                String[] parts = item.split("::");
                if (parts.length >= 6) {
                    list.add(new KillEvent(
                            Long.parseLong(parts[0]),
                            parts[1],
                            Integer.parseInt(parts[2]),
                            Long.parseLong(parts[3]),
                            Long.parseLong(parts[4]),
                            Long.parseLong(parts[5])
                    ));
                }
            }
        } catch (Throwable ignored) {}
        return list;
    }

    public static void clearKillHistory(Context ctx) {
        if (ctx == null) return;
        ctx.getSharedPreferences("jellyfin_kill_history", Context.MODE_PRIVATE)
                .edit().clear().apply();
    }

    /**
     * §9: CLEAR DISPLAY only clears the UI log buffer.
     * Actual Jellyfin logs on disk are preserved.
     */
    public synchronized void clearDisplayedLogs() {
        liveLines.clear();
        preservedErrorLogs.clear();
        notifyListeners();
        notifyLogListeners();
    }

    public void addListener(Listener l) {
        listeners.add(l);
        l.onServerChanged(getState());
    }
    public void removeListener(Listener l) { listeners.remove(l); }

    public void addLogListener(LogListener l) { logListeners.add(l); }
    public void removeLogListener(LogListener l) { logListeners.remove(l); }

    /**
     * §18.5: Immediate server state hydration / reconciliation.
     * Synchronizes internal controller state with actual background process & HTTP endpoint status.
     * Runs asynchronously on command executor to avoid UI thread blocking.
     */
    public void reconcileStateAsync() {
        executor.execute(() -> {
            synchronized (JellyfinController.this) {
                if (currentState == State.STARTING || currentState == State.INITIALIZING || currentState == State.STOPPING) {
                    return; // Do not interfere with active transitions
                }
            }
            if (!JellyfinBootstrapper.isInitialized(null)) {
                synchronized (JellyfinController.this) {
                    if (currentState != State.INITIALIZING && currentState != State.STARTING) {
                        setStateLocked(State.UNINITIALIZED);
                    }
                }
                return;
            }
            boolean ready = isReady();
            synchronized (JellyfinController.this) {
                if (ready) {
                    if (currentState != State.RUNNING) {
                        appendLogLocked("State reconciled -> HTTP 200 JSON confirmed (SERVER RUNNING)");
                        setStateLocked(State.RUNNING);
                    }
                } else {
                    // Do not mark STOPPED if the process is actually running or starting
                    if (jellyfinProcess != null && jellyfinProcess.isAlive()) {
                        Log.d(TAG, "reconcileStateAsync: process is alive, waiting for HTTP readiness without marking STOPPED");
                        return;
                    }
                    if (currentState == State.UNINITIALIZED || currentState == State.RUNNING) {
                        setStateLocked(State.STOPPED);
                    }
                }
            }
        });
    }

    // ── Persistent data directory ──────────────────────────────────────────────

    /**
     * §7: Separate persistent Jellyfin data from the replaceable runtime.
     *
     * Runtime: $PREFIX/lib/jellyfin, $PREFIX/lib/dotnet, $PREFIX/opt/jellyfin
     *    → can be re-extracted on update
     *
     * Persistent: $FILES/home/.local/share/jellyfin
     *    → users, database, libraries, metadata, configuration, plugins
     *    → survives app restart, server restart, runtime re-bootstrap, APK update
     */
    private static File getProgramDataDir() {
        return new File(TermuxConstants.TERMUX_HOME_DIR, ".local/share/jellyfin");
    }

    // ── Commands ───────────────────────────────────────────────────────────────

    /** §1: Start with duplicate-process protection. Idempotent. */
    public void start(final Context context) {
        executor.execute(() -> {
            synchronized (JellyfinController.this) {
                if (currentState == State.RUNNING) {
                    appendLogLocked("SERVER ALREADY RUNNING — ignoring duplicate start");
                    return;
                }
                if (currentState == State.STARTING || currentState == State.INITIALIZING) {
                    appendLogLocked("Server is already starting — ignoring request");
                    return;
                }
                if (currentState == State.STOPPING) {
                    appendLogLocked("Server is stopping — ignoring start request");
                    return;
                }
                if (currentState == State.CRASH_LOOP) {
                    appendLogLocked("CRASH LOOP DETECTED — use RESET CRASH LOOP first");
                    return;
                }
                stopRequested = false;
                lastError = null;
                setStateLocked(State.INITIALIZING);
                appendLogLocked("Initializing Jellyfin runtime");
            }

            // §11: Port conflict pre-check
            if (isPortOccupied()) {
                synchronized (JellyfinController.this) {
                    lastError = "PORT 8096 UNAVAILABLE — another process is using it";
                    appendLogLocked("ERROR: " + lastError);
                    setStateLocked(State.FAILED);
                }
                return;
            }

            final Context appContext = context.getApplicationContext();
            boolean initialized = JellyfinBootstrapper.initializeIfNeeded(appContext, (msg, pct) -> {
                synchronized (JellyfinController.this) {
                    bootstrapProgressMessage = msg;
                    bootstrapProgressPercent = pct;
                    appendLogLocked("[BOOTSTRAP " + pct + "%] " + msg);
                }
                notifyBootstrapProgress(msg, pct);
            });

            if (!initialized) {
                synchronized (JellyfinController.this) {
                    String detail = JellyfinBootstrapper.getLastError();
                    lastError = (detail != null && !detail.isEmpty())
                            ? "Runtime initialization failed — " + detail
                            : "Runtime initialization failed — bootstrap error";
                    appendLogLocked("ERROR: " + lastError);
                    setStateLocked(State.FAILED);
                }
                return;
            }
            launchServer(appContext);
        });
    }

    /** Reinstall Jellyfin runtime from scratch without losing media, database, or settings. */
    public void reinstallRuntime(final Context context) {
        executor.execute(() -> {
            stopAndWait();
            waitForPortRelease();
            synchronized (JellyfinController.this) {
                lastError = null;
                setStateLocked(State.INITIALIZING);
                appendLogLocked("Reinstalling Jellyfin runtime from bundled packages...");
            }
            final Context appContext = context.getApplicationContext();
            boolean ok = JellyfinBootstrapper.reinstallRuntime(appContext, (msg, pct) -> {
                synchronized (JellyfinController.this) {
                    bootstrapProgressMessage = msg;
                    bootstrapProgressPercent = pct;
                    appendLogLocked("[REINSTALL " + pct + "%] " + msg);
                }
                notifyBootstrapProgress(msg, pct);
            });
            if (!ok) {
                synchronized (JellyfinController.this) {
                    String detail = JellyfinBootstrapper.getLastError();
                    lastError = (detail != null && !detail.isEmpty())
                            ? "Runtime reinstallation failed — " + detail
                            : "Runtime reinstallation failed";
                    appendLogLocked("ERROR: " + lastError);
                    setStateLocked(State.FAILED);
                }
                return;
            }
            launchServer(appContext);
        });
    }

    /** §1: Restart is stop + start. Idempotent. Resets crash-loop. */
    public void restart(final Context context) {
        executor.execute(() -> {
            synchronized (JellyfinController.this) {
                if (currentState == State.CRASH_LOOP) {
                    autoRestartCount.set(0);
                    appendLogLocked("Manual restart — crash loop counter reset");
                }
            }
            stopAndWait();
            waitForPortRelease();
            start(context);
        });
    }

    /** §1: Stop is idempotent. */
    public void stop() {
        executor.execute(() -> {
            synchronized (JellyfinController.this) {
                stopRequested = true;
            }
            stopAndWait();
        });
    }

    /** Reset crash-loop state and start. */
    public void resetCrashLoop(Context context) {
        executor.execute(() -> {
            synchronized (JellyfinController.this) {
                if (currentState == State.CRASH_LOOP) {
                    autoRestartCount.set(0);
                    appendLogLocked("Crash loop manually reset — ready to start");
                    setStateLocked(State.STOPPED);
                }
            }
            start(context);
        });
    }

    // ── Launch ─────────────────────────────────────────────────────────────────

    private void launchServer(Context ctx) {
        synchronized (this) {
            if (stopRequested) { setStateLocked(State.STOPPED); return; }
            setStateLocked(State.STARTING);
            appendLogLocked("Launching Jellyfin server process");
        }
        try {
            long freeStorageMb = getAvailableInternalStorageMb();
            if (freeStorageMb < 50) {
                synchronized (this) {
                    lastError = "STORAGE IS FULL (" + freeStorageMb + " MB free). Jellyfin requires at least 150 MB free space to launch. Clear cache or free device space to proceed.";
                    appendLogLocked("CRITICAL: " + lastError);
                    setStateLocked(State.FAILED);
                }
                recordKillEvent(ctx, "Storage Full Launch Abort (<50MB free)", 1, 0);
                return;
            }

            File prefixDir   = TermuxConstants.TERMUX_PREFIX_DIR;
            File dotnetBin   = new File(prefixDir, "lib/dotnet/dotnet");
            File jellyfinDll = new File(prefixDir, "lib/jellyfin/jellyfin.dll");
            File ffmpegBin   = new File(prefixDir, "opt/jellyfin/bin/ffmpeg");
            File dataDir     = getProgramDataDir();

            // §2: Verify runtime binaries exist
            if (!dotnetBin.exists()) {
                throw new RuntimeException("dotnet executable not found: " + dotnetBin.getAbsolutePath());
            }
            if (!jellyfinDll.exists()) {
                throw new RuntimeException("jellyfin.dll not found: " + jellyfinDll.getAbsolutePath());
            }
            if (!ffmpegBin.exists()) {
                throw new RuntimeException("ffmpeg not found: " + ffmpegBin.getAbsolutePath());
            }

            // §7: Ensure persistent data directory exists
            if (!dataDir.exists()) {
                dataDir.mkdirs();
            }

            File homeDir     = TermuxConstants.TERMUX_HOME_DIR;
            File configDir   = new File(homeDir, ".config/jellyfin");
            File cacheDir    = new File(homeDir, ".cache/jellyfin");
            File logDir      = new File(dataDir, "log");

            // Ensure all target directories exist
            if (!configDir.exists()) configDir.mkdirs();
            if (!cacheDir.exists()) cacheDir.mkdirs();
            if (!logDir.exists()) logDir.mkdirs();

            synchronized (this) {
                appendLogLocked("dotnet: " + dotnetBin.getAbsolutePath());
                appendLogLocked("jellyfin.dll: " + jellyfinDll.getAbsolutePath());
                appendLogLocked("ffmpeg: " + ffmpegBin.getAbsolutePath());
                appendLogLocked("DATA_DIR: " + dataDir.getAbsolutePath());
                appendLogLocked("CONFIG_DIR: " + configDir.getAbsolutePath());
                appendLogLocked("CACHE_DIR: " + cacheDir.getAbsolutePath());
                appendLogLocked("LOG_DIR: " + logDir.getAbsolutePath());
            }

            // §2: Direct ProcessBuilder, no shell invocation, explicit arguments
            ProcessBuilder pb = new ProcessBuilder(
                    dotnetBin.getAbsolutePath(),
                    jellyfinDll.getAbsolutePath(),
                    "--nonetchange",
                    "--ffmpeg", ffmpegBin.getAbsolutePath(),
                    "--datadir", dataDir.getAbsolutePath(),
                    "--configdir", configDir.getAbsolutePath(),
                    "--cachedir", cacheDir.getAbsolutePath(),
                    "--logdir", logDir.getAbsolutePath()
            );

            // §2: Set required environment variables every time
            Map<String, String> env = pb.environment();
            env.put("HOME", homeDir.getAbsolutePath());
            env.put("DOTNET_ROOT", new File(prefixDir, "lib/dotnet").getAbsolutePath());
            String existingPath = env.getOrDefault("PATH", "");
            env.put("PATH", new File(prefixDir, "lib/dotnet").getAbsolutePath()
                    + ":" + new File(prefixDir, "opt/jellyfin/bin").getAbsolutePath()
                    + ":" + new File(prefixDir, "bin").getAbsolutePath()
                    + (existingPath.isEmpty() ? "" : ":" + existingPath));

            // Phase 14: LD_LIBRARY_PATH must include $PREFIX/lib for native libs
            // (e_sqlite3.so, libSkiaSharp.so, etc.) and $PREFIX/opt/jellyfin/lib
            // for ffmpeg shared libraries (libavdevice, libavcodec, etc.)
            String libDir = new File(prefixDir, "lib").getAbsolutePath();
            String ffmpegLibDir = new File(prefixDir, "opt/jellyfin/lib").getAbsolutePath();
            String existingLdPath = env.getOrDefault("LD_LIBRARY_PATH", "");
            env.put("LD_LIBRARY_PATH", libDir + ":" + ffmpegLibDir
                    + (existingLdPath.isEmpty() ? "" : ":" + existingLdPath));

            // SSL CA Certificate bundle for .NET SslStream outbound HTTPS connections (TMDb, OMDb, etc.)
            File certFile = new File(prefixDir, "etc/tls/cert.pem");
            if (certFile.exists()) {
                env.put("SSL_CERT_FILE", certFile.getAbsolutePath());
                env.put("SSL_CERT_DIR", certFile.getParentFile().getAbsolutePath());
            }

            // §2: Merge stdout+stderr to prevent blocked pipe
            pb.redirectErrorStream(true);

            final Process process = pb.start();

            // §12: Clean up previous workers before creating new ones
            shutdownProcessWorkers();
            processWorkers = Executors.newFixedThreadPool(2);

            synchronized (this) { jellyfinProcess = process; }

            readOutput(process);
            monitorExit(process, ctx);
            startHealthCheck(process);

        } catch (Exception e) {
            synchronized (this) {
                lastError = "Failed to start Jellyfin: " + e.getMessage();
                appendLogLocked("ERROR: " + lastError);
                setStateLocked(State.FAILED);
            }
            Log.e(TAG, "Failed to start Jellyfin process", e);
        }
    }

    // ── Output reader (§2: capture stdout+stderr safely) ───────────────────────

    private void readOutput(final Process process) {
        processWorkers.execute(() -> {
            try (BufferedReader reader =
                         new BufferedReader(new InputStreamReader(process.getInputStream()))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    final String logLine = line;
                    synchronized (JellyfinController.this) { appendLogLocked(logLine); }
                    if (BuildConfig.DEBUG) {
                        Log.d("JellyfinOut", logLine);
                    }
                }
            } catch (Exception e) {
                Log.w(TAG, "Jellyfin output reader ended: " + e.getMessage());
            }
        });
    }

    // ── Exit monitor (§1: crash detection & differentiation) ───────────────────

    private void monitorExit(final Process process, final Context ctx) {
        processWorkers.execute(() -> {
            try {
                int exitCode = process.waitFor();
                synchronized (JellyfinController.this) {
                    if (process != jellyfinProcess) return; // stale
                    lastExitCode = exitCode;
                    jellyfinProcess = null;
                    cancelHealthCheckLocked();

                    long uptime = (serverStartTimeMs > 0) ? (System.currentTimeMillis() - serverStartTimeMs) / 1000 : 0;

                    if (stopRequested) {
                        appendLogLocked("Jellyfin stopped normally (exit " + exitCode + ")");
                        setStateLocked(State.STOPPED);
                    } else {
                        long freeMb = getAvailableInternalStorageMb();
                        boolean diskFull = freeMb < 50;
                        String diskLog = getDiskLogs().toLowerCase(Locale.ROOT);
                        if (diskLog.contains("database or disk is full") || diskLog.contains("sqlite_full")
                                || diskLog.contains("no space left") || diskLog.contains("not enough space")
                                || diskLog.contains("enospc")) {
                            diskFull = true;
                        }

                        if (diskFull) {
                            lastError = "STORAGE IS FULL — Device internal storage is full (" + freeMb + " MB free). SQLite database or disk is full.";
                            appendLogLocked("CRITICAL: " + lastError);
                            setStateLocked(State.FAILED);
                            recordKillEvent(ctx, "Storage Full (SQLite ENOSPC)", exitCode, uptime);
                        } else if (exitCode == 137 || exitCode == 143) {
                            lastError = "PROCESS KILLED BY OS (exit " + exitCode + " - SIGKILL/LMK)";
                            appendLogLocked("ERROR: " + lastError);
                            setStateLocked(State.CRASHED);
                            recordKillEvent(ctx, "Killed by Android OS (Low Memory Killer)", exitCode, uptime);
                        } else if (currentState == State.STARTING) {
                            lastError = "JELLYFIN FAILED TO START (exit " + exitCode + ")";
                            appendLogLocked("ERROR: " + lastError);
                            setStateLocked(State.FAILED);
                            recordKillEvent(ctx, "Startup Sequence Failure", exitCode, uptime);
                        } else {
                            lastError = "JELLYFIN CRASHED (exit " + exitCode + ")";
                            appendLogLocked("ERROR: " + lastError);
                            setStateLocked(State.CRASHED);
                            recordKillEvent(ctx, "Process Crash", exitCode, uptime);
                        }
                    }
                }

                // Crash recovery outside the lock
                if (!stopRequested) {
                    handleCrashRecovery(ctx);
                }

            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        });
    }

    // ── Health check (§3: sole RUNNING signal; stop polling once healthy) ──────

    private synchronized void startHealthCheck(final Process process) {
        cancelHealthCheckLocked();
        healthPollAttempt.set(0);
        healthCheckExecutor = Executors.newSingleThreadScheduledExecutor();
        healthCheckFuture = healthCheckExecutor.scheduleAtFixedRate(() -> {
            boolean ready = isReady();
            boolean listening = isPortListening(8096);
            int attempt = healthPollAttempt.incrementAndGet();
            synchronized (JellyfinController.this) {
                if (process != jellyfinProcess || stopRequested) return;
                if (!process.isAlive()) return; // exit monitor handles
                if (ready && currentState == State.STARTING) {
                    autoRestartCount.set(0); // successful start resets crash counter
                    appendLogLocked("Public System Info -> HTTP 200 JSON - server is RUNNING (READY)");
                    setStateLocked(State.RUNNING);
                    // §3/§12: Stop polling once healthy — avoid unnecessary background polling
                    cancelHealthCheckLocked();
                } else if (currentState == State.STARTING) {
                    if (listening) {
                        currentStage = StartupStage.CHECKING_READINESS;
                        if (attempt % 4 == 0) {
                            appendLogLocked("Port 8096 listening — database migration & startup in progress (attempt " + attempt + ")...");
                        }
                    } else {
                        currentStage = StartupStage.WAITING_FOR_SERVER;
                    }
                    notifyListeners();
                }
            }
        }, HEALTH_INITIAL_DELAY_S, HEALTH_PERIOD_S, TimeUnit.SECONDS);
    }

    // ── Stop (§1: kills process tree including child FFmpeg) ───────────────────

    private void stopAndWait() {
        Process process;
        synchronized (this) {
            stopRequested = true;
            cancelHealthCheckLocked();
            process = jellyfinProcess;
            if (process == null || !process.isAlive()) {
                jellyfinProcess = null;
                if (currentState != State.STOPPED) setStateLocked(State.STOPPED);
                return;
            }
            appendLogLocked("STOPPING — requesting process termination");
            setStateLocked(State.STOPPING);
        }

        // §1: destroy() sends SIGTERM to the process group,
        // which terminates Jellyfin and its child FFmpeg processes
        process.destroy();

        try {
            boolean exited = process.waitFor(STOP_TIMEOUT_S, TimeUnit.SECONDS);
            if (!exited) {
                synchronized (this) { appendLogLocked("Process did not exit in time — force killing"); }
                process.destroyForcibly();
                process.waitFor(3, TimeUnit.SECONDS);
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }

        synchronized (this) {
            jellyfinProcess = null;
            boolean portStillBound = isHealthy();
            appendLogLocked("Server stopped. Port 8096 released: " + !portStillBound);
            lastError = null;
            setStateLocked(State.STOPPED);
        }
    }

    /** Wait until port 8096 is released before restart. */
    private void waitForPortRelease() {
        long deadline = System.currentTimeMillis() + PORT_RELEASE_TIMEOUT_S * 1000L;
        while (System.currentTimeMillis() < deadline) {
            if (!isHealthy()) return;
            try { Thread.sleep(500); } catch (InterruptedException e) {
                Thread.currentThread().interrupt(); return;
            }
        }
        synchronized (this) { appendLogLocked("WARNING: port 8096 may still be in use"); }
    }

    // ── Crash recovery ─────────────────────────────────────────────────────────

    private void handleCrashRecovery(Context ctx) {
        if (getAvailableInternalStorageMb() < 50 || (lastError != null && lastError.contains("STORAGE IS FULL"))) {
            synchronized (this) {
                appendLogLocked("Halting auto-restart loop: Storage is full (" + getAvailableInternalStorageMb() + " MB free). Please clear cache or free device space.");
                setStateLocked(State.FAILED);
            }
            return;
        }

        int attempt = autoRestartCount.incrementAndGet();
        if (attempt > MAX_AUTO_RESTARTS) {
            synchronized (this) {
                lastError = "CRASH LOOP DETECTED — " + MAX_AUTO_RESTARTS
                        + " consecutive crashes. Auto-restart disabled.";
                appendLogLocked("ERROR: " + lastError);
                setStateLocked(State.CRASH_LOOP);
            }
            return;
        }

        synchronized (this) {
            appendLogLocked("Auto-restart attempt " + attempt + "/" + MAX_AUTO_RESTARTS);
        }

        // Exponential back-off: 3s, 6s, 9s
        try { Thread.sleep(3000L * attempt); } catch (InterruptedException e) {
            Thread.currentThread().interrupt(); return;
        }

        synchronized (this) {
            if (stopRequested) return;
            stopRequested = false;
        }

        launchServer(ctx);
    }

    // ── Port conflict detection ────────────────────────────────────────────────

    private boolean isPortOccupied() {
        if (jellyfinProcess != null && jellyfinProcess.isAlive()) {
            return false;
        }
        if (isReady()) {
            return false;
        }
        try (Socket socket = new Socket()) {
            socket.connect(new InetSocketAddress("127.0.0.1", 8096), 500);
            return true; // Connection succeeded, another application is holding port 8096
        } catch (Exception e) {
            return false; // Port is available
        }
    }

    public boolean isPortListening(int port) {
        try (Socket socket = new Socket()) {
            socket.connect(new InetSocketAddress("127.0.0.1", port), 500);
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    private boolean isReady() {
        HttpURLConnection conn = null;
        try {
            conn = (HttpURLConnection) new URL("http://127.0.0.1:8096/system/info/public").openConnection();
            conn.setConnectTimeout(2000);
            conn.setReadTimeout(2000);
            conn.setRequestMethod("GET");
            if (conn.getResponseCode() == 200) {
                String contentType = conn.getContentType();
                if (contentType != null && contentType.contains("application/json")) {
                    try (BufferedReader reader = new BufferedReader(new InputStreamReader(conn.getInputStream()))) {
                        StringBuilder sb = new StringBuilder();
                        String line;
                        while ((line = reader.readLine()) != null) {
                            sb.append(line);
                        }
                        String body = sb.toString();
                        return body.contains("\"ProductName\":\"Jellyfin\"") || body.toLowerCase().contains("jellyfin");
                    }
                }
            }
            return false;
        } catch (Exception e) {
            return false;
        } finally {
            if (conn != null) conn.disconnect();
        }
    }

    // ── Health check ───────────────────────────────────────────────────────────

    private boolean isHealthy() {
        HttpURLConnection conn = null;
        try {
            conn = (HttpURLConnection) new URL("http://127.0.0.1:8096/health").openConnection();
            conn.setConnectTimeout(2000);
            conn.setReadTimeout(2000);
            conn.setRequestMethod("GET");
            if (conn.getResponseCode() == 200) {
                try (BufferedReader reader = new BufferedReader(new InputStreamReader(conn.getInputStream()))) {
                    String line = reader.readLine();
                    return line != null && line.contains("Healthy");
                }
            }
            return false;
        } catch (Exception e) {
            return false;
        } finally {
            if (conn != null) conn.disconnect();
        }
    }

    /**
     * Read the latest Jellyfin server log file directly from disk ($DATA_DIR/log).
     * Provides immediate visibility into database migrations, SQLite errors, and startup details.
     */
    public synchronized String getDiskLogs() {
        File dataDir = getProgramDataDir();
        File logDir = new File(dataDir, "log");
        if (!logDir.exists() || !logDir.isDirectory()) return "";
        File[] files = logDir.listFiles((dir, name) -> name.endsWith(".log"));
        if (files == null || files.length == 0) return "";
        File latest = files[0];
        for (File f : files) {
            if (f.lastModified() > latest.lastModified()) {
                latest = f;
            }
        }
        try (java.io.RandomAccessFile raf = new java.io.RandomAccessFile(latest, "r")) {
            long len = raf.length();
            long start = Math.max(0, len - 32768);
            raf.seek(start);
            byte[] bytes = new byte[(int) (len - start)];
            raf.readFully(bytes);
            return new String(bytes, java.nio.charset.StandardCharsets.UTF_8);
        } catch (Throwable e) {
            return "Error reading disk log: " + e.getMessage();
        }
    }

    // ── Resource cleanup (§12) ─────────────────────────────────────────────────

    private void cancelHealthCheckLocked() {
        if (healthCheckFuture != null) {
            healthCheckFuture.cancel(false);
            healthCheckFuture = null;
        }
        if (healthCheckExecutor != null) {
            healthCheckExecutor.shutdownNow();
            healthCheckExecutor = null;
        }
    }

    private void shutdownProcessWorkers() {
        if (processWorkers != null) {
            processWorkers.shutdownNow();
            processWorkers = null;
        }
    }

    // ── Logging (§9) ───────────────────────────────────────────────────────────

    public synchronized void appendLog(String message) {
        appendLogLocked(message);
    }

    private void appendLogLocked(String message) {
        Log.i(TAG, message);
        String ts = sdf.format(new Date());
        String entry = ts + "  " + message;

        // Preserve errors so they are never lost even when 200 live lines scroll past
        String lower = message.toLowerCase(Locale.ROOT);
        if (lower.contains("[err]") || lower.contains("[error]") || lower.contains("[ftl]")
                || lower.contains("[fatal]") || lower.contains("exception") || lower.contains("error:")
                || lower.contains("sqlite_full") || lower.contains("database or disk is full")
                || lower.contains("no space left") || lower.contains("not enough space")
                || lower.contains("fail")) {
            preservedErrorLogs.add(entry);
            if (preservedErrorLogs.size() > MAX_PRESERVED_ERRORS) {
                preservedErrorLogs.remove(0);
            }
        }

        // Live log buffer (infinite accumulation, fully synchronized)
        liveLines.add(entry);

        notifyLogListeners();
    }

    private void notifyLogListeners() {
        for (LogListener l : logListeners) l.onLogAppended();
    }

    private void setStateLocked(State state) {
        currentState = state;
        switch (state) {
            case INITIALIZING:
                currentStage = StartupStage.STARTING_RUNTIME;
                break;
            case STARTING:
                currentStage = StartupStage.LAUNCHING_SERVER;
                break;
            case RUNNING:
                currentStage = StartupStage.READY;
                if (serverStartTimeMs == 0) {
                    serverStartTimeMs = System.currentTimeMillis();
                }
                break;
            case FAILED:
            case CRASHED:
            case CRASH_LOOP:
                currentStage = StartupStage.FAILED;
                serverStartTimeMs = 0;
                break;
            case STOPPED:
            case STOPPING:
            default:
                currentStage = StartupStage.NONE;
                serverStartTimeMs = 0;
                break;
        }
        Log.i(TAG, "State → " + state + " (Stage: " + currentStage + ")");
        notifyListeners();
    }

    private void notifyListeners() {
        for (Listener l : listeners) l.onServerChanged(currentState);
    }

    private void notifyBootstrapProgress(String message, int percent) {
        for (Listener l : listeners) {
            try {
                l.onBootstrapProgress(message, percent);
            } catch (Throwable ignored) {}
        }
    }
}
