package com.fishbowl.app;

import android.annotation.TargetApi;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.graphics.drawable.Icon;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.service.quicksettings.Tile;
import android.service.quicksettings.TileService;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.annotation.VisibleForTesting;

import com.termux.R;

/**
 * Native Android Quick Settings Tile for Fishbowl.
 *
 * Exactly ONE tile representing Fishbowl's Jellyfin media server.
 * - Branding: Fishbowl logo (no Jellyfin branding).
 * - Function: 1-tap start / stop toggle of the Jellyfin server.
 * - State synchronization: Live reflection of JellyfinController state.
 * - Long-press: Launches FishbowlActivity via QS_TILE_PREFERENCES.
 *
 * Supported Android Versions:
 * - Android 7.0+ (API 24+): Base TileService support
 * - Android 10+ (API 29+): Dynamic subtitles (setSubtitle)
 * - Android 11+ (API 30+): Accessibility state descriptions (setStateDescription)
 * - Android 12+ (API 31+): Background execution & foreground service launch exemption
 * - Android 13+ (API 33+): In-app tile addition request (requestAddTileService)
 */
@TargetApi(Build.VERSION_CODES.N)
public class FishbowlQuickSettingsTileService extends TileService implements JellyfinController.Listener {

    private static final String TAG = "FishbowlQSTile";

    public static final int STATE_UNAVAILABLE = 0; // Equivalent to Tile.STATE_UNAVAILABLE
    public static final int STATE_INACTIVE    = 1; // Equivalent to Tile.STATE_INACTIVE
    public static final int STATE_ACTIVE      = 2; // Equivalent to Tile.STATE_ACTIVE

    public static final long CLICK_DEBOUNCE_MS = 1000L;
    private static volatile long sLastClickTime = 0L;

    public enum DesiredAction {
        START,
        STOP,
        NONE
    }

    public static class TilePresentation {
        public final int state;
        public final String label;
        public final String subtitle;
        public final String contentDescription;

        public TilePresentation(int state, @NonNull String label, @NonNull String subtitle, @NonNull String contentDescription) {
            this.state = state;
            this.label = label;
            this.subtitle = subtitle;
            this.contentDescription = contentDescription;
        }
    }

    private final Handler mMainHandler = new Handler(Looper.getMainLooper());
    private boolean mIsListening = false;

    // -- Lifecycle -------------------------------------------------------------

    @Override
    public void onTileAdded() {
        super.onTileAdded();
        Log.i(TAG, "Quick Settings tile added to panel");
        JellyfinController controller = JellyfinController.getInstance();
        if (controller != null) {
            updateTileForState(controller.getState());
        } else {
            updateTileForState(JellyfinController.State.STOPPED);
        }
    }

    @Override
    public void onTileRemoved() {
        super.onTileRemoved();
        Log.i(TAG, "Quick Settings tile removed from panel");
        mIsListening = false;
        JellyfinController controller = JellyfinController.getInstance();
        if (controller != null) {
            controller.removeListener(this);
        }
    }

    @Override
    public void onStartListening() {
        super.onStartListening();
        mIsListening = true;
        Log.d(TAG, "Tile became visible in Quick Settings panel");

        JellyfinController controller = JellyfinController.getInstance();
        if (controller != null) {
            controller.addListener(this);
            updateTileForState(controller.getState());
        } else {
            updateTileForState(JellyfinController.State.STOPPED);
        }
    }

    @Override
    public void onStopListening() {
        super.onStopListening();
        mIsListening = false;
        Log.d(TAG, "Tile no longer visible in Quick Settings panel");

        JellyfinController controller = JellyfinController.getInstance();
        if (controller != null) {
            controller.removeListener(this);
        }
    }

    // -- Interaction -----------------------------------------------------------

