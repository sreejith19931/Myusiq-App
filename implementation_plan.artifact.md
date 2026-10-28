# Implementation Plan: v1.4.1 Stability Polish & v1.5.0 Smart Library 2.0

This plan outlines the sequential engineering tasks to transition Myusiq from **v1.4.1** (Stability, Memory, and Battery Polish) to **v1.5.0-beta1** (Smart Library 2.0, Batch Tag Organization, and Harmonic Key Detection), while strictly preserving GitHub Actions quota via 100% local testing.

## User Review Required

> [!IMPORTANT]
> **GitHub Actions Quota Conservation**: All builds (`app:assembleDebug`, test suites) will be executed entirely locally using Gradle. No pushes or remote CI triggers will occur until local verification is 100% green.

## Proposed Changes

### 1. Version 1.4.1: Stability, Memory & Performance Polish

#### [MODIFY] [PlayerFragment.kt](file:///C:/Users/Sreejith/AndroidStudioProjects/Myusiq/app/src/main/java/com/music/myusiq/ui/PlayerFragment.kt)
- Audit and clean up bitmap references and state listeners in `onDestroyView()` to prevent memory leaks.
- Ensure audio session resources and visualizer callbacks unregister cleanly when backgrounded.

#### [MODIFY] [FftEngineManager.kt](file:///C:/Users/Sreejith/AndroidStudioProjects/Myusiq/app/src/main/java/com/music/myusiq/service/FftEngineManager.kt)
- Optimize capture rate and ensure `Visualizer` instances are explicitly disabled and released when the Now Playing sheet or visualizer view is hidden or paused.

#### [MODIFY] [SetupFoldersFragment.kt](file:///C:/Users/Sreejith/AndroidStudioProjects/Myusiq/app/src/main/java/com/music/myusiq/ui/SetupFoldersFragment.kt) & [MetadataBackupUtils.kt](file:///C:/Users/Sreejith/AndroidStudioProjects/Myusiq/app/src/main/java/com/music/myusiq/utils/MetadataBackupUtils.kt)
- Strengthen Scoped Storage fallback handling for Xiaomi HyperOS and Android 14+ directories.

---

### 2. Version 1.5.0: Smart Library 2.0 & Batch Organization

#### [MODIFY] [AudioFingerprintingUseCase.kt](file:///C:/Users/Sreejith/AndroidStudioProjects/Myusiq/app/src/main/java/com/music/myusiq/domain/usecase/AudioFingerprintingUseCase.kt)
- Enhance acoustic signature extraction to support robust duplicate detection regardless of bitrate/naming differences.

#### [NEW] [BatchTagOrganizer.kt](file:///C:/Users/Sreejith/AndroidStudioProjects/Myusiq/app/src/main/java/com/music/myusiq/utils/BatchTagOrganizer.kt)
- Implement batch tag editing and file storage reorganization according to customizable template strings (e.g., `{Artist}/{Album}/{Track} - {Title}.ext`).

#### [MODIFY] [MusicEnergyAnalyzer.kt](file:///C:/Users/Sreejith/AndroidStudioProjects/Myusiq/app/src/main/java/com/music/myusiq/utils/MusicEnergyAnalyzer.kt)
- Extend energy analysis to compute estimated tempo (BPM) and musical key for DJ-style harmonic playlist generation.

---

## End-to-End User Journey Verification Checklist (Setup -> All Pages & Features -> About Screen)

Upon completion of the implementation, the following sequential end-to-end user walkthrough will be executed and verified locally:

### Step 1: Initial Setup & Permissions Wizard (`SetupActivity`)
- [ ] **Welcome Screen (`SetupWelcomeFragment`)**: Clean hero graphic, primary "Get Started" button navigation.
- [ ] **Permissions Screen (`SetupPermissionFragment`)**: Storage access permission card turns bold green upon grant without badge artifacts.
- [ ] **Folder Discovery Screen (`SetupFoldersFragment`)**: Native directory browser lists media folders starting deselected (OFF) by default; toggling folders updates instant zero-delay in-memory cache.
- [ ] **Personalize & Palette (`SetupPersonalizeFragment`)**: Material You theme swatch selection applies immediately to app chrome.

### Step 2: Main Library Navigation (`MainActivity` & Core Tabs)
- [ ] **Tracks Fragment (`TracksFragment`)**: A-Z FastScroller with '#' on top and 3D fisheye wave zoom; smooth track list scrolling (<50ms initial load).
- [ ] **Albums Fragment (`AlbumsFragment`)**: Album grid rendering, shared album artwork auto-assignment across multi-track albums, tap opens `AlbumDetailsFragment`.
- [ ] **Playlists Fragment (`PlaylistsFragment`)**: Smart playlist creation (Energy/BPM mixes), custom playlist editing, track reordering.
- [ ] **Folders Explorer (`FoldersFragment`)**: Directory tree browsing, folder exclusion toggles, breadcrumb navigation.

