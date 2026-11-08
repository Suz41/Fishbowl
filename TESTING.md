# Testing & Verification Guide

## Overview

This guide details testing procedures, verification gates, and diagnostic commands for Fishbowl (`com.fishbowl.app`).

---

## 1. Automated Unit Testing

Fishbowl includes automated unit test suites covering Java/Kotlin interoperability, process supervision, security validation, and service lifecycle transitions.

### Running Unit Tests

Execute the full debug test suite via Gradle:

```bash
./gradlew testDebugUnitTest
```

Execute specific test suites:

- Kotlin/Java Interoperability:
  ```bash
  ./gradlew :app:testDebugUnitTest --tests "com.fishbowl.app.core.KotlinJavaInteropTest"
  ```
- Service Lifecycle & Core Models:
  ```bash
  ./gradlew :app:testDebugUnitTest --tests "com.fishbowl.app.services.ServiceLifecycleContractsTest"
  ```
- Process Supervision & Circuit Breaker:
  ```bash
  ./gradlew :app:testDebugUnitTest --tests "com.fishbowl.app.ProcessSupervisionTest"
  ```
- Backup & Storage Security:
  ```bash
  ./gradlew :app:testDebugUnitTest --tests "com.fishbowl.app.BackupRestoreSecurityTest"
  ```

---

## 2. Compilation and Code Quality Gates

### Kotlin and Java Compilation Verification

Validate clean compilation of both languages:

```bash
./gradlew :app:compileDebugKotlin :app:compileDebugJavaWithJavac
```

### Android Lint Execution

Run Android Lint across debug variants:

```bash
./gradlew :app:lintDebug
```

Lint reports are generated at:
`app/build/reports/lint-results-debug.html`

---

## 3. Physical Device Testing (ADB)

For physical ARM64 hardware verification (e.g. Android 11 through 16):

### Service Status Inspection

Query running Fishbowl processes:

```bash
adb shell ps -ef | grep com.fishbowl.app
```

Verify open network ports on host:

```bash
adb shell "netstat -tuln 2>/dev/null || ss -tuln 2>/dev/null" | grep -E '8096|7878|8989|9696|9117|9091|5055|6767'
```

### Logcat Live Monitoring

Stream unified Fishbowl and service manager logs:

```bash
adb logcat -v time -s JellyfinServerService:D MediaServiceManager:D JellyfinController:D
```
