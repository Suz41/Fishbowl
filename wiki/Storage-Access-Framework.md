# Storage Access Framework (SAF) Bridge

Starting with Android 11, the OS enforces Scoped Storage boundaries to restrict applications from freely traversing local storage. To stream media stored in non-standard directories, SD cards, or external USB OTG drives, Fishbowl provides a Storage Access Framework (SAF) document tree bridge.

## How to Configure Media Folders via SAF

1. Open the **Settings** tab in the Fishbowl application.
2. Under the Storage section, tap **Manage Media Storage (SAF)**.
3. Tap **Add Media Folder**.
4. The system document picker will launch. Select the root folder of your media files (e.g., your Movies directory on a microSD card) and tap **Use This Folder**.
5. When Android prompts you to grant Fishbowl access to files in that folder, tap **Allow**.

## Storage Access Features
*   **Persistent URIs:** Fishbowl automatically persists URI permissions across device reboots, ensuring your media libraries do not disconnect when the device restarts.
*   **Storage Breakdown:** The settings screen parses the active directory tree and calculates size metrics so you can keep track of storage capacity.
