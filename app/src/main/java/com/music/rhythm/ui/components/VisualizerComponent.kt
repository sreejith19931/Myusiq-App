package com.music.rhythm.ui.components

import android.content.Context
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.music.rhythm.service.FftEngineManager
import com.music.rhythm.ui.viewmodels.PlaybackViewModel
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

class VisualizerComponent(
    context: Context,
    private val waveformSeekBar: AnimatedWaveSeekBar,
    private val viewModel: PlaybackViewModel,
) {
    private var fftManager: FftEngineManager? = FftEngineManager(context, waveformSeekBar)

    fun setup(lifecycleOwner: LifecycleOwner) {
        lifecycleOwner.lifecycleScope.launch {
            lifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch {
                    viewModel.audioSessionId.collectLatest { id ->
                        if (id != -1) fftManager?.attachToSession(id)
                    }
                }
                launch {
                    viewModel.settingsChangedSignal.collectLatest {
                        val id = viewModel.audioSessionId.value
                        if (id != -1) fftManager?.attachToSession(id)
                    }
                }
                launch {
                    viewModel.currentTrackWaveform.collectLatest { waveform ->
                        waveformSeekBar.setSampleAmplitudes(waveform)
                    }
                }
                launch {
                    viewModel.isPlaying.collectLatest { isPlaying ->
                        waveformSeekBar.isPlaying = isPlaying
                    }
                }
                launch {
                    viewModel.currentPosition.collectLatest { pos ->
                        val dur = viewModel.currentPlayingSong.value?.duration ?: 0
                        if (dur > 0) waveformSeekBar.progress = pos.toFloat() / dur.toFloat()
                    }
                }
            }
        }
    }

    fun release() {
        fftManager?.release()
        fftManager = null
    }
}
