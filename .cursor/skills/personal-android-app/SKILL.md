---
name: personal-android-app
description: Build a new personal Android app (Kotlin + Compose) for the owner's Pixel, sideloaded from a Windows PC with adb, not published to the Play Store. Use when asked to create or scaffold another Android app like TubeOffline, or to apply this repo's patterns to a new app.
---

# Personal Android app, sideloaded

This repo (TubeOffline) is the reference implementation. When you start a new app, copy its structure and conventions, not its YouTube-specific code. Put the new app in its own folder under `C:\GitHub\<AppName>`, with its own git repo.

## 1. Scope
- Pin down the one core action: how the app starts (a share-sheet target, the launcher screen, a pasted link) and where its output goes. Make sensible assumptions instead of asking a lot of questions.
- Plan from the start for what tends to get added next, so the first version doesn't need reworking:
  - an in-app viewer or player rather than handing off to another app
  - search over the saved list
  - thumbnails
  - share and delete icons on each row
  - resuming where the user left off
- Prefer automatic behavior over buttons. For example, refresh a stale dependency on its own instead of showing an "Update" button. See the yt-dlp staleness check in `Engine.kt`.

## 2. Stack defaults
Check the web for current stable versions; don't copy versions from memory.
- Kotlin, the Gradle Kotlin DSL, a version catalog in `gradle/libs.versions.toml`, and a committed Gradle wrapper. Use the latest stable AGP.
- compileSdk and targetSdk set to the current stable release, with minSdk 29 or higher.
- When native libraries make the APK large, build `arm64-v8a` only. Note that this means an x86 emulator can't run it.
- Jetpack Compose with Material 3, plus `material-icons-core`.
- For media, use Media3: `exoplayer`, `ui` and `session`, with a `MediaSessionService` so playback continues with the screen off, and picture-in-picture. Create the `default_channel_id` notification channel in the Application class. See `PlayerActivity.kt`, `PlaybackService.kt`, `PlaybackPositionStore.kt` and `TubeOfflineApp.kt`.
- For long-running work, use a foreground service with the correct `foregroundServiceType`. Start it while the share activity is still in front, because Android 12+ blocks starting it from the background. Give it a progress notification with Cancel, and a notification for success or failure that shows a plain-language error. Tapping that notification opens the in-app screen. See `DownloadService.kt` and `Notifications.kt`.
- A share target is a transparent `ShareActivity` for `ACTION_SEND` with `text/plain`. It pulls the URL out of shared text that may also contain a title, enqueues the work, shows a short toast and finishes immediately. See `ShareActivity.kt` and `YouTubeUrl.kt`.
- Save files through MediaStore into a public folder such as `Movies/<AppName>`, using `IS_PENDING` while writing. Keep small metadata, such as a prefixed id, in `DESCRIPTION`. See `VideoStore.kt`.
- Request `POST_NOTIFICATIONS` and media-read permission the first time the app opens.
- Write unit tests for the pure logic, such as URL parsing, error mapping and version checks. See `app/src/test`.

## 3. Repo files
- Copy this repo's `.gitignore`. It ignores build outputs, `local.properties`, jars except the wrapper jar, APKs and AABs, signing keys and logs. It also ignores `.cursor/` except `.cursor/skills/`.
- Copy `install.ps1` and `install.bat`, saved with CRLF line endings, and update the package id in the uninstall hint. The script finds the SDK, writes `local.properties`, falls back to Android Studio's `jbr` JDK, builds the app (or skips the build with `-SkipBuild`), checks that exactly one authorized device is connected, and then runs `adb install -r`.
- Write a README covering what the app does, a table of each file and its purpose, the Windows build steps, phone setup (Developer options and USB debugging), how to use the app, and any limitations, stated honestly.
- Use a `main` branch with a clear first commit.

## 4. Done when
- `gradlew.bat assembleDebug` and `gradlew.bat test` both pass.
- `.\install.ps1` installs the app on the connected phone.
- No build output or `local.properties` is committed.
- You report what hasn't yet been tested on a real phone.
