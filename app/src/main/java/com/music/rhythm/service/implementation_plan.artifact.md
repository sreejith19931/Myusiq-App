# Fix Volume Spike during Crossfade

When a crossfade occurs, the next track briefly plays at full volume before being faded in. This is caused by the `onMediaItemTransition` listener resetting the volume to full when it detects a manual track transition (which is how the crossfade is implemented using a second player).

## Proposed Changes

### Playback Service

#### [MODIFY] [PlaybackService.kt](file:///C:/Users/Sreejith/AndroidStudioProjects/MySimpleMusicPlayer/app/src/main/java/com/music/rhythm/service/PlaybackService.kt)

- Update `onMediaItemTransition` in `setupPlayer` to prevent volume resets if a crossfade animation is currently active.
- Specifically, ensure that when the "next" player starts playing during a crossfade, it doesn't hit the `else` block that sets `volume = 1.0f`.

## Verification Plan

### Manual Verification
- Enable Crossfade in settings.
- Play a track and wait for it to transition to the next track via crossfade.
- Observe the transition to ensure there is no loud "pop" or volume spike at the start of the new track.
- Verify that manual track changes (clicking Next) still behave correctly (either immediate volume or fade-in if enabled).
