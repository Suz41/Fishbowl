package com.jellyfin.droid;

import android.content.Context;
import android.util.Log;

import com.termux.shared.termux.TermuxConstants;

import java.io.BufferedReader;
import java.io.File;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArraySet;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/** Owns the single Jellyfin process launched by the existing Termux service. */
public class JellyfinController {
    private static final String TAG = "JellyfinController";
    private static final int MAX_LOG_CHARS = 80000;
    private static JellyfinController instance;

    public enum State { UNINITIALIZED, INITIALIZING, STOPPED, STARTING, RUNNING, FAILED, CRASHED }
    public interface Listener { void onServerChanged(State state); }

    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    private final CopyOnWriteArraySet<Listener> listeners = new CopyOnWriteArraySet<>();
    private final StringBuilder logs = new StringBuilder();
    private State currentState = State.UNINITIALIZED;
    private Process jellyfinProcess;
    private ScheduledExecutorService healthCheckExecutor;
    private boolean stopRequested;
    private Integer lastExitCode;

    public static synchronized JellyfinController getInstance() {
        if (instance == null) instance = new JellyfinController();
        return instance;
    }

    public synchronized State getStatus() { return currentState; }
    public synchronized State getState() { return currentState; }
    public synchronized boolean isRunning() { return currentState == State.RUNNING && jellyfinProcess != null && jellyfinProcess.isAlive(); }
    public synchronized Integer getLastExitCode() { return lastExitCode; }
    public synchronized String getLogs() { return logs.toString(); }
    public synchronized void clearDisplayedLogs() { logs.setLength(0); notifyListeners(); }
    public void addListener(Listener listener) { listeners.add(listener); listener.onServerChanged(getStatus()); }
    public void removeListener(Listener listener) { listeners.remove(listener); }

    public void start(final Context context) {
        synchronized (this) {
            if (currentState == State.RUNNING || currentState == State.STARTING || currentState == State.INITIALIZING) return;
            stopRequested = false;
            setStateLocked(State.INITIALIZING);
            appendLogLocked("Initializing Jellyfin runtime");
        }
        final Context appContext = context.getApplicationContext();
        executor.execute(() -> {
            if (!JellyfinBootstrapper.initializeIfNeeded(appContext)) {
                synchronized (JellyfinController.this) { appendLogLocked("Runtime initialization failed"); setStateLocked(State.FAILED); }
                return;
            }
            launchServer();
        });
    }

    public void restart(final Context context) {
        executor.execute(() -> { stopAndWait(); start(context); });
    }
    public void stop() { executor.execute(this::stopAndWait); }

    private void launchServer() {
        synchronized (this) {
            if (stopRequested) { setStateLocked(State.STOPPED); return; }
            setStateLocked(State.STARTING);
            appendLogLocked("Launching Jellyfin server process");
        }
        try {
            File prefixDir = TermuxConstants.TERMUX_PREFIX_DIR;
            File dotnetBin = new File(prefixDir, "lib/dotnet/dotnet");
            File jellyfinDll = new File(prefixDir, "lib/jellyfin/jellyfin.dll");
            File ffmpegBin = new File(prefixDir, "opt/jellyfin/bin/ffmpeg");
            ProcessBuilder pb = new ProcessBuilder(dotnetBin.getAbsolutePath(), jellyfinDll.getAbsolutePath(), "--ffmpeg", ffmpegBin.getAbsolutePath());
            Map<String, String> env = pb.environment();
            env.put("DOTNET_ROOT", new File(prefixDir, "lib/dotnet").getAbsolutePath());
            env.put("DOTNET_SYSTEM_GLOBALIZATION_INVARIANT", "1");
            env.put("PATH", env.get("PATH") + ":" + new File(prefixDir, "lib/dotnet").getAbsolutePath());
            pb.redirectErrorStream(true);
            final Process process = pb.start();
            synchronized (this) { jellyfinProcess = process; }
            readOutput(process);
            monitorExit(process);
            startHealthCheck(process);
        } catch (Exception e) {
            synchronized (this) { appendLogLocked("Failed to start Jellyfin: " + e.getMessage()); setStateLocked(State.FAILED); }
            Log.e(TAG, "Failed to start Jellyfin process", e);
        }
    }

    private void readOutput(final Process process) {
        Executors.newSingleThreadExecutor().execute(() -> {
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream()))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    synchronized (JellyfinController.this) { appendLogLocked(line); }
                    Log.d("JellyfinOutput", line);
                }
            } catch (Exception e) { Log.w(TAG, "Jellyfin output reader ended", e); }
        });
    }

    private void monitorExit(final Process process) {
        Executors.newSingleThreadExecutor().execute(() -> {
            try {
                int exitCode = process.waitFor();
                synchronized (JellyfinController.this) {
                    if (process != jellyfinProcess) return;
                    lastExitCode = exitCode;
                    jellyfinProcess = null;
                    stopHealthCheckLocked();
                    appendLogLocked("Jellyfin process exited with code " + exitCode);
                    setStateLocked(stopRequested ? State.STOPPED : State.CRASHED);
                }
            } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
        });
    }

    private synchronized void startHealthCheck(final Process process) {
        stopHealthCheckLocked();
        healthCheckExecutor = Executors.newSingleThreadScheduledExecutor();
        healthCheckExecutor.scheduleAtFixedRate(() -> {
            boolean healthy = isHealthy();
            synchronized (JellyfinController.this) {
                if (process != jellyfinProcess || stopRequested) return;
                if (process.isAlive() && healthy && currentState == State.STARTING) {
                    appendLogLocked("Health endpoint returned HTTP 200 Healthy");
                    setStateLocked(State.RUNNING);
                }
            }
        }, 1, 2, TimeUnit.SECONDS);
    }

    private void stopAndWait() {
        Process process;
        synchronized (this) {
            stopRequested = true;
            stopHealthCheckLocked();
            process = jellyfinProcess;
            if (process == null || !process.isAlive()) { jellyfinProcess = null; setStateLocked(State.STOPPED); return; }
            appendLogLocked("Stopping Jellyfin server");
            process.destroy();
        }
        try { process.waitFor(15, TimeUnit.SECONDS); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
        synchronized (this) {
            if (process.isAlive()) process.destroyForcibly();
            jellyfinProcess = null;
            appendLogLocked("Jellyfin server stopped; health endpoint available=" + isHealthy());
            setStateLocked(State.STOPPED);
        }
    }

    private boolean isHealthy() {
        HttpURLConnection connection = null;
        try {
            connection = (HttpURLConnection) new URL("http://127.0.0.1:8096/health").openConnection();
            connection.setConnectTimeout(2000); connection.setReadTimeout(2000); connection.setRequestMethod("GET");
            return connection.getResponseCode() == 200;
        } catch (Exception e) {
            Log.d(TAG, "Health endpoint is not ready: " + e.getMessage());
            return false;
        }
        finally { if (connection != null) connection.disconnect(); }
    }

    private void stopHealthCheckLocked() {
        if (healthCheckExecutor != null) { healthCheckExecutor.shutdownNow(); healthCheckExecutor = null; }
    }
    private void appendLogLocked(String message) {
        logs.append(System.currentTimeMillis()).append("  ").append(message).append('\n');
        if (logs.length() > MAX_LOG_CHARS) logs.delete(0, logs.length() - MAX_LOG_CHARS);
        notifyListeners();
    }
    private void setStateLocked(State state) { currentState = state; notifyListeners(); }
    private void notifyListeners() { for (Listener listener : listeners) listener.onServerChanged(currentState); }
}
