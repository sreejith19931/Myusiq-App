package com.music.myusiq.ui

import android.annotation.SuppressLint
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.util.Log
import android.view.View
import android.widget.TextView
import androidx.fragment.app.Fragment
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import com.music.myusiq.R

class AboutFragment : Fragment(R.layout.fragment_about) {

    @SuppressLint("SetTextI18n")
    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        val header = view.findViewById<View>(R.id.aboutHeader)
        header.findViewById<TextView>(R.id.tvHeaderTitle).setText(R.string.about_rhythm)
        header.findViewById<TextView>(R.id.tvHeaderSubtitle).visibility = View.GONE
        header.findViewById<View>(R.id.btnBack).apply {
            visibility = View.VISIBLE
            setOnClickListener { requireActivity().onBackPressedDispatcher.onBackPressed() }
        }

        try {
            val packageInfo = requireContext().packageManager.getPackageInfo(requireContext().packageName, 0)
            
            // Version
            view.findViewById<TextView>(R.id.tvAboutVersion).text = getString(R.string.version_format, packageInfo.versionName)
            
            // Package
            view.findViewById<TextView>(R.id.tvAboutPackage).text = packageInfo.packageName
            
            // Last Updated
            val sdf = SimpleDateFormat("MMM d, yyyy", Locale.getDefault())
            val date = Date(packageInfo.lastUpdateTime)
            view.findViewById<TextView>(R.id.tvAboutUpdated).text = sdf.format(date)
            
        } catch (e: Exception) {
            Log.e("AboutFragment", "Error getting package info", e)
        }

        view.findViewById<View>(R.id.btnReportBug)?.setOnClickListener {
            try {
                val packageInfo = requireContext().packageManager.getPackageInfo(requireContext().packageName, 0)
                val versionName = packageInfo.versionName
                val packageName = packageInfo.packageName
                val androidVersion = Build.VERSION.RELEASE
                val deviceModel = Build.MODEL

                val emailBody = "\n\n\n---\n" +
                        "App Version: $versionName\n" +
                        "Package: $packageName\n" +
                        "Android Version: $androidVersion\n" +
                        "Device: $deviceModel"

                val intent = Intent(Intent.ACTION_SENDTO).apply {
                    data = Uri.parse("mailto:rhythmmusicplayer@gmail.com")
                    putExtra(Intent.EXTRA_SUBJECT, "Rhythm Bug Report & Feedback")
                    putExtra(Intent.EXTRA_TEXT, emailBody)
                }
                startActivity(Intent.createChooser(intent, "Send Bug Report"))
            } catch (e: Exception) {
                Log.e("AboutFragment", "Error launching email intent", e)
            }
        }
    }
}
