package com.music.myusiq.ui.components

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.util.AttributeSet
import android.view.View
import android.view.animation.AccelerateDecelerateInterpolator
import com.google.android.material.R
import com.music.myusiq.utils.ThemeUtils
import kotlin.math.min

class PulseLoadingView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0,
) : View(context, attrs, defStyleAttr) {

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
    }
    
    private var pulseRadius = 0f
    private var pulseAlpha = 255
    private var animator: ValueAnimator? = null

    init {
        paint.color = ThemeUtils.getThemeColor(context, R.attr.colorPrimaryContainer)
        startAnimation()
    }

    private fun startAnimation() {
        animator?.cancel()
        animator = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 1500L
            repeatCount = ValueAnimator.INFINITE
            interpolator = AccelerateDecelerateInterpolator()
            addUpdateListener {
                val value = it.animatedValue as Float
                pulseRadius = value
                pulseAlpha = ((1f - value) * 255).toInt()
                invalidate()
            }
            start()
        }
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val centerX = width / 2f
        val centerY = height / 2f
        val maxRadius = min(centerX, centerY)
        
        paint.alpha = pulseAlpha
        canvas.drawCircle(centerX, centerY, maxRadius * pulseRadius, paint)
        
        // Inner static circle
        paint.alpha = 255
        canvas.drawCircle(centerX, centerY, maxRadius * 0.3f, paint)
    }

    override fun onVisibilityChanged(changedView: View, visibility: Int) {
        super.onVisibilityChanged(changedView, visibility)
        if (visibility == VISIBLE) {
            startAnimation()
        } else {
            animator?.cancel()
        }
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        animator?.cancel()
    }
}
