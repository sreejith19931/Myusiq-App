package com.music.myusiq.ui

import android.os.Bundle
import android.view.View
import androidx.appcompat.app.AppCompatDelegate
import androidx.fragment.app.Fragment
import com.music.myusiq.R
import com.music.myusiq.databinding.FragmentSetupPersonalizeBinding
import com.music.myusiq.databinding.ItemPaletteOptionBinding

class SetupPersonalizeFragment : Fragment(R.layout.fragment_setup_personalize) {
    private var _binding: FragmentSetupPersonalizeBinding? = null
    private val binding get() = _binding!!
    
    private var selectedTheme = "system"
    private var selectedPalette = "system_colors"

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        _binding = FragmentSetupPersonalizeBinding.bind(view)

        (activity as? SetupActivity)?.let { setupAct ->
            selectedPalette = setupAct.getChosenPalette()
            selectedTheme = setupAct.getChosenThemeMode()
        }

        initThemeToggle()
        initPaletteOptions()
        updatePaletteSelection()
    }

    private fun initThemeToggle() {
        when (selectedTheme) {
            "light" -> binding.toggleGroupTheme.check(R.id.btnLightTheme)
            "dark" -> binding.toggleGroupTheme.check(R.id.btnDarkTheme)
            else -> binding.toggleGroupTheme.check(R.id.btnSystemTheme)
        }

        binding.toggleGroupTheme.addOnButtonCheckedListener { _, checkedId, isChecked ->
            if (isChecked) {
                selectedTheme = when (checkedId) {
                    R.id.btnLightTheme -> "light"
                    R.id.btnDarkTheme -> "dark"
                    else -> "system"
                }
                applyTheme()
                (activity as? SetupActivity)?.onThemeChanged(selectedTheme)
            }
        }
    }

    private fun applyTheme() {
        val mode = when (selectedTheme) {
            "light" -> AppCompatDelegate.MODE_NIGHT_NO
            "dark" -> AppCompatDelegate.MODE_NIGHT_YES
            else -> AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM
        }
        AppCompatDelegate.setDefaultNightMode(mode)
    }

    private fun initPaletteOptions() {
        setupPaletteItem(binding.paletteDefault, "system_colors", getString(R.string.system_default))
        setupPaletteItem(binding.paletteMidnight, "palette_midnight", getString(R.string.midnight_pro))
        setupPaletteItem(binding.paletteSlate, "palette_slate", getString(R.string.slate_blue))
        setupPaletteItem(binding.paletteForest, "palette_forest", getString(R.string.forest_luxury))
        setupPaletteItem(binding.paletteCharcoal, "palette_charcoal", getString(R.string.charcoal_gold))
        setupPaletteItem(binding.paletteRoyal, "palette_royal", getString(R.string.royal_purple))
        
        updatePaletteSelection()
    }

    private fun setupPaletteItem(itemBinding: ItemPaletteOptionBinding, paletteId: String, name: String) {
        itemBinding.tvPaletteName.text = name
        
        val ctx = requireContext()
        when (paletteId) {
            "system_colors" -> {
                itemBinding.viewColorPrimary.setBackgroundColor(ctx.getColor(R.color.md_theme_default_primary))
                itemBinding.viewColorSurface.setBackgroundColor(ctx.getColor(R.color.surface_blue))
            }
            "palette_midnight" -> {
                itemBinding.viewColorPrimary.setBackgroundColor(ctx.getColor(R.color.midnight_accent))
                itemBinding.viewColorSurface.setBackgroundColor(ctx.getColor(R.color.midnight_blue))
            }
            "palette_slate" -> {
                itemBinding.viewColorPrimary.setBackgroundColor(ctx.getColor(R.color.slate_accent))
                itemBinding.viewColorSurface.setBackgroundColor(ctx.getColor(R.color.slate_dark))
            }
            "palette_forest" -> {
                itemBinding.viewColorPrimary.setBackgroundColor(ctx.getColor(R.color.forest_accent))
                itemBinding.viewColorSurface.setBackgroundColor(ctx.getColor(R.color.forest_dark))
            }
            "palette_charcoal" -> {
                itemBinding.viewColorPrimary.setBackgroundColor(ctx.getColor(R.color.charcoal_accent))
                itemBinding.viewColorSurface.setBackgroundColor(ctx.getColor(R.color.charcoal_dark))
            }
            "palette_royal" -> {
                itemBinding.viewColorPrimary.setBackgroundColor(ctx.getColor(R.color.royal_accent))
                itemBinding.viewColorSurface.setBackgroundColor(ctx.getColor(R.color.royal_dark))
            }
        }

        itemBinding.root.setOnClickListener {
            selectedPalette = paletteId
            updatePaletteSelection()
            (activity as? SetupActivity)?.onPaletteChanged(selectedPalette)
        }
    }

    private fun updatePaletteSelection() {
        val ctx = requireContext()
        val palettes = listOf(
            binding.paletteDefault to "system_colors",
            binding.paletteMidnight to "palette_midnight",
            binding.paletteSlate to "palette_slate",
            binding.paletteForest to "palette_forest",
            binding.paletteCharcoal to "palette_charcoal",
            binding.paletteRoyal to "palette_royal",
        )

        palettes.forEach { (item, id) ->
            val isSelected = id == selectedPalette
            item.cardCheckBadge.visibility = if (isSelected) View.VISIBLE else View.GONE
            item.root.strokeWidth = if (isSelected) 6 else 2
            item.root.strokeColor = if (isSelected) {
                ctx.getColor(R.color.md_theme_default_primary)
            } else {
                ctx.getColor(android.R.color.darker_gray)
            }
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
