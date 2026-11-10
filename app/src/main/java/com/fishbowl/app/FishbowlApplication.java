package com.fishbowl.app;

import android.content.Context;
import android.util.Log;

import com.termux.app.TermuxApplication;

/**
 * Native Android Application class for FishBowl Media Server.
 *
 * Coordinates standalone application lifecycle, background runtime preparation,
 * external media storage initialization (/storage/emulated/0/FishbowlMedia),
 * and non-root execution environment safety.
 */
public class FishbowlApplication extends TermuxApplication {

    private static final String TAG = "FishbowlApplication";
    private static FishbowlApplication sInstance;

    public static FishbowlApplication getInstance() {
        return sInstance;
    }

    @Override
    public void onCreate() {
        sInstance = this;
        super.onCreate();

        Context context = getApplicationContext();
        Log.i(TAG, "Initializing FishBowl standalone media server application");

        try {
            // Scaffold and ensure core unified external storage paths
            MediaStorageManager.getInstance(context).initializeOnLaunch(context);
        } catch (Exception e) {
            Log.e(TAG, "Failed to initialize unified FishbowlMedia storage directories", e);
        }
    }
}
