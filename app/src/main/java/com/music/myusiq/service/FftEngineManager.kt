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
    private var isPaused: Boolean = false

    fun setPaused(paused: Boolean) {
        if (isPaused == paused) return
        isPaused = paused
        try {
            if (paused) {
                visualizer?.enabled = false
            } else if (currentAudioSessionId > 0) {
                visualizer?.enabled = true
            }
        } catch (e: Exception) {
            Log.e("FftEngineManager", "Error setting visualizer paused state: $paused", e)
        }
    }

    fun isPaused(): Boolean = isPaused

    fun attachToSession(audioSessionId: Int) {
        if (audioSessionId <= 0) {
            release()
            return
        }
        if (targetWaveSeekBar != null) {
            val sharedPreferences = context.getSharedPreferences("app_prefs", Context.MODE_PRIVATE)
            val isFeatureEnabled = sharedPreferences.getBoolean("fft_visualizer_enabled", false)
            if (!isFeatureEnabled) {
                release()
                return
            }
        }

        if ((currentAudioSessionId == audioSessionId) && (visualizer != null)) {
            targetWaveSeekBar?.isFftModeEnabled = true
            if (!isPaused) {
                try {
                    visualizer?.enabled = true
                } catch (_: Exception) {}
            }
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
                            if (!isPaused) {
                                fft?.let { onFftDataCaptured?.invoke(it) }
                            }
                        }
                    },
                    Visualizer.getMaxCaptureRate() / 2,
                    false,
                    true,
                )
                enabled = !isPaused
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
