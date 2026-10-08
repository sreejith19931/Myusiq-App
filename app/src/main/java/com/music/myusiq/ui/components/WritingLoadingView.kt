package com.music.myusiq.ui.components

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.util.AttributeSet
import android.view.View
import android.view.animation.LinearInterpolator
import com.google.android.material.R
import com.google.android.material.color.MaterialColors

class WritingLoadingView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0,
) : View(context, attrs, defStyleAttr) {

    private val paperPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
    }
    
    private val linePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
    }
    
    private val penPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
    }

    private var animationProgress = 0f
    private var animator: ValueAnimator? = null
    private val paperRect = RectF()

    init {
        paperPaint.color = MaterialColors.getColor(context, R.attr.colorSurfaceVariant, 0)
        linePaint.color = MaterialColors.getColor(context, R.attr.colorOnSurfaceVariant, 0)
        penPaint.color = MaterialColors.getColor(context, R.attr.colorPrimaryContainer, 0)
        
        startAnimation()
    }

    private fun startAnimation() {
        animator?.cancel()
        animator = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 2000L
            repeatCount = ValueAnimator.INFINITE
            interpolator = LinearInterpolator()
            addUpdateListener {
                animationProgress = it.animatedValue as Float
                invalidate()
            }
            start()
        }
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        
        val w = width.toFloat()
        val h = height.toFloat()
        val padding = w * 0.1f
        
        // Draw Paper
        paperRect.apply {
            left = padding
            top = padding
            right = w - padding
            bottom = h - padding
        }
        val cornerRadius = w * 0.1f
        canvas.drawRoundRect(paperRect, cornerRadius, cornerRadius, paperPaint)
        
        linePaint.strokeWidth = h * 0.05f
        val lineLeft = w * 0.25f
        val lineRight = w * 0.75f
        val lineWidth = lineRight - lineLeft
        
        val lineYs = floatArrayOf(h * 0.35f, h * 0.55f, h * 0.75f)
        
        // Stages: 0-0.3, 0.33-0.63, 0.66-0.96 (with pauses)
        for (i in 0 until 3) {
            val start = i * 0.33f
            val end = start + 0.3f
            
            val lineProgress = when {
                animationProgress > end -> 1f
                animationProgress < start -> 0f
                else -> (animationProgress - start) / 0.3f
            }
            
            if (lineProgress > 0) {
                canvas.drawLine(lineLeft, lineYs[i], lineLeft + (lineWidth * lineProgress), lineYs[i], linePaint)
            }
            
            // Draw Pen
            if (animationProgress in start..end) {
                val penX = lineLeft + (lineWidth * lineProgress)
                val penY = lineYs[i] - (h * 0.05f)
                canvas.drawCircle(penX, penY, h * 0.04f, penPaint)
            }
        }
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
