package com.music.rhythm.utils

import android.content.Context
import android.graphics.Color
import android.util.TypedValue
import androidx.annotation.AttrRes
import androidx.core.content.ContextCompat

object ThemeUtils {
    fun getThemeColor(context: Context, @AttrRes attrColor: Int): Int {
        val typedValue = TypedValue()
        val wasResolved = context.theme.resolveAttribute(attrColor, typedValue, true)
        return if (wasResolved) {
            if ((typedValue.type >= TypedValue.TYPE_FIRST_COLOR_INT) && (typedValue.type <= TypedValue.TYPE_LAST_COLOR_INT)) {
                typedValue.data
            } else if (typedValue.resourceId != 0) {
                try {
                    ContextCompat.getColor(context, typedValue.resourceId)
                } catch (_: Exception) {
                    Color.BLACK
                }
            } else {
                Color.BLACK
            }
        } else {
            Color.BLACK // Safeguard fallback protection value
        }
    }
}
