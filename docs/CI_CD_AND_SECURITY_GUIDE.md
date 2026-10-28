# Fishbowl CI/CD & Security Hardening Guide

This document establishes the architecture, security policies, local developer workflows, and release engineering standards for **Fishbowl** (`Suz41/Fishbowl`).

---

## 1. Automation Architecture & Workflow Matrix

Fishbowl operates with automated GitHub Actions pipelines designed for strict least-privilege permissions, reproducibility, and cryptographic validation.

| Workflow File | Name | Trigger Events | Primary Responsibilities | Permissions | Artifacts Produced |
| :--- | :--- | :--- | :--- | :--- | :--- |
| [`.github/workflows/build.yml`](../.github/workflows/build.yml) | `Build Bot` | Push & PR (`main`, `develop`), manual dispatch | Gradle wrapper validation, unit tests, lint check, debug APK build, release build dry-run validation, SHA-256 calculation | `contents: read` | `debug-apk` (7 days), `release-validation-apk` (3 days), `lint-report` (7 days), `unit-test-reports` (on failure, 7 days) |
| [`.github/workflows/lint.yml`](../.github/workflows/lint.yml) | `Linter Bot` | Push & PR (`main`, `develop`), manual dispatch | Dedicated Android Lint analysis (`:app:lintDebug`) | `contents: read` | `lint-reports` (7 days) |
| [`.github/workflows/release.yml`](../.github/workflows/release.yml) | `Release Bot` | Tag push (`v*`), manual dispatch with confirmation | Tag semantic validation, commit provenance verification, pre-release checks (unit tests + lint), production APK assembly, SHA-256 generation, GitHub Release publication | `contents: write` | Official release APKs, `release-checksums.sha256` |
| GitHub Default Setup | `CodeQL Analysis` | Push & PR (`main`), weekly cron | Native SAST scanning across Java/Kotlin, C/C++, JavaScript, and Actions | `security-events: write` | CodeQL SARIF security alerts |
| [`.github/workflows/nightly.yml`](../.github/workflows/nightly.yml) | `Scheduled Regression CI` | Weekly cron (Sunday 03:00 UTC), manual dispatch | Toolchain drift detection, upstream link verification, clean build & release validation | `contents: read` | `regression-failure-reports` (on failure) |
| [`.github/workflows/dependency-review.yml`](../.github/workflows/dependency-review.yml) | `Dependency Review` | PR (`main`) | Vulnerability review of newly introduced package dependencies | `contents: read`, `pull-requests: write` | PR security review comment |
| [`.github/workflows/labeler.yml`](../.github/workflows/labeler.yml) | `PR Labeler Bot` | PR (`opened`, `synchronize`) | Auto-categorization of pull requests by path | `contents: read`, `pull-requests: write` | None |
| [`.github/workflows/stale.yml`](../.github/workflows/stale.yml) | `Stale Bot` | Daily cron (00:00 UTC), manual dispatch | Automatic triaging and cleanup of inactive issues/PRs (exempts bugs & security) | `issues: write`, `pull-requests: write` | None |
| [`.github/workflows/welcome.yml`](../.github/workflows/welcome.yml) | `Welcome Bot` | Issue/PR (`opened`) | Community onboarding, log submission guidance, project guidelines enforcement | `issues: write`, `pull-requests: write` | None |

---

## 2. Local Reproduction Guide (Windows 10 & Linux)

Developers can reproduce all CI checks locally prior to committing or opening a pull request.

### 2.1 Prerequisites
- **JDK:** OpenJDK 17 or OpenJDK 21 (Temurin distribution recommended)
- **Android SDK:** Platform API 36, Build-Tools 36.x
- **Android NDK:** Version `29.0.14206865` (set in `gradle.properties`)

### 2.2 Execution Steps (PowerShell / Windows CMD)

```powershell
# 1. Verify Gradle wrapper integrity (SHA-256)
Get-FileHash -Path "gradle\wrapper\gradle-wrapper.jar" -Algorithm SHA256

# 2. Download and verify Termux bootstrap packages
.\gradlew.bat downloadBootstraps

# 3. Compile Java & Native C/C++ Components
.\gradlew.bat :app:compileDebugJavaWithJavac

# 4. Run Unit Test Suite
.\gradlew.bat testDebugUnitTest

# 5. Execute Android Lint
.\gradlew.bat :app:lintDebug

# 6. Assemble Debug APK
.\gradlew.bat assembleDebug

# 7. Validate Release Build Compilation (without publishing)
.\gradlew.bat assembleRelease
```

### 2.3 Interpreting Local & CI Failures
- **Unit Test Failures:** Detailed HTML test reports are written to:
  `app/build/reports/tests/testDebugUnitTest/index.html`
- **Lint Findings:** Inspection report is written to:
  `app/build/reports/lint-results-debug.html`
- **Native NDK Build Logs:** Located under `app/.cxx/` and `app/build/intermediates/cxx/`.

---

## 3. Dependabot Policy & The AGP Compatibility Boundary

