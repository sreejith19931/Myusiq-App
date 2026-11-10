package com.music.myusiq

import android.app.Application
import android.content.Context
import com.music.myusiq.data.AppDatabase
import com.music.myusiq.service.PlaybackPersistence
import com.music.myusiq.ui.ThemeSettingsManager
import kotlinx.coroutines.*

class MainApplication : Application() {
    private val applicationScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

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

        val themeSettingsManager = ThemeSettingsManager(this)

        // Apply cached theme mode immediately from SharedPreferences without blocking main thread on DataStore
        val cachedMode = getSharedPreferences("app_prefs", Context.MODE_PRIVATE)
            .getString("cached_theme_mode", "system") ?: "system"
        themeSettingsManager.applyThemeMode(cachedMode)

        // Asynchronously observe future theme preference updates
        applicationScope.launch {
            themeSettingsManager.themeModeFlow.collect { mode ->
                themeSettingsManager.applyThemeMode(mode)
            }
        }
    }

    override fun onTerminate() {
        super.onTerminate()
        applicationScope.cancel()
    }
}