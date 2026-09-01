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
import java.util.zip.GZIPInputStream;

import org.apache.commons.compress.archivers.tar.TarArchiveEntry;
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream;

public class JellyfinBootstrapper {

    private static final String TAG = "JellyfinBootstrapper";
    private static final String INITIALIZED_MARKER_FILE = ".jellyfin_initialized_v10.11.11";
    private static final String[] ASSET_PARTS = {
            "jellyfin-bootstrap.tar.gz.part_aa",
            "jellyfin-bootstrap.tar.gz.part_ab",
            "jellyfin-bootstrap.tar.gz.part_ac"
    };

    public static synchronized boolean isInitialized(Context context) {
        File marker = new File(TermuxConstants.TERMUX_FILES_DIR, INITIALIZED_MARKER_FILE);
        File jellyfinDll = new File(TermuxConstants.TERMUX_PREFIX_DIR, "lib/jellyfin/jellyfin.dll");
        File dotnetBin = new File(TermuxConstants.TERMUX_PREFIX_DIR, "lib/dotnet/dotnet");
        return marker.exists() && jellyfinDll.exists() && dotnetBin.exists();
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

    public static synchronized boolean initializeIfNeeded(Context context) {
        ensureNetworkConfig(context);
        if (isInitialized(context)) {
            Log.i(TAG, "Jellyfin environment already initialized.");
            return true;
        }

        Log.i(TAG, "Initializing Jellyfin self-contained environment...");
        File marker = new File(TermuxConstants.TERMUX_FILES_DIR, INITIALIZED_MARKER_FILE);
        if (marker.exists()) {
            marker.delete();
        }

        File prefixDir = TermuxConstants.TERMUX_PREFIX_DIR;
        if (!prefixDir.exists()) {
            prefixDir.mkdirs();
        }

        File cacheTarGz = new File(context.getCacheDir(), "jellyfin-bootstrap.tar.gz");
        try {
            Log.i(TAG, "Recombining split bootstrap assets into cache...");
            try (OutputStream out = new BufferedOutputStream(new FileOutputStream(cacheTarGz))) {
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
                            targetFile.delete();
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
                }
                Log.i(TAG, "Extracted " + extractedCount + " entries from tar.gz!");
            }

            cacheTarGz.delete();

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

            if (!isInitialized(context)) {
                Log.e(TAG, "Extraction finished but validation failed.");
                return false;
            }

            Log.i(TAG, "Jellyfin self-contained environment initialized successfully!");
            return true;

        } catch (Exception e) {
            Log.e(TAG, "Failed to initialize Jellyfin bootstrap environment", e);
            if (cacheTarGz.exists()) {
                cacheTarGz.delete();
            }
            return false;
        }
    }
}
