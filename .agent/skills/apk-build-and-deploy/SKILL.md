---
name: apk-build-and-deploy
description: >-
  Use this skill to compile, verify, and package Android Debug and Release APKs, run unit tests, install APKs onto connected devices via ADB, and debug runtime behavior via Logcat for OwlFM+.
---

# APK Build, Test & Deploy Skill

This runbook guides agents through building, verifying, installing, and debugging the OwlFM+ Android application.

## 1. Running Unit Tests

Before creating release packages or merging branches, always ensure all unit tests pass:
```powershell
./gradlew testDebugUnitTest
```
- Tests cover local file operations (`FileOperationsHelperTest`), cloud decrypters (`MegaDecryptingDataSourceTest`, `TeraBoxApiClientTest`), and network streams.

## 2. Compiling the APK

### Debug Build
Fast build for local verification without R8 obfuscation:
```powershell
./gradlew assembleDebug
```
Output: `app/build/outputs/apk/debug/app-debug.apk`

### Release Build
Full production build with R8 minification, resource shrinking, and ProGuard rules:
```powershell
./gradlew assembleRelease
```
Output: `app/build/outputs/apk/release/app-release.apk`

> [!IMPORTANT]
> If `assembleRelease` fails with R8 class missing errors, check `app/proguard-rules.pro` to add appropriate `-keep` or `-dontwarn` rules for third-party libraries.

## 3. Deploying to Android Device via ADB

### Check Connected Devices
```powershell
adb devices
```

### Install APK
Replace existing installation preserving data (`-r`):
```powershell
adb install -r app/build/outputs/apk/release/app-release.apk
```
Or for debug build:
```powershell
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

### Launch Application
Launch the main launcher activity:
```powershell
adb shell monkey -p com.antigravity.filemanager -c android.intent.category.LAUNCHER 1
```

### Monitor Runtime Logs (Logcat)
Filter logs specifically for the file manager:
```powershell
adb logcat -v time com.antigravity.filemanager:D *:S
```
Or search for specific cloud/file errors:
```powershell
adb logcat -d | Select-String "MegaApiClient|GoogleDrive|Dropbox|TeraBox|FileOps"
```
