# Troubleshooting & FAQ

Here are solutions for the most common issues when running Fishbowl on Android.

## Battery & Background Playback Issues

### Q: The server stops when the screen locks or when I leave the app
Many Android devices have aggressive battery management daemons that terminate background services.

**Solution:**
1. Open Android Settings and navigate to **Apps -> Fishbowl -> Battery**.
2. Change the battery optimization setting to **Unrestricted** (or "Don't Optimize").
3. On devices from Xiaomi, Vivo, Samsung, or OnePlus, also enable background auto-start and lock the app in your recent apps tray.

---

## Server Diagnostics & Startup Recovery

### Q: What should I do if the Error Diagnoser flags a startup failure?
Fishbowl v1.4.6 includes an automated Error Diagnoser that inspects startup logs and identifies failure causes:
* **Port Conflict (8096 in use):** Occurs if another instance or media server (like Emby) is running on port 8096. Tap **STOP SERVER**, check background apps, or tap **RESTART SERVER**.
* **SELinux Storage Denial:** Occurs if an invalid or blocked directory is referenced in media folders. Use Fishbowl's Storage manager to verify paths.
* **Corrupted Bootstrap Files:** If files were corrupted during an interrupted download, go to Settings and perform a clean cache re-extraction.

---

## Network & Client Connections

### Q: How do I find my server's LAN address?
On the **Home** tab, once the server status turns to **SERVER RUNNING**, look at the Network cards:
* **Loopback Address:** `http://127.0.0.1:8096` (for playback directly on the Android host device)
* **LAN Address:** `http://192.168.X.X:8096` (for connecting from TVs, laptops, and phones on your Wi-Fi network)
* **Tailscale Address:** `http://100.X.X.X:8096` (for remote streaming over a Tailscale VPN mesh)

### Q: Other devices cannot connect to the server
1. Verify both the Android server and client device are connected to the exact same Wi-Fi network.
2. Check if your Wi-Fi router has **AP Isolation** enabled. If enabled, turn it off so local devices can communicate with each other.
3. Configure a **DHCP Reservation** or Static IP in your router settings if you want the server's IP address to remain permanent.

---

## Storage & External Drives

### Q: Why is my Jellyfin library empty after adding a folder?
There are two common causes:
1. **Android SELinux Restrictions:** On Android 11 through 16, native processes cannot read arbitrary paths on external drives (such as `/storage/XXXX-XXXX/Movies`). Move your files into the guaranteed POSIX app directory (`/storage/<UUID>/Android/data/com.fishbowl.app/files`) which is 100% accessible to Jellyfin's Linux engine.
2. **0 Media Files Detected:** If the selected directory contains only non-media files or unsupported formats, Jellyfin's scanner will find nothing. Use Fishbowl's **Manage Media Storage & Folders** screen to verify that media files (.mp4, .mkv, .mp3, etc.) are present before copying the path.

### Q: Why can't Jellyfin read my USB drive root folder directly?
Android kernel SELinux policies prevent non-root Linux binaries from accessing arbitrary external root mounts. Android explicitly permits access to the app's package directory on the drive (`/storage/<UUID>/Android/data/com.fishbowl.app/files`). Move your media into that folder and use the **COPY PATH** button in Storage settings.

---

## Upgrades & Data Preservation

### Q: Will I lose my libraries or watch history when updating Fishbowl?
**No.** All user configuration files, SQLite databases, user accounts, and watch histories are saved in `--datadir` (`/data/data/com.fishbowl.app/files/home/.local/share/jellyfin`). When you install an updated APK over the existing app, Android preserves this directory completely.