### Step 3: Now Playing Player Screen & Queue (`PlayerFragment` & `QueueFragment`)
- [ ] **Now Playing Player (`PlayerFragment`)**: Dynamic Material You palette extraction from album art; playback controls (Play/Pause, Skip Next/Prev, Seekbar, Shuffle, Repeat).
- [ ] **Hi-Res Badges & Variable Speed/Pitch**: Real-time sample rate (`96 kHz`), format (`FLAC`/`MP3`), bitrate badges; speed control (`0.75x` - `2.0x`) with pitch locking.
- [ ] **Queue Fragment (`QueueFragment`)**: Drag-and-drop reordering, swipe to remove, clear queue.

### Step 4: 10-Band Equalizer & Hardware AudioFX (`EqualizerFragment`)
- [ ] **10-Band Sliders**: Sliders (`31Hz` to `16kHz`) alter frequency balance live without audio distortion.
- [ ] **Real-time Spectrum View**: FFT frequency spectrum analyzer renders live audio response.
- [ ] **Hardware AudioFX Suite**: `BassBoost`, `Virtualizer 3D`, `PresetReverb`, and `LoudnessEnhancer` sliders update audio session parameters.

### Step 5: 60fps Visualizer Studio & Karaoke Lyrics
- [ ] **Visualizer Studio (`VisualizerStudioBottomSheet`)**: Smooth 60fps rendering across all 4 modes (*Spectrum Analyzer, Circular Pulse Ring, Liquid Waveform, Particle Field*).
- [ ] **Tap-to-Sync Lyric Creator (`LyricEditorBottomSheet`)**: Real-time timestamp stamping `[mm:ss.xx]` line-by-line during playback; embeds into physical ID3 tags.
- [ ] **Sub-Second Karaoke Line Fill**: Left-to-right fill animation in `LyricLineView` syncs smoothly with audio progress.

### Step 6: Audio Trimmer & Driving Mode
- [ ] **Audio Trimmer (`AudioTrimmerFragment`)**: Waveform selection range trimming, fade-in/fade-out curves, preview playback.
- [ ] **Driving Mode (`DrivingModeFragment`)**: High-contrast, large-button UI with gesture controls and high-visibility track metadata.

### Step 7: Smart Library 2.0 & Library Janitor (`LibraryJanitorFragment`)
- [ ] **Chromaprint / Acoustic Fingerprinting**: Identifies duplicate songs regardless of file naming or bitrate differences.
- [ ] **Batch Tag Editor & File Organizer (`BatchTagOrganizer`)**: Bulk tag updates re-organize local storage files based on `{Artist}/{Album}/{Track} - {Title}.ext` templates.
- [ ] **BPM & Harmonic Key Detection (`MusicEnergyAnalyzer`)**: Calculates tempo and musical key for harmonic playlist mixing.
- [ ] **Metadata Fix History & Revert**: Snapshots pre-edit state; 1-tap "Undo Last Fix" card reverts tags and mirrors to `.myusiq_backup`.

### Step 8: Settings Hub & Sub-Screens (`SettingsFragment`)
- [ ] **Appearance Settings (`AppearanceSettingsFragment`)**: Dark/Light/System theme toggling, color swatches.
- [ ] **Audio Settings (`AudioSettingsFragment`)**: EBU R128 ReplayGain toggle (+6dB preamp), audio target routing profile switching.
- [ ] **Lyrics Settings (`LyricsSettingsFragment`)**: Online LRC fetcher toggles, font size sliders.
- [ ] **Maintenance Settings (`MaintenanceSettingsFragment`)**: Cache clearing, manual backup trigger to shared storage.

### Step 9: Stability, Memory & Battery Audits (v1.4.1 Fixes)
- [ ] **Memory Audit**: Repeatedly opening/closing `PlayerFragment` results in zero leaked Bitmaps or unreleased visualizer listeners.
- [ ] **Battery Efficiency**: Background audio playback with FFT visualizer disabled consumes <1.5% battery per hour.
- [ ] **Scoped Storage Fallback**: Scoped storage permissions remain resilient across Android 14+ and Xiaomi HyperOS.

### Step 10: About Screen & In-App Update Engine (`AboutFragment`)
- [ ] **App Info & Version**: Displays accurate version string (`v1.5.0-beta1`).
- [ ] **In-App GitHub Update Engine**: "Check for Updates" queries release endpoint, handles rate limits gracefully, and triggers direct APK download dialog.
- [ ] **Links & Channel Integration**: Official Telegram announcement channel and website links launch correctly in external browser.

---

### Automated Build Verification
- Execute full local Gradle build and test suite:
  `gradle_build("app:assembleDebug")`
