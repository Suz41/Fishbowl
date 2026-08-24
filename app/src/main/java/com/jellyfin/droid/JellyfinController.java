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
    private static JellyfinController instance;

    public enum State {
        UNINITIALIZED,
        INITIALIZING,
        STOPPED,
        STARTING,
        RUNNING,
        FAILED,
        CRASHED
    }

    private State currentState = State.UNINITIALIZED;
    private Process jellyfinProcess;
    private ScheduledExecutorService healthCheckExecutor;

    public static synchronized JellyfinController getInstance() {
        if (instance == null) {
            instance = new JellyfinController();
        }
        return instance;
    }

    public synchronized State getState() {
        return currentState;
    }

    public synchronized void start(Context context) {
        if (currentState == State.RUNNING || currentState == State.STARTING || currentState == State.INITIALIZING) {
            Log.i(TAG, "Jellyfin is already running or starting.");
            return;
        }

        currentState = State.INITIALIZING;
        Executors.newSingleThreadExecutor().execute(() -> {
            boolean success = JellyfinBootstrapper.initializeIfNeeded(context);
            if (!success) {
                synchronized (JellyfinController.this) {
                    currentState = State.FAILED;
                }
                Log.e(TAG, "Jellyfin initialization failed.");
                return;
            }

            launchServer(context);
        });
    }

    private synchronized void launchServer(Context context) {
        currentState = State.STARTING;
        Log.i(TAG, "Launching Jellyfin server process...");

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
            env.put("PATH", env.get("PATH") + ":" + new File(prefixDir, "lib/dotnet").getAbsolutePath());
            env.put("DOTNET_SYSTEM_GLOBALIZATION_INVARIANT", "1");

            pb.redirectErrorStream(true);
            jellyfinProcess = pb.start();

            Executors.newSingleThreadExecutor().execute(() -> {
                try (BufferedReader reader = new BufferedReader(new InputStreamReader(jellyfinProcess.getInputStream()))) {
                    String line;
                    while ((line = reader.readLine()) != null) {
                        Log.d("JellyfinOutput", line);
                    }
                } catch (Exception e) {
                    Log.e(TAG, "Error reading Jellyfin output stream", e);
                }
            });

            Executors.newSingleThreadExecutor().execute(() -> {
                try {
                    int exitCode = jellyfinProcess.waitFor();
                    synchronized (JellyfinController.this) {
                        if (currentState == State.RUNNING || currentState == State.STARTING) {
                            currentState = State.CRASHED;
                            Log.w(TAG, "Jellyfin process exited unexpectedly with code: " + exitCode);
                        } else {
                            currentState = State.STOPPED;
                            Log.i(TAG, "Jellyfin process stopped with code: " + exitCode);
                        }
                    }
                    stopHealthCheck();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            });

            startHealthCheck();

        } catch (Exception e) {
            currentState = State.FAILED;
            Log.e(TAG, "Failed to start Jellyfin process", e);
        }
    }

    private void startHealthCheck() {
        stopHealthCheck();
        healthCheckExecutor = Executors.newSingleThreadScheduledExecutor();
        healthCheckExecutor.scheduleAtFixedRate(() -> {
            try {
                URL url = new URL("http://127.0.0.1:8096/health");
                HttpURLConnection connection = (HttpURLConnection) url.openConnection();
                connection.setConnectTimeout(2000);
                connection.setReadTimeout(2000);
                connection.setRequestMethod("GET");
                int responseCode = connection.getResponseCode();
                connection.disconnect();

                synchronized (JellyfinController.this) {
                    if (responseCode == 200) {
                        if (currentState != State.RUNNING) {
                            currentState = State.RUNNING;
                            Log.i(TAG, "Jellyfin is RUNNING and Healthy (200 OK)!");
                        }
                    }
                }
            } catch (Exception ignored) {
            }
        }, 3, 3, TimeUnit.SECONDS);
    }

    private void stopHealthCheck() {
        if (healthCheckExecutor != null && !healthCheckExecutor.isShutdown()) {
            healthCheckExecutor.shutdownNow();
            healthCheckExecutor = null;
        }
    }

    public synchronized void stop() {
        stopHealthCheck();
        if (jellyfinProcess != null && jellyfinProcess.isAlive()) {
            jellyfinProcess.destroy();
            jellyfinProcess = null;
        }
        currentState = State.STOPPED;
        Log.i(TAG, "Jellyfin service stopped.");
    }
}
