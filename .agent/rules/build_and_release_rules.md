# Build, Release & Execution Rules

## 1. R8 Minification & Release Verification
- The release build variant has R8 minification enabled (`isMinifyEnabled = true`).
- Whenever reflection, serialization, or new native/cloud libraries are introduced (e.g. Google Drive GenericJson, Dropbox SDK, Apache FtpServer/MINA, NanoHTTPD, Junrar):
  - Always verify that `./gradlew assembleRelease` compiles and obfuscates without missing class/interface warnings or runtime crashes.
  - Keep necessary reflection rules in `app/proguard-rules.pro`.

## 2. Model Usage Freedom
- **No Artificial Model Restrictions**:
  - Routine Git commands (pull, merge, push) and APK builds do not require any specific model.
  - AI agents can use normal/inherited models or lightweight models as best fits the task.

## 3. Safe Git Branching Protocol
- Develop distinct features on dedicated branches (e.g., `feature/<feature-name>`).
- Always run `./gradlew testDebugUnitTest` and compile `./gradlew assembleRelease` before merging into `main`.
- Clean up merged branches after successful merge.

## 4. Explicit Git Commit & Push Rule
- **Never commit or push automatically**: Do not execute `git commit` or `git push` unless the user explicitly requests it (e.g., "commit", "commit push", "push lên git").
- Keep changes in the working tree/local files, run tests/builds, report results to the user, and wait for the user's explicit command before committing or pushing.
