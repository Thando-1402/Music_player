# AI Handoff: Auralis Internal Development Notes (Local Only)

## Current Architecture & Status
- **Target Device:** Nokia 1.4 (Android 11 / API 30, low-RAM optimization)
- **Min SDK:** API 24 (Android 7.0)
- **Completed Screens:**
  1. `MainActivity` (Library list, shared mini-player, `VOLUME_NAME` MicroSD URI support, `AudioFilterManager` integration, managed `scanExecutor`).
  2. `NowPlayingActivity` (Full-screen player, SeekBar scrubber, persistent Favourites via `SongMetadataManager`, `AudioManager` DAC route pill, 60fps micro-animations).
  3. `FoldersActivity` (Side-by-side Internal vs. MicroSD storage meters, `[Phone]`/`[MicroSD]` folder badges, long-press folder hiding, callback-based media rescan).
  4. `FolderExclusionsActivity` (Material 3 switches for hidden folders, `.nomedia`, `<30s` clips, WhatsApp isolation, Podcasts, and restoring hidden folders).
  5. `EditTrackActivity` (Full-screen ID3/metadata override editor, `MediaMetadataRetriever` embedded art extraction, gallery cover picker, `.lrc` lyrics dialog, stable internal JSON storage in `context.getFilesDir()`).
- **Recent Bug Fixes:**
  - Fixed `ListenableFuture.get()` `NullPointerException` race condition in `onStart()` across `MainActivity`, `FoldersActivity`, and `NowPlayingActivity`.
  - Fixed `MaterialSwitch` binding in `FolderExclusionsActivity`.
  - Purged `.logcat` files and added `LogCat/`, `*.logcat`, and `AI_HANDOFF.md` to `.gitignore`.

## Next Up on Roadmap
- Wire up `ProfileSettingsActivity` (Settings toggles, OpenRouter AI Auto-Tagger key config, Firebase Auth).
- Wire up `PlaylistsActivity` & `EditPlaylistActivity` (Custom playlists, Smart Curator, `.m3u8` export).
- Wire up `EqualizerActivity` (5-band hardware `Equalizer`, `BassBoost`, and `Virtualizer` bound to ExoPlayer audio session).