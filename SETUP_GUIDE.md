# Fishbowl Setup Guide

Complete guide to setting up and hosting Jellyfin on your Android ARM64 device with Fishbowl.

---

## Quick Overview

Fishbowl hosts the full, native **Jellyfin Media Server (v12.1.0)** on your Android device without requiring root access or Docker containers.

### Requirements
- **Android Version:** 5.0 (API 21) or higher (Android 11–16 fully supported)
- **Architecture:** ARM64 (ARMv8 64-bit)
- **RAM:** Minimum 2GB (4GB+ recommended)
- **Network:** Connected to local Wi-Fi or hotspot

---

## Step-by-Step Setup

### Step 1: Download & Install
1. Go to [Fishbowl Releases](https://github.com/Suz41/Fishbowl/releases/tag/v1.4.5).
2. Download the latest `Fishbowl-v1.4.5-release-universal.apk`.
3. Open the downloaded file and install the app.
4. Grant Notification, Storage, and Battery Optimization permissions when prompted.

### Step 2: Start the Server
1. Launch Fishbowl. The server auto-starts in the background on app open.
2. Wait for the status indicator to transition to **SERVER RUNNING**.
3. Tap **OPEN JELLYFIN** to launch the built-in setup wizard.

### Step 3: Run the Setup Wizard
1. Choose your preferred display language.
2. Create an admin username and password.
3. Add your media libraries:
   - **Internal Storage:** `/storage/emulated/0/Movies` or `/storage/emulated/0/Music`
   - **External USB / SD Card:** Copy the dedicated app directory path from **Settings ➔ Manage Media Storage** (`/storage/<UUID>/Android/data/com.fishbowl.app/files`)
4. Finish the wizard and log into your dashboard.

### Step 4: Stream to Other Devices
1. Look at the **LAN Address** card on Fishbowl's home screen (e.g. `http://192.168.1.xxx:8096`).
2. Open any web browser or official Jellyfin client (Android TV, Apple TV, iOS, Roku) connected to the same Wi-Fi network.
3. Enter the server address and your login credentials to start streaming.

---

## Media Organization

Organize media inside standard folder structures for automatic metadata matching:

```
Media/
|-- Movies/
|   |-- Inception (2010)/
|   |   `-- Inception (2010).mkv
|-- TV Shows/
|   `-- Breaking Bad/
|       `-- Season 01/
|           `-- Breaking Bad - S01E01.mkv
`-- Music/
    `-- Artist Name/
        `-- Album Name/
            |-- 01 - Track Name.flac
            `-- cover.jpg
```

---

## Troubleshooting & Tips

- **Server Stops in Background:** Set Fishbowl's battery optimization to **Unrestricted** in Android Settings (`Apps ➔ Fishbowl ➔ Battery ➔ Unrestricted`).
- **Cannot Connect from Another Device:** Ensure both devices are connected to the same Wi-Fi network and AP isolation is disabled on your router.
- **Updating the App:** Install new APK releases directly over the existing app. All user data, accounts, and libraries in `--datadir` (`~/.local/share/jellyfin`) are automatically preserved.
