package com.music.rhythm.ui.components

import android.content.Context
import android.util.AttributeSet
import android.util.TypedValue
import android.view.Gravity
import android.view.LayoutInflater
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import com.music.rhythm.R
import com.music.rhythm.utils.ThemeUtils

class SettingsHubItem @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0,
) : LinearLayout(context, attrs, defStyleAttr) {

    init {
        orientation = HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        setPadding(20)
        
        val typedArray = context.obtainStyledAttributes(attrs, R.styleable.SettingsHubItem)
        val iconRes = typedArray.getResourceId(R.styleable.SettingsHubItem_hubIcon, 0)
        val titleText = typedArray.getString(R.styleable.SettingsHubItem_hubTitle)
        val subtitleText = typedArray.getString(R.styleable.SettingsHubItem_hubSubtitle)
        val iconTint = typedArray.getColor(
            R.styleable.SettingsHubItem_hubIconTint,
            ThemeUtils.getThemeColor(context, android.R.attr.colorPrimary),
        )
        typedArray.recycle()

        LayoutInflater.from(context).inflate(R.layout.view_settings_hub_item, this, true)

        findViewById<ImageView>(R.id.ivHubIcon).apply {
            if (iconRes != 0) setImageResource(iconRes)
            setColorFilter(iconTint)
        }
        
        findViewById<TextView>(R.id.tvHubTitle).text = titleText
        findViewById<TextView>(R.id.tvHubSubtitle).text = subtitleText
        
        val outValue = TypedValue()
        context.theme.resolveAttribute(android.R.attr.selectableItemBackground, outValue, true)
        setBackgroundResource(outValue.resourceId)
        isClickable = true
        isFocusable = true
    }

    private fun setPadding(dp: Int) {
        val px = (dp * context.resources.displayMetrics.density).toInt()
        setPadding(px, px, px, px)
    }
}
