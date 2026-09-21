package com.fishbowl.app;

import android.content.Context;
import android.system.Os;
import android.util.Log;

import com.termux.shared.termux.TermuxConstants;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.zip.GZIPInputStream;

import android.os.StatFs;
import org.apache.commons.compress.archivers.tar.TarArchiveEntry;
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream;

public class JellyfinBootstrapper {

    private static final String TAG = "JellyfinBootstrapper";
    private static final String INITIALIZED_MARKER_FILE = ".jellyfin_initialized_v12.1.0";
    private static final String[] ASSET_PARTS = {
            "jellyfin-bootstrap.tar.gz.part_aa",
            "jellyfin-bootstrap.tar.gz.part_ab",
            "jellyfin-bootstrap.tar.gz.part_ac",
            "jellyfin-bootstrap.tar.gz.part_ad",
            "jellyfin-bootstrap.tar.gz.part_ae"
    };
    private static final long MIN_REQUIRED_BYTES = 1000L * 1024L * 1024L; // ~1.0 GB minimum required free space
    private static volatile String lastError = null;

    public interface ProgressCallback {
        void onProgress(String statusMessage, int percent);
    }

    public static String getLastError() {
        return lastError;
    }

    private static void setLastError(String error) {
        lastError = error;
    }

    private static String formatSize(long bytes) {
        if (bytes < 1024) return bytes + " B";
        int z = (63 - Long.numberOfLeadingZeros(bytes)) / 10;
        return String.format(java.util.Locale.US, "%.1f %cB", (double) bytes / (1L << (z * 10)), " KMGTPE".charAt(z));
    }

    private static final Object EXTRACT_LOCK = new Object();
    private static volatile Boolean isInitializedCached = null;

    public static boolean isInitialized(Context context) {
        if (isInitializedCached != null && isInitializedCached) {
            return true;
        }
        File marker = new File(TermuxConstants.TERMUX_FILES_DIR, INITIALIZED_MARKER_FILE);
        File jellyfinDll = new File(TermuxConstants.TERMUX_PREFIX_DIR, "lib/jellyfin/jellyfin.dll");
        File dotnetBin = new File(TermuxConstants.TERMUX_PREFIX_DIR, "lib/dotnet/dotnet");
        File ffmpegBin = new File(TermuxConstants.TERMUX_PREFIX_DIR, "opt/jellyfin/bin/ffmpeg");
        boolean ready = marker.exists() && jellyfinDll.exists() && dotnetBin.exists() && ffmpegBin.exists();
        if (ready) {
            isInitializedCached = true;
        }
        return ready;
    }

    /**
     * Completely purges the replaceable runtime files and marker to allow a clean reinstall.
     * Note: Does NOT touch persistent user data in ~/.local/share/jellyfin or configs in ~/.config/jellyfin.
     */
    public static boolean reinstallRuntime(Context context, ProgressCallback callback) {
        synchronized (EXTRACT_LOCK) {
            isInitializedCached = false;
            lastError = null;
            if (callback != null) {
                callback.onProgress("Clearing previous runtime...", 0);
            }
            File marker = new File(TermuxConstants.TERMUX_FILES_DIR, INITIALIZED_MARKER_FILE);
            if (marker.exists()) {
                marker.delete();
            }
            File legacyMarker = new File(TermuxConstants.TERMUX_FILES_DIR, ".jellyfin_initialized_v10.11.11");
            if (legacyMarker.exists()) {
                legacyMarker.delete();
            }
            File prefixDir = TermuxConstants.TERMUX_PREFIX_DIR;
            if (prefixDir.exists()) {
                deleteRecursively(prefixDir);
            }
            File cacheTarGz = new File(context.getCacheDir(), "jellyfin-bootstrap.tar.gz");
            if (cacheTarGz.exists()) {
                cacheTarGz.delete();
            }
            return initializeInternal(context, callback);
        }
    }

    private static boolean deleteRecursively(File fileOrDir) {
        if (fileOrDir.isDirectory()) {
            File[] children = fileOrDir.listFiles();
            if (children != null) {
                for (File child : children) {
                    deleteRecursively(child);
                }
            }
        }
        return fileOrDir.delete();
    }

    /**
     * Phase 14: Cross-package migration is NOT possible due to Android UID sandbox isolation.
     * /data/data/com.termux/ (UID u0_a1598) cannot be read by com.fishbowl.app (UID u0_a1599).
     * JellyfinDroid starts with a fresh independent server instance under its own sandbox.
     * Users must reconfigure their Jellyfin server (add libraries, create accounts, etc.).
     */

