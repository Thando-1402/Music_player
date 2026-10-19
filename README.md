# Auralis Music Player

**Auralis** is a lightweight, offline-first Android audio player built with Java, Material Design 3 XML Views, and AndroidX Media3 (ExoPlayer). Engineered for low-memory efficiency and tactile one-handed ergonomics, it delivers smooth 60fps navigation and bit-perfect local playback across internal storage and removable MicroSD cards.

---

## ✨ Implemented Features

### 🎧 Core Playback & Media3 Engine
- **Background Playback Service:** Powered by `androidx.media3` (`ExoPlayer` & `MediaSessionService`) for uninterrupted background audio.
- **Custom Media Notification:** System notification shade integration with playback controls and track metadata.
- **Persistent Mini-Player:** Anchored across primary navigation tabs with quick Play/Pause, Previous, and Next controls.

### 📱 Now Playing Experience
- **60fps Hardware-Accelerated UI:** True Dark (`#121212`) zero-overdraw layout with Electric Blue (`#00B0FF`) accents.
- **Interactive Controls:** 1000-step seekbar scrubber with live elapsed/remaining timestamps, 3-state Repeat, Shuffle rotation, and persistent Favourite heart toggles.
- **Live Audio Route Detection:** Real-time hardware output indicator detecting Internal DAC/Speaker, 3.5mm Wired Headphones, USB DACs, and Bluetooth A2DP streams.

### 📂 Dual-Storage & Folder Browser
- **Separate Storage Meters:** Independent capacity and free-space progress bars for **Phone Internal Storage** and **MicroSD Card** storage.
- **Volume-Aware Indexing:** Full `MediaStore` support for removable MicroSD volumes (`VOLUME_NAME`) alongside internal storage.
- **Directory Navigation:** Browse audio folders badged by storage location (`[Phone]` / `[MicroSD]`), sort by Name or Size, and trigger media rescans.

### 🛡️ Smart Scan & Index Filters
- **Automated Non-Music Isolation:** Automatically shields the library from WhatsApp voice notes and audio (`AUD-*-WA*` / `PTT-*-WA*`), short notification clips (`< 30s`), hidden system directories (`.*`), and `.nomedia` paths.
- **Custom Folder Exclusions:** Long-press any directory in the folder browser to hide it from library sweeps, or manage rules in the **Folder Scan & Exclusions** screen.

### 🎨 Track Details & Artwork Editor
- **Custom Metadata Overrides:** Edit track Title, Artist, Album, Track #, Year/Genre, and synchronized `.lrc` lyrics locally.
- **Artwork Management:** Extract embedded ID3 cover art directly from audio files, pick custom covers from the gallery, or reset artwork with automatic `300x300` compression.

### 📊 Stability & Diagnostics
- **Resource & Crash Telemetry:** Built-in APK footprint, RAM heap usage, and crash reporting integration.

---

## 🛠️ Tech Stack
- **Language:** Java
- **UI Framework:** Android Native XML Views (`ConstraintLayout`, Material Design 3)
- **Audio Engine:** AndroidX Media3 (`ExoPlayer`, `MediaSessionService`, `MediaController`)
- **Minimum SDK:** Android 7.0 (API 24)