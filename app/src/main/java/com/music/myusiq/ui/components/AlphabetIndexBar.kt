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

    val alphabet = arrayOf(
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
    private var selectedFloatIndex = -1f
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
        val targetIndex = if (index != -1) index else alphabet.indexOf('#')
        setSelectedFloatIndex(targetIndex.toFloat())
    }

    fun setSelectedFloatIndex(floatIndex: Float) {
        if (selectedFloatIndex != floatIndex) {
            selectedFloatIndex = floatIndex
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
        val activeIndex = if (selectedFloatIndex >= 0f) selectedFloatIndex else selectedIndex.toFloat()

        for (i in alphabet.indices) {
            val letter = alphabet[i]
            val isActive = activePositions.containsKey(letter)
            val paint = if (isActive) activePaint else inactivePaint

            // Fisheye Wave Zoom Scale & Pop-Out Shift
            val scaleFactor: Float
            val xShift: Float

            if (activeIndex >= 0f) {
                val dist = abs(i.toFloat() - activeIndex)
                if (dist <= 2.5f) {
                    val influence = (1.0f - (dist / 2.5f)).coerceIn(0f, 1f)
                    scaleFactor = 1.0f + (1.2f * influence) // Enlarges up to 2.2x smoothly!
                    xShift = -16f * density * influence // Pops out up to -16dp
                } else {
                    scaleFactor = 1.0f
                    xShift = 0f
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

        when (event.action) {
            MotionEvent.ACTION_DOWN, MotionEvent.ACTION_MOVE -> {
                val floatIndex = (event.y / itemHeight).coerceIn(0f, (alphabet.size - 1).toFloat())
                selectedFloatIndex = floatIndex
                val index = floatIndex.toInt().coerceIn(0, alphabet.size - 1)
                val letter = alphabet[index]
                val pos = activePositions[letter]

                if (pos != null) {
                    if (index != selectedIndex) {
                        performHapticFeedback(android.view.HapticFeedbackConstants.KEYBOARD_TAP)
                        selectedIndex = index
                    }
                    showBubble(letter)
                    onLetterSelectedListener?.invoke(pos, letter)
                } else {
                    val nearestEntry = activePositions.entries.minByOrNull { abs(alphabet.indexOf(it.key) - index) }
                    if (nearestEntry != null) {
                        if (index != selectedIndex) {
                            performHapticFeedback(android.view.HapticFeedbackConstants.KEYBOARD_TAP)
                            selectedIndex = index
                        }
                        showBubble(letter, isInactive = true)
                        onLetterSelectedListener?.invoke(nearestEntry.value, nearestEntry.key)
                    }
                }
                invalidate()
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
