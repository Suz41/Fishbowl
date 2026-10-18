# Fishbowl Releases

Authoritative release chronicle and technical specifications for Fishbowl.

---

## Homelab Scope and Compliance Policy

Fishbowl is engineered strictly as a personal homelab server hosting and library management utility.

- **Homelab and Self-Hosting Only:** Designed for self-hosters and homelab enthusiasts running local infrastructure on ARM64 Android devices. Intended lawful use cases include private family media collections, legally digitized personal backups of user-owned optical media, public domain archives, and Creative Commons educational content.
- **Strictly Not for Piracy:** Fishbowl is not designed, licensed, or provided for piracy, copyright infringement, unauthorized torrenting, or illicit media distribution.
- **Zero Bundled Content:** The software contains no copyrighted films, audio, television series, or proprietary media codecs. The project maintainers do not operate indexers or public trackers.
- **User Legal Responsibility:** Users are 100% legally responsible for ensuring that all media files hosted on or streamed through their servers comply with all applicable local, national, and international copyright laws.

---

## Release v1.4.8 (Stable)

- **Release Date:** October 18, 2026
- **Target Platform:** Android ARM64 (`aarch64`, Bionic libc)
- **Minimum Android API:** API 21 (Android 5.0)
- **Target Android API:** API 35 (Android 15)
- **Core Server Engine:** Jellyfin 12.1.0 (.NET 10.0.12 ARM64)
- **Transcoding Engine:** Jellyfin FFmpeg 7.1.4
- **Database Engine:** SQLite 3.46.1 (`libe_sqlite3.so`)

### Release Artifacts and Verification

| Artifact Name | Architecture | File Size (Bytes) | SHA-256 Checksum |
| :--- | :--- | :--- | :--- |
| `Fishbowl-v1.4.8-release-universal.apk` | Universal ARM64 | 338,910,287 | `34B1DEB600961DCA19A3BF8B23F35ABF5B68DB2BB237D3F3D51BCAE05494298F` |

### Detailed Changes

#### 1. Setup Screen Isolation & 0% Extraction Fix
- Completely decoupled and isolated the initial setup runtime extraction screen on fresh installs (`setChromeVisibilityForSetup(true)`).
- Eliminated chrome overlap (tabs, header bars, and navigation dividers) during first launch.
- Resolved race conditions on modern high-refresh OEM devices (Samsung Galaxy S21/S22 Ultra, Vivo) where the setup screen could remain visually stuck at 0%.

#### 2. Dynamic Foreground Notification & System Status
- Re-architected `JellyfinServerService` foreground notification with dynamic setup progress (`Fishbowl • Setup (X%)`), real-time chronometer uptime tracking, and color-coded status badges.
- Resolved double-protocol URL formatting (`http://http://`).
- Added quick action intents for Web UI, Dashboard, Stop All, and Retry.

#### 3. System Console & Log Deduplication
- Resolved duplicate `[ JELLYFIN ]` filter chips in the System Console by filtering out core service manifestations during companion service discovery.
- Eliminated duplicated log output lines when viewing `ALL`.

#### 4. Lazy Seerr Deployment
- Fixed Seerr request engine auto-deploying into the file system on first app open.
- Refactored `deploySeerrService(boolean forceInstall)` to guarantee Seerr is only unpacked when explicitly requested by user interaction in the Services tab.

#### 5. Production Reliability & Cryptographic Safeguards
- Hardened `UpdateManager` with SHA-256 cryptographic verification prior to triggering APK installs.
- Secured `BackupRestoreManager` with canonical Path prefix boundary checks against ZipSlip path traversal and integrated automatic rollback on corrupted archives.
- Guarded `MediaStorageManager` download staging deduplication watcher against race conditions.
- Enforced synchronous state validation in `JellyfinController` to guarantee idempotent start/stop operations.

---

## Release v1.4.8-beta.2

- **Release Date:** October 16, 2026
- **Target Platform:** Android ARM64 (`aarch64`, Bionic libc)
- **Minimum Android API:** API 21 (Android 5.0)
- **Target Android API:** API 35 (Android 15)
- **Core Server Engine:** Jellyfin 12.1.0 (.NET 10.0.12 ARM64)
- **Transcoding Engine:** Jellyfin FFmpeg 7.1.4
- **Database Engine:** SQLite 3.46.1 (`libe_sqlite3.so`)
- **Git Commit:** `c2a2c21c` / `6b8ce412`

