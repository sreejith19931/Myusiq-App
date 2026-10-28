package com.music.myusiq.ui

import android.annotation.SuppressLint
import android.content.Context
import android.os.Bundle
import android.util.TypedValue
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.widget.ArrayAdapter
import android.widget.LinearLayout
import android.widget.SeekBar
import android.widget.TextView
import androidx.core.content.edit
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels
import androidx.gridlayout.widget.GridLayout
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.google.android.material.button.MaterialButtonToggleGroup
import com.google.android.material.chip.Chip
import com.google.android.material.materialswitch.MaterialSwitch
import com.google.android.material.textfield.MaterialAutoCompleteTextView
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import com.music.myusiq.service.EqualizerManager
import com.music.myusiq.service.FftEngineManager
import com.music.myusiq.ui.components.EqualizerSpectrumView
import com.music.myusiq.ui.viewmodels.PlaybackViewModel
import com.music.myusiq.R

class EqualizerFragment : Fragment(R.layout.fragment_equalizer) {

    private val viewModel: PlaybackViewModel by activityViewModels()
    private var glPresetContainer: GridLayout? = null
    private var llBandsContainer: LinearLayout? = null
    private var switchEqualizer: MaterialSwitch? = null
    private var spinnerDeviceProfile: MaterialAutoCompleteTextView? = null
    private var llCustomSlidersWrapper: LinearLayout? = null
    private var equalizerSpectrumView: EqualizerSpectrumView? = null
    private var fftEngineManager: FftEngineManager? = null

    private val profiles = arrayOf("Device Speaker", "Wired Headphones", "Bluetooth Audio")

    @SuppressLint("ClickableViewAccessibility")
    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        equalizerSpectrumView = view.findViewById(R.id.equalizerSpectrumView)
        glPresetContainer = view.findViewById(R.id.glPresetContainer)
        llCustomSlidersWrapper = view.findViewById(R.id.llCustomSlidersWrapper)
        llBandsContainer = view.findViewById(R.id.llBandsContainer)
        switchEqualizer = view.findViewById(R.id.switchEqualizer)
        spinnerDeviceProfile = view.findViewById(R.id.spinnerDeviceProfile)

        val context = context ?: return
        val safeAppContext = context.applicationContext
        val adapter = ArrayAdapter(context, android.R.layout.simple_list_item_1, profiles)
        spinnerDeviceProfile?.setAdapter(adapter)