    public static void ensureNetworkConfig(Context context) {
        try {
            File etcDir = new File(TermuxConstants.TERMUX_PREFIX_DIR, "etc");
            if (!etcDir.exists()) etcDir.mkdirs();

            File hostsFile = new File(etcDir, "hosts");
            String hostsContent = "127.0.0.1 localhost\n::1 ip6-localhost\n";
            try (FileOutputStream out = new FileOutputStream(hostsFile)) {
                out.write(hostsContent.getBytes());
            }

            File resolvFile = new File(etcDir, "resolv.conf");
            String resolvContent = "nameserver 8.8.8.8\nnameserver 1.1.1.1\n";
            try (FileOutputStream out = new FileOutputStream(resolvFile)) {
                out.write(resolvContent.getBytes());
            }

            // Ensure Jellyfin network configuration explicitly enables binding across all interfaces (0.0.0.0)
            ensureJellyfinNetworkXml();
            ensureStorageSymlinks();
        } catch (Exception e) {
            Log.e(TAG, "Failed to write network config: " + e.getMessage(), e);
        }
    }

    public static void ensureStorageSymlinks() {
        try {
            File homeDir = TermuxConstants.TERMUX_HOME_DIR;
            if (!homeDir.exists()) homeDir.mkdirs();

            // 1. Symlink /storage/emulated/0 to ~/storage/shared
            File storageDir = new File(homeDir, "storage");
            if (!storageDir.exists()) storageDir.mkdirs();

            File sharedLink = new File(storageDir, "shared");
            if (!sharedLink.exists()) {
                try {
                    Os.symlink("/storage/emulated/0", sharedLink.getAbsolutePath());
                    Log.i(TAG, "Created storage symlink ~/storage/shared -> /storage/emulated/0");
                } catch (Exception e) {
                    Log.w(TAG, "Failed to create ~/storage/shared symlink: " + e.getMessage());
                }
            }

            // 2. Symlink /storage to ~/storage/external (for SD cards / USB OTG)
            File extLink = new File(storageDir, "external");
            if (!extLink.exists()) {
                try {
                    Os.symlink("/storage", extLink.getAbsolutePath());
                    Log.i(TAG, "Created storage symlink ~/storage/external -> /storage");
                } catch (Exception e) {
                    Log.w(TAG, "Failed to create ~/storage/external symlink: " + e.getMessage());
                }
            }
        } catch (Exception e) {
            Log.e(TAG, "Failed to create storage symlinks: " + e.getMessage(), e);
        }
    }

    public static void ensureJellyfinNetworkXml() {
        try {
            File configDir = new File(TermuxConstants.TERMUX_HOME_DIR, ".config/jellyfin");
            if (!configDir.exists()) configDir.mkdirs();

            File networkXml = new File(configDir, "network.xml");
            String xmlContent = "<?xml version=\"1.0\" encoding=\"utf-8\"?>\n"
                    + "<NetworkConfiguration xmlns:xsi=\"http://www.w3.org/2001/XMLSchema-instance\" xmlns:xsd=\"http://www.w3.org/2001/XMLSchema\">\n"
                    + "  <RequireHttps>false</RequireHttps>\n"
                    + "  <InternalHttpPort>8096</InternalHttpPort>\n"
                    + "  <InternalHttpsPort>8920</InternalHttpsPort>\n"
                    + "  <PublicHttpPort>8096</PublicHttpPort>\n"
                    + "  <PublicHttpsPort>8920</PublicHttpsPort>\n"
                    + "  <AutoRunWebApp>true</AutoRunWebApp>\n"
                    + "  <EnableRemoteAccess>true</EnableRemoteAccess>\n"
                    + "  <LocalNetworkAddresses />\n"
                    + "  <LocalNetworkSubnets />\n"
                    + "  <KnownProxies />\n"
                    + "  <IgnoreVirtualInterfaces>false</IgnoreVirtualInterfaces>\n"
                    + "  <VirtualInterfaceNames />\n"
                    + "  <EnablePublishedServerUriByRequest>true</EnablePublishedServerUriByRequest>\n"
                    + "</NetworkConfiguration>";
            try (FileOutputStream out = new FileOutputStream(networkXml)) {
                out.write(xmlContent.getBytes());
            }
            Log.i(TAG, "Written network.xml enabling remote access and virtual interface binding.");
        } catch (Exception e) {
            Log.e(TAG, "Failed to write network.xml: " + e.getMessage(), e);
        }
    }

