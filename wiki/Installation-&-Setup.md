# Installation & Setup Guide

Getting your Fishbowl server up and running requires a few simple steps. Follow this guide to initialize your server.

## Step 1: Download & Install
1. Head to [Releases](https://github.com/Suz41/Fishbowl/releases) and download the latest version (`Fishbowl-vX.Y.Z-release-universal.apk`).
2. Open the APK on your Android device and proceed with the installation.
3. Launch the app and grant **Notifications** and **Storage** permissions when prompted.

## Step 2: Start the Server
1. On the **Home** tab, tap the **START SERVER** button.
2. The UI will guide you through the startup sequence:
   `STARTING_RUNTIME` ➡️ `LAUNCHING_SERVER` ➡️ `WAITING_FOR_SERVER` ➡️ `CHECKING_READINESS` ➡️ `READY`.
3. Once the server status card turns green and displays **SERVER RUNNING**, tap **OPEN JELLYFIN**.

## Step 3: Run the Setup Wizard
1. The built-in WebView will display the Jellyfin Setup Wizard.
2. **Language:** Select your preferred display language.
3. **Admin User:** Create your primary administrator username and password.
4. **Media Libraries:** Add your media folders. You can point them to internal storage directories (like `/sdcard/Movies`) or configure custom external directories using the [Storage Access Framework Bridge](Storage-Access-Framework).
5. **Metadata:** Choose the default metadata retrieval settings for your libraries.
6. **Remote Access:** Turn on remote access options if you plan to connect from other devices on your home network.
7. Click **Finish** to log in to your server dashboard.
