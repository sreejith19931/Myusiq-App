# Changelog

All notable changes to this project will be documented in this file.

## [1.4.1] - 2026-10-25

### Added & Fixed
- **Streamlined Setup & Permissions Wizard**: Refined Access permissions page with clean green verification cards, bold typography upon grant, badge-free minimal design, and themed header icons.
- **Default-Deselected Folder Setup**: Discovered folders now start deselected (OFF) by default during setup, allowing users to explicitly choose which directories to scan.
- **Zero-Delay Folder Toggling**: Implemented in-memory folder map caching for instant, zero-delay UI responsiveness when toggling folder exclusions and smart noise filter presets.
- **Website & Telegram Update Integration**: Updated web companion and official Telegram announcement channel references.

## [1.4.0] - 2026-10-24

### Added & Fixed
- **Native Setup Folder Discovery & Xiaomi HyperOS Compatibility**: Added native in-app music directory browser during setup, multi-volume MediaStore queries, and direct storage fallback scanning.
- **Interactive Tap-to-Sync Lyric Creator**: Built real-time tap-to-sync lyric creator bottom sheet allowing users to stamp LRC timestamps `[mm:ss.xx]` line-by-line during track playback and embed into audio ID3 tags.
- **Sub-Second Karaoke Line Shading**: Upgraded `LyricLineView` to render smooth horizontal left-to-right text fill gradient shading as audio progresses.
- **60fps Visualizer Studio**: Added standalone 60fps Visualizer Studio with 4 audio-reactive render modes (*Spectrum Analyzer, Circular Pulse Ring, Liquid Waveform, Particle Field*).
- **Persistent Metadata Fix History**: Mirror track edit history to public shared storage (`.myusiq_backup`) and MediaStore, guaranteeing survival across app updates and reinstalls.

## [1.3.0] - 2026-10-18

### Added & Fixed
- **10-Band Equalizer & Spectrum Visualizer**: Expanded equalizer to 10 frequency bands (`31Hz`, `62Hz`, `125Hz`, `250Hz`, `500Hz`, `1kHz`, `2kHz`, `4kHz`, `8kHz`, `16kHz`) with a real-time FFT frequency spectrum analyzer.
- **Hardware AudioFX Suite**: Integrated native `BassBoost`, `Virtualizer` (3D Spatial surround), `PresetReverb`, and `LoudnessEnhancer` audio effects with live UI sliders & toggle chips.
- **Variable Playback Speed & Pitch Control**: Variable speed controls (`0.75x`, `1.0x`, `1.25x`, `1.5x`, `2.0x`) with pitch locking via ExoPlayer `PlaybackParameters`.
- **EBU R128 ReplayGain Normalization**: Automatic ReplayGain parsing with +6dB preamp volume compensation for dynamic, consistent volume across all tracks.
- **Audio Output Target Routing**: Profile memory auto-switching across Device Speaker, Wired Headphones, and Bluetooth Audio outputs.
- **Dynamic Hi-Res Quality Badges**: Real-time sample rate (`96.0 kHz`), format (`FLAC`), and bitrate badges rendered on the Now Playing player screen.
- **Playback Engine Stability & Error Safeguards**: Error recovery, HAL resource leak prevention, and visualizer bounds safeguards across custom ROMs.

## [1.3.0-beta3] - 2026-10-16

### Added & Fixed
- **Parallel Auto-Fix & Bounded Processing**: Accelerated Library Janitor cloud scans with 4x parallel speedup via Kotlin Coroutines `Semaphore(4)` permit locks.
- **Shared Album Artwork Auto-Assignment**: Tracks belonging to the same album automatically share and reuse official album artwork, instantly filling missing covers across multi-track albums.
- **In-Memory LRU Search Query Caching**: Cached online metadata search results with an LRU cache (100-entry capacity) to prevent duplicate API queries and speed up manual searches.
- **Dialog Overflow & Fixed Button Placement**: Optimized review pop-up dialog height and button anchoring so "Apply Selected" is always visible across all device screen sizes.
- **Smart First-Time Scan Lifecycle**: Runs a comprehensive artwork & metadata scan once on initial launch, then smartly filters subsequent scans strictly for tracks with missing art or messy metadata.
- **Metadata Verification Accuracy**: Enhanced clutter detection and path synchronization to eliminate false positives for clean or previously updated tracks.

## [1.3.0-beta2] - 2026-10-15

### Added & Fixed
- **Smart Janitor Candidate Verification & Similarity Scoring**: Added token Jaccard similarity and duration match bonus to candidate evaluation, eliminating false positives and wrong suggestions.
- **Interactive Manual Online Search**: Added a "Search Online" button in the Janitor review pop-up with a search picker (`dialog_manual_cloud_search.xml`) displaying iTunes & Deezer candidate results with high-res artwork, album info, and match confidence scores.
- **One-Tap Metadata Revert / Undo**: Implemented pre-edit JSON snapshots before applying bulk/single metadata updates, with a 1-tap "Undo Last Fix" card in Library Janitor.
- **Offline Title Case Sanitizer**: Converts ALL-CAPS or all-lowercase titles to Title Case and strips website domains (`www.site.com`), stray underscores, and web converter tags offline.
- **Auto Metadata Fix Optimization**: Prevented infinite re-scanning of clean or already-fixed tracks when toggling the Auto-Improve switch ON/OFF.

