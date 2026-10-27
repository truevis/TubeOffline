# TubeOffline

A tiny personal Android app: **share a YouTube video to it and it downloads the smallest version that still has picture *and* sound**, saved to `Movies/TubeOffline` on your phone so you can watch it offline (e.g. on a plane).

Built for a Pixel 8 (Android 14 – 17), sideloaded with `adb`. Not for the Play Store.

## What it does

- Shows up as **“Download offline”** in the Android share sheet for any shared text/link.
- Understands `youtube.com/watch?v=…`, `m.youtube.com/…`, `youtu.be/…`, `youtube.com/shorts/…`, `/live/…`, `/embed/…`, including shared text like `Video title https://youtu.be/xyz?si=…`. Playlist parameters are ignored (only the one video is downloaded).
- The share screen closes immediately with a toast; the download runs in a **foreground service** with a progress notification (with a *Cancel* button). Multiple shares are queued and downloaded one after another.
- Format choice (yt-dlp format selector):
  `worstvideo[ext=mp4]+worstaudio[ext=m4a] / worstvideo+worstaudio / worst[ext=mp4] with audio+video / worst with audio+video`, merged with ffmpeg into **MP4**. In practice that's usually 144p H.264 + low-bitrate AAC (roughly 1–3 MB per minute).
- Saves via MediaStore to **`Movies/TubeOffline/<video title>.mp4`** (title sanitised for file systems). Visible to Google Photos / Files / any video player.
- Notification when finished (tap to play) or failed (with a readable reason).
- Main screen: list of downloaded videos (tap → play in an external player, *Delete*), a paste-a-link box, and **Update download engine** (updates the bundled yt-dlp from GitHub).

## How it works

Uses [youtubedl-android](https://github.com/yausername/youtubedl-android) `0.18.1` (Maven Central `io.github.junkfood02.youtubedl-android:library` + `:ffmpeg`), which bundles Python, **yt-dlp**, **ffmpeg** and **QuickJS** (the JS runtime yt-dlp now needs for YouTube). yt-dlp is the most actively maintained YouTube downloader, and the in-app updater lets you pull a new yt-dlp whenever YouTube breaks things — no rebuild needed.

| File | Purpose |
|---|---|
| `ShareActivity.kt` | Invisible share target; extracts the URL, starts the service, toasts, finishes |
| `YouTubeUrl.kt` | URL extraction / normalisation (unit-tested) |
| `DownloadService.kt` | Foreground service (`dataSync`), queue, yt-dlp call, progress notification |
| `Engine.kt` | yt-dlp init/update, format selector, human-readable errors |
| `VideoStore.kt` | MediaStore save / list / delete in `Movies/TubeOffline` |
| `MainActivity.kt` | Jetpack Compose UI |

## Build on Windows

Requirements:

1. **JDK 17** (e.g. [Eclipse Temurin 17](https://adoptium.net/temurin/releases/?version=17)) — or just use the JDK bundled with Android Studio.
2. **Android SDK** with *Android SDK Platform 37* — easiest via [Android Studio](https://developer.android.com/studio) (SDK Manager). Command-line-tools only also works.
3. Internet access on first build (Gradle downloads itself, AGP and the libraries).

Steps (PowerShell or cmd):

```bat
cd C:\GitHub\TubeOffline

:: Point Gradle at the JDK and the SDK (only needed if not already set system-wide)
set JAVA_HOME=C:\Program Files\Eclipse Adoptium\jdk-17.0.x-hotspot
::   or with Android Studio's JDK:  set JAVA_HOME=C:\Program Files\Android\Android Studio\jbr
echo sdk.dir=C\:\\Users\\%USERNAME%\\AppData\\Local\\Android\\Sdk> local.properties

gradlew.bat assembleDebug
```

(In PowerShell use `$env:JAVA_HOME="..."` instead of `set`.) Alternatively just **File → Open** the folder in Android Studio and press *Build ▸ Build APK(s)*; Android Studio creates `local.properties` for you.

**APK output:** `app\build\outputs\apk\debug\app-debug.apk` (~85 MB; it contains Python + ffmpeg for arm64).

## Install on the Pixel 8

1. On the phone: *Settings → About phone →* tap *Build number* 7× → *Settings → System → Developer options →* enable **USB debugging**.
2. Install [Android platform-tools](https://developer.android.com/tools/releases/platform-tools) (comes with Android Studio: `%LOCALAPPDATA%\Android\Sdk\platform-tools`).
3. Plug in the phone, accept the "Allow USB debugging" prompt, then:

```bat
adb devices
adb install -r app\build\outputs\apk\debug\app-debug.apk
```

`-r` reinstalls/updates while keeping your downloads and app data.

## Use it

1. Open **TubeOffline** once and allow **notifications** (and "videos" access, so it can list files from earlier installs).
2. Optional but recommended before a trip: tap **Update download engine**.
3. In the YouTube app: *Share → Download offline (TubeOffline)*. (First time you may need *More* in the share sheet; long-press to pin it.) Works from Chrome too (share the page).
4. Watch the progress notification. When done, tap the notification or open TubeOffline and tap the video. Files live in `Movies/TubeOffline`.

Tip: the first download after install takes a few extra seconds while the bundled Python/ffmpeg unpack.

## Limitations

- **Lowest quality really is low** (usually 144p). Great for size, poor for small text. To change it, edit `Engine.FORMAT_LOWEST`, e.g. `bestvideo[height<=360][ext=mp4]+bestaudio[ext=m4a]/best[height<=360]`.
- YouTube changes things often. If downloads start failing (403, "no formats", "sign in to confirm you're not a bot"), tap **Update download engine** first. Bot checks tied to your network/IP can still block downloads for a while; there's no sign-in/cookie support.
- No age-restricted, private, members-only or DRM content; live streams aren't supported in a useful way.
- Playlists: only the single shared video is downloaded.
- One download at a time (others are queued). Cancel cancels the current one and the queue.
- Android 15+ limits `dataSync` foreground services to ~6 h per day; plenty for small files, but very long queues could be cut off.
- Only built for **arm64-v8a** (Pixel 8). For an x86_64 emulator add `"x86_64"` to `abiFilters` in `app/build.gradle.kts`.
- Debug-signed APK. If you ever switch to a different signing key you must uninstall first (`adb uninstall com.tubeoffline.app`); videos in `Movies/TubeOffline` remain on the phone.
- For personal use; respect YouTube's Terms of Service and creators' rights.

## License note

youtubedl-android is GPL-3.0 and yt-dlp is Unlicense; this is a personal, non-distributed build.
