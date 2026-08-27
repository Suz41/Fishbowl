# JellyfinDroid — Native Jellyfin Media Server for Android (ARM64)

[![Release](https://img.shields.io/badge/Release-v1.4.0-blue.svg)](https://github.com/Suz41/JellyfinDroid/releases)
[![Jellyfin Server](https://img.shields.io/badge/Jellyfin%20Core-10.11.11-purple.svg)](https://jellyfin.org)
[![Runtime](https://img.shields.io/badge/.NET-9.0%20ARM64-512BD4.svg)](https://dotnet.microsoft.com)
[![Platform](https://img.shields.io/badge/Android-5.0%2B%20(ARM64)-3DDC84.svg?logo=android&logoColor=white)](https://android.com)
[![License](https://img.shields.io/badge/License-GPLv3-yellow.svg)](LICENSE.md)
[![Privacy](https://img.shields.io/badge/Telemetry-0%25%20(100%25%20Local)-success.svg)](#privacy--security)

**JellyfinDroid** is a native Android application that packages and runs the full, unmodified **Jellyfin Media Server 10.11.11** directly on Android devices (**ARM64-v8a**).

Transform any spare Android smartphone, tablet, Android TV box, or SBC into a standalone, energy-efficient, 24/7 personal home media streaming server. **No root access required, no PRoot emulation overhead, and zero cloud subscriptions.**

---

## 📑 Table of Contents

1. [Key Features](#-key-features)
2. [System Architecture](#-system-architecture)
3. [Client Ecosystem & Compatibility](#-client-ecosystem--compatibility)
4. [Quick Start & Setup Guide](#-quick-start--setup-guide)
5. [Storage Access Framework (SAF) Bridge](#-storage-access-framework-saf-bridge)
6. [Metadata & Dynamic DNS Pipeline](#-metadata--dynamic-dns-pipeline)
7. [Memory-Safe Diagnostics & Logging](#-memory-safe-diagnostics--logging)
8. [Permissions & Sandboxing](#-permissions--sandboxing)
9. [Building From Source](#-building-from-source)
10. [Troubleshooting & FAQ](#-troubleshooting--faq)
11. [License & Acknowledgments](#-license--acknowledgments)

---

## 🌟 Key Features

### 🚀 Native Server Performance
- **Embedded Jellyfin Core (v10.11.11):** Runs full native Linux ARM64 Dotnet binaries compiled for Android's Bionic C library (`libc.so`).
- **Jellyfin-FFmpeg Integration:** Native transcoding and remuxing engine optimized for mobile ARM64 hardware.
- **SQLite3 Database Engine:** Local embedded storage driver managing library catalogs, user watch states, and item metadata.

### 🎨 Pixel-Style Material 3 UI
- **Pure Dark Mode (`#121316`):** High-contrast, battery-saving dark interface with distinct surface cards and fluid Material Touch Ripples.
- **3-Tab Navigation:**
  - **Home:** Real-time server status, dual-band network IP cards (Local `127.0.0.1` and LAN `192.168.x.x`), one-tap `COPY IP` buttons, and lifecycle controls (`START`, `STOP`, `RESTART`).
  - **Logs:** Memory-capped, live-streaming diagnostic log console with 500ms batched updates and smart auto-scrolling.
  - **Settings:** Storage breakdown, SAF media folder bridge, software stack audit, and environment path metrics.

### ⚡ Resilient Background Execution
- **Foreground Service Supervision:** Supervised by `JellyfinServerService` with an ongoing notification (ID `1001`) preventing Android OS aggressive background app killing.
- **Intelligent CPU WakeLock:** Automatically acquires a system `PARTIAL_WAKE_LOCK` with a safety timeout during active media streaming.
- **Immediate State Hydration:** `reconcileStateAsync()` directly checks runtime readiness on startup/resume, eliminating delayed `UNINITIALIZED` states.

### 🎬 Built-in WebView Web Client
- Integrated hardware-accelerated WebView container allowing immediate local playback and administrative server configuration without leaving the app.

---

## 🏗️ System Architecture

```
+-------------------------------------------------------------------------+
|                           Android User Space                            |
|                                                                         |
|  +-------------------------------------------------------------------+  |
|  |                     JellyfinDroid UI Shell                        |  |
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
|  |   .NET 9.0 Host (dotnet) ---> Jellyfin.Server.dll (10.11.11)      |  |
|  |   Jellyfin-FFmpeg Engine ---> Hardware Transcoding / HLS Remux     |  |
|  |   libe_sqlite3.so Engine ---> Database Storage (~/.local/share)   |  |
|  |   libfontconfig / libfreetype ---> Subtitle Burn-In Engine        |  |
|  +-------------------------------------------------------------------+  |
|                                                                         |
+-------------------------------------------------------------------------+
```

---

## 📺 Client Ecosystem & Compatibility

Once JellyfinDroid is running, you can stream your media to any official Jellyfin client on your local Wi-Fi network:

| Client App | Platform | Direct Play / Stream Support |
| :--- | :--- | :--- |
| **Jellyfin for Android TV** | Google TV, FireStick, Shield TV | Full 4K HDR, HEVC, Direct Play |
| **Jellyfin Mobile** | Android & iOS Phones / Tablets | Full Touch Controls, Offline Sync |
| **Web Client** | Chrome, Firefox, Safari, Edge | Full HTML5 Video & Audio |
| **Roku** | Roku Streaming Stick, Roku TV | Direct Stream & Transcoding |
| **Kodi (Jellyfin for Kodi)** | Linux, Windows, LibreELEC | Native Direct Path Playback |
| **Swiftfin / Infuse** | Apple TV, iPhone, iPad | Native Metal Hardware Decoding |

Simply enter your server's **LAN Address** (e.g. `http://192.168.1.150:8096`) in any of these apps to connect.

---

## 🚀 Quick Start & Setup Guide

### 1. Installation
1. Download the latest `termux-app_apt-android-7-release_universal.apk` from [GitHub Releases](https://github.com/Suz41/JellyfinDroid/releases/tag/v1.4.0).
2. Install the APK on your ARM64 Android device.
3. Grant notification and storage permissions when prompted.

### 2. Starting the Server
1. Launch **JellyfinDroid**.
2. Tap the **START SERVER** button.
3. The authentic startup stage pipeline will progress:
   `STARTING_RUNTIME` -> `LAUNCHING_SERVER` -> `WAITING_FOR_SERVER` -> `CHECKING_READINESS` -> `READY`
4. When the status badge turns green (**SERVER RUNNING**), tap **OPEN JELLYFIN**.

### 3. Jellyfin Initial Wizard Setup
1. Select your preferred display language.
2. Create your administrator account username and password.
3. Add your media libraries (point to `/sdcard/Movies`, `/sdcard/Music`, etc., or your SAF storage bridge path).
4. Select your preferred metadata language (English, Spanish, French, etc.).
5. Complete the setup and log in!

---

## 📂 Storage Access Framework (SAF) Bridge

Android 11+ enforces scoped storage rules. JellyfinDroid provides a dedicated Storage Access Framework (SAF) bridge:

1. Open the **Settings** tab in JellyfinDroid.
2. Tap **Manage Media Storage (SAF)**.
3. Tap **Add Media Folder** and pick any directory on internal storage, microSD card, or OTG USB drive using the Android system document picker.
4. JellyfinDroid preserves permanent read permissions across device reboots and updates folder sizes dynamically in the UI.

---

## 🌐 Metadata & Dynamic DNS Pipeline

JellyfinDroid uses dynamic multi-provider DNS resolution to ensure movie and TV show identification, metadata retrieval, and artwork fetching operate smoothly:

- **DNS Providers:** Dynamically queries Google DNS (`8.8.8.8`) and Cloudflare DNS (`1.1.1.1`) inside the POSIX environment.
- **Provider Resolution:** Resolves endpoints for:
  - The Movie Database (TMDb): `api.themoviedb.org`, `image.tmdb.org`
  - The Open Movie Database (OMDb)
  - Internet Movie Database (IMDb) metadata scrapers
  - OpenSubtitles & MusicBrainz
- **SSL / TLS Integrity:** Complete CA certificate bundle (`cacert.pem`) embedded within the application ensures secure HTTPS communication with all upstream providers.

---

## 📊 Memory-Safe Diagnostics & Logging

JellyfinDroid features a dedicated, hardened logging engine designed to prevent UI freezes during heavy transcoding or library imports:

- **Decoupled Observers (`LogListener`):** Log stream updates are completely separated from Activity state rendering.
- **500ms Handler Throttling:** Incoming log lines from stdout/stderr are batched and posted to the UI looper at most 2 times per second.
- **30KB Bounded Ring Buffer:** Memory consumption is strictly capped at ~500 lines, automatically discarding stale log lines without triggering garbage collection spikes.
- **Smart Viewport Auto-Scrolling:** Auto-scroll engages *only* when the user is already at the bottom of the log view, allowing you to scroll up and inspect past events without the viewport jumping.

---

## 🔒 Permissions & Sandboxing

JellyfinDroid operates strictly within standard Android OS application sandboxing rules:

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

## 🛠️ Building From Source

### Prerequisites
- **Operating System:** Windows 10/11, macOS, or Linux
- **JDK:** OpenJDK 17 or OpenJDK 21
- **Android SDK & NDK:** Android SDK (API 28+), NDK r25+
- **Gradle:** Gradle 8.x / 9.x (wrapper included)

### Build Steps

```bash
# 1. Clone repository
git clone https://github.com/Suz41/JellyfinDroid.git
cd JellyfinDroid

# 2. Build Debug ARM64 APK
./gradlew assembleDebug

# 3. Build Universal Release APK
./gradlew assembleRelease
```

Generated APKs will be located in `app/build/outputs/apk/release/`.

---

## ❓ Troubleshooting & FAQ

#### Q: Server stops when I lock my phone or switch apps?
**A:** Ensure battery optimization is set to **"Unrestricted"** for JellyfinDroid in Android Settings (`Apps -> JellyfinDroid -> Battery -> Unrestricted`). JellyfinDroid runs an ongoing Foreground Service with WakeLock, but aggressive OEM battery managers (e.g. Xiaomi, Huawei, Vivo, Samsung) may require explicit permission to run in the background.

#### Q: How do I access Jellyfin from other devices?
**A:** Open the JellyfinDroid app, locate the **LAN Address** under the Network Connection card (e.g. `http://192.168.1.150:8096`), and enter that URL into any browser or Jellyfin app on any device connected to the same Wi-Fi network.

#### Q: Where are my server configuration files stored?
**A:** All Jellyfin server configuration, database, and cache files are stored within the app's sandboxed data directory:
`/data/data/com.jellyfin.droid/files/home/.config/jellyfin`

---

## 📄 License & Acknowledgments

- **JellyfinDroid:** Licensed under the [GNU General Public License v3.0 (GPLv3)](LICENSE.md).
- **Jellyfin Core:** [Jellyfin Project](https://jellyfin.org) (GPLv3).
- **Packaging Foundation:** Built upon the [Termux](https://github.com/termux/termux-app) open-source container architecture.