    @Override
    public void onClick() {
        super.onClick();

        long now = SystemClock.elapsedRealtime();
        if (!isClickAllowed(now, sLastClickTime)) {
            Log.d(TAG, "Ignoring rapid repeated click (debounced)");
            return;
        }
        sLastClickTime = now;

        JellyfinController controller = JellyfinController.getInstance();
        if (controller == null) {
            Log.w(TAG, "JellyfinController is null; cannot process tile click");
            return;
        }

        JellyfinController.State currentState = controller.getState();
        DesiredAction action = resolveNextAction(currentState);
        Log.i(TAG, "Quick Settings tile tapped. Current server state: " + currentState + " -> Action: " + action);

        Tile tile = getQsTile();

        if (action == DesiredAction.STOP) {
            // Immediate transitional feedback
            if (tile != null) {
                applyTransitionalState(tile, "Stopping...", "Jellyfin server stopping.");
            }

            // Stop Jellyfin server via foreground service & controller
            Intent stopIntent = new Intent(this, JellyfinServerService.class);
            stopIntent.setAction(JellyfinServerService.ACTION_STOP_JELLYFIN);
            startService(stopIntent);
            controller.stop();

        } else if (action == DesiredAction.START) {
            // Immediate transitional feedback
            if (tile != null) {
                applyTransitionalState(tile, "Starting...", "Jellyfin server starting.");
            }

            // Start Jellyfin server via foreground service (ensures foreground wakelocks and notifications)
            Intent startIntent = new Intent(this, JellyfinServerService.class);
            startIntent.setAction(JellyfinServerService.ACTION_START_JELLYFIN);
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                startForegroundService(startIntent);
            } else {
                startService(startIntent);
            }
        } else {
            Log.d(TAG, "Server is currently transitional (" + currentState + "). Tap ignored.");
        }
    }

    // -- State Synchronization -------------------------------------------------

    @Override
    public void onServerChanged(JellyfinController.State state) {
        if (!mIsListening) return;
        mMainHandler.post(() -> updateTileForState(state));
    }

    private void applyTransitionalState(Tile tile, String subtitle, String contentDescription) {
        tile.setState(Tile.STATE_UNAVAILABLE);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            tile.setSubtitle(subtitle);
            tile.setContentDescription(contentDescription);
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            tile.setStateDescription(contentDescription);
        }
        try {
            tile.updateTile();
        } catch (Throwable t) {
            Log.w(TAG, "Failed to apply transitional state to tile: " + t.getMessage());
        }
    }

    public void updateTileForState(@Nullable JellyfinController.State state) {
        Tile tile = getQsTile();
        if (tile == null) {
            return;
        }

        TilePresentation presentation = computePresentation(state);

        tile.setState(presentation.state);
        tile.setLabel(presentation.label);
        tile.setIcon(Icon.createWithResource(this, R.drawable.ic_fishbowl_logo));

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            tile.setSubtitle(presentation.subtitle);
            tile.setContentDescription(presentation.contentDescription);
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            tile.setStateDescription(presentation.contentDescription);
        }

        try {
            tile.updateTile();
        } catch (Throwable t) {
            Log.w(TAG, "Failed to update QS tile: " + t.getMessage());
        }
    }

    // -- Pure Logic Helpers (Visible for Testing) ------------------------------

    @NonNull
    public static TilePresentation computePresentation(@Nullable JellyfinController.State state) {
        String label = "Jellyfin";
        if (state == null) {
            return new TilePresentation(STATE_INACTIVE, label, "Stopped", "Jellyfin server stopped. Tap to start.");
        }

        switch (state) {
            case RUNNING:
                return new TilePresentation(STATE_ACTIVE, label, "Running", "Jellyfin server running. Tap to stop.");

            case STARTING:
            case INITIALIZING:
                return new TilePresentation(STATE_UNAVAILABLE, label, "Starting...", "Jellyfin server starting.");

            case STOPPING:
                return new TilePresentation(STATE_UNAVAILABLE, label, "Stopping...", "Jellyfin server stopping.");

            case FAILED:
            case CRASHED:
            case CRASH_LOOP:
                return new TilePresentation(STATE_INACTIVE, label, "Error", "Jellyfin server error. Tap to restart.");

            case STOPPED:
            case UNINITIALIZED:
            default:
                return new TilePresentation(STATE_INACTIVE, label, "Stopped", "Jellyfin server stopped. Tap to start.");
        }
    }

    @NonNull
    public static DesiredAction resolveNextAction(@Nullable JellyfinController.State state) {
        if (state == null) {
            return DesiredAction.START;
        }
        switch (state) {
            case RUNNING:
                return DesiredAction.STOP;

            case STARTING:
            case INITIALIZING:
            case STOPPING:
                return DesiredAction.NONE; // Transitional; prevent race conditions

            case STOPPED:
            case FAILED:
            case CRASHED:
            case CRASH_LOOP:
            case UNINITIALIZED:
            default:
                return DesiredAction.START;
        }
    }

    public static boolean isClickAllowed(long now, long lastClickTime) {
        return (now - lastClickTime) >= CLICK_DEBOUNCE_MS;
    }

    // -- Static Invocation Helpers ---------------------------------------------

    /**
     * Broadcasts a tile update request to the Android System UI.
     * When the tile is visible in Quick Settings, System UI will invoke onStartListening().
     */
    public static void requestTileUpdate(@Nullable Context context) {
        if (context == null) return;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            try {
                requestListeningState(context.getApplicationContext(),
                        new ComponentName(context.getApplicationContext(), FishbowlQuickSettingsTileService.class));
            } catch (Throwable t) {
                Log.w(TAG, "Failed to request tile listening state: " + t.getMessage());
            }
        }
    }

    /**
     * Requests the operating system to prompt the user to add the Fishbowl tile
     * to their active Quick Settings panel (Android 13+ / API 33+).
     */
    public static void requestAddTile(@Nullable Context context,
                                      @Nullable Runnable onSuccess,
                                      @Nullable Runnable onFailure) {
        if (context == null) {
            if (onFailure != null) onFailure.run();
            return;
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            try {
                android.app.StatusBarManager sbm = context.getSystemService(android.app.StatusBarManager.class);
                if (sbm != null) {
                    ComponentName comp = new ComponentName(context, FishbowlQuickSettingsTileService.class);
                    sbm.requestAddTileService(
                            comp,
                            "Jellyfin",
                            Icon.createWithResource(context, R.drawable.ic_fishbowl_logo),
                            context.getMainExecutor(),
                            resultCode -> {
                                if (resultCode == android.app.StatusBarManager.TILE_ADD_REQUEST_RESULT_TILE_ALREADY_ADDED ||
                                    resultCode == android.app.StatusBarManager.TILE_ADD_REQUEST_RESULT_TILE_ADDED) {
                                    if (onSuccess != null) onSuccess.run();
                                } else {
                                    if (onFailure != null) onFailure.run();
                                }
                            }
                    );
                    return;
                }
            } catch (Throwable t) {
                Log.w(TAG, "StatusBarManager.requestAddTileService failed: " + t.getMessage());
            }
        }

        if (onFailure != null) {
            onFailure.run();
        }
    }
}
