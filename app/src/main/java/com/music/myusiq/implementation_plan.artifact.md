# Page-by-Page UI Analysis & Material 3 Loading Indicator Recommendations

An audit of the Myusiq application reveals several asynchronous operations, background database scans, I/O tasks, and network requests where implementing Material 3 circular and linear loading indicators (`CircularProgressIndicator` & `LinearProgressIndicator`) would significantly improve user experience and consistency.

## Summary Table of Recommendations

| Screen / Feature | Trigger Action | Current Behavior | Recommended Material 3 Loading Pattern |
| :--- | :--- | :--- | :--- |
| **Library Settings** (`LibrarySettingsFragment`) | "Rescan Library", "Reset Folder Restrictions" | Toast message (`"Scanning music library..."`) | Material 3 modal loading dialog with indeterminate circular indicator & status text. |
| **Maintenance Settings** (`MaintenanceSettingsFragment`) | Backup Data, Restore Data, Factory Reset | Instant UI / basic toast | Material 3 progress dialog (*"Backing up database & playlists..."*). |
| **Audio Trimmer** (`AudioTrimmerFragment`) | Trim Audio / Save as Ringtone | Short delay / I/O block | Material 3 loading container (*"Processing audio stream & encoding..."*). |
| **Batch Track Tagging** (`TracksFragment`) | Batch edit tags for selected tracks | Basic progress dialog | Unified Material 3 expressive dialog (like Library Janitor) with determinate progress (1 to N). |
| **Online Lyrics & Metadata** (`PlayerFragment` / `TrackInfoBottomSheet`) | Search online for lyrics or artwork | Basic text / progress spinner | Material 3 circular progress indicator card overlay (*"Searching online databases..."*). |
| **Onboarding Setup** (`SetupFoldersFragment`) | Initial folder scan during setup | Silent scan | Material 3 welcome progress card (*"Discovering device audio files..."*). |

---

## Detailed Page Breakdown

### 1. Library & Storage Settings (`LibrarySettingsFragment`)
* **Operation:** Rescanning device media storage or clearing folder restrictions.
* **Why improve:** Currently relies solely on a quick Toast message. For large libraries containing thousands of songs, a media scan takes several seconds.
* **Material 3 Recommendation:** Show a modal Material 3 dialog with `CircularProgressIndicator` (*"Scanning local device storage for music..."*) with a Cancel action.

### 2. Maintenance & Backup (`MaintenanceSettingsFragment`)
* **Operation:** Exporting app data / importing backups / factory reset.
* **Why improve:** Exporting SQLite databases and history JSON files involves heavy disk I/O.
* **Material 3 Recommendation:** Display a Material 3 loading indicator dialog (*"Exporting database backup to storage..."*) with determinate progress or pulsing animation.

### 3. Audio Trimmer & Ringtone Saver (`AudioTrimmerFragment`)
* **Operation:** Cutting audio waveforms and writing output files to ringtone directory.
* **Why improve:** Audio transcoding and file writing can cause brief stuttering if done on the main thread or waiting for background tasks.
* **Material 3 Recommendation:** Show a bottom sheet or inline Material 3 progress card with a linear progress indicator (*"Trimming audio file & encoding ringtone..."*).

### 4. Batch Track Editing (`TracksFragment`)
* **Operation:** Selecting multiple tracks to edit artist, album, or genre in bulk.
* **Why improve:** Uses a basic system alert dialog without detailed file write progress.
* **Material 3 Recommendation:** Reuse the unified Material 3 Janitor dialog component to show exact 1-to-N tag writing progress.

### 5. Lyrics & Metadata Search (`TrackInfoBottomSheet` / `FullscreenLyricsStageDialogFragment`)
* **Operation:** Querying Genius/Musixmatch/MusicBrainz for lyrics or artwork.
* **Why improve:** Network latency during search can leave users waiting with static buttons.
* **Material 3 Recommendation:** Embed a Material 3 `CircularProgressIndicator` directly inside the search button or stage view.
