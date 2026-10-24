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

        view.findViewById<View>(R.id.rowResetFolderRestrictions)?.setOnClickListener {
            com.music.myusiq.utils.FolderPreferences(requireContext()).clearAllowedFolderUris()
            viewModel.forceScanMediaLibrary()
            Toast.makeText(requireContext(), "Cleared folder restrictions. Scanning all device music...", Toast.LENGTH_SHORT).show()
        }

        val switchFilter = view.findViewById<MaterialSwitch>(R.id.switchFilterShort)
        switchFilter.isChecked = sharedPreferences.getBoolean("filter_short_audio", true)
        switchFilter.setOnCheckedChangeListener { _, isChecked ->
            sharedPreferences.edit { putBoolean("filter_short_audio", isChecked) }
            Toast.makeText(requireContext(), "Rescan library to apply", Toast.LENGTH_SHORT).show()
        }

        val switchAutoImprove = view.findViewById<MaterialSwitch>(R.id.switchAutoImprove)
        switchAutoImprove.isChecked = sharedPreferences.getBoolean("auto_improve_metadata", false)

        fun launchJanitorAutoFixIfUnfixedExist() {
            val songs = viewModel.songsLibrary.value
            val ctx = context ?: return
            val unfixedExist = songs.any { song ->
                val isUnknownArtist = com.music.myusiq.utils.CloudMetadataResolver.isGenericArtist(song.artist)
                val hasFileExt = Regex("(?i)\\.(mp3|m4a|flac|wav|aac|ogg|wma)$").containsMatchIn(song.title)
                val isGenericTitle = Regex("(?i)^(track|audio|sound|recording)[_\\s\\-]*\\d*$").matches(song.title) || song.title.isBlank()
                val hasClutter = com.music.myusiq.utils.YouTubeTitleCleaner.cleanTitle(song.title) != song.title
                val missingArt = com.music.myusiq.utils.ArtworkUtils.getArtworkFile(ctx, song.id, song.path) == null

                isUnknownArtist || hasFileExt || isGenericTitle || hasClutter || missingArt
            }

            if (unfixedExist) {
                val fragment = LibraryJanitorFragment().apply {
                    arguments = Bundle().apply {
                        putBoolean("auto_start_scan", true)
                    }
                }
                parentFragmentManager.beginTransaction()
                    .setCustomAnimations(R.anim.slide_in_right, R.anim.slide_out_left, R.anim.slide_in_left, R.anim.slide_out_right)
                    .add(R.id.navHostFragment, fragment)
                    .hide(this)
                    .addToBackStack(null)
                    .commit()
            } else {
                Toast.makeText(requireContext(), "Auto-Improve Metadata enabled. All tracks are up to date!", Toast.LENGTH_SHORT).show()
            }
        }

        switchAutoImprove.setOnCheckedChangeListener { _, isChecked ->
            sharedPreferences.edit { putBoolean("auto_improve_metadata", isChecked) }
            if (isChecked) {
                launchJanitorAutoFixIfUnfixedExist()
            }
        }

        view.findViewById<View>(R.id.rowAutoImprove)?.setOnClickListener {
            switchAutoImprove.isChecked = !switchAutoImprove.isChecked
        }

        view.findViewById<View>(R.id.rowLibraryJanitor).setOnClickListener {
            parentFragmentManager.beginTransaction()
                .setCustomAnimations(R.anim.slide_in_right, R.anim.slide_out_left, R.anim.slide_in_left, R.anim.slide_out_right)
                .add(R.id.navHostFragment, LibraryJanitorFragment())
                .hide(this)
                .addToBackStack(null)
                .commit()
        }

        val switchBackgroundScan = view.findViewById<MaterialSwitch>(R.id.switchBackgroundScan)
        switchBackgroundScan.isChecked = sharedPreferences.getBoolean("background_auto_scan", false)
        switchBackgroundScan.setOnCheckedChangeListener { _, isChecked ->
            sharedPreferences.edit { putBoolean("background_auto_scan", isChecked) }
            val ctx = requireContext().applicationContext
            if (isChecked) {
                val constraints = androidx.work.Constraints.Builder()
                    .setRequiredNetworkType(androidx.work.NetworkType.UNMETERED)
                    .setRequiresCharging(true)
                    .build()
                val request = androidx.work.PeriodicWorkRequestBuilder<com.music.myusiq.service.JanitorBackgroundWorker>(24, java.util.concurrent.TimeUnit.HOURS)
                    .setConstraints(constraints)
                    .build()
                androidx.work.WorkManager.getInstance(ctx).enqueueUniquePeriodicWork(
                    "JanitorBackgroundScan",
                    androidx.work.ExistingPeriodicWorkPolicy.KEEP,
                    request
                )
                Toast.makeText(ctx, "Background Auto-Scan enabled (runs on Wi-Fi & Charging)", Toast.LENGTH_SHORT).show()
            } else {
                androidx.work.WorkManager.getInstance(ctx).cancelUniqueWork("JanitorBackgroundScan")
                Toast.makeText(ctx, "Background Auto-Scan disabled", Toast.LENGTH_SHORT).show()
            }
        }

        view.findViewById<View>(R.id.rowBackgroundScan)?.setOnClickListener {
            switchBackgroundScan.isChecked = !switchBackgroundScan.isChecked
        }

        view.findViewById<View>(R.id.rowMetadataHistory).setOnClickListener {
            parentFragmentManager.beginTransaction()
                .setCustomAnimations(R.anim.slide_in_right, R.anim.slide_out_left, R.anim.slide_in_left, R.anim.slide_out_right)
                .add(R.id.navHostFragment, MetadataHistoryFragment())
                .hide(this)
                .addToBackStack(null)
                .commit()
        }
    }
}
