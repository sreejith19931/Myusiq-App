package com.music.myusiq.utils

import android.content.Context
import android.view.LayoutInflater
import android.widget.ImageView
import android.widget.TextView
import androidx.annotation.DrawableRes
import androidx.core.content.edit
import com.google.android.material.button.MaterialButton
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.music.myusiq.R

data class GuideFeatureItem(
    @DrawableRes val iconRes: Int,
    val title: String,
    val description: String
)

object PageGuideManager {

    private const val PREFS_NAME = "myusiq_page_guides_prefs"

    fun showGuideIfNeeded(
        context: Context,
        pageKey: String,
        pageTitle: String,
        @DrawableRes headerIconRes: Int,
        features: List<GuideFeatureItem>,
        forceShow: Boolean = false
    ) {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val keyName = "has_seen_guide_$pageKey"
        
        if (!forceShow && prefs.getBoolean(keyName, false)) {
            return
        }

        val inflater = LayoutInflater.from(context)
        val dialogView = inflater.inflate(R.layout.dialog_page_guide, null)

        val tvTitle = dialogView.findViewById<TextView>(R.id.tvPageGuideTitle)
        val ivHeaderIcon = dialogView.findViewById<ImageView>(R.id.ivPageGuideHeaderIcon)
        val container = dialogView.findViewById<android.widget.LinearLayout>(R.id.layoutGuideFeaturesContainer)
        val btnGotIt = dialogView.findViewById<MaterialButton>(R.id.btnGuideGotIt)

        tvTitle.text = pageTitle
        ivHeaderIcon.setImageResource(headerIconRes)

        container.removeAllViews()
        for (feature in features) {
            val itemView = inflater.inflate(R.layout.item_page_guide_feature, container, false)
            val ivIcon = itemView.findViewById<ImageView>(R.id.ivGuideFeatureIcon)
            val tvFeatureTitle = itemView.findViewById<TextView>(R.id.tvGuideFeatureTitle)
            val tvFeatureDesc = itemView.findViewById<TextView>(R.id.tvGuideFeatureDesc)

            ivIcon.setImageResource(feature.iconRes)
            tvFeatureTitle.text = feature.title
            tvFeatureDesc.text = feature.description

            container.addView(itemView)
        }

        val dialog = MaterialAlertDialogBuilder(context)
            .setView(dialogView)
            .create()

        btnGotIt.setOnClickListener {
            prefs.edit { putBoolean(keyName, true) }
            dialog.dismiss()
        }

        dialog.show()
    }

    fun resetAllGuides(context: Context) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).edit { clear() }
    }
}
