# Contributing to JellyfinDroid

Thank you for your interest in contributing to JellyfinDroid! We welcome contributions from developers, designers, testers, and documentation enthusiasts.

## Ways to Contribute

### 🐛 Report Bugs
Found a bug? Please open an issue with:
- Clear description of the problem
- Steps to reproduce
- Expected vs. actual behavior
- Device/Android version info
- Relevant logs or screenshots

### 💡 Suggest Features
Have an idea? Open an issue with:
- Clear description of the feature
- Why it would be useful
- Any proposed implementation details

### 📝 Improve Documentation
Help us improve guides, README, or comments:
- Fix typos or unclear explanations
- Add examples or clarifications
- Improve formatting or structure

### 🔧 Contribute Code
Want to code? Here's how:

1. **Fork the repository**
2. **Create a feature branch**: `git checkout -b feature/your-feature-name`
3. **Make your changes** with clear commit messages
4. **Test thoroughly** on ARM64 Android devices
5. **Push to your fork**: `git push origin feature/your-feature-name`
6. **Open a Pull Request** with:
   - Clear description of changes
   - Reference any related issues
   - Screenshots if UI changes

## Development Setup

### Prerequisites
- Android Studio
- Java Development Kit (JDK 11+)
- Android SDK (API level 21+)
- ARM64 device or emulator

### Getting Started
```bash
# Clone your fork
git clone https://github.com/YOUR-USERNAME/JellyfinDroid.git
cd JellyfinDroid

# Create a feature branch
git checkout -b feature/your-feature

# Build the project
./gradlew build

# Run on device
./gradlew installDebug
```

## Code Style

- Follow Java conventions and existing code patterns
- Use meaningful variable and method names
- Add comments for complex logic
- Keep functions focused and modular

## Pull Request Guidelines

- Keep PRs focused on a single feature or fix
- Write clear commit messages
- Update documentation if needed
- Test on multiple device sizes/Android versions
- Be responsive to review feedback

## Questions?

Have questions? Open an issue or check existing discussions. We're here to help!

---

**Happy coding! 🎉**