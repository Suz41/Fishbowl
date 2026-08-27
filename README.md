<div align="center">

# JellyfinDroid

### Standalone, Native Jellyfin Media Server for Android (ARM64)

[![Release](https://img.shields.io/badge/Release-v1.4.0-blue.svg?style=for-the-badge&logo=github)](https://github.com/Suz41/JellyfinDroid/releases/tag/v1.4.0)
[![Jellyfin Core](https://img.shields.io/badge/Jellyfin%20Core-10.11.11-8B5CF6.svg?style=for-the-badge&logo=jellyfin)](https://jellyfin.org)
[![Runtime](https://img.shields.io/badge/.NET-9.0%20ARM64-512BD4.svg?style=for-the-badge&logo=dotnet)](https://dotnet.microsoft.com)
[![Platform](https://img.shields.io/badge/Android-5.0%2B%20(ARM64)-3DDC84.svg?style=for-the-badge&logo=android&logoColor=white)](https://android.com)
[![License](https://img.shields.io/badge/License-GPLv3-yellow.svg?style=for-the-badge)](LICENSE.md)
[![Privacy](https://img.shields.io/badge/Telemetry-0%25%20(100%25%20Local)-success.svg?style=for-the-badge)](#-permissions--privacy)

<p align="center">
  <strong>Turn any spare Android smartphone, tablet, Android TV box, or SBC into a fully independent, low-power, 24/7 personal media streaming server.</strong>
  <br />
  <em>No root access required. No PRoot/chroot emulation overhead. Zero cloud subscriptions.</em>
</p>

</div>

---

> [!NOTE]
> **What is JellyfinDroid?**  
> Unlike standard Jellyfin client apps that only *play* streams from an external PC or NAS, **JellyfinDroid hosts and runs the actual Jellyfin Server directly on your Android hardware**. It packages native Linux ARM64 Dotnet binaries, the SQLite3 database engine, Jellyfin-FFmpeg transcoding binaries, and an Android Foreground Service into a single, standalone APK.

---

## 📑 Table of Contents

- [Overview & Architecture](#-overview--architecture)
- [Key Features & Highlights](#-key-features--highlights)
- [System Architecture](#-system-architecture)
- [Client Ecosystem & Compatibility](#-client-ecosystem--compatibility)
- [Step-by-Step Installation & Setup](#-step-by-step-installation--setup)
- [Storage Access Framework (SAF) Bridge](#-storage-access-framework-saf-bridge)
- [Dynamic Metadata DNS Pipeline](#-dynamic-metadata-dns-pipeline)
- [Memory-Safe Logging Engine](#-memory-safe-logging-engine)
- [Permissions & Privacy](#-permissions--privacy)
- [Directory Hierarchy & Persistence](#-directory-hierarchy--persistence)
- [Building From Source](#-building-from-source)
- [Troubleshooting & FAQ](#-troubleshooting--faq)
- [License & Acknowledgments](#-license--acknowledgments)

---

## 🌟 Key Features & Highlights

### ⚡ Native ARM64 Server Engine
- **Full Jellyfin 10.11.11 Core:** Direct POSIX execution on Android's Bionic C runtime (`libc.so`) without virtualization or Docker containers.
- **Embedded .NET 9.0 Host:** High-throughput JIT-compiled server core with optimized memory management.
- **Jellyfin-FFmpeg Transcoder:** Mobile-optimized native FFmpeg binary for on-the-fly video transcoding, audio remuxing, and HLS segmenting.
- **SQLite3 Database Engine (`libe_sqlite3.so`):** Low-latency local database storage managing media libraries, user watch states, and item metadata.

### 🎨 Material 3 Pixel UI Shell
- **Pure Dark Mode (`#121316`):** OLED-optimized dark theme with distinct surface cards, crisp typography, and fluid Material Touch Ripples.
- **Real-Time Startup Progression:** Displays authentic multi-stage initialization (`STARTING_RUNTIME` -> `LAUNCHING_SERVER` -> `WAITING_FOR_SERVER` -> `CHECKING_READINESS` -> `READY`).
- **Dual-Band Network Hub:** Instant local loopback (`http://127.0.0.1:8096`) and local Wi-Fi LAN IP display (`http://192.168.x.x:8096`) with one-tap `COPY IP` buttons.
- **3-Tab Navigation:**
  - **Home:** Server lifecycle dashboard, status pills, address cards, and action buttons (`OPEN JELLYFIN`, `START`, `STOP`, `RESTART`).
  - **Logs:** Memory-capped, live-streaming diagnostic console with 500ms throttled rendering and smart auto-scrolling.
  - **Settings:** Storage breakdown, SAF media folder bridge, software stack audit, and boot auto-start switches.

### 🛡️ Background Streaming Resilience
- **Foreground Service Supervision:** Managed by `JellyfinServerService` with an ongoing status notification (ID `1001`) preventing Android OS aggressive memory killing.
- **Smart CPU WakeLock:** Automatically acquires a `PARTIAL_WAKE_LOCK` with an automatic safety timeout to maintain uninterrupted streaming when the screen is locked.
- **Immediate State Hydration:** Asynchronous `reconcileStateAsync()` directly resolves running server state on app launch/resume with zero UI latency.

### 🎬 Built-in Hardware-Accelerated WebView
- Native in-app WebView container allowing immediate local playback and administrative server configuration without leaving the application.

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

Once JellyfinDroid is active on your device, you can stream your movies, shows, and music to any official Jellyfin client across your local home network:

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
> **Connecting Clients:** Open any Jellyfin client on the same Wi-Fi network and enter the **LAN Address** shown on your JellyfinDroid Home screen (e.g., `http://192.168.1.150:8096`).

---

## 🚀 Step-by-Step Installation & Setup

### 1. Download & Install
1. Download the latest **`JellyfinDroid-v1.4.0-release-universal.apk`** from [GitHub Releases](https://github.com/Suz41/JellyfinDroid/releases/tag/v1.4.0).
2. Install the APK on your ARM64 Android device.
3. Grant **Notification** and **Storage** permissions when prompted.

### 2. Starting the Server
1. Launch **JellyfinDroid**.
2. Tap the **START SERVER** button.
3. The authentic stage pipeline will verify runtime environment, start the Dotnet process, and poll HTTP readiness.
4. Once the hero badge turns green (**SERVER RUNNING**), tap **OPEN JELLYFIN**.

### 3. Initial Setup Wizard
1. Select your preferred display language.
2. Create your admin username and password.
3. Add your media libraries (point to `/sdcard/Movies`, `/sdcard/Music`, or your SAF storage bridge path).
4. Configure your preferred metadata language (English, Spanish, French, German, etc.).
5. Finish the wizard and start streaming!

---

## 📂 Storage Access Framework (SAF) Bridge

Android 11+ enforces Scoped Storage boundaries. JellyfinDroid includes a built-in Storage Access Framework (SAF) document tree bridge:

1. In JellyfinDroid, navigate to the **Settings** tab.
2. Tap **Manage Media Storage (SAF)**.
3. Tap **Add Media Folder** and select any directory on internal storage, microSD card, or OTG USB storage using Android's system document picker.
4. JellyfinDroid preserves persistent URI permissions across device reboots and surfaces folder size calculations dynamically.

---

## 🌐 Dynamic Metadata DNS Pipeline

JellyfinDroid uses dynamic multi-provider DNS resolution to ensure movie and TV show identification, metadata retrieval, and artwork fetching operate smoothly:

- **DNS Resolvers:** Embedded POSIX environment dynamically resolves DNS via Google DNS (`8.8.8.8`) and Cloudflare DNS (`1.1.1.1`).
- **Metadata Endpoints:** Seamlessly fetches data and posters from:
  - The Movie Database (TMDb): `api.themoviedb.org`, `image.tmdb.org`
  - The Open Movie Database (OMDb)
  - Internet Movie Database (IMDb) metadata scrapers
  - OpenSubtitles & MusicBrainz
- **SSL / TLS Trust Store:** Built-in Mozilla CA Certificate bundle (`cacert.pem`) ensures secure HTTPS communication with all upstream providers.

---

## 📊 Memory-Safe Logging Engine

JellyfinDroid features a hardened logging subsystem designed to prevent UI freezes during heavy transcoding or library imports:

- **Decoupled Observers (`LogListener`):** Log stream updates are completely separated from Activity state rendering.
- **500ms Handler Throttling:** Incoming log lines from stdout/stderr are batched and posted to the UI looper at most 2 times per second.
- **30KB Bounded Ring Buffer:** Memory consumption is strictly capped at ~500 lines, automatically discarding stale log lines without triggering garbage collection spikes.
- **Smart Viewport Auto-Scrolling:** Auto-scroll engages *only* when the user is already at the bottom of the log view, allowing you to scroll up and inspect past events without viewport jumping.

---

## 🔒 Permissions & Privacy

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

## 📁 Directory Hierarchy & Persistence

All Jellyfin server configuration, database, and cache files are stored within the app's sandboxed data directory:

| Directory Type | Absolute Android Path | Purpose |
| :--- | :--- | :--- |
| **System Prefix** | `/data/data/com.jellyfin.droid/files/usr` | Native binaries, Dotnet assemblies, FFmpeg executables, and shared C/C++ libraries. |
| **Persistent Data (`--datadir`)** | `/data/data/com.jellyfin.droid/files/home/.local/share/jellyfin` | Jellyfin SQLite database (`jellyfin.db`), user accounts, library definitions, and metadata. **Survives all updates.** |
| **Configuration (`--configdir`)** | `/data/data/com.jellyfin.droid/files/home/.config/jellyfin` | Server XML configuration (`system.xml`, `network.xml`, `encoding.xml`). |
| **Cache (`--cachedir`)** | `/data/data/com.jellyfin.droid/files/home/.cache/jellyfin` | Image thumbnail caches, transcoded media chunks, and temporary streaming buffers. |
| **Logs (`--logdir`)** | `/data/data/com.jellyfin.droid/files/home/.local/share/jellyfin/log` | On-disk server execution logs. |

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
git clone https://github.com/Suz41/JellyfinDroid.git
cd JellyfinDroid

# 2. Build Debug ARM64 APK
./gradlew assembleDebug

# 3. Build Universal Release APK
./gradlew assembleRelease
```

Generated APKs will be located in `app/build/outputs/apk/release/` and `app/build/outputs/apk/debug/`.

---

## ❓ Troubleshooting & FAQ

#### Q: Server stops when I lock my phone or switch apps?
**A:** Ensure battery optimization is set to **"Unrestricted"** for JellyfinDroid in Android Settings (`Apps -> JellyfinDroid -> Battery -> Unrestricted`). JellyfinDroid runs an ongoing Foreground Service with WakeLock, but aggressive OEM battery managers (e.g. Xiaomi MIUI/HyperOS, Huawei EMUI, Vivo OriginOS, Samsung OneUI) may require explicit permission to run in the background.

#### Q: How do I access Jellyfin from other devices on my network?
**A:** Open the JellyfinDroid app, locate the **LAN Address** under the Network Connection card (e.g. `http://192.168.1.150:8096`), and enter that URL into any browser or Jellyfin app on any device connected to the same Wi-Fi network.

#### Q: How do I update JellyfinDroid without losing my libraries or watch history?
**A:** Simply install the updated APK over the existing installation. All database records, user accounts, and library configurations reside in `--datadir` (`~/.local/share/jellyfin`) which is preserved across APK updates.

---

## 📄 License & Acknowledgments

- **JellyfinDroid:** Licensed under the [GNU General Public License v3.0 (GPLv3)](LICENSE.md).
- **Jellyfin Core:** [Jellyfin Project](https://jellyfin.org) (GPLv3).
- **Packaging Foundation:** Built upon the [Termux](https://github.com/termux/termux-app) open-source container architecture.

