package com.music.myusiq.ui

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Bundle
import android.view.View
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.core.content.edit
import androidx.core.view.isVisible
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels
import androidx.lifecycle.lifecycleScope
import android.graphics.Color
import com.google.android.material.button.MaterialButtonToggleGroup
import com.google.android.material.card.MaterialCardView
import com.google.android.material.materialswitch.MaterialSwitch
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import com.music.myusiq.ui.viewmodels.PlaybackViewModel
import com.music.myusiq.R

class AppearanceSettingsFragment : Fragment(R.layout.fragment_settings_appearance) {
    private lateinit var themeManager: ThemeSettingsManager
    private val viewModel: PlaybackViewModel by activityViewModels()
    private var isInitialSetup = true

    private val requestAudioPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { isPermissionGranted ->
        if (isPermissionGranted) {
            updateFftVisualizerPref(true)
        }
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        themeManager = ThemeSettingsManager(requireContext())

        val header = view.findViewById<View>(R.id.appearanceHeader)
        header.findViewById<TextView>(R.id.tvHeaderTitle).text = "Look & Feel"
        header.findViewById<TextView>(R.id.tvHeaderSubtitle).visibility = View.GONE
        header.findViewById<View>(R.id.btnBack).apply {
            visibility = View.VISIBLE
            setOnClickListener { requireActivity().onBackPressedDispatcher.onBackPressed() }
        }

        val toggleThemeMode = view.findViewById<MaterialButtonToggleGroup>(R.id.toggleThemeMode)
        val switchColorPalette = view.findViewById<MaterialSwitch>(R.id.switchColorPalette)
        val layoutColorSwatches = view.findViewById<View>(R.id.layoutColorSwatches)
        val switchAlbumArtTheming = view.findViewById<MaterialSwitch>(R.id.switchAlbumArtTheming)
        val switchBlurredBackground = view.findViewById<MaterialSwitch>(R.id.switchBlurredBackground)
        val switchFftVisualizer = view.findViewById<MaterialSwitch>(R.id.switchFftVisualizer)
        val switchAppTypography = view.findViewById<MaterialSwitch>(R.id.switchAppTypography)

        val swatches = mapOf(
            "system_colors" to view.findViewById<View>(R.id.paletteDefault),
            "palette_midnight" to view.findViewById<View>(R.id.paletteMidnight),
            "palette_slate" to view.findViewById<View>(R.id.paletteSlate),
            "palette_forest" to view.findViewById<View>(R.id.paletteForest),
            "palette_charcoal" to view.findViewById<View>(R.id.paletteCharcoal),
            "palette_royal" to view.findViewById<View>(R.id.paletteRoyal)
        )

        swatches.forEach { (id, v) ->
            val name = when(id) {
                "palette_midnight" -> getString(R.string.midnight_pro)
                "palette_slate" -> getString(R.string.slate_blue)
                "palette_forest" -> getString(R.string.forest_luxury)
                "palette_charcoal" -> getString(R.string.charcoal_gold)
                "palette_royal" -> getString(R.string.royal_purple)
                else -> getString(R.string.system_default)
            }
            setupPaletteItem(v, id, name)
        }

        viewLifecycleOwner.lifecycleScope.launch {
            val currentMode = themeManager.themeModeFlow.first()
            val currentStyle = themeManager.colorStyleFlow.first()
            val useAlbumArt = themeManager.useAlbumArtThemingFlow.first()
            val useBlurredBg = themeManager.useBlurredBackgroundFlow.first()
            val useCustomFont = themeManager.useCustomFontFlow.first()

            if (!isAdded) return@launch

            isInitialSetup = true

            // Theme Mode
            when (currentMode) {
                "light" -> toggleThemeMode.check(R.id.btnLightTheme)
                "dark" -> toggleThemeMode.check(R.id.btnDarkTheme)
                else -> toggleThemeMode.check(R.id.btnSystemTheme)
            }

            // Custom Palette Toggle
            val isCustomActive = currentStyle != "system_colors"
            switchColorPalette.isChecked = isCustomActive
            layoutColorSwatches.visibility = if (isCustomActive) View.VISIBLE else View.GONE
            
            swatches.forEach { (id, v) ->
                val isSelected = id == currentStyle
                v.findViewById<View>(R.id.ivSelected).isVisible = isSelected
                (v as? MaterialCardView)?.strokeWidth = if (isSelected) 4 else 1
                (v as? MaterialCardView)?.strokeColor = if (isSelected) 
                    ContextCompat.getColor(requireContext(), R.color.md_theme_default_primary) 
                else ContextCompat.getColor(requireContext(), android.R.color.darker_gray)
            }

            // Adaptive Colors
            switchAlbumArtTheming.isChecked = useAlbumArt
            switchBlurredBackground.isChecked = useBlurredBg
            switchAppTypography.isChecked = useCustomFont

            // FFT
            val sharedPreferences = requireContext().getSharedPreferences("app_prefs", Context.MODE_PRIVATE)
            switchFftVisualizer.isChecked = sharedPreferences.getBoolean("fft_visualizer_enabled", true)

            isInitialSetup = false
        }

        setupListeners(toggleThemeMode, switchColorPalette, swatches, switchAlbumArtTheming, switchBlurredBackground, switchFftVisualizer, switchAppTypography)
    }

