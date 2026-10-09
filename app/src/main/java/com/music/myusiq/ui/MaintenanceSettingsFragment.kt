package com.music.myusiq.ui

import android.app.ActivityManager
import android.content.Context
import android.os.Bundle
import android.view.View
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels
import androidx.lifecycle.lifecycleScope
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import kotlinx.coroutines.launch
import com.music.myusiq.ui.viewmodels.PlaybackViewModel
import com.music.myusiq.utils.BackupManager
import com.music.myusiq.R

class MaintenanceSettingsFragment : Fragment(R.layout.fragment_settings_maintenance) {
    private val viewModel: PlaybackViewModel by activityViewModels()

    private val createBackupLauncher = registerForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
        uri?.let {
            lifecycleScope.launch {
                val outputStream = requireContext().contentResolver.openOutputStream(it)
                if (outputStream != null) {
                    val success = BackupManager.exportBackup(requireContext(), outputStream)
                    val resId = if (success) R.string.backup_created else R.string.backup_failed
                    Toast.makeText(requireContext(), resId, Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    private val restoreBackupLauncher = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri?.let {
            lifecycleScope.launch {
                val inputStream = requireContext().contentResolver.openInputStream(it)
                if (inputStream != null) {
                    val success = BackupManager.importBackup(requireContext(), inputStream)
                    val resId = if (success) R.string.restore_successful else R.string.restore_failed
                    Toast.makeText(requireContext(), resId, Toast.LENGTH_LONG).show()
                }
            }
        }
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        
        val header = view.findViewById<View>(R.id.maintenanceHeader)
        header.findViewById<TextView>(R.id.tvHeaderTitle).text = getString(R.string.backup_and_maintenance)
        header.findViewById<TextView>(R.id.tvHeaderSubtitle).visibility = View.GONE
        header.findViewById<View>(R.id.btnBack).apply {
            visibility = View.VISIBLE
            setOnClickListener { requireActivity().onBackPressedDispatcher.onBackPressed() }
        }

        view.findViewById<View>(R.id.rowExportData).setOnClickListener {
            createBackupLauncher.launch("myusiq_backup.json")
        }

        view.findViewById<View>(R.id.rowRestoreData).setOnClickListener {
            restoreBackupLauncher.launch(arrayOf("application/json"))
        }

        view.findViewById<View>(R.id.rowClearHistory).setOnClickListener {
            MaterialAlertDialogBuilder(requireContext())
                .setTitle(R.string.clear_history_title)
                .setMessage(R.string.clear_history_message)
                .setNegativeButton(R.string.cancel, null)
                .setPositiveButton(R.string.clear) { _, _ ->
                    viewModel.clearPlaybackHistory()
                    Toast.makeText(requireContext(), R.string.playback_history_cleared, Toast.LENGTH_SHORT).show()
                }
                .show()
        }

        view.findViewById<View>(R.id.rowClearLyrics)?.setOnClickListener {
            showClearLyricsOptionsDialog()
        }

        view.findViewById<View>(R.id.rowResetApp).setOnClickListener { showResetConfirmationDialog() }
    }

    private fun showClearLyricsOptionsDialog() {
        val options = arrayOf("Clear All Lyrics Files", "Select Song Lyrics to Remove")
        MaterialAlertDialogBuilder(requireContext())
            .setTitle("Clear Lyrics Files")
            .setItems(options) { _, which ->
                when (which) {
                    0 -> confirmClearAllLyrics()
                    1 -> showSelectLyricsToRemoveDialog()
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun confirmClearAllLyrics() {
        MaterialAlertDialogBuilder(requireContext())
            .setTitle("Clear All Lyrics?")
            .setMessage("This will remove all saved lyrics files and custom lyrics overrides from local storage. Continue?")
            .setNegativeButton("Cancel", null)
            .setPositiveButton("Clear All") { _, _ ->
                lifecycleScope.launch {
                    viewModel.clearAllLyrics()
                    Toast.makeText(requireContext(), "All local lyrics cleared", Toast.LENGTH_SHORT).show()
                }
            }
            .show()
    }

    private fun showSelectLyricsToRemoveDialog() {
        lifecycleScope.launch {
            val songsWithLyrics = viewModel.getSongsWithLyrics()
            if (songsWithLyrics.isEmpty()) {
                Toast.makeText(requireContext(), "No local lyrics files found", Toast.LENGTH_SHORT).show()
                return@launch
            }

            val items = songsWithLyrics.map { "${it.title} - ${it.artist}" }.toTypedArray()
            val checked = BooleanArray(songsWithLyrics.size) { true }

            MaterialAlertDialogBuilder(requireContext())
                .setTitle("Select Lyrics to Remove")
                .setMultiChoiceItems(items, checked) { _, which, isChecked ->
                    checked[which] = isChecked
                }
                .setPositiveButton("Remove Selected") { _, _ ->
                    val selected = songsWithLyrics.filterIndexed { index, _ -> checked[index] }
                    if (selected.isNotEmpty()) {
                        lifecycleScope.launch {
                            viewModel.deleteLyricsForSongs(selected)
                            Toast.makeText(requireContext(), "Removed lyrics for ${selected.size} tracks", Toast.LENGTH_SHORT).show()
                        }
                    }
                }
                .setNegativeButton("Cancel", null)
                .show()
        }
    }

    private fun showResetConfirmationDialog() {
        MaterialAlertDialogBuilder(requireContext())
            .setTitle(R.string.reset_app_title)
            .setMessage(R.string.reset_app_message)
            .setNegativeButton(R.string.cancel, null)
            .setPositiveButton(R.string.reset_app_button) { _, _ -> executeFactoryReset() }
            .show()
    }

    private fun executeFactoryReset() {
        try {
            val activityManager = requireContext().getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
            activityManager.clearApplicationUserData()
        } catch (_: Exception) {
            activity?.finishAffinity()
        }
    }
}
