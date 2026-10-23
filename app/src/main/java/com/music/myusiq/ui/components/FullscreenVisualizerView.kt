package com.music.myusiq.ui.components

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.util.AttributeSet
import android.view.View
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sin
import kotlin.random.Random

class FullscreenVisualizerView @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null, defStyleAttr: Int = 0,
) : View(context, attrs, defStyleAttr) {

    companion object {
        const val MODE_SPECTRUM = 0
        const val MODE_CIRCULAR = 1
        const val MODE_WAVEFORM = 2
        const val MODE_PARTICLES = 3
    }

    var currentMode: Int = MODE_SPECTRUM
        set(value) {
            field = value
            invalidate()
        }

    var accentColor: Int = Color.CYAN
        set(value) {
            field = value
            paint.color = value
            strokePaint.color = value
            invalidate()
        }

    var isPlaying: Boolean = false
        set(value) {
            field = value
            if (value) {
                postInvalidateOnAnimation()
            }
        }

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = accentColor
    }

    private val strokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 6f
        color = accentColor
    }

    private var magnitudes = FloatArray(32)
    private val wavePath = Path()
    private val particles = List(50) { Particle() }
    private var lastFftTime = 0L

    fun updateFftData(fftData: ByteArray) {
        if (fftData.size < 4) return
        val n = (fftData.size / 2).coerceAtMost(256)
        lastFftTime = System.currentTimeMillis()

        val numBands = 32
        for (i in 0 until numBands) {
            val logMin = 1.0
            val logMax = n.toDouble()
            val startIdx = (logMin * (logMax / logMin).pow(i.toDouble() / numBands)).toInt().coerceIn(1, n - 1)
            val endIdx = (logMin * (logMax / logMin).pow((i + 1).toDouble() / numBands)).toInt().coerceIn(startIdx + 1, n)

            var sum = 0f
            var count = 0
            for (k in startIdx until endIdx) {
                val r = fftData[2 * k].toFloat()
                val im = fftData[2 * k + 1].toFloat()
                sum += hypot(r, im)
                count++
            }

            val rawMag = if (count > 0) (sum / count) else 0f
            // Smooth peak decay
            magnitudes[i] = magnitudes[i] * 0.55f + rawMag * 0.45f
        }
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val w = width.toFloat()
        val h = height.toFloat()
        if (w <= 0 || h <= 0) return

        // Decay magnitudes if FFT stream is idle
        val now = System.currentTimeMillis()
        if (now - lastFftTime > 80) {
            for (i in magnitudes.indices) {
                magnitudes[i] *= 0.88f
            }
        }

        when (currentMode) {
            MODE_SPECTRUM -> drawSpectrumBars(canvas, w, h)
            MODE_CIRCULAR -> drawCircularPulse(canvas, w, h)
            MODE_WAVEFORM -> drawWaveform(canvas, w, h)
            MODE_PARTICLES -> drawParticles(canvas, w, h)
        }

        if (isPlaying) {
            postInvalidateOnAnimation()
        }
    }

    private fun drawSpectrumBars(canvas: Canvas, w: Float, h: Float) {
        val numBars = 32
        val barWidth = (w / numBars) * 0.72f
        val gap = (w / numBars) * 0.28f

        for (i in 0 until numBars) {
            val mag = (magnitudes[i] / 90f).coerceIn(0.04f, 1.0f)
            val barHeight = h * 0.55f * mag
            val left = i * (barWidth + gap) + gap / 2
            val top = h - barHeight
            val right = left + barWidth
            val bottom = h
            canvas.drawRect(left, top, right, bottom, paint)
        }
    }

    private fun drawCircularPulse(canvas: Canvas, w: Float, h: Float) {
        val cx = w / 2f
        val cy = h / 2f
        val baseRadius = min(w, h) * 0.22f
        val bassEnergy = magnitudes.take(4).average().toFloat() / 90f
        val currentRadius = baseRadius + (bassEnergy * 55f)

        strokePaint.color = accentColor
        canvas.drawCircle(cx, cy, currentRadius, strokePaint)

        val numSpokes = 24
        for (i in 0 until numSpokes) {
            val angle = (2 * Math.PI * i / numSpokes).toFloat()
            val mag = (magnitudes[i % magnitudes.size] / 90f).coerceIn(0.08f, 1.0f)
            val spokeLen = 45f * mag
            val x1 = cx + cos(angle) * currentRadius
            val y1 = cy + sin(angle) * currentRadius
            val x2 = cx + cos(angle) * (currentRadius + spokeLen)
            val y2 = cy + sin(angle) * (currentRadius + spokeLen)
            canvas.drawLine(x1, y1, x2, y2, strokePaint)
        }
    }

    private fun drawWaveform(canvas: Canvas, w: Float, h: Float) {
        val cy = h / 2f
        wavePath.reset()
        wavePath.moveTo(0f, cy)

        val points = 32
        val step = w / (points - 1)

        for (i in 0 until points) {
            val mag = (magnitudes[i] / 90f).coerceIn(0.02f, 1f)
            val y = cy + sin(i * 0.45f) * (mag * h * 0.25f)
            wavePath.lineTo(i * step, y)
        }

        strokePaint.color = accentColor
        canvas.drawPath(wavePath, strokePaint)
    }

    private fun drawParticles(canvas: Canvas, w: Float, h: Float) {
        val energy = magnitudes.take(6).average().toFloat() / 90f
        particles.forEach { p ->
            p.update(w, h, energy)
            paint.alpha = (p.alpha * 255).toInt()
            canvas.drawCircle(p.x, p.y, p.radius * (1f + energy), paint)
        }
        paint.alpha = 255
    }

    private class Particle {
        var x: Float = Random.nextFloat() * 1000f
        var y: Float = Random.nextFloat() * 1000f
        var radius: Float = Random.nextFloat() * 6f + 4f
        var speedY: Float = Random.nextFloat() * 2f + 1f
        var alpha: Float = Random.nextFloat() * 0.7f + 0.3f

        fun update(w: Float, h: Float, energy: Float) {
            y -= speedY * (1f + energy * 2.5f)
            if (y < 0) {
                y = h
                x = Random.nextFloat() * w
            }
        }
    }
}
