# Contributing to Fishbowl

Thank you for your interest in contributing to Fishbowl. We welcome contributions from developers, testers, designers, and documentation contributors.

---

## Community & Upstream Notice

- **Unofficial Community Project:** Fishbowl is an independent, unofficial community project and is not affiliated with, maintained by, or endorsed by the official Jellyfin project or its core developers.
- **Do Not Contact Upstream Jellyfin:** Please do not submit bug reports, support tickets, or inquiries regarding Fishbowl to official Jellyfin forums, issue trackers, or Discord servers. Direct all contributions, questions, and issues exclusively to this repository.
- **Tone & Code Hygiene:** This project enforces a strict zero-emoji policy across all code, commit messages, documentation, UI strings, and issue templates.

---

## Ways to Contribute

### Report Bugs
Found a bug or runtime exception? Please open an issue on GitHub with:
- Clear description of the unexpected behavior
- Exact device model, Android OS version, and chipset architecture (ARM64)
- Step-by-step reproduction instructions
- Relevant log output (use the on-disk Serilog log viewer or adb logcat)
- Screenshots or screen recordings for UI defects

### Suggest Enhancements
Have an architectural idea or UI refinement? Open an issue detailing:
- Clear explanation of the proposed feature
- Motivation and practical utility for Android self-hosted streaming
- Proposed implementation approach or API constraints (e.g. Android SELinux, Bionic compatibility)

### Improve Documentation
Contributions to project documentation, technical guides, and code comments are welcome:
- Fix factual inaccuracies, outdated instructions, or typos
- Improve setup guides for specific Android OEMs (Xiaomi, Vivo, Samsung, etc.)
- Expand hardware transcoding and network architecture documentation

### Submit Code Changes
To contribute code to Fishbowl:

1. **Fork the Repository:** Create your own fork on GitHub.
2. **Branch from main:** `git checkout -b feature/your-feature-name`
3. **Make Surgical Edits:** Keep changes targeted and modular. Avoid unnecessary formatting churn.
4. **Adhere to Code Style:**
   - Pure Dark Mode palette for UI components (`#121316` background, `#1E2025` card surface, `#272930` elevated/pressed, `#00A4DC` cyan accent).
   - Zero emojis in Java code, strings, log messages, and documentation.
   - Maintain backwards compatibility across Android 5.0 through Android 16 (API 21 to 36).
5. **Verify on Physical Hardware:**
   - Build and test on a physical ARM64 Android device.
   - Confirm `./gradlew assembleDebug` and `./gradlew assembleRelease` pass with zero errors.
6. **Submit a Pull Request:** Open a PR against `main` with a clear explanation of changes, test evidence, and related issue numbers.

---

## Development Environment Setup

### Prerequisites
- Windows 10/11, macOS, or Linux
- OpenJDK 17 or OpenJDK 21
- Android SDK Platform Tools (API 21 through 36)
- Android NDK (r25+)
- Physical ARM64 Android device with USB debugging enabled (recommended)

### Build Commands
```bash
# Clone the repository
git clone https://github.com/Suz41/Fishbowl.git
cd Fishbowl

# Compile debug APK
./gradlew assembleDebug

# Install directly to connected device
adb install -r -d app/build/outputs/apk/debug/Fishbowl-v1.4.6-debug-arm64-v8a.apk

# Compile production release universal APK
./gradlew assembleRelease
```

---

## Pull Request Checklist

Before submitting your pull request, verify:
- [ ] Code builds cleanly with `./gradlew assembleDebug` and `./gradlew assembleRelease`.
- [ ] No emojis in code, commits, comments, or documentation.
- [ ] UI changes adhere to the Pure Dark Mode palette (`#121316`, `#1E2025`, `#272930`, `#00A4DC`).
- [ ] Storage paths respect Android SELinux constraints (test external drive handling if applicable).
- [ ] Commit message is descriptive, concise, and follows conventional commit formatting.
