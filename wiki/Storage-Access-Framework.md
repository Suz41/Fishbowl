# Storage Access Framework (SAF) Bridge

Starting with Android 11, the OS enforces Scoped Storage boundaries to restrict applications from freely traversing local storage. 

To stream media stored on non-standard directories, SD cards, or external USB OTG drives, Fishbowl provides storage routing mechanisms.

## Option 1: Internal Storage (Direct POSIX)
Grant **All-Files Access** (`MANAGE_EXTERNAL_STORAGE`) when prompted. You can point Jellyfin directly to:
* `/storage/emulated/0/Movies`
* `/storage/emulated/0/Music`
* `/storage/emulated/0/Download`

## Option 2: USB OTG & MicroSD Drives (Guaranteed POSIX)
Due to Android SELinux kernel restrictions on Android 11–16, non-root Linux binaries are blocked from reading the raw root of external drives (`/storage/XXXX-XXXX/`). 

Fishbowl automatically discovers your connected drive and provides the guaranteed app package directory:
`/storage/<UUID>/Android/data/com.fishbowl.app/files`

1. Move your media into this directory on your USB drive.
2. In Fishbowl, go to **Settings ➔ Manage Media Storage**.
3. Tap **COPY PATH** next to your USB drive.
4. Paste the path directly into the Jellyfin Web UI when creating a media library.

## Option 3: SAF Folder Bridge [TEST MODE]
Fishbowl includes an experimental Storage Access Framework (SAF) folder picker:
* **Virtual Cloud Providers (RSAF, Google Drive, Nextcloud):** These services exist strictly in Android Java (`content://`) space without Linux kernel mount points. Fishbowl quarantines these selections and alerts the user, preventing broken path entries from crashing Jellyfin's directory scanner.
