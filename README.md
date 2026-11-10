<p align="center">
  <img src="art/fishbowl_logo.svg" width="120" height="120" alt="Fishbowl Logo" />
</p>

<h1 align="center">Fishbowl</h1>

<p align="center">
  <b>Standalone Jellyfin Server 12.4.0 Host & Companion Automation Suite for Android ARM64</b><br>
  <i>Native POSIX execution on Android Bionic libc without containers, root, or virtualization.</i>
</p>

<p align="center">
  <a href="https://github.com/Suz41/Fishbowl/actions/workflows/build.yml"><img src="https://img.shields.io/github/actions/workflow/status/Suz41/Fishbowl/build.yml?branch=main&style=flat-square&logo=githubactions&logoColor=white&label=CI" alt="Build Status" /></a>
  <a href="https://github.com/Suz41/Fishbowl/releases/tag/v1.4.10-beta.2"><img src="https://img.shields.io/badge/Release-v1.4.10--beta.2-00A4DC?style=flat-square" alt="Latest Release" /></a>
  <a href="https://jellyfin.org"><img src="https://img.shields.io/badge/Jellyfin%20Core-12.4.0-9B59B6?style=flat-square&logo=jellyfin&logoColor=white" alt="Jellyfin" /></a>
  <a href="https://dotnet.microsoft.com"><img src="https://img.shields.io/badge/.NET-10.0%20ARM64-512BD4?style=flat-square&logo=dotnet&logoColor=white" alt=".NET" /></a>
  <a href="https://android.com"><img src="https://img.shields.io/badge/Platform-Android%20ARM64-3DDC84?style=flat-square&logo=android&logoColor=white" alt="Android" /></a>
  <a href="LICENSE"><img src="https://img.shields.io/badge/License-GPL%20v3.0-F1C40F?style=flat-square" alt="License" /></a>
</p>

---

## Overview

Fishbowl runs Jellyfin Server 12.4.0 natively on ARM64 Android devices using Microsoft .NET 10.0 and Android Bionic libc. It provides an all-in-one local media server hosting environment with integrated companion automation daemons, background execution management, and hardware monitoring.

- **No Root Required:** Executes directly in unprivileged user space.
- **Embedded .NET 10 Runtime:** High-performance JIT execution tailored for ARM64 Bionic.
- **Companion Automation Stack:** Integrated management for Radarr, Sonarr, Prowlarr, Jackett, Transmission, Bazarr, Seerr, and Lidarr.
- **100% Offline & Private:** Zero telemetry, no external accounts, no cloud dependencies.

---

## Architecture

```mermaid
flowchart TD
    Clients["Streaming Clients (Smart TV, Mobile, Web Browser)"] --> Ingress["Local Network (Wi-Fi LAN / Hotspot / Tailscale)"]
    Ingress --> JFN["Jellyfin 12.4.0 Core (:8096)"]
    
    subgraph Host["Android Host (com.fishbowl.app)"]
        UI["Fishbowl Dashboard & Controls"]
        Service["Foreground Lifecycle Service"]
    end

    subgraph Runtimes["Native Runtimes"]
        DOTNET[".NET 10 ARM64"] --> JFN
        NODE["Node.js LTS"] --> SEERR["Seerr (:5055)"]
        PYTHON["Python 3.14"] --> BAZARR["Bazarr (:6767)"]
        NATIVE["Native C/C++"] --> TRANS["Transmission (:9091)"]
        DOTNET --> SERVARR["Radarr (:7878) / Sonarr (:8989) / Prowlarr (:9696) / Lidarr (:8686) / Jackett (:9117)"]
    end

    subgraph Storage["Storage Layer"]
        MEDIA["/storage/emulated/0/FishbowlMedia/"]
    end

    Host --> Runtimes
    Runtimes --> Storage
```

---

## Service Endpoints & Ports

All services bind to local loopback and the active LAN IP:

| Service | Default Port | Runtime | Purpose |
| :--- | :--- | :--- | :--- |
| **Jellyfin Core** | `8096` | .NET 10.0 ARM64 | Media streaming server & library management |
| **Radarr** | `7878` | .NET 10.0 ARM64 | Automated movie management |
| **Sonarr** | `8989` | .NET 10.0 ARM64 | Automated TV series management |
| **Prowlarr** | `9696` | .NET 10.0 ARM64 | Indexer proxy and manager |
| **Jackett** | `9117` | .NET 10.0 ARM64 | Torznab indexer translator |
| **Transmission** | `9091` | Native C (Bionic) | BitTorrent download daemon |
| **Bazarr** | `6767` | Python 3.14 | Subtitle downloader and synchronizer |
| **Seerr** | `5055` | Node.js LTS | Media discovery and requests portal |
| **Lidarr** | `8686` | .NET 10.0 ARM64 | Music management and automation |

---

## Quick Start

### Prerequisites
- ARM64 Android device (aarch64 / ARMv8 64-bit)
- Android 7.0 (API 24) or newer (Android 11–16 recommended)
- 4 GB+ RAM recommended for running multiple services simultaneously

### Installation & Setup
1. Download the latest release APK from [Releases](https://github.com/Suz41/Fishbowl/releases/tag/v1.4.10-beta.2).
2. Install the APK and grant required permissions:
   - **All-Files Storage Access (`MANAGE_EXTERNAL_STORAGE`):** Required for indexing media folders.
   - **Battery Optimization Exemption:** Set battery usage to **Unrestricted** to allow continuous background streaming.
3. Launch Fishbowl and wait for initial runtime extraction (one-time setup).
4. Tap **Start Server** to start Jellyfin, then tap **Open Jellyfin** to complete the initial setup wizard at `http://localhost:8096`.
5. Access companion services from the **Services** tab and start individual services as needed.

---

## Media Storage Structure

Fishbowl provisions a standardized directory layout on internal storage:

```text
/storage/emulated/0/FishbowlMedia/
|-- Movies/       # Movie library
|-- TV/           # TV show library
|-- Music/        # Music library
|-- Downloads/    # Download staging
`-- Backups/      # Timestamped configuration backups
```

For external storage (USB OTG or MicroSD), use the guaranteed app directory:
```text
/storage/<UUID>/Android/data/com.fishbowl.app/files
```

---

## Backup & Recovery

Under **Settings -> Backup & Restore System**, Fishbowl provides 1-tap backup and recovery:
- Compresses all database, XML configurations, and service credentials into `/storage/emulated/0/FishbowlMedia/Backups/`.
- Safe atomic restoration with pre-restore safety snapshots and ZipSlip boundary validation.

---

## Building from Source

```bash
# Clone the repository
git clone https://github.com/Suz41/Fishbowl.git
cd Fishbowl

# Run unit tests
./gradlew testDebugUnitTest

# Build debug APK
./gradlew assembleDebug

# Build release universal APK
./gradlew assembleRelease
```

---

## Compliance & Disclaimer

- **Unofficial Community Project:** Fishbowl is an independent open-source project and is not affiliated with, endorsed by, or maintained by the official Jellyfin project.
- **Homelab & Lawful Media Scope:** Fishbowl is strictly a self-hosted media server management utility. It does not provide, host, index, or distribute any copyrighted content. Users are solely responsible for ensuring compliance with all applicable copyright and intellectual property laws.
- **License:** Licensed under the [GNU General Public License v3.0 (GPLv3)](LICENSE). See [THIRD_PARTY_LICENSES.md](THIRD_PARTY_LICENSES.md) for third-party component licenses.

---

## Maintainer

Created and maintained by **Sujal Achari** ([@Suz41](https://github.com/Suz41)).
