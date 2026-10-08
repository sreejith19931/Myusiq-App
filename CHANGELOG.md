# Changelog

All notable changes to this project will be documented in this file.

## [1.0.1] - 2024-10-06

### Added
- **Release Key Integration**: Configured release keystore and signing configs for automated GitHub releases and seamless in-place app updates.
- **CI/CD Build Pipeline**: Updated GitHub Actions build workflow to generate production release APKs automatically.

## [1.0.0] - 2024-10-05

### Added
- **Official Rebranding to Myusiq**: Complete project and app rebrand from Rhythm to Myusiq across code, themes, widgets, navigation, build configurations, and website.
- **Album Grouping & Normalization**: Resolved duplicate album cards for multi-artist albums by grouping tracks strictly by normalized album key.
- **Persistent Library Sorting**: Added album sorting options (Alphabetical, Artist, Track Count, Recently Added) and track sorting options (Alphabetical, Recently Added, File Size, Popularity, Artist, Album, Recently Played) with Ascending/Descending toggles.
- **Interactive Janitor Review Pop-up**: Added side-by-side comparison pop-ups for Auto-Fix Missing Artwork and Repair Missing Info displaying CURRENT vs PROPOSED info with checkboxes.
- **Lyrics Management & Transliteration**: Added lyrics management with transliteration loaded on-demand.

## [0.9.5] - 2024-09-20

### Added
- **Core Playback Engine**: Robust local audio playback powered by Media3 and ExoPlayer.
- **Material Design 3 UI**: Modern interface with seamless transitions, dynamic color extraction, Light/Dark mode.
- **Studio Equalizer**: Built-in 5-band equalizer with presets and bass boost.
- **Audio Trimmer**: Utility to clip and save audio segments.
- **Driving Mode**: Simplified distraction-free UI for safe use while driving.
