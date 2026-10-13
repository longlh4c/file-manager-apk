# Build, Release & Execution Rules

## 1. R8 Minification & Release Verification
- The release build variant has R8 minification enabled (`isMinifyEnabled = true`).
- Whenever reflection, serialization, or new native/cloud libraries are introduced (e.g. Google Drive GenericJson, Dropbox SDK, Apache FtpServer/MINA, NanoHTTPD, Junrar):
  - Always verify that `./gradlew assembleRelease` compiles and obfuscates without missing class/interface warnings or runtime crashes.
  - Keep necessary reflection rules in `app/proguard-rules.pro`.

## 2. Unrestricted Model Usage
- **No Model Restriction for Git / Build**:
  - Previously, routine Git commands (pull, merge, push) and APK builds were restricted to the Haiku model.
  - The user has **explicitly revoked** this restriction.
  - AI agents can use normal/inherited models or lightweight models as best fits the context, without being forced to switch to Haiku.

## 3. Safe Git Branching Protocol
- Develop distinct features on dedicated branches (e.g., `feature/<feature-name>`).
- Always run `./gradlew testDebugUnitTest` and compile `./gradlew assembleRelease` before merging into `main`.
- Clean up merged branches after successful merge.
