# Changelog

All notable changes to this project will be documented in this file.

## [Unreleased]

### Added & Fixed
- **Real Audio Waveforms for Mood Classification**: Waveform extraction now decodes true PCM via MediaCodec and stores per-window RMS envelopes (400 windows) instead of reading compressed frames as amplitude data — the old extractor made every track read as maximum energy, so mood playlists matched nothing. Legacy/corrupt waveforms are auto-detected and re-extracted in the background.
- **Mood Mix Quality Hardening**: Mood mixes respect their energy archetype with symmetric hard-veto windows for every mood (chill moods admit only genuinely soft tracks, party moods reject quiet ballads), the fit ranking is preserved (the old code shuffled away the ordering), tracks whose audio contradicts a mood are excluded, and profiles written from corrupt data are re-classified instead of being trusted.
- **Hang-Proof Tempo Estimation**: Fixed an infinite loop in BPM estimation on sparse peak envelopes and a duration-unit bug that collapsed every track's BPM to the 120 fallback; profiles now carry real per-track tempo (70–136 BPM observed).
- **Playback-Driven Mood Learning**: The app now learns from how you actually listen. Finish a track and it rises in the mood mixes; skip it early and it sinks. After repeated skips of the same track the engine goes further and re-categorizes it — the penalized mood is demoted and the next-best mood takes over, so a track can literally move between mood playlists based on your listening. Feedback is stored per track (Room v16 `listenFeedback`/`listenSamples`).
- **Smarter Mood Mix Membership**: Mixes are built in evidence tiers — engine-verified classifications first, then title/lyric keyword confirmations (e.g. "Aakhri Ishq" enters Romance via its title while "Didi (Sher-E-Baloch)" at an identical audio profile does not), then strong multi-signal fits. Weak guesses only pad the list when there is not enough real evidence.
- **Indian-Language Mood Vocabulary**: Keyword evidence now covers Urdu/Hindi (ishq, pyaar, mohabbat, sanam, deewana...), Tamil (kadhal, anbe, kanne...) and Telugu/Malayalam (prema, priya...) romance markers plus kuthu/gaana party titles, and frequent listens get a small affinity boost.
- **Junk-Genre Enrichment**: MediaStore placeholder genres ("music", "other", "unknown") are now treated as unknown so the background worker fetches real genres from iTunes/Deezer/MusicBrainz for them.
- **Progressive Mood Organization**: Mood profiles build up immediately from genre/title/lyrics tags at library load and continuously refresh with audio evidence as waveforms are analyzed (every 10 tracks), instead of waiting for the entire waveform pass to finish.
- **Adaptive Mood Engine (Smart Playlists that Learn)**: New three-signal classification engine analyzes every library track and organizes the mood playlists automatically. Audio assessment (waveform energy + estimated BPM mapped to mood archetypes), genre/title/lyric keyword tagging, and capped online genre lookups via iTunes, Deezer & MusicBrainz for tracks with unknown genre.
- **Mood Playlists Learn From Your Own Playlists**: Aggregates the audio, genre and keyword profile of the tracks you actually place in each playlist (e.g. "Gym Anthems", "Chill Sunday") and folds it back into mood mixes — the more you curate, the better the mood chips match your taste.
- **Mood Chip Playlist Pages**: Tapping a mood chip (Energize, Relax, Party, Focus, Romance, Drive) now opens its mood playlist page with the engine-ranked track list; long-press still instantly plays the mix.
- **Playlist Track Suggestions**: Long-press any playlist → *Suggest Tracks* ranks the rest of your library by similarity to that playlist's learned profile for one-tap additions.
- **Auto-Organize as a Background Process**: The Mood Engine organizes tracks into the mood chips fully automatically — a silent local classification pass runs after every library load, then a WorkManager job (`MoodClassificationWorker`) finishes the job with online genre enrichment and playlist learning. No manual trigger needed; mood profiles are stored in Room (v15: `mood_profiles`, `playlist_profiles` with full migration).
- **Release & Signing Hardening**: Fixed the release keystore path resolution that silently fell back to debug signing, corrected R8 keep rules for Room entities, enabled R8 shrinking for the Wear OS companion app (7.1 MB, down from 15.4 MB), and added 13 new unit tests (49 total) covering the repository, mood classifier and database migrations.
- **Library Janitor Rebuilt for Speed & Accuracy**: Scans now reuse the waveforms the Mood Engine already extracted instead of re-decoding every file (~10x faster), the per-track timeout no longer kills real online matches, the dead AcoustID lookups (invalid fingerprint format and client key) were removed in favour of duration-validated iTunes/Deezer matching, "Artist - Title" splitting no longer mangles hyphenated artists (Jay-Z), background scans persist their results so the review sheet opens instantly, dismissed suggestions are remembered and stop being re-proposed, and background tag healing is high-confidence-only with automatic reversion snapshots.
- **M3U Playlist Export & Import**: Any playlist exports to a standard extended M3U (with artist/title/duration metadata and absolute paths) via its overflow menu; the plus button in Playlists now offers "Import M3U file" — paths are matched against the library (with file-name fallback for relocated files) and unmatched entries are reported. Tested round-trip on device.
- **Mood Auto-DJ**: Play any mood playlist and the queue never ends — as playback approaches the end of the queue, ten more tracks from the same mood mix are added automatically. The endless queue skips what's already queued or recently added and filters out tracks you've repeatedly skipped (feedback learning keeps steering the selection). Toggle it in Settings → Audio (on by default, only engages for mood playlists).
- **Volume Normalization Now Works for Every Track**: The ReplayGain setting previously only helped tracks with ReplayGain tags; tracks without one (converted or downloaded files) played at full volume. Normalization now falls back to the Mood Engine's cached RMS waveform to derive loudness — instant, no decoding — bringing quiet and loud masters to a consistent level. Stale legacy/saturated waveforms are ignored and left at unity.
- **Playback Reliability Fixes**: After 5 consecutive playback errors the service now actually pauses (instead of announcing it and looping), error toasts only appear once per burst instead of one per track, crossfade duration lookups no longer hit disk I/O on the main thread, and the widget update timer idles at 5s instead of spinning at 1Hz while paused.
- **Janitor Remembers Fixed Tracks Across Reinstalls**: A public processed-track record (in the shared .myusiq_backup folder) survives app-data clears, so after reinstalling the app no longer re-scans or re-proposes fixes for tracks it already healed — it shows "All metadata and artwork look clean" right away. Tracks whose tags are later edited are reviewed again automatically.
- **Janitor Quality Round 2**: iTunes now falls back to the Indian storefront when the US catalogue misses regional music, all-caps artists keep their acronyms (DJ, AC/DC, EDM survive Title Case), one artwork download is shared across an album's tracks instead of re-downloading per song, matched-album covers are shared with artwork-less siblings, and duplicate scans are debounced so rapid library refreshes don't re-trigger the full analysis.
- **Mood Profiles Invalidate After Metadata Fixes**: When the Janitor corrects a track's title/artist/genre, its mood profile is dropped and re-classified with the corrected metadata.
- **Website Overhaul**: Fully responsive design for 320px–1440px screens (mobile nav fix, compact announcement banner, fluid typography), SEO structured data, sitemap & robots.txt, WebP-optimized screenshots, keyboard-accessible gallery and a Firefox hero-compositing fix.

