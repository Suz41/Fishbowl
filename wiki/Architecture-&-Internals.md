# Architecture & Internals

Fishbowl runs Jellyfin natively inside the Android user space without virtualization, containers, or root access.

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
|  |   .NET 10 Host (dotnet) ---> Jellyfin.Server.dll (12.1.0)         |  |
|  |   Jellyfin-FFmpeg Engine ---> Hardware Transcoding / HLS Remux     |  |
|  |   libe_sqlite3.so Engine ---> Database Storage (~/.local/share)   |  |
|  |   libfontconfig / libfreetype ---> Subtitle Burn-In Engine        |  |
|  +-------------------------------------------------------------------+  |
|                                                                         |
+-------------------------------------------------------------------------+
```

## Core Subsystems

### 1. Foreground Service (`JellyfinServerService`)
To protect the server process from being killed by the Android OS's Out-Of-Memory (OOM) manager during background streaming, the server process runs inside an ongoing Foreground Service with `foregroundServiceType="mediaPlayback"`. It maintains a persistent status notification and acquires a `PARTIAL_WAKE_LOCK` to keep the CPU awake during active playback even when the screen is turned off.

### 2. State Machine (`JellyfinController`)
Manages the lifecycle of the Jellyfin process. When the application resumes or launches, the controller runs an asynchronous check (`reconcileStateAsync()`) to verify if the server process is already running. This prevents redundant startups and refreshes the UI dashboard instantly.

### 3. Native Bionic Environment
The native components (.NET 10 CLR host v10.0.12, FFmpeg binary, and SQLite dependencies) run directly compiled against the Android Bionic C runtime (`libc.so`).

### 4. Memory-Safe Logging (`LogListener`)
Logging stdout/stderr feeds from the active Jellyfin process can generate high data volume during library scanning. Fishbowl decouples log streams and batches them onto a `500ms` handler throttle. The log console is capped with a `30KB` bounding ring buffer (~500 lines) to prevent garbage collection spikes.

### 5. Comprehensive Error Diagnoser (`ErrorDiagnoser`)
Monitors server lifecycle and diagnostic logs across all startup phases. Automatically detects port collisions (port 8096 in use), SELinux storage permission denials, database lockup/migration stalls, and missing bootstrap archives, displaying real-time actionable guidance directly on the dashboard.

### 6. Consolidated POSIX Storage Engine (`StorageDriveHelper`)
Provides a unified POSIX-first media path management layer. Automatically provisions and validates guaranteed package directories on external USB/SD mounts (`/storage/<UUID>/Android/data/com.fishbowl.app/files`) to bypass Android 11-16 SELinux root blocks. Incorporates pre-flight sanity checks to prevent empty library creation in Jellyfin.

### 7. CodeQL Security Architecture
- **ZipSlip Protection:** Archive extractors in `JellyfinBootstrapper` and `TermuxInstaller` check `entry.getName().contains("..")` and verify `toPath().normalize().startsWith()` against canonical destinations before executing filesystem operations.
- **Path Injection Sanitization:** Custom path inputs in `JellyfinStorageActivity` and `StorageDriveHelper` validate against allowlisted mount prefixes (`/storage/`, `/sdcard/`, `/mnt/`, app data) and canonical boundaries.
- **Deterministic ReDoS-Safe Parsing:** Replaced backtracking regular expressions in `UpdateManager` with linear $O(N)$ string and character token parsing.
- **Primitive Cast Safety:** Promoted character column pointers in `TerminalRow` to 32-bit integers to eliminate narrowing compound assignment vulnerabilities.