### 3.1 The Gradle 9.6+ Incompatibility Boundary
In September 2026, automated Dependabot PR `#33` attempted to upgrade the Gradle wrapper from `9.2.1` to `9.7.1`. The build failed across all jobs with:
```text
Plugin 'com.android.internal.application' relies on 'org.gradle.api.problems.internal.InternalProblems',
a Gradle internal API that was removed in Gradle 9.6.0.
```
**Root Cause:** The project uses Android Gradle Plugin (AGP) `8.13.2`. Gradle removed `InternalProblems` in Gradle 9.6.0. AGP 8.13.x is incompatible with Gradle >= 9.6.0.

### 3.2 Implemented Rule in `.github/dependabot.yml`
```yaml
ignore:
  - dependency-name: "org.gradle:gradle"
    versions: [">= 9.6.0"]
```
Do not upgrade Gradle to 9.6+ until AGP is upgraded to 8.14+ or 9.0+.

---

## 4. Release Engineering & Signing Key Continuity

### 4.1 Release Safety Invariants
1. **No Release on Normal Commits:** Ordinary pushes to `main` **never** publish releases.
2. **Strict Tagging:** Tags must follow `vX.Y.Z` or `vX.Y.Z-suffix` (e.g., `v1.4.9-beta.1`).
3. **Commit Verification:** The release workflow verifies that the release tag points directly to the checked-out commit.
4. **Pre-Release Validation:** Unit tests and lint must pass before APK assembly commences.
5. **SHA-256 Generation:** Every release automatically generates `release-checksums.sha256`.

### 4.2 Keystore Security & Migration Strategy

#### The Legacy Problem (`testkey_untrusted.jks`)
Historically, the repository signed both debug and release builds with a public test key (`app/testkey_untrusted.jks`, inherited from Termux):
- **Risk:** Anyone can build an APK signed with this key.
- **Continuity Constraint:** Existing users who installed APKs signed with `testkey_untrusted.jks` cannot update to an APK signed with a new private key unless an uninstallation occurs or an Android `apksigner` key rotation lineage (`--lineage`) is configured.

#### Implemented Solution
[`app/build.gradle`](../app/build.gradle) supports private production signing via environment variables while preserving local fallback:
```groovy
def releaseKeyStorePath = System.getenv("FISHBOWL_RELEASE_KEYSTORE_PATH")
if (releaseKeyStorePath && file(releaseKeyStorePath).exists()) {
    storeFile file(releaseKeyStorePath)
    keyAlias System.getenv("FISHBOWL_RELEASE_KEY_ALIAS")
    storePassword System.getenv("FISHBOWL_RELEASE_STORE_PASSWORD")
    keyPassword System.getenv("FISHBOWL_RELEASE_KEY_PASSWORD")
} else {
    // Graceful fallback for local development and CI validation
    storeFile file('testkey_untrusted.jks')
    ...
}
```

#### GitHub Actions Secrets Setup for Production Signing
To enable production signing in GitHub Releases:
1. Base64-encode your official release keystore:
   ```bash
   base64 -w 0 fishbowl_release.keystore > keystore_base64.txt
   ```
2. In GitHub (`Settings → Secrets and variables → Actions`), add:
   - `FISHBOWL_RELEASE_KEYSTORE_BASE64`: Contents of `keystore_base64.txt`
   - `FISHBOWL_RELEASE_KEY_ALIAS`: Alias name in the keystore
   - `FISHBOWL_RELEASE_STORE_PASSWORD`: Keystore password
   - `FISHBOWL_RELEASE_KEY_PASSWORD`: Key password

---

## 5. Checksum Verification Procedure

Users and downstream distributors can verify downloaded binaries against official release checksums:

```bash
# Verify all release files against the checksum manifest
sha256sum -c release-checksums.sha256

# Or manual verification of a single APK:
sha256sum Fishbowl-v1.4.9-beta.1-release-arm64-v8a.apk
```

---

## 6. Recommended Repository Security Settings (GitHub UI)

To enforce the hardened pipeline, configure the following settings in the GitHub repository:

### 6.1 Branch Ruleset for `main`
Navigate to **Settings → Rules → Rulesets → New ruleset**:
- **Ruleset Name:** `Main Branch Protection`
- **Enforcement Status:** `Active`
- **Target Branches:** `Include default branch (main)`
- **Rules:**
  - [x] **Restrict deletions**
  - [x] **Block force pushes**
  - [x] **Require a pull request before merging:**
    - Required approvals: `1`
    - Dismiss stale pull request approvals when new commits are pushed: `Checked`
    - Require review from Code Owners: `Optional`
  - [x] **Require status checks to pass before merging:**
    - Require branches to be up to date before merging: `Checked`
    - Required checks:
      - `Build, Test & Validate` (from `Build Bot`)
      - `Android Lint` (from `Linter Bot`)
      - `CodeQL Security Scan (Java/Kotlin)`

### 6.2 Security & Code Analysis
Navigate to **Settings → Code security and analysis**:
- [x] **Dependency graph:** `Enabled`
- [x] **Dependabot alerts:** `Enabled`
- [x] **Dependabot security updates:** `Enabled`
- [x] **Secret scanning:** `Enabled`
- [x] **Push protection:** `Enabled` (blocks commits containing credentials before push)
