package com.music.myusiq.ui

import android.content.Context
import android.os.Bundle
import android.view.View
import android.widget.TextView
import android.widget.Toast
import androidx.core.content.edit
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels
import com.google.android.material.materialswitch.MaterialSwitch
import com.music.myusiq.ui.viewmodels.PlaybackViewModel
import com.music.myusiq.R

class LibrarySettingsFragment : Fragment(R.layout.fragment_settings_library) {
    private val viewModel: PlaybackViewModel by activityViewModels()

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        
        val header = view.findViewById<View>(R.id.libraryHeader)
        header.findViewById<TextView>(R.id.tvHeaderTitle).setText(R.string.library_and_storage)
        header.findViewById<TextView>(R.id.tvHeaderSubtitle).visibility = View.GONE
        header.findViewById<View>(R.id.btnBack).apply {
            visibility = View.VISIBLE
            setOnClickListener { requireActivity().onBackPressedDispatcher.onBackPressed() }
        }

        val sharedPreferences = requireContext().getSharedPreferences("app_prefs", Context.MODE_PRIVATE)

        view.findViewById<View>(R.id.rowRescanLibrary).setOnClickListener {
            viewModel.forceScanMediaLibrary()
            Toast.makeText(requireContext(), "Scanning music library...", Toast.LENGTH_SHORT).show()
        }

        val switchFilter = view.findViewById<MaterialSwitch>(R.id.switchFilterShort)
        switchFilter.isChecked = sharedPreferences.getBoolean("filter_short_audio", true)
        switchFilter.setOnCheckedChangeListener { _, isChecked ->
            sharedPreferences.edit { putBoolean("filter_short_audio", isChecked) }
            Toast.makeText(requireContext(), "Rescan library to apply", Toast.LENGTH_SHORT).show()
        }

        val switchAutoImprove = view.findViewById<MaterialSwitch>(R.id.switchAutoImprove)
        switchAutoImprove.isChecked = sharedPreferences.getBoolean("auto_improve_metadata", false)
        switchAutoImprove.setOnCheckedChangeListener { _, isChecked ->
            sharedPreferences.edit { putBoolean("auto_improve_metadata", isChecked) }
        }

        view.findViewById<View>(R.id.rowLibraryJanitor).setOnClickListener {
            parentFragmentManager.beginTransaction()
                .setCustomAnimations(R.anim.slide_in_right, R.anim.slide_out_left, R.anim.slide_in_left, R.anim.slide_out_right)
                .add(R.id.navHostFragment, LibraryJanitorFragment())
                .hide(this)
                .addToBackStack(null)
                .commit()
        }
    }
}
