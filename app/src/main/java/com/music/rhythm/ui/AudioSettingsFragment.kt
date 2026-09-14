package com.music.rhythm.ui

import android.content.Context
import android.os.Bundle
import android.view.View
import android.widget.TextView
import androidx.core.content.edit
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels
import com.google.android.material.materialswitch.MaterialSwitch
import com.google.android.material.slider.Slider
import com.music.rhythm.ui.viewmodels.PlaybackViewModel
import com.music.rhythm.R

class AudioSettingsFragment : Fragment(R.layout.fragment_settings_audio) {
    private val viewModel: PlaybackViewModel by activityViewModels()

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        
        val header = view.findViewById<View>(R.id.audioHeader)
        header.findViewById<TextView>(R.id.tvHeaderTitle).text = "Playback & Audio"
        header.findViewById<TextView>(R.id.tvHeaderSubtitle).visibility = View.GONE
        header.findViewById<View>(R.id.btnBack).apply {
            visibility = View.VISIBLE
            setOnClickListener { requireActivity().onBackPressedDispatcher.onBackPressed() }
        }

        val sharedPreferences = requireContext().getSharedPreferences("app_prefs", Context.MODE_PRIVATE)

        // Equalizer
        view.findViewById<View>(R.id.rowSettingsEqualizer).setOnClickListener {
            parentFragmentManager.beginTransaction()
                .setCustomAnimations(R.anim.slide_in_right, R.anim.slide_out_left, R.anim.slide_in_left, R.anim.slide_out_right)
                .add(R.id.navHostFragment, EqualizerFragment())
                .hide(this)
                .addToBackStack(null)
                .commit()
        }

        // Crossfade
        val switchCrossfade = view.findViewById<MaterialSwitch>(R.id.switchCrossfade)
        val sliderCrossfade = view.findViewById<Slider>(R.id.sliderCrossfade)
        val tvCrossfadeDesc = view.findViewById<TextView>(R.id.tvCrossfadeDesc)
        
        val isCfEnabled = sharedPreferences.getBoolean("crossfade_enabled", false)
        val cfDuration = sharedPreferences.getFloat("crossfade_duration", 2.0f)
        
        switchCrossfade.isChecked = isCfEnabled
        sliderCrossfade.visibility = if (isCfEnabled) View.VISIBLE else View.GONE
        sliderCrossfade.value = cfDuration
        tvCrossfadeDesc.text = getString(R.string.crossfade_desc, cfDuration.toInt())

        switchCrossfade.setOnCheckedChangeListener { _, isChecked ->
            sharedPreferences.edit { putBoolean("crossfade_enabled", isChecked) }
            sliderCrossfade.visibility = if (isChecked) View.VISIBLE else View.GONE
            viewModel.refreshAudioSettings()
        }
        
        sliderCrossfade.addOnChangeListener { _, value, fromUser ->
            if (fromUser) {
                sharedPreferences.edit { putFloat("crossfade_duration", value) }
                tvCrossfadeDesc.text = getString(R.string.crossfade_desc, value.toInt())
                viewModel.refreshAudioSettings()
            }
        }

        // Bluetooth Auto-Actions
        val switchBtResume = view.findViewById<MaterialSwitch>(R.id.switchBtResume)
        switchBtResume.isChecked = sharedPreferences.getBoolean("bluetooth_resume_on_connect", false)
        switchBtResume.setOnCheckedChangeListener { _, isChecked ->
            sharedPreferences.edit { putBoolean("bluetooth_resume_on_connect", isChecked) }
            viewModel.notifySettingsChanged()
        }

        val switchUnplugPause = view.findViewById<MaterialSwitch>(R.id.switchUnplugPause)
        switchUnplugPause.isChecked = sharedPreferences.getBoolean("bluetooth_stop_on_unplug", true)
        switchUnplugPause.setOnCheckedChangeListener { _, isChecked ->
            sharedPreferences.edit { putBoolean("bluetooth_stop_on_unplug", isChecked) }
            viewModel.notifySettingsChanged()
        }


        // Fade In
        val switchFadeIn = view.findViewById<MaterialSwitch>(R.id.switchFadeIn)
        switchFadeIn.isChecked = sharedPreferences.getBoolean("audio_fade_in", false)
        switchFadeIn.setOnCheckedChangeListener { _, isChecked ->
            sharedPreferences.edit { putBoolean("audio_fade_in", isChecked) }
            viewModel.notifySettingsChanged()
        }

        // Ducking
        val switchDucking = view.findViewById<MaterialSwitch>(R.id.switchDucking)
        switchDucking.isChecked = sharedPreferences.getBoolean("audio_ducking", true)
        switchDucking.setOnCheckedChangeListener { _, isChecked ->
            sharedPreferences.edit { putBoolean("audio_ducking", isChecked) }
            viewModel.notifySettingsChanged()
        }

        // Shake to Shuffle
        val switchShakeToShuffle = view.findViewById<MaterialSwitch>(R.id.switchShakeToShuffle)
        switchShakeToShuffle.isChecked = sharedPreferences.getBoolean("shake_to_shuffle", false)
        switchShakeToShuffle.setOnCheckedChangeListener { _, isChecked ->
            sharedPreferences.edit { putBoolean("shake_to_shuffle", isChecked) }
        }
    }
}
