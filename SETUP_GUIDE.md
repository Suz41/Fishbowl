# Fishbowl Setup Guide

Complete guide to installing and running Fishbowl on your Android ARM64 device.

## 📋 Requirements

### Device Requirements
- **Android Version**: 5.0 (API 21) or higher
- **Architecture**: ARM64 (ARMv8)
- **RAM**: Minimum 2GB (4GB+ recommended)
- **Storage**: Depends on media library size
- **Network**: Stable WiFi or cellular connection

### Server Requirements
- Stable internet connection
- Port forwarding (optional, for external access)
- Dynamic DNS service (optional, for remote access)

## 🚀 Installation Steps

### Step 1: Download the App
1. Go to [Fishbowl Releases](https://github.com/Suz41/Fishbowl/releases)
2. Download the latest `Fishbowl-v*-release-universal.apk`
3. Save to your Android device

### Step 2: Install the APK
1. Open your device's file manager
2. Navigate to the downloaded APK file
3. Tap to install
4. Grant necessary permissions when prompted
5. Wait for installation to complete

### Step 3: Initial Setup
1. Launch Fishbowl
2. Enter your Jellyfin server address:
   - **Local Network**: `http://192.168.1.XXX:8096`
   - **Remote**: `https://your-domain.com:8920`
3. Enter your credentials (Jellyfin username & password)
4. Tap "Connect"

### Step 4: Configure Settings
1. **Server Settings**
   - Media folders
   - Transcoding options
   - User permissions

2. **App Settings**
   - UI Theme (Material 3)
   - Playback quality
   - Background streaming

## 🔧 Configuration

### Local Network Setup
```
Server Address: http://[LOCAL_IP]:8096
Example: http://192.168.1.100:8096
```

### Remote Access (Port Forwarding)
```
Server Address: https://[YOUR_DOMAIN]:8920
Example: https://jellyfin.example.com:8920
```

## 🎵 Media Library

### Supported Formats
- **Video**: MP4, MKV, AVI, MOV, WebM
- **Audio**: MP3, FLAC, WAV, AAC, OGG
- **Subtitles**: SRT, ASS, SSA, VTT

### Organizing Media
```
Media/
├── Movies/
│   ├── Action/
│   └── Comedy/
├── TV Shows/
│   ├── Series Name/
│   │   ├── Season 1/
│   │   └── Season 2/
└── Music/
    ├── Artist Name/
    └── Album Name/
```

## ⚙️ Troubleshooting

### App Won't Connect
- Check server address is correct
- Verify network connectivity
- Ensure server is running and accessible
- Check firewall settings

### Playback Issues
- Confirm device supports video codec
- Try lowering playback quality
- Check available storage space
- Restart the app and server

### Background Streaming Not Working
- Enable background permissions in Android settings
- Check battery optimization settings
- Verify sufficient RAM available
- Update to latest app version

### Performance Issues
- Disable background tasks
- Lower resolution/quality settings
- Reduce number of concurrent streams
- Free up device storage

## 📱 Background Streaming

Fishbowl supports background streaming with Material 3 UI:

1. Start playback
2. Minimize app or press home
3. Music/audio continues playing
4. Control playback from notification or lock screen

## 🔐 Security Tips

- Use strong passwords for Jellyfin account
- Enable HTTPS for remote access
- Keep app updated
- Use firewall protection
- Avoid sharing credentials

## 🆘 Need Help?

- Check existing [Issues](https://github.com/Suz41/Fishbowl/issues)
- Open a new issue with bug report
- Review [SECURITY.md](SECURITY.md) for security concerns

## 📞 Support

For additional help:
- Visit Jellyfin documentation: https://jellyfin.org/docs/
- Check app settings for debug logs
- Open an issue on GitHub

---

**Happy streaming! 🎬🎵**
