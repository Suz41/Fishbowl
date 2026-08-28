# Welcome to the Fishbowl Wiki

Fishbowl is a standalone, native server host/launcher for Jellyfin on Android ARM64 with a Material 3 UI. It transforms any Android phone, tablet, or TV box into an energy-efficient, dedicated home media streaming server.

Use the navigation below to learn more about setting up, configuring, and developing for Fishbowl:

## 📖 Navigation

*   [**Installation & Setup**](Installation-&-Setup) - Step-by-step instructions to get Fishbowl running on your Android device.
*   [**Storage Access Framework (SAF) Bridge**](Storage-Access-Framework) - Guide on configuring media directories across Scoped Storage boundaries.
*   [**System Architecture & Internals**](Architecture-&-Internals) - Detailed look at the native POSIX subsystem, Dotnet host, and background services.
*   [**Troubleshooting & FAQ**](Troubleshooting-&-FAQ) - Solutions to battery optimization killers, network connection issues, and update preservation.

---

## 🛠️ Technology Stack
*   **Android Wrapper:** Built on top of Termux's terminal emulator framework with a native Material 3 UI.
*   **Server Core:** Full Jellyfin 10.11.11 Media Server.
*   **Runtime:** Embedded .NET 9.0 JIT-compiled host for ARM64.
*   **Transcoder:** Tailored native Jellyfin-FFmpeg binary for hardware-accelerated video/audio conversion.
*   **Database:** Local SQLite3 (`libe_sqlite3.so`) managing state and metadata.
