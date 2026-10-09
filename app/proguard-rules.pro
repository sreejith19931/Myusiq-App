# Media3 / ExoPlayer rules
-keep class androidx.media3.session.** { *; }
-keep class androidx.media3.common.** { *; }
-keep class androidx.media3.exoplayer.** { *; }
-keep class androidx.media3.datasource.** { *; }
-keep class androidx.media3.decoder.** { *; }
-keep class androidx.media3.extractor.** { *; }
-keep class androidx.media3.database.** { *; }
-keep class androidx.media3.container.** { *; }

# Room rules
-keep class * extends androidx.room.RoomDatabase
-dontwarn androidx.room.paging.**

# Glide rules
-keep public class * implements com.bumptech.glide.module.GlideModule
-keep public class * extends com.bumptech.glide.module.AppGlideModule
-keep public enum com.bumptech.glide.load.ImageHeaderParser$** {
  **[] $VALUES;
  public *;
}

# Jsoup rules
-keep class org.jsoup.** { *; }

# Jaudiotagger rules
-keep class net.jthink.jaudiotagger.** { *; }
-dontwarn java.awt.**
-dontwarn javax.imageio.**

# Keep domain models to prevent issues with Room or serialization
-keep class com.music.myusiq.Song { *; }
-keep class com.music.myusiq.TrackEntity { *; }
-keep class com.music.myusiq.Playlist { *; }
-keep class com.music.myusiq.PlaylistWithTracks { *; }
-keep class com.music.myusiq.PlaylistTrackCrossRef { *; }
-keep class com.music.myusiq.SongOverride { *; }
-keep class com.music.myusiq.LyricsOverride { *; }

# Gson rules
-keepattributes Signature
-keepattributes *Annotation*
-dontwarn sun.misc.**
-keep class com.google.gson.** { *; }
-keepclassmembers class * {
    @com.google.gson.annotations.SerializedName <fields>;
}

# Update Manager & GitHub models
-keep class com.music.myusiq.utils.GithubRelease { *; }
-keep class com.music.myusiq.utils.GithubAsset { *; }
-keep class com.music.myusiq.utils.UpdateInfo { *; }
-keep class com.music.myusiq.utils.UpdateCheckResult** { *; }
-keep class com.music.myusiq.utils.UpdateManager { *; }

# Palette rules
-keep class androidx.palette.graphics.** { *; }

# Navigation rules
-keep class androidx.navigation.** { *; }

# DataStore rules
-keep class androidx.datastore.** { *; }

# Kotlin Coroutines
-keepnames class kotlinx.coroutines.internal.MainDispatcherFactory {}
-keepnames class kotlinx.coroutines.CoroutineExceptionHandler {}

# AndroidX Core
-keep class androidx.core.app.CoreComponentFactory { *; }

# Strip debug logs
-assumenosideeffects class android.util.Log {
    public static boolean isLoggable(java.lang.String, int);
    public static int v(...);
    public static int d(...);
    public static int i(...);
    public static int w(...);
    public static int e(...);
}

