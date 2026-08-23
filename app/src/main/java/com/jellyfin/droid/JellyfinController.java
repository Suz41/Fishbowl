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
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

public class JellyfinController {

    private static final String TAG = "JellyfinController";
    private static JellyfinController sInstance;

    public enum State {
        UNINITIALIZED,
        INITIALIZING,
        STOPPED,
        STARTING,
        RUNNING,
        FAILED,
        CRASHED
    }

    private State mState = State.UNINITIALIZED;
    private Process mJellyfinProcess;
    private StringBuilder mLogs = new StringBuilder();
    private ScheduledExecutorService mPoller;

    public static synchronized JellyfinController getInstance() {
        if (sInstance == null) {
            sInstance = new JellyfinController();
        }
        return sInstance;
    }

    public synchronized State getState() {
        return mState;
    }

    public synchronized String getLogs() {
        return mLogs.toString();
    }

    public synchronized boolean isRunning() {
        return mState == State.RUNNING;
    }

    public synchronized void start(final Context context) {
        if (mState == State.RUNNING || mState == State.STARTING || mState == State.INITIALIZING) {
            Log.i(TAG, "Jellyfin is already starting or running. Current state: " + mState);
            return;
        }

        mState = State.INITIALIZING;
        new Thread(() -> {
            boolean initialized = JellyfinBootstrapper.initializeIfNeeded(context);
            if (!initialized) {
                synchronized (JellyfinController.this) {
                    mState = State.FAILED;
                }
                Log.e(TAG, "Jellyfin initialization failed.");
                return;
            }

            synchronized (JellyfinController.this) {
                mState = State.STARTING;
            }

            try {
                File prefixDir = TermuxConstants.TERMUX_PREFIX_DIR;
                File dotnetBin = new File(prefixDir, "lib/dotnet/dotnet");
                File jellyfinDll = new File(prefixDir, "lib/jellyfin/jellyfin.dll");
                File ffmpegBin = new File(prefixDir, "opt/jellyfin/bin/ffmpeg");

                ProcessBuilder pb = new ProcessBuilder(
                        dotnetBin.getAbsolutePath(),
                        jellyfinDll.getAbsolutePath(),
                        "--ffmpeg", ffmpegBin.getAbsolutePath()
                );

                Map<String, String> env = pb.environment();
                env.put("DOTNET_ROOT", new File(prefixDir, "lib/dotnet").getAbsolutePath());
                env.put("DOTNET_SYSTEM_GLOBALIZATION_INVARIANT", "1");
                env.put("PATH", env.get("PATH") + ":" + new File(prefixDir, "lib/dotnet").getAbsolutePath());

                pb.redirectErrorStream(true);
                Log.i(TAG, "Launching Jellyfin server process...");
                mJellyfinProcess = pb.start();

                // Capture logs
                new Thread(() -> {
                    try (BufferedReader reader = new BufferedReader(new InputStreamReader(mJellyfinProcess.getInputStream()))) {
                        String line;
                        while ((line = reader.readLine()) != null) {
                            synchronized (JellyfinController.this) {
                                mLogs.append(line).append("\n");
                                if (mLogs.length() > 50000) {
                                    mLogs.delete(0, 10000);
                                }
                            }
                            Log.d("JellyfinOutput", line);
                        }
                    } catch (Exception e) {
                        Log.e(TAG, "Error reading Jellyfin process output", e);
                    }
                }).start();

                // Monitor process exit
                new Thread(() -> {
                    try {
                        int exitCode = mJellyfinProcess.waitFor();
                        Log.w(TAG, "Jellyfin process exited with code: " + exitCode);
                        synchronized (JellyfinController.this) {
                            if (mState != State.STOPPED) {
                                mState = State.CRASHED;
                            }
                        }
                        stopPolling();
                    } catch (InterruptedException ignored) {}
                }).start();

                startPollingHealth();

            } catch (Exception e) {
                Log.e(TAG, "Failed to start Jellyfin process", e);
                synchronized (JellyfinController.this) {
                    mState = State.FAILED;
                }
            }
        }).start();
    }

    private void startPollingHealth() {
        stopPolling();
        mPoller = Executors.newSingleThreadScheduledExecutor();
        mPoller.scheduleAtFixedRate(() -> {
            try {
                URL url = new URL("http://127.0.0.1:8096/health");
                HttpURLConnection conn = (HttpURLConnection) url.openConnection();
                conn.setConnectTimeout(2000);
                conn.setReadTimeout(2000);
                int code = conn.getResponseCode();
                if (code == 200) {
                    synchronized (JellyfinController.this) {
                        if (mState == State.STARTING) {
                            mState = State.RUNNING;
                            Log.i(TAG, "Jellyfin HTTP readiness confirmed! State -> RUNNING");
                        }
                    }
                    stopPolling();
                }
                conn.disconnect();
            } catch (Exception ignored) {}
        }, 1, 2, TimeUnit.SECONDS);
    }

    private synchronized void stopPolling() {
        if (mPoller != null && !mPoller.isShutdown()) {
            mPoller.shutdownNow();
            mPoller = null;
        }
    }

    public synchronized void stop() {
        mState = State.STOPPED;
        stopPolling();
        if (mJellyfinProcess != null) {
            mJellyfinProcess.destroy();
            mJellyfinProcess = null;
        }
        Log.i(TAG, "Jellyfin server stopped.");
    }
}
