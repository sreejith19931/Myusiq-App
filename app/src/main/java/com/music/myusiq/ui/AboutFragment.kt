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
import com.music.myusiq.utils.UpdateCheckResult
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
            val isBeta = (packageInfo.versionName?.lowercase()?.contains("beta") == true) || requireContext().packageName.endsWith(".beta")
            
            val appTitle = if (isBeta) "Myusiq (beta)" else "Myusiq"
            view.findViewById<TextView>(R.id.tvAboutAppName)?.text = appTitle
            
            // Version
            view.findViewById<TextView>(R.id.tvAboutVersion).text = if (isBeta) {
                val versionNum = packageInfo.versionName?.replace(Regex("(?i)-?beta\\d*"), "")?.trim() ?: ""
                "v$versionNum (beta)"
            } else {
                getString(R.string.version_format, packageInfo.versionName)
            }
            
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
                when (val result = UpdateManager.checkForUpdates(requireContext(), force = true)) {
                    is UpdateCheckResult.Success -> {
                        val updateInfo = result.updateInfo
                        if (updateInfo.hasUpdate) {
                            UpdateManager.showUpdateDialog(requireActivity(), updateInfo)
                        } else {
                            Toast.makeText(requireContext(), "You are on the latest version (${updateInfo.currentVersion})!", Toast.LENGTH_SHORT).show()
                        }
                    }
                    is UpdateCheckResult.Error -> {
                        Toast.makeText(requireContext(), result.message, Toast.LENGTH_LONG).show()
                    }
                    is UpdateCheckResult.Skipped -> {
                        // Not skipped when force = true
                    }
                }
            }
        }

        view.findViewById<View>(R.id.btnReportBug)?.setOnClickListener {
            try {
                val intent = Intent(Intent.ACTION_VIEW, Uri.parse("https://github.com/sreejith19931/Myusiq-App/issues"))
                startActivity(intent)
            } catch (e: Exception) {
                Log.e("AboutFragment", "Error launching GitHub issues link", e)
            }
        }

        view.findViewById<View>(R.id.btnTelegramSupport)?.setOnClickListener {
            try {
                val intent = Intent(Intent.ACTION_VIEW, Uri.parse("https://t.me/+j2pPQYhxkz1mN2Yy"))
                startActivity(intent)
            } catch (e: Exception) {
                Log.e("AboutFragment", "Error launching Telegram support link", e)
            }
        }
    }
}
