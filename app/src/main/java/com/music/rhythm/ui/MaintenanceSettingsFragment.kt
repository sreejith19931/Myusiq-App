package com.music.rhythm.ui

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
import com.music.rhythm.ui.viewmodels.PlaybackViewModel
import com.music.rhythm.utils.BackupManager
import com.music.rhythm.R

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
            createBackupLauncher.launch("rhythm_backup.json")
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

        view.findViewById<View>(R.id.rowResetApp).setOnClickListener { showResetConfirmationDialog() }
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
