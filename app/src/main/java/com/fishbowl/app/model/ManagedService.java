package com.fishbowl.app.model;

import com.termux.shared.termux.TermuxConstants;

import java.io.File;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/**
 * Runtime state representation and log collector for an individual media service.
 */
public class ManagedService {

    private final ServiceManifest manifest;
    private ServiceState state = ServiceState.NOT_INSTALLED;
    private String lastError = null;
    private long startTimeMs = 0;
    private boolean isHealthy = false;
    private volatile boolean userStopped = false;
    private int crashCount = 0;
    private long lastCrashTime = 0;
    private final List<String> logs = new ArrayList<>();
    private final SimpleDateFormat sdf = new SimpleDateFormat("HH:mm:ss", Locale.getDefault());

    private volatile boolean enabled = true;
    private volatile boolean blocked = false;
    private String blockedReason = null;
    private long processPid = 0;

    public ManagedService(ServiceManifest manifest) {
        this.manifest = manifest;
        reconcileInstalledState();
    }

    public synchronized boolean isInstalled() {
        if ("jellyfin".equalsIgnoreCase(manifest.getId()) || "flaresolverr".equalsIgnoreCase(manifest.getId())) {
            return true;
        }
        if ("seerr".equalsIgnoreCase(manifest.getId())) {
            File f = new File(manifest.getExecutableOrDllPath());
            File nodeBin = new File(TermuxConstants.TERMUX_PREFIX_DIR, "bin/node");
            return f.exists() && nodeBin.exists();
        }
        if ("bazarr".equalsIgnoreCase(manifest.getId())) {
            File f = new File(manifest.getExecutableOrDllPath());
            File pyBin = new File(TermuxConstants.TERMUX_PREFIX_DIR, "bin/python3");
            return f.exists() && pyBin.exists();
        }
        if ("jackett".equalsIgnoreCase(manifest.getId())) {
            File fBin = new File(manifest.getInstallDir(), "jackett");
            File fDll = new File(manifest.getInstallDir(), "Jackett.dll");
            File fDllLower = new File(manifest.getInstallDir(), "jackett.dll");
            File fTarget = manifest.getExecutableOrDllPath() != null ? new File(manifest.getExecutableOrDllPath()) : null;
            return (fTarget != null && fTarget.exists()) || fBin.exists() || fDll.exists() || fDllLower.exists();
        }
        if (manifest.getExecutableOrDllPath() != null) {
            File f = new File(manifest.getExecutableOrDllPath());
            return f.exists();
        }
        File dir = new File(manifest.getInstallDir());
        return dir != null && dir.exists() && dir.isDirectory() && dir.list() != null && dir.list().length > 0;
    }

    public synchronized void reconcileInstalledState() {
        if (blocked) {
            state = ServiceState.BLOCKED;
            return;
        }
        if (!enabled) {
            state = ServiceState.DISABLED;
            return;
        }
        if (!isInstalled()) {
            state = ServiceState.NOT_INSTALLED;
            return;
        }
        if (state == ServiceState.RUNNING || state == ServiceState.API_READY ||
            state == ServiceState.SOCKET_READY || state == ServiceState.PROCESS_READY ||
            state == ServiceState.STARTING || state == ServiceState.STOPPING ||
            state == ServiceState.RECOVERING || state == ServiceState.CRASH_LOOP_PROTECTION) {
            return;
        }
        if (state == ServiceState.NOT_INSTALLED || state == ServiceState.DISABLED) {
            state = ServiceState.STOPPED;
        }
    }

    public ServiceManifest getManifest() { return manifest; }

    public synchronized ServiceState getState() { return state; }

    public synchronized void setState(ServiceState state) {
        this.state = state;
        if ((state == ServiceState.RUNNING || state == ServiceState.API_READY) && startTimeMs == 0) {
            this.startTimeMs = System.currentTimeMillis();
        } else if (state == ServiceState.STOPPED || state == ServiceState.FAILED ||
                   state == ServiceState.ERROR || state == ServiceState.NOT_INSTALLED ||
                   state == ServiceState.DISABLED || state == ServiceState.BLOCKED) {
            this.startTimeMs = 0;
            this.isHealthy = false;
            this.processPid = 0;
        }
    }

