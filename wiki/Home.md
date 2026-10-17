# Welcome to the Fishbowl Wiki

Fishbowl is an unofficial standalone, native Jellyfin server host and media stack launcher for compatible ARM64 Android devices. It executes a local Jellyfin media server directly on the Android host without root access, containerization, or emulation, keeping configurations, databases, and media indexing 100% local to the device.

## Quick Start

1. Download the latest release from the [GitHub Releases page](https://github.com/Suz41/Fishbowl/releases).
2. Install the APK and grant required Android permissions when prompted (Notifications and All-Files Storage Access).
3. Start the server from the Home tab, wait for the status indicator to show `SERVER RUNNING`, and tap `OPEN JELLYFIN`.
4. Complete the initial Jellyfin setup wizard, then configure media folders via Fishbowl's storage tools.

For full architectural documentation, source compilation instructions, and compliance policies, see the main [README](https://github.com/Suz41/Fishbowl/blob/main/README.md) and [RELEASE.md](https://github.com/Suz41/Fishbowl/blob/main/RELEASE.md).

---

## Navigation

*   [**Installation & Setup**](Installation-&-Setup) - Step-by-step setup and configuration guide for Android devices.
*   [**Storage Architecture & Directory Setup**](Storage-Access-Framework) - Guide on Scoped Storage, custom path selection, external storage, and empty library prevention.
*   [**System Architecture & Internals**](Architecture-&-Internals) - Technical breakdown of the Bionic POSIX runtime, .NET 10 CLR host, companion orchestration, and 1-tap backup engine.
*   [**Troubleshooting & FAQ**](Troubleshooting-&-FAQ) - Solutions for battery optimization, port conflicts, network discovery, and data preservation.

---

## Highlights

*   **Native Android Host:** Direct POSIX process execution on Android Bionic libc with lightweight background service management.
*   **Jellyfin Core:** Full Jellyfin 12.1.0 media server running locally on-device.
*   **Runtime:** Embedded Microsoft .NET 10.0.12 JIT runtime for Linux Bionic ARM64.
*   **Hardware Transcoding:** Native Jellyfin-FFmpeg binary supporting HLS streaming, media remuxing, and thumbnails.
*   **Local Database:** Embedded SQLite3 relational storage for server state, user accounts, and library metadata.
*   **Automated Diagnostics:** Built-in error analysis for port collisions, storage denials, and database stalls.
*   **Disaster Recovery:** 1-tap automated configuration backup and restoration engine (`.tar.gz`).
*   **Privacy & Offline Security:** No cloud dependencies, no remote tracking SDKs, and zero telemetry.

> **Homelab & Compliance Notice:** Fishbowl is an independent community project and is not affiliated with, maintained by, or endorsed by the official Jellyfin project. Fishbowl does not host, provide, or distribute media content and is strictly intended for personal, legally authorized homelab media collections.
