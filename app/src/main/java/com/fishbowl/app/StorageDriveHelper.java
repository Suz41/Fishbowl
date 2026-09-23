package com.fishbowl.app;

import android.content.Context;
import android.net.Uri;
import android.os.Build;
import android.os.Environment;
import android.os.StatFs;
import android.os.storage.StorageManager;
import android.os.storage.StorageVolume;
import android.provider.DocumentsContract;

import androidx.core.content.ContextCompat;

import java.io.File;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

public final class StorageDriveHelper {

    private StorageDriveHelper() {}

    public static final class DriveInfo {
        public final String uuid;
        public final String name;
        public final String rootPath;
        public final boolean isPrimary;
        public final boolean isRemovable;
        public final String state;
        public final long totalBytes;
        public final long freeBytes;
        public final List<String> mediaFolders;

        public DriveInfo(String uuid, String name, String rootPath, boolean isPrimary,
                         boolean isRemovable, String state, long totalBytes,
                         long freeBytes, List<String> mediaFolders) {
            this.uuid = uuid;
            this.name = name;
            this.rootPath = rootPath;
            this.isPrimary = isPrimary;
            this.isRemovable = isRemovable;
            this.state = state;
            this.totalBytes = totalBytes;
            this.freeBytes = freeBytes;
            this.mediaFolders = mediaFolders != null ? mediaFolders : Collections.emptyList();
        }
    }

    public static final class PathVerification {
        public final boolean exists;
        public final boolean canRead;
        public final boolean isDirectory;
        public final int itemCount;
        public final int mediaFileCount;
        public final boolean hasPermissionError;

        public PathVerification(boolean exists, boolean canRead, boolean isDirectory, int itemCount, int mediaFileCount, boolean hasPermissionError) {
            this.exists = exists;
            this.canRead = canRead;
            this.isDirectory = isDirectory;
            this.itemCount = itemCount;
            this.mediaFileCount = mediaFileCount;
            this.hasPermissionError = hasPermissionError;
        }
    }

    public static List<DriveInfo> getMountedDrives(Context context) {
        List<DriveInfo> drives = new ArrayList<>();
        if (context == null) return drives;

        StorageManager sm = (StorageManager) context.getSystemService(Context.STORAGE_SERVICE);
        if (sm != null) {
            List<StorageVolume> volumes = sm.getStorageVolumes();
            for (StorageVolume vol : volumes) {
                String state = vol.getState();
                if (!Environment.MEDIA_MOUNTED.equalsIgnoreCase(state) &&
                        !Environment.MEDIA_MOUNTED_READ_ONLY.equalsIgnoreCase(state)) {
                    continue;
                }

                String path = null;
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                    try {
                        File dir = vol.getDirectory();
                        if (dir != null) {
                            path = dir.getAbsolutePath();
                        }
                    } catch (Throwable ignored) {}
                }

                if (path == null) {
                    if (vol.isPrimary()) {
                        path = Environment.getExternalStorageDirectory().getAbsolutePath();
                    } else {
                        String uuid = vol.getUuid();
                        if (uuid != null && !uuid.isEmpty()) {
                            path = "/storage/" + uuid;
                        }
                    }
                }

                if (path == null) continue;

                String desc = vol.getDescription(context);
                if (desc == null || desc.trim().isEmpty()) {
                    desc = vol.isPrimary() ? "Internal Storage" : "External Storage";
                }

                long total = 0, free = 0;
                try {
                    StatFs stat = new StatFs(path);
                    total = stat.getTotalBytes();
                    free = stat.getAvailableBytes();
                } catch (Throwable ignored) {}

                List<String> folders = scanTopLevelMediaFolders(path, vol.isPrimary());

                drives.add(new DriveInfo(
                        vol.getUuid(),
                        desc,
                        path,
                        vol.isPrimary(),
                        vol.isRemovable(),
                        state,
                        total,
                        free,
                        folders
                ));
            }
        }

