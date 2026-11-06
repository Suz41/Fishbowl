# Fishbowl Architecture Specification

## Overview

Fishbowl (`com.fishbowl.app`) is an unprivileged, no-root media server platform and automation orchestrator for ARM64 Android devices. It pairs native Linux POSIX binaries compiled against Android's Bionic C runtime (`libc.so`) with an Android host control plane written in Java and Kotlin.

---

## 1. Java + Kotlin Hybrid Application Architecture

Fishbowl v1.4.10-beta.2 establishes an incremental Java + Kotlin hybrid architecture. Mature Android lifecycle and Termux runtime layers remain in Java, while new contracts, models, diagnostic tools, and UI presenters are introduced as Kotlin components.

### Package Boundaries

- `com.fishbowl.app.core`
  - Runtime configuration models (`RuntimeConfig.kt`).
  - Bidirectional interoperability verification (`KotlinInteropBridge.kt`).
- `com.fishbowl.app.services`
  - Lifecycle transition contracts (`ServiceLifecycleContract.kt`).
  - Service registry contracts (`ServiceRegistryContract.kt`).
  - Granular service state models (`ServiceHealthState.kt`, `ServiceLifecycleResult.kt`).
  - Multi-tier service readiness assessment (`ServiceHealthMonitor.kt`).
- `com.fishbowl.app.diagnostics`
  - System resource snapshot models (`SystemResourceSnapshot.kt`).
  - Lightweight resource and thermal monitoring (`ResourceMonitor.kt`).
- `com.fishbowl.app.process`
  - Process execution abstractions and structured result models (`ProcessResult.kt`).
- `com.fishbowl.app.storage`
  - Storage operation models and transfer integrity results (`StorageOperationResult.kt`).
- `com.fishbowl.app.ui`
  - Extracted presentation models and brand styling helpers (`DashboardPresenter.kt`).

### Kotlin Migration Principles

1. Zero Mass Conversions: Existing, verified Java classes are not mechanically converted to Kotlin.
2. Binary and JVM Interoperability: New Kotlin types maintain full compatibility with Java 8 bytecode targets using `@JvmStatic` and `@JvmOverloads` where appropriate.
3. Immutability and Null Safety: Core models are structured as immutable Kotlin data classes and sealed class hierarchies with explicit nullability.
4. Concurrency Safety: Heavy operations are isolated in bounded executor pools without uncontrolled thread growth or UI-thread blocking.

---

## 2. Termux POSIX Runtime Integration

Fishbowl embeds a sandboxed POSIX user space derived from Termux packages, deployed directly inside the application private directory:

```text
/data/data/com.fishbowl.app/files/usr/
```

- Native Bionic Execution: All binaries are compiled targeting Android Bionic libc (`libc.so`). No glibc translation layer, QEMU virtualization, chroot, or root access is used.
- Environment Scaffolding: Dynamic loaders, shared libraries, and configuration paths are resolved via environment variables (`LD_LIBRARY_PATH`, `PATH`, `TMPDIR`, `DOTNET_ROOT`).
- Process Supervision: Native companion processes and Jellyfin server processes are tracked via PID files, stdout/stderr background streaming, and graceful POSIX signal handlers (`SIGTERM`, `SIGKILL`).

---

## 3. Concurrency and Execution Management

Service lifecycle operations and background processing are separated across dedicated, bounded executor pools in `MediaServiceManager`:

1. Lifecycle Executor (`serviceExecutor`):
   - Thread pool size: 2 core, 4 maximum workers.
   - Queue: Bounded `LinkedBlockingQueue` (64 capacity).
   - Rejection policy: Explicit logging with thread tracking to prevent silent task loss.
   - Deduplication: Idempotent state guards ensure duplicate start, stop, or restart commands are safely ignored.
2. Process I/O Pool (`processIoExecutor`):
   - Dedicated daemon thread pool handling long-running stdout/stderr streaming and blocking `process.waitFor()` calls.
   - Decoupled from lifecycle workers to prevent pool starvation during multi-service operation.
3. Deduplication Scheduler (`dedupScheduler`):
   - Single-threaded scheduler executing debounced service discovery and stack auto-wiring.

---

## 4. Service Readiness and Health Monitoring

`ServiceHealthMonitor` enforces a strict distinction between process occupancy and full service readiness:

1. Process Alive: PID is active and verifiable in OS table.
2. Port Listening: Local TCP socket binds and accepts loopback connections (`127.0.0.1:<port>`).
3. HTTP Endpoint Responding: Web server returns standard HTTP response codes (`200..399`, `401` Unauthorized indicating active auth layer, or `409` Conflict indicating RPC handshake).
4. Application API Ready: Downstream endpoints respond to service queries.
5. Service Fully Operational: Stack integration and database locks verified.

Security Safeguard: Health checks inject authentication tokens exclusively via HTTP request headers (`X-Api-Key`), strictly preventing credential leakage in URL query parameters and diagnostic log dumps.

---

## 5. Storage Architecture and Backup Reliability

- Internal Scaffolding: Standardized storage layout rooted at `/storage/emulated/0/FishbowlMedia/`.
- External / USB OTG Access: Guaranteed Android application directories (`/storage/<UUID>/Android/data/com.fishbowl.app/files`) validated through Storage Access Framework (SAF) diagnostic checks.
- Disaster Recovery: One-tap tarball archives (`fishbowl_backup_*.tar.gz`) capturing Jellyfin databases, companion configuration XMLs, and runtime state with SHA-256 integrity validation.
