package com.fishbowl.app;

import android.content.Context;
import android.content.UriPermission;
import android.net.Uri;
import android.os.Build;
import android.os.Environment;
import android.os.StatFs;
import android.os.storage.StorageManager;
import android.os.storage.StorageVolume;
import android.provider.DocumentsContract;

import androidx.core.content.ContextCompat;
import androidx.documentfile.provider.DocumentFile;

import java.io.File;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

public final class StorageDriveHelper {

    private StorageDriveHelper() {}

    public static String normalizeStoragePath(String path) {
        if (path == null) return null;
        String clean = path.trim();
        if (clean.isEmpty()) return clean;

        // Strip trailing slash unless root "/"
        while (clean.length() > 1 && clean.endsWith("/")) {
            clean = clean.substring(0, clean.length() - 1);
        }

        // 1. Convert internal kernel vold mount /mnt/media_rw/<UUID> to public POSIX /storage/<UUID>
        if (clean.startsWith("/mnt/media_rw/")) {
            clean = "/storage/" + clean.substring("/mnt/media_rw/".length());
        }
        // 2. Convert /mnt/pass_through/<uid>/<UUID> or /mnt/pass_through/<uid>/emulated/0
        else if (clean.startsWith("/mnt/pass_through/")) {
            String rest = clean.substring("/mnt/pass_through/".length());
            int nextSlash = rest.indexOf('/');
            if (nextSlash != -1) {
                String sub = rest.substring(nextSlash + 1);
                clean = "/storage/" + sub;
            }
        }
        // 3. Convert /mnt/user/<uid>/<UUID> or /mnt/user/<uid>/emulated/0
        else if (clean.startsWith("/mnt/user/")) {
            String rest = clean.substring("/mnt/user/".length());
            int nextSlash = rest.indexOf('/');
            if (nextSlash != -1) {
                String sub = rest.substring(nextSlash + 1);
                clean = "/storage/" + sub;
            }
        }
        // 4. Convert legacy /storage/emulated/legacy or /sdcard to /storage/emulated/0
        else if (clean.equals("/storage/emulated/legacy") || clean.startsWith("/storage/emulated/legacy/")) {
            clean = "/storage/emulated/0" + clean.substring("/storage/emulated/legacy".length());
        } else if (clean.equals("/sdcard") || clean.startsWith("/sdcard/")) {
            clean = "/storage/emulated/0" + clean.substring("/sdcard".length());
        }

        return clean;
    }

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
        public final boolean isSafVerified;
        public final boolean isAppSpecific;
        public final String note;

        public PathVerification(boolean exists, boolean canRead, boolean isDirectory, int itemCount,
                                int mediaFileCount, boolean hasPermissionError, boolean isSafVerified,
                                boolean isAppSpecific, String note) {
            this.exists = exists;
            this.canRead = canRead;
            this.isDirectory = isDirectory;
            this.itemCount = itemCount;
            this.mediaFileCount = mediaFileCount;
            this.hasPermissionError = hasPermissionError;
            this.isSafVerified = isSafVerified;
            this.isAppSpecific = isAppSpecific;
            this.note = note != null ? note : "";
        }

