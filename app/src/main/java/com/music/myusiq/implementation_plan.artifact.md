# Implementation Plan - "Premium Features" Expansion

Following the analysis of the codebase, I will implement the remaining "Premium" features to complete the feature set of the Rhythm music player.

## User Review Required

> [!NOTE]
> The "Wear OS" app and "Android Auto" full browsing require significant architectural changes (new modules/complex adapters). I will focus on the phone app features first: Shake to Shuffle, Homescreen Widgets, and Shared Element Transitions.

## Proposed Changes

### [app](file:///C:/Users/Sreejith/AndroidStudioProjects/MySimpleMusicPlayer/app)

#### [NEW] [RhythmWidgetProvider.kt](file:///C:/Users/Sreejith/AndroidStudioProjects/MySimpleMusicPlayer/app/src/main/java/com/music/rhythm/RhythmWidgetProvider.kt)
- Implement an `AppWidgetProvider` that allows basic playback control (Play/Pause, Next, Previous) and displays current song info.

#### [NEW] [layout_widget_standard.xml](file:///C:/Users/Sreejith/AndroidStudioProjects/MySimpleMusicPlayer/app/src/main/res/layout/layout_widget_standard.xml)
- Design a modern Material 3 widget layout with adaptive colors.

#### [MODIFY] [MainActivity.kt](file:///C:/Users/Sreejith/AndroidStudioProjects/MySimpleMusicPlayer/app/src/main/java/com/music/rhythm/MainActivity.kt)
- **Shake to Shuffle**: Integrate `SensorEventListener` to detect shakes and toggle shuffle mode in the `PlaybackViewModel`.
- **Shared Element Transitions**: Update `navigateToTabFragment` to support `setReorderingAllowed(true)` and `addSharedElement` for a more fluid experience.

#### [MODIFY] [PlaybackViewModel.kt](file:///C:/Users/Sreejith/AndroidStudioProjects/MySimpleMusicPlayer/app/src/main/java/com/music/rhythm/PlaybackViewModel.kt)
- Add "Song Radio" logic if not already fully functional (ensure it fetches similar songs based on genre/artist from the local library).

#### [MODIFY] [AndroidManifest.xml](file:///C:/Users/Sreejith/AndroidStudioProjects/MySimpleMusicPlayer/app/src/main/AndroidManifest.xml)
- Register the new Widget Provider.

## Verification Plan

### Automated Tests
- `./gradlew :app:assembleDebug`

### Manual Verification
- **Shake to Shuffle**: Shake the device while music is playing and verify shuffle state changes.
- **Widget**: Add the widget to the homescreen and test controls.
- **Transitions**: Navigate between tracks and fragments to verify smooth animations.
