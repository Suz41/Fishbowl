# Fishbowl

An unofficial standalone server host and launcher for Jellyfin on Android (ARM64).

[![Release](https://img.shields.io/badge/Release-v1.4.6-blue.svg)](https://github.com/Suz41/Fishbowl/releases/tag/v1.4.6)
[![Pre--Release](https://img.shields.io/badge/Test%20Build-v1.4.7--beta.1-orange.svg)](https://github.com/Suz41/Fishbowl/releases/tag/v1.4.7-beta.1)
[![Jellyfin Core](https://img.shields.io/badge/Jellyfin%20Core-12.1.0-purple.svg)](https://jellyfin.org)
[![Runtime](https://img.shields.io/badge/.NET-10.0%20ARM64-512BD4.svg)](https://dotnet.microsoft.com)
[![Platform](https://img.shields.io/badge/Android-ARM64-3DDC84.svg?logo=android&logoColor=white)](https://android.com)
[![License](https://img.shields.io/badge/License-GPLv3-yellow.svg)](LICENSE.md)

> [!IMPORTANT]
> **Disclaimer, Community Notice & Security Transparency:**
> - **Unofficial Project & Upstream Protection:** Fishbowl is an independent, unofficial community project and is **not affiliated with, maintained by, or endorsed by the official Jellyfin project or its core developers**. This project was created utilizing an independent vibe-coding workflow with agentic AI assistants. **Please do NOT contact, trouble, or submit bug reports to official Jellyfin developers, forums, or issue trackers for Fishbowl issues.** All questions and bug reports must be submitted exclusively on this repository's [GitHub Issues](https://github.com/Suz41/Fishbowl/issues).
> - **Voluntary Use & Security Transparency:** Nobody is forced or pressured to download or install this application. We recognize that because this is a vibe-coded project, security-conscious users may have questions or reservations. The entire codebase is 100% open-source and transparent, with zero proprietary tracking SDKs, zero telemetry, and zero third-party analytics. You are freely welcome and encouraged to inspect, decompile, audit the code, monitor network traffic, or test the APK in an isolated environment.
> - **Reproducible Source Builds:** You do not have to trust pre-compiled release binaries. All build configurations are open and reproducible; you can build the application directly from clean source with `./gradlew assembleRelease`.
> - **Mobile Hardware & Thermals:** Smartphones and TV boxes are not rackmount servers. Continuous high-bitrate video transcoding generates significant heat and drains battery. For sustained 24/7 reliability, prioritize Direct Play/Stream and ensure proper physical device cooling.
> - **Provided "As-Is":** Distributed freely under the GNU General Public License v3.0 (GPLv3) for personal experimentation and home-lab use without commercial warranties or service-level agreements.

Fishbowl runs Jellyfin on a compatible ARM64 Android device and provides a native interface for starting the server, viewing status, managing media storage folders, and accessing diagnostic logs without requiring root access, Docker, or external PC hardware.

---

## Quick Start (3 Steps)

1. **Download & Install:**
   - **Recommended Stable Release:** Download [**Fishbowl v1.4.6**](https://github.com/Suz41/Fishbowl/releases/tag/v1.4.6) (`Fishbowl-v1.4.6-release-universal.apk`).
     - **SHA-256:** `D9F9AF398D988AFBF6483E8196F0226B8B90CF0C7557B566537BF20D05E8A930`
   - **Optional Testing Pre-Release:** For users specifically testing USB OTG drives, SD cards, or Storage Access Framework (SAF) folder selection, test builds are available under [**Fishbowl v1.4.7-beta.1**](https://github.com/Suz41/Fishbowl/releases/tag/v1.4.7-beta.1).
2. **Launch & Start:**
   - Open Fishbowl on your device. The server will start automatically. When the status indicator turns green (**SERVER RUNNING**), tap **OPEN JELLYFIN**.
3. **Connect & Stream:**
   - Connect from any Jellyfin client (Smart TV, mobile app, browser) on your local network using the **LAN Address** displayed by Fishbowl (e.g. `http://192.168.1.xxx:8096`).

---

## Preview & Interface

<p align="center">
  <img src="docs/screenshots/01_home_screen.png" width="30%" alt="Fishbowl home screen" />
  &nbsp;
  <img src="docs/screenshots/02_logs_tab.png" width="30%" alt="Fishbowl logs screen" />
  &nbsp;
  <img src="docs/screenshots/03_settings_tab.png" width="30%" alt="Fishbowl settings and diagnostics" />
</p>

---

## Key Features

### Server & Platform Engine
- **Embedded Jellyfin 12.1.0 Core:** Direct POSIX execution on Android's Bionic C runtime (`libc.so`) without containers, emulation, or root access.
- **Embedded .NET 10 Host:** High-performance JIT execution powered by Microsoft .NET 10.0.12 (Linux Bionic ARM64).
- **Embedded FFmpeg Engine:** Bundled mobile FFmpeg binary supporting media streaming, audio remuxing, and HLS playback.
- **Local SQLite3 Database:** Embedded SQLite engine (`libe_sqlite3.so`) managing media libraries, user playback states, and metadata locally.
- **Port Conflict & Health Guard:** Active socket loopback probing and HTTP readiness polling (`/system/info/public` and `/health`) to verify genuine server readiness and prevent collisions.

### User Interface & Diagnostics
- **Comprehensive Error Diagnoser:** Automatically analyzes startup and runtime failures (port conflicts, SELinux blocks, database stalls, or corrupted files) and displays clear recovery instructions.
- **Network Hub:** Live display of local loopback (`127.0.0.1`), local Wi-Fi LAN IP, and Tailscale VPN CGNAT (`100.x.x.x`) addresses with 1-tap copy buttons.
- **Button Rate Limiting & Touch Feedback:** Prevents duplicate service invocations with debounced actions and immediate visual state transitions (`STARTING...`, `RUNNING`, `STOPPING...`).
- **Dual Log Console:** Real-time log terminal with auto-scroll and direct on-disk Serilog log viewer for troubleshooting.
- **Permissions Dashboard:** Live status badges (All-Files Access, Battery Optimization Exemption, Notifications) with 1-tap deep links to system settings.
- **Collapsible Settings Drawers:** Clean accordion drawers for Transcoding & Hardware Codecs and System Components to keep the interface uncluttered.

---

## Client Compatibility

Any client compatible with the official Jellyfin server ecosystem can connect to Fishbowl over your local network:

- **Smart TVs:** Jellyfin for Android TV, Google TV, Fire TV, Roku, Apple TV, and LG webOS.
- **Mobile Devices:** Official Jellyfin Android and iOS clients, Swiftfin, and compatible third-party players.
- **Desktop & Web:** Any modern web browser (Chrome, Firefox, Safari, Edge) or Jellyfin Media Player.
- **Media Centers:** Kodi (via the Jellyfin for Kodi add-on).

Playback capabilities depend on client capabilities, network throughput, and available hardware decoding support. For optimal performance on mobile devices, prioritize Direct Play / Direct Stream.

---

## Storage & Media Setup

Jellyfin requires direct read access to your media files. Fishbowl provides dedicated storage tools under **Settings → Media Storage & Folders** to browse, verify, and copy paths for Jellyfin libraries.

### Internal Storage
Grant the All-Files Storage Access permission when prompted, then select your media directory, such as:
- `/storage/emulated/0/Movies`
- `/storage/emulated/0/Music`
- `/storage/emulated/0/Download`

### USB OTG & External MicroSD Storage
Android 11+ OEM security restricts untrusted application UIDs from traversing raw kernel mount points (`/mnt/media_rw/`). Fishbowl resolves this through two accessible methods:
1. **Public POSIX Storage Mounts:** Fishbowl maps removable drives to standard POSIX `/storage/<UUID>/` paths and verifies directories on the active FUSE layer.
2. **Guaranteed App Data Directory:** When direct traversal on external drives is restricted by OEM SELinux policies, use the guaranteed app data directory created on the external volume:
   ```text
   /storage/<UUID>/Android/data/com.fishbowl.app/files
   ```
   Fishbowl provides a 1-tap quick chip for this path. Media placed inside this folder is guaranteed readable by the server.

### Empty Library Sanity Checks
Tapping **[ COPY PATH FOR JELLYFIN ]** verifies folder contents before copying to clipboard:
- Alerts if the directory does not exist on disk.
- Alerts if Android SELinux blocks native read access, offering a 1-tap redirect to the guaranteed app data folder.
- Alerts if 0 media files are detected, advising you to add video or audio files before initiating a library scan in Jellyfin.

---

## Background Operation & Battery Guidance

Android aggressively terminates background processes to conserve power. To ensure continuous server operation:

1. **Battery Optimization Exemption:** Set Fishbowl's battery usage to **Unrestricted** (or **Don't Optimize**). The exact menu name depends on your device manufacturer:
   - Samsung: Settings → Apps → Fishbowl → Battery → Unrestricted.
   - Xiaomi / HyperOS: Settings → Apps → Manage Apps → Fishbowl → Battery Saver → No Restrictions.
   - Pixel / Stock Android: Settings → Apps → Fishbowl → App Battery Usage → Unrestricted.
2. **Foreground Service & Notification:** Keep the persistent foreground service notification enabled so Android prioritizes the process.
3. **Power Connection:** For extended streaming sessions or initial library indexing, keep the device connected to a charger.

---

## Permissions & Privacy

Fishbowl uses standard Android permissions to provide local server hosting:
- **All-Files Access (`MANAGE_EXTERNAL_STORAGE`):** Required to read user media directories on internal and external storage.
- **Foreground Service (`FOREGROUND_SERVICE`):** Prevents Android from immediately terminating the server when the app is minimized.
- **Wake Lock (`WAKE_LOCK`):** Prevents CPU deep-sleep while streaming media over the network.
- **Notifications (`POST_NOTIFICATIONS`):** Displays the persistent background service status and controls.

**Privacy Guarantee:**
- Zero tracking SDKs, zero telemetry, and zero remote analytics.
- No accounts, no registration, and no cloud dependency.
- All server databases, configurations, and media indexing remain 100% local on your device.

---

## Building From Source

### Prerequisites
- Windows, macOS, or Linux
- OpenJDK 17 or OpenJDK 21
- Android SDK (API 35) and Android NDK
- Git

### Build Instructions
```bash
# Clone the repository
git clone https://github.com/Suz41/Fishbowl.git
cd Fishbowl

# Download Termux bootstrap archives
./gradlew downloadBootstraps

# Build the debug APK
./gradlew assembleDebug

# Build the release universal APK
./gradlew assembleRelease
```

Generated APKs are located at `app/build/outputs/apk/release/Fishbowl-*-release-universal.apk`.

---

## Troubleshooting

### The server stops when I lock the screen or switch apps
Set Fishbowl's battery setting to **Unrestricted**. Some OEM skins (MIUI, OriginOS, ColorOS) require enabling "Autostart" and setting background limits to "No Restrictions" in system app management.

### Other devices cannot connect to the server
- Verify both devices are connected to the same local Wi-Fi network and that your router does not have "AP Isolation" or "Client Isolation" enabled.
- Verify the server status shows **SERVER RUNNING** and port 8096 is not occupied by another app.
- If using Tailscale, ensure both the host device and client device are logged into the same tailnet with active connections.

### Jellyfin library shows no media files after scanning
- Ensure the selected folder contains supported media extensions (`.mp4`, `.mkv`, `.mp3`, `.flac`, etc.).
- On external drives, verify the path starts with `/storage/<UUID>/` rather than `/mnt/media_rw/`.
- If Android SELinux blocks read access on an external drive, move your media files into the guaranteed app directory (`/storage/<UUID>/Android/data/com.fishbowl.app/files`) and select that path.

### How to update without losing library databases or user accounts
Install new release APKs directly over your existing installation. Fishbowl preserves your database, configuration files, and user libraries across upgrades. Avoid using "Clear Data" in Android system settings, as this resets the server state.

---

## Disclosures & License

Fishbowl is an unofficial community project and is not supported, maintained, or endorsed by the official Jellyfin project.

This project is licensed under the [GNU General Public License v3.0](LICENSE.md).

- **Jellyfin:** Licensed under GNU General Public License v3.0.
- **Termux Components:** Licensed under GNU General Public License v3.0.
- **.NET Runtime:** Licensed under the MIT License.
- **FFmpeg:** Licensed under GNU General Public License v3.0 / LGPL v3.0.
- **SQLite:** Public Domain.

This application is provided freely without warranties or service-level guarantees. Always back up important library configurations and use this project for personal experimentation and home-lab use.
