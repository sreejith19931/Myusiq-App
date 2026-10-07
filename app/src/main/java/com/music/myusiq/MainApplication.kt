package com.music.myusiq

import android.app.Application
import android.content.Context
import android.util.Log
import com.google.android.material.color.DynamicColors
import com.music.myusiq.data.AppDatabase
import com.music.myusiq.service.PlaybackPersistence
import com.music.myusiq.ui.ThemeSettingsManager
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first

class MainApplication : Application() {
    val database: AppDatabase by lazy { DatabaseProvider.getDatabase(this) }
    companion object {
        lateinit var appContext: Context
            private set
    }
    val playbackPersistence: PlaybackPersistence by lazy {
        PlaybackPersistence(this)
    }
    override fun onCreate() {
        super.onCreate()
        appContext = applicationContext

        // Apply Dynamic Colors globally (Material You support)
        DynamicColors.applyToActivitiesIfAvailable(this)

        val themeSettingsManager = ThemeSettingsManager(this)

        // Use runBlocking for the initial theme load to prevent flickering on startup.
        // This ensures the correct night mode is set before the first Activity is created.
        runBlocking {
            try {
                val mode = themeSettingsManager.themeModeFlow.first()
                themeSettingsManager.applyThemeMode(mode)
            } catch (e: Exception) {
                Log.e("MainApplication", "Error setting initial theme", e)
            }
        }

        // Still listen for future changes asynchronously
        MainScope().launch {
            themeSettingsManager.themeModeFlow.collect { mode ->
                themeSettingsManager.applyThemeMode(mode)
            }
        }
    }
}