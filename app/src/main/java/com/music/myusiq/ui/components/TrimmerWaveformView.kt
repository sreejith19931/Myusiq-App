package com.music.myusiq.ui.components

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View
import androidx.core.graphics.ColorUtils
import com.music.myusiq.utils.ThemeUtils
import kotlin.math.abs

class TrimmerWaveformView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    private var amplitudes: FloatArray = floatArrayOf()
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        strokeCap = Paint.Cap.ROUND
    }
    private val handlePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
    }
    private val regionPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
    }
    private val playheadPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 6f
        strokeCap = Paint.Cap.ROUND
    }

    private var selectionStartMs: Float = 0f
    private var selectionEndMs: Float = 0f
    private var durationMs: Float = 1f
    private var zoomFromMs: Float = 0f
    private var zoomToMs: Float = 1f
    private var playbackPositionMs: Long = -1L

    var onRangeChangedListener: ((Float, Float) -> Unit)? = null
    var onSeekListener: ((Float) -> Unit)? = null

    private var activeDraggingHandle: DragHandle? = null

    private enum class DragHandle { START, END }

    fun setWaveform(waveformString: String?) {
        if (waveformString.isNullOrBlank()) {
            amplitudes = floatArrayOf()
        } else {
            amplitudes = waveformString.split(",").mapNotNull { it.toFloatOrNull() }.toFloatArray()
        }
        invalidate()
    }

    fun setSelectionRange(start: Float, end: Float, totalDuration: Float) {
        selectionStartMs = start.coerceIn(0f, totalDuration)
        selectionEndMs = end.coerceIn(selectionStartMs, totalDuration)
        durationMs = totalDuration.coerceAtLeast(1f)
        invalidate()
    }

    fun setZoomRange(fromMs: Float, toMs: Float, totalDuration: Float) {
        zoomFromMs = fromMs
        zoomToMs = toMs.coerceAtLeast(fromMs + 10f)
        durationMs = totalDuration.coerceAtLeast(1f)
        invalidate()
    }

    fun setPlaybackPosition(posMs: Long, totalDuration: Float) {
        playbackPositionMs = posMs
        durationMs = totalDuration.coerceAtLeast(1f)
        invalidate()
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        val w = width.toFloat()
        if (w <= 0f) return false
        val currentZoomSpan = (zoomToMs - zoomFromMs).coerceAtLeast(1f)

        val xToTime: (Float) -> Float = { x ->
            (zoomFromMs + (x / w) * currentZoomSpan).coerceIn(0f, durationMs)
        }
        val timeToX: (Float) -> Float = { timeMs ->
            ((timeMs - zoomFromMs) / currentZoomSpan) * w
        }

        val startX = timeToX(selectionStartMs)
        val endX = timeToX(selectionEndMs)

        when (event.action) {
            MotionEvent.ACTION_DOWN -> {
                val touchX = event.x
                when {
                    abs(touchX - startX) < 80f -> {
                        activeDraggingHandle = DragHandle.START
                        parent?.requestDisallowInterceptTouchEvent(true)
                        return true
                    }
                    abs(touchX - endX) < 80f -> {
                        activeDraggingHandle = DragHandle.END
                        parent?.requestDisallowInterceptTouchEvent(true)
                        return true
                    }
                    else -> {
                        val time = xToTime(touchX)
                        onSeekListener?.invoke(time)
                        return true
                    }
                }
            }
            MotionEvent.ACTION_MOVE -> {
                val time = xToTime(event.x)
                when (activeDraggingHandle) {
                    DragHandle.START -> {
                        if (time < selectionEndMs - 200f) {
                            selectionStartMs = time
                            onRangeChangedListener?.invoke(selectionStartMs, selectionEndMs)
                            invalidate()
                        }
                    }
                    DragHandle.END -> {
                        if (time > selectionStartMs + 200f) {
                            selectionEndMs = time
                            onRangeChangedListener?.invoke(selectionStartMs, selectionEndMs)
                            invalidate()
                        }
                    }
                    null -> {}
                }
                return true
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                activeDraggingHandle = null
                parent?.requestDisallowInterceptTouchEvent(false)
                return true
            }
        }
        return super.onTouchEvent(event)
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (amplitudes.isEmpty()) return

        val w = width.toFloat()
        val h = height.toFloat()
        val primaryColor = ThemeUtils.getThemeColor(context, android.R.attr.colorPrimary)
        val mutedColor = ThemeUtils.getThemeColor(context, com.google.android.material.R.attr.colorSurfaceVariant)

        val vibrantColor = primaryColor
        val dimmedColor = ColorUtils.setAlphaComponent(mutedColor, 90)
        val regionColor = ColorUtils.setAlphaComponent(primaryColor, 35)

        val currentZoomSpan = (zoomToMs - zoomFromMs).coerceAtLeast(1f)
        val maxAmp = amplitudes.maxOrNull()?.coerceAtLeast(0.01f) ?: 1f

        val timeToX: (Float) -> Float = { timeMs ->
            ((timeMs - zoomFromMs) / currentZoomSpan) * w
        }

        val startX = timeToX(selectionStartMs)
        val endX = timeToX(selectionEndMs)

        // Draw selection region overlay
        regionPaint.color = regionColor
        canvas.drawRect(startX, 0f, endX, h, regionPaint)

        val barWidth = w / amplitudes.size.coerceAtLeast(1)

        for (i in amplitudes.indices) {
            val timeMs = (i.toFloat() / amplitudes.size) * durationMs
            
            if (timeMs < zoomFromMs || timeMs > zoomToMs) continue

            val xPos = ((timeMs - zoomFromMs) / currentZoomSpan) * w
            val amp = amplitudes[i] / maxAmp
            val barHeight = (amp * h * 0.80f).coerceAtLeast(4f)
            val left = xPos
            val right = (xPos + barWidth).coerceAtMost(w)
            val top = (h - barHeight) / 2f
            val bottom = top + barHeight

            val isSelected = timeMs in selectionStartMs..selectionEndMs
            paint.color = if (isSelected) vibrantColor else dimmedColor

            canvas.drawRoundRect(left, top, right - 2f, bottom, 3f, 3f, paint)
        }

        // Draw Start and End handles
        handlePaint.color = vibrantColor
        canvas.drawRect(startX - 6f, 0f, startX + 6f, h, handlePaint)
        canvas.drawRect(endX - 6f, 0f, endX + 6f, h, handlePaint)

        // Draw handle thumbs (circles at top and bottom)
        canvas.drawCircle(startX, 16f, 16f, handlePaint)
        canvas.drawCircle(startX, h - 16f, 16f, handlePaint)
        canvas.drawCircle(endX, 16f, 16f, handlePaint)
        canvas.drawCircle(endX, h - 16f, 16f, handlePaint)

        // Draw Playhead progress line if playing
        if (playbackPositionMs in zoomFromMs.toLong()..zoomToMs.toLong()) {
            val playheadX = timeToX(playbackPositionMs.toFloat())
            playheadPaint.color = Color.WHITE
            canvas.drawLine(playheadX, 0f, playheadX, h, playheadPaint)
        }
    }
}
