# OwlFM+ (File Manager) — Gemini & Agent Guidelines

Welcome to the **OwlFM+ (File Manager)** project! This document outlines key architecture details, rules, user preferences, memory from past sessions, and standard operational workflows.

---

## 1. Project Overview & Architecture

OwlFM+ is a feature-rich, high-performance Android file manager built with modern Android development best practices:
- **UI Framework**: Jetpack Compose (Material 3).
- **Architecture**: Clean Architecture with unidirectional data flow (MVI / MVVM).
  - `presentation/`: Compose screens, ViewModels, UI state models, and reusable components (`browser`, `categories`, `cloud`, `analyzer`, `components`, etc.).
  - `domain/`: Pure Kotlin models, repository interfaces, and use cases (`FileItem`, `CloudAccount`, `ImageDimensions`, etc.).
  - `data/`: Local storage (`LocalFileScanner`, `FileOperationsHelper`), caching (`FolderCacheManager`), and remote cloud API clients (`GoogleDriveApiClient`, `DropboxApiClient`, `MegaApiClient`, `TeraBoxApiClient`).
- **Dependency Injection**: Dagger Hilt.
- **Asynchrony**: Kotlin Coroutines & Flows with bounded concurrency (e.g., `Semaphore` for batch transfers).
- **Minification**: R8 minification enabled in Release builds with strict ProGuard keep rules in `app/proguard-rules.pro`.

---

## 2. Core Rules & Constraints (Memory & Preferences)

### 2.1. Strict English UI Policy
- **All user-facing UI text MUST be in English**: This includes screen titles, button labels, dropdown menus, dialogs, warnings, tooltips, error messages, and toast notifications.
- Vietnamese is used exclusively for conversational interactions with the user in chat.

### 2.2. Direct In-Place File Operations
- **No redundant subfolders**: Operations such as Image Resizer, extraction, or file modifications must perform direct in-place replacements or clear prompt-based overwrites instead of creating intermediate subfolders (e.g., no `Resized/` subfolder).
- **Atomic updates & cache invalidation**: Whenever a file is modified or overwritten (locally or on Cloud), immediately update the cache (`FolderCacheManager`, `CloudDownloadCache`, Coil memory/disk cache) and refresh the list so changes reflect without requiring a restart or manual double-refresh.

### 2.3. Original Timestamp & EXIF Preservation (Cloud & Resizer)
- When uploading, resizing, or moving images/files on Cloud storage (Google Drive, Dropbox, MEGA, TeraBox), **preserve original creation/modification timestamps** (`lastModified`) and all EXIF tags (Camera, GPS, DateTime).
- Cloud API clients must send preserved timestamps to remote servers:
  - **MEGA**: Include `"ts": localFile.lastModified() / 1000` in the `'p'` node creation command, and patch `nodeTreeCache`.
  - **Google Drive**: Set `DriveFile().modifiedTime = DateTime(localFile.lastModified())`.
  - **Dropbox**: Supply `.withClientModified(Date(localFile.lastModified()))` to `UploadBuilder` and `CommitInfo.Builder`, and prioritize `clientModified.time`.
  - **TeraBox**: Maintain `uploadedItem.lastModified = localFile.lastModified()`.

### 2.4. Performance & Fast Navigation First
- **Cloud tree caching**: Avoid full-account recursive re-fetches after single-file operations. Always use localized tree cache patching (`patchNodeCacheAfterFileUpload`, `patchTreeAfterUpload`).
- **Media categories (Images, Videos, Audio, Documents)**:
  - Must load instantly with high TTL caching without flashing empty state icons.
  - Display direct media items or flattened media folders; do not expose internal storage root or nested empty directories.
  - Maintain list scroll positions when returning from media viewer screens.

### 2.5. Foldable & Dual-Pane Drag & Drop
- Dual-pane layout activates only on unfolded foldable screens or large displays.
- Drag-and-drop between panes supports a Copy/Move selection dialog.
- Dropping onto cloud root or top-level items (Main Storage, Downloads, Recycle Bin) is supported; excluded for Media categories.

### 2.6. Model Usage Guidelines
- Standard models (`inherit`, `flash`, or `pro`) can be used as appropriate. There is **no restriction** requiring routine Git or APK build operations to use a specific model (the historical Haiku-only rule was explicitly revoked by the user).

---

## 3. Standard Operational Commands

### Unit Tests
```powershell
./gradlew testDebugUnitTest
```

### Build APK
- **Debug Build**:
  ```powershell
  ./gradlew assembleDebug
  ```
- **Release Build** (R8 minified):
  ```powershell
  ./gradlew assembleRelease
  ```
  Output: `app/build/outputs/apk/release/app-release.apk`

### ADB Device Operations
- **Install to connected device/emulator**:
  ```powershell
  adb install -r app/build/outputs/apk/release/app-release.apk
  ```
- **Launch app**:
  ```powershell
  adb shell monkey -p com.antigravity.filemanager -c android.intent.category.LAUNCHER 1
  ```
- **Check application logs**:
  ```powershell
  adb logcat -v time com.antigravity.filemanager:D *:S
  ```

---

## 4. Customization Links
- Detailed rule definitions: [`.agent/rules/`](.agent/rules/)
- Workflows & skills: [`.agent/skills/`](.agent/skills/)