    private fun setupListeners(
        toggleThemeMode: MaterialButtonToggleGroup,
        switchColorPalette: MaterialSwitch,
        swatches: Map<String, View>,
        switchAlbumArtTheming: MaterialSwitch,
        switchBlurredBackground: MaterialSwitch,
        switchFftVisualizer: MaterialSwitch,
        switchAppTypography: MaterialSwitch
    ) {
        toggleThemeMode.addOnButtonCheckedListener { _, checkedId, isChecked ->
            if (isChecked) {
                val selection = when (checkedId) {
                    R.id.btnLightTheme -> "light"
                    R.id.btnDarkTheme -> "dark"
                    else -> "system"
                }
                viewLifecycleOwner.lifecycleScope.launch {
                    themeManager.saveThemeMode(selection)
                    themeManager.applyThemeMode(selection)
                }
            }
        }

        switchColorPalette.setOnCheckedChangeListener { _, isChecked ->
            if (isInitialSetup) return@setOnCheckedChangeListener
            viewLifecycleOwner.lifecycleScope.launch {
                if (isChecked) {
                    themeManager.saveColorStyle("palette_midnight")
                    themeManager.saveUseAlbumArtTheming(false)
                    activity?.recreate()
                } else {
                    themeManager.saveColorStyle("system_colors")
                    activity?.recreate()
                }
            }
        }

        swatches.forEach { (styleKey, v) ->
            v.setOnClickListener {
                viewLifecycleOwner.lifecycleScope.launch {
                    val current = themeManager.colorStyleFlow.first()
                    if (current != styleKey) {
                        themeManager.saveColorStyle(styleKey)
                        themeManager.saveUseAlbumArtTheming(false)
                        activity?.recreate()
                    }
                }
            }
        }

        switchAlbumArtTheming.setOnCheckedChangeListener { _, isChecked ->
            if (isInitialSetup) return@setOnCheckedChangeListener
            viewLifecycleOwner.lifecycleScope.launch {
                if (isChecked) {
                    themeManager.saveColorStyle("system_colors")
                    themeManager.saveUseAlbumArtTheming(true)
                    activity?.recreate()
                } else {
                    themeManager.saveUseAlbumArtTheming(false)
                    viewModel.currentVibrantColor.value = null
                    viewModel.currentBgColor.value = null
                    viewModel.currentOnBgColor.value = null
                }
            }
        }

        switchBlurredBackground.setOnCheckedChangeListener { _, isChecked ->
            if (isInitialSetup) return@setOnCheckedChangeListener
            viewLifecycleOwner.lifecycleScope.launch {
                themeManager.saveUseBlurredBackground(isChecked)
                viewModel.notifySettingsChanged() // Notify PlayerFragment to update background
            }
        }

        switchFftVisualizer.setOnCheckedChangeListener { _, isChecked ->
            if (isChecked) {
                if (ContextCompat.checkSelfPermission(requireContext(), Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
                    updateFftVisualizerPref(true)
                    viewModel.notifySettingsChanged()
                } else {
                    requestAudioPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
                }
            } else {
                updateFftVisualizerPref(false)
                viewModel.notifySettingsChanged()
            }
        }

        switchAppTypography.setOnCheckedChangeListener { _, isChecked ->
            if (isInitialSetup) return@setOnCheckedChangeListener
            viewLifecycleOwner.lifecycleScope.launch {
                themeManager.saveUseCustomFont(isChecked)
                activity?.recreate()
            }
        }
    }

    private fun updateFftVisualizerPref(enabled: Boolean) {
        val sharedPreferences = requireContext().getSharedPreferences("app_prefs", Context.MODE_PRIVATE)
        sharedPreferences.edit {
            putBoolean("fft_visualizer_enabled", enabled)
        }
    }

    private fun setupPaletteItem(view: View, paletteId: String, name: String) {
        val tvPaletteName = view.findViewById<TextView>(R.id.tvPaletteName)
        val viewColorPrimary = view.findViewById<View>(R.id.viewColorPrimary)
        val viewColorSurface = view.findViewById<View>(R.id.viewColorSurface)
        
        tvPaletteName.text = name
        
        when (paletteId) {
            "system_colors" -> {
                viewColorPrimary.setBackgroundColor(ContextCompat.getColor(requireContext(), R.color.md_theme_default_primary))
                tvPaletteName.setTextColor(Color.BLACK)
            }
            "palette_midnight" -> {
                viewColorPrimary.setBackgroundColor(ContextCompat.getColor(requireContext(), R.color.midnight_accent))
                viewColorSurface.setBackgroundColor(ContextCompat.getColor(requireContext(), R.color.midnight_blue))
                tvPaletteName.setTextColor(Color.WHITE)
            }
            "palette_slate" -> {
                viewColorPrimary.setBackgroundColor(ContextCompat.getColor(requireContext(), R.color.slate_accent))
                viewColorSurface.setBackgroundColor(ContextCompat.getColor(requireContext(), R.color.slate_dark))
                tvPaletteName.setTextColor(Color.WHITE)
            }
            "palette_forest" -> {
                viewColorPrimary.setBackgroundColor(ContextCompat.getColor(requireContext(), R.color.forest_accent))
                viewColorSurface.setBackgroundColor(ContextCompat.getColor(requireContext(), R.color.forest_dark))
                tvPaletteName.setTextColor(Color.WHITE)
            }
            "palette_charcoal" -> {
                viewColorPrimary.setBackgroundColor(ContextCompat.getColor(requireContext(), R.color.charcoal_accent))
                viewColorSurface.setBackgroundColor(ContextCompat.getColor(requireContext(), R.color.charcoal_dark))
                tvPaletteName.setTextColor(Color.WHITE)
            }
            "palette_royal" -> {
                viewColorPrimary.setBackgroundColor(ContextCompat.getColor(requireContext(), R.color.royal_accent))
                viewColorSurface.setBackgroundColor(ContextCompat.getColor(requireContext(), R.color.royal_dark))
                tvPaletteName.setTextColor(Color.WHITE)
            }
        }
    }
}
