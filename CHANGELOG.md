# Changelog

All notable changes to the Fishbowl project are documented in this file.

---

## [1.4.10-beta.2] - 2026-11-06

### Added
- **Java + Kotlin Hybrid Architecture:** Integrated Kotlin Gradle Plugin 2.1.20 with AGP 8.13.2 targeting Java 8 bytecode compatibility.
- **Kotlin Core Models:** Added immutable data models and sealed hierarchies:
  - `com.fishbowl.app.core.RuntimeConfig`
  - `com.fishbowl.app.services.ServiceHealthState`
  - `com.fishbowl.app.services.ServiceHealthSnapshot`
  - `com.fishbowl.app.services.ServiceLifecycleResult`
  - `com.fishbowl.app.process.ProcessResult`
  - `com.fishbowl.app.diagnostics.SystemResourceSnapshot`
  - `com.fishbowl.app.storage.StorageOperationResult`
- **Service Lifecycle Contracts:** Introduced `ServiceLifecycleContract` and `ServiceRegistryContract` interfaces implemented by `MediaServiceManager`.
- **Service Health Monitor:** Extracted multi-tier service assessment into `ServiceHealthMonitor.kt` distinguishing process life, port listening, HTTP response, and API readiness.
- **Lightweight Resource Monitor:** Implemented `ResourceMonitor.kt` sampling RAM, storage thresholds, process counts, and thermal status.
- **Dashboard Presentation Helper:** Extracted `DashboardPresenter.kt` for brand colors, status badges, and byte formatting.
- **Automated Test Coverage:** Added `KotlinJavaInteropTest` and `ServiceLifecycleContractsTest`.

### Fixed
- **Network IP Discrepancy:** Centralized IP resolution in `NetworkUtils.java` with strict prioritization (`wlan*` -> `rndis*`/`usb*`/`eth*` -> `ap*`/`softap*`) and filtering of cellular/virtual interfaces.
- **Widget Foreground Notification:** Replaced non-foreground PendingIntent starts in widgets with `PendingIntent.getForegroundService()` targeting `JellyfinServerService`, eliminating Android 12+ / 14+ background launch exceptions.
- **Seerr API Key Exposure:** Restored Seerr API key extraction across configuration paths with UI copy action in `FishbowlActivity`.
- **Diagnostics Centre:** Streamlined Diagnostics section by eliminating redundant report buttons.
- **Service Execution Concurrency:** Bounded `MediaServiceManager` worker concurrency (2 core, 4 max, 64-slot queue) and offloaded long-running process stream monitoring to dedicated `processIoExecutor`.
- **Service Readiness Evaluation:** Updated health check evaluation to prevent occupying port sockets from falsely reporting unhealthy services as ready; added header-based API key injection.

---

## [1.4.10-beta.1] - 2026-11-04

### Added
- Native Android Quick Settings tile for 1-tap server toggle.
- Diagnostic report export (.txt) and filtered crash log viewer.
- Jellyfin database and cache breakdown inspection with one-tap cache clearance.
- Movie Importer keep-screen-awake toggle, two-phase replacement commit, and SHA-256 verification.

### Fixed
- Resolved companion services connection refused issues on Android 15 under multi-service load.
- Resolved Radarr movie remaining with missing tag when deleted in Jellyfin.
- Restored Jackett icon scale and typography to standard dimensions.
