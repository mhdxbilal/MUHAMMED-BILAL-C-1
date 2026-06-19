# The Continental

**Every frame, perfectly kept.**

A native Kotlin Android media player targeting ARM64 (Snapdragon 778G+), built on Media3/ExoPlayer for zero-lag, hardware-accelerated 4K 60 FPS playback — with a God-tier integrated download manager powered by a real yt-dlp binary.

---

## Features

### Player
- Hardware-accelerated decoding for H.264, H.265 (HEVC), and AV1 via `MediaCodec`
- 4K 60 FPS playback with tuned buffer settings (50 s max, 2.5 s start threshold)
- True AMOLED `#000000` black theme — zero wasted power on OLED panels
- Gesture controls: swipe left half = volume, right half = brightness, horizontal = seek
- Double-tap left/right edge to skip ±N seconds (configurable: 5/10/15/30)
- Pinch-to-zoom anywhere on the video surface
- Lock screen button — disables all gestures and controls with one tap
- Audio track switching and subtitle track switching with language labels
- Subtitle files loadable from disk (`.srt`, `.ass`, `.vtt`)
- Playback speed control: 0.25× to 3.0× with optional "remember last speed" persistence
- Aspect ratio cycling: Fit → Stretch → Crop/Zoom
- Orientation modes: Auto-sensor, Landscape (locked), Portrait (locked), Follow system
- Picture-in-Picture (PiP) — activates automatically when you leave the app
- Wake lock / keep screen on while playing
- Bass boost + 3D virtualizer (bound directly to ExoPlayer's audio session)
- Codec error handling: descriptive overlay with Retry and Skip buttons instead of a black screen
- Playlist / queue support — the entire visible library becomes the player's queue

### Library
- Full MediaStore scan, scoped-storage compliant (Android 13+ `READ_MEDIA_VIDEO`)
- Expandable/collapsible folder groups with long-press for Hide/Remove from library
- Sort by: Date newest/oldest, Name A–Z/Z–A, File size, Duration
- Real video frame thumbnails (not placeholder icons) via `loadThumbnail` (API 29+) or `MediaMetadataRetriever`
- Search across all video titles, live-filtered as you type
- Resume progress bar on each thumbnail showing how far through the video you are
- "Open with" intent filter — visible in any file manager's share sheet for `video/*`
- Manual file picker for opening any video outside the scanned library

### Download Manager
- Real `yt-dlp` binary (via `youtubedl-android`) — supports 1000+ sites
- `ffmpeg` for muxing and format conversion
- `aria2c` for multi-connection accelerated downloads
- Quality presets: 4K (2160p), Full HD (1080p), HD (720p), SD (480p), Best available, Audio-only MP3
- Live progress bar with percentage and ETA
- Foreground service — downloads continue with a notification even when the app is backgrounded
- Scoped-storage-safe publishing: finished files land in Movies/ or Downloads/ via MediaStore (no permissions needed on API 29+), or in a custom folder you pick with SAF
- Queue multiple downloads simultaneously (configurable: 1–3 concurrent)
- Wi-Fi-only mode, optional subtitle embedding, configurable default quality
- Download history survives app restarts; completed files are playable directly from the queue

### Settings (fully persisted across sessions)
- Every preference listed above stored in default SharedPreferences — nothing resets on relaunch
- Dedicated Settings screen with categories for Playback, Gestures, Subtitles/Audio, Library/Display, and Downloads
- Manage hidden folders (bulk unhide dialog)
- SAF folder picker for custom download destination

---

## Getting the APK

### Option A — GitHub Actions (recommended, zero setup)

1. Push this project to a GitHub repository (public or private).
2. Go to **Actions** → **Debug APK** → **Run workflow**.
3. Wait ~5 minutes. When the run turns green, click it and scroll to **Artifacts**.
4. Download **TheContinental-debug.zip**, unzip it, and sideload the `.apk`:
   ```
   adb install -r TheContinental-debug.apk
   ```
   Or copy to `/sdcard/` and install through your file manager.

The workflow file is at `.github/workflows/build_debug_apk.yml` — it pins JDK 17, Gradle 8.7, and AGP 8.5.2 for reproducible builds.

### Option B — Build locally in Termux

> Requires: Android SDK with build-tools installed. The SDK does **not** need to be inside Termux — just set `ANDROID_HOME` to wherever it lives.

```bash
# 1. Clone or copy the project
cd ~
git clone https://github.com/YOUR_USERNAME/TheContinental.git
cd TheContinental

# 2. Point Gradle at your SDK (add to ~/.bashrc for persistence)
export ANDROID_HOME=/path/to/your/android-sdk
export PATH="$ANDROID_HOME/platform-tools:$ANDROID_HOME/tools:$PATH"

# 3. Build
chmod +x gradlew
./gradlew assembleDebug

# 4. APK is here
ls app/build/outputs/apk/debug/
```

If Gradle runs out of memory on your device:
```bash
export GRADLE_OPTS="-Xmx1g -Dorg.gradle.daemon=false"
./gradlew assembleDebug
```

---

## Dependency versions (update if a newer stable release is available)

| Dependency | Version |
|---|---|
| Gradle | 8.7 |
| Android Gradle Plugin | 8.5.2 |
| Kotlin | 1.9.24 |
| compileSdk / targetSdk | 34 |
| minSdk | 26 (Android 8.0) |
| Media3 / ExoPlayer | 1.4.1 |
| youtubedl-android | 0.18.1 |

To update `youtubedl-android`, check https://github.com/junkfood02/youtubedl-android/releases and change the version in `app/build.gradle`.

---

## Project structure

```
app/src/main/kotlin/com/continental/player/
├── ContinentalApp.kt          Application class — boots yt-dlp engine on start
├── MainActivity.kt            Library screen
├── PlayerActivity.kt          Full-featured player
├── SettingsActivity.kt        Preference screen
├── base/
│   └── BaseActivity.kt        Immersive mode + settings access for all screens
├── data/
│   ├── Enums.kt               SortOrder, OrientationMode, ResizeMode
│   ├── ResumeStore.kt         Per-video position persistence
│   ├── SettingsRepository.kt  Single source of truth for every user preference
│   └── VideoItem.kt           Data models (VideoItem, LibraryRow)
├── download/
│   ├── DownloadActivity.kt    Download manager UI
│   ├── DownloadAdapter.kt     RecyclerView adapter for the queue
│   ├── DownloadModels.kt      DownloadTask, DownloadQuality, DownloadStatus
│   ├── DownloadRepository.kt  In-memory + persisted download queue
│   └── DownloadService.kt     Foreground service running yt-dlp
├── library/
│   ├── LibraryAdapter.kt      Expandable folder+video RecyclerView adapter
│   └── VideoRepository.kt     MediaStore scan, sort, filter, flatten
├── player/
│   ├── AudioEffectsManager.kt BassBoost + Virtualizer bound to ExoPlayer
│   ├── PlayerEngine.kt        ExoPlayer factory — hardware codec preference
│   ├── PlayerGestureHelper.kt Volume / brightness / seek / double-tap / pinch
│   └── TrackSelectionHelper.kt Audio and subtitle track switching via Media3
└── util/
    ├── Extensions.kt           View helpers, coerce()
    ├── FormatUtils.kt          Duration, file size, speed, ETA formatting
    ├── MediaStoreFileUtils.kt  Scoped-storage-safe file publishing
    ├── PermissionsHelper.kt    Runtime permission helpers per API level
    └── ThumbnailLoader.kt      Real video frame thumbnail with LRU cache
```
