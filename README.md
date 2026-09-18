# Fishbowl

An unofficial standalone server host/launcher for Jellyfin on Android (ARM64).

[![Release](https://img.shields.io/badge/Release-v1.4.5-blue.svg)](https://github.com/Suz41/Fishbowl/releases/tag/v1.4.5)
[![Jellyfin Core](https://img.shields.io/badge/Jellyfin%20Core-12.1.0-purple.svg)](https://jellyfin.org)
[![Runtime](https://img.shields.io/badge/.NET-9.0%20ARM64-512BD4.svg)](https://dotnet.microsoft.com)
[![Platform](https://img.shields.io/badge/Android-5.0%2B%20(ARM64)-3DDC84.svg?logo=android&logoColor=white)](https://android.com)
[![License](https://img.shields.io/badge/License-GPLv3-yellow.svg)](LICENSE.md)
[![Privacy](https://img.shields.io/badge/Telemetry-0%25%20(100%25%20Local)-success.svg)](#permissions--privacy)

Fishbowl is a native Android application that hosts and runs the full **Jellyfin Media Server (v12.1.0)** directly on your Android device (**ARM64**). 

It transforms any Android phone, tablet, or TV box into a standalone, energy-efficient home media streaming server without requiring root access, Docker, or external PC hardware.

---

## Table of Contents

- [AI/LLM Usage Disclosure](#aillm-usage-disclosure)
- [Key Features & Highlights](#key-features--highlights)
- [System Architecture](#system-architecture)
- [Client Ecosystem & Compatibility](#client-ecosystem--compatibility)
- [Step-by-Step Installation & Setup](#step-by-step-installation--setup)
- [Storage & Media Library Access](#storage--media-library-access)
- [Dynamic Metadata DNS Pipeline](#dynamic-metadata-dns-pipeline)
- [Memory-Safe Logging Engine](#memory-safe-logging-engine)
- [Permissions & Privacy](#permissions--privacy)
- [Directory Hierarchy & Persistence](#directory-hierarchy--persistence)
- [Building From Source](#building-from-source)
- [Troubleshooting & FAQ](#troubleshooting--faq)
- [License & Acknowledgments](#license--acknowledgments)

## AI/LLM Usage Disclosure

In compliance with open-source community standards and the Jellyfin project's AI/LLM policies, we disclose that:
* **Vibe Coding:** This project was built utilizing a "vibe coding" methodology—relying on conversational programming, interactive agent instruction, and iterative design loops to rapidly prototype and assemble the application.
* **Development Assistance:** The Java wrappers, UI layouts, shell structures, and build configuration files were generated with the assistance of agentic AI coding tools (specifically Google Antigravity / Gemini models).
* **Review & Verification:** All generated code has been manually audited, integrated, refactored, and empirically tested on physical hardware (ARM64 Android devices) to ensure reliability, security, and performance. No code is shipped without manual verification.
* **Documentation & Assets:** README documentation, architectural diagrams, and project metadata templates were refined or generated with the aid of LLM tools.

---

## Key Features & Highlights

### Native ARM64 Server Engine
- **Full Jellyfin 12.1.0 Core:** Direct POSIX execution on Android's Bionic C runtime (`libc.so`) without virtualization or Docker containers.
- **Embedded .NET 9.0 Host:** High-throughput JIT-compiled server core with optimized memory management.
- **Jellyfin-FFmpeg Transcoder:** Mobile-optimized native FFmpeg binary for on-the-fly video transcoding, audio remuxing, and HLS segmenting.
- **SQLite3 Database Engine (`libe_sqlite3.so`):** Low-latency local database storage managing media libraries, user watch states, and item metadata.

### Material 3 Pixel UI Shell & Tactile Animations
- **Automatic Server Auto-Start:** Launches server lifecycle automatically in background on app startup without requiring manual intervention.
- **Pure Dark Mode (`#121316`):** OLED-optimized dark theme with distinct surface cards, crisp typography, and fluid spring press animations (0.96x compression) on all buttons.
- **Real-Time Startup Progression:** Displays authentic multi-stage initialization (`STARTING_RUNTIME` -> `LAUNCHING_SERVER` -> `WAITING_FOR_SERVER` -> `CHECKING_READINESS` -> `READY`).
- **Multi-Network Address Hub:** Instant local loopback (`http://127.0.0.1:8096`), Wi-Fi LAN IP, and dynamic Tailscale VPN CGNAT (`100.x.x.x`) IP resolution with locked display across tab switches and one-tap `COPY IP` buttons.
- **3-Tab Navigation:**
  - **Home:** Server lifecycle dashboard, status pills, address cards, and action buttons (`OPEN JELLYFIN`, `START`, `STOP`, `RESTART`).
  - **Logs:** Memory-capped, live-streaming diagnostic console with 500ms throttled rendering and smart auto-scrolling.
  - **Settings:** Storage breakdown, SAF media folder bridge, software stack audit, and boot auto-start switches.

### Background Streaming Resilience
- **Foreground Service Supervision:** Managed by `JellyfinServerService` with media playback classification (`foregroundServiceType="mediaPlayback"`) and an ongoing status notification (ID `1001`) preventing Android OS memory killing.
- **Battery Optimization Safeguard:** Prompts for `REQUEST_IGNORE_BATTERY_OPTIMIZATIONS` exemption to prevent background termination on aggressive OEM devices (Vivo, Xiaomi, Samsung, Moto).
- **Smart CPU WakeLock:** Automatically acquires a `PARTIAL_WAKE_LOCK` with an automatic safety timeout to maintain uninterrupted streaming when the screen is locked.
- **Immediate State Hydration:** Asynchronous `reconcileStateAsync()` directly resolves running server state on app launch/resume with zero UI latency.

### Built-in Hardware-Accelerated WebView
- Native in-app WebView container allowing immediate local playback and administrative server configuration without leaving the application.

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
|  |   .NET 9.0 Host (dotnet) ---> Jellyfin.Server.dll (12.1.0)        |  |
|  |   Jellyfin-FFmpeg Engine ---> Hardware Transcoding / HLS Remux     |  |
|  |   libe_sqlite3.so Engine ---> Database Storage (~/.local/share)   |  |
|  |   libfontconfig / libfreetype ---> Subtitle Burn-In Engine        |  |
|  +-------------------------------------------------------------------+  |
|                                                                         |
+-------------------------------------------------------------------------+
```

---

## Client Ecosystem & Compatibility

Once Fishbowl is active on your device, you can stream your movies, shows, and music to any official Jellyfin client across your local home network:

| Client App | Supported Platforms | Features & Direct Stream |
| :--- | :--- | :--- |
| **Jellyfin for Android TV** | Google TV, FireStick, Shield TV, Xiaomi Box | 4K HDR, HEVC, Dolby Vision, Direct Play |
| **Jellyfin Mobile** | Android Smartphones & Tablets | Touch Gestures, Background Audio, Casting |
| **Jellyfin for iOS** | iPhone, iPad | Native Player, HLS Streaming, AirPlay |
| **Web Browser** | Chrome, Firefox, Safari, Edge, Brave | Full HTML5 Video Player, Admin Dashboard |
| **Roku Client** | Roku Streaming Sticks, Roku Smart TVs | Direct Play, On-The-Fly Transcoding |
| **Swiftfin & Infuse** | Apple TV, macOS, iOS | Native Metal Decoding, Subtitle Sync |
| **Kodi Integration** | Windows, Linux, LibreELEC, Raspberry Pi | Jellyfin for Kodi Addon / Direct Path |

> [!TIP]
> **Connecting Clients:** Open any Jellyfin client on the same Wi-Fi network and enter the **LAN Address** shown on your Fishbowl Home screen (e.g., `http://<YOUR-DEVICE-LAN-IP>:8096` or `http://192.168.x.x:8096`).

---

## Step-by-Step Installation & Setup

### 1. Download & Install
1. Download the latest **`Fishbowl-v1.4.5-debug-arm64-v8a.apk`** from [GitHub Releases](https://github.com/Suz41/Fishbowl/releases/tag/v1.4.5).
2. Install the APK on your ARM64 Android device.
3. Grant **Notification**, **Storage**, and **Battery Optimization Exception** permissions when prompted.

### 2. Starting the Server
1. Launch **Fishbowl** (server auto-starts automatically on app open).
2. The stage pipeline will verify runtime environment, start the Dotnet process, and poll HTTP readiness.
3. Once the hero badge turns green (**SERVER RUNNING**), tap **OPEN JELLYFIN**.

### 3. Initial Setup Wizard
1. Select your preferred display language.
2. Create your admin username and password.
3. Add your media libraries (point to `/storage/emulated/0/Movies`, `/storage/emulated/0/Music`, or your external USB/SD card app path).
4. Configure your preferred metadata language (English, Spanish, French, German, etc.).
5. Finish the wizard and start streaming!

---

## Storage & Media Library Access

Jellyfin runs as a native Linux process (`dotnet jellyfin.dll`) and requires real POSIX filesystem paths (`/storage/...`) to read media files.

### 1. Internal Shared Storage (Standard)
* When **All-Files Access** (`MANAGE_EXTERNAL_STORAGE`) is granted, Jellyfin can directly index and read standard internal paths:
  * `/storage/emulated/0/Movies`
  * `/storage/emulated/0/Music`
  * `/storage/emulated/0/Download`

### 2. External USB OTG & MicroSD Drives (Guaranteed POSIX)
* Due to Android SELinux security boundaries on modern Android (11–16), direct raw root access to external USB drives (e.g. `/storage/XXXX-XXXX/`) is blocked at the kernel level for non-system native binaries.
* Fishbowl automatically detects connected external storage and provides the guaranteed app package directory:
  * `/storage/<UUID>/Android/data/com.fishbowl.app/files`
* Files placed inside this directory are 100% accessible to Jellyfin's POSIX engine without permission errors. Use the built-in **`COPY PATH`** button in the Storage settings.

### 3. SAF Document Tree Bridge [TEST MODE]
* Fishbowl includes an experimental Storage Access Framework (SAF) folder picker for translating document URIs.
* **Virtual Cloud Providers (RSAF, Google Drive, Nextcloud):** These services exist strictly in Android Java (`content://`) space and do not create Linux kernel mount points. Fishbowl quarantines these selections and informs the user, preventing broken path entries from crashing Jellyfin's directory scanner.

---

## 🌐 Dynamic Metadata DNS Pipeline

Fishbowl uses dynamic multi-provider DNS resolution to ensure movie and TV show identification, metadata retrieval, and artwork fetching operate smoothly:

- **DNS Resolvers:** Embedded POSIX environment dynamically resolves DNS via Google DNS (`8.8.8.8`) and Cloudflare DNS (`1.1.1.1`).
- **Metadata Endpoints:** Seamlessly fetches data and posters from:
  - The Movie Database (TMDb): `api.themoviedb.org`, `image.tmdb.org`
  - The Open Movie Database (OMDb)
  - Internet Movie Database (IMDb) metadata scrapers
  - OpenSubtitles & MusicBrainz
- **SSL / TLS Trust Store:** Built-in Mozilla CA Certificate bundle (`cacert.pem`) ensures secure HTTPS communication with all upstream providers.

---

## 📊 Memory-Safe Logging Engine

Fishbowl features a hardened logging subsystem designed to prevent UI freezes during heavy transcoding or library imports:

- **Decoupled Observers (`LogListener`):** Log stream updates are completely separated from Activity state rendering.
- **500ms Handler Throttling:** Incoming log lines from stdout/stderr are batched and posted to the UI looper at most 2 times per second.
- **30KB Bounded Ring Buffer:** Memory consumption is strictly capped at ~500 lines, automatically discarding stale log lines without triggering garbage collection spikes.
- **Smart Viewport Auto-Scrolling:** Auto-scroll engages *only* when the user is already at the bottom of the log view, allowing you to scroll up and inspect past events without viewport jumping.

---

## 🔒 Permissions & Privacy

Fishbowl operates strictly within standard Android OS application sandboxing rules:

| Android Permission | Purpose |
| :--- | :--- |
| `FOREGROUND_SERVICE` | Keeps server process active in background |
| `FOREGROUND_SERVICE_MEDIA_PLAYBACK` | Android 14+ FGS media streaming classification |
| `WAKE_LOCK` | Prevents CPU sleep during active media streaming |
| `INTERNET` & `ACCESS_NETWORK_STATE` | Handles LAN and loopback socket communication |
| `READ_EXTERNAL_STORAGE` / `MANAGE_EXTERNAL_STORAGE` | Accesses local movie, music, and show files |

**Privacy Verification:**
- 0 Remote Trackers
- 0 Analytics Libraries
- 0 Telemetry Sockets
- 100% Local Storage & Processing

---

## 📁 Directory Hierarchy & Persistence

All Jellyfin server configuration, database, and cache files are stored within the app's sandboxed data directory:

| Directory Type | Absolute Android Path | Purpose |
| :--- | :--- | :--- |
| **System Prefix** | `/data/data/com.fishbowl.app/files/usr` | Native binaries, Dotnet assemblies, FFmpeg executables, and shared C/C++ libraries. |
| **Persistent Data (`--datadir`)** | `/data/data/com.fishbowl.app/files/home/.local/share/jellyfin` | Jellyfin SQLite database (`jellyfin.db`), user accounts, library definitions, and metadata. **Survives all updates.** |
| **Configuration (`--configdir`)** | `/data/data/com.fishbowl.app/files/home/.config/jellyfin` | Server XML configuration (`system.xml`, `network.xml`, `encoding.xml`). |
| **Cache (`--cachedir`)** | `/data/data/com.fishbowl.app/files/home/.cache/jellyfin` | Image thumbnail caches, transcoded media chunks, and temporary streaming buffers. |
| **Logs (`--logdir`)** | `/data/data/com.fishbowl.app/files/home/.local/share/jellyfin/log` | On-disk server execution logs. |

---

## 🛠️ Building From Source

### Prerequisites
- **Operating System:** Windows 10/11, macOS, or Linux
- **JDK:** OpenJDK 17 or OpenJDK 21
- **Android SDK & NDK:** Android SDK (API 28+), NDK r25+
- **Gradle:** Gradle 8.x / 9.x (wrapper included)

### Build Steps

```bash
# 1. Clone the repository
git clone https://github.com/Suz41/Fishbowl.git
cd Fishbowl

# 2. Build Debug ARM64 APK
./gradlew assembleDebug

# 3. Build Universal Release APK
./gradlew assembleRelease
```

Generated APKs will be located in `app/build/outputs/apk/release/` and `app/build/outputs/apk/debug/`.

---

## Troubleshooting & FAQ

#### Q: Server stops when I lock my phone or switch apps?
**A:** Ensure battery optimization is set to **"Unrestricted"** for Fishbowl in Android Settings (`Apps -> Fishbowl -> Battery -> Unrestricted`). Fishbowl runs an ongoing Foreground Service with WakeLock, but aggressive OEM battery managers (e.g. Xiaomi MIUI/HyperOS, Huawei EMUI, Vivo OriginOS, Samsung OneUI) may require explicit permission to run in the background.

#### Q: How do I access Jellyfin from other devices on my network?
**A:** Open the Fishbowl app, locate the **LAN Address** under the Network Connection card (e.g. `http://<YOUR-DEVICE-LAN-IP>:8096` or `http://192.168.1.xxx:8096`), and enter that URL into any browser or Jellyfin app on any device connected to the same Wi-Fi network.

#### Q: Why can't Jellyfin read my USB drive directly from the root folder?
**A:** Android 11+ applies strict kernel-level SELinux policies that block native non-root Linux processes from arbitrary external mount access. However, Android explicitly guarantees read/write access to the application's package directory on the drive (`/storage/<UUID>/Android/data/com.fishbowl.app/files`). Move your media into this folder and copy the path directly from Fishbowl's Storage settings.

#### Q: How do I update Fishbowl without losing my libraries or watch history?
**A:** Simply install the updated APK over the existing installation. All database records, user accounts, and library configurations reside in `--datadir` (`~/.local/share/jellyfin`) which is preserved across APK updates.

---

## License & Acknowledgments

- **Fishbowl:** Licensed under the [GNU General Public License v3.0 (GPLv3)](LICENSE.md).
- **Jellyfin Core:** [Jellyfin Project](https://jellyfin.org) (GPLv3).
- **Packaging Foundation:** Built upon the [Termux](https://github.com/termux/termux-app) open-source container architecture.

