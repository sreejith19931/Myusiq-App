package com.music.rhythm.utils

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper

/**
 * Unwraps the context to find the underlying Activity, if any.
 */
fun Context.findActivity(): Activity? {
    var context = this
    while (context is ContextWrapper) {
        if (context is Activity) return context
        context = context.baseContext
    }
    return null
}

/**
 * Checks if the context is associated with a destroyed or finishing activity.
 */
fun Context.isActivityDestroyed(): Boolean {
    val activity = findActivity()
    return (activity?.isDestroyed == true) || (activity?.isFinishing == true)
}
