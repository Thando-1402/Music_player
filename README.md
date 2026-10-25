<div align="center">
  <img src="https://raw.githubusercontent.com/Thando-1402/Music_player/main/app/src/main/res/mipmap-xxxhdpi/ic_launcher.webp" alt="Auralis app icon" width="120" />
  <h1>Auralis Music Player</h1>
  <p><strong>Your music library, your device, your listening experience.</strong></p>
  <p>A native Android music player for discovering and playing audio stored on your phone or removable storage.</p>
  <p>
    <img src="https://img.shields.io/badge/Platform-Android-3DDC84?style=flat&logo=android&logoColor=white" alt="Platform: Android" />
    <img src="https://img.shields.io/badge/Language-Java-orange?style=flat&logo=openjdk&logoColor=white" alt="Language: Java" />
    <img src="https://img.shields.io/badge/Playback-AndroidX%20Media3-4285F4?style=flat" alt="Playback: AndroidX Media3" />
    <img src="https://img.shields.io/badge/Interface-Material%20Design%203-00B0FF?style=flat" alt="Interface: Material Design 3" />
  </p>
</div>

---

## Overview

**Auralis** is a native Android music player designed around a practical local music library. It scans audio available on the device, lets listeners browse tracks and folders across internal and MicroSD storage, and provides a dedicated **Now Playing** screen with hardware audio route detection and 60fps controls.

Playback is handled by AndroidX Media3 and ExoPlayer through a dedicated media playback service. Music continues playing when the app is in the background, with system media controls available through the playback notification.

---

## Features

### Music Library & Navigation
- Scan the device's audio library across phone and removable MicroSD storage using Android's `MediaStore` APIs (`VOLUME_NAME` aware).
- Browse the music library, filter by category chips, or search for tracks in real time with 60fps animations.
- Trigger **✨ AI Smart Mix** curation or 1-tap **✨ AI Auto-Clean Title & Artist** tag cleanup directly from the library.
- Use a persistent mini-player with track information and playback controls across primary screens.

### Now Playing Experience
- Full-screen Material Design 3 **True Dark (`#121212`)** layout with a zero-overdraw flat `ConstraintLayout` hierarchy optimized for low-RAM devices.
- 1000-step seekbar scrubber with live elapsed and remaining timecodes.
- Shuffle toggle, 3-state Repeat mode cycling, and persistent local Favourites.
- Live hardware audio route monitor detecting Internal Hi-Fi DAC/Speaker, 3.5mm Wired Headphones, External USB DACs, and Bluetooth A2DP/LDAC streams.

### Folder Browsing & Dual-Storage Meters
- Independent storage-usage progress meters for **Phone Internal Storage** and **MicroSD Card** storage.
- Browse audio folders badged by storage location (`[Phone]` / `[MicroSD]`), sort by Name or Size, and long-press any directory to hide it from library scans.

### Smart Library Filters
- Automatically excludes WhatsApp voice notes (`AUD-*-WA*` / `PTT-*-WA*`), short notification clips (`< 30s`), hidden directories (`.*`), and `.nomedia` paths.
- Manage filter rules and restore hidden directories in the **Folder Scan & Exclusions** screen.

### Track Details, Artwork & AI Tag Assistant
- Override displayed track Title, Artist, Album, Track #, Year/Genre, and synchronized `.lrc` lyrics without mutating original audio files.
- Extract embedded ID3 cover art, pick custom gallery covers with `300x300` compression, or clean messy filenames via the Dual-AI Failover assistant (OpenRouter free tier + offline regex fallback).

### Equalizer & Diagnostics
- 5-band hardware `Equalizer`, `BassBoost`, and `Virtualizer` controls bound to ExoPlayer's audio session.
- Built-in RAM heap, APK size, and local crash telemetry.

---

## Technology Stack

| Area | Technology |
|---|---|
| Platform | Native Android (Min SDK 24 / Target SDK 37) |
| Language | Java |
| UI | XML layouts, AndroidX, Material Design 3, flat `ConstraintLayout` |
| Audio Playback | AndroidX Media3 (`ExoPlayer`, `MediaSessionService`, `MediaController`) |
| Audio Processing | Android `Equalizer`, `BassBoost`, `Virtualizer`, `AudioManager` |
| Build System | Gradle Kotlin DSL, Version Catalog (`libs.versions.toml`) |

---

## Getting Started

1. Clone the repository:
   ```bash
   git clone https://github.com/Thando-1402/Music_player.git
   ```

2. Open the project in **Android Studio** and allow Gradle to sync.
3. *(Optional)* To pre-bake your free OpenRouter API key for local builds, add `OPENROUTER_API_KEY=sk-or-v1-...` to your local `local.properties` file (ignored by Git), or paste it in-app under **Profile & Settings**.
4. Connect an Android device (API 24+) and click **Run**, or build from the terminal:
   ```bat
   gradlew.bat assembleDebug
   ```

---

## Contact

**Repository:** [Thando-1402/Music_player](https://github.com/Thando-1402/Music_player)
**Maintainer:** [Thando-1402](https://www.google.com/url?sa=E&source=gmail&q=https://github.com/Thando-1402)