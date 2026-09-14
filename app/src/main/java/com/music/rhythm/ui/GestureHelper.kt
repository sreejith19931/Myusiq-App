package com.music.rhythm.ui

import android.content.Context
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.View
import androidx.recyclerview.widget.RecyclerView

class GestureHelper(
    private val context: Context,
    private val onFlipCard: () -> Unit,
    private val onExpand: () -> Unit,
    private val onClosePopups: () -> Unit
) {
    private val gestureDetector = GestureDetector(context, object : GestureDetector.SimpleOnGestureListener() {
        override fun onSingleTapUp(e: MotionEvent): Boolean {
            onClosePopups()
            onFlipCard()
            return true
        }
    })

    fun setupAlbumArt(view: View) {
        view.setOnClickListener {
            if (isPlayerCollapsed()) {
                onExpand()
            } else {
                onFlipCard()
            }
        }
    }

    fun setupLyricsBack(view: View) {
        view.setOnClickListener {
            if (isPlayerCollapsed()) {
                onExpand()
            } else {
                onFlipCard()
            }
        }
    }

    fun setupRootView(view: View) {
        view.setOnClickListener {
            if (isPlayerCollapsed()) {
                onExpand()
            } else {
                onClosePopups()
            }
        }
    }

    fun setupLyricsRecyclerView(rv: RecyclerView) {
        rv.setOnTouchListener { _, event ->
            gestureDetector.onTouchEvent(event)
            false 
        }
    }

    private fun isPlayerCollapsed(): Boolean {
        return (context as? MainActivity)?.isPlayerCollapsed() ?: false
    }
}
