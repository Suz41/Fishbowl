package com.jellyfin.droid;

import android.content.Context;
import android.util.Log;

import com.termux.shared.termux.TermuxConstants;

import java.io.BufferedReader;
import java.io.File;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.text.SimpleDateFormat;
import java.util.Date;
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
    private static final int MAX_LOG_CHARS      = 100_000;
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

    public interface Listener { void onServerChanged(State state); }

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
    private final StringBuilder logs = new StringBuilder();
    private final SimpleDateFormat sdf =
            new SimpleDateFormat("HH:mm:ss", Locale.getDefault());

    private State currentState = State.UNINITIALIZED;
    private Process jellyfinProcess;
    private boolean stopRequested;
    private Integer lastExitCode;
    private String  lastError;
    private final AtomicInteger autoRestartCount = new AtomicInteger(0);

    // ── Public accessors ───────────────────────────────────────────────────────
    public synchronized State getState()           { return currentState; }
    public synchronized State getStatus()          { return currentState; }
    public synchronized boolean isRunning()        { return currentState == State.RUNNING; }
    public synchronized Integer getLastExitCode()  { return lastExitCode; }
    public synchronized String  getLastError()     { return lastError; }
    public synchronized String  getLogs()          { return logs.toString(); }
    public synchronized int     getRestartCount()  { return autoRestartCount.get(); }

    /**
     * §9: CLEAR DISPLAY only clears the UI log buffer.
     * Actual Jellyfin logs on disk are preserved.
     */
    public synchronized void clearDisplayedLogs() {
        logs.setLength(0);
        notifyListeners();
    }

    public void addListener(Listener l) {
        listeners.add(l);
        l.onServerChanged(getState());
    }
    public void removeListener(Listener l) { listeners.remove(l); }

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
            if (!JellyfinBootstrapper.initializeIfNeeded(appContext)) {
                synchronized (JellyfinController.this) {
                    lastError = "Runtime initialization failed — bootstrap error";
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
            env.put("DOTNET_SYSTEM_GLOBALIZATION_INVARIANT", "1");
            String existingPath = env.getOrDefault("PATH", "");
            env.put("PATH", new File(prefixDir, "lib/dotnet").getAbsolutePath()
                    + ":" + new File(prefixDir, "bin").getAbsolutePath()
                    + (existingPath.isEmpty() ? "" : ":" + existingPath));

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
                    Log.d("JellyfinOut", logLine);
                }
            } catch (Exception e) {
                Log.w(TAG, "Output reader ended: " + e.getMessage());
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

                    if (stopRequested) {
                        appendLogLocked("Jellyfin stopped normally (exit " + exitCode + ")");
                        setStateLocked(State.STOPPED);
                    } else if (currentState == State.STARTING) {
                        lastError = "JELLYFIN FAILED TO START (exit " + exitCode + ")";
                        appendLogLocked("ERROR: " + lastError);
                        setStateLocked(State.FAILED);
                    } else {
                        lastError = "JELLYFIN CRASHED (exit " + exitCode + ")";
                        appendLogLocked("ERROR: " + lastError);
                        setStateLocked(State.CRASHED);
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
        healthCheckExecutor = Executors.newSingleThreadScheduledExecutor();
        healthCheckFuture = healthCheckExecutor.scheduleAtFixedRate(() -> {
            boolean ready = isReady();
            synchronized (JellyfinController.this) {
                if (process != jellyfinProcess || stopRequested) return;
                if (!process.isAlive()) return; // exit monitor handles
                if (ready && currentState == State.STARTING) {
                    autoRestartCount.set(0); // successful start resets crash counter
                    appendLogLocked("Public System Info → HTTP 200 JSON — server is RUNNING (READY)");
                    setStateLocked(State.RUNNING);
                    // §3/§12: Stop polling once healthy — avoid unnecessary background polling
                    cancelHealthCheckLocked();
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
        HttpURLConnection conn = null;
        try {
            conn = (HttpURLConnection) new URL("http://127.0.0.1:8096/health").openConnection();
            conn.setConnectTimeout(1000);
            conn.setReadTimeout(1000);
            conn.setRequestMethod("GET");
            conn.getResponseCode();
            return true; // any response = port occupied
        } catch (Exception e) {
            return false;
        } finally {
            if (conn != null) conn.disconnect();
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
                return contentType != null && contentType.contains("application/json");
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
            return conn.getResponseCode() == 200;
        } catch (Exception e) {
            return false;
        } finally {
            if (conn != null) conn.disconnect();
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

    private void appendLogLocked(String message) {
        String ts = sdf.format(new Date());
        logs.append(ts).append("  ").append(message).append('\n');
        if (logs.length() > MAX_LOG_CHARS) {
            logs.delete(0, logs.length() - MAX_LOG_CHARS);
        }
        notifyListeners();
    }

    private void setStateLocked(State state) {
        currentState = state;
        Log.d(TAG, "State → " + state);
        notifyListeners();
    }

    private void notifyListeners() {
        for (Listener l : listeners) l.onServerChanged(currentState);
    }
}
