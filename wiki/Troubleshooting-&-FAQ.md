# Troubleshooting & FAQ

Here are answers and workarounds for the most common issues you might run into while hosting a Jellyfin server with Fishbowl.

## 🔋 Battery & Background Playback Issues

### Q: The server turns off when the screen locks or when I leave the app
Many Android devices have aggressive battery management daemons that will terminate background services, even Foreground Services with wake locks.

**Solution:**
1. Open your Android Settings and navigate to **Apps** ➡️ **Fishbowl** ➡️ **Battery**.
2. Change the battery optimization setting to **Unrestricted** (or "Don't Optimize").
3. For specific OEMs (like Xiaomi/HyperOS, Samsung, OnePlus), you may need to allow background autostart or lock the app in the recent apps tray.

---

## 🌐 Network & Client Connections

### Q: How do I find my server's LAN address?
On the **Home** tab, once the server status turns to **SERVER RUNNING**, the application displays your network card details:
*   **Loopback Address:** `http://127.0.0.1:8096` (for playing media on the host device itself)
*   **LAN Address:** `http://192.168.X.X:8096` (for playing media on other devices connected to your Wi-Fi network)

### Q: Other devices cannot connect to the server
1. Double-check that your Android server host and the client device (Android TV, Roku, laptop, etc.) are connected to the exact same Wi-Fi network/SSID.
2. Check if your Wi-Fi router has AP Isolation (client isolation) enabled. If enabled, the router prevents wireless clients from communicating with each other. You must turn this off in your router settings.
3. Make sure the LAN IP has not changed. Routers periodically re-assign IPs. If you want a permanent address, configure a **Static IP** or **DHCP Reservation** for your server device in your router dashboard.

---

## 📁 Upgrades & Data Preservation

### Q: Will I lose my libraries or watch history when I update Fishbowl?
**No.** All user configuration files, databases (metadata, user watch history, accounts), and custom structures are saved within the app's persistent user-data subdirectory (`--datadir` points to `/data/data/com.fishbowl.app/files/home/.local/share/jellyfin`). 

When you install a newer version of the APK, the Android system preserves this directory, so your server state survives update cycles.
