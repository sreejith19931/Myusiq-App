package com.music.myusiq.service

import android.content.Context
import android.media.audiofx.BassBoost
import android.media.audiofx.Equalizer
import android.media.audiofx.LoudnessEnhancer
import android.media.audiofx.PresetReverb
import android.media.audiofx.Virtualizer
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
    private var bassBoost: BassBoost? = null
    @Suppress("DEPRECATION")
    private var virtualizer: Virtualizer? = null
    private var presetReverb: PresetReverb? = null
    private var loudnessEnhancer: LoudnessEnhancer? = null

    private var currentAudioSessionId: Int = 0
    private const val PREFS_NAME = "music_player_equalizer_settings"
    const val PROFILE_SPEAKER = 0
    const val PROFILE_WIRED = 1
    const val PROFILE_BLUETOOTH = 2

    private var equalizerSecondary: Equalizer? = null
    private var bassBoostSecondary: BassBoost? = null
    @Suppress("DEPRECATION")
    private var virtualizerSecondary: Virtualizer? = null
    private var presetReverbSecondary: PresetReverb? = null
    private var loudnessEnhancerSecondary: LoudnessEnhancer? = null
    private var secondaryAudioSessionId: Int = 0

    private var currentActiveProfile = PROFILE_SPEAKER

    private val _isEqualizerEnabled = MutableStateFlow(value = false)
    val isEqualizerEnabled: StateFlow<Boolean> = _isEqualizerEnabled.asStateFlow()
    private val _activeHardwareRoute = MutableStateFlow(PROFILE_SPEAKER)
    val activeHardwareRoute: StateFlow<Int> = _activeHardwareRoute.asStateFlow()

    private val _bassBoostStrength = MutableStateFlow<Short>(0)
    val bassBoostStrength: StateFlow<Short> = _bassBoostStrength.asStateFlow()

    private val _virtualizerStrength = MutableStateFlow<Short>(0)
    val virtualizerStrength: StateFlow<Short> = _virtualizerStrength.asStateFlow()

    private val _presetReverbPreset = MutableStateFlow<Short>(0)
    val presetReverbPreset: StateFlow<Short> = _presetReverbPreset.asStateFlow()

    private val _loudnessGain = MutableStateFlow(0)
    val loudnessGain: StateFlow<Int> = _loudnessGain.asStateFlow()

    private val storageIoScope = CoroutineScope(Dispatchers.IO + Job())

    val presetsMap = mapOf(
        "Flat"         to shortArrayOf(0, 0, 0, 0, 0, 0, 0, 0, 0, 0),
        "Classical"    to shortArrayOf(500, 400, 300, 200, -100, -200, 200, 400, 500, 600),
        "Pop"          to shortArrayOf(-200, -100, 0, 200, 400, 500, 300, 100, -100, -200),
        "Rock"         to shortArrayOf(600, 500, 300, 100, -100, -200, 100, 300, 500, 700),
        "Bass Boost"   to shortArrayOf(900, 800, 600, 400, 200, 0, 0, 0, 0, 0),
        "Treble Boost" to shortArrayOf(0, 0, 0, 0, 0, 200, 400, 600, 800, 1000),
        "Vocal Focus"  to shortArrayOf(-400, -300, -100, 200, 500, 600, 400, 100, -200, -300),
        "Custom"       to shortArrayOf(0, 0, 0, 0, 0, 0, 0, 0, 0, 0),
    )

    fun initEqualizer(context: Context, audioSessionId: Int) {
        if (audioSessionId == 0) return
        
        // If it's already the primary, nothing to do
        if (currentAudioSessionId == audioSessionId && equalizer != null) return
        
        // If it's the secondary, promote it to primary
        if (secondaryAudioSessionId == audioSessionId && equalizerSecondary != null) {
            val oldEq = equalizer
            val oldBb = bassBoost
            val oldVirt = virtualizer
            val oldRev = presetReverb
            val oldLoud = loudnessEnhancer
            val oldId = currentAudioSessionId
            
            equalizer = equalizerSecondary
            bassBoost = bassBoostSecondary
            virtualizer = virtualizerSecondary
            presetReverb = presetReverbSecondary
            loudnessEnhancer = loudnessEnhancerSecondary
            currentAudioSessionId = secondaryAudioSessionId
            
            equalizerSecondary = oldEq
            bassBoostSecondary = oldBb
            virtualizerSecondary = oldVirt
            presetReverbSecondary = oldRev
            loudnessEnhancerSecondary = oldLoud
            secondaryAudioSessionId = oldId
            return
        }

        // Otherwise, initialize it as the primary and move the current primary to secondary
        try {
            releaseSecondaryInstance()

            equalizerSecondary = equalizer
            bassBoostSecondary = bassBoost
            virtualizerSecondary = virtualizer
            presetReverbSecondary = presetReverb
            loudnessEnhancerSecondary = loudnessEnhancer
            secondaryAudioSessionId = currentAudioSessionId

            currentAudioSessionId = audioSessionId
            try {
                equalizer = Equalizer(0, audioSessionId)
                
                try {
                    bassBoost = BassBoost(0, audioSessionId)
                } catch (e: Exception) {
                    Log.e("EqualizerManager", "Failed to create BassBoost instance", e)
                    bassBoost = null
                }

                try {
                    @Suppress("DEPRECATION")
                    virtualizer = Virtualizer(0, audioSessionId)
                } catch (e: Exception) {
                    Log.e("EqualizerManager", "Failed to create Virtualizer instance", e)
                    virtualizer = null
                }

                try {
                    presetReverb = PresetReverb(0, audioSessionId)
                } catch (e: Exception) {
                    Log.e("EqualizerManager", "Failed to create PresetReverb instance", e)
                    presetReverb = null
                }

                try {
                    loudnessEnhancer = LoudnessEnhancer(audioSessionId)
                } catch (e: Exception) {
                    Log.e("EqualizerManager", "Failed to create LoudnessEnhancer instance", e)
                    loudnessEnhancer = null
                }

                loadProfileSettings(context.applicationContext, currentActiveProfile)
            } catch (e: Exception) {
                Log.e("EqualizerManager", "Failed to create Equalizer instance", e)
                equalizer = null
            }
        } catch (e: Exception) {
            Log.e("EqualizerManager", "Error in initEqualizer wrapper", e)
        }
    }

    private fun releaseSecondaryInstance() {
        try {
            equalizerSecondary?.enabled = false
            equalizerSecondary?.release()
            bassBoostSecondary?.enabled = false
            bassBoostSecondary?.release()
            virtualizerSecondary?.enabled = false
            virtualizerSecondary?.release()
            presetReverbSecondary?.enabled = false
            presetReverbSecondary?.release()
            loudnessEnhancerSecondary?.enabled = false
            loudnessEnhancerSecondary?.release()
        } catch (e: Exception) {
            Log.e("EqualizerManager", "Error releasing old secondary AudioFX", e)
        }
        equalizerSecondary = null
        bassBoostSecondary = null
        virtualizerSecondary = null
        presetReverbSecondary = null
        loudnessEnhancerSecondary = null
    }

    private fun applyToInstance(
        eq: Equalizer?,
        bb: BassBoost?,
        virt: Virtualizer?,
        rev: PresetReverb?,
        loud: LoudnessEnhancer?,
        context: Context,
        presetName: String,
        isEnabled: Boolean
    ) {
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
                    val all = getAllPresets(context)
                    all[presetName]?.let { gains ->
                        for (i in 0 until it.numberOfBands) {
                            val gain = if (i < gains.size) gains[i] else 0.toShort()
                            it.setBandLevel(i.toShort(), gain)
                        }
                    }
                }
            } catch (e: Exception) {
                Log.e("EqualizerManager", "Error applying EQ settings", e)
            }
        }

        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

        // Bass Boost
        val savedBass = prefs.getInt("eq_prof_${currentActiveProfile}_bass_boost", 0).toShort()
        _bassBoostStrength.value = savedBass
        bb?.let {
            try {
                if (it.strengthSupported) {
                    it.setStrength(savedBass)
                    it.enabled = isEnabled && savedBass > 0
                }
            } catch (e: Exception) {
                Log.e("EqualizerManager", "Error applying BassBoost", e)
            }
        }

        // Virtualizer
        val savedVirt = prefs.getInt("eq_prof_${currentActiveProfile}_virtualizer", 0).toShort()
        _virtualizerStrength.value = savedVirt
        virt?.let {
            try {
                if (it.strengthSupported) {
                    it.setStrength(savedVirt)
                    it.enabled = isEnabled && savedVirt > 0
                }
            } catch (e: Exception) {
                Log.e("EqualizerManager", "Error applying Virtualizer", e)
            }
        }

        // Preset Reverb
        val savedRev = prefs.getInt("eq_prof_${currentActiveProfile}_reverb", 0).toShort()
        _presetReverbPreset.value = savedRev
        rev?.let {
            try {
                it.preset = savedRev
                it.enabled = isEnabled && savedRev > 0
            } catch (e: Exception) {
                Log.e("EqualizerManager", "Error applying PresetReverb", e)
            }
        }

        // Loudness Enhancer
        val savedLoud = prefs.getInt("eq_prof_${currentActiveProfile}_loudness", 0)
        _loudnessGain.value = savedLoud
        loud?.let {
            try {
                it.setTargetGain(savedLoud)
                it.enabled = isEnabled && savedLoud > 0
            } catch (e: Exception) {
                Log.e("EqualizerManager", "Error applying LoudnessEnhancer", e)
            }
        }
    }

    fun setBassBoostStrength(context: Context, strength: Short) {
        _bassBoostStrength.value = strength
        try {
            bassBoost?.let {
                if (it.strengthSupported) {
                    it.setStrength(strength)
                    it.enabled = _isEqualizerEnabled.value && strength > 0
                }
            }
            bassBoostSecondary?.let {
                if (it.strengthSupported) {
                    it.setStrength(strength)
                    it.enabled = _isEqualizerEnabled.value && strength > 0
                }
            }
        } catch (e: Exception) {
            Log.e("EqualizerManager", "Error setting BassBoost strength", e)
        }

        val appContext = context.applicationContext
        storageIoScope.launch {
            val prefs = appContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            prefs.edit(commit = false) {
                putInt("eq_prof_${currentActiveProfile}_bass_boost", strength.toInt())
            }
        }
    }

    fun setVirtualizerStrength(context: Context, strength: Short) {
        _virtualizerStrength.value = strength
        try {
            virtualizer?.let {
                if (it.strengthSupported) {
                    it.setStrength(strength)
                    it.enabled = _isEqualizerEnabled.value && strength > 0
                }
            }
            virtualizerSecondary?.let {
                if (it.strengthSupported) {
                    it.setStrength(strength)
                    it.enabled = _isEqualizerEnabled.value && strength > 0
                }
            }
        } catch (e: Exception) {
            Log.e("EqualizerManager", "Error setting Virtualizer strength", e)
        }

        val appContext = context.applicationContext
        storageIoScope.launch {
            val prefs = appContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            prefs.edit(commit = false) {
                putInt("eq_prof_${currentActiveProfile}_virtualizer", strength.toInt())
            }
        }
    }

    fun setPresetReverb(context: Context, preset: Short) {
        _presetReverbPreset.value = preset
        try {
            presetReverb?.let {
                it.preset = preset
                it.enabled = _isEqualizerEnabled.value && preset > 0
            }
            presetReverbSecondary?.let {
                it.preset = preset
                it.enabled = _isEqualizerEnabled.value && preset > 0
            }
        } catch (e: Exception) {
            Log.e("EqualizerManager", "Error setting PresetReverb", e)
        }

        val appContext = context.applicationContext
        storageIoScope.launch {
            val prefs = appContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            prefs.edit(commit = false) {
                putInt("eq_prof_${currentActiveProfile}_reverb", preset.toInt())
            }
        }
    }

    fun setLoudnessGain(context: Context, gainmB: Int) {
        _loudnessGain.value = gainmB
        try {
            loudnessEnhancer?.let {
                it.setTargetGain(gainmB)
                it.enabled = _isEqualizerEnabled.value && gainmB > 0
            }
            loudnessEnhancerSecondary?.let {
                it.setTargetGain(gainmB)
                it.enabled = _isEqualizerEnabled.value && gainmB > 0
            }
        } catch (e: Exception) {
            Log.e("EqualizerManager", "Error setting LoudnessEnhancer gain", e)
        }

        val appContext = context.applicationContext
        storageIoScope.launch {
            val prefs = appContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            prefs.edit(commit = false) {
                putInt("eq_prof_${currentActiveProfile}_loudness", gainmB)
            }
        }
    }

    fun getUserCustomPresets(context: Context): Map<String, ShortArray> {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val jsonStr = prefs.getString("user_custom_presets_json", "{}") ?: "{}"
        val map = mutableMapOf<String, ShortArray>()
        try {
            val json = org.json.JSONObject(jsonStr)
            val keys = json.keys()
            while (keys.hasNext()) {
                val key = keys.next()
                val array = json.getJSONArray(key)
                val shorts = ShortArray(array.length())
                for (i in 0 until array.length()) {
                    shorts[i] = array.getInt(i).toShort()
                }
                map[key] = shorts
            }
        } catch (_: Exception) {}
        return map
    }

    fun saveUserCustomPreset(context: Context, presetName: String, bandLevels: ShortArray) {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val current = getUserCustomPresets(context).toMutableMap()
        current[presetName] = bandLevels
        val json = org.json.JSONObject()
        current.forEach { (k, v) ->
            val arr = org.json.JSONArray()
            v.forEach { arr.put(it.toInt()) }
            json.put(k, arr)
        }
        prefs.edit { putString("user_custom_presets_json", json.toString()) }
    }

    fun deleteUserCustomPreset(context: Context, presetName: String) {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val current = getUserCustomPresets(context).toMutableMap()
        current.remove(presetName)
        val json = org.json.JSONObject()
        current.forEach { (k, v) ->
            val arr = org.json.JSONArray()
            v.forEach { arr.put(it.toInt()) }
            json.put(k, arr)
        }
        prefs.edit { putString("user_custom_presets_json", json.toString()) }
    }

    fun getAllPresets(context: Context): Map<String, ShortArray> {
        return presetsMap + getUserCustomPresets(context)
    }

    fun updateDetectedHardwareRoute(profileIndex: Int) {
        _activeHardwareRoute.value = profileIndex
    }

    fun detectActiveAudioProfile(audioManager: android.media.AudioManager): Int {
        val devices = audioManager.getDevices(android.media.AudioManager.GET_DEVICES_OUTPUTS)
        for (device in devices) {
            when (device.type) {
                android.media.AudioDeviceInfo.TYPE_BLUETOOTH_A2DP,
                android.media.AudioDeviceInfo.TYPE_BLUETOOTH_SCO,
                android.media.AudioDeviceInfo.TYPE_HEARING_AID,
                android.media.AudioDeviceInfo.TYPE_BLE_HEADSET,
                android.media.AudioDeviceInfo.TYPE_BLE_SPEAKER -> return PROFILE_BLUETOOTH
                
                android.media.AudioDeviceInfo.TYPE_WIRED_HEADSET,
                android.media.AudioDeviceInfo.TYPE_WIRED_HEADPHONES,
                android.media.AudioDeviceInfo.TYPE_USB_HEADSET,
                android.media.AudioDeviceInfo.TYPE_USB_DEVICE -> return PROFILE_WIRED
                
                else -> {}
            }
        }
        return PROFILE_SPEAKER
    }

    fun handleAudioDeviceRouting(context: Context, audioManager: android.media.AudioManager) {
        val detectedProfile = detectActiveAudioProfile(audioManager)
        _activeHardwareRoute.value = detectedProfile
        if (currentActiveProfile != detectedProfile) {
            switchProfile(context, detectedProfile)
            Log.d("EqualizerManager", "Auto-switched EQ profile to $detectedProfile")
        }
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
        applyToInstance(
            equalizer, bassBoost, virtualizer, presetReverb, loudnessEnhancer,
            context, activePresetName, isEnabled
        )
        applyToInstance(
            equalizerSecondary, bassBoostSecondary, virtualizerSecondary, presetReverbSecondary, loudnessEnhancerSecondary,
            context, activePresetName, isEnabled
        )
    }

    fun applyPresetToHardware(context: Context, presetName: String, saveState: Boolean = true): Boolean {
        if (!presetsMap.containsKey(presetName)) return false
        
        val isEnabled = _isEqualizerEnabled.value
        applyToInstance(
            equalizer, bassBoost, virtualizer, presetReverb, loudnessEnhancer,
            context, presetName, isEnabled
        )
        applyToInstance(
            equalizerSecondary, bassBoostSecondary, virtualizerSecondary, presetReverbSecondary, loudnessEnhancerSecondary,
            context, presetName, isEnabled
        )

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

            val savedBass = _bassBoostStrength.value
            bassBoost?.enabled = isEnabled && savedBass > 0
            bassBoostSecondary?.enabled = isEnabled && savedBass > 0

            val savedVirt = _virtualizerStrength.value
            virtualizer?.enabled = isEnabled && savedVirt > 0
            virtualizerSecondary?.enabled = isEnabled && savedVirt > 0

            val savedRev = _presetReverbPreset.value
            presetReverb?.enabled = isEnabled && savedRev > 0
            presetReverbSecondary?.enabled = isEnabled && savedRev > 0

            val savedLoud = _loudnessGain.value
            loudnessEnhancer?.enabled = isEnabled && savedLoud > 0
            loudnessEnhancerSecondary?.enabled = isEnabled && savedLoud > 0
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
            releaseSecondaryInstance()
            bassBoost?.release()
            virtualizer?.release()
            presetReverb?.release()
            loudnessEnhancer?.release()
        } catch (e: Exception) {
            Log.e("EqualizerManager", "Error final release", e)
        }
        equalizer = null
        bassBoost = null
        virtualizer = null
        presetReverb = null
        loudnessEnhancer = null
        equalizerSecondary = null
        currentAudioSessionId = 0
        secondaryAudioSessionId = 0
        _isEqualizerEnabled.value = false
    }
}