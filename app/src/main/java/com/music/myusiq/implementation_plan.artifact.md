# 💿 Metadata Fixer Feature: Final Implementation Plan

## Goal
To automatically and semi-automatically correct messy or incomplete track metadata by analyzing acoustic fingerprints and matching them against the MusicBrainz database, providing the user with suggested matches for review.

## ⚙️ Decisions Made (User Delegation)
1.  **Audio Analysis Engine:** Lightweight, on-device acoustic fingerprinting will be used for fast, privacy-focused analysis.
2.  **External API Strategy:** MusicBrainz API will be the primary metadata source for matching.
3.  **Search Trigger:** **Batch Mode.** The fix will be triggered automatically during the `JanitorScanUseCase` execution.
4.  **Match Confidence:** A minimum confidence score of **90%** will be enforced to prevent false matches.
5.  **Performance:** WorkManager will be used to ensure all heavy audio processing and API calls are non-blocking.
6.  **Data Model:** `JanitorUpdateItem` will be extended to support a `Suggestion` state, allowing for user confirmation.

## 🏗️ Architectural Implementation

### 1. Data Model Extension
The existing `JanitorUpdateItem` model will be extended to include a `SuggestedMetadataItem` structure. This model will hold the original track data alongside a list of potential matches, each containing:
*   `suggestedTitle`, `suggestedArtist`, `suggestedAlbum`, `suggestedArtUri`.
*   `confidenceScore` (0.0 to 1.0).
*   `matchId` (Reference to the external source).

### 2. The Intelligence Layer (New Components)

#### 2.1 `AudioFingerprintingUseCase`
This new use case will:
*   Take a track ID or audio path as input.
*   Execute a lightweight ML model or feature extraction algorithm to generate a unique acoustic fingerprint.
*   Return this fingerprint to the `MatchSearchUseCase`.

#### 2.2 `MatchSearchUseCase`
This new use case will:
*   Receive the fingerprint.
*   Coordinate with a `MatchSearchWorker` to call the MusicBrainz API.
*   Perform the scoring/matching logic against the retrieved candidates.
*   Filter candidates based on the 90% confidence threshold.
*   Return a list of `SuggestedMetadataItem` objects.

### 3. Integration Workflow (Modification of Existing Code)

#### 3.1 `JanitorScanUseCase` (Modification)
The scan process will be modified to include the following steps for each track:
1.  **Check for missing metadata** (Existing logic).
2.  **Trigger `AudioFingerprintingUseCase`** (New).
3.  **Trigger `MatchSearchUseCase`** (New).
4.  **Queue Suggestions:** If the `MatchSearchUseCase` returns a list of suggestions, they will be added to the `JanitorUpdateItem` results as a `Suggestion` type.
5.  **Queue Auto-Fix:** If metadata is missing *and* the audio fingerprinting is inconclusive/unsupported, the existing `CloudMetadataResolver` can still be used for a standard API lookup.

#### 3.2 `MetadataUpdateUseCase` (Modification)
The `permanentUpdateSongMetadata` function will be modified to accept a `SuggestedMetadataItem`. When triggered by the user, it will use the existing `MediaStore` write logic to apply the *suggested* data.

#### 3.3 `LibraryJanitorFragment` (UI/UX)
*   **Suggestion Display:** The fragment's UI logic must be updated to recognize `JanitorUpdateItem.Suggestion`.
*   **User Flow:** Instead of an immediate auto-fix, the UI will present a dedicated "Review and Accept" screen listing the suggested matches for the user to choose from.

## ✅ Verification Plan
1.  **Unit Tests:** Ensure fingerprint generation is deterministic and matching logic correctly applies the 90% threshold.
2.  **Integration Tests:** Verify the end-to-end flow: Track $\rightarrow$ Fingerprint $\rightarrow$ MusicBrainz $\rightarrow$ UI Suggestion $\rightarrow$ User Acceptance $\rightarrow$ MediaStore Update.
3.  **Performance:** Verify the heavy processing runs asynchronously and doesn't impact the UI performance during the library scan.

---