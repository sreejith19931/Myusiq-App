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

        activePaint.color = activeColor
        activePaint.textSize = (itemHeight * 0.7f).coerceIn(10f * density, 16f * density)

        inactivePaint.color = inactiveColor
        inactivePaint.textSize = (itemHeight * 0.6f).coerceIn(8f * density, 14f * density)

        val x = width / 2f

        for (i in alphabet.indices) {
            val letter = alphabet[i]
            val isActive = activePositions.containsKey(letter)
            val paint = if (isActive) activePaint else inactivePaint

            val y = (i * itemHeight) + (itemHeight / 2f) - ((paint.descent() + paint.ascent()) / 2f)
            canvas.drawText(letter.toString(), x, y, paint)
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
                        val nearestEntry = activePositions.entries.minByOrNull { kotlin.math.abs(alphabet.indexOf(it.key) - index) }
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
                selectedIndex = -1
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
        card.animate().alpha(0f).setDuration(150).withEndAction {
            card.visibility = GONE
            card.alpha = 1.0f
        }.start()
    }
}
