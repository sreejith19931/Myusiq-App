package com.music.myusiq.ui.components

import android.content.Context
import android.graphics.*
import android.util.AttributeSet
import android.view.View
import androidx.core.graphics.ColorUtils
import com.google.android.material.color.MaterialColors
import com.music.myusiq.utils.ThemeUtils
import kotlin.math.hypot
import kotlin.math.max

class EqualizerSpectrumView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0,
) : View(context, attrs, defStyleAttr) {

    private val numBands = 10
    private val bandLevels = ShortArray(numBands) { 0 }
    private val fftAmplitudes = FloatArray(numBands) { 0f }
    private val displayedAmplitudes = FloatArray(numBands) { 0f }
    private val bandX = FloatArray(numBands) { 0f }
    private val bandY = FloatArray(numBands) { 0f }

    private var isEqEnabled = true

    private val gridPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 1.5f
        pathEffect = DashPathEffect(floatArrayOf(10f, 10f), 0f)
    }

    private val gridTextPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textSize = 22f
    }

    private val barPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
    }

    private val curvePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 6f
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }

    private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
    }

    private val curvePath = Path()
    private val fillPath = Path()

    private var primaryColor: Int = Color.CYAN
    private var primaryContainerColor: Int = Color.BLUE
    private var onSurfaceVariantColor: Int = Color.GRAY

    init {
        resolveColors()
        setLayerType(LAYER_TYPE_SOFTWARE, null)
    }

    private fun resolveColors() {
        primaryColor = try {
            MaterialColors.getColor(context, com.google.android.material.R.attr.colorPrimaryContainer, Color.CYAN)
        } catch (_: Exception) { Color.CYAN }

        primaryContainerColor = try {
            MaterialColors.getColor(context, com.google.android.material.R.attr.colorSecondaryContainer, Color.BLUE)
        } catch (_: Exception) { Color.BLUE }

        onSurfaceVariantColor = ThemeUtils.getThemeColor(context, android.R.attr.textColorSecondary)

        gridPaint.color = ColorUtils.setAlphaComponent(onSurfaceVariantColor, 60)
        gridTextPaint.color = ColorUtils.setAlphaComponent(onSurfaceVariantColor, 120)
        curvePaint.color = primaryColor
        barPaint.color = ColorUtils.setAlphaComponent(primaryColor, 140)
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        updateShader(w, h)
    }

    private fun updateShader(w: Int, h: Int) {
        if (w <= 0 || h <= 0) return
        val fillGradient = LinearGradient(
            0f, 0f, 0f, h.toFloat(),
            intArrayOf(
                ColorUtils.setAlphaComponent(primaryColor, 90),
                ColorUtils.setAlphaComponent(primaryContainerColor, 20),
                Color.TRANSPARENT,
            ),
            floatArrayOf(0f, 0.6f, 1f),
            Shader.TileMode.CLAMP,
        )
        fillPaint.shader = fillGradient
    }

    fun setEqEnabled(enabled: Boolean) {
        this.isEqEnabled = enabled
        alpha = if (enabled) 1.0f else 0.4f
        invalidate()
    }

    fun setBandLevels(gains: ShortArray) {
        val size = gains.size.coerceAtMost(numBands)
        System.arraycopy(gains, 0, bandLevels, 0, size)
        postInvalidate()
    }

    fun updateFftData(fft: ByteArray) {
        if (fft.size < 20) return
        val magnitudesSize = fft.size / 2
        for (i in 0 until numBands) {
            val binStart = (i * (magnitudesSize / numBands)).coerceIn(0, magnitudesSize - 1)
            val binEnd = ((i + 1) * (magnitudesSize / numBands)).coerceIn(binStart + 1, magnitudesSize)

            var maxMag = 0f
            for (j in binStart until binEnd) {
                val r = fft[2 * j].toFloat()
                val im = fft[2 * j + 1].toFloat()
                val mag = hypot(r, im)
                if (mag > maxMag) maxMag = mag
            }

            val gainFactor = 1.0f + (bandLevels[i].toFloat() / 1500f).coerceIn(-0.8f, 1.5f)
            val targetAmp = (maxMag / 120f) * gainFactor
            fftAmplitudes[i] = targetAmp.coerceIn(0.02f, 1.0f)
        }
        postInvalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (width <= 0 || height <= 0) return

        val paddingLeft = 60f
        val paddingRight = 30f
        val paddingTop = 30f
        val paddingBottom = 40f

        val drawWidth = width - paddingLeft - paddingRight
        val drawHeight = height - paddingTop - paddingBottom
        val centerY = paddingTop + drawHeight / 2f

        // 1. Draw Grid Lines & dB Text (+15dB, 0dB, -15dB)
        val dbLevels = arrayOf("+15 dB", "0 dB", "-15 dB")
        val yPositions = floatArrayOf(paddingTop, centerY, paddingTop + drawHeight)

        for (idx in dbLevels.indices) {
            val y = yPositions[idx]
            canvas.drawLine(paddingLeft, y, width - paddingRight, y, gridPaint)
            canvas.drawText(dbLevels[idx], 8f, y + 6f, gridTextPaint)
        }

        // 2. Compute Band Positions & Draw FFT Bars
        val stepX = drawWidth / (numBands - 1)
        val barWidth = max(8f, (drawWidth / numBands) * 0.45f)

        for (i in 0 until numBands) {
            bandX[i] = paddingLeft + (i * stepX)

            val currentAmp = displayedAmplitudes[i]
            val targetAmp = fftAmplitudes[i]
            displayedAmplitudes[i] = currentAmp + 0.25f * (targetAmp - currentAmp)

            val levelRatio = (bandLevels[i].toFloat() / 1500f).coerceIn(-1.0f, 1.0f)
            bandY[i] = centerY - (levelRatio * (drawHeight / 2f - 10f))

            val barAmp = displayedAmplitudes[i]
            val barHeight = barAmp * (drawHeight * 0.45f)
            val barTop = (bandY[i] - barHeight / 2f).coerceAtLeast(paddingTop)
            val barBottom = (bandY[i] + barHeight / 2f).coerceAtMost(paddingTop + drawHeight)

            val left = bandX[i] - barWidth / 2f
            val right = bandX[i] + barWidth / 2f

            val rect = RectF(left, barTop, right, barBottom)
            canvas.drawRoundRect(rect, 6f, 6f, barPaint)

            fftAmplitudes[i] *= 0.92f
        }

        // 3. Draw Overlaid Smooth EQ Curve
        curvePath.reset()
        fillPath.reset()

        curvePath.moveTo(bandX[0], bandY[0])
        fillPath.moveTo(bandX[0], paddingTop + drawHeight)
        fillPath.lineTo(bandX[0], bandY[0])

        for (i in 0 until numBands - 1) {
            val x1 = bandX[i]
            val y1 = bandY[i]
            val x2 = bandX[i + 1]
            val y2 = bandY[i + 1]

            val cx1 = x1 + (x2 - x1) / 2f
            val cy1 = y1
            val cx2 = x1 + (x2 - x1) / 2f
            val cy2 = y2

            curvePath.cubicTo(cx1, cy1, cx2, cy2, x2, y2)
            fillPath.cubicTo(cx1, cy1, cx2, cy2, x2, y2)
        }

        fillPath.lineTo(bandX[numBands - 1], paddingTop + drawHeight)
        fillPath.close()

        canvas.drawPath(fillPath, fillPaint)
        canvas.drawPath(curvePath, curvePaint)
    }
}