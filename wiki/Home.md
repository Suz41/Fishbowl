# Welcome to the Fishbowl Wiki

Fishbowl is a standalone, native server host and launcher for Jellyfin on Android ARM64 with a Material 3 UI. It transforms any Android phone, tablet, or TV box into an energy-efficient, dedicated home media streaming server.

Use the navigation below to learn more about setting up, configuring, and developing for Fishbowl:

## Navigation

*   [**Installation & Setup**](Installation-&-Setup) - Step-by-step instructions to get Fishbowl v1.4.6 running on your Android device.
*   [**Storage Architecture & Directory Setup**](Storage-Access-Framework) - Guide on configuring media directories across Scoped Storage boundaries and external drives.
*   [**System Architecture & Internals**](Architecture-&-Internals) - Detailed look at the native POSIX subsystem, Dotnet host, background services, and error diagnosis.
*   [**Troubleshooting & FAQ**](Troubleshooting-&-FAQ) - Solutions to battery optimization killers, empty library prevention, network connection issues, and update preservation.

---

## Technology Stack

*   **Android Host:** Native Android application runtime with pure dark OLED theme and tactile feedback.
*   **Server Core:** Full Jellyfin 12.1.0 Media Server.
*   **Runtime:** Embedded Microsoft .NET 10 JIT-compiled host (v10.0.12 Linux Bionic ARM64).
*   **Transcoder:** Tailored native Jellyfin-FFmpeg binary for video/audio conversion and HLS segmenting.
*   **Database:** Local SQLite3 (`libe_sqlite3.so`) managing state, accounts, and metadata.
*   **Diagnostics:** Comprehensive Error Diagnoser analyzing failure stages and providing actionable recovery steps.
*   **Security:** 100% CodeQL cleared with ZipSlip protection, canonical path validation, and deterministic parsing.
