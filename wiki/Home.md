# Welcome to the Fishbowl Wiki

Fishbowl is a standalone, native server host and launcher for Jellyfin on Android ARM64 with a Material 3 UI. It transforms any Android phone, tablet, or TV box into an energy-efficient, dedicated home media streaming server.

Use the navigation below to learn more about setting up, configuring, and developing for Fishbowl:

## Navigation

*   [**Installation & Setup**](Installation-&-Setup) - Step-by-step instructions to get Fishbowl running on your Android device.
*   [**Storage Access Framework (SAF) Bridge**](Storage-Access-Framework) - Guide on configuring media directories across Scoped Storage boundaries.
*   [**System Architecture & Internals**](Architecture-&-Internals) - Detailed look at the native POSIX subsystem, Dotnet host, and background services.
*   [**Troubleshooting & FAQ**](Troubleshooting-&-FAQ) - Solutions to battery optimization killers, network connection issues, and update preservation.

---

## Technology Stack

*   **Android Wrapper:** Built on top of Termux's terminal emulator framework with a native Material 3 UI.
*   **Server Core:** Full Jellyfin 12.1.0 Media Server.
*   **Runtime:** Embedded Microsoft .NET 10 JIT-compiled host (v10.0.12 Linux Bionic ARM64).
*   **Transcoder:** Tailored native Jellyfin-FFmpeg binary for video/audio conversion and HLS segmenting.
*   **Database:** Local SQLite3 (`libe_sqlite3.so`) managing state, accounts, and metadata.