        fftEngineManager = FftEngineManager(safeAppContext) { fft ->
            equalizerSpectrumView?.updateFftData(fft)
        }

        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch {
                    viewModel.audioSessionId.collectLatest { id ->
                        if (id != -1) {
                            fftEngineManager?.attachToSession(id)
                        }
                    }
                }
                launch {
                    EqualizerManager.isEqualizerEnabled.collect { isEnabled ->
                        switchEqualizer?.let {
                            if (it.isChecked != isEnabled) {
                                it.isChecked = isEnabled
                            }
                        }
                        equalizerSpectrumView?.setEqEnabled(isEnabled)
                        togglePresetContainerState(isEnabled)
                    }
                }
                launch {
                    EqualizerManager.activeHardwareRoute.collect { activeRouteIndex ->
                        if (activeRouteIndex in profiles.indices) {
                            spinnerDeviceProfile?.setText(profiles[activeRouteIndex], false)
                            EqualizerManager.switchProfile(safeAppContext, activeRouteIndex)
                            buildPresetInterfaceList()
                        }
                    }
                }
            }
        }

        switchEqualizer?.setOnCheckedChangeListener { _, isChecked ->
            EqualizerManager.toggleEqualizer(safeAppContext, isChecked)
            equalizerSpectrumView?.setEqEnabled(isChecked)
        }

        // Reverb Logic
        val toggleReverb = view.findViewById<MaterialButtonToggleGroup>(R.id.toggleReverb)
        val sharedPreferences = requireContext().getSharedPreferences("app_prefs", Context.MODE_PRIVATE)

        when (sharedPreferences.getInt("reverb_preset", 0)) {
            0 -> toggleReverb.check(R.id.btnReverbNone)
            2 -> toggleReverb.check(R.id.btnReverbRoom)
            5 -> toggleReverb.check(R.id.btnReverbHall)
            6 -> toggleReverb.check(R.id.btnReverbPlate)
        }

        toggleReverb.addOnButtonCheckedListener { _, checkedId, isChecked ->
            if (isChecked) {
                val preset = when (checkedId) {
                    R.id.btnReverbRoom -> 2
                    R.id.btnReverbHall -> 5
                    R.id.btnReverbPlate -> 6
                    else -> 0
                }
                sharedPreferences.edit { putInt("reverb_preset", preset) }
                viewModel.setReverbPreset(preset)
                if (preset > 0 && switchEqualizer?.isChecked == false) {
                    switchEqualizer?.isChecked = true
                }
                EqualizerManager.setPresetReverb(safeAppContext, preset.toShort())
            }
        }

        val sbBassBoost = view.findViewById<SeekBar>(R.id.sbBassBoost)
        val tvBassValue = view.findViewById<TextView>(R.id.tvBassValue)
        val sbVirtualizer = view.findViewById<SeekBar>(R.id.sbVirtualizer)
        val tvVirtualizerValue = view.findViewById<TextView>(R.id.tvVirtualizerValue)

        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch {
                    EqualizerManager.bassBoostStrength.collect { strength ->
                        val percent = (strength.toInt() * 100 / 1000)
                        tvBassValue?.text = "$percent%"
                        if (sbBassBoost?.progress != strength.toInt()) {
                            sbBassBoost?.progress = strength.toInt()
                        }
                    }
                }
                launch {
                    EqualizerManager.virtualizerStrength.collect { strength ->
                        val percent = (strength.toInt() * 100 / 1000)
                        tvVirtualizerValue?.text = "$percent%"
                        if (sbVirtualizer?.progress != strength.toInt()) {
                            sbVirtualizer?.progress = strength.toInt()
                        }
                    }
                }
            }
        }

        sbBassBoost?.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                if (fromUser) {
                    tvBassValue?.text = "${progress * 100 / 1000}%"
                    if (progress > 0 && switchEqualizer?.isChecked == false) {
                        switchEqualizer?.isChecked = true
                    }
                    EqualizerManager.setBassBoostStrength(safeAppContext, progress.toShort())
                }
            }
            override fun onStartTrackingTouch(seekBar: SeekBar?) {}
            override fun onStopTrackingTouch(seekBar: SeekBar?) {}
        })

        sbVirtualizer?.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                if (fromUser) {
                    tvVirtualizerValue?.text = "${progress * 100 / 1000}%"
                    if (progress > 0 && switchEqualizer?.isChecked == false) {
                        switchEqualizer?.isChecked = true
                    }
                    EqualizerManager.setVirtualizerStrength(safeAppContext, progress.toShort())
                }
            }
            override fun onStartTrackingTouch(seekBar: SeekBar?) {}
            override fun onStopTrackingTouch(seekBar: SeekBar?) {}
        })

        spinnerDeviceProfile?.setOnItemClickListener { _, _, position, _ ->
            EqualizerManager.switchProfile(safeAppContext, position)
            buildPresetInterfaceList()
        }
        buildPresetInterfaceList()

        context?.let { ctx ->
            com.music.myusiq.utils.PageGuideManager.showGuideIfNeeded(
                ctx,
                pageKey = "equalizer",
                pageTitle = "Equalizer Guide",
                headerIconRes = R.drawable.ic_equalizer,
                features = listOf(
                    com.music.myusiq.utils.GuideFeatureItem(R.drawable.ic_equalizer, "Hardware Profile Auto-Switching", "Myusiq remembers separate EQ settings when using Device Speaker, Wired Headphones, or Bluetooth Audio."),
                    com.music.myusiq.utils.GuideFeatureItem(R.drawable.ic_speed, "Sound Profiles & Presets", "Tap sound profile chips (Bass Boost, Vocal Focus, Rock, Classical) to quickly switch frequency balances."),
                    com.music.myusiq.utils.GuideFeatureItem(R.drawable.ic_volume_up, "10-Band Slider Control", "Select Custom to manually adjust individual 10-band frequency sliders with smooth animated controls.")
                )
            )
        }
    }

    private fun updateSpectrumCurve() {
        val numBands = EqualizerManager.getNumberOfBands().toInt()
        val gains = ShortArray(numBands)
        for (i in 0 until numBands) {
            gains[i] = EqualizerManager.getBandLevel(i.toShort())
        }
        equalizerSpectrumView?.setBandLevels(gains)
    }

    @SuppressLint("UseKtx")
    private fun buildPresetInterfaceList() {
        val container = glPresetContainer ?: return
        container.removeAllViews()
        val safeContext = context ?: return
        val safeAppContext = safeContext.applicationContext
        val currentlySavedPreset = EqualizerManager.getActivePresetForProfile(safeAppContext)
        val allPresets = EqualizerManager.getAllPresets(safeAppContext)

        for (presetName in allPresets.keys) {
            val chip = Chip(safeContext).apply {
                text = presetName
                val typedValue = TypedValue()
                safeContext.theme.resolveAttribute(com.google.android.material.R.attr.textAppearanceLabelLarge, typedValue, true)
                if (typedValue.resourceId != 0) {
                    setTextAppearance(typedValue.resourceId)
                } else {
                    setTextAppearance(com.google.android.material.R.style.TextAppearance_Material3_LabelLarge)
                }
                isCheckable = true
                isChecked = (presetName == currentlySavedPreset)
                gravity = Gravity.CENTER
                setOnClickListener { view ->
                    val clickedChip = view as Chip
                    for (i in 0 until container.childCount) {
                        val child = container.getChildAt(i) as? Chip
                        child?.isChecked = false
                    }
                    clickedChip.isChecked = true

                    if (presetName == "Custom") {
                        val activeSelection = profiles.indexOf(spinnerDeviceProfile?.text?.toString()).coerceAtLeast(0)
                        EqualizerManager.saveCustomPresetSelection(safeAppContext, activeSelection)
                        handleCustomSlidersVisibility(showSliders = true)
                    } else {
                        EqualizerManager.applyPresetToHardware(safeAppContext, presetName)
                        handleCustomSlidersVisibility(showSliders = false)
                    }
                    updateSpectrumCurve()
                }
            }

            val params = GridLayout.LayoutParams().apply {
                rowSpec = GridLayout.spec(GridLayout.UNDEFINED)
                columnSpec = GridLayout.spec(GridLayout.UNDEFINED, 1f)
                setMargins(6, 6, 6, 6)
                width = 0
            }

            chip.layoutParams = params
            container.addView(chip)
        }

        updateSpectrumCurve()
        handleCustomSlidersVisibility(currentlySavedPreset == "Custom")
        switchEqualizer?.let { togglePresetContainerState(it.isChecked) }
    }

    private fun handleCustomSlidersVisibility(showSliders: Boolean) {
        llCustomSlidersWrapper?.visibility = if (showSliders) View.VISIBLE else View.GONE
        if (showSliders) {
            buildVerticalSliders()
        }
    }

    private fun buildVerticalSliders() {
        val bandsContainer = llBandsContainer ?: return
        bandsContainer.removeAllViews()

        val numBands = EqualizerManager.getNumberOfBands().toInt()
        val range = EqualizerManager.getBandLevelRange()

        if (numBands <= 0) return

        val minLevel = range[0].toInt()
        val maxLevel = range[1].toInt()
        val safeContext = context ?: return
        val safeAppContext = safeContext.applicationContext
        val inflater = LayoutInflater.from(safeContext)

        for (band in 0 until numBands) {
            val bandIndex = band.toShort()
            val centerFreq = EqualizerManager.getCenterFrequency(bandIndex)

            val freqLabel = if (centerFreq >= 1000000) {
                "${centerFreq / 1000000} kHz"
            } else {
                "${centerFreq / 1000} Hz"
            }

            val rowView = inflater.inflate(R.layout.item_equalizer_slider, bandsContainer, false)
            val tvLabel = rowView.findViewById<TextView>(R.id.tvBandLabel)
            val tvGain = rowView.findViewById<TextView>(R.id.tvBandGain)
            val seekBar = rowView.findViewById<SeekBar>(R.id.sbBandSlider)

            val currentBandLevel = EqualizerManager.getBandLevel(bandIndex).toInt()
            val targetProgress = currentBandLevel - minLevel
            tvLabel.text = freqLabel

            val dbVal = currentBandLevel / 100f
            tvGain?.text = if (dbVal > 0) "+%.1f dB".format(dbVal) else "%.1f dB".format(dbVal)

            seekBar.max = maxLevel - minLevel

            android.animation.ObjectAnimator.ofInt(seekBar, "progress", 0, targetProgress).apply {
                duration = 300
                start()
            }

            seekBar.setOnSeekBarChangeListener(
                object : SeekBar.OnSeekBarChangeListener {
                    override fun onProgressChanged(sb: SeekBar?, progress: Int, fromUser: Boolean) {
                        if (fromUser) {
                            if (switchEqualizer?.isChecked == false) {
                                switchEqualizer?.isChecked = true
                            }
                            val finalLevel = (progress + minLevel).toShort()
                            val gainDb = finalLevel / 100f
                            tvGain?.text = if (gainDb > 0) "+%.1f dB".format(gainDb) else "%.1f dB".format(gainDb)

                            EqualizerManager.setBandLevel(safeAppContext, bandIndex, finalLevel)
                            updateSpectrumCurve()
                        }
                    }

                    override fun onStartTrackingTouch(sb: SeekBar?) {}

                    override fun onStopTrackingTouch(sb: SeekBar?) {
                        val activeSelection =
                            profiles.indexOf(spinnerDeviceProfile?.text?.toString()).coerceAtLeast(0)
                        EqualizerManager.saveCustomPresetSelection(safeAppContext, activeSelection)

                        val container = glPresetContainer ?: return
                        for (i in 0 until container.childCount) {
                            val child = container.getChildAt(i) as? Chip
                            child?.isChecked = child.text == "Custom"
                        }
                    }
                },
            )

            bandsContainer.addView(rowView)
        }
    }

    private fun togglePresetContainerState(isEnabled: Boolean) {
        glPresetContainer?.isEnabled = isEnabled
        llCustomSlidersWrapper?.isEnabled = isEnabled

        glPresetContainer?.let { container ->
            for (i in 0 until container.childCount) {
                container.getChildAt(i).isEnabled = isEnabled
            }
        }

        llBandsContainer?.let { bandsContainer ->
            for (i in 0 until bandsContainer.childCount) {
                val child = bandsContainer.getChildAt(i)
                child.findViewById<SeekBar>(R.id.sbBandSlider)?.isEnabled = isEnabled
                child.alpha = if (isEnabled) 1.0f else 0.35f
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
        equalizerSpectrumView = null
        glPresetContainer = null
        llBandsContainer = null
        switchEqualizer = null
        spinnerDeviceProfile = null
        llCustomSlidersWrapper = null
        super.onDestroyView()
    }
}