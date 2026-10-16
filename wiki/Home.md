# Welcome to the Fishbowl Wiki

Fishbowl is a standalone, native Jellyfin server host and launcher for compatible ARM64 Android devices. It runs a local Jellyfin media server directly on the phone, tablet, or TV box without root access, Docker, or a PC, while keeping configuration, databases, and media indexing local to the device.

## Quick Start

1. Download the latest Fishbowl release from the [GitHub Releases page](https://github.com/Suz41/Fishbowl/releases).
2. Install the APK and grant the required Android permissions when prompted, especially Notifications and All-Files Storage Access.
3. Start the server from the Home tab, wait for the status card to show `SERVER RUNNING`, and then tap `OPEN JELLYFIN`.
4. Complete the Jellyfin setup wizard, then add media folders through Fishbowl's storage tools.

For the latest release notes, source build instructions, and project overview, see the main [README](https://github.com/Suz41/Fishbowl/blob/main/README.md).

---

## Navigation

*   [**Installation & Setup**](Installation-&-Setup) - Step-by-step instructions for setting up Fishbowl on Android.
*   [**Storage Architecture & Directory Setup**](Storage-Access-Framework) - Information about media folders, Scoped Storage, and external drive access.
*   [**System Architecture & Internals**](Architecture-&-Internals) - Details on the native runtime, Jellyfin host, background services, and diagnostics.
*   [**Troubleshooting & FAQ**](Troubleshooting-&-FAQ) - Solutions for battery optimization issues, empty libraries, network connectivity, and updates.

---

## Highlights

*   **Android Host:** Native Android runtime with a Material 3 interface and lightweight background service management.
*   **Server Core:** Full Jellyfin 12.1.0 media server running on-device.
*   **Runtime:** Embedded Microsoft .NET 10 JIT runtime for Linux Bionic ARM64.
*   **Transcoding:** Native Jellyfin-FFmpeg binary for HLS streaming, media remuxing, and thumbnails.
*   **Database:** Local SQLite storage for server state, libraries, user data, and metadata.
*   **Diagnostics:** Built-in error analysis for startup problems, port conflicts, storage issues, and SELinux restrictions.
*   **Privacy:** No cloud account requirement, no telemetry SDKs, and all server state remains local to the device.

> Fishbowl is an unofficial community project and is not affiliated with or endorsed by the official Jellyfin project. Fishbowl does not host, provide, or distribute media content and is intended strictly for personal, legally authorized media collections.
