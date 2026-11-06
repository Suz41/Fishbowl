package com.fishbowl.app.services;

import android.util.Log;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.SynchronousQueue;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Dedicated process-exit supervision engine providing guaranteed process termination observation
 * with non-blocking fallback mechanisms.
 *
 * Prevents main-thread blocking by never executing blocking process.waitFor() on caller threads,
 * and maintains continuous termination observation even when the primary worker pool is saturated.
 */
public class ProcessExitSupervisor {

    private static final String TAG = "ProcessExitSupervisor";

    public interface ExitCallback {
        void onProcessExit(int exitCode);
    }

    private static class MonitoredTarget {
        final Process process;
        final ExitCallback callback;

        MonitoredTarget(Process process, ExitCallback callback) {
            this.process = process;
            this.callback = callback;
        }
    }

    private final ThreadPoolExecutor primaryExecutor;
    private final ConcurrentHashMap<String, MonitoredTarget> fallbackTargets = new ConcurrentHashMap<>();
    private final Object fallbackSignal = new Object();
    private volatile boolean isRunning = true;
    private final Thread fallbackDaemonThread;

    public ProcessExitSupervisor() {
        this(4, 16);
    }

    public ProcessExitSupervisor(int corePoolSize, int maxPoolSize) {
        this.primaryExecutor = new ThreadPoolExecutor(
                corePoolSize,
                maxPoolSize,
                60L,
                TimeUnit.SECONDS,
                new SynchronousQueue<>(),
                new ThreadFactory() {
                    private final AtomicInteger count = new AtomicInteger(1);
                    @Override
                    public Thread newThread(Runnable r) {
                        Thread t = new Thread(r, "Fishbowl-ExitMonitor-" + count.getAndIncrement());
                        t.setDaemon(true);
                        return t;
                    }
                },
                new ThreadPoolExecutor.AbortPolicy()
        );

        this.fallbackDaemonThread = new Thread(this::runFallbackSupervisionLoop, "Fishbowl-FallbackExitSupervisor");
        this.fallbackDaemonThread.setDaemon(true);
        this.fallbackDaemonThread.start();
    }

    /**
     * Supervise process termination. Submits to primary bounded pool; if saturated,
     * immediately enrolls in the non-blocking fallback supervisor daemon.
     * Guaranteed to never block the calling thread or Android main thread.
     */
    public void supervise(final String serviceId, final Process process, final ExitCallback callback) {
        if (!isRunning || process == null || callback == null) {
            return;
        }

        try {
            primaryExecutor.execute(() -> {
                try {
                    int exitCode = process.waitFor();
                    fallbackTargets.remove(serviceId);
                    callback.onProcessExit(exitCode);
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                }
            });
        } catch (RejectedExecutionException rex) {
            // Primary pool is saturated: enroll in fallback supervisor without blocking caller
            Log.w(TAG, "[Supervision] Primary exit executor saturated. Enrolling " + serviceId + " in fallback daemon.");
            enrollInFallback(serviceId, process, callback);
        }
    }

    /**
     * Directly enrolls a process in the non-blocking fallback supervisor.
     */
    public void enrollInFallback(String serviceId, Process process, ExitCallback callback) {
        if (!isRunning || process == null || callback == null) {
            return;
        }
        fallbackTargets.put(serviceId, new MonitoredTarget(process, callback));
        synchronized (fallbackSignal) {
            fallbackSignal.notifyAll();
        }
    }

    public boolean unregister(String serviceId) {
        return fallbackTargets.remove(serviceId) != null;
    }

    public int getFallbackCount() {
        return fallbackTargets.size();
    }

    public int getActivePrimaryThreadCount() {
        return primaryExecutor.getActiveCount();
    }

    public ExecutorService getPrimaryExecutor() {
        return primaryExecutor;
    }

    private void runFallbackSupervisionLoop() {
        while (isRunning && !Thread.currentThread().isInterrupted()) {
            try {
                if (fallbackTargets.isEmpty()) {
                    synchronized (fallbackSignal) {
                        while (fallbackTargets.isEmpty() && isRunning) {
                            fallbackSignal.wait(5000);
                        }
                    }
                }
                if (!isRunning) break;

                for (Map.Entry<String, MonitoredTarget> entry : fallbackTargets.entrySet()) {
                    String id = entry.getKey();
                    MonitoredTarget target = entry.getValue();
                    try {
                        // exitValue() throws IllegalThreadStateException if the process is still running
                        int exitCode = target.process.exitValue();
                        fallbackTargets.remove(id);
                        target.callback.onProcessExit(exitCode);
                    } catch (IllegalThreadStateException alive) {
                        // Process is still active, continue monitoring
                    } catch (Throwable t) {
                        Log.e(TAG, "[Fallback] Error inspecting exit value for " + id, t);
                    }
                }

                Thread.sleep(200);
            } catch (InterruptedException ie) {
                break;
            }
        }
    }

    public void shutdown() {
        isRunning = false;
        primaryExecutor.shutdown();
        synchronized (fallbackSignal) {
            fallbackSignal.notifyAll();
        }
        fallbackDaemonThread.interrupt();
        fallbackTargets.clear();
    }
}