        public PathVerification(boolean exists, boolean canRead, boolean isDirectory, int itemCount,
                                int mediaFileCount, boolean hasPermissionError) {
            this(exists, canRead, isDirectory, itemCount, mediaFileCount, hasPermissionError, false, false, "");
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
                String uuid = vol.getUuid();

                // Standard POSIX mapping: Always prioritize /storage/<UUID> over internal /mnt/media_rw mounts
                if (vol.isPrimary()) {
                    path = "/storage/emulated/0";
                } else if (uuid != null && !uuid.trim().isEmpty()) {
                    path = "/storage/" + uuid.trim();
                } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
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
                    } else if (uuid != null && !uuid.isEmpty()) {
                        path = "/storage/" + uuid;
                    }
                }

                if (path == null) continue;

                // Always normalize to ensure internal /mnt/media_rw/ or mount namespaces never leak through
                path = normalizeStoragePath(path);

                String desc = vol.getDescription(context);
                if (desc == null || desc.trim().isEmpty()) {
                    desc = vol.isPrimary() ? "Internal Storage" : "External Storage";
                }

                long total = 0, free = 0;
                StatFs stat = getDriveStatFs(context, path);
                if (stat != null) {
                    try {
                        total = stat.getTotalBytes();
                        free = stat.getAvailableBytes();
                    } catch (Throwable ignored) {}
                }

                // If this is an external drive, auto-provision media folders so it works seamlessly like internal storage
                if (!vol.isPrimary()) {
                    try {
                        provisionAppDataMediaFolders(context, path);
                    } catch (Throwable ignored) {}
                }

                List<String> folders = scanTopLevelMediaFolders(context, path, vol.getUuid(), vol.isPrimary());

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
                    String full = normalizeStoragePath(ext.getAbsolutePath());
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
                                if (total <= 0) {
                                    try {
                                        StatFs stat = new StatFs(full);
                                        total = stat.getTotalBytes();
                                        free = stat.getAvailableBytes();
                                    } catch (Throwable ignored) {}
                                }

                                String driveLabel = "USB Drive";
                                String[] segs = rootPath.split("/");
                                String uuid = segs.length > 0 ? segs[segs.length - 1] : null;
                                if (uuid != null) {
                                    driveLabel = "USB Drive (" + uuid + ")";
                                }

                                List<String> folders = scanTopLevelMediaFolders(context, rootPath, uuid, false);
                                drives.add(new DriveInfo(
                                        uuid,
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
            List<String> folders = scanTopLevelMediaFolders(context, primaryPath, null, true);
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

    public static StatFs getDriveStatFs(Context context, String drivePath) {
        if (drivePath == null || drivePath.isEmpty()) return null;
        try {
            StatFs stat = new StatFs(drivePath);
            if (stat.getTotalBytes() > 0) return stat;
        } catch (Throwable ignored) {}

        if (context != null) {
            try {
                File[] extDirs = ContextCompat.getExternalFilesDirs(context, null);
                if (extDirs != null) {
                    for (File ext : extDirs) {
                        if (ext != null) {
                            String p = normalizeStoragePath(ext.getAbsolutePath());
                            if (p.startsWith(drivePath)) {
                                StatFs stat = new StatFs(p);
                                if (stat.getTotalBytes() > 0) return stat;
                            }
                        }
                    }
                }
            } catch (Throwable ignored) {}
        }
        return null;
    }

    public static DriveInfo getPrimaryExternalDrive(Context context) {
        if (context == null) return null;
        List<DriveInfo> drives = getMountedDrives(context);
        for (DriveInfo d : drives) {
            if (!d.isPrimary && d.totalBytes > 0) {
                return d;
            }
        }
        for (DriveInfo d : drives) {
            if (!d.isPrimary) {
                return d;
            }
        }
        return null;
    }

    public static List<String> setupExternalDriveSameAsInternal(Context context, String driveRootPath) {
        return provisionAppDataMediaFolders(context, driveRootPath);
    }

    public static List<String> provisionAppDataMediaFolders(Context context, String driveRootPath) {
        List<String> created = new ArrayList<>();
        if (driveRootPath == null || driveRootPath.trim().isEmpty()) return created;
        String cleanRoot = normalizeStoragePath(driveRootPath);

        if (context != null) {
            try {
                // Triggers Android framework to create the app package dir on external storage
                ContextCompat.getExternalFilesDirs(context, null);
            } catch (Throwable ignored) {}
        }

        File baseAppDir = new File(cleanRoot, "Android/data/com.fishbowl.app/files");
        if (!baseAppDir.exists()) {
            baseAppDir.mkdirs();
        }

        String[] subDirs = {"Movies", "Music", "Shows", "Downloads"};
        for (String sub : subDirs) {
            File dir = new File(baseAppDir, sub);
            if (!dir.exists()) {
                try {
                    dir.mkdirs();
                } catch (Throwable ignored) {}
            }
            if (dir.exists()) {
                created.add(normalizeStoragePath(dir.getAbsolutePath()));
            }
        }
        return created;
    }

    private static List<String> scanTopLevelMediaFolders(Context context, String rootPath, String uuid, boolean isPrimary) {
        List<String> list = new ArrayList<>();
        File root = new File(rootPath);

        // 1. Direct POSIX listing (works on Internal Storage)
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

        // 2. If list is empty and this is primary storage, seed standard folders if they exist
        if (list.isEmpty() && isPrimary) {
            String[] common = {"Movies", "Music", "TV Shows", "Download", "DCIM", "Pictures", "Documents"};
            for (String c : common) {
                File sub = new File(root, c);
                if (sub.exists()) {
                    list.add(c);
                }
            }
        } else if (!isPrimary) {
            // 3. For external drives: check persisted SAF URI permissions to discover real folders
            if (context != null) {
                try {
                    List<UriPermission> perms = context.getContentResolver().getPersistedUriPermissions();
                    for (UriPermission perm : perms) {
                        if (!perm.isReadPermission()) continue;
                        Uri uri = perm.getUri();
                        if ("com.android.externalstorage.documents".equals(uri.getAuthority())) {
                            String docId = DocumentsContract.getTreeDocumentId(uri);
                            if (docId != null) {
                                String[] parts = docId.split(":");
                                String docUuid = parts[0];
                                if (uuid != null && uuid.equalsIgnoreCase(docUuid)) {
                                    DocumentFile treeDoc = DocumentFile.fromTreeUri(context, uri);
                                    if (treeDoc != null && treeDoc.exists() && treeDoc.isDirectory()) {
                                        if (parts.length > 1 && !parts[1].isEmpty()) {
                                            String folderName = parts[1];
                                            if (!list.contains(folderName)) {
                                                list.add(folderName);
                                            }
                                        } else {
                                            // Granted at drive root: list all top-level folders
                                            DocumentFile[] children = treeDoc.listFiles();
                                            for (DocumentFile c : children) {
                                                if (c.isDirectory() && !c.getName().startsWith(".") && !"Android".equalsIgnoreCase(c.getName())) {
                                                    if (!list.contains(c.getName())) {
                                                        list.add(c.getName());
                                                    }
                                                }
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }
                } catch (Throwable ignored) {}
            }

            // 4. Also scan the guaranteed Android app data folder on external storage
            File appFiles = new File(root, "Android/data/com.fishbowl.app/files");
            if (!appFiles.exists()) {
                try {
                    appFiles.mkdirs();
                } catch (Throwable ignored) {}
            }
            if (appFiles.exists() && appFiles.canRead()) {
                File[] subs = appFiles.listFiles();
                if (subs != null) {
                    for (File s : subs) {
                        if (s.isDirectory() && !s.getName().startsWith(".")) {
                            String rel = "Android/data/com.fishbowl.app/files/" + s.getName();
                            if (!list.contains(rel)) {
                                list.add(rel);
                            }
                        }
                    }
                }
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
                            return normalizeStoragePath(relPath.isEmpty() ? "/storage/emulated/0" : "/storage/emulated/0/" + relPath);
                        }

                        // Match against mounted drives
                        List<DriveInfo> drives = getMountedDrives(context);
                        for (DriveInfo drive : drives) {
                            if (drive.uuid != null && drive.uuid.equalsIgnoreCase(type)) {
                                String combined = relPath.isEmpty() ? drive.rootPath : drive.rootPath + "/" + relPath;
                                return normalizeStoragePath(combined);
                            }
                            if (drive.rootPath != null && drive.rootPath.toLowerCase(Locale.ROOT).contains(type.toLowerCase(Locale.ROOT))) {
                                String combined = relPath.isEmpty() ? drive.rootPath : drive.rootPath + "/" + relPath;
                                return normalizeStoragePath(combined);
                            }
                        }

                        return normalizeStoragePath(relPath.isEmpty() ? "/storage/" + type : "/storage/" + type + "/" + relPath);
                    }
                }
            } catch (Throwable ignored) {}
        }
        return null;
    }

    public static PathVerification verifyPath(String path) {
        return verifyPath(null, path);
    }

    public static PathVerification verifyPath(Context context, String path) {
        if (path == null || path.trim().isEmpty()) {
            return new PathVerification(false, false, false, 0, 0, false, false, false, "Path is empty");
        }
        String cleanPath = normalizeStoragePath(path);
        if (cleanPath.contains("..") || cleanPath.contains("\0") || !cleanPath.startsWith("/")) {
            return new PathVerification(false, false, false, 0, 0, false, false, false, "Invalid path format");
        }
        boolean isAllowedPrefix = cleanPath.startsWith("/storage/") ||
                                  cleanPath.startsWith("/sdcard") ||
                                  cleanPath.startsWith("/data/data/com.fishbowl.app") ||
                                  cleanPath.startsWith("/data/user/0/com.fishbowl.app");
        if (!isAllowedPrefix) {
            return new PathVerification(false, false, false, 0, 0, false, false, false, "Path prefix not allowed");
        }

        boolean isAppSpecific = cleanPath.contains("/Android/data/com.fishbowl.app/");

        // 1. Direct POSIX check
        File f = new File(cleanPath);
        boolean posixExists = false;
        try {
            posixExists = f.exists();
        } catch (Throwable ignored) {}

        if (posixExists) {
            boolean isDir = f.isDirectory();
            boolean canRead = f.canRead();
            int itemCount = 0;
            int mediaCount = 0;
            boolean permissionError = false;

            if (isDir) {
                File[] subs = f.listFiles();
                if (subs == null) {
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

            if (canRead && !permissionError) {
                String note = isAppSpecific ? "App-specific directory (Native POSIX Jellyfin ready)" : "Verified via direct filesystem";
                return new PathVerification(true, canRead, isDir, itemCount, mediaCount, false, false, isAppSpecific, note);
            }
        }

        // 2. SAF verification fallback for external USB OTG paths
        if (context != null && cleanPath.startsWith("/storage/")) {
            String afterStorage = cleanPath.substring("/storage/".length());
            String uuid;
            String relPath;
            int slash = afterStorage.indexOf('/');
            if (slash != -1) {
                uuid = afterStorage.substring(0, slash);
                relPath = afterStorage.substring(slash + 1);
            } else {
                uuid = afterStorage;
                relPath = "";
            }

            if (!"emulated".equalsIgnoreCase(uuid) && !uuid.isEmpty()) {
                try {
                    List<UriPermission> perms = context.getContentResolver().getPersistedUriPermissions();
                    for (UriPermission perm : perms) {
                        if (!perm.isReadPermission()) continue;
                        Uri uri = perm.getUri();
                        if ("com.android.externalstorage.documents".equals(uri.getAuthority())) {
                            String docId = DocumentsContract.getTreeDocumentId(uri);
                            if (docId != null) {
                                String[] parts = docId.split(":");
                                String docUuid = parts[0];
                                String docRel = parts.length > 1 ? parts[1] : "";
                                while (docRel.startsWith("/")) docRel = docRel.substring(1);

                                if (docUuid.equalsIgnoreCase(uuid)) {
                                    DocumentFile treeDoc = DocumentFile.fromTreeUri(context, uri);
                                    if (treeDoc != null && treeDoc.exists()) {
                                        DocumentFile target = null;
                                        if (relPath.isEmpty() || relPath.equalsIgnoreCase(docRel)) {
                                            target = treeDoc;
                                        } else if (docRel.isEmpty()) {
                                            target = findDocumentFileByPath(treeDoc, relPath);
                                        }

                                        if (target != null && target.exists()) {
                                            boolean isDir = target.isDirectory();
                                            int itemCount = 0;
                                            int mediaCount = 0;
                                            if (isDir) {
                                                DocumentFile[] children = target.listFiles();
                                                itemCount = children.length;
                                                mediaCount = countDocumentMediaFiles(children, 0);
                                            } else {
                                                if (isMediaFile(target.getName())) mediaCount = 1;
                                            }
                                            return new PathVerification(true, true, isDir, itemCount, mediaCount, false, true, false,
                                                    "Verified via Storage Access Framework (SAF)");
                                        }
                                    }
                                }
                            }
                        }
                    }
                } catch (Throwable ignored) {}
            }
        }

        return new PathVerification(false, false, false, 0, 0, false, false, false, "Directory not detected on disk");
    }

    private static DocumentFile findDocumentFileByPath(DocumentFile root, String relPath) {
        if (root == null || relPath == null || relPath.isEmpty()) return root;
        String[] segments = relPath.split("/");
        DocumentFile current = root;
        for (String seg : segments) {
            if (seg.isEmpty()) continue;
            DocumentFile next = current.findFile(seg);
            if (next == null || !next.exists()) return null;
            current = next;
        }
        return current;
    }

    private static int countDocumentMediaFiles(DocumentFile[] files, int depth) {
        if (files == null || depth > 2) return 0;
        int count = 0;
        for (DocumentFile f : files) {
            if (f.isDirectory() && !f.getName().startsWith(".")) {
                DocumentFile[] children = f.listFiles();
                if (children != null) {
                    count += countDocumentMediaFiles(children, depth + 1);
                }
            } else if (f.isFile() && isMediaFile(f.getName())) {
                count++;
            }
            if (count >= 500) break;
        }
        return count;
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
