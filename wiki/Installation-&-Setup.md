# Installation & Setup Guide

Getting your Fishbowl server up and running requires a few simple steps. Follow this guide to initialize your server.

## Step 1: Download & Install
1. Head to [Releases](https://github.com/Suz41/Fishbowl/releases/tag/v1.4.6) and download the latest version (`Fishbowl-v1.4.6-release-universal.apk`).
2. Open the APK on your Android device and proceed with the installation.
3. Launch the app and grant **Notifications** and **All-Files Storage Access** permissions when prompted.

## Step 2: Start the Server
1. On the **Home** tab, tap the **START SERVER** button.
2. The UI will guide you through the startup sequence:
   `STARTING_RUNTIME` -> `LAUNCHING_SERVER` -> `WAITING_FOR_SERVER` -> `CHECKING_READINESS` -> `READY`.
3. If an issue occurs (e.g. port collision or permission block), the **Comprehensive Error Diagnoser** will display the exact failure cause and corrective recovery steps.
4. Once the server status card turns green and displays **SERVER RUNNING**, tap **OPEN JELLYFIN**.

## Step 3: Run the Setup Wizard & Configure Media
1. The built-in WebView will display the Jellyfin Setup Wizard.
2. **Language:** Select your preferred display language.
3. **Admin User:** Create your primary administrator username and password.
4. **Media Libraries:** Add your media folders using Fishbowl's Consolidated Storage Engine:
   - For internal storage: Select common paths like `/storage/emulated/0/Movies` or `/storage/emulated/0/Music`.
   - For custom paths: In Fishbowl Settings -> **Manage Media Storage & Folders**, tap `[ ENTER PATH ]` to type or paste any directory.
   - For external USB OTG or SD drives: Tap the `[ App Data (Guaranteed) ]` chip on your drive card (`/storage/<UUID>/Android/data/com.fishbowl.app/files`) to copy the verified readable path.
   - Always verify via `[ COPY PATH FOR JELLYFIN ]` to ensure media files exist and SELinux does not block reading.
5. **Metadata:** Choose the default metadata retrieval settings for your libraries.
6. **Remote Access:** Turn on remote access options if you plan to connect from other devices on your home network.
7. Click **Finish** to log in to your server dashboard.
