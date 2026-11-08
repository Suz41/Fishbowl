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
        if ("transmission".equalsIgnoreCase(manifest.getId())) {
            File f = new File(TermuxConstants.TERMUX_PREFIX_DIR, "bin/transmission-daemon");
            return f.exists();
        }
        if ("seerr".equalsIgnoreCase(manifest.getId())) {
            File f = new File(manifest.getExecutableOrDllPath());
            File nodeBin = new File(TermuxConstants.TERMUX_PREFIX_DIR, "bin/node");
            return f.exists() && nodeBin.exists();
        }
        if ("bazarr".equalsIgnoreCase(manifest.getId())) {
            File f1 = manifest.getExecutableOrDllPath() != null ? new File(manifest.getExecutableOrDllPath()) : null;
            File home = TermuxConstants.TERMUX_HOME_DIR;
            File f2 = new File(home, "services/bazarr/main.py");
            File f3 = new File(home, "services/bazarr/bazarr/main.py");
            boolean fOk = (f1 != null && f1.exists()) || f2.exists() || f3.exists();
            File pyBin1 = new File(TermuxConstants.TERMUX_PREFIX_DIR, "bin/python3");
            File pyBin2 = new File(TermuxConstants.TERMUX_PREFIX_DIR, "bin/python");
            boolean pyOk = pyBin1.exists() || pyBin2.exists();
            return fOk && pyOk;
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
            if (f.exists()) return true;
        }
        if (manifest.getInstallDir() != null) {
            File dir = new File(manifest.getInstallDir());
            if (dir.exists() && dir.isDirectory()) {
                String id = manifest.getId();
                String cap = id.substring(0, 1).toUpperCase(java.util.Locale.ROOT) + id.substring(1);
                if (new File(dir, cap + ".dll").exists() || new File(dir, id + ".dll").exists() || new File(dir, id).exists()) {
                    return true;
                }
                if (hasExecutableOrDll(dir, id)) {
                    return true;
                }
            }
        }
        return false;
    }

    private static boolean hasExecutableOrDll(File dir, String id) {
        if (dir == null || !dir.exists() || !dir.isDirectory()) return false;
        String cap = id.substring(0, 1).toUpperCase(java.util.Locale.ROOT) + id.substring(1);
        File[] files = dir.listFiles();
        if (files == null) return false;
        for (File f : files) {
            if (f.isFile()) {
                String name = f.getName();
                if (name.equalsIgnoreCase(cap + ".dll") || name.equalsIgnoreCase(id + ".dll") || name.equalsIgnoreCase(id)) {
                    return true;
                }
            } else if (f.isDirectory() && !f.getName().startsWith(".")) {
                if (hasExecutableOrDll(f, id)) return true;
            }
        }
        return false;
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

    /**
     * Filters in-memory logs (and optionally disk log files) to return strictly errors,
     * crashes, critical warnings, and exception stack traces.
     */
    public synchronized String getErrorAndCrashLogs(int maxLines) {
        List<String> errorLines = new java.util.ArrayList<>();
        boolean inStackTrace = false;

        for (int i = 0; i < logs.size(); i++) {
            String line = logs.get(i);
            String lower = line.toLowerCase(java.util.Locale.ROOT);
            boolean isError = lower.contains("error") || lower.contains("crit") || lower.contains("fatal")
                    || lower.contains("crash") || lower.contains("exception") || lower.contains("fail")
                    || lower.contains("warn") || lower.contains("traceback") || lower.contains("sigkill")
                    || lower.contains("sigsegv") || lower.contains("unhandled") || lower.contains("exited unexpectedly")
                    || lower.contains("exit code:") || lower.contains("halted");

            if (isError) {
                errorLines.add(line);
                inStackTrace = lower.contains("exception") || lower.contains("traceback");
            } else if (inStackTrace && (line.startsWith("   at ") || line.startsWith("\tat ") || line.startsWith("  File "))) {
                errorLines.add(line);
            } else {
                inStackTrace = false;
            }
        }

        if (errorLines.isEmpty()) {
            if (lastError != null && !lastError.isEmpty()) {
                return "[LAST RECORDED ERROR]\n" + lastError;
            }
            return "";
        }

        int start = Math.max(0, errorLines.size() - maxLines);
        StringBuilder sb = new StringBuilder();
        if (lastError != null && !lastError.isEmpty()) {
            sb.append("[CURRENT ERROR STATE] ").append(lastError).append("\n\n");
        }
        for (int i = start; i < errorLines.size(); i++) {
            sb.append(errorLines.get(i)).append('\n');
        }
        return sb.toString();
    }

    public synchronized void clearLogs() {
        logs.clear();
        try {
            if (manifest != null && manifest.getLogDir() != null) {
                java.io.File logDir = new java.io.File(manifest.getLogDir());
                if (logDir.exists() && logDir.isDirectory()) {
                    java.io.File[] files = logDir.listFiles();
                    if (files != null) {
                        for (java.io.File f : files) {
                            if (f.isFile() && (f.getName().endsWith(".log") || f.getName().endsWith(".txt"))) {
                                try (java.io.FileOutputStream fos = new java.io.FileOutputStream(f)) {} catch (Throwable ignored) {}
                            }
                        }
                    }
                }
            }
        } catch (Throwable ignored) {}
    }
}
