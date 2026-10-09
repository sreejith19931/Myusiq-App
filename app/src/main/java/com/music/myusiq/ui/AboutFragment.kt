package com.music.myusiq.ui

import android.annotation.SuppressLint
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.util.Log
import android.view.View
import android.widget.TextView
import android.widget.Toast
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import com.music.myusiq.R
import com.music.myusiq.utils.UpdateManager
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class AboutFragment : Fragment(R.layout.fragment_about) {

    @SuppressLint("SetTextI18n")
    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        val header = view.findViewById<View>(R.id.aboutHeader)
        header.findViewById<TextView>(R.id.tvHeaderTitle).setText(R.string.about_myusiq)
        header.findViewById<TextView>(R.id.tvHeaderSubtitle).visibility = View.GONE
        header.findViewById<View>(R.id.btnBack).apply {
            visibility = View.VISIBLE
            setOnClickListener { requireActivity().onBackPressedDispatcher.onBackPressed() }
        }

        try {
            val packageInfo = requireContext().packageManager.getPackageInfo(requireContext().packageName, 0)
            
            // Version
            view.findViewById<TextView>(R.id.tvAboutVersion).text = getString(R.string.version_format, packageInfo.versionName)
            
            // Maintainer
            view.findViewById<TextView>(R.id.tvAboutMaintainer).text = getString(R.string.maintainer_value)
            
            // Last Updated
            val sdf = SimpleDateFormat("MMM d, yyyy", Locale.getDefault())
            val date = Date(packageInfo.lastUpdateTime)
            view.findViewById<TextView>(R.id.tvAboutUpdated).text = sdf.format(date)
            
        } catch (e: Exception) {
            Log.e("AboutFragment", "Error getting package info", e)
        }

        view.findViewById<View>(R.id.btnCheckUpdate)?.setOnClickListener {
            lifecycleScope.launch {
                Toast.makeText(requireContext(), "Checking for updates...", Toast.LENGTH_SHORT).show()
                val updateInfo = UpdateManager.checkForUpdates(requireContext(), force = true)
                if (updateInfo == null) {
                    Toast.makeText(requireContext(), "Unable to check for updates. Check internet connection.", Toast.LENGTH_SHORT).show()
                } else if (updateInfo.hasUpdate) {
                    UpdateManager.showUpdateDialog(requireActivity(), updateInfo)
                } else {
                    Toast.makeText(requireContext(), "You are on the latest version (${updateInfo.currentVersion})!", Toast.LENGTH_SHORT).show()
                }
            }
        }

        view.findViewById<View>(R.id.btnReportBug)?.setOnClickListener {
            try {
                val intent = Intent(Intent.ACTION_VIEW, Uri.parse("https://github.com/sreejith19931/Myusiq/issues"))
                startActivity(intent)
            } catch (e: Exception) {
                Log.e("AboutFragment", "Error launching GitHub issues link", e)
            }
        }
    }
}
