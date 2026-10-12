package com.music.myusiq.ui.components

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View
import android.widget.TextView
import com.google.android.material.card.MaterialCardView
import com.music.myusiq.utils.ThemeUtils
import kotlin.math.abs

class AlphabetIndexBar @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    private val alphabet = arrayOf(
        'A', 'B', 'C', 'D', 'E', 'F', 'G', 'H', 'I', 'J',
        'K', 'L', 'M', 'N', 'O', 'P', 'Q', 'R', 'S', 'T',
        'U', 'V', 'W', 'X', 'Y', 'Z', '#'
    )

    private val activePositions = mutableMapOf<Char, Int>()
    var onLetterSelectedListener: ((Int, Char) -> Unit)? = null

    private val activePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textAlign = Paint.Align.CENTER
        typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
    }

    private val inactivePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textAlign = Paint.Align.CENTER
        typeface = Typeface.DEFAULT
    }

    private var selectedIndex = -1
    private var bubbleCard: MaterialCardView? = null
    private var bubbleText: TextView? = null

    fun updateActivePositions(map: Map<Char, Int>) {
        activePositions.clear()
        activePositions.putAll(map)
        invalidate()
    }

    fun setSelectedLetter(letter: Char) {
        val uppercase = if (letter.isLetter()) letter.uppercaseChar() else '#'
        val index = alphabet.indexOf(uppercase)
        val newIndex = if (index != -1) index else alphabet.indexOf('#')
        if (selectedIndex != newIndex) {
            selectedIndex = newIndex
            invalidate()
        }
    }

    fun attachBubbleViews(card: MaterialCardView, text: TextView) {
        this.bubbleCard = card
        this.bubbleText = text
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (height <= 0) return

        val density = context.resources.displayMetrics.density
        val itemHeight = height.toFloat() / alphabet.size
        val activeColor = ThemeUtils.getThemeColor(context, androidx.appcompat.R.attr.colorPrimary)
        val inactiveColor = Color.parseColor("#4D808080")

        val baseActiveSize = (itemHeight * 0.65f).coerceIn(10f * density, 14f * density)
        val baseInactiveSize = (itemHeight * 0.55f).coerceIn(8f * density, 12f * density)

        val defaultX = width / 2f

        for (i in alphabet.indices) {
            val letter = alphabet[i]
            val isActive = activePositions.containsKey(letter)
            val paint = if (isActive) activePaint else inactivePaint

            // Fisheye Wave Zoom Scale & Pop-Out Shift
            val scaleFactor: Float
            val xShift: Float

            if (selectedIndex != -1) {
                val dist = abs(i - selectedIndex)
                when (dist) {
                    0 -> {
                        scaleFactor = 2.0f // Active letter enlarged 2.0x!
                        xShift = -12f * density
                    }
                    1 -> {
                        scaleFactor = 1.35f
                        xShift = -6f * density
                    }
                    2 -> {
                        scaleFactor = 1.15f
                        xShift = -2f * density
                    }
                    else -> {
                        scaleFactor = 1.0f
                        xShift = 0f
                    }
                }
            } else {
                scaleFactor = 1.0f
                xShift = 0f
            }

            paint.color = if (isActive) activeColor else inactiveColor
            val baseSize = if (isActive) baseActiveSize else baseInactiveSize
            paint.textSize = baseSize * scaleFactor

            val drawX = defaultX + xShift
            val y = (i * itemHeight) + (itemHeight / 2f) - ((paint.descent() + paint.ascent()) / 2f)

            canvas.drawText(letter.toString(), drawX, y, paint)
        }
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (alphabet.isEmpty() || height <= 0) return false

        val itemHeight = height.toFloat() / alphabet.size
        val index = (event.y / itemHeight).toInt().coerceIn(0, alphabet.size - 1)

        when (event.action) {
            MotionEvent.ACTION_DOWN, MotionEvent.ACTION_MOVE -> {
                if (index != selectedIndex) {
                    selectedIndex = index
                    val letter = alphabet[index]
                    val pos = activePositions[letter]

                    if (pos != null) {
                        performHapticFeedback(android.view.HapticFeedbackConstants.KEYBOARD_TAP)
                        showBubble(letter)
                        onLetterSelectedListener?.invoke(pos, letter)
                    } else {
                        // Find nearest active letter
                        val nearestEntry = activePositions.entries.minByOrNull { abs(alphabet.indexOf(it.key) - index) }
                        if (nearestEntry != null) {
                            showBubble(letter, isInactive = true)
                            onLetterSelectedListener?.invoke(nearestEntry.value, nearestEntry.key)
                        }
                    }
                    invalidate()
                }
                return true
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                hideBubble()
                invalidate()
                return true
            }
        }
        return super.onTouchEvent(event)
    }

    private fun showBubble(letter: Char, isInactive: Boolean = false) {
        val card = bubbleCard ?: return
        val tv = bubbleText ?: return
        tv.text = letter.toString()
        card.alpha = if (isInactive) 0.6f else 1.0f
        card.visibility = VISIBLE
    }

    private fun hideBubble() {
        val card = bubbleCard ?: return
        card.animate().alpha(0f).setDuration(200).withEndAction {
            card.visibility = GONE
            card.alpha = 1.0f
        }.start()
    }
}
