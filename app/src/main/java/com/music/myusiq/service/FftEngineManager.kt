package com.music.myusiq.service

import android.content.Context
import android.media.audiofx.Visualizer
import android.util.Log

class FftEngineManager(
    private val context: Context,
    private val onFftDataCaptured: ((ByteArray) -> Unit)? = null,
) {
    constructor(
        context: Context,
        waveSeekBar: com.music.myusiq.ui.components.AnimatedWaveSeekBar,
    ) : this(
        context,
        onFftDataCaptured = { fft ->
            if (waveSeekBar.isFftModeEnabled) {
                waveSeekBar.updateFftData(fft)
            }
        },
    ) {
        this.targetWaveSeekBar = waveSeekBar
    }

    private var targetWaveSeekBar: com.music.myusiq.ui.components.AnimatedWaveSeekBar? = null
    private var visualizer: Visualizer? = null
    private var currentAudioSessionId: Int = -1

    fun attachToSession(audioSessionId: Int) {
        if (audioSessionId <= 0) {
            release()
            return
        }
        val sharedPreferences = context.getSharedPreferences("app_prefs", Context.MODE_PRIVATE)
        val isFeatureEnabled = sharedPreferences.getBoolean("fft_visualizer_enabled", true)

        if (!isFeatureEnabled) {
            release()
            return
        }

        if ((currentAudioSessionId == audioSessionId) && (visualizer != null)) {
            targetWaveSeekBar?.isFftModeEnabled = true
            return
        }
        release()
        targetWaveSeekBar?.isFftModeEnabled = true
        currentAudioSessionId = audioSessionId
        try {
            visualizer = Visualizer(audioSessionId).apply {
                val captureRange = runCatching { Visualizer.getCaptureSizeRange() }.getOrNull()
                captureSize = if (captureRange != null && captureRange.size >= 2) {
                    captureRange[1]
                } else {
                    512
                }

                setDataCaptureListener(
                    object : Visualizer.OnDataCaptureListener {
                        override fun onWaveFormDataCapture(v: Visualizer?, waveform: ByteArray?, samplingRate: Int) {
                        }

                        override fun onFftDataCapture(v: Visualizer?, fft: ByteArray?, samplingRate: Int) {
                            fft?.let { onFftDataCaptured?.invoke(it) }
                        }
                    },
                    Visualizer.getMaxCaptureRate() / 2,
                    false,
                    true,
                )
                enabled = true
            }
        } catch (e: Exception) {
            Log.e("FftEngineManager", "Error attaching visualizer to session $audioSessionId", e)
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
        targetWaveSeekBar?.isFftModeEnabled = false
    }
}