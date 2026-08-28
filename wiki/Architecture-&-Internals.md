# Architecture & Internals

Fishbowl runs Jellyfin natively inside the Android user space without virtualization, containers, or root access. Here is an overview of the internal mechanisms.

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
|  |   .NET 9.0 Host (dotnet) ---> Jellyfin.Server.dll (10.11.11)      |  |
|  |   Jellyfin-FFmpeg Engine ---> Hardware Transcoding / HLS Remux     |  |
|  |   libe_sqlite3.so Engine ---> Database Storage (~/.local/share)   |  |
|  |   libfontconfig / libfreetype ---> Subtitle Burn-In Engine        |  |
|  +-------------------------------------------------------------------+  |
|                                                                         |
+-------------------------------------------------------------------------+
```

## Core Subsystems

### 1. Foreground Service (`JellyfinServerService`)
To protect the server process from being killed by the Android OS's Out-Of-Memory (OOM) manager during background streaming, the server process runs inside a Foreground Service. It spawns a persistent status notification and acquires a `PARTIAL_WAKE_LOCK` to keep the CPU awake during active playback even when the screen is locked.

### 2. State Machine (`JellyfinController`)
Manages the lifecycle of the Jellyfin process. When the application resumes or launches, the controller runs an asynchronous check (`reconcileStateAsync()`) to verify if the server process is already running. This prevents redundant startups and refreshes the UI dashboard instantly.

### 3. Native Bionic Environment
The native components (.NET 9.0 CLR host, FFmpeg binary, and SQLite dependencies) run directly compiled against the Android Bionic C runtime (`libc.so`).

### 4. Memory-Safe Logging (`LogListener`)
Logging stdout/stderr feeds from the active Jellyfin process can generate massive amounts of data, leading to memory inflation and UI freezes. Fishbowl decouples log streams and batches them onto a `500ms` handler throttle. The log console is capped with a `30KB` bounding ring buffer (~500 lines) to avoid GC thrashing.
