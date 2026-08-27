# JellyfinDroid

[![Release](https://img.shields.io/badge/Release-v1.4.0-blue.svg)](https://github.com/Suz41/JellyfinDroid/releases)
[![Jellyfin Server](https://img.shields.io/badge/Jellyfin%20Core-10.11.11-purple.svg)](https://jellyfin.org)
[![Runtime](https://img.shields.io/badge/.NET-9.0%20ARM64-512BD4.svg)](https://dotnet.microsoft.com)
[![Platform](https://img.shields.io/badge/Android-5.0%2B%20(ARM64)-3DDC84.svg?logo=android&logoColor=white)](https://android.com)
[![Privacy](https://img.shields.io/badge/Telemetry-0%25%20(100%25%20Local)-success.svg)](#privacy--security)

**JellyfinDroid** is a native Android application that packages and runs the full **Jellyfin Media Server 10.11.11** directly on Android devices (**ARM64**). 

Transform any spare Android phone, tablet, TV box, or SBC into a fully independent, low-power, 24/7 personal media streaming server without requiring root access, Proot performance overhead, or external server hardware.

---

## 🌟 Highlights & Key Features

- **Embedded Native Jellyfin Core (v10.11.11):** Runs full native Linux ARM64 Dotnet binaries directly inside an isolated Android application sandbox.
- **Pixel-Style Material UI:** Clean 3-Tab interface (**Home**, **Logs**, **Settings**) designed with Pure Dark Mode (`#121316`) and Material Touch Ripples.
- **Real-Time Startup Progression:** Displays authentic multi-stage initialization (`STARTING_RUNTIME` -> `LAUNCHING_SERVER` -> `WAITING_FOR_SERVER` -> `CHECKING_READINESS` -> `READY`).
- **Foreground Service Supervision:** Supervised by Android Foreground Service (`JellyfinServerService`) with persistent status notification, media streaming WakeLock, and one-tap `STOP SERVER` action.
- **Immediate State Hydration:** Instantly detects whether the server runtime is already active upon application launch (`reconcileStateAsync()`), eliminating UI delay.
- **Integrated Hardware-Accelerated WebView:** Built-in Jellyfin Web UI player container supporting full HTML5 video, audio streaming, direct play, and transcoding.
- **Storage Access Framework (SAF) Bridge:** Easily bind external storage directories, SD cards, and USB drives to Jellyfin media libraries securely.
- **Dynamic Multi-Provider Metadata DNS:** Out-of-the-box support for TMDb, IMDb, OMDb metadata scanning, and artwork poster downloads via dynamic DNS resolution (`8.8.8.8`, `1.1.1.1`).
- **Memory-Safe Throttled Log Viewer:** Rate-limited 500ms UI batching with a bounded 30KB memory buffer and smart viewport auto-scrolling that remains smooth during active media playback.
- **Privacy First:** **0 Trackers | 0 Remote Analytics | 100% Local Storage.** All data, metadata, and watch progress remain strictly on your device.

---

## 🛠️ Software Stack & Architecture

| Component | Technology | Description |
| :--- | :--- | :--- |
| **Server Core** | Jellyfin Server `10.11.11` | Full ASP.NET Core media streaming backend |
| **Runtime Container** | .NET 9.0 (Linux ARM64) | High-performance managed runtime hosting Jellyfin assemblies |
| **Transcoding Engine** | Jellyfin-FFmpeg | Native binary for on-the-fly video transcoding and HLS remuxing |
| **Database Driver** | SQLite3 (`libe_sqlite3.so`) | Local relational storage for libraries, indexes, and user accounts |
| **Subtitle Rendering** | Fontconfig & FreeType | Native C libraries for video subtitle burn-in and text layout |
| **Android Shell** | Native Android Java (SDK 28) | Activity lifecycle, Foreground Service, WebView, and SAF storage bridge |

---

## 🚀 Installation & Quick Start

### 1. Download & Install
Download the latest **Universal Release APK** from [GitHub Releases](https://github.com/Suz41/JellyfinDroid/releases/tag/v1.4.0):
- **Package:** `com.jellyfin.droid`
- **File:** `termux-app_apt-android-7-release_universal.apk`

### 2. Launching the Server
1. Open **JellyfinDroid** on your Android device.
2. Tap **START SERVER**.
3. Watch the real-time progress card as it initializes the environment, launches the process, and verifies readiness.
4. Once **SERVER RUNNING** appears, tap **OPEN JELLYFIN** to access the web interface or open `http://<YOUR-DEVICE-LAN-IP>:8096` in any browser on your home Wi-Fi network.

### 3. Setting Up Media Libraries
1. Open the **Settings** tab.
2. Under **Storage & Directories**, tap **Manage Media Storage (SAF)** to grant access to your Movies, Music, or TV Shows folders on your SD card or internal storage.
3. In the Jellyfin Setup Wizard, point your media libraries to `/sdcard/...` or your SAF storage bridge folder.

---

## 🔒 Permissions & Security

JellyfinDroid operates strictly within Android OS sandboxing standards:
- `FOREGROUND_SERVICE` & `FOREGROUND_SERVICE_MEDIA_PLAYBACK`: Keeps the media server running continuously when the screen is locked or app is in background.
- `WAKE_LOCK`: Prevents CPU deep-sleep while clients are actively streaming media.
- `INTERNET` & `ACCESS_NETWORK_STATE`: Handles local loopback and LAN media streaming.
- `MANAGE_EXTERNAL_STORAGE` / Storage Access Framework: Allows read access to user-selected media directories.

---

## 📦 Build From Source

### Prerequisites
- Android Studio / Android SDK (API 28+, NDK r25+)
- JDK 17 or JDK 21
- Physical ARM64 device or emulator

### Build Commands

```bash
# Clone the repository
git clone https://github.com/Suz41/JellyfinDroid.git
cd JellyfinDroid

# Build Debug APK
./gradlew assembleDebug

# Build Universal Release APK
./gradlew assembleRelease
```

Compiled APKs will be generated in `app/build/outputs/apk/`.

---

## 📄 License & Credits

- **JellyfinDroid** is licensed under the [GNU General Public License v3.0 (GPLv3)](LICENSE.md).
- **Jellyfin Core:** [Jellyfin Project](https://jellyfin.org) (GPLv3).
- **Base Terminal Infrastructure:** Built upon the [Termux](https://github.com/termux/termux-app) open-source packaging foundation.
