package com.music.myusiq.service

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

    private var equalizerSecondary: Equalizer? = null
    private var secondaryAudioSessionId: Int = 0

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
        if (audioSessionId == 0) return
        
        // If it's already the primary, nothing to do
        if (currentAudioSessionId == audioSessionId && equalizer != null) return
        
        // If it's the secondary, promote it to primary
        if (secondaryAudioSessionId == audioSessionId && equalizerSecondary != null) {
            val oldEq = equalizer
            val oldId = currentAudioSessionId
            
            equalizer = equalizerSecondary
            currentAudioSessionId = secondaryAudioSessionId
            
            equalizerSecondary = oldEq
            secondaryAudioSessionId = oldId
            return
        }

        // Otherwise, initialize it as the primary and move the current primary to secondary
        // This ensures the outgoing track keeps its EQ during crossfade if the new track becomes primary
        try {
            val oldSecondaryEq = equalizerSecondary
            equalizerSecondary = equalizer
            secondaryAudioSessionId = currentAudioSessionId
            
            // Release the old secondary if it exists
            try {
                oldSecondaryEq?.enabled = false
                oldSecondaryEq?.release()
            } catch (e: Exception) { Log.e("EqualizerManager", "Error releasing old secondary EQ", e) }

            currentAudioSessionId = audioSessionId
            try {
                val newEq = Equalizer(0, audioSessionId)
                equalizer = newEq
                loadProfileSettings(context.applicationContext, currentActiveProfile)
            } catch (e: Exception) {
                Log.e("EqualizerManager", "Failed to create Equalizer instance", e)
                equalizer = null
            }
        } catch (e: Exception) {
            Log.e("EqualizerManager", "Error in initEqualizer wrapper", e)
        }
    }

    private fun applyToInstance(eq: Equalizer?, context: Context, presetName: String, isEnabled: Boolean) {
        eq?.let {
            try {
                it.enabled = isEnabled
                if (presetName == "Custom") {
                    for (i in 0 until it.numberOfBands) {
                        val bandIndex = i.toShort()
                        val savedLevel = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                            .getInt("eq_prof_${currentActiveProfile}_custom_band_$bandIndex", 0)
                        it.setBandLevel(bandIndex, savedLevel.toShort())
                    }
                } else {
                    presetsMap[presetName]?.let { gains ->
                        for (i in 0 until it.numberOfBands) {
                            val gain = if (i < gains.size) gains[i] else 0.toShort()
                            it.setBandLevel(i.toShort(), gain)
                        }
                    }
                }
            } catch (e: Exception) { Log.e("EqualizerManager", "Error applying settings to instance", e) }
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
        val activePresetName = prefs.getString("eq_prof_${profileIndex}_active_preset", "Flat") ?: "Flat"
        
        _isEqualizerEnabled.value = isEnabled
        applyToInstance(equalizer, context, activePresetName, isEnabled)
        applyToInstance(equalizerSecondary, context, activePresetName, isEnabled)
    }

    fun applyPresetToHardware(context: Context, presetName: String, saveState: Boolean = true): Boolean {
        if (!presetsMap.containsKey(presetName)) return false
        
        val isEnabled = _isEqualizerEnabled.value
        applyToInstance(equalizer, context, presetName, isEnabled)
        applyToInstance(equalizerSecondary, context, presetName, isEnabled)

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
            equalizerSecondary?.enabled = isEnabled
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
            equalizerSecondary?.setBandLevel(band, level)

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
            equalizerSecondary?.release()
        } catch (e: Exception) {
            Log.e("EqualizerManager", "Error final release", e)
        }
        equalizer = null
        equalizerSecondary = null
        currentAudioSessionId = 0
        secondaryAudioSessionId = 0
        _isEqualizerEnabled.value = false
    }
}