package com.music.myusiq.ui.components

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.activityViewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.google.android.material.bottomsheet.BottomSheetDialogFragment
import com.google.android.material.button.MaterialButton
import com.google.android.material.chip.Chip
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import com.music.myusiq.R
import com.music.myusiq.service.FftEngineManager
import com.music.myusiq.ui.viewmodels.PlaybackViewModel
import com.music.myusiq.utils.ThemeUtils

class VisualizerStudioBottomSheet : BottomSheetDialogFragment() {

    private val viewModel: PlaybackViewModel by activityViewModels()
    private lateinit var visualizerView: FullscreenVisualizerView
    private lateinit var btnClose: MaterialButton
    private var fftEngineManager: FftEngineManager? = null

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View? {
        return inflater.inflate(R.layout.dialog_visualizer_studio, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        visualizerView = view.findViewById(R.id.visualizerView)
        btnClose = view.findViewById(R.id.btnClose)

        val chipSpectrum = view.findViewById<Chip>(R.id.chipSpectrum)
        val chipCircular = view.findViewById<Chip>(R.id.chipCircular)
        val chipWaveform = view.findViewById<Chip>(R.id.chipWaveform)
        val chipParticles = view.findViewById<Chip>(R.id.chipParticles)

        chipSpectrum.setOnClickListener { visualizerView.currentMode = FullscreenVisualizerView.MODE_SPECTRUM }
        chipCircular.setOnClickListener { visualizerView.currentMode = FullscreenVisualizerView.MODE_CIRCULAR }
        chipWaveform.setOnClickListener { visualizerView.currentMode = FullscreenVisualizerView.MODE_WAVEFORM }
        chipParticles.setOnClickListener { visualizerView.currentMode = FullscreenVisualizerView.MODE_PARTICLES }

        btnClose.setOnClickListener { dismiss() }

        fftEngineManager = FftEngineManager(requireContext()) { fft ->
            visualizerView.updateFftData(fft)
        }

        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch {
                    viewModel.audioSessionId.collectLatest { sessionId ->
                        if (sessionId > 0) {
                            fftEngineManager?.attachToSession(sessionId)
                        }
                    }
                }
                launch {
                    viewModel.currentVibrantColor.collectLatest { color ->
                        val accent = color ?: ThemeUtils.getThemeColor(requireContext(), android.R.attr.colorPrimary)
                        visualizerView.accentColor = accent
                    }
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        fftEngineManager?.setPaused(false)
    }

    override fun onPause() {
        fftEngineManager?.setPaused(true)
        super.onPause()
    }

    override fun onDestroyView() {
        fftEngineManager?.release()
        fftEngineManager = null
        super.onDestroyView()
    }

    companion object {
        const val TAG = "VisualizerStudioBottomSheet"
        fun newInstance() = VisualizerStudioBottomSheet()
    }
}
