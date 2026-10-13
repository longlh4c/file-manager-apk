# OwlFM+ Agent Workspace & Context Map

Welcome, AI Agent! This file acts as the primary registry and single source of truth for agent rules, memory, and skills in the **OwlFM+ (File Manager)** workspace.

---

## 1. Project Directory Map

```text
file-manager-apk/
├── GEMINI.md                          <-- Comprehensive guidelines, preferences, and architecture
├── AGENTS.md                          <-- Workspace context map and registry
├── .agent/                            <-- AI Agent customizations
│   ├── rules/                         <-- Specific rules and guidelines
│   │   ├── ui_language_rules.md       <-- Strict English UI requirement
│   │   ├── cloud_and_file_rules.md    <-- In-place overwrite, timestamp preservation & caching rules
│   │   └── build_and_release_rules.md <-- ProGuard, R8 minification, and model freedom
│   └── skills/                        <-- Operational skills & procedures
│       ├── apk-build-and-deploy/      <-- Build, lint, install & ADB testing workflows
│       │   └── SKILL.md
│       └── cloud-storage-integration/ <-- Cloud API architecture, cache patching & sync workflows
│           └── SKILL.md
├── app/                               <-- Android application module
│   ├── src/main/java/com/antigravity/filemanager/
│   │   ├── data/                      <-- Local scanner, caching, remote cloud clients
│   │   ├── domain/                    <-- Models, repositories, use cases
│   │   ├── presentation/              <-- Compose screens & ViewModels
│   │   └── utils/                     <-- Engine utilities (ImageResizer, Cache, etc.)
│   └── proguard-rules.pro             <-- Release minification keep rules
└── build.gradle.kts                   <-- Root build configuration
```

---

## 2. Rules
- **[UI Language Rules](.agent/rules/ui_language_rules.md)**: Strict requirement that all user-facing text, dialogs, buttons, and toasts must be in English.
- **[Cloud & File Rules](.agent/rules/cloud_and_file_rules.md)**: Direct in-place file modifications without redundant subfolders; original EXIF & timestamp preservation; smart localized cache patching.
- **[Build & Release Rules](.agent/rules/build_and_release_rules.md)**: R8 release verification, ProGuard rule maintenance, and unrestricted model usage for routine tasks.

---

## 3. Skills
- **[APK Build and Deploy](.agent/skills/apk-build-and-deploy/SKILL.md)**: Runbook for building Debug/Release APKs, verifying unit tests, installing on target devices with ADB, and monitoring Logcat.
- **[Cloud Storage Integration](.agent/skills/cloud-storage-integration/SKILL.md)**: Guide for managing and debugging Google Drive, Dropbox, MEGA, and TeraBox API clients, tree caches, and upload mechanisms.
