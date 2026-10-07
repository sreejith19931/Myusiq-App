package com.music.myusiq.ui

import android.annotation.SuppressLint
import android.os.Bundle
import android.view.View
import android.widget.TextView
import androidx.fragment.app.Fragment
import com.music.myusiq.R

class SettingsFragment : Fragment(R.layout.fragment_settings) {

    @SuppressLint("SetTextI18n")
    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        
        val header = view.findViewById<View>(R.id.settingsHeader)
        header.visibility = View.VISIBLE
        header.findViewById<TextView>(R.id.tvHeaderTitle).text = "Settings"
        header.findViewById<TextView>(R.id.tvHeaderSubtitle).visibility = View.GONE
        header.findViewById<View>(R.id.btnBack).visibility = View.GONE

        view.findViewById<View>(R.id.hubAudio).setOnClickListener {
            navigateTo(R.id.dest_settings_audio)
        }
        view.findViewById<View>(R.id.hubAppearance).setOnClickListener {
            navigateTo(R.id.dest_settings_appearance)
        }
        view.findViewById<View>(R.id.hubLyrics).setOnClickListener {
            navigateTo(R.id.dest_settings_lyrics)
        }
        view.findViewById<View>(R.id.hubLibrary).setOnClickListener {
            navigateTo(R.id.dest_settings_library)
        }
        view.findViewById<View>(R.id.hubMaintenance).setOnClickListener {
            navigateTo(R.id.dest_settings_maintenance)
        }
        view.findViewById<View>(R.id.hubAbout).setOnClickListener {
            navigateTo(R.id.dest_about)
        }
    }

    private fun navigateTo(destinationId: Int) {
        val fragment = createFragment(destinationId)
        requireActivity().supportFragmentManager.beginTransaction()
            .setCustomAnimations(R.anim.slide_in_right, R.anim.slide_out_left, R.anim.slide_in_left, R.anim.slide_out_right)
            .add(R.id.navHostFragment, fragment)
            .hide(this)
            .addToBackStack(null)
            .commit()
    }


    private fun createFragment(id: Int): Fragment {
        return when (id) {
            R.id.dest_settings_audio -> AudioSettingsFragment()
            R.id.dest_settings_appearance -> AppearanceSettingsFragment()
            R.id.dest_settings_lyrics -> LyricsSettingsFragment()
            R.id.dest_settings_library -> LibrarySettingsFragment()
            R.id.dest_settings_maintenance -> MaintenanceSettingsFragment()
            R.id.dest_about -> AboutFragment()
            else -> AudioSettingsFragment()
        }
    }
}
