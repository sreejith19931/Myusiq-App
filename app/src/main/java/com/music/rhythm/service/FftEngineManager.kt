package com.music.rhythm.service

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.media.audiofx.Visualizer
import com.music.rhythm.ui.components.AnimatedWaveSeekBar

class FftEngineManager(
    private val context: Context,
    private val waveSeekBar: AnimatedWaveSeekBar,
) {
    private var visualizer: Visualizer? = null
    private var currentAudioSessionId: Int = -1

    fun attachToSession(audioSessionId: Int) {
        if (audioSessionId <= 0) {
            release()
            return
        }
        val sharedPreferences = context.getSharedPreferences("app_prefs", Context.MODE_PRIVATE)
        val isFeatureEnabled = sharedPreferences.getBoolean("fft_visualizer_enabled", true)
        val hasPermission = context.checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
        
        if (!isFeatureEnabled || !hasPermission) {
            release()
            return
        }
        
        if ((currentAudioSessionId == audioSessionId) && (visualizer != null)) {
            waveSeekBar.isFftModeEnabled = true
            return
        }
        release()
        waveSeekBar.isFftModeEnabled = true
        currentAudioSessionId = audioSessionId
        try {
            visualizer = Visualizer(audioSessionId).apply {
                captureSize = Visualizer.getCaptureSizeRange()[1]

                setDataCaptureListener(
                    object : Visualizer.OnDataCaptureListener {
                        override fun onWaveFormDataCapture(v: Visualizer?, waveform: ByteArray?, samplingRate: Int) {
                        }

                        override fun onFftDataCapture(v: Visualizer?, fft: ByteArray?, samplingRate: Int) {
                            if (waveSeekBar.isFftModeEnabled) {
                                fft?.let { waveSeekBar.updateFftData(it) }
                            }
                        }
                    },
                    Visualizer.getMaxCaptureRate() / 2,
                    false,
                    true,
                )
                enabled = true
            }
        } catch (e: Exception) {
            e.printStackTrace()
            release()
        }
    }
    fun release() {
        try {
            visualizer?.enabled = false
            visualizer?.release()
        } catch (e: Exception) {
            e.printStackTrace()
        }
        visualizer = null
        currentAudioSessionId = -1
        waveSeekBar.isFftModeEnabled = false
    }
}