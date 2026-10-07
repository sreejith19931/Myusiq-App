package com.music.myusiq.ui

import android.os.Bundle
import android.view.View
import android.widget.TextView
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import com.google.android.material.materialswitch.MaterialSwitch
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import com.music.myusiq.R

class LyricsSettingsFragment : Fragment(R.layout.fragment_settings_lyrics) {
    private lateinit var themeManager: ThemeSettingsManager

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        
        val header = view.findViewById<View>(R.id.lyricsHeader)
        header.findViewById<TextView>(R.id.tvHeaderTitle).setText(R.string.lyrics_settings)
        header.findViewById<TextView>(R.id.tvHeaderSubtitle).visibility = View.GONE
        header.findViewById<View>(R.id.btnBack).apply {
            visibility = View.VISIBLE
            setOnClickListener { requireActivity().onBackPressedDispatcher.onBackPressed() }
        }

        themeManager = ThemeSettingsManager(requireContext())

        val switchOverlay = view.findViewById<MaterialSwitch>(R.id.switchLyricOverlay)
        val switchMinimalist = view.findViewById<MaterialSwitch>(R.id.switchSingleLineLyrics)

        viewLifecycleOwner.lifecycleScope.launch {
            switchOverlay.isChecked = themeManager.useLyricOverlayFlow.first()
            switchMinimalist.isChecked = themeManager.useSingleLineLyricsFlow.first()
        }

        switchOverlay.setOnCheckedChangeListener { _, isChecked ->
            viewLifecycleOwner.lifecycleScope.launch {
                themeManager.saveUseLyricOverlay(isChecked)
            }
        }

        switchMinimalist.setOnCheckedChangeListener { _, isChecked ->
            viewLifecycleOwner.lifecycleScope.launch {
                themeManager.saveUseSingleLineLyrics(isChecked)
            }
        }
    }
}
