package com.music.myusiq.ui

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.ArgbEvaluator
import android.animation.ObjectAnimator
import android.animation.ValueAnimator
import android.content.Context
import android.content.res.Configuration
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.text.TextUtils
import android.view.Gravity
import android.view.View
import android.view.animation.OvershootInterpolator
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.TextView
import com.google.android.material.button.MaterialButton
import com.google.android.material.imageview.ShapeableImageView
import com.google.android.material.progressindicator.CircularProgressIndicator
import com.music.myusiq.R
import com.music.myusiq.ui.viewmodels.PlaybackViewModel
import com.music.myusiq.utils.ThemeUtils

class AnimationHelper(
    private val context: Context,
    private val viewModel: PlaybackViewModel,
    private val root: View
) {
    private var backgroundAnimator: ValueAnimator? = null

    private val layoutCardContainer: FrameLayout = root.findViewById(R.id.layoutCardContainer)
    private val layoutLyricsBack: View = root.findViewById(R.id.layoutLyricsBack)
    private val ivFullAlbumArt: ImageView = root.findViewById(R.id.ivFullAlbumArt)
    private val tvFullTrackTitle: TextView = root.findViewById(R.id.tvFullTrackTitle)
    private val tvFullArtistName: TextView = root.findViewById(R.id.tvFullArtistName)
    private val tvPlayerTitle: TextView = root.findViewById(R.id.tvPlayerTitle)
    private val progressPlayer: CircularProgressIndicator = root.findViewById(R.id.progressPlayer)
    private val layoutPlayPauseContainer: FrameLayout = root.findViewById(R.id.layoutPlayPauseContainer)
    private val btnPlayPause: MaterialButton = root.findViewById(R.id.btnFullPlayPause)
    private val ivBlurredBackground: ImageView = root.findViewById(R.id.ivBlurredBackground)
    
    private var currentAlphaOffset: Float = 0f
    private var customMorphX: Float? = null
    private var customMorphY: Float? = null
    private var customMorphSize: Float? = null

    fun setCustomMorphStart(x: Float, y: Float, size: Float) {
        customMorphX = x
        customMorphY = y
        customMorphSize = size
    }

    fun getCurrentAlphaOffset() = currentAlphaOffset

    fun setTransitionAlpha(
        alpha: Float,
        isLyricsShowing: Boolean,
        isBlurredModeCached: Boolean,
        onSyncControlTints: () -> Unit
    ) {
        val oldOffset = currentAlphaOffset
        currentAlphaOffset = alpha
        
        if ((oldOffset <= 0.5f && alpha > 0.5f) || (oldOffset > 0.5f && alpha <= 0.5f)) {
            onSyncControlTints()
        }
        
        if (alpha <= 0.01f || alpha >= 0.99f) {
            if (alpha <= 0.01f) {
                customMorphX = null
                customMorphY = null
                customMorphSize = null
            }
        }

        val density = context.resources.displayMetrics.density
        val screenWidth = context.resources.displayMetrics.widthPixels
        val screenHeight = context.resources.displayMetrics.heightPixels
        val isLandscape = context.resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE

        val fragLoc = IntArray(2)
        root.getLocationOnScreen(fragLoc)
        val contentWidth = root.width.toFloat()
        
        val railWidth = if (isLandscape) context.resources.getDimension(R.dimen.nav_rail_width) else 0f
        
        val miniArtX = if (customMorphX != null) customMorphX!! - fragLoc[0] else (16 * density + railWidth)
        val miniArtY = if (customMorphY != null) customMorphY!! - fragLoc[1] else (if (isLandscape) 4 * density else 12 * density)
        val miniArtSize = customMorphSize ?: (if (isLandscape) 44 * density else 48 * density)
        
        val miniTextY = if (isLandscape) 10 * density else 18 * density
        val miniPlayX = contentWidth - (if (isLandscape) 52 * density else 64 * density)
        val miniPlayY = if (isLandscape) 6 * density else 12 * density
        val miniPlaySize = if (isLandscape) 40 * density else 48 * density

        var fullArtSize = layoutCardContainer.width.toFloat()
        var fullArtX = layoutCardContainer.left.toFloat()
        var fullArtY = layoutCardContainer.top.toFloat()
        
        if (fullArtSize <= 0) {
            if (isLandscape) {
                fullArtSize = screenHeight * 0.7f
                fullArtX = 32 * density
                fullArtY = 64 * density
            } else {
                fullArtSize = screenWidth - 64 * density
                fullArtX = 32 * density
                fullArtY = 100 * density
            }
        }
        
        val titleScale = (18f / 24f)
        val artistScale = (15f / 22f)

        var fullTextY = tvFullTrackTitle.top.toFloat()
        if (fullTextY <= 0) {
            fullTextY = if (isLandscape) 64 * density else fullArtY + fullArtSize + 48 * density
        }

        var fullArtistY = tvFullArtistName.top.toFloat()
        if (fullArtistY <= 0) fullArtistY = fullTextY + 36 * density
        
        var fullPlayX = layoutPlayPauseContainer.left.toFloat()
        var fullPlayY = layoutPlayPauseContainer.top.toFloat()
        if (fullPlayY <= 0) {
            if (isLandscape) {
                fullPlayX = screenWidth * 0.7f
                fullPlayY = screenHeight * 0.6f
            } else {
                fullPlayX = screenWidth / 2f - 44 * density
                fullPlayY = screenHeight - 152 * density
            }
        }

        val offset = alpha.coerceIn(0f, 1f)
        
        val cameraDist = 12000 * density
        layoutCardContainer.cameraDistance = cameraDist
        layoutLyricsBack.cameraDistance = cameraDist

        if (isLyricsShowing) {
            layoutCardContainer.rotationY = 180f * offset
            layoutLyricsBack.rotationY = 180f
            if (offset < 0.5f) {
                ivFullAlbumArt.visibility = View.VISIBLE
                layoutLyricsBack.visibility = View.GONE
            } else {
                ivFullAlbumArt.visibility = View.GONE
                layoutLyricsBack.visibility = View.VISIBLE
            }
        } else {
            layoutCardContainer.rotationY = 0f
            ivFullAlbumArt.visibility = View.VISIBLE
            layoutLyricsBack.visibility = View.GONE
        }

        ivFullAlbumArt.translationX = (1f - offset) * (miniArtX - fullArtX)
        ivFullAlbumArt.translationY = (1f - offset) * (miniArtY - fullArtY)
        val artScale = (miniArtSize / fullArtSize) * (1f - offset) + offset
        ivFullAlbumArt.scaleX = artScale
        ivFullAlbumArt.scaleY = artScale
        ivFullAlbumArt.pivotX = 0f
        ivFullAlbumArt.pivotY = 0f
        
        if (offset < 0.2f) {
            layoutCardContainer.translationZ = (1f - offset) * 10 * density
        } else if (offset > 0.8f) {
            layoutCardContainer.translationZ = 0f
        }
        
        val cornerPx = (8 * (1f - offset) + 16 * offset) * density
        (ivFullAlbumArt as? ShapeableImageView)?.let {
            it.shapeAppearanceModel = it.shapeAppearanceModel.toBuilder()
                .setAllCornerSizes(cornerPx)
                .build()
        }
        ivFullAlbumArt.alpha = 1f

        val targetGravity = if (isLandscape) Gravity.START or Gravity.CENTER_VERTICAL else Gravity.CENTER
        if (tvFullTrackTitle.gravity != targetGravity) {
            tvFullTrackTitle.gravity = targetGravity
            tvFullArtistName.gravity = targetGravity
        }

        var fullTextWidth = tvFullTrackTitle.width.toFloat()
        var fullTextX = tvFullTrackTitle.left.toFloat()
        
        if (fullTextWidth <= 0) {
            if (isLandscape) {
                fullTextX = contentWidth * 0.45f
                fullTextWidth = contentWidth * 0.55f
            } else {
                fullTextX = 0f
                fullTextWidth = contentWidth
            }
        }

        val miniTextStartX = miniArtX + miniArtSize + 12 * density
        val miniTextEndX = miniPlayX - 8 * density
        val miniTextX = (miniTextStartX + miniTextEndX) / 2f
        
        val currentTitleScale = titleScale * (1f - offset) + offset
        val currentArtistScale = artistScale * (1f - offset) + offset

        if (isLandscape) {
            tvFullTrackTitle.pivotX = 0f
            tvFullTrackTitle.translationX = (1f - offset) * (miniTextX - fullTextX - (fullTextWidth * titleScale / 2f))
            tvFullArtistName.pivotX = 0f
            tvFullArtistName.translationX = (1f - offset) * (miniTextX - fullTextX - (fullTextWidth * artistScale / 2f))
        } else {
            tvFullTrackTitle.pivotX = fullTextWidth / 2f
            tvFullTrackTitle.translationX = (1f - offset) * (miniTextX - (fullTextX + fullTextWidth / 2f))
            tvFullArtistName.pivotX = fullTextWidth / 2f
            tvFullArtistName.translationX = (1f - offset) * (miniTextX - (fullTextX + fullTextWidth / 2f))
        }

        tvFullTrackTitle.translationY = (1f - offset) * (miniTextY - fullTextY)
        tvFullTrackTitle.scaleX = currentTitleScale
        tvFullTrackTitle.scaleY = currentTitleScale
        tvFullTrackTitle.pivotY = 0f
        tvFullTrackTitle.alpha = 1f

        tvFullArtistName.translationY = (1f - offset) * (miniTextY + 24 * density - fullArtistY)
        tvFullArtistName.scaleX = currentArtistScale
        tvFullArtistName.scaleY = currentArtistScale
        tvFullArtistName.pivotY = 0f
        tvFullArtistName.alpha = 1f

        layoutPlayPauseContainer.translationX = (1f - offset) * (miniPlayX - fullPlayX)
        layoutPlayPauseContainer.translationY = (1f - offset) * (miniPlayY - fullPlayY)
        val playScale = (miniPlaySize / (96 * density)) * (1f - offset) + offset
        layoutPlayPauseContainer.scaleX = playScale
        layoutPlayPauseContainer.scaleY = playScale
        layoutPlayPauseContainer.pivotX = 0f
        layoutPlayPauseContainer.pivotY = 0f
        layoutPlayPauseContainer.alpha = 1f
        
        btnPlayPause.elevation = offset * 6 * density
        progressPlayer.alpha = (1f - (offset / 0.15f)).coerceIn(0f, 1f)
        
        if (offset < 0.01f || offset > 0.99f) {
            if (!tvFullTrackTitle.isSelected) {
                tvFullTrackTitle.isSelected = true
                tvFullTrackTitle.ellipsize = TextUtils.TruncateAt.MARQUEE
            }
        } else {
            if (tvFullTrackTitle.isSelected) {
                tvFullTrackTitle.isSelected = false
                tvFullTrackTitle.ellipsize = TextUtils.TruncateAt.END
            }
        }

        if (offset == 1f && oldOffset < 1f) {
            viewModel.currentBgColor.value?.let { startLivingBackground(it, isBlurredModeCached) }
        } else if (offset < 1f) {
            backgroundAnimator?.cancel()
            root.findViewById<View>(R.id.playerRootLayout)?.setBackgroundColor(Color.TRANSPARENT)
        }

        val scaffoldAlpha = (offset - 0.2f).coerceIn(0f, 1f)
        val bgView = root.findViewById<View>(R.id.playerPageBackground)
        val blurredBg = root.findViewById<View>(R.id.ivBlurredBackground)
        val scrimView = root.findViewById<View>(R.id.playerScrim)
        
        if (isBlurredModeCached) {
            blurredBg?.alpha = offset
            scrimView?.alpha = offset * 0.75f 
            bgView?.alpha = 1.0f // Keep solid background fully opaque but hidden behind blurred art
        } else {
            blurredBg?.alpha = 0f
            scrimView?.alpha = 0f
            bgView?.alpha = 1.0f
        }
        
        val themeBg = ThemeUtils.getThemeColor(context, android.R.attr.colorBackground)
        val targetBg = viewModel.currentBgColor.value ?: themeBg
        bgView?.setBackgroundColor(targetBg)
        
        listOf(
            R.id.playerDragHandle, R.id.tvPlayerTitle, R.id.waveformSeekBar,
            R.id.layoutTimeInfo, R.id.btnFullShuffle, R.id.btnFullPrevious,
            R.id.btnFullRewind, R.id.btnFullForward, R.id.btnFullNext,
            R.id.btnFullRepeat, R.id.layoutUtilityControls, R.id.btnDrivingMode
        ).forEach { id ->
            root.findViewById<View>(id)?.alpha = scaffoldAlpha
        }
    }

    fun animateButton(view: View) {
        view.animate()
            .scaleX(0.85f)
            .scaleY(0.85f)
            .setDuration(100)
            .withEndAction {
                view.animate()
                    .scaleX(1.0f)
                    .scaleY(1.0f)
                    .setDuration(150)
                    .setInterpolator(OvershootInterpolator())
                    .start()
            }
            .start()
    }

    fun flipCard(
        isLyricsShowing: Boolean,
        updateLyricsVisibility: (Boolean) -> Unit,
        onFlipEnd: (Boolean) -> Unit
    ) {
        val density = context.resources.displayMetrics.density
        val cameraDist = 12000 * density
        layoutCardContainer.cameraDistance = cameraDist
        layoutLyricsBack.cameraDistance = cameraDist

        val targetIsLyricsShowing = !isLyricsShowing
        val start = if (isLyricsShowing) 180f else 0f
        val end = if (isLyricsShowing) 0f else 180f
        
        var visibilityToggled = false
        
        ObjectAnimator.ofFloat(layoutCardContainer, "rotationY", start, end).apply {
            duration = 400
            addUpdateListener { anim ->
                if (anim.animatedFraction >= 0.5f && !visibilityToggled) {
                    visibilityToggled = true
                    if (targetIsLyricsShowing) {
                        ivFullAlbumArt.visibility = View.GONE
                        layoutLyricsBack.visibility = View.VISIBLE
                        layoutLyricsBack.rotationY = 180f
                    } else {
                        layoutLyricsBack.visibility = View.GONE
                        ivFullAlbumArt.visibility = View.VISIBLE
                    }
                    updateLyricsVisibility(targetIsLyricsShowing)
                }
            }
            addListener(object : AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: Animator) { 
                    if (!targetIsLyricsShowing) {
                        layoutCardContainer.rotationY = 0f
                    }
                    onFlipEnd(targetIsLyricsShowing)
                }
            })
            start()
        }
    }

    fun startLivingBackground(color: Int?, isBlurredModeCached: Boolean) {
        backgroundAnimator?.cancel()
        if (isBlurredModeCached) return
        
        val rootBackground = root.findViewById<View>(R.id.playerPageBackground) ?: return
        val themeBg = ThemeUtils.getThemeColor(context, android.R.attr.colorBackground)
        val targetColor = color ?: themeBg
        
        val oldColor = (rootBackground.background as? ColorDrawable)?.color ?: themeBg
        
        backgroundAnimator = ValueAnimator.ofObject(ArgbEvaluator(), oldColor, targetColor).apply {
            duration = 1000
            addUpdateListener { anim ->
                rootBackground.setBackgroundColor(anim.animatedValue as Int)
            }
            start()
        }
    }

    fun cancelBackgroundAnimation() {
        backgroundAnimator?.cancel()
    }
}
