# Android-Screen-Recorder

A reliable Android screen recorder that captures **video + audio** and **screenshots**, with a floating **quick-access bubble** overlay — similar to AZ Screen Recorder but designed to be more dependable.

Built by [jnetai.com](https://jnetai.com).

## Features

- 🎥 **Record screen + audio** — MP4 (H.264 video + AAC audio)
- 🫧 **Floating quick-access bubble** — drag to move, tap to expand
- 🎬 **Bubble buttons**: Record / Stop / Screenshot / Settings / Close
- 📸 **Screenshot capture** — toggle show/hide in settings
- 📁 **Dedicated storage** — defaults to `DCIM/ScreenRecorder/`
  - Recordings → `DCIM/ScreenRecorder/Recordings/`
  - Screenshots → `DCIM/ScreenRecorder/Screenshots/`
- 📱 **Works on old AND new devices** — Samsung S8 (Android 7+) up to Pixel 9 (Android 15)
- ⚙️ **Settings**: save location, resolution (720p/1080p/1440p/screen), FPS, record-audio toggle, screenshot button toggle, stream recording toggle
- ℹ️ **About section**: made by jnetai.com, check for update, share app (links to latest GitHub release)
- 🔄 **GitHub Actions CI** — builds the APK and creates a release automatically

## ⚠️ DRM note

**DRM-protected apps (Netflix, Prime Video, Disney+, HBO Max) cannot be recorded.** Android's Widevine DRM blanks screen-capture frames at the hardware level — no recorder can bypass it, and circumvention is illegal. This app records **non-DRM** streaming (Twitch, YouTube web, Vimeo, self-hosted, your own content, games, calls) normally. See `Notes.txt` for full details.

## How to Use

1. **Install** the APK and open the app.
2. **Grant permissions**:
   - **Overlay** — required for the floating bubble (tap "Enable overlay")
   - **Microphone** — required to record sound
   - **Screen capture** — asked the first time you start a recording
3. **Show the bubble** — tap "Show quick-access bubble".
4. **Record** — tap the bubble to expand it, then tap the red record button. Stop with the stop button.
5. **Screenshot** — tap the camera button in the bubble.
6. **Find your files** under `DCIM/ScreenRecorder/`.

## Build

### Via GitHub Actions (recommended)

Push to `main` — CI builds the APK automatically. Manual trigger via Actions → "Build Screen Recorder APK" → Run workflow.

For a signed release, add these repo secrets:
- `KEYSTORE_BASE64` — base64 of your `.jks` keystore
- `KEYSTORE_PASSWORD` — store + key password
- `KEY_ALIAS` — key alias

### Locally

```bash
git clone https://github.com/jnetai-clawbot/Android-Screen-Recorder.git
cd Android-Screen-Recorder
./gradlew assembleDebug
```

APK output: `app/build/outputs/apk/debug/app-debug.apk`

## Requirements

- Android 7.0+ (API 24+)
- ~4MB install size

## About

Made by [J~Net](https://jnetai.com) — part of the jnetai.com app suite.