    public static boolean initializeIfNeeded(Context context) {
        return initializeIfNeeded(context, null);
    }

    public static boolean initializeIfNeeded(Context context, ProgressCallback callback) {
        synchronized (EXTRACT_LOCK) {
            return initializeInternal(context, callback);
        }
    }

    private static boolean initializeInternal(Context context, ProgressCallback callback) {
        lastError = null;
        ensureNetworkConfig(context);
        if (isInitialized(context)) {
            Log.i(TAG, "Jellyfin environment already initialized.");
            if (callback != null) {
                callback.onProgress("Runtime verified and ready", 100);
            }
            return true;
        }

        File filesDir = TermuxConstants.TERMUX_FILES_DIR;
        try {
            if (!filesDir.exists()) {
                filesDir.mkdirs();
            }
            StatFs stat = new StatFs(filesDir.getAbsolutePath());
            long availableBytes = stat.getAvailableBytes();
            Log.i(TAG, "Available internal storage: " + formatSize(availableBytes));
            if (availableBytes < MIN_REQUIRED_BYTES) {
                String err = "Insufficient internal storage: " + formatSize(availableBytes)
                        + " free, but at least " + formatSize(MIN_REQUIRED_BYTES) + " is required to unpack Jellyfin runtime.";
                Log.e(TAG, err);
                setLastError(err);
                if (callback != null) {
                    callback.onProgress(err, 0);
                }
                return false;
            }
        } catch (Exception e) {
            Log.w(TAG, "Unable to inspect available storage: " + e.getMessage());
        }

        if (callback != null) {
            callback.onProgress("Recombining runtime packages...", 10);
        }
        Log.i(TAG, "Initializing Jellyfin self-contained environment...");
        File marker = new File(TermuxConstants.TERMUX_FILES_DIR, INITIALIZED_MARKER_FILE);
        if (marker.exists()) {
            marker.delete();
        }
        File legacyMarker = new File(TermuxConstants.TERMUX_FILES_DIR, ".jellyfin_initialized_v10.11.11");
        if (legacyMarker.exists()) {
            legacyMarker.delete();
        }

        File prefixDir = TermuxConstants.TERMUX_PREFIX_DIR;
        if (!prefixDir.exists()) {
            prefixDir.mkdirs();
        }

        File cacheTarGz = new File(context.getCacheDir(), "jellyfin-bootstrap.tar.gz");
        try {
            Log.i(TAG, "Recombining split bootstrap assets into cache...");
            List<String> partFiles = new ArrayList<>();
            try {
                String[] allAssets = context.getAssets().list("");
                if (allAssets != null) {
                    for (String asset : allAssets) {
                        if (asset.startsWith("jellyfin-bootstrap.tar.gz.part_")) {
                            partFiles.add(asset);
                        }
                    }
                    Collections.sort(partFiles);
                }
            } catch (Exception ignored) {}
            if (partFiles.isEmpty()) {
                Collections.addAll(partFiles, ASSET_PARTS);
            }
            Log.i(TAG, "Found bootstrap asset parts: " + partFiles);

            int totalParts = partFiles.size();
            int partIndex = 0;
            try (OutputStream out = new BufferedOutputStream(new FileOutputStream(cacheTarGz))) {
                byte[] buffer = new byte[65536];
                for (String part : partFiles) {
                    partIndex++;
                    if (callback != null) {
                        int pct = 10 + (int) ((partIndex / (float) totalParts) * 25);
                        callback.onProgress("Recombining part " + partIndex + " of " + totalParts + "...", pct);
                    }
                    try (InputStream in = context.getAssets().open(part)) {
                        int read;
                        while ((read = in.read(buffer)) != -1) {
                            out.write(buffer, 0, read);
                        }
                    }
                }
            }

            if (callback != null) {
                callback.onProgress("Extracting Jellyfin runtime & FFmpeg...", 40);
            }
            Log.i(TAG, "Unpacking combined bootstrap archive via Java TarArchiveInputStream...");
            try (InputStream fileIn = new BufferedInputStream(new java.io.FileInputStream(cacheTarGz));
                 GZIPInputStream gzIn = new GZIPInputStream(fileIn);
                 TarArchiveInputStream tarIn = new TarArchiveInputStream(gzIn)) {

                TarArchiveEntry entry;
                byte[] buffer = new byte[65536];
                int extractedCount = 0;
                while ((entry = tarIn.getNextTarEntry()) != null) {
                    String name = entry.getName();
                    while (name.startsWith("./") || name.startsWith("/")) {
                        name = name.startsWith("./") ? name.substring(2) : name.substring(1);
                    }
                    File targetFile = new File(prefixDir, name);

                    if (entry.isDirectory()) {
                        targetFile.mkdirs();
                    } else if (entry.isSymbolicLink()) {
                        targetFile.getParentFile().mkdirs();
                        if (targetFile.exists()) {
                            deleteRecursively(targetFile);
                        }
                        try {
                            Os.symlink(entry.getLinkName(), targetFile.getAbsolutePath());
                        } catch (Exception e) {
                            Log.w(TAG, "Failed to create symlink " + targetFile + " -> " + entry.getLinkName() + ": " + e.getMessage());
                        }
                    } else {
                        targetFile.getParentFile().mkdirs();
                        try (OutputStream out = new BufferedOutputStream(new FileOutputStream(targetFile))) {
                            int count;
                            while ((count = tarIn.read(buffer, 0, buffer.length)) != -1) {
                                out.write(buffer, 0, count);
                            }
                        }
                        int mode = entry.getMode();
                        if (mode != 0) {
                            try {
                                Os.chmod(targetFile.getAbsolutePath(), mode);
                            } catch (Exception ignored) {}
                        }
                    }
                    extractedCount++;
                    if (callback != null && extractedCount % 100 == 0) {
                        int pct = Math.min(88, 40 + (extractedCount / 50));
                        callback.onProgress("Extracting runtime files (" + extractedCount + ")...", pct);
                    }
                }
                Log.i(TAG, "Extracted " + extractedCount + " entries from tar.gz!");
            }

            cacheTarGz.delete();

            if (callback != null) {
                callback.onProgress("Configuring permissions & binaries...", 90);
            }
            File dotnetBin = new File(prefixDir, "lib/dotnet/dotnet");
            if (dotnetBin.exists()) {
                Os.chmod(dotnetBin.getAbsolutePath(), 0755);
            }
            File ffmpegBin = new File(prefixDir, "opt/jellyfin/bin/ffmpeg");
            if (ffmpegBin.exists()) {
                Os.chmod(ffmpegBin.getAbsolutePath(), 0755);
            }

            File jellyfinDll = new File(prefixDir, "lib/jellyfin/jellyfin.dll");
            Log.i(TAG, "Post-extraction check: dotnetBin=" + dotnetBin.getAbsolutePath() + " exists=" + dotnetBin.exists() + ", jellyfinDll=" + jellyfinDll.getAbsolutePath() + " exists=" + jellyfinDll.exists());

            if (!marker.getParentFile().exists()) {
                marker.getParentFile().mkdirs();
            }
            marker.createNewFile();
            isInitializedCached = null;

            if (!isInitialized(context)) {
                StringBuilder missing = new StringBuilder();
                if (!marker.exists()) missing.append("marker file, ");
                if (!jellyfinDll.exists()) missing.append("jellyfin.dll, ");
                if (!dotnetBin.exists()) missing.append("dotnet binary, ");
                if (!ffmpegBin.exists()) missing.append("ffmpeg binary, ");
                String missingFiles = missing.length() > 2 ? missing.substring(0, missing.length() - 2) : "unknown components";
                String valErr = "Extraction finished but validation failed: missing " + missingFiles;
                Log.e(TAG, valErr);
                setLastError(valErr);
                if (callback != null) {
                    callback.onProgress(valErr, 0);
                }
                return false;
            }

            Log.i(TAG, "Jellyfin self-contained environment initialized successfully!");
            if (callback != null) {
                callback.onProgress("Runtime installation complete!", 100);
            }
            return true;

        } catch (Exception e) {
            Log.e(TAG, "Failed to initialize Jellyfin bootstrap environment", e);
            String errorMsg = e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName();
            String fullErr = "Bootstrap extraction failed: " + errorMsg;
            setLastError(fullErr);
            if (callback != null) {
                callback.onProgress(fullErr, 0);
            }
            if (cacheTarGz.exists()) {
                cacheTarGz.delete();
            }
            return false;
        }
    }
}
