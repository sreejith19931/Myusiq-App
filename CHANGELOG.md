# Changelog

All notable changes to this project will be documented in this file.

## [2.1.0] - 2026-10-06

### Added
- **Album Grouping & Normalization**: Resolved duplicate album cards for multi-artist albums by grouping tracks strictly by normalized album key. Automatically displays "Various Artists" for tracks with different artists.
- **Persistent Library Sorting**: Added album sorting options (Alphabetical, Artist, Track Count, Recently Added) and track sorting options (Alphabetical, Recently Added, File Size, Popularity, Artist, Album, Recently Played) with Ascending/Descending toggles. Selected options are saved to `SharedPreferences` and restored across app restarts.
- **Interactive Janitor Review Pop-up**: Added side-by-side comparison pop-ups for **Auto-Fix Missing Artwork** and **Repair Missing Info** displaying CURRENT vs FOUND / PROPOSED info and artwork with checkboxes, giving users full choice over applied updates.
- **Artwork Caching & Dynamic Refresh**: Integrated `ArtworkUtils` persistent local artwork caching and updated `_artworkRefreshSignatures` timestamps to force Glide cache invalidation and render new artwork instantly across all screens.
- **Lyrics Management & Transliteration**: Added "Clear Lyrics Files" in Backup & Maintenance settings with "Clear All" and "Select Song Lyrics to Remove" options. Raw original lyrics are loaded as default; transliteration is applied on-demand when user taps the Transliterate button. Instant lyrics cleanup on track transitions.

## [2.0.2] - 2026-09-15

### Added
- **Website Automation**: Implemented Python script to automatically update the website roadmap from changelog entries.
- **CI/CD Optimization**: Improved release workflow with explicit token-based authentication for multiple repositories.
- **User Support**: Integrated frictionless email reporting with diagnostic templates in-app and on the website.

## [2.0.1] - 2026-09-14

### Fixed
- **Database Integrity**: Fixed a `java.lang.IllegalStateException` where Room could not verify data integrity after schema changes. 
  - Incremented `AppDatabase` version to `11`.
  - Enabled `fallbackToDestructiveMigration` in `DatabaseProvider` to automatically recreate the database when schema mismatches occur during development.
- **Version Alignment**: Synced `versionCode` and `versionName` in build configuration for the new patch release.

## [2.0.0] - 2026-09-14

### Added
- **Core Playback Engine**: Robust local audio playback powered by Media3 and ExoPlayer.
- **Material Design 3 UI**: Modern, elegant interface with seamless transitions and animations.
- **Dynamic Theming Engine**: Support for Light/Dark modes and dynamic colors extracted from album art.
- **Library Management**: 
  - Categorized browsing by Tracks, Albums, Folders, and Playlists.
  - Smart library scanning and indexing using Room database.
- **Advanced Audio Features**:
  - **Equalizer**: Built-in 5-band equalizer with presets and bass boost.
  - **Audio Trimmer**: Utility to clip and save audio segments.
- **Specialized Modes**:
  - **Driving Mode**: A simplified, distraction-free UI for safe use while driving.
- **Lyrics Integration**: Local and online lyrics retrieval and display.
- **User Experience**:
  - **Setup Wizard**: Interactive onboarding process for permissions, folder selection, and personalization.
  - **Library Janitor**: Tools to clean up missing files and manage library health.
  - **Deep Customization**: Extensive settings for appearance, audio behavior, and library management.
- **CI/CD Pipeline**: Integrated GitHub Actions for automated builds, testing, and APK distribution to the Rhythm-app repository.
