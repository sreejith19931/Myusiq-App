# Myusiq v2.0 Roadmap & Execution Plan

## Current Baseline (`v1.4.1`)
- **Studio Audio**: 10-Band Equalizer, real-time FFT spectrum analyzer, hardware AudioFX (`BassBoost`, `Virtualizer 3D`, `PresetReverb`, `LoudnessEnhancer`), ReplayGain normalization.
- **Visuals & UI**: 60fps Visualizer Studio (4 reactive modes), Tap-to-Sync Lyric Creator, Material You dynamic theming extracted live from album art.
- **Library & Management**: Library Janitor automated health scans, offline ID3 tag embedding, smart default-deselected directory setup wizard.
- **Companion**: Wear OS app integration (`:wear` module).

---

## 🚀 v2.0 Core Pillars & Milestones

### 1. Jetpack Compose UI Modernization (`v2.0-alpha`)
- Migrate core legacy UI fragments (`PlayerFragment`, `TracksFragment`, `AlbumsFragment`) to pure **Jetpack Compose** with Material 3 Expressive styling.
- Deliver fluid 60fps transitions, window insets management, and adaptive multi-pane tablet/foldable layouts.

### 2. Auto-DJ & Gapless Crossfade Engine (`v2.0-beta`)
- Implement algorithmic track crossfading, adjustable crossfade duration (1s - 12s), and gapless playback optimization in `PlaybackService`.
- Introduce Auto-DJ smart transition rules based on energy levels and tempo.

### 3. Wear OS Standalone Sync (`v2.0-rc`)
- Upgrade `WearableDataListenerService` for bi-directional offline track transfer, playlist synchronization, and standalone smartwatch playback control.

### 4. DLNA / UPnP Local Network Streaming (`v2.0`)
- Add optional local network music streaming support (DLNA / UPnP) to stream from home NAS or media servers while retaining the 100% offline-first privacy model.

### 5. Encrypted Backup & Migration (`v2.0`)
- Comprehensive export/import of user playlists, favorites, custom equalizer profiles, and metadata edit histories to encrypted local backups.

---

## 🛡️ GitHub Actions Quota Protection & Verification Policy
Due to low GitHub Actions quota:
- **Local-First Verification**: All Gradle builds (`app:assembleDebug`, `:wear:assembleDebug`), unit tests, and UI inspections will be executed strictly locally.
- **Zero Exploratory Pushes**: No pushes to GitHub will be made until features are fully integrated, built, and verified locally.
- **Batch Releases**: Changes will be batched into well-tested milestones.
