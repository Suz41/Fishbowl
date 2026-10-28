package com.fishbowl.app;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.util.Log;

/**
 * Device Reboot Auto-start:
 * Permanently disabled per user requirement - phone reboot must NEVER start services.
 */
public class JellyfinBootReceiver extends BroadcastReceiver {

    private static final String TAG = "JellyfinBootReceiver";

    @Override
    public void onReceive(Context context, Intent intent) {
        Log.i(TAG, "Boot event ignored: auto-start on phone reboot is permanently disabled.");
    }
}
