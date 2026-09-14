# Changelog

All notable changes to this project will be documented in this file.

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