        // Secondary detection via getExternalFilesDirs to catch any unlisted OTG mounts
        try {
            File[] extDirs = ContextCompat.getExternalFilesDirs(context, null);
            if (extDirs != null) {
                for (File ext : extDirs) {
                    if (ext == null) continue;
                    String full = ext.getAbsolutePath();
                    if (!full.startsWith("/storage/emulated/0")) {
                        int idx = full.indexOf("/Android/data");
                        if (idx > 0) {
                            String rootPath = full.substring(0, idx);
                            boolean alreadyPresent = false;
                            for (DriveInfo d : drives) {
                                if (d.rootPath.equalsIgnoreCase(rootPath)) {
                                    alreadyPresent = true;
                                    break;
                                }
                            }
                            if (!alreadyPresent) {
                                long total = 0, free = 0;
                                try {
                                    StatFs stat = new StatFs(rootPath);
                                    total = stat.getTotalBytes();
                                    free = stat.getAvailableBytes();
                                } catch (Throwable ignored) {}

                                String driveLabel = "USB Drive";
                                String[] segs = rootPath.split("/");
                                if (segs.length > 0) {
                                    driveLabel = "USB Drive (" + segs[segs.length - 1] + ")";
                                }

                                List<String> folders = scanTopLevelMediaFolders(rootPath, false);
                                drives.add(new DriveInfo(
                                        null,
                                        driveLabel,
                                        rootPath,
                                        false,
                                        true,
                                        "mounted",
                                        total,
                                        free,
                                        folders
                                ));
                            }
                        }
                    }
                }
            }
        } catch (Throwable ignored) {}

        // Fallback if list is empty
        if (drives.isEmpty()) {
            String primaryPath = "/storage/emulated/0";
            long total = 0, free = 0;
            try {
                StatFs stat = new StatFs(primaryPath);
                total = stat.getTotalBytes();
                free = stat.getAvailableBytes();
            } catch (Throwable ignored) {}
            List<String> folders = scanTopLevelMediaFolders(primaryPath, true);
            drives.add(new DriveInfo(
                    null,
                    "Internal Storage",
                    primaryPath,
                    true,
                    false,
                    "mounted",
                    total,
                    free,
                    folders
            ));
        }

