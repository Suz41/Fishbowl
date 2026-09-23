# Security Policy

We prioritize the security, integrity, and privacy of **Fishbowl**. This document outlines our security architecture, supported versions, and responsible disclosure procedures.

---

## Supported Versions

Security fixes and compatibility updates are actively maintained on the following release line:

| Version | Status | Notes |
| :--- | :--- | :--- |
| **v1.4.x** (v1.4.6+) | Supported | Current active production release line |
| **< v1.4.0** | Unsupported | Deprecated legacy releases; upgrade to v1.4.6 |

---

## Reporting a Vulnerability

If you discover a security vulnerability or sensitive flaw in Fishbowl, please do **not** open a public issue. We request that you disclose it responsibly:

### How to Report
1. **GitHub Security Advisory (Preferred):** Navigate to the **Security** tab of this repository and select **Report a vulnerability**.
2. **Private Email:** Alternatively, send a detailed vulnerability report to:
   **sujalacharii@gmail.com**

### What to Include in Your Report
To help us triage and resolve the issue quickly, please provide:
- Clear description of the vulnerability and its potential security impact
- Step-by-step reproduction instructions or a minimal Proof-of-Concept (PoC)
- Target Android OS version, patch level, and device model
- Any proposed remediation or patch if available

### Response & Disclosure Process
- **Acknowledgement:** We will acknowledge receipt of your vulnerability report within 48 hours.
- **Remediation:** We will assess severity, develop a fix on a private branch, and verify on physical Android hardware.
- **Coordinated Disclosure:** A patched release will be published alongside public release notes crediting the reporter (unless anonymity is requested).

---

## Security Architecture & Invariants

Fishbowl is engineered around strict security invariants to ensure user safety:

### 1. Zero Telemetry & Privacy Preservation
- **0 Remote Trackers:** No analytics SDKs (Google Analytics, Firebase, Mixpanel, etc.).
- **0 Background Sockets:** The application never phones home to external telemetry endpoints.
- **100% Local Execution:** All database queries, user management, and metadata indexing occur strictly on-device.

### 2. Sandboxed Process Execution
- Native .NET processes (`dotnet jellyfin.dll`), FFmpeg binaries, and SQLite engines execute exclusively within the Android application's dedicated UID sandbox (`/data/data/com.fishbowl.app/files/usr`).
- No root access is required or requested. The application operates entirely within unprivileged user space.

### 3. SELinux & Storage Security
- Media directory access strictly respects Android SELinux security boundaries.
- External storage interactions are anchored to the application's guaranteed package folder (`/storage/<UUID>/Android/data/com.fishbowl.app/files`) or verified POSIX paths to prevent privilege escalation or directory traversal.
- Virtual cloud DocumentProviders without Linux mount points are quarantined to prevent server corruption.

### 4. Local Network & Socket Bounds
- Kestrel web server bindings are limited to the local loopback interface (`127.0.0.1:8096`) and the device's local Wi-Fi / Tailscale subnet addresses.
- Fishbowl does not perform automatic UPnP port opening or expose ports to public WAN interfaces without user-configured network tunnels.

### 5. Reproducible Builds & Source Auditing
- All build configurations, ProGuard/R8 rules, and dependency manifests are fully open-source.
- Users can audit every line of source code, verify build reproducibility, or compile release APKs independently via `./gradlew assembleRelease`.
