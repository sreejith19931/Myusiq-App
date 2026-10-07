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

class AnimatedLoadingBars @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0,
) : View(context, attrs, defStyleAttr) {

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
    }
    
    private val animators = mutableListOf<ValueAnimator>()
    private val barHeights = floatArrayOf(0.3f, 0.6f, 0.4f)
    private val barCount = 3

    init {
        paint.color = ThemeUtils.getThemeColor(context, R.attr.colorPrimaryContainer)
        setupAnimators()
    }

    private fun setupAnimators() {
        for (i in 0 until barCount) {
            val animator = ValueAnimator.ofFloat(0.2f, 0.9f).apply {
                duration = 400L + (i * 100)
                repeatCount = ValueAnimator.INFINITE
                repeatMode = ValueAnimator.REVERSE
                interpolator = AccelerateDecelerateInterpolator()
                addUpdateListener {
                    barHeights[i] = it.animatedValue as Float
                    invalidate()
                }
                startDelay = (i * 150L)
            }
            animators.add(animator)
            animator.start()
        }
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val w = width.toFloat()
        val h = height.toFloat()
        val barWidth = w / (barCount * 2 - 1)

        for (i in 0 until barCount) {
            val left = i * (barWidth + barWidth)
            val barH = h * barHeights[i]
            val top = h - barH
            canvas.drawRect(left, top, left + barWidth, h, paint)
        }
    }

    override fun onVisibilityChanged(changedView: View, visibility: Int) {
        super.onVisibilityChanged(changedView, visibility)
        if (visibility == VISIBLE) {
            animators.forEach { it.start() }
        } else {
            animators.forEach { it.cancel() }
        }
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        animators.forEach { it.cancel() }
    }
}