## [1.3.0-beta1] - 2026-10-15

### Added & Fixed
- **10-Band Equalizer Core Engine**: Expanded equalizer from 5 bands to 10 bands (`31Hz`, `62Hz`, `125Hz`, `250Hz`, `500Hz`, `1kHz`, `2kHz`, `4kHz`, `8kHz`, `16kHz`) with profile auto-switching.
- **Hardware AudioFX Extensions**: Integrated native Android `BassBoost`, `Virtualizer` (3D Spatial surround), `PresetReverb`, and `LoudnessEnhancer` instances tied to ExoPlayer audio session IDs.
- **Dual-Player AudioFX Crossfade Sync**: Preserved primary and secondary AudioFX session switching for smooth dual-ExoPlayer crossfading without popping.
- **Equalizer UI Redesign**: Added 10-band slider interface with horizontal scrolling and live interactive controls for Bass Boost & 3D Virtualizer strength.

## [1.2.0] - 2026-10-12

### Added & Fixed
- **A-Z FastScroller Enhancements**: Positioned '#' symbol on top and implemented smooth 3D fisheye wave zoom animation with smoothstep curve.
- **Settings Page Reorganization**: Grouped all Settings categories and sub-screens into clean Material 3 card sections with section headers.
- **Whole-Track Energy Analysis & Smart Mixes**: Updated waveform extractor to sample 100 points across the entire track duration and guaranteed full 20-50 track smart playlist generation.
- **App Launch Responsiveness**: Optimized PlaybackViewModel to start playback instantly when tapping a track on app launch with zero latency.
- **In-App GitHub Update Engine**: Automated release checks, direct APK downloading with progress status, rate limit handling, and Android 8.0+ installer permissions.

## [1.1.1] - 2026-10-10

### Fixed
- **In-App Update Checker Fixes**: Fixed issue where "Check for Updates" reported "Unable to check. Check internet connection" by adding Proguard/R8 `@Keep` rules for Gson models.
- **Specific Error Feedback**: Enhanced update check status reporting to distinguish network disconnections from GitHub API rate limits (HTTP 403/429), HTTP 404, or parse errors.
- **Multi-Repository Fallback & Asset Filtering**: Added fallback endpoint support and improved APK asset matching to reliably download phone installer binaries.
- **Package Install Permission Handling**: Handled Android 8.0+ unknown app installation permissions gracefully.

## [1.1.0] - 2026-10-09

### Added & Fixed
- **In-App GitHub Update Tracker**: Automatic update check on app launch and manual check in Settings -> About with direct APK download & installation.
- **Telegram Announcement Automation**: Integrated automated release publishing & Telegram channel notifications.
- **UI & Performance Enhancements**: General polish, bug fixes, and stability improvements.

## [1.0.2] - 2026-10-08

### Added & Fixed
- **Physical ID3 Tag & Artwork Persistence**: Embedded ID3v2 tags and cover art directly into audio files on disk and added persistent public backups.
- **Permission Optimization**: Removed unnecessary `RECORD_AUDIO` prompt on startup, asking for audio recording permission strictly on-demand when enabling the FFT visualizer.
- **Performance & Playback Responsiveness**: Accelerated library scan speed to <50ms and eliminated Binder IPC queue latency for instant track playback.
- **CI/CD Build Speed**: Optimized GitHub Actions workflow with Gradle build cache and parallel task execution.

## [1.0.1] - 2026-10-08

### Added
- **Release Key Integration**: Configured release keystore and signing configs for automated GitHub releases and seamless in-place app updates.
- **CI/CD Build Pipeline**: Updated GitHub Actions build workflow to generate production release APKs automatically.

## [1.0.0] - 2026-10-06

### Added
- **Official Rebranding to Myusiq**: Complete project and app rebrand from Rhythm to Myusiq across code, themes, widgets, navigation, build configurations, and website.
- **Album Grouping & Normalization**: Resolved duplicate album cards for multi-artist albums by grouping tracks strictly by normalized album key.
- **Persistent Library Sorting**: Added album sorting options (Alphabetical, Artist, Track Count, Recently Added) and track sorting options (Alphabetical, Recently Added, File Size, Popularity, Artist, Album, Recently Played) with Ascending/Descending toggles.
- **Interactive Janitor Review Pop-up**: Added side-by-side comparison pop-ups for Auto-Fix Missing Artwork and Repair Missing Info displaying CURRENT vs PROPOSED info with checkboxes.
- **Lyrics Management & Transliteration**: Added lyrics management with transliteration loaded on-demand.

## [0.9.5] - 2026-09-20

### Added
- **Core Playback Engine**: Robust local audio playback powered by Media3 and ExoPlayer.
- **Material Design 3 UI**: Modern interface with seamless transitions, dynamic color extraction, Light/Dark mode.
- **Studio Equalizer**: Built-in 5-band equalizer with presets and bass boost.
- **Audio Trimmer**: Utility to clip and save audio segments.
- **Driving Mode**: Simplified distraction-free UI for safe use while driving.
