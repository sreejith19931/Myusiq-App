package com.music.myusiq.utils

import android.app.Activity
import android.content.Context
import android.view.LayoutInflater
import android.widget.TextView
import androidx.core.content.edit
import com.google.android.material.button.MaterialButton
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.music.myusiq.R

object ChangelogManager {

    private const val PREFS_NAME = "myusiq_changelog_prefs"
    private const val KEY_LAST_VERSION_CODE = "last_seen_version_code"

    fun checkAndShowChangelog(activity: Activity, forceShow: Boolean = false) {
        val prefs = activity.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val lastSeenVersionCode = prefs.getInt(KEY_LAST_VERSION_CODE, 0)

        var currentVersionCode = 10303
        var currentVersionName = "1.3.0-beta3"

        try {
            val pInfo = activity.packageManager.getPackageInfo(activity.packageName, 0)
            currentVersionCode = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.P) {
                pInfo.longVersionCode.toInt()
            } else {
                @Suppress("DEPRECATION")
                pInfo.versionCode
            }
            currentVersionName = pInfo.versionName ?: "1.3.0-beta3"
        } catch (_: Exception) {}

        val isBetaBuild = currentVersionName.contains("beta", ignoreCase = true) || activity.packageName.endsWith(".beta")

        if (!forceShow) {
            if (lastSeenVersionCode == currentVersionCode) {
                return
            }
        }

        val inflater = LayoutInflater.from(activity)
        val dialogView = inflater.inflate(R.layout.dialog_changelog, null)

        val tvTitle = dialogView.findViewById<TextView>(R.id.tvChangelogTitle)
        val tvSub = dialogView.findViewById<TextView>(R.id.tvChangelogSub)
        val btnGotIt = dialogView.findViewById<MaterialButton>(R.id.btnChangelogGotIt)

        tvTitle.text = if (isBetaBuild) "What's New in Beta" else "What's New in Myusiq"
        tvSub.text = "Version $currentVersionName"

        val dialog = MaterialAlertDialogBuilder(activity)
            .setView(dialogView)
            .create()

        btnGotIt.setOnClickListener {
            prefs.edit { putInt(KEY_LAST_VERSION_CODE, currentVersionCode) }
            dialog.dismiss()
        }

        dialog.show()
    }
}