    public synchronized boolean isEnabled() { return enabled; }

    public synchronized void setEnabled(boolean enabled) {
        this.enabled = enabled;
        if (!enabled && !isRunning()) {
            this.state = ServiceState.DISABLED;
        } else if (enabled && this.state == ServiceState.DISABLED) {
            this.state = isInstalled() ? ServiceState.STOPPED : ServiceState.NOT_INSTALLED;
        }
    }

    public synchronized boolean isBlocked() { return blocked; }

    public synchronized void setBlocked(boolean blocked, String reason) {
        this.blocked = blocked;
        this.blockedReason = reason;
        if (blocked) {
            this.state = ServiceState.BLOCKED;
            this.lastError = reason;
        }
    }

    public synchronized String getBlockedReason() { return blockedReason; }

    public synchronized long getProcessPid() { return processPid; }

    public synchronized void setProcessPid(long pid) { this.processPid = pid; }

    public synchronized boolean isUserStopped() { return userStopped; }

    public synchronized void setUserStopped(boolean userStopped) { this.userStopped = userStopped; }

    public synchronized int getCrashCount() { return crashCount; }

    public synchronized void recordCrash() {
        long now = System.currentTimeMillis();
        // Reset count if previous crash was more than 60 seconds ago
        if (now - lastCrashTime > 60000) {
            crashCount = 0;
        }
        crashCount++;
        lastCrashTime = now;
    }

    public synchronized void resetCrashCount() {
        crashCount = 0;
        lastCrashTime = 0;
    }

    public synchronized boolean isReady() {
        return state == ServiceState.API_READY || state == ServiceState.RUNNING;
    }

    public synchronized boolean isStarting() {
        return state == ServiceState.STARTING || state == ServiceState.PROCESS_READY ||
               state == ServiceState.SOCKET_READY;
    }

    public synchronized String getLastError() { return lastError; }

    public synchronized void setLastError(String lastError) { this.lastError = lastError; }

    public synchronized boolean isHealthy() { return isHealthy; }

    public synchronized void setHealthy(boolean healthy) { this.isHealthy = healthy; }

    public synchronized long getUptimeSeconds() {
        if (startTimeMs > 0 && state == ServiceState.RUNNING) {
            return (System.currentTimeMillis() - startTimeMs) / 1000;
        }
        return 0;
    }

    public synchronized String getUptimeFormatted() {
        long sec = getUptimeSeconds();
        if (sec <= 0) return "--";
        long h = sec / 3600;
        long m = (sec % 3600) / 60;
        long s = sec % 60;
        if (h > 0) return String.format(java.util.Locale.US, "%dh %02dm", h, m);
        return String.format(java.util.Locale.US, "%dm %02ds", m, s);
    }

    public synchronized void appendLog(String message) {
        String ts = sdf.format(new Date());
        logs.add(ts + "  " + message);
    }

    public synchronized String getLogs() {
        StringBuilder sb = new StringBuilder();
        for (String line : logs) {
            sb.append(line).append('\n');
        }
        return sb.toString();
    }

    public String getId() { return manifest.getId(); }

    public String getDisplayName() { return manifest.getDisplayName(); }

    public synchronized boolean isRunning() {
        return state == ServiceState.RUNNING || state == ServiceState.API_READY;
    }

    public synchronized String getRecentLogs(int maxLines) {
        if (logs.isEmpty()) return "--- No logs recorded for " + manifest.getDisplayName() + " ---";
        int start = Math.max(0, logs.size() - maxLines);
        StringBuilder sb = new StringBuilder();
        for (int i = start; i < logs.size(); i++) {
            sb.append(logs.get(i)).append('\n');
        }
        return sb.toString();
    }

    public synchronized void clearLogs() {
        logs.clear();
    }
}