        return drives;
    }

    private static List<String> scanTopLevelMediaFolders(String rootPath, boolean isPrimary) {
        List<String> list = new ArrayList<>();
        File root = new File(rootPath);
        if (root.exists() && root.canRead()) {
            File[] files = root.listFiles();
            if (files != null) {
                for (File f : files) {
                    if (f.isDirectory() && !f.isHidden()) {
                        String name = f.getName();
                        if (!"Android".equalsIgnoreCase(name) && !name.startsWith(".")) {
                            list.add(name);
                        }
                    }
                }
            }
        }

        // If list is empty and this is primary storage, seed with standard media folders if they exist
        if (list.isEmpty() && isPrimary) {
            String[] common = {"Movies", "Music", "TV Shows", "Download", "DCIM", "Pictures", "Documents"};
            for (String c : common) {
                File sub = new File(root, c);
                if (sub.exists()) {
                    list.add(c);
                }
            }
        } else if (!isPrimary) {
            // For external drives: ensure common folders and guaranteed app media directory are available
            String[] common = {"Movies", "Music", "Videos", "Download", "DCIM"};
            for (String c : common) {
                if (!list.contains(c)) {
                    list.add(c);
                }
            }
            // Ensure guaranteed Android app data folder exists and is listed for external storage
            File appFiles = new File(root, "Android/data/com.fishbowl.app/files");
            if (!appFiles.exists()) {
                try {
                    appFiles.mkdirs();
                } catch (Throwable ignored) {}
            }
            if (!list.contains("Android/data/com.fishbowl.app/files")) {
                list.add("Android/data/com.fishbowl.app/files");
            }
        }

        Collections.sort(list, String.CASE_INSENSITIVE_ORDER);
        return list;
    }

    public static String resolveTreeUriToPath(Context context, Uri treeUri) {
        if (treeUri == null) return null;
        String authority = treeUri.getAuthority();
        if ("com.android.externalstorage.documents".equals(authority)) {
            try {
                String docId = null;
                try {
                    docId = DocumentsContract.getTreeDocumentId(treeUri);
                } catch (Throwable ignored) {}
                if (docId == null) {
                    try {
                        docId = DocumentsContract.getDocumentId(treeUri);
                    } catch (Throwable ignored) {}
                }
                if (docId != null) {
                    String[] parts = docId.split(":");
                    if (parts.length >= 1) {
                        String type = parts[0];
                        String relPath = parts.length > 1 ? parts[1] : "";
                        while (relPath.startsWith("/")) {
                            relPath = relPath.substring(1);
                        }

                        if ("primary".equalsIgnoreCase(type)) {
                            return relPath.isEmpty() ? "/storage/emulated/0" : "/storage/emulated/0/" + relPath;
                        }

                        // Match against mounted drives
                        List<DriveInfo> drives = getMountedDrives(context);
                        for (DriveInfo drive : drives) {
                            if (drive.uuid != null && drive.uuid.equalsIgnoreCase(type)) {
                                return relPath.isEmpty() ? drive.rootPath : drive.rootPath + "/" + relPath;
                            }
                            if (drive.rootPath != null && drive.rootPath.toLowerCase(Locale.ROOT).contains(type.toLowerCase(Locale.ROOT))) {
                                return relPath.isEmpty() ? drive.rootPath : drive.rootPath + "/" + relPath;
                            }
                        }

                        return relPath.isEmpty() ? "/storage/" + type : "/storage/" + type + "/" + relPath;
                    }
                }
            } catch (Throwable ignored) {}
        }
        return null;
    }

    public static PathVerification verifyPath(String path) {
        if (path == null || path.trim().isEmpty()) {
            return new PathVerification(false, false, false, 0, 0, false);
        }
        String cleanPath = path.trim();
        if (cleanPath.contains("..") || cleanPath.contains("\0") || !cleanPath.startsWith("/")) {
            return new PathVerification(false, false, false, 0, 0, false);
        }
        boolean isAllowedPrefix = cleanPath.startsWith("/storage/") ||
                                  cleanPath.startsWith("/sdcard") ||
                                  cleanPath.startsWith("/mnt/") ||
                                  cleanPath.startsWith("/data/data/com.fishbowl.app") ||
                                  cleanPath.startsWith("/data/user/0/com.fishbowl.app");
        if (!isAllowedPrefix) {
            return new PathVerification(false, false, false, 0, 0, false);
        }

        File f = new File(cleanPath);
        try {
            f = f.getCanonicalFile();
        } catch (Exception e) {
            return new PathVerification(false, false, false, 0, 0, false);
        }
        String canonicalPath = f.getPath();
        if (canonicalPath.contains("..") || (!canonicalPath.startsWith("/storage/") && !canonicalPath.startsWith("/sdcard") && !canonicalPath.startsWith("/mnt/") && !canonicalPath.startsWith("/data/"))) {
            return new PathVerification(false, false, false, 0, 0, false);
        }

        if (!f.exists()) {
            return new PathVerification(false, false, false, 0, 0, false);
        }
        boolean isDir = f.isDirectory();
        boolean canRead = f.canRead();
        int itemCount = 0;
        int mediaCount = 0;
        boolean permissionError = false;

        if (isDir) {
            File[] subs = f.listFiles();
            if (subs == null) {
                // Folder exists on disk but process cannot list files (Android SELinux or permission block)
                permissionError = true;
                canRead = false;
            } else {
                itemCount = subs.length;
                mediaCount = countMediaFiles(subs, 0);
            }
        } else {
            if (isMediaFile(f.getName())) {
                mediaCount = 1;
            }
        }
        return new PathVerification(true, canRead, isDir, itemCount, mediaCount, permissionError);
    }

    private static int countMediaFiles(File[] files, int depth) {
        if (files == null || depth > 2) return 0;
        int count = 0;
        for (File f : files) {
            if (f.isDirectory() && !f.isHidden() && !f.getName().startsWith(".")) {
                File[] children = f.listFiles();
                if (children != null) {
                    count += countMediaFiles(children, depth + 1);
                }
            } else if (f.isFile() && isMediaFile(f.getName())) {
                count++;
            }
            if (count >= 500) break;
        }
        return count;
    }

    public static boolean isMediaFile(String name) {
        if (name == null) return false;
        String n = name.toLowerCase(Locale.ROOT);
        return n.endsWith(".mp4") || n.endsWith(".mkv") || n.endsWith(".avi") ||
                n.endsWith(".mov") || n.endsWith(".wmv") || n.endsWith(".webm") ||
                n.endsWith(".flv") || n.endsWith(".m4v") || n.endsWith(".ts") ||
                n.endsWith(".mpg") || n.endsWith(".mpeg") || n.endsWith(".m2ts") ||
                n.endsWith(".vob") || n.endsWith(".3gp") || n.endsWith(".iso") ||
                n.endsWith(".mp3") || n.endsWith(".flac") || n.endsWith(".m4a") ||
                n.endsWith(".aac") || n.endsWith(".ogg") || n.endsWith(".opus") ||
                n.endsWith(".wav") || n.endsWith(".wma") || n.endsWith(".alac") ||
                n.endsWith(".aiff") || n.endsWith(".ape") || n.endsWith(".dsf") ||
                n.endsWith(".jpg") || n.endsWith(".jpeg") || n.endsWith(".png") ||
                n.endsWith(".webp") || n.endsWith(".epub") || n.endsWith(".cbz") ||
                n.endsWith(".cbr") || n.endsWith(".pdf");
    }

    public static String formatCapacity(long freeBytes, long totalBytes) {
        if (totalBytes <= 0) return "Available";
        double freeGb = freeBytes / (1024.0 * 1024.0 * 1024.0);
        double totalGb = totalBytes / (1024.0 * 1024.0 * 1024.0);
        return String.format(Locale.ROOT, "%.1f GB free of %.1f GB", freeGb, totalGb);
    }
}
