# Fishbowl

An unofficial standalone server host and launcher for Jellyfin on Android (ARM64).

[![Release](https://img.shields.io/badge/Release-v1.4.6-blue.svg)](https://github.com/Suz41/Fishbowl/releases/tag/v1.4.6)
[![Jellyfin Core](https://img.shields.io/badge/Jellyfin%20Core-12.1.0-purple.svg)](https://jellyfin.org)
[![Runtime](https://img.shields.io/badge/.NET-10.0%20ARM64-512BD4.svg)](https://dotnet.microsoft.com)
[![Platform](https://img.shields.io/badge/Android-5.0%2B%20(ARM64)-3DDC84.svg?logo=android&logoColor=white)](https://android.com)
[![License](https://img.shields.io/badge/License-GPLv3-yellow.svg)](LICENSE.md)
[![Privacy](https://img.shields.io/badge/Telemetry-0%25%20(100%25%20Local)-success.svg)](#permissions--privacy)

> [!IMPORTANT]
> **Disclaimer & Community Notice:**
> Fishbowl is an independent, unofficial community project and is **not affiliated with, maintained by, or endorsed by the official Jellyfin project or its core developers**. This project was built using an independent vibe-coding workflow with agentic AI assistants.
> - **Upstream Protection:** **Please do not contact, bother, or submit bug reports to the official Jellyfin developers, forums, or issue trackers for issues with Fishbowl.** All bug reports and questions must be directed exclusively to this repository's [GitHub Issues](https://github.com/Suz41/Fishbowl/issues).
> - **Security, Transparency & Voluntary Use:** Nobody is forced or pressured to download or install this application. We recognize that because this is a vibe-coded project, security-conscious users may have questions or concerns. The entire project is 100% open-source with full transparency, zero trackers, and zero telemetry. You are freely welcome and encouraged to inspect, decompile, audit the code and network traffic, or test the APK in an isolated environment.
> - **Self-Build Verification:** You do not have to trust pre-compiled release binaries. All build scripts are open and reproducible; you can build your own APK directly from source using `./gradlew assembleRelease`.
> - **Mobile Hardware Expectations:** Smartphones and TV boxes are not rackmount servers. Continuous high-bitrate video transcoding generates heat and consumes battery. For 24/7 reliability, prioritize Direct Play/Stream and ensure proper device ventilation.
> - **Provided "As-Is":** Distributed freely under the GPLv3 license for personal experimentation and home lab use without commercial warranties or service-level agreements.

Fishbowl runs the full **Jellyfin Media Server (v12.1.0)** natively on your Android device (**ARM64**). It transforms any spare Android phone, tablet, or TV box into a dedicated, low-power home streaming server without requiring root access, Docker, or external PC hardware.

---

## Quick Start (3 Steps)

1. **Download & Install:**
   Grab the latest [Fishbowl-v1.4.6-release-universal.apk](https://github.com/Suz41/Fishbowl/releases/tag/v1.4.6) and install it on your ARM64 Android device.
2. **Launch & Start:**
   Open **Fishbowl**. The server auto-starts in the background. When the status badge turns green (**SERVER RUNNING**), tap **OPEN JELLYFIN**.
3. **Connect & Stream:**
   Connect any official Jellyfin client (TV, phone, browser) on your Wi-Fi using the **LAN Address** displayed on your home screen (e.g. `http://192.168.1.xxx:8096`).

---

## Preview & Interface

<p align="center">
  <img src="docs/screenshots/01_home_screen.png" width="30%" alt="Home Screen" />
  &nbsp;
  <img src="docs/screenshots/02_logs_tab.png" width="30%" alt="Logs Console" />
  &nbsp;
  <img src="docs/screenshots/03_settings_tab.png" width="30%" alt="Settings & Diagnostics" />
</p>

---

## Table of Contents

- [Quick Start (3 Steps)](#quick-start-3-steps)
- [Key Features](#key-features)
- [Client Compatibility](#client-compatibility)
- [Storage & Media Setup](#storage--media-setup)
- [Background Resilience](#background-resilience)
- [System Architecture](#system-architecture)
- [Permissions & Privacy](#permissions--privacy)
- [Directory Structure](#directory-structure)
- [Building From Source](#building-from-source)
- [Troubleshooting & FAQ](#troubleshooting--faq)
- [Disclosures & License](#disclosures--license)

---

## Key Features

### Server Engine
- **Jellyfin 12.1.0 Core:** Direct POSIX execution on Android Bionic C runtime (`libc.so`) without virtualization or containers.
- **Embedded .NET 10 Host:** High-throughput JIT execution powered by Microsoft .NET 10.0.12 (Linux Bionic ARM64).
- **Jellyfin-FFmpeg Engine:** Native mobile FFmpeg binary supporting on-the-fly transcoding, audio remuxing, and HLS streaming.
- **SQLite3 Database Engine:** Local low-latency database storage (`libe_sqlite3.so`) for library indexes, user states, and metadata.
- **Port Conflict & Health Guard:** Active socket loopback probing (`isPortListening`) and strict verification of public Jellyfin system endpoints (`/system/info/public` and `/health`) to prevent false-positive reconciles, startup staging hangs, and Emby port 8096 collisions.
- **Hardened Bootstrap Extraction:** Proactive write permission enforcement (`setWritable`) and destination unlinking eliminates Android `EACCES (Permission denied)` errors during clean installs.

### User Interface & Diagnostics
- **Comprehensive Error Diagnoser:** Real-time root-cause analysis that identifies the exact cause of any runtime failure (port collision, SELinux block, EF Core migration hang, or corrupted extraction) with actionable recovery guidance.
- **Button Rate Limiting & Pressed State Feedback:** Primary actions (`START SERVER`, `STOP SERVER`, `RESTART SERVER`) enforce active lockouts and immediate visual state transitions (`SERVER STARTING...`, `SERVER RUNNING`, `STOPPING...`) to prevent duplicate service invocations.
- **Auto-Scrolling Log Terminal:** Real-time log terminal and Serilog viewers maintain persistent auto-scroll lock to the latest execution events.
- **Live System Permissions & Privileges:** Real-time detection badges for All-Files Access, Battery Optimization Exemption, and Notifications with one-tap deep links.
- **Collapsible Settings Drawers:** Clean accordion drawers (`▼` / `▲`) for Transcoding & Hardware Codecs and System Components & Security to minimize visual clutter.
- **Clear Cache Alert Styling:** Distinct alert red trigger for cache cleanup.
- **Interactive Setup & Extraction Overlay:** Dedicated onboarding overlay with real-time percentage progress (0% to 100%), five-stage checklist, and live log stream during initial runtime extraction or reinstallations.
- **Zero-Crash Resilient Architecture:** Pre-cached tab navigation (Home, Logs, Settings) eliminates fragment recreation crashes and state loss.
- **On-Disk Serilog Log Reader:** Dedicated `DISK LOG` / `CONSOLE` toggle and `COPY` button on both the home dashboard and full-screen Logs viewer to inspect internal Jellyfin logs directly from disk.
- **Throttled Log Queue:** Thread-safe UI log buffer eliminates main thread looper congestion and ANRs during high-volume server operations.
- **Safe Background Minimization:** Back-press safely moves application to background (`moveTaskToBack`) without terminating or restarting the active server session.
- **Automatic Auto-Start:** Server lifecycle begins immediately on app launch without manual intervention.
- **Material 3 Pixel UI:** OLED pure dark mode (`#121316`) with tactile spring animations on all touch targets.
- **Live Lifecycle Pipeline:** Step-by-step progress tracking (`STARTING_RUNTIME` -> `LAUNCHING_SERVER` -> `WAITING_FOR_SERVER` -> `READY`).
- **Network Address Hub:** Instant local loopback, Wi-Fi LAN IP, and Tailscale VPN CGNAT (`100.x.x.x`) IP resolution with locked display and one-tap `COPY IP` buttons.
- **Dedicated Diagnostics:** Real-time software and hardware transcoding test suite in Settings.
- **Community Links:** Direct links to the Fishbowl GitHub repository and issue tracker in the About drawer.

---

## Client Compatibility

Stream to any standard Jellyfin client across your local network:

| Client App | Platforms | Playback Capabilities |
| :--- | :--- | :--- |
| **Jellyfin Android TV** | Google TV, FireStick, Shield TV, Mi Box | 4K HDR, HEVC, Dolby Vision, Direct Play |
| **Jellyfin Mobile** | Android Smartphones & Tablets | Gestures, Background Audio, Casting |
| **Jellyfin iOS** | iPhone, iPad | Native Player, HLS Streaming, AirPlay |
| **Web Browser** | Chrome, Firefox, Safari, Edge, Brave | HTML5 Player, Administrative Dashboard |
| **Roku Client** | Roku Streaming Sticks, Roku Smart TVs | Direct Play, Remuxing |
| **Swiftfin & Infuse** | Apple TV, macOS, iOS | Native Metal Decoding, Subtitle Sync |
| **Kodi Integration** | Windows, Linux, LibreELEC, Raspberry Pi | Jellyfin for Kodi Addon / Direct Path |

---

## Storage & Media Setup

Jellyfin runs as a native Linux process (`dotnet jellyfin.dll`) and requires standard POSIX paths (`/storage/...`) to index and stream media.

### Consolidated Media Storage & Folders (Option A)
The storage manager (**Settings ➔ Manage Media Storage & Folders**) provides a consolidated interface for browsing, validating, and selecting media directories:
- **Active Path Card:** Displays your currently selected folder with live verification status and media item counts. Includes an interactive `[ ENTER PATH ]` dialog to type or paste any internal, SD card, USB OTG, or symlink directory.
- **Empty Library Prevention:** Pre-flight checks intercept `[ COPY PATH FOR JELLYFIN ]`:
  - **Missing Directory:** Warns that the path does not exist on disk.
  - **SELinux Block:** Alerts if Android SELinux restricts native read access and provides a 1-tap `[ SWITCH & COPY APP DATA ]` redirect.
  - **Empty Folder:** Alerts if 0 media files are detected, advising you to add media files before scanning in Jellyfin to prevent empty libraries.

### Option 1: Internal Storage (Recommended)
Grant **All-Files Access** when prompted. You can immediately point Jellyfin libraries to your standard internal folders using the quick chips:
- `/storage/emulated/0/Movies`
- `/storage/emulated/0/Music`
- `/storage/emulated/0/Download`

### Option 2: USB OTG & MicroSD Drives (Guaranteed POSIX)
Due to Android SELinux boundaries on Android 11 through 16, non-root Linux binaries cannot read the raw root of external drives. Fishbowl automatically creates and exposes the guaranteed app directory on external storage:
- `/storage/<UUID>/Android/data/com.fishbowl.app/files`

**How to use external media:**
1. Move your movies and music into the guaranteed path above on your external drive.
2. In Fishbowl, go to **Settings ➔ Manage Media Storage & Folders**.
3. Tap the **[ App Data (Guaranteed) ]** chip on your external drive card.
4. Tap **COPY PATH FOR JELLYFIN**.
5. Paste the path into the Jellyfin Web UI when adding your media library.

### Option 3: Document Tree Browser & Custom Path Input
- **`[ BROWSE (SAF) ]`:** Native Android Storage Access Framework document tree picker that resolves selected tree URIs to their physical filesystem POSIX mount points.
- **`[ ENTER PATH ]`:** Custom path dialog allows direct input of any directory with real-time path verification.

---

## Background Resilience

- **Foreground Service:** Runs `JellyfinServerService` with `foregroundServiceType="mediaPlayback"` and ongoing notification (ID `1001`) to protect the process from OS memory management.
- **CPU WakeLock:** Maintains an active `PARTIAL_WAKE_LOCK` with auto-timeout safeguards so media streaming continues with the screen turned off.
- **Battery Optimization Exemption:** Prompts for battery unrestricted mode to prevent OEM task killers (Vivo, Xiaomi, Samsung, Huawei) from terminating the background server.
- **State Hydration:** Asynchronously syncs server lifecycle state instantly on app resume.

---

## System Architecture

```
+-------------------------------------------------------------------------+
|                           Android User Space                            |
|                                                                         |
|  +-------------------------------------------------------------------+  |
|  |                     Fishbowl UI Shell                           |  |
|  |   JellyfinDroidActivity (3-Tab Pixel UI / Home / Logs / Settings)  |  |
|  |   JellyfinWebActivity   (Hardware-Accelerated WebView Client)     |  |
|  |   JellyfinStorageActivity (SAF Document Tree File Bridge)         |  |
|  +-------------------------------------------------------------------+  |
|                                  |                                      |
|  +-------------------------------------------------------------------+  |
|  |                    Lifecycle & Service Layer                      |  |
|  |   JellyfinController    (State Machine: STARTING -> READY)        |  |
|  |   JellyfinServerService (Foreground Service / Ongoing Notification)| |
|  |   JellyfinBootstrapper  (Asset Extraction & Dynamic DNS Setup)    |  |
|  +-------------------------------------------------------------------+  |
|                                  |                                      |
|  +-------------------------------------------------------------------+  |
|  |                     Native Subsystem (POSIX)                      |  |
|  |   .NET 10 Host (dotnet) ---> Jellyfin.Server.dll (12.1.0)         |  |
|  |   Jellyfin-FFmpeg Engine ---> Hardware Transcoding / HLS Remux     |  |
|  |   libe_sqlite3.so Engine ---> Database Storage (~/.local/share)   |  |
|  |   libfontconfig / libfreetype ---> Subtitle Burn-In Engine        |  |
|  +-------------------------------------------------------------------+  |
|                                                                         |
+-------------------------------------------------------------------------+
```

---

## Permissions & Privacy

Fishbowl operates entirely within standard Android OS sandboxing rules:

| Permission | Purpose |
| :--- | :--- |
| `FOREGROUND_SERVICE` | Keeps server process active in background |
| `FOREGROUND_SERVICE_MEDIA_PLAYBACK` | Android 14+ background media streaming classification |
| `WAKE_LOCK` | Prevents CPU sleep during playback when screen is off |
| `INTERNET` & `ACCESS_NETWORK_STATE` | Manages LAN and local socket connections |
| `MANAGE_EXTERNAL_STORAGE` | Grants access to local movie and music files |

**Privacy Guarantee:**
- 0 Remote Trackers
- 0 Analytics SDKs
- 0 Telemetry Sockets
- 100% Local Processing

---

## Directory Structure

All persistent server state is stored within the application sandboxed directory:

| Type | Path | Description |
| :--- | :--- | :--- |
| **System Prefix** | `/data/data/com.fishbowl.app/files/usr` | Native binaries, Dotnet runtime, and libraries |
| **Data (`--datadir`)** | `/data/data/com.fishbowl.app/files/home/.local/share/jellyfin` | Database (`jellyfin.db`), accounts, and watch history (preserved across updates) |
| **Config (`--configdir`)** | `/data/data/com.fishbowl.app/files/home/.config/jellyfin` | Server XML configuration (`system.xml`, `network.xml`) |
| **Cache (`--cachedir`)** | `/data/data/com.fishbowl.app/files/home/.cache/jellyfin` | Image thumbnails and transcode buffers |
| **Logs (`--logdir`)** | `/data/data/com.fishbowl.app/files/home/.local/share/jellyfin/log` | Native server logs |

---

## Building From Source

### Prerequisites
- Windows 10/11, macOS, or Linux
- OpenJDK 17 or OpenJDK 21
- Android SDK (API 28+) and NDK (r25+)
- Gradle (wrapper included)

### Build Commands
```bash
# Clone the repository
git clone https://github.com/Suz41/Fishbowl.git
cd Fishbowl

# Build Debug APK
./gradlew assembleDebug

# Build Universal Release APK
./gradlew assembleRelease
```
Generated APKs will be located in `app/build/outputs/apk/release/` and `app/build/outputs/apk/debug/`.

---

## Troubleshooting & FAQ

#### Q: Server stops when I lock my phone or switch apps?
**A:** Set battery optimization to **Unrestricted** in Android Settings (`Apps ➔ Fishbowl ➔ Battery ➔ Unrestricted`). OEM skins (MIUI, HyperOS, OriginOS, OneUI) may terminate background tasks aggressively if this is not set.

#### Q: How do I access Jellyfin from other devices on my network?
**A:** Open Fishbowl, copy the **LAN Address** shown on your home screen (e.g. `http://192.168.1.xxx:8096`), and enter that URL into any browser or Jellyfin app on any device connected to the same Wi-Fi.

#### Q: Why can't Jellyfin read my USB drive root folder directly?
**A:** Android 11+ kernel SELinux policies block non-root Linux binaries from accessing arbitrary external root mounts. Android explicitly allows access to the app's package directory on the drive (`/storage/<UUID>/Android/data/com.fishbowl.app/files`). Move media there and use the one-tap **COPY PATH** button in Fishbowl Storage settings.

#### Q: Why is my Jellyfin media library empty after adding a folder?
**A:** There are two common causes:
1. **SELinux Permissions on External Drives:** On Android 11 through 16, native processes cannot read arbitrary paths on external drives (e.g. `/storage/XXXX-XXXX/Movies`). To prevent empty libraries, move your media to the guaranteed POSIX app directory (`/storage/<UUID>/Android/data/com.fishbowl.app/files`) which is 100% readable by Jellyfin's Linux engine.
2. **Empty Folders or Unsupported File Formats:** If the selected directory contains 0 detected media files, Jellyfin's scanner will find nothing. In Fishbowl, go to **Settings ➔ Manage Media Storage & Folders** to verify your path before copying. Fishbowl will warn you if 0 media files are found or if SELinux blocks read access.

#### Q: How do I update Fishbowl without losing my libraries or watch history?
**A:** Install updated APKs over your existing installation. All database files and configurations are stored in `--datadir` (`~/.local/share/jellyfin`) and survive APK upgrades.

---

## Disclosures & License

### Unofficial Community Project & Upstream Notice
- **Not an Official Jellyfin Product:** Fishbowl is an independent, unofficial launcher and container environment. It is not affiliated with, endorsed by, maintained by, or supported by the official Jellyfin project or its development team.
- **Do Not Contact Official Jellyfin Developers:** Please **do not contact, trouble, or open bug reports with the official Jellyfin developers, Discord, or community forums** regarding Fishbowl crashes, packaging errors, or port issues. All bug reports and feature requests must be opened exclusively on this project's [GitHub Issues](https://github.com/Suz41/Fishbowl/issues).
- **Vibe-Coded Architecture:** This project was developed utilizing a vibe-coding methodology with conversational agentic AI coding assistants (Google Antigravity / Gemini models) and tested empirically on physical ARM64 hardware.

### Security, Transparency & Voluntary Usage
- **Complete Open Transparency:** Every line of application code, build script, packaging routine, and CI workflow is published openly in this repository. There are no obfuscated blobs, no closed-source analytics SDKs, and zero telemetry endpoints.
- **Open to Auditing & Inspection:** We recognize that because this project is vibe-coded, users may naturally have security or stability questions. Anyone is freely welcome and actively encouraged to inspect the source code, decompile the APK, monitor background sockets, or test network traffic in a sandbox or isolated network environment.
- **Voluntary Exploration:** Nobody is forced, pressured, or obligated to download or install Fishbowl. It is provided freely for enthusiasts and community members who wish to explore, test, or run self-hosted media streaming on Android hardware.
- **Independent Self-Build Verification:** You do not have to rely on pre-compiled release artifacts. Full build commands and Gradle configuration files are maintained in the repository, enabling anyone to compile their own release APK directly from clean source via `./gradlew assembleRelease`.
- **Hardware & Thermal Expectations:** Running a multi-threaded media server on mobile SoC architectures naturally differs from running on active-cooled x86 servers. Extended software transcoding tasks generate heat and battery drain. Users are encouraged to prioritize Direct Play configurations and keep host devices properly ventilated on stable power.
- **"As-Is" Hobbyist Basis:** Distributed under the terms of the GNU GPLv3 without warranties or SLAs. It is built as a community exploration project to prove what mobile hardware can do.

### Licenses & Upstream
- **Fishbowl:** Licensed under the [GNU General Public License v3.0 (GPLv3)](LICENSE.md).
- **Jellyfin Core:** [Jellyfin Project](https://jellyfin.org) (GPLv3).
- **Packaging Foundation:** Built upon the [Termux](https://github.com/termux/termux-app) open-source container architecture.