## [1.7.0] - 2026-11-20

### Added & Fixed
- **Version & Build Infrastructure Upgrade**: Synchronized version bump (`v1.7.0` / versionCode `10700`) across `:app` and `:wear` modules.
- **Interactive Touch Waveform Audio Trimmer & Export**: Full touch-draggable audio trimmer with multi-scale zoom (`1x`-`16x`), instant ringtone/notification setup, and file sharing via FileProvider.
- **Spacious Library Janitor Sheet & Quick Filters**: Upgraded metadata review dialog into a full-page scrollable bottom sheet with filter chips (*All, Missing Art, Unknown Artist, Messy Titles*).
- **Studio Equalizer Preset Serialization**: 1-tap JSON export and sharing of custom 10-band equalizer, bass boost, and 3D spatializer profiles.
- **Engine Stability & Storage Resilience**: Refined scoped storage fallback resolution on Android 14+ / Xiaomi HyperOS and zero-delay playback initiation.

## [1.6.0] - 2026-11-10

### Added & Fixed
- **Interactive Touch Waveform Audio Trimmer**: Direct touch waveform dragging for precise start/end trim points, zoom-in/out (`1x` to `16x`) controls, real-time selected clip duration counter, and live playback progress line.
- **Audio Trimmer Ringtone, Notification & Quick Share**: Immediate 1-tap options after trimming to set audio as default ringtone, notification sound, or share via FileProvider.
- **Library Janitor Quick Filters & Search**: Horizontal filter chips (*All, Missing Art, Unknown Artist, Messy Titles*) inside the full-page Library Janitor review bottom sheet.
- **Studio Equalizer Preset Export**: 1-tap export of 10-band frequency gains, bass boost, and virtualizer settings to shareable JSON preset files.
- **Full-Page Library Janitor Review**: Upgraded metadata fix workflow from a restricted popup dialog to a spacious, scrollable full-page bottom sheet.

## [1.5.0] - 2026-10-26

### Added & Fixed
- **Smart Library 2.0 & Batch Tag Organization**: Automated acoustic fingerprinting and duplicate detection, bulk metadata batch editing and file storage restructuring based on customizable templates (`{Artist}/{Album}/{Track} - {Title}.ext`).
- **BPM & Harmonic Key Detection**: Automated tempo and musical key calculation in `MusicEnergyAnalyzer` for DJ-style harmonic playlist generation.
- **10-Band Studio Equalizer & AudioFX**: Precision frequency sliders (`31Hz` to `16kHz`), real-time FFT spectrum analyzer, hardware `BassBoost`, `Virtualizer 3D`, `PresetReverb`, and `LoudnessEnhancer`.
- **60fps Visualizer Studio & Tap-to-Sync Lyrics**: 4 audio-reactive render modes and real-time timestamp editor to stamp LRC lyrics line-by-line and embed into ID3 tags.
- **Streamlined Setup & Storage Resilience**: Refined permission cards, default-deselected folder discovery, and HyperOS/Android 14+ scoped storage fallback handling.

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
- **Metadata Verification Accuracy**: Enhanced clutter detection andpath synchronization to eliminate false positives for clean or previously updated tracks.

## [1.3.0-beta2] - 2026-10-15

### Added & Fixed
- **Smart Janitor Candidate Verification & Similarity Scoring**: Added token Jaccard similarity and duration match bonus to candidate evaluation, eliminating false positives and wrong suggestions.
- **Interactive Manual Online Search**: Added a "Search Online" button in the Janitor review pop-up with a search picker (`dialog_manual_cloud_search.xml`) displaying iTunes & Deezer candidate results with high-res artwork, album info, and match confidence scores.
- **One-Token Metadata Revert / Undo**: Implemented pre-edit JSON snapshots before applying bulk/single metadata updates, with a 1-tap "Undo Last Fix" card in Library Janitor.
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
- **In-App GitHub Update Tracker**: Automated update check on app launch and manual check in Settings -> About with direct APK download & installation.
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
- **Core Playback Engine**: Lookup code/version check in `ChangelogManager.kt`.
