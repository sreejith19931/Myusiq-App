package com.music.myusiq.ui.components

import android.content.Context
import android.util.AttributeSet
import android.util.TypedValue
import android.view.Gravity
import android.view.LayoutInflater
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import com.music.myusiq.R
import com.music.myusiq.utils.ThemeUtils

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
        var iconRes = 0
        var titleText: String? = null
        var subtitleText: String? = null
        var iconTint = ThemeUtils.getThemeColor(context, android.R.attr.colorPrimary)
        try {
            iconRes = typedArray.getResourceId(R.styleable.SettingsHubItem_hubIcon, 0)
            titleText = typedArray.getString(R.styleable.SettingsHubItem_hubTitle)
            subtitleText = typedArray.getString(R.styleable.SettingsHubItem_hubSubtitle)
            iconTint = typedArray.getColor(R.styleable.SettingsHubItem_hubIconTint, iconTint)
        } catch (_: Exception) {
            // Fallback gracefully if attribute resolution fails
        } finally {
            typedArray.recycle()
        }

        LayoutInflater.from(context).inflate(R.layout.view_settings_hub_item, this, true)

        findViewById<ImageView>(R.id.ivHubIcon).apply {
            if (iconRes != 0) setImageResource(iconRes)
            setColorFilter(iconTint)
        }
        
        findViewById<TextView>(R.id.tvHubTitle).text = titleText
        findViewById<TextView>(R.id.tvHubSubtitle).text = subtitleText
        
        val outValue = TypedValue()
        if (context.theme.resolveAttribute(android.R.attr.selectableItemBackground, outValue, true)) {
            if (outValue.resourceId != 0) {
                setBackgroundResource(outValue.resourceId)
            } else if (outValue.type >= TypedValue.TYPE_FIRST_COLOR_INT && outValue.type <= TypedValue.TYPE_LAST_COLOR_INT) {
                setBackgroundColor(outValue.data)
            }
        }
        isClickable = true
        isFocusable = true
    }

    private fun setPadding(dp: Int) {
        val px = (dp * context.resources.displayMetrics.density).toInt()
        setPadding(px, px, px, px)
    }
}
