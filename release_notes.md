# Fishbowl v1.4.0

First production release candidate following the complete development and verification cycle.

## 🌟 Highlights & Key Features

- **Embedded Jellyfin Server 10.11.11:** Full native ASP.NET Core Linux ARM64 server runtime with embedded SQLite3 database engine and Jellyfin-FFmpeg transcoding binary.
- **Real Server Startup Lifecycle:** Authentic multi-stage startup pipeline (`STARTING_RUNTIME` -> `LAUNCHING_SERVER` -> `WAITING_FOR_SERVER` -> `CHECKING_READINESS` -> `READY`) with HTTP 200 readiness verification at `/system/info/public`.
- **Foreground Service Supervision:** Supervised by `JellyfinServerService` with ongoing notification ID `1001`, system media streaming WakeLock, and one-tap `STOP SERVER` action.
- **Immediate State Hydration:** Asynchronous `reconcileStateAsync()` actively detects running server state on launch/resume with zero UI latency.
- **Hardware-Accelerated WebView:** Integrated native web client player supporting full HTML5 video, audio streaming, direct play, and on-the-fly transcoding.
- **Memory-Safe Throttled Logs Viewer:** 500ms UI batching with a bounded 30KB buffer and smart viewport auto-scrolling that remains smooth during active media playback.
- **Dynamic Multi-Provider Metadata DNS:** Out-of-the-box support for TMDb, IMDb, OMDb metadata scanning and artwork poster downloads via dynamic DNS resolution (`8.8.8.8`, `1.1.1.1`).
- **Storage Access Framework (SAF) Bridge:** Easily grant persistent read access to external media folders, SD cards, and OTG USB drives.
- **Pixel-Style Material 3 UI:** Pure Dark Mode (`#121316`) interface with dual-band IP presentation (Local `127.0.0.1` + LAN IP) and dedicated `COPY IP` buttons.
- **Zero Emojis & Clean Branding:** 100% compliant with native Android UI design guidelines.

## 🧪 Physical Device Verification

- **Target Architecture:** Physical ARM64 device (`iQOO I2219`, Android 16 / SDK 36) via USB ADB.
- **Test Matrix:** 27/27 physical verification tests passed (**0 crashes**, **0 ANRs**, **0 duplicate processes**).

## 📦 Release Artifacts & Checksums

| Package | Version | Version Code | Architecture | File Size |
| :--- | :--- | :--- | :--- | :--- |
| `com.fishbowl.app` | `1.4.0` | `101400` | Universal (ARM64 embedded) | **271.6 MB** |

---

## 📄 Documentation & Source

- Source Code Repository: [https://github.com/Suz41/Fishbowl](https://github.com/Suz41/Fishbowl)
