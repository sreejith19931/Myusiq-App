package com.music.rhythm.ui.components

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Shader
import android.util.AttributeSet
import androidx.appcompat.widget.AppCompatTextView

class LyricLineView @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null, defStyleAttr: Int = 0,
) : AppCompatTextView(context, attrs, defStyleAttr) {

    private var progress: Float = 0.0f
    private var activeColor: Int = Color.BLUE
    private var inactiveColor: Int = Color.GRAY
    private var currentShader: Shader? = null

    fun setProgress(progress: Float, activeColor: Int, inactiveColor: Int) {
        val coercedProgress = progress.coerceIn(0.0f, 1.0f)
        if ((this.progress == coercedProgress) && (this.activeColor == activeColor) && (this.inactiveColor == inactiveColor)) return
        
        this.progress = coercedProgress
        this.activeColor = activeColor
        this.inactiveColor = inactiveColor
        updateShader()
        invalidate()
    }

    fun setActive(isActive: Boolean) {
        val targetScale = if (isActive) 1.05f else 1.0f
        if (scaleX != targetScale) {
            animate().scaleX(targetScale).scaleY(targetScale).setDuration(200).start()
        }
    }

    private fun updateShader() {
        val w = width.toFloat()
        val h = height.toFloat()
        if (w <= 0 || h <= 0) return
        
        currentShader = if (progress > 0) {
            LinearGradient(
                0f, 0f, 0f, h,
                intArrayOf(activeColor, activeColor, inactiveColor, inactiveColor),
                floatArrayOf(0f, progress, progress, 1f),
                Shader.TileMode.CLAMP
            )
        } else {
            null
        }
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        updateShader()
    }

    override fun onDraw(canvas: Canvas) {
        paint.shader = currentShader
        if (currentShader == null) {
            setTextColor(inactiveColor)
        }
        super.onDraw(canvas)
    }
}
