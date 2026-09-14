package com.music.rhythm.ui.components

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.*
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View
import android.view.animation.LinearInterpolator
import androidx.core.graphics.ColorUtils
import androidx.core.graphics.withClip
import com.google.android.material.color.MaterialColors
import kotlin.math.*
import com.music.rhythm.R
import com.music.rhythm.utils.ThemeUtils

class AnimatedWaveSeekBar @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0,
) : View(context, attrs, defStyleAttr) {
    private var playedColor: Int
    private var unplayedColor: Int
    
    private val playedPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 12f
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }
    
    private val backgroundTrackPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 3f // Thinner
        strokeCap = Paint.Cap.ROUND
    }
    
    private val thumbPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
    }
    
    private var thumbScale = 1.0f
    private var thumbAnimator: ValueAnimator? = null
    private var isSeeking = false

    var progress: Float = 0f
        set(value) {
            field = value.coerceIn(0f, 1f)
            updatePaints()
            invalidate()
        }

    private fun updatePaints() {
        if (width > 0) {
            // 1. Played Wave Solid Color
            playedPaint.shader = null
            playedPaint.color = playedColor
            
            // 2. Unplayed Track Color (Solid for better visibility)
            val onSurface = ThemeUtils.getThemeColor(context, android.R.attr.textColorPrimary)
            backgroundTrackPaint.color = ColorUtils.setAlphaComponent(onSurface, 80) // 30% opacity for better visibility
            backgroundTrackPaint.shader = null 
        }
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        updatePaints()
    }

    var isPlaying: Boolean = false
        set(value) {
            if (field != value) {
                field = value
                if (value && !isFftModeEnabled) startWaveAnimation() else stopWaveAnimation()
            }
        }
        
    var onSeekBarChangeListener: ((Float) -> Unit)? = null
    private var phaseShift = 0f
    private var waveAnimator: ValueAnimator? = null
    private val wavePath = Path()
    private val baseThumbRadius = 8f * context.resources.displayMetrics.density
    private var fftMagnitudes: FloatArray? = null
    
    var isFftModeEnabled: Boolean = false
        set(value) {
            field = value
            if (!value) {
                fftMagnitudes = null
                if (isPlaying) startWaveAnimation()
            } else {
                stopWaveAnimation()
            }
            postInvalidate()
        }

    init {
        val defaultPlayed = MaterialColors.getColor(context, com.google.android.material.R.attr.colorPrimaryContainer, Color.RED)
        val defaultUnplayed = MaterialColors.getColor(context, com.google.android.material.R.attr.colorSurfaceVariant, Color.GRAY)
        
        val typedArray = context.obtainStyledAttributes(attrs, R.styleable.AnimatedWaveSeekBar, 0, 0)
        try {
            playedColor = typedArray.getColor(R.styleable.AnimatedWaveSeekBar_playedColor, defaultPlayed)
            unplayedColor = typedArray.getColor(R.styleable.AnimatedWaveSeekBar_unplayedColor, defaultUnplayed)
        } finally {
            typedArray.recycle()
        }

        playedPaint.color = playedColor
        thumbPaint.color = Color.WHITE
        
        // Disable hardware acceleration artifacts
        setLayerType(LAYER_TYPE_SOFTWARE, null)
    }

    fun setColors(played: Int, unplayed: Int) {
        this.playedColor = played
        this.unplayedColor = unplayed
        playedPaint.color = played
        thumbPaint.clearShadowLayer()
        backgroundTrackPaint.color = unplayed
        updatePaints()
        invalidate()
    }

    fun updateFftData(fft: ByteArray) {
        if (!isFftModeEnabled) return
        val magnitudesSize = fft.size / 2
        val currentFftMagnitudes = fftMagnitudes
        if ((currentFftMagnitudes == null) || (currentFftMagnitudes.size != magnitudesSize)) {
            fftMagnitudes = FloatArray(magnitudesSize)
        }
        for (i in 0 until magnitudesSize) {
            val r = fft[2 * i].toFloat()
            val im = fft[2 * i + 1].toFloat()
            val magnitude = hypot(r, im)
            val currentAmp = fftMagnitudes!![i]
            fftMagnitudes!![i] = currentAmp + 0.3f * (magnitude - currentAmp)
        }
        postInvalidate()
    }

    private fun startWaveAnimation() {
        if (isFftModeEnabled) return
        waveAnimator?.cancel()
        waveAnimator = ValueAnimator.ofFloat(0f, (2 * PI).toFloat()).apply {
            duration = 2000L
            repeatCount = ValueAnimator.INFINITE
            interpolator = LinearInterpolator()
            addUpdateListener { animator ->
                phaseShift = animator.animatedValue as Float
                invalidate()
            }
            start()
        }
    }

    private fun stopWaveAnimation() {
        waveAnimator?.cancel()
        phaseShift = 0f
        invalidate()
    }

    private fun animateThumb(expand: Boolean) {
        thumbAnimator?.cancel()
        val targetScale = if (expand) 1.6f else 1.0f
        thumbAnimator = ValueAnimator.ofFloat(thumbScale, targetScale).apply {
            duration = 200L
            addUpdateListener {
                thumbScale = it.animatedValue as Float
                invalidate()
            }
            start()
        }
    }

    fun setSampleAmplitudes(@Suppress("UNUSED_PARAMETER") amplitudesString: String?) {
        // No longer using precomputed static waveform in current design
        // Parameter kept for API compatibility but not used
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (width == 0 || height == 0) return
        val centerY = height / 2f
        val progressX = width * progress
        
        // 1. Draw the background track (Simple Grey Bar for unplayed part)
        // We only draw it from the current progress to the end
        canvas.drawLine(progressX, centerY, width.toFloat(), centerY, backgroundTrackPaint)
        
        // 2. Prepare the Animated Wave Path (Top overlay)
        val baseAmplitude = 15f
        val seekingBoost = if (isSeeking) 2.5f else 1.0f 
        val pulseAmplitude = if (isPlaying) {
            (baseAmplitude + (sin(phaseShift * 2) * 6f)) * seekingBoost
        } else {
            baseAmplitude * seekingBoost
        }
        
        val wavelength = 60f
        wavePath.reset()
        val magnitudes = fftMagnitudes
        
        if (isFftModeEnabled && magnitudes != null && magnitudes.isNotEmpty()) {
            var x = 0f
            var isFirst = true
            while (x <= width) {
                val normalizedPosition = x / width.toFloat()
                val targetIndex = (normalizedPosition * (magnitudes.size * 0.6f)).toInt().coerceIn(0, magnitudes.size - 1)
                val rawFrequencyValue = magnitudes[targetIndex]
                val adaptiveAmplitude = (rawFrequencyValue * 0.5f * seekingBoost).coerceIn(0f, centerY - 10f)
                val frequencyPhaseModifier = sin(x / 12f)
                val y = centerY + (frequencyPhaseModifier * adaptiveAmplitude)
                if (isFirst) {
                    wavePath.moveTo(x, y)
                    isFirst = false
                } else {
                    wavePath.lineTo(x, y)
                }
                x += 3f
            }
        } else if (isPlaying || isSeeking) {
            var x = 0f
            var isFirst = true
            while (x <= width) {
                val y = centerY + (sin((x / wavelength) - phaseShift) * pulseAmplitude)
                if (isFirst) {
                   wavePath.moveTo(x, y)
                   isFirst = false
                } else {
                    wavePath.lineTo(x, y)
                }
                x += 3f
            }
        } else {
            wavePath.moveTo(0f, centerY)
            wavePath.lineTo(width.toFloat(), centerY)
        }

        // 3. Draw the animated wave ONLY from the start to the thumb
        canvas.withClip(0f, 0f, progressX, height.toFloat()) {
            drawPath(wavePath, playedPaint)
        }
        
        // 4. Draw pulsing thumb
        val pulseScale = if (isPlaying) 1.0f + (sin(phaseShift * 3) * 0.1f) else 1.0f
        val currentThumbScale = thumbScale * pulseScale
        val safeThumbRadius = baseThumbRadius * currentThumbScale
        val safeThumbX = progressX.coerceIn(safeThumbRadius, width - safeThumbRadius)
        
        canvas.drawCircle(safeThumbX, centerY, safeThumbRadius, thumbPaint)
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.action) {
            MotionEvent.ACTION_DOWN -> {
                isSeeking = true
                animateThumb(expand = true)
                handleTouch(event)
                parent.requestDisallowInterceptTouchEvent(true)
                return true
            }
            MotionEvent.ACTION_MOVE -> {
                handleTouch(event)
                return true
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                isSeeking = false
                animateThumb(expand = false)
                performClick()
                return true
            }
        }
        return super.onTouchEvent(event)
    }

    private fun handleTouch(event: MotionEvent) {
        val touchProgress = (event.x / width.toFloat()).coerceIn(0f, 1f)
        progress = touchProgress
        onSeekBarChangeListener?.invoke(touchProgress)
    }

    override fun performClick(): Boolean {
        super.performClick()
        return true
    }
}
