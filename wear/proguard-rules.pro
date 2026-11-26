# Media3 / ExoPlayer rules
-keep class androidx.media3.session.** { *; }
-keep class androidx.media3.common.** { *; }

# Room rules
-keep class * extends androidx.room.RoomDatabase
-dontwarn androidx.room.paging.**

# Shared domain models (Room entities used for serialization)
-keep class com.music.myusiq.domain.models.** { *; }

# Wearable Data Layer listener callbacks
-keep class com.music.myusiq.wear.service.** { *; }

# Kotlin Coroutines
-keepnames class kotlinx.coroutines.internal.MainDispatcherFactory {}
-keepnames class kotlinx.coroutines.CoroutineExceptionHandler {}

# Strip debug logs
-assumenosideeffects class android.util.Log {
    public static boolean isLoggable(java.lang.String, int);
    public static int v(...);
    public static int d(...);
    public static int i(...);
}
