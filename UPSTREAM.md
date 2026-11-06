# Upstream Architecture & Source Attributions

## Overview

Fishbowl combines an Android application host with open-source server runtimes and packages. This document records upstream repositories, source origins, and patch policies.

---

## 1. Upstream Components

### Jellyfin Server
- Upstream: https://github.com/jellyfin/jellyfin
- Target Version: 12.4.0
- Runtime Target: Microsoft .NET 10.0 ARM64 (Linux Bionic)
- Role: Core media streaming server, transcode orchestrator, and metadata indexing engine.

### Microsoft .NET Runtime
- Upstream: https://github.com/dotnet/runtime
- Version: 10.0.12 (ARM64)
- Modifications: Configured with Workstation GC (`DOTNET_gcServer=0`) to enforce bounded memory allocation on mobile hardware.

### Termux App & Terminal Packages
- Upstream:
  - App: https://github.com/termux/termux-app
  - Packages: https://github.com/termux/termux-packages
- Role: Provides native terminal emulation, POSIX Bionic environment scaffolding, and bootstrap archive management.

### Jellyfin FFmpeg
- Upstream: https://github.com/jellyfin/jellyfin-ffmpeg
- Target Version: 7.1.4
- Role: Audio remuxing, video stream container repackaging, and HLS segment generation.

### Media Automation Companion Suite
- Radarr: https://github.com/Radarr/Radarr (v5.x, .NET ARM64)
- Sonarr: https://github.com/Sonarr/Sonarr (v4.x, .NET ARM64)
- Lidarr: https://github.com/Lidarr/Lidarr (v2.x, .NET ARM64)
- Prowlarr: https://github.com/Prowlarr/Prowlarr (v1.x, .NET ARM64)
- Jackett: https://github.com/Jackett/Jackett (v0.22.x, .NET ARM64)
- Transmission: https://github.com/transmission/transmission (v4.1.4, C / Bionic)
- Seerr: https://github.com/jellyseerr/jellyseerr (Node.js runtime)
- FlareSolverr: Python / Node.js proxy with fallback to native Android WebView solver (`NativeFlareSolver`).

---

## 2. Maintenance and Patch Policy

1. Minimal Core Alteration: Upstream service binaries are executed in standard standalone modes without modifying their internal assemblies.
2. Configuration Injection: Service configuration files (`config.xml`, `settings.json`) are modified only to bind local loopback ports, configure storage folders, and disable conflicting upstream auto-updaters that would corrupt mobile Bionic dependencies.
3. Upstream Alignment: Upstream patches are evaluated periodically for security advisories and upstream API deprecations.
