package com.jellyfin.droid;

import android.content.Context;
import android.system.Os;
import android.util.Log;

import com.termux.shared.termux.TermuxConstants;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;

public class JellyfinBootstrapper {

    private static final String TAG = "JellyfinBootstrapper";
    private static final String INITIALIZED_MARKER_FILE = ".jellyfin_initialized_v10.11.11";
    private static final String[] ASSET_PARTS = {
            "jellyfin-bootstrap.tar.gz.part_aa",
            "jellyfin-bootstrap.tar.gz.part_ab"
    };

    public static synchronized boolean isInitialized(Context context) {
        File marker = new File(TermuxConstants.TERMUX_FILES_DIR_PATH, INITIALIZED_MARKER_FILE);
        File jellyfinDll = new File(TermuxConstants.TERMUX_PREFIX_DIR_PATH, "lib/jellyfin/jellyfin.dll");
        File dotnetBin = new File(TermuxConstants.TERMUX_PREFIX_DIR_PATH, "lib/dotnet/dotnet");
        return marker.exists() && jellyfinDll.exists() && dotnetBin.exists();
    }

    public static synchronized boolean initializeIfNeeded(Context context) {
        if (isInitialized(context)) {
            Log.i(TAG, "Jellyfin environment already initialized.");
            return true;
        }

        Log.i(TAG, "Initializing Jellyfin self-contained environment...");
        File marker = new File(TermuxConstants.TERMUX_FILES_DIR_PATH, INITIALIZED_MARKER_FILE);
        if (marker.exists()) {
            marker.delete();
        }

        File prefixDir = TermuxConstants.TERMUX_PREFIX_DIR;
        if (!prefixDir.exists()) {
            prefixDir.mkdirs();
        }

        try {
            File combinedTarGz = new File(context.getCacheDir(), "jellyfin-bootstrap.tar.gz");
            Log.i(TAG, "Recombining split bootstrap assets into cache...");
            try (OutputStream out = new FileOutputStream(combinedTarGz)) {
                byte[] buffer = new byte[65536];
                for (String part : ASSET_PARTS) {
                    try (InputStream in = context.getAssets().open(part)) {
                        int read;
                        while ((read = in.read(buffer)) != -1) {
                            out.write(buffer, 0, read);
                        }
                    }
                }
            }

            File tarBin = new File(prefixDir, "bin/tar");
            String tarCmd = tarBin.exists() ? tarBin.getAbsolutePath() : "tar";

            Log.i(TAG, "Unpacking combined bootstrap tar.gz into " + prefixDir.getAbsolutePath());
            Process process = new ProcessBuilder(tarCmd, "-x", "-z", "-f", combinedTarGz.getAbsolutePath(), "-C", prefixDir.getAbsolutePath())
                    .redirectErrorStream(true)
                    .start();
            
            int exitCode = process.waitFor();
            combinedTarGz.delete();

            if (exitCode != 0) {
                Log.e(TAG, "Tar.gz unpacking failed with exit code: " + exitCode);
                return false;
            }

            File dotnetBin = new File(prefixDir, "lib/dotnet/dotnet");
            if (dotnetBin.exists()) {
                Os.chmod(dotnetBin.getAbsolutePath(), 0755);
            }
            File ffmpegBin = new File(prefixDir, "opt/jellyfin/bin/ffmpeg");
            if (ffmpegBin.exists()) {
                Os.chmod(ffmpegBin.getAbsolutePath(), 0755);
            }

            marker.createNewFile();
            Log.i(TAG, "Jellyfin self-contained environment initialized successfully!");
            return true;

        } catch (Exception e) {
            Log.e(TAG, "Failed to initialize Jellyfin bootstrap environment", e);
            return false;
        }
    }
}
