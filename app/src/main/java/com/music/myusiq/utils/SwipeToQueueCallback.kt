package com.music.myusiq.utils

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.graphics.RectF
import android.view.HapticFeedbackConstants
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.ItemTouchHelper
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.color.MaterialColors
import com.music.myusiq.R

class SwipeToQueueCallback(
    context: Context,
    private val onSwipedToQueue: (position: Int) -> Unit,
) : ItemTouchHelper.SimpleCallback(0, ItemTouchHelper.RIGHT) {
    private val backgroundPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val clearPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        xfermode = PorterDuffXfermode(PorterDuff.Mode.CLEAR)
    }
    private val queueIcon = ContextCompat.getDrawable(context, R.drawable.ic_queue)
    private val intrinsicWidth = queueIcon?.intrinsicWidth ?: 0
    private val intrinsicHeight = queueIcon?.intrinsicHeight ?: 0

    init {
        backgroundPaint.color = MaterialColors.getColor(
            context,
            com.google.android.material.R.attr.colorPrimaryContainer,
            0xFFD0BCFF.toInt()
        )
    }
    override fun onMove(
        recyclerView: RecyclerView,
        viewHolder: RecyclerView.ViewHolder,
        target: RecyclerView.ViewHolder
    ): Boolean = false
    override fun onChildDraw(
        c: Canvas,
        recyclerView: RecyclerView,
        viewHolder: RecyclerView.ViewHolder,
        dX: Float,
        dY: Float,
        actionState: Int,
        isCurrentlyActive: Boolean,
    ) {
        val itemView = viewHolder.itemView
        val itemHeight = itemView.bottom - itemView.top
        val isCanceled = (dX == 0f) && !isCurrentlyActive
        if (isCanceled) {
            c.drawRect(
                itemView.left.toFloat(),
                itemView.top.toFloat(),
                itemView.left + dX,
                itemView.bottom.toFloat(),
                clearPaint
            )
            super.onChildDraw(c, recyclerView, viewHolder, dX, dY, actionState, isCurrentlyActive)
            return
        }
        val horizontalMargin = (8 * recyclerView.context.resources.displayMetrics.density)
        val cornerRadius = (12 * recyclerView.context.resources.displayMetrics.density)
        val backgroundRect = RectF(
            itemView.left.toFloat() + horizontalMargin,
            itemView.top.toFloat() + (4 * recyclerView.context.resources.displayMetrics.density), // match vertical margin
            itemView.left + dX,
            itemView.bottom.toFloat() - (4 * recyclerView.context.resources.displayMetrics.density)
        )
        c.drawRoundRect(backgroundRect, cornerRadius, cornerRadius, backgroundPaint)
        if (dX > (horizontalMargin + intrinsicWidth)) {
            val iconTop = itemView.top + (itemHeight - intrinsicHeight) / 2
            val iconMargin = (itemView.height - intrinsicHeight) / 2
            val iconLeft = itemView.left + iconMargin
            val iconRight = itemView.left + iconMargin + intrinsicWidth
            val iconBottom = iconTop + intrinsicHeight
            queueIcon?.let {
                it.setBounds(iconLeft, iconTop, iconRight, iconBottom)
                it.setTint(
                    MaterialColors.getColor(
                        recyclerView.context,
                        com.google.android.material.R.attr.colorOnPrimaryContainer,
                        0xFF381E72.toInt()
                    )
                )
                it.draw(c)
            }
        }
        super.onChildDraw(c, recyclerView, viewHolder, dX, dY, actionState, isCurrentlyActive)
    }

    override fun onSwiped(viewHolder: RecyclerView.ViewHolder, direction: Int) {
        val position = viewHolder.bindingAdapterPosition
        if (position != RecyclerView.NO_POSITION) {
            viewHolder.itemView.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
            onSwipedToQueue(position)
        }
    }
}