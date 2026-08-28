# Security Policy

We take the security of **Fishbowl** seriously. This document outlines our security policies, supported versions, and how to report vulnerabilities.

## Supported Versions

Security updates are actively applied to the following versions:

| Version | Supported | Notes |
| :--- | :--- | :--- |
| **v1.4.x** | Yes | Active stable release branch |
| **v1.0.x - v1.3.x** | No | Deprecated legacy releases |

## Reporting a Vulnerability

If you discover a security vulnerability in Fishbowl, please do **not** open a public issue. Instead, report it privately to ensure we can publish a fix before public disclosure:

1. Send an email detailing the vulnerability to: **sujalacharii@gmail.com**
2. Include step-by-step instructions, a proof-of-concept (PoC), and the target Android OS/SDK version.

We aim to acknowledge your report within 48 hours and coordinate a fix release within 14 days.

## Security Architecture & Invariants

Fishbowl adheres to strict local-only execution and sandboxing models:
- **No Telemetry / Analytics:** 0 tracking, 0 metrics reporting, 0 telemetry sockets.
- **Scoped Storage Protection:** Leverages the Android Storage Access Framework (SAF) to restrict file access strictly to user-selected media directories.
- **Process Isolation:** The native .NET server runs entirely in the application's isolated runtime sandbox.
