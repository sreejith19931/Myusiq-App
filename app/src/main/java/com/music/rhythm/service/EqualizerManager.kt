package com.music.rhythm.service

import android.content.Context
import android.media.audiofx.Equalizer
import android.util.Log
import androidx.core.content.edit
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

object EqualizerManager {
    private var equalizer: Equalizer? = null
    private var currentAudioSessionId: Int = 0
    private const val PREFS_NAME = "music_player_equalizer_settings"
    const val PROFILE_SPEAKER = 0
    const val PROFILE_WIRED = 1
    const val PROFILE_BLUETOOTH = 2

    private var currentActiveProfile = PROFILE_SPEAKER

    private val _isEqualizerEnabled = MutableStateFlow(value = false)
    val isEqualizerEnabled: StateFlow<Boolean> = _isEqualizerEnabled.asStateFlow()
    private val _activeHardwareRoute = MutableStateFlow(PROFILE_SPEAKER)
    val activeHardwareRoute: StateFlow<Int> = _activeHardwareRoute.asStateFlow()
    private val storageIoScope = CoroutineScope(Dispatchers.IO + Job())
    val presetsMap = mapOf(
        "Flat"         to shortArrayOf(0, 0, 0, 0, 0),
        "Classical"    to shortArrayOf(500, 300, -200, 400, 600),
        "Pop"          to shortArrayOf(-200, -100, 300, 400, -100),
        "Rock"         to shortArrayOf(400, 200, -300, 200, 500),
        "Bass Boost"   to shortArrayOf(800, 500, 0, 0, 0),
        "Treble Boost" to shortArrayOf(0, 0, 0, 500, 800),
        "Vocal Focus"  to shortArrayOf(-300, 0, 600, 500, -200),
        "Custom"       to shortArrayOf(0, 0, 0, 0, 0),
    )

    fun initEqualizer(context: Context, audioSessionId: Int) {
        try {
            if (audioSessionId != 0) {
                if ((currentAudioSessionId != audioSessionId) || (equalizer == null)) {
                    try {
                        equalizer?.enabled = false
                        equalizer?.release()
                    } catch (e: Exception) {
                        Log.e("EqualizerManager", "Error releasing old equalizer", e)
                    }
                    
                    currentAudioSessionId = audioSessionId
                    try {
                        equalizer = Equalizer(0, audioSessionId)
                        loadProfileSettings(context.applicationContext, currentActiveProfile)
                    } catch (e: UnsupportedOperationException) {
                        Log.e("EqualizerManager", "Equalizer not supported on this device", e)
                        equalizer = null
                    } catch (e: Exception) {
                        Log.e("EqualizerManager", "Failed to create Equalizer instance", e)
                        equalizer = null
                    }
                }
            }
        } catch (e: Exception) {
            Log.e("EqualizerManager", "Error in initEqualizer wrapper", e)
        }
    }
    fun updateDetectedHardwareRoute(profileIndex: Int) {
        _activeHardwareRoute.value = profileIndex
    }

    fun switchProfile(context: Context, profileIndex: Int) {
        currentActiveProfile = profileIndex
        loadProfileSettings(context.applicationContext, profileIndex)
    }
    private fun loadProfileSettings(context: Context, profileIndex: Int) {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val isEnabled = prefs.getBoolean("eq_enabled_prof_$profileIndex", false)
        _isEqualizerEnabled.value = isEnabled

        try {
            equalizer?.enabled = isEnabled
        } catch (e: Exception) {
            Log.e("EqualizerManager", "Error setting equalizer state", e)
        }

        val activePresetName = prefs.getString("eq_prof_${profileIndex}_active_preset", "Flat") ?: "Flat"
        if (activePresetName == "Custom") {
            val numBands = equalizer?.numberOfBands ?: 0
            for (i in 0 until numBands) {
                val bandIndex = i.toShort()
                val savedLevel = prefs.getInt("eq_prof_${profileIndex}_custom_band_$bandIndex", 0)
                try {
                    equalizer?.setBandLevel(bandIndex, savedLevel.toShort())
                } catch (e: Exception) {
                    Log.e("EqualizerManager", "Error setting band level for band $bandIndex", e)
                }
            }
        } else {
            applyPresetToHardware(context, activePresetName, saveState = false)
        }
    }
    fun applyPresetToHardware(context: Context, presetName: String, saveState: Boolean = true): Boolean {
        val bandGains = presetsMap[presetName] ?: return false
        val numBands = equalizer?.numberOfBands ?: 0

        for (i in 0 until numBands) {
            try {
                val targetGain = if (i < bandGains.size) bandGains[i] else 0.toShort()
                equalizer?.setBandLevel(i.toShort(), targetGain)
            } catch (e: Exception) {
                Log.e("EqualizerManager", "Error applying preset to band $i", e)
            }
        }
        if (saveState) {
            val appContext = context.applicationContext
            storageIoScope.launch {
                val prefs = appContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                prefs.edit(commit = false) {
                    putString("eq_prof_${currentActiveProfile}_active_preset", presetName)
                }
            }
        }
        return true
    }
    fun getActivePresetForProfile(context: Context): String {
        val prefs = context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        return prefs.getString("eq_prof_${currentActiveProfile}_active_preset", "Flat") ?: "Flat"
    }

    fun toggleEqualizer(context: Context, isEnabled: Boolean) {
        _isEqualizerEnabled.value = isEnabled
        try {
            equalizer?.enabled = isEnabled
        } catch (e: Exception) {
            Log.e("EqualizerManager", "Error toggling equalizer", e)
        }

        val appContext = context.applicationContext
        storageIoScope.launch {
            val prefs = appContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            prefs.edit(commit = false) {
                putBoolean("eq_enabled_prof_$currentActiveProfile", isEnabled)
            }
        }
    }

    fun getNumberOfBands(): Short = equalizer?.numberOfBands ?: 0
    fun getCenterFrequency(band: Short): Int = equalizer?.getCenterFreq(band) ?: 0
    fun getBandLevelRange(): ShortArray = equalizer?.bandLevelRange ?: shortArrayOf(-1500, 1500)
    fun getBandLevel(band: Short): Short = equalizer?.getBandLevel(band) ?: 0

    fun setBandLevel(context: Context, band: Short, level: Short) {
        try {
            equalizer?.setBandLevel(band, level)

            val appContext = context.applicationContext
            storageIoScope.launch {
                val prefs = appContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                prefs.edit(commit = false) {
                    putInt("eq_prof_${currentActiveProfile}_custom_band_$band", level.toInt())
                    putString("eq_prof_${currentActiveProfile}_active_preset", "Custom")
                }
            }
        } catch (e: Exception) {
            Log.e("EqualizerManager", "Error setting band level", e)
        }
    }

    fun saveCustomPresetSelection(context: Context, profileIndex: Int) {
        val appContext = context.applicationContext
        storageIoScope.launch {
            val prefs = appContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            prefs.edit(commit = false) {
                putString("eq_prof_${profileIndex}_active_preset", "Custom")
            }
        }
    }
    fun release() {
        try {
            equalizer?.release()
        } catch (e: Exception) {
            Log.e("EqualizerManager", "Error final release", e)
        }
        equalizer = null
        currentAudioSessionId = 0
        _isEqualizerEnabled.value = false
    }
}