### Release Artifacts and Verification

| Artifact Name | Architecture | File Size (Bytes) | SHA-256 Checksum |
| :--- | :--- | :--- | :--- |
| `Fishbowl-v1.4.8-beta.2-release-universal.apk` | Universal ARM64 | 338,901,437 | `8AC58D02E84BD53D2FF2F53E2CE39139BB42FEE7ED0AB9B5F66070B78E65AC4C` |

### Detailed Changes

#### 1. Disaster Recovery and 1-Tap Backup Engine
- Implemented `BackupRestoreManager` under Settings Section 6 (Backup and Restore System).
- Single-tap archive generation packaging configuration XMLs, API authentication tokens, and SQLite databases for:
  - Jellyfin (`jellyfin.db`, `system.xml`, `network.xml`)
  - Radarr (`radarr.db`, `config.xml`)
  - Sonarr (`sonarr.db`, `config.xml`)
  - Prowlarr (`prowlarr.db`, `config.xml`)
  - Transmission (`settings.json`, resume data)
  - Seerr (`settings.json`, SQLite store)
- Archives are compressed and stored locally at `/storage/emulated/0/FishbowlMedia/Backups/fishbowl_backup_YYYYMMDD_HHmmss.tar.gz`.
- Atomic restoration workflow: validates archive headers, halts running services, restores configuration paths safely, and triggers automatic daemon reconciliation.

#### 2. Dedicated Download Staging and Storage Deduplication
- Resolved Transmission 4.x label-drop issues by provisioning explicit, dedicated directory routing:
  - Movies download staging: `FishbowlMedia/Downloads/radarr`
  - Television series download staging: `FishbowlMedia/Downloads/sonarr`
- Implemented background storage watcher in `MediaStorageManager` that detects when an imported file is successfully moved to destination library folders (`Movies/` or `TV Shows/`) and purges completed staging duplicates to conserve internal flash storage.
- Standardized storage scaffolding under `/storage/emulated/0/FishbowlMedia/` (`Movies`, `TV Shows`, `Music`, `Downloads`).

#### 3. Android Home Screen Supervision Widgets
- Fishbowl Status Widget: Real-time service state indicator, IP addresses (Loopback, LAN, Tailscale), active daemon counters, and direct Start/Restart/Stop/Web UI controls.
- Services Quick-Tile Widget: Individual status indicators and launch shortcuts for all 6 companion services.

#### 4. Architecture, Offline Security and AI Removal
- Decommissioned and excised the experimental AI Fix Assistant / Copilot subsystem (12 classes under `com.fishbowl.app.ai.*`).
- Removed `android.permission.RECORD_AUDIO` and unused AI vector drawables.
- Application operates strictly offline with zero cloud LLM dependencies, zero telemetry SDKs, and zero remote analytics.
- Added AI-assisted development transparency disclosure in project documentation.

#### 5. Documentation, Brand Identity and Licensing
- Vectorized official Fishbowl logo badge (`art/fishbowl_logo.svg`) with crisp white emblem.
- Consolidated repository licenses to a single canonical `LICENSE` file (GPLv3).
- Updated `THIRD_PARTY_LICENSES.md` with complete GPLv3 Section 6 source code disclosures.
- Expanded Content and Anti-Piracy Policy across `README.md`, `SETUP_GUIDE.md`, and project wiki.

---

## Release v1.4.7-beta.3

- **Release Date:** October 4, 2026
- **Tag:** `v1.4.7-beta.3`
- **Key Highlights:**
  - Automated CI/CD Release Bot with dual APK compilation and release publishing.
  - Thumb-zone docked bottom control panel for single-handed mobile navigation.
  - Persistent process kill history tracker with Markdown diagnostic report export.
  - Storage space full assertion (<50MB) and auto-restart loop circuit breaker.
  - Automated debounced error diagnostic modal.
  - Live console stream with desugaring crash fixes.

---

## Release v1.4.6

- **Release Date:** September 23, 2026
- **Tag:** `v1.4.6`
- **Key Highlights:**
  - Integrated Error Diagnoser for port conflicts, SELinux denials, and database stalls.
  - Live Permissions Dashboard for All-Files Access, Battery Exemption, and Notifications.
  - External storage normalization from raw `/mnt/media_rw/` to standard POSIX `/storage/<UUID>/`.
  - Guaranteed app data storage path integration for OEM restricted external drives.
