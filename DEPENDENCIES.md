# Dependencies Specification

## Overview

This document specifies all direct build tools, language runtimes, libraries, and native packages utilized by Fishbowl (`com.fishbowl.app`).

---

## 1. Build Tools & Compilers

| Tool | Version | Purpose |
| :--- | :--- | :--- |
| Android Gradle Plugin (AGP) | 8.13.2 | Core Android compilation toolchain |
| Gradle Wrapper | 9.5.1 | Project build automation engine |
| Kotlin Gradle Plugin | 2.1.20 | Kotlin language compiler and Android plugin |
| Java Development Kit (JDK) | 17 (Temurin) | JVM compiler and build environment |
| Android Compile SDK | 36 | Android API platform target |
| Android Minimum SDK | 21 (Android 5.0) | Base backward-compatibility floor |
| Android Target SDK | 28 (Android 9.0) | App behavior compatibility target |
| Android NDK | 29.0.14206865 | Native C/C++ cross-compilation toolchain |

---

## 2. Android Host Dependencies

| Library | Version | Scope | Function |
| :--- | :--- | :--- | :--- |
| `org.jetbrains.kotlin:kotlin-stdlib` | 2.1.20 | Implementation | Kotlin standard runtime library |
| `androidx.annotation:annotation` | 1.11.0 | Implementation | Code analysis annotations |
| `androidx.core:core` | 1.13.1 | Implementation | Android framework compatibility utilities |
| `androidx.documentfile:documentfile` | 1.1.0 | Implementation | Storage Access Framework (SAF) tree manipulation |
| `androidx.drawerlayout:drawerlayout` | 1.2.0 | Implementation | Navigation drawer controls |
| `androidx.preference:preference` | 1.2.1 | Implementation | Application preferences management |
| `androidx.viewpager:viewpager` | 1.1.0 | Implementation | Tabbed swipe navigation |
| `com.google.android.material:material` | 1.12.0 | Implementation | Material Design components |
| `com.google.guava:guava` | 24.1-jre | Implementation | Google collection and concurrency utilities |
| `org.apache.commons:commons-compress` | 1.28.0 | Implementation | Tar, gzip, and zip archive processing |
| `io.noties.markwon:*` | 4.6.2 | Implementation | Markdown rendering for release notes and help dialogs |
| `junit:junit` | 4.13.2 | Test | Unit testing framework |

---

## 3. Native Linux & Bionic Packages

| Package | Version | Binary / Library Target |
| :--- | :--- | :--- |
| Jellyfin Core Server | 12.4.0 | `jellyfin.dll` (.NET 10 ARM64) |
| Microsoft .NET CLR | 10.0.12 | `dotnet` executable (Linux Bionic ARM64) |
| Jellyfin FFmpeg | 7.1.4 | `ffmpeg`, `ffprobe` |
| SQLite Engine | 3.46.1 | `libe_sqlite3.so` |
| Transmission | 4.1.4 | `transmission-daemon`, `transmission-remote` |
| Node.js | 20.x | `node` (Seerr runtime) |
| Python | 3.11.x | `python3` (Bazarr runtime) |
