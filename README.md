# TubeOffline

Personal Android app that downloads a YouTube video for offline playback. Share a link (or paste one) and it saves the smallest file that still has picture and sound to `Movies/TubeOffline`.

It is meant to be built from source and sideloaded with `adb`. It is not on the Play Store. The debug build targets **arm64** phones (developed on a Pixel 8). Minimum SDK is 29 (Android 10).

Repository: https://github.com/truevis/TubeOffline

## Features

- Share-sheet target **Download offline** for plain text, plus a paste box on the main screen. Accepts `youtube.com/watch`, `m.youtube.com`, `youtu.be`, `/shorts/`, `/live/`, and `/embed/` links, including a title pasted in front of the URL. Playlist parameters are ignored; only that one video is downloaded.
- Downloads run in a foreground service with a progress notification and a Cancel action. Shares are queued and run one at a time.
- Format: smallest video that still has audio, preferring an English audio track when YouTube labels a language, merged to MP4 with ffmpeg (see `Engine.FORMAT_LOWEST`). In practice that is often 144p.
- Files go to `Movies/TubeOffline/<title>.mp4` through MediaStore, so other apps can see them. The YouTube id is stored with the file for the thumbnail and the original link.
- If the bundled yt-dlp is missing or older than about 60 days, the download service updates it from GitHub before starting.
- Library: free space, search, thumbnail, title (dimmed after you finish a video), relative upload age when it was recorded, share of the original `watch?v=` link, and delete. Delete removes the file; it is not sent to trash. Videos saved before upload time was recorded have no age label.
- Player (Media3): resume, 10s back and 15s forward, **previous/next** to move through downloaded videos (same order as the library list, newest first; no wrap at the ends), playback speed from 0.5× to 3×, picture-in-picture when you leave while it is playing, and background audio. **Dismiss** leaves playback running; **Stop** ends it. A **Now playing** card returns you to the current video. Skip controls are in the top bar and on the Media3 transport overlay; the media notification can skip as well.
- **Autoplay** is off until you turn it on. When on, the next library item plays after the current one ends, skipping videos already marked finished (manual prev/next always plays the adjacent file, even if you already finished it).

## Build

Uses [youtubedl-android](https://github.com/yausername/youtubedl-android) `0.18.1` (`io.github.junkfood02.youtubedl-android:library` and `:ffmpeg`), which bundles Python, yt-dlp, ffmpeg, and QuickJS. Playback uses Media3 ExoPlayer and a media session.

Requirements:

1. **JDK 17** (for example [Eclipse Temurin 17](https://adoptium.net/temurin/releases/?version=17), or the JDK bundled with Android Studio).
2. **Android SDK** with platform 37. [Android Studio](https://developer.android.com/studio) is the straightforward way to get it.
3. Internet on the first build so Gradle can download the wrapper, the Android Gradle Plugin, and dependencies.

```bat
git clone https://github.com/truevis/TubeOffline.git
cd TubeOffline

set JAVA_HOME=C:\Program Files\Eclipse Adoptium\jdk-17.0.x-hotspot
echo sdk.dir=C\:\\Users\\%USERNAME%\\AppData\\Local\\Android\\Sdk> local.properties

gradlew.bat assembleDebug
```

In PowerShell, set the JDK with `$env:JAVA_HOME="..."`. Android Studio can also open the folder and build the APK; it writes `local.properties` for you.

Debug APK: `app\build\outputs\apk\debug\app-debug.apk` (large, because it contains Python and ffmpeg for arm64).

### Build and install (Windows)

USB debugging on, one authorized phone connected:

```powershell
.\install.ps1             # build the debug APK and adb install -r
.\install.ps1 -SkipBuild  # reinstall the last APK
```

`install.bat` does the same. The script looks for the SDK via `ANDROID_HOME` or `%LOCALAPPDATA%\Android\Sdk`, writes `local.properties` if needed, and uses Android Studio’s JDK when `java` is not on `PATH`.

Manual install:

```bat
adb devices
adb install -r app\build\outputs\apk\debug\app-debug.apk
```

`-r` replaces the app and keeps its data. The APK is debug-signed. A different signing key means uninstall first (`adb uninstall com.tubeoffline.app`). Files already in `Movies/TubeOffline` stay on the phone.

For an x86_64 emulator, add `"x86_64"` to `abiFilters` in `app/build.gradle.kts`.

## Use

1. Open TubeOffline once and allow notifications (and video access so it can list files from an earlier install).
2. In YouTube or Chrome: Share → **Download offline**. The first time it may be under More. Or paste a link on the main screen.
3. When the notification finishes, tap it or open the library. The first download after install is slower while Python and ffmpeg unpack.

## Limitations

- Quality is intentionally low (often 144p). Change `Engine.FORMAT_LOWEST` if you want a higher cap.
- YouTube breaks downloaders often. The app refreshes yt-dlp when its copy is older than about 60 days. There is no cookie or sign-in support, so network-level bot checks can still block a download.
- No age-restricted, private, members-only, or DRM video. Live URLs are not handled in a useful way. Playlists download only the single shared video.
- One download at a time. Cancel stops the current item and the rest of the queue.
- Android’s limit on `dataSync` foreground services can cut off a very long queue.
- arm64-v8a only, unless you change `abiFilters`.

YouTube’s terms and the uploader’s rights still apply.

## License

TubeOffline is released under the [WTFPL](LICENSE) (Do What The Fuck You Want To Public License).

youtubedl-android is GPL-3.0. yt-dlp is Unlicense. Those licenses apply to those components, not as a license for this app.
