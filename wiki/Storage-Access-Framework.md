# Storage & Media Directory Architecture

Starting with Android 11, the operating system enforces strict Scoped Storage boundaries to restrict applications from traversing filesystem paths outside their designated boundaries.

In Fishbowl v1.4.6, the storage architecture was consolidated into a unified POSIX-first model with active library protection.

---

## Storage Modes & Access Methods

### 1. Internal Storage (Direct POSIX)
Grant **All-Files Access** (`MANAGE_EXTERNAL_STORAGE`) when prompted in Fishbowl Settings. You can select standard media directories with one-tap quick chips:
* `/storage/emulated/0/Movies`
* `/storage/emulated/0/Music`
* `/storage/emulated/0/Download`

### 2. Custom Path Selection (`[ ENTER PATH ]`)
Tap `[ ENTER PATH ]` on the Active Media Path card to type or paste any custom storage path (such as nested folders, symlinks, or custom download locations). Fishbowl validates the path in real time and reports detected media file counts.

### 3. External USB OTG & MicroSD Drives (Guaranteed POSIX)
Due to Android SELinux kernel restrictions on Android 11 through 16, non-root Linux binaries cannot read arbitrary root paths on external drives (e.g., `/storage/XXXX-XXXX/Movies`).

To solve this, Fishbowl automatically discovers connected external drives and generates the guaranteed accessible package directory:
`/storage/<UUID>/Android/data/com.fishbowl.app/files`

1. Move your media files into this directory on your external drive.
2. In Fishbowl, go to **Settings -> Manage Media Storage & Folders**.
3. Tap the `[ App Data (Guaranteed) ]` chip on the external drive card.
4. Tap `[ COPY PATH FOR JELLYFIN ]` and paste it into the Jellyfin Web UI.

### 4. SAF Document Tree Picker (`[ BROWSE (SAF) ]`)
Each drive card includes a direct `[ BROWSE (SAF) ]` action triggering the Android Document Tree picker. Fishbowl resolves selected tree URIs into direct POSIX filesystem paths when available. Virtual cloud storage providers (such as Google Drive or Nextcloud) lacking Linux kernel mount points are quarantined to prevent scanner crashes.

---

## Active Prevention of Empty Libraries in Jellyfin

Tapping `[ COPY PATH FOR JELLYFIN ]` executes an active pre-flight sanity check before copying the path to your clipboard:

1. **Non-Existent Path Warning:** Alerts if the directory does not exist on disk.
2. **SELinux Block Detection:** Detects if Android security restrictions block read access and presents a 1-tap `[ SWITCH & COPY APP DATA ]` shortcut to automatically redirect to the guaranteed external path.
3. **Empty Folder Detection:** Warns if 0 media files are found in the directory, preventing users from creating empty Jellyfin libraries before transferring media files.
4. **Live Subtitles:** Saved media paths display real-time verification status (`X media files verified`, `0 media files detected`, or `Permission blocked by SELinux`) directly beneath the path.
