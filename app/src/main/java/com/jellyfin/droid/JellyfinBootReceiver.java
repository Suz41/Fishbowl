package com.jellyfin.droid;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.util.Log;

/**
 * Phase 9 Step 7: Device Reboot → Auto-start
 *
 * Receives BOOT_COMPLETED and starts JellyfinServerService (foreground service)
 * only if the user has Auto-start enabled in Settings.
 *
 * Registered in AndroidManifest.xml with RECEIVE_BOOT_COMPLETED permission.
 */
public class JellyfinBootReceiver extends BroadcastReceiver {

    private static final String TAG = "JellyfinBootReceiver";

    @Override
    public void onReceive(Context context, Intent intent) {
        if (!Intent.ACTION_BOOT_COMPLETED.equals(intent.getAction())) return;

        SharedPreferences prefs =
                context.getSharedPreferences("jellyfindroid", Context.MODE_PRIVATE);
        boolean autoStart = prefs.getBoolean("auto_start", false);

        Log.i(TAG, "Boot completed — auto_start=" + autoStart);

        if (autoStart) {
            Log.i(TAG, "Auto-start is ON — starting JellyfinServerService");
            Intent serviceIntent = new Intent(context, JellyfinServerService.class);
            serviceIntent.setAction(JellyfinServerService.ACTION_START);
            try {
                context.startForegroundService(serviceIntent);
            } catch (Exception e) {
                Log.e(TAG, "Failed to start foreground service on boot", e);
            }
        }
    }
}
