package com.music.rhythm.utils

import android.graphics.RenderEffect
import android.graphics.Shader
import android.os.Build
import android.view.MotionEvent
import android.view.View
import androidx.dynamicanimation.animation.DynamicAnimation
import androidx.dynamicanimation.animation.SpringAnimation
import androidx.dynamicanimation.animation.SpringForce

fun View.applySpringClick(onClick: (() -> Unit)? = null) {
    val scaleXAnim = SpringAnimation(this, DynamicAnimation.SCALE_X, 1f).apply {
        spring.dampingRatio = SpringForce.DAMPING_RATIO_MEDIUM_BOUNCY
        spring.stiffness = SpringForce.STIFFNESS_LOW
    }
    val scaleYAnim = SpringAnimation(this, DynamicAnimation.SCALE_Y, 1f).apply {
        spring.dampingRatio = SpringForce.DAMPING_RATIO_MEDIUM_BOUNCY
        spring.stiffness = SpringForce.STIFFNESS_LOW
    }

    setOnTouchListener { v, event ->
        when (event.action) {
            MotionEvent.ACTION_DOWN -> {
                scaleXAnim.cancel()
                scaleYAnim.cancel()
                this.scaleX = 0.9f
                this.scaleY = 0.9f
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                scaleXAnim.start()
                scaleYAnim.start()
                if (event.action == MotionEvent.ACTION_UP) {
                    onClick?.invoke() ?: v.performClick()
                }
            }
        }
        true
    }
}

fun View.applyBlurEffect(radius: Float) {
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        if (radius > 0) {
            val blurEffect = RenderEffect.createBlurEffect(radius, radius, Shader.TileMode.CLAMP)
            this.setRenderEffect(blurEffect)
        } else {
            this.setRenderEffect(null)
        }
    }
}
