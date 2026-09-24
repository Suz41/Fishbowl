# Fishbowl

An unofficial standalone server host and launcher for Jellyfin on Android (ARM64).

[![Release](https://img.shields.io/badge/Release-v1.4.6-blue.svg)](https://github.com/Suz41/Fishbowl/releases/tag/v1.4.6)
[![Jellyfin Core](https://img.shields.io/badge/Jellyfin%20Core-12.1.0-purple.svg)](https://jellyfin.org)
[![Runtime](https://img.shields.io/badge/.NET-10.0%20ARM64-512BD4.svg)](https://dotnet.microsoft.com)
[![Platform](https://img.shields.io/badge/Android-ARM64-3DDC84.svg?logo=android&logoColor=white)](https://android.com)
[![License](https://img.shields.io/badge/License-GPLv3-yellow.svg)](LICENSE.md)

> [!IMPORTANT]
> Fishbowl is an independent, unofficial community project. It is not affiliated with, maintained by, or endorsed by the official Jellyfin project or its developers. Please report Fishbowl-specific issues in this repository rather than to the official Jellyfin project.
>
> Fishbowl is provided as-is for personal experimentation and home-lab use. Mobile devices and TV boxes have different thermal, storage, and background-process behavior from dedicated servers. Test your device and workload before relying on it for continuous service.

Fishbowl runs Jellyfin on a compatible ARM64 Android device and provides a local interface for starting the server, viewing status, selecting media folders, and accessing diagnostics.

---

## Quick Start

1. Download the latest [Fishbowl release](https://github.com/Suz41/Fishbowl/releases).
2. Install it on a compatible ARM64 Android device.
3. Open Fishbowl and wait for the server status to show that it is running.
4. Tap **OPEN JELLYFIN**, or connect from another device using the LAN address displayed by Fishbowl (for example, `http://192.168.1.xxx:8096`).

For release `v1.4.6`, the published APK checksum is:

```text
SHA-256: D9F9AF398D988AFBF6483E8196F0226B8B90CF0C7557B566537BF20D05E8A930
```

---

## Preview

<p align="center">
  <img src="docs/screenshots/01_home_screen.png" width="30%" alt="Fishbowl home screen" />
  &nbsp;
  <img src="docs/screenshots/02_logs_tab.png" width="30%" alt="Fishbowl logs screen" />
  &nbsp;
  <img src="docs/screenshots/03_settings_tab.png" width="30%" alt="Fishbowl settings and diagnostics" />
</p>

---

## Key Features

- Hosts Jellyfin locally on compatible ARM64 Android devices.
- Starts, stops, and restarts the Jellyfin server from the Fishbowl interface.
- Displays local, LAN, and—when available—Tailscale network addresses.
- Provides server logs and basic runtime diagnostics.
- Supports selecting media folders through Android storage tools and custom paths.
- Includes background-service and battery-optimization guidance for devices that restrict background applications.
- Provides setup progress and status feedback while the server runtime is prepared.
- Supports local SQLite-backed Jellyfin data and configuration storage.

Fishbowl does not require a separate desktop or container host, but performance and compatibility depend on the Android device, OS version, storage permissions, network, and media workload.

---

## Client Compatibility

Jellyfin clients that can reach the device over the local network may be used, including:

- Jellyfin Android TV and mobile clients
- Jellyfin iOS, Swiftfin, and compatible third-party clients
- Web browsers
- Roku and Kodi clients, where supported by the Jellyfin client ecosystem

Playback capabilities depend on the client, media format, network, and the device's available decoding or transcoding support. Hardware transcoding is not guaranteed by Fishbowl; verify the behavior on your device and media library.

---

## Storage & Media Setup

Jellyfin needs read access to the directories that contain your media. Fishbowl provides storage-management tools to browse for folders, validate selected paths, and copy a path for use in the Jellyfin web interface.

### Internal Storage

Grant the storage access requested by Android, then select an appropriate media directory, such as:

- `/storage/emulated/0/Movies`
- `/storage/emulated/0/Music`
- `/storage/emulated/0/Download`

These are examples; the available paths and required permissions vary by Android version and device.

### USB and MicroSD Storage

Android storage restrictions can prevent native applications from reading arbitrary directories on removable storage. When available, use the Fishbowl app-data directory exposed on the removable volume:

```text
/storage/<UUID>/Android/data/com.fishbowl.app/files
```

The `<UUID>` portion is an example placeholder and differs between devices and volumes. Move or copy media there only if that arrangement suits your storage setup, then use **Settings → Manage Media Storage & Folders** to select and copy the path into Jellyfin.

### Storage Access Framework and Custom Paths

Fishbowl can use Android's document-tree picker and also provides a custom path option. Whether a selected path is readable depends on Android permissions, device policy, and filesystem behavior.

---

## Background Operation

Fishbowl can use an Android foreground service, a wake lock, and battery-optimization settings to help keep the server available while the application is in the background. Android and device manufacturers may still stop background work, especially under memory, battery, or thermal pressure.

If the server stops when the screen is locked, set Fishbowl's battery usage to **Unrestricted** where that option is available. The exact menu names vary by Android version and manufacturer.

---

## Permissions & Privacy

Fishbowl uses Android permissions to run the local server, keep it available in the background, access selected media, and communicate over the local network. Depending on the device and Android version, these may include foreground-service, wake-lock, network, notification, and external-storage access.

The project is designed for local operation and does not intentionally include analytics SDKs or remote tracking services. Network activity can still occur when users access Jellyfin clients, download releases, follow update links, or use other network-enabled functionality. Do not treat this statement as a guarantee that every future build or dependency has identical behavior; review the source and build a version yourself if that matters for your threat model.

---

## Building From Source

### Prerequisites

- Windows, macOS, or Linux
- OpenJDK 17 or OpenJDK 21
- Android SDK (API 28 or later) and a compatible Android NDK
- The included Gradle wrapper

### Build Commands

```bash
git clone https://github.com/Suz41/Fishbowl.git
cd Fishbowl

./gradlew assembleDebug
./gradlew assembleRelease
```

Generated APKs are placed under `app/build/outputs/apk/`.

---

## Troubleshooting

### The server stops when I lock the phone or switch apps

Set Fishbowl's battery usage to **Unrestricted** and keep the device connected to power for sustained workloads. OEM background-process policies may still affect availability.

### Other devices cannot connect

Confirm that both devices are on a network that permits local traffic, that the address and port shown by Fishbowl are current, and that Android or the network is not blocking the connection. A Tailscale address is usable only when the relevant devices are connected to the same tailnet and permitted to communicate.

### Jellyfin cannot read a media folder

Recheck the selected folder and Android's storage permissions. On removable storage, Android security policies may restrict arbitrary paths; try the Fishbowl app-data directory shown by the storage manager. Also confirm that the directory contains supported media files.

### How do I update without losing my library?

Install the update over the existing application when supported by Android. Back up important Jellyfin data before upgrading. Fishbowl stores server data inside its application storage, but uninstalling the application or clearing its data can remove that data.

---

## Disclosures & License

Fishbowl is an unofficial community project and is not supported by the official Jellyfin project. It is developed and distributed under the [GNU General Public License v3.0](LICENSE.md).

Jellyfin and related components remain subject to their respective licenses. Fishbowl also uses open-source runtime, packaging, and native components; consult the repository and included notices for details.

This project is provided without warranties or service-level guarantees. Test releases, protect your data with backups, and use the issue tracker for Fishbowl-specific reports.
