package com.music.myusiq.ui

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.ObjectAnimator
import android.animation.ValueAnimator
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.res.ColorStateList
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Typeface
import android.media.AudioManager
import android.os.Build
import android.os.Bundle
import android.provider.MediaStore
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.Gravity
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast
import androidx.core.graphics.ColorUtils
import androidx.core.view.isVisible
import androidx.palette.graphics.Palette
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updateLayoutParams
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.LinearSmoothScroller
import androidx.recyclerview.widget.RecyclerView
import com.bumptech.glide.Glide
import com.bumptech.glide.signature.ObjectKey
import com.bumptech.glide.request.target.CustomTarget
import com.bumptech.glide.request.transition.Transition
import com.google.android.material.button.MaterialButton
import com.google.android.material.imageview.ShapeableImageView
import com.google.android.material.progressindicator.CircularProgressIndicator
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import android.content.ContentUris
import android.content.IntentFilter
import android.content.res.Configuration
import android.graphics.Canvas
import android.graphics.RenderEffect
import android.graphics.Shader
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Drawable
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.text.TextUtils
import android.util.DisplayMetrics
import android.util.Log
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.ViewTreeObserver
import android.view.animation.OvershootInterpolator
import androidx.core.net.toUri
import com.google.android.material.card.MaterialCardView
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.textfield.TextInputEditText
import com.music.myusiq.domain.models.Song
import com.music.myusiq.service.PlaybackService
import com.music.myusiq.R
import com.music.myusiq.ui.viewmodels.LyricsResponse
import com.music.myusiq.ui.viewmodels.PlaybackViewModel
import com.music.myusiq.utils.TransliterationUtil
import com.music.myusiq.service.FftEngineManager
import com.music.myusiq.ui.components.*
import com.music.myusiq.utils.*
import com.music.myusiq.ui.*
import kotlin.math.max
import kotlin.math.min

class PlayerFragment : Fragment(R.layout.fragment_player) {
    private val viewModel: PlaybackViewModel by activityViewModels()
    private lateinit var themeManager: ThemeSettingsManager
    private lateinit var layoutCardContainer: FrameLayout
    private lateinit var layoutLyricsBack: View
    private lateinit var rvLyrics: RecyclerView
    private lateinit var lyricsAdapter: LyricsAdapter
    
    private lateinit var progressPlayer: CircularProgressIndicator
    private lateinit var layoutPlayPauseContainer: FrameLayout
    
    private lateinit var ivFullAlbumArt: ImageView
    private lateinit var ivBlurredBackground: ImageView
    private lateinit var tvFullTrackTitle: TextView
    private lateinit var tvFullArtistName: TextView
    private lateinit var tvPlayerTitle: TextView
    private lateinit var playerDragHandle: View
    private lateinit var waveformSeekBar: AnimatedWaveSeekBar
    private lateinit var btnShuffle: MaterialButton
    private lateinit var btnPrevious: MaterialButton
    private lateinit var btnRewind: MaterialButton
    private lateinit var btnForward: MaterialButton
    private lateinit var btnPlayPause: MaterialButton
    private lateinit var btnNext: MaterialButton
    private lateinit var btnRepeat: MaterialButton
    private lateinit var btnVolumeToggle: MaterialButton
    private lateinit var btnSleepTimer: MaterialButton
    private lateinit var btnMoreLikeThis: MaterialButton
    private lateinit var btnDrivingMode: MaterialButton
    private lateinit var layoutSliderExpandable: View
    private lateinit var sbVolume: SeekBar
    private lateinit var tvVolumeNumber: TextView
    private lateinit var tvCurrentTime: TextView
    private lateinit var tvTotalDuration: TextView
    private lateinit var tvSleepTimerStatus: TextView
    private lateinit var btnFavorite: MaterialButton
    private lateinit var btnPlaybackSpeed: MaterialButton

    private lateinit var btnLyricsPlus: MaterialButton
    private lateinit var btnLyricsMinus: MaterialButton
    private lateinit var tvLyricsOffset: TextView
    private lateinit var btnLyricsTransliterate: MaterialButton
    private lateinit var btnLyricsEdit: MaterialButton
    private lateinit var btnLyricsResetSearch: MaterialButton
    private lateinit var btnLyricsPoster: MaterialButton
    private lateinit var tvLyricOverlay: TextView
    private lateinit var tvMinimalistLyric: TextView
    private lateinit var layoutLyricsControls: View
    private lateinit var layoutLyricsLoading: View
    private lateinit var layoutLyricsError: View
    private lateinit var tvLyricsError: TextView
    private lateinit var btnLyricsRetry: MaterialButton

    private var originalLyricsText: String? = null
    private var transliteratedLyricsText: String? = null
    private var showTransliteratedLyrics: Boolean = false
    
    private lateinit var visualizerComponent: VisualizerComponent
    private lateinit var gestureHelper: GestureHelper
    private lateinit var animationHelper: AnimationHelper
    
    private lateinit var layoutSuggestionBanner: View
    private lateinit var tvSuggestionText: TextView
    private lateinit var tvSuggestionSubtext: TextView
    private lateinit var btnSuggestionApply: MaterialButton
    private lateinit var btnSuggestionDismiss: MaterialButton
    
    private var isLyricsShowing: Boolean = false
    private var lastAnimatedSongId: Long = -1L
    private var isShowingRemainingTime = false
    private var isBlurredModeCached: Boolean = false
    private var lyricsPromptDialog: androidx.appcompat.app.AlertDialog? = null

    fun setCustomMorphStart(x: Float, y: Float, size: Float) {
        animationHelper.setCustomMorphStart(x, y, size)
    }

    fun setTransitionAlpha(alpha: Float) {
        if (!isAdded) return
        animationHelper.setTransitionAlpha(alpha, isLyricsShowing, isBlurredModeCached) {
            syncControlTints()
        }
    }

    private fun updateDurationDisplay() {
        val song = viewModel.currentPlayingSong.value ?: return
        val pos = viewModel.currentPosition.value
        if (isShowingRemainingTime) {
            val remaining = max(0, song.duration - pos)
            tvTotalDuration.text = "-${formatTime(remaining)}"
        } else {
            tvTotalDuration.text = formatTime(song.duration)
        }
    }
    private lateinit var audioManager: AudioManager
    private var currentLyrics: List<LyricLine> = emptyList()
    private var activeLyricIndex: Int = -1
    private var isAutoScrolling = true
    private var lyricOffsetMs: Long = 0L

    data class LyricLine(
        val timeMs: Long, 
        val text: String,
        var durationMs: Long = 0,
        var preGapMs: Long = 0
    )

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        themeManager = ThemeSettingsManager(requireContext())
        audioManager = requireContext().getSystemService(Context.AUDIO_SERVICE) as AudioManager

        initViews(view)
        
        visualizerComponent = VisualizerComponent(requireContext(), waveformSeekBar, viewModel)
        animationHelper = AnimationHelper(requireContext(), viewModel, view)
        gestureHelper = GestureHelper(
            requireContext(),
            onFlipCard = { flipCard() },
            onExpand = { (activity as? MainActivity)?.expandFullPlayer() },
            onClosePopups = { closeAllPopups() }
        )

        setupListeners()
        setupViewModelObservers()
        setupVolumeControl()
        
        visualizerComponent.setup(viewLifecycleOwner)

        ViewCompat.setOnApplyWindowInsetsListener(view) { _, insets ->
            val systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            playerDragHandle.updateLayoutParams<ViewGroup.MarginLayoutParams> {
                topMargin = systemBars.top + (8 * resources.displayMetrics.density).toInt()
            }
            insets
        }
        
        // Ensure blurred mode state is cached early
        viewLifecycleOwner.lifecycleScope.launch {
            isBlurredModeCached = themeManager.useBlurredBackgroundFlow.first()
            updateSongUI(viewModel.currentPlayingSong.value)
        }

        // Initialize UI based on current expansion state to avoid disruption during theme changes
        view.viewTreeObserver.addOnGlobalLayoutListener(object : ViewTreeObserver.OnGlobalLayoutListener {
            override fun onGlobalLayout() {
                if (isAdded) {
                    val mainActivity = activity as? MainActivity
                    if (mainActivity != null) {
                        val offset = if (mainActivity.isPlayerCollapsed()) 0f else 1f
                        updateSongUI(viewModel.currentPlayingSong.value)
                        setTransitionAlpha(offset)
                    }
                }
                view.viewTreeObserver.removeOnGlobalLayoutListener(this)
            }
        })

        gestureHelper.setupRootView(view)
    }

    private fun initViews(view: View) {
        layoutCardContainer = view.findViewById(R.id.layoutCardContainer)
        layoutLyricsBack = view.findViewById(R.id.layoutLyricsBack)
        rvLyrics = view.findViewById(R.id.rvLyrics)
        lyricsAdapter = LyricsAdapter()
        rvLyrics.layoutManager = LinearLayoutManager(requireContext())
        rvLyrics.adapter = lyricsAdapter
        rvLyrics.itemAnimator = null

        waveformSeekBar = view.findViewById(R.id.waveformSeekBar)
        ivFullAlbumArt = view.findViewById(R.id.ivFullAlbumArt)
        ivFullAlbumArt.transitionName = "album_art_transition"
        tvFullTrackTitle = view.findViewById(R.id.tvFullTrackTitle)
        tvFullArtistName = view.findViewById(R.id.tvFullArtistName)
        tvPlayerTitle = view.findViewById(R.id.tvPlayerTitle)
        playerDragHandle = view.findViewById(R.id.playerDragHandle)
        playerDragHandle.setOnClickListener { (activity as? MainActivity)?.collapseFullPlayer() }
        btnShuffle = view.findViewById(R.id.btnFullShuffle)
        btnPrevious = view.findViewById(R.id.btnFullPrevious)
        btnPlayPause = view.findViewById(R.id.btnFullPlayPause)
        btnNext = view.findViewById(R.id.btnFullNext)
        btnRepeat = view.findViewById(R.id.btnFullRepeat)
        sbVolume = view.findViewById(R.id.sbVolume)
        tvVolumeNumber = view.findViewById(R.id.tvVolumeNumber)
        tvCurrentTime = view.findViewById(R.id.tvCurrentTime)
        tvTotalDuration = view.findViewById(R.id.tvTotalDuration)
        btnVolumeToggle = view.findViewById(R.id.btnVolumeToggle)
        layoutSliderExpandable = view.findViewById(R.id.layoutSliderExpandable)
        btnSleepTimer = view.findViewById(R.id.btnSleepTimer)
        btnMoreLikeThis = view.findViewById(R.id.btnMoreLikeThis)
        btnDrivingMode = view.findViewById(R.id.btnDrivingMode)
        tvSleepTimerStatus = view.findViewById(R.id.tvSleepTimerStatus)
        btnPlaybackSpeed = view.findViewById(R.id.btnPlaybackSpeed)
        btnRewind = view.findViewById(R.id.btnFullRewind)
        btnForward = view.findViewById(R.id.btnFullForward)
        btnFavorite = view.findViewById(R.id.btnFavorite)

        btnLyricsPlus = view.findViewById(R.id.btnLyricsPlus)
        btnLyricsMinus = view.findViewById(R.id.btnLyricsMinus)
        tvLyricsOffset = view.findViewById(R.id.tvLyricsOffset)
        btnLyricsTransliterate = view.findViewById(R.id.btnLyricsTransliterate)
        btnLyricsEdit = view.findViewById(R.id.btnLyricsEdit)
        btnLyricsResetSearch = view.findViewById(R.id.btnLyricsResetSearch)
        btnLyricsPoster = view.findViewById(R.id.btnLyricsPoster)
        tvLyricOverlay = view.findViewById(R.id.tvLyricOverlay)
        tvMinimalistLyric = view.findViewById(R.id.tvMinimalistLyric)
        layoutLyricsControls = view.findViewById(R.id.layoutLyricsControls)
        layoutLyricsLoading = view.findViewById(R.id.layoutLyricsLoading)
        layoutLyricsError = view.findViewById(R.id.layoutLyricsError)
        tvLyricsError = view.findViewById(R.id.tvLyricsError)
        btnLyricsRetry = view.findViewById(R.id.btnLyricsRetry)
        updateOffsetUI()

        layoutSuggestionBanner = view.findViewById(R.id.layoutSuggestionBanner)
        tvSuggestionText = view.findViewById(R.id.tvSuggestionText)
        tvSuggestionSubtext = view.findViewById(R.id.tvSuggestionSubtext)
        btnSuggestionApply = view.findViewById(R.id.btnSuggestionApply)
        btnSuggestionDismiss = view.findViewById(R.id.btnSuggestionDismiss)
        
        progressPlayer = view.findViewById<CircularProgressIndicator>(R.id.progressPlayer)
        layoutPlayPauseContainer = view.findViewById(R.id.layoutPlayPauseContainer)
        ivBlurredBackground = view.findViewById(R.id.ivBlurredBackground)
    }

    private var volumeHideRunnable: Runnable? = null
    private val volumeHandler = Handler(Looper.getMainLooper())

    private fun setupListeners() {
        btnPlayPause.setOnClickListener { 
            animateButton(it)
            viewModel.togglePlayPause() 
        }
        btnNext.setOnClickListener { 
            animateButton(it)
            viewModel.playNextSong() 
        }
        btnPrevious.setOnClickListener { 
            animateButton(it)
            viewModel.playPreviousSong() 
        }
        
        setupSeekButtons()

        btnShuffle.setOnClickListener { 
            animateButton(it)
            viewModel.setShuffleEnabled(!(viewModel.shuffleModeEnabled.value)) 
        }
        btnRepeat.setOnClickListener {
            animateButton(it)
            val next = when (viewModel.currentRepeatState) {
                PlaybackService.RepeatState.OFF -> PlaybackService.RepeatState.ALL
                PlaybackService.RepeatState.ALL -> PlaybackService.RepeatState.ONE
                else -> PlaybackService.RepeatState.OFF
            }
            viewModel.setRepeatState(next)
        }
        btnFavorite.setOnClickListener { 
            animateButton(it)
            viewModel.toggleFavoriteForCurrentTrack() 
        }
        btnMoreLikeThis.setOnClickListener { 
            animateButton(it)
            viewModel.currentPlayingSong.value?.let { viewModel.playMoreLikeThis(it) } 
        }
        btnDrivingMode.setOnClickListener {
            animateButton(it)
            (activity as? MainActivity)?.showFullScreenOverlay(DrivingModeFragment())
        }
        btnSleepTimer.setOnClickListener {
            SleepTimerBottomSheet.newInstance().show(childFragmentManager, SleepTimerBottomSheet.TAG)
        }
        btnPlaybackSpeed.setOnClickListener {
            val current = viewModel.playbackSpeed.value ?: 1.0f
            val next = when (current) {
                1.0f -> 1.25f
                1.25f -> 1.5f
                1.5f -> 2.0f
                2.0f -> 0.5f
                0.5f -> 0.75f
                else -> 1.0f
            }
            viewModel.setPlaybackSpeed(next)
        }
        
        tvTotalDuration.setOnClickListener {
            isShowingRemainingTime = !isShowingRemainingTime
            updateDurationDisplay()
        }

        waveformSeekBar.onSeekBarChangeListener = { progress ->
            viewModel.currentPlayingSong.value?.let { song ->
                viewModel.seekTo((progress * song.duration).toInt())
            }
        }
        
        gestureHelper.setupAlbumArt(ivFullAlbumArt)
        gestureHelper.setupLyricsBack(layoutLyricsBack)
        gestureHelper.setupLyricsRecyclerView(rvLyrics)
        
        // Ensure clicking lyrics or minimalist view flips back
        tvMinimalistLyric.setOnClickListener { 
            closeAllPopups()
            flipCard() 
        }
        
        btnLyricsPlus.setOnClickListener {
            lyricOffsetMs += 500
            updateOffsetUI()
        }
        btnLyricsMinus.setOnClickListener {
            lyricOffsetMs -= 500
            updateOffsetUI()
        }
        btnLyricsTransliterate.setOnClickListener {
            if (originalLyricsText.isNullOrBlank()) return@setOnClickListener
            showTransliteratedLyrics = !showTransliteratedLyrics
            val toastMsg = if (showTransliteratedLyrics) R.string.showing_transliterated_lyrics else R.string.showing_original_lyrics
            Toast.makeText(requireContext(), toastMsg, Toast.LENGTH_SHORT).show()
            updateTransliterateButtonState()

            val textToDisplay = if (showTransliteratedLyrics && !transliteratedLyricsText.isNullOrBlank()) {
                transliteratedLyricsText!!
            } else {
                originalLyricsText!!
            }
            parseLyrics(textToDisplay)
        }
        btnLyricsEdit.setOnClickListener {
            showLyricsEditorDialog()
        }
        
        btnLyricsResetSearch.setOnClickListener {
            showLyricsSearchDialog()
        }
        
        btnLyricsPoster.isEnabled = false
        btnLyricsPoster.alpha = 0.38f
        btnLyricsPoster.setOnClickListener {
            Toast.makeText(requireContext(), "Lyric poster feature coming soon", Toast.LENGTH_SHORT).show()
        }
        
        btnLyricsResetSearch.setOnLongClickListener {
            val song = viewModel.currentPlayingSong.value ?: return@setOnLongClickListener true
            MaterialAlertDialogBuilder(requireContext())
                .setTitle("Delete Lyrics?")
                .setMessage("This will remove local lyrics for this track.")
                .setNegativeButton("Cancel", null)
                .setPositiveButton("Delete") { _, _ ->
                    lifecycleScope.launch {
                        viewModel.deleteLyrics(song.path)
                        loadLyrics(song)
                    }
                }
                .show()
            true
        }

        btnLyricsRetry.setOnClickListener {
            viewModel.currentPlayingSong.value?.let { loadLyrics(it) }
        }

        view?.findViewById<View>(R.id.btnLyricsSearchManual)?.setOnClickListener {
            showLyricsSearchDialog()
        }
        
        btnSuggestionDismiss.setOnClickListener {
            layoutSuggestionBanner.visibility = View.GONE
        }

        rvLyrics.addOnScrollListener(object : RecyclerView.OnScrollListener() {
            override fun onScrollStateChanged(recyclerView: RecyclerView, newState: Int) {
                if (newState == RecyclerView.SCROLL_STATE_DRAGGING) {
                    isAutoScrolling = false
                }
            }
        })
    }

    private fun setupViewModelObservers() {
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch {
                    themeManager.useBlurredBackgroundFlow.collectLatest { useBlurred ->
                        isBlurredModeCached = useBlurred
                        ivBlurredBackground.isVisible = useBlurred
                        val bgView = view?.findViewById<View>(R.id.playerPageBackground)
                        if (useBlurred) {
                            animationHelper.cancelBackgroundAnimation()
                            updateBlurredBackground(viewModel.currentPlayingSong.value)
                            // If we are already expanded, ensure alpha is updated
                            val mainActivity = activity as? MainActivity
                            if (mainActivity != null && !mainActivity.isPlayerCollapsed()) {
                                setTransitionAlpha(1.0f)
                            }
                        } else {
                            ivBlurredBackground.alpha = 0f
                            if (animationHelper.getCurrentAlphaOffset() > 0.9f) bgView?.alpha = 1f
                            viewModel.currentBgColor.value?.let { startLivingBackground(it) }
                        }
                    }
                }
                launch {
                    viewModel.settingsChangedSignal.collectLatest {
                        // Refresh cached settings when signaled
                        isBlurredModeCached = themeManager.useBlurredBackgroundFlow.first()
                        ivBlurredBackground.isVisible = isBlurredModeCached
                        if (isBlurredModeCached) {
                            updateBlurredBackground(viewModel.currentPlayingSong.value)
                        }
                        
                        // Force a refresh of all control tints immediately
                        syncControlTints()
                        
                        // Force a transition sync to update alphas
                        val mainActivity = activity as? MainActivity
                        if (mainActivity != null) {
                            val offset = if (mainActivity.isPlayerCollapsed()) 0f else 1f
                            setTransitionAlpha(offset)
                        }
                    }
                }
                launch {
                    themeManager.useAlbumArtThemingFlow.collectLatest { useAdaptive ->
                        if (!useAdaptive) {
                            animationHelper.cancelBackgroundAnimation()
                            val baseColor = ThemeUtils.getThemeColor(requireContext(), android.R.attr.colorBackground)
                            view?.findViewById<View>(R.id.playerPageBackground)?.setBackgroundColor(baseColor)
                            view?.findViewById<View>(R.id.playerRootLayout)?.setBackgroundColor(Color.TRANSPARENT)
                            syncControlTints()
                        } else {
                            viewModel.currentBgColor.value?.let { startLivingBackground(it) }
                        }
                    }
                }
                launch { viewModel.currentPlayingSong.collectLatest { song -> updateSongUI(song) } }
                launch { viewModel.isPlaying.collectLatest { isPlaying -> 
                    btnPlayPause.setIconResource(if (isPlaying) R.drawable.ic_pause else R.drawable.ic_play)
                } }
                launch { viewModel.currentPosition.collectLatest { pos -> 
                    tvCurrentTime.text = formatTime(pos)
                    updateDurationDisplay()
                    updateActiveLyric(pos)
                } }
                launch { viewModel.shuffleModeEnabled.collectLatest { syncControlTints() } }
                launch { viewModel.repeatMode.collectLatest { syncControlTints() } }
                launch { viewModel.isSleepTimerActive.collectLatest { syncControlTints() } }
                launch { viewModel.isCurrentTrackFavorite.collectLatest { syncControlTints() } }
                launch { viewModel.playbackSpeed.collectLatest { speed -> 
                    btnPlaybackSpeed.text = "${speed}x"
                    syncControlTints() 
                } }
                launch { viewModel.playbackProgressPct.collectLatest { progress -> 
                    progressPlayer.setProgress(progress, true) 
                } }
                launch { viewModel.currentBgColor.collectLatest { color -> startLivingBackground(color) } }
                launch { viewModel.currentOnBgColor.collectLatest { syncControlTints() } }
                launch { viewModel.currentVibrantColor.collectLatest { syncControlTints() } }
                launch { themeManager.useAlbumArtThemingFlow.collectLatest { syncControlTints() } }
                launch { viewModel.sleepTimerTimeLeft.collectLatest { timeLeft ->
                    tvSleepTimerStatus.text = timeLeft
                    tvSleepTimerStatus.isVisible = timeLeft.isNotEmpty()
                } }
                launch { themeManager.useLyricOverlayFlow.collectLatest { updateLyricsVisibility() } }
                launch { themeManager.useSingleLineLyricsFlow.collectLatest { updateLyricsVisibility() } }
                launch { themeManager.themeModeFlow.collectLatest { 
                    // Re-apply palette when theme mode changes to update adaptive colors
                    ivFullAlbumArt.drawable?.let { drawable ->
                        if (drawable is BitmapDrawable) {
                            Palette.from(drawable.bitmap).generate { palette ->
                                palette?.let { applyPaletteToPlayer(it) }
                            }
                        }
                    }
                } }
            }
        }
    }

    private fun updateSongUI(song: Song?) {
        if (song == null) {
            tvFullTrackTitle.text = getString(R.string.no_track_playing)
            tvFullArtistName.text = getString(R.string.unknown_artist)
            tvTotalDuration.text = "0:00"
            tvCurrentTime.text = "0:00"
            ivFullAlbumArt.setImageResource(R.drawable.ic_music_note_2)
            ivBlurredBackground.visibility = View.GONE
            return
        }
        
        tvFullTrackTitle.text = song.title
        tvFullArtistName.text = song.artist
        tvFullTrackTitle.isSelected = true 

        if (animationHelper.getCurrentAlphaOffset() < 0.01f && isAdded) {
            view?.post { if (isAdded) setTransitionAlpha(0f) }
        } else if (animationHelper.getCurrentAlphaOffset() > 0.99f && isAdded) {
            view?.post { if (isAdded) setTransitionAlpha(1f) }
        }
        
        if (song.id != lastAnimatedSongId) {
            loadLyrics(song)
            updateBlurredBackground(song)
            val ctx = context ?: return
            val cachedArtFile = com.music.myusiq.utils.ArtworkUtils.getArtworkFile(ctx, song.id, song.path)
            val artworkUri = ContentUris.withAppendedId("content://media/external/audio/albumart".toUri(), song.albumId)
            val modelToLoad: Any = cachedArtFile ?: artworkUri
            val normPath = PlatformUtils.normalizePath(song.path)
            val signature = viewModel.artworkRefreshSignatures.value[normPath] ?: 0L

            Glide.with(this)
                .asBitmap()
                .load(modelToLoad)
                .signature(ObjectKey(signature))
                .placeholder(R.drawable.ic_music_note_2)
                .error(
                    Glide.with(this)
                        .asBitmap()
                        .load(ContentUris.withAppendedId(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, song.id))
                        .signature(ObjectKey(signature))
                        .error(R.drawable.ic_music_note_2)
                )
                .into(object : CustomTarget<Bitmap>() {
                    override fun onResourceReady(resource: Bitmap, transition: Transition<in Bitmap>?) {
                        ivFullAlbumArt.setImageBitmap(resource)
                        Palette.from(resource).generate { palette -> palette?.let { applyPaletteToPlayer(it) } }
                    }
                    override fun onLoadCleared(placeholder: Drawable?) {}
                    override fun onLoadFailed(errorDrawable: Drawable?) {
                        ivFullAlbumArt.setImageResource(R.drawable.ic_music_note_2)
                        resetAdaptiveColors()
                    }
                })
            lastAnimatedSongId = song.id
        }
    }

    private fun updateBlurredBackground(song: Song?) {
        if (song == null) return
        viewLifecycleOwner.lifecycleScope.launch {
            if (!themeManager.useBlurredBackgroundFlow.first()) return@launch
            
            val artworkUri = ContentUris.withAppendedId("content://media/external/audio/albumart".toUri(), song.albumId)
            val normPath = PlatformUtils.normalizePath(song.path)
            val signature = viewModel.artworkRefreshSignatures.value[normPath] ?: 0L

            Glide.with(this@PlayerFragment)
                .asBitmap()
                .load(artworkUri)
                .signature(ObjectKey(signature))
                .override(150) // Slightly larger for smoother blur
                .placeholder(R.drawable.ic_music_note_2)
                .into(object : CustomTarget<Bitmap>() {
                    override fun onResourceReady(resource: Bitmap, transition: Transition<in Bitmap>?) {
                        ivBlurredBackground.setImageBitmap(resource)
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                            ivBlurredBackground.setRenderEffect(
                                RenderEffect.createBlurEffect(80f, 80f, Shader.TileMode.CLAMP)
                            )
                        }
                    }
                    override fun onLoadCleared(placeholder: Drawable?) {}
                    override fun onLoadFailed(errorDrawable: Drawable?) {
                        ivBlurredBackground.alpha = 0f
                        // Re-enable solid background if blur fails
                        view?.findViewById<View>(R.id.playerPageBackground)?.alpha = animationHelper.getCurrentAlphaOffset()
                    }
                })
        }
    }

    private fun showLyricsPosterGenerator() {
        val song = viewModel.currentPlayingSong.value ?: return
        val dialogView = LayoutInflater.from(requireContext()).inflate(R.layout.dialog_lyric_poster, null)
        
        val tvPosterLyrics = dialogView.findViewById<TextView>(R.id.tvPosterLyrics)
        val tvPosterTrackInfo = dialogView.findViewById<TextView>(R.id.tvPosterTrackInfo)
        val ivPosterBackground = dialogView.findViewById<ImageView>(R.id.ivPosterBackground)
        val layoutPosterContent = dialogView.findViewById<View>(R.id.layoutPosterContent)
        val rvSelection = dialogView.findViewById<RecyclerView>(R.id.rvLyricSelection)
        val btnShare = dialogView.findViewById<MaterialButton>(R.id.btnSharePoster)

        tvPosterTrackInfo.text = "${song.title} • ${song.artist}"
        
        // Load artwork for background
        val artworkUri = ContentUris.withAppendedId("content://media/external/audio/albumart".toUri(), song.albumId)
        Glide.with(this).load(artworkUri).placeholder(R.drawable.ic_music_note_2).into(ivPosterBackground)

        val selectedLines = mutableListOf<String>()
        val adapter = object : RecyclerView.Adapter<RecyclerView.ViewHolder>() {
            override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) = object : RecyclerView.ViewHolder(
                LayoutInflater.from(parent.context).inflate(android.R.layout.simple_list_item_multiple_choice, parent, false)
            ) {}
            
            override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
                val line = currentLyrics[position].text
                val tv = holder.itemView as TextView
                tv.text = line
                tv.setOnClickListener {
                    if (selectedLines.contains(line)) {
                        selectedLines.remove(line)
                    } else if (selectedLines.size < 4) {
                        selectedLines.add(line)
                    }
                    tvPosterLyrics.text = if (selectedLines.isEmpty()) "Select lines below" else selectedLines.joinToString("\n")
                }
            }
            override fun getItemCount() = currentLyrics.size
        }
        
        rvSelection.layoutManager = LinearLayoutManager(requireContext())
        rvSelection.adapter = adapter

        val dialog = MaterialAlertDialogBuilder(requireContext())
            .setView(dialogView)
            .show()

        btnShare.setOnClickListener {
            if (selectedLines.isEmpty()) return@setOnClickListener
            
            // Render view to Bitmap
            val bitmap = Bitmap.createBitmap(layoutPosterContent.width, layoutPosterContent.height, Bitmap.Config.ARGB_8888)
            val canvas = Canvas(bitmap)
            layoutPosterContent.draw(canvas)
            
            // Share bitmap
            val path = MediaStore.Images.Media.insertImage(requireContext().contentResolver, bitmap, "LyricPoster_${System.currentTimeMillis()}", null)
            val uri = Uri.parse(path)
            
            val intent = Intent(Intent.ACTION_SEND).apply {
                type = "image/jpeg"
                putExtra(Intent.EXTRA_STREAM, uri)
            }
            startActivity(Intent.createChooser(intent, "Share Lyric Poster"))
            dialog.dismiss()
        }
    }

    private fun applyPaletteToPlayer(palette: Palette) {
        val context = context ?: return
        
        viewLifecycleOwner.lifecycleScope.launch {
            if (!themeManager.useAlbumArtThemingFlow.first()) {
                viewModel.currentVibrantColor.value = null
                viewModel.currentBgColor.value = null
                viewModel.currentOnBgColor.value = null
                
                // When art theming is off, use the custom palette background
                val baseColor = ThemeUtils.getThemeColor(context, android.R.attr.colorBackground)
                val bgView = view?.findViewById<View>(R.id.playerPageBackground)
                bgView?.setBackgroundColor(baseColor)
                startLivingBackground(null) // Resets to base theme color
                return@launch
            }

            val themePrimary = ThemeUtils.getThemeColor(context, android.R.attr.colorPrimary)
            val themeBg = ThemeUtils.getThemeColor(context, android.R.attr.colorBackground)
            val isDark = (context.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES

            val vibrant = palette.getVibrantColor(themePrimary)
            val dominant = palette.getDominantColor(themeBg)
            
            // Adaptive blending: Light mode uses White (pastel), Dark mode uses a Dark Surface
            val blendedBg = if (isDark) {
                ColorUtils.blendARGB(dominant, Color.parseColor("#121212"), 0.85f)
            } else {
                ColorUtils.blendARGB(dominant, Color.WHITE, 0.85f)
            }
            
            viewModel.currentVibrantColor.value = vibrant
            viewModel.currentBgColor.value = blendedBg
            
            // Detect optimal text color based on the blended background's luminance
            val onBg = if (ColorUtils.calculateLuminance(blendedBg) > 0.5) Color.BLACK else Color.WHITE
            viewModel.currentOnBgColor.value = onBg
            
            // Sync mini progress indicator
            progressPlayer.setIndicatorColor(vibrant)
        }
    }

    private fun syncControlTints() {
        viewLifecycleOwner.lifecycleScope.launch {
            val useAdaptive = themeManager.useAlbumArtThemingFlow.first()
            val colorPrimary = ThemeUtils.getThemeColor(requireContext(), androidx.appcompat.R.attr.colorPrimary)
            val accent = if (useAdaptive) {
                viewModel.currentVibrantColor.value ?: colorPrimary
            } else {
                colorPrimary
            }

            val themeBg = ThemeUtils.getThemeColor(requireContext(), android.R.attr.colorBackground)
            val isExpanded = animationHelper.getCurrentAlphaOffset() > 0.5f

            // Text Contrast Sync - Dynamically calculate based on the current adaptive background
            val onBg = if (isBlurredModeCached && isExpanded) {
                Color.WHITE // Immersive blur uses a dark scrim, so white text provides best contrast
            } else if (useAdaptive) {
                val dominantColor = viewModel.currentBgColor.value ?: themeBg
                if (ColorUtils.calculateLuminance(dominantColor) > 0.5) Color.BLACK else Color.WHITE
            } else {
                ThemeUtils.getThemeColor(requireContext(), android.R.attr.textColorPrimary)
            }

            val onBgVariant = if (isBlurredModeCached && useAdaptive && animationHelper.getCurrentAlphaOffset() > 0.5f) {
                ColorUtils.setAlphaComponent(onBg, 200)
            } else if (ColorUtils.calculateLuminance(onBg) > 0.5) {
                ColorUtils.setAlphaComponent(onBg, 230) 
            } else {
                Color.parseColor("#333333") 
            }
            
            progressPlayer.setIndicatorColor(accent)
            
            val accentTint = ColorStateList.valueOf(accent)
            val onAccent = if (ColorUtils.calculateLuminance(accent) > 0.5) Color.BLACK else Color.WHITE
            
            btnPlayPause.backgroundTintList = accentTint
            btnPlayPause.setIconTint(ColorStateList.valueOf(onAccent))
            
            val iconTintList = ColorStateList.valueOf(accent)

            // Shuffle Button
            val shuffleActive = viewModel.shuffleModeEnabled.value
            btnShuffle.setIconTint(iconTintList)
            btnShuffle.backgroundTintList = if (shuffleActive) 
                ColorStateList.valueOf(ColorUtils.setAlphaComponent(accent, 45)) 
            else ColorStateList.valueOf(Color.TRANSPARENT)
            
            // Favorite Button
            val isFav = viewModel.isCurrentTrackFavorite.value
            btnFavorite.setIconResource(if (isFav) R.drawable.ic_heart_filled else R.drawable.ic_heart_border)
            btnFavorite.setIconTint(iconTintList)
            
            // Repeat Button
            val repeatState = viewModel.currentRepeatState
            btnRepeat.setIconResource(when(repeatState) {
                PlaybackService.RepeatState.ONE -> R.drawable.ic_repeat_one_on
                PlaybackService.RepeatState.ALL -> R.drawable.ic_repeat_on
                else -> R.drawable.ic_repeat
            })
            btnRepeat.setIconTint(iconTintList)
            btnRepeat.backgroundTintList = if (repeatState != PlaybackService.RepeatState.OFF) 
                ColorStateList.valueOf(ColorUtils.setAlphaComponent(accent, 45)) 
            else ColorStateList.valueOf(Color.TRANSPARENT)

            waveformSeekBar.setColors(accent, ColorUtils.setAlphaComponent(accent, 60))

            if (!isAdded) return@launch

            listOf(btnNext, btnPrevious, btnRewind, btnForward, btnVolumeToggle, btnSleepTimer, btnMoreLikeThis, btnDrivingMode,
                btnLyricsTransliterate, btnLyricsEdit, btnLyricsResetSearch, btnLyricsPlus, btnLyricsMinus).forEach {
                it.setIconTint(iconTintList)
                it.alpha = 1.0f 
            }

            btnLyricsPoster.setIconTint(iconTintList)
            btnLyricsPoster.alpha = 0.38f
            btnLyricsPoster.isEnabled = false
            btnLyricsPoster.backgroundTintList = ColorStateList.valueOf(Color.TRANSPARENT)
            
            btnPlaybackSpeed.setTextColor(accent)
            btnPlaybackSpeed.setIconTint(iconTintList)
            val speed = viewModel.playbackSpeed.value ?: 1.0f
            btnPlaybackSpeed.backgroundTintList = if (speed != 1.0f)
                ColorStateList.valueOf(ColorUtils.setAlphaComponent(accent, 45))
            else ColorStateList.valueOf(Color.TRANSPARENT)
            btnPlaybackSpeed.alpha = 1.0f

            tvLyricsOffset.setTextColor(accent)

            // Update utility buttons background to a translucent accent
            val translucentAccent = ColorStateList.valueOf(ColorUtils.setAlphaComponent(accent, 40))
            btnLyricsTransliterate.backgroundTintList = translucentAccent
            btnLyricsEdit.backgroundTintList = translucentAccent
            btnLyricsResetSearch.backgroundTintList = translucentAccent

            // Sync Lyrics Pill Container background & stroke
            (layoutLyricsControls as? MaterialCardView)?.setCardBackgroundColor(translucentAccent)
            (layoutLyricsControls as? MaterialCardView)?.strokeColor = ColorUtils.setAlphaComponent(accent, 80)

            // Sync Volume Slider popup colors
            sbVolume.progressTintList = accentTint
            sbVolume.thumbTintList = accentTint
            tvVolumeNumber.setTextColor(accent)


            tvFullTrackTitle.setTextColor(onBg)
            tvPlayerTitle.setTextColor(onBg)
            tvFullArtistName.setTextColor(onBgVariant)
            tvCurrentTime.setTextColor(onBgVariant)
            tvTotalDuration.setTextColor(onBgVariant)
            
            // Adjust drag handle tint
            (playerDragHandle as? ShapeableImageView)?.imageTintList = ColorStateList.valueOf(onBgVariant)
        }
    }

    private fun flipCard() {
        animationHelper.flipCard(
            isLyricsShowing = isLyricsShowing,
            updateLyricsVisibility = { updateLyricsVisibility(it) },
            onFlipEnd = { isLyricsShowing = it }
        )
    }

    private fun updateTransliterateButtonState() {
        val hasTransliteration = !transliteratedLyricsText.isNullOrBlank() && 
            transliteratedLyricsText != originalLyricsText && 
            TransliterationUtil.isAvailable()
        
        btnLyricsTransliterate.isVisible = hasTransliteration
        btnLyricsTransliterate.alpha = if (showTransliteratedLyrics) 1.0f else 0.5f
    }

    private fun applyAndParseSongLyrics(rawLyrics: String) {
        originalLyricsText = rawLyrics
        transliteratedLyricsText = TransliterationUtil.transliterateLyrics(rawLyrics)
        showTransliteratedLyrics = false
        updateTransliterateButtonState()
        parseLyrics(rawLyrics)
    }

    private fun updateLyricsVisibility(showingLyrics: Boolean = isLyricsShowing) {
        viewLifecycleOwner.lifecycleScope.launch {
            val useOverlay = themeManager.useLyricOverlayFlow.first()
            val useMinimalist = themeManager.useSingleLineLyricsFlow.first()
            
            if (showingLyrics) {
                // Showing Lyrics Card
                tvLyricOverlay.isVisible = false
                tvLyricOverlay.alpha = 0f
                tvMinimalistLyric.isVisible = useMinimalist
                rvLyrics.isVisible = !useMinimalist
            } else {
                // Showing Album Art
                val hasOverlayText = tvLyricOverlay.text.isNotBlank()
                val shouldShowOverlay = useOverlay && activeLyricIndex != -1 && hasOverlayText
                tvLyricOverlay.isVisible = shouldShowOverlay
                if (!shouldShowOverlay) {
                    tvLyricOverlay.alpha = 0f
                }
                tvMinimalistLyric.isVisible = false
                rvLyrics.isVisible = false
            }
        }
    }

    private fun formatTime(ms: Int): String {
        val s = ms / 1000
        return "%02d:%02d".format(s / 60, s % 60)
    }

    private fun loadLyrics(song: Song) {
        lyricsPromptDialog?.dismiss()
        lyricsPromptDialog = null
        lyricOffsetMs = 0L
        updateOffsetUI()

        // Clear all lyrics state immediately for the new song
        originalLyricsText = null
        transliteratedLyricsText = null
        currentLyrics = emptyList()
        activeLyricIndex = -1
        updateTransliterateButtonState()
        lyricsAdapter.notifyDataSetChanged()

        tvLyricOverlay.text = ""
        tvLyricOverlay.alpha = 0f
        tvLyricOverlay.isVisible = false
        tvMinimalistLyric.text = ""
        tvMinimalistLyric.isVisible = false
        updateLyricsVisibility()

        viewLifecycleOwner.lifecycleScope.launch {
            layoutLyricsLoading.visibility = View.VISIBLE
            layoutLyricsError.visibility = View.GONE
            layoutSuggestionBanner.visibility = View.GONE
            rvLyrics.visibility = View.GONE
            tvMinimalistLyric.visibility = View.GONE
            
            val local = viewModel.getLyricsForSong(song.path)
            
            if (local != null) {
                layoutLyricsLoading.visibility = View.GONE
                applyAndParseSongLyrics(local)
                updateLyricsVisibility()
            } else {
                // Try to auto-discover online lyrics when a track is played
                val online = viewModel.fetchLyricsOnline(song.title, song.artist)
                layoutLyricsLoading.visibility = View.GONE
                
                if (online != null && !online.lyricContent.isNullOrBlank()) {
                    originalLyricsText = null
                    transliteratedLyricsText = null
                    updateTransliterateButtonState()
                    currentLyrics = emptyList()
                    lyricsAdapter.notifyDataSetChanged()
                    layoutLyricsError.visibility = View.GONE
                    
                    if (isAdded && context != null) {
                        showLyricsPromptDialog(online, song)
                    }
                } else {
                    originalLyricsText = null
                    transliteratedLyricsText = null
                    updateTransliterateButtonState()
                    currentLyrics = emptyList()
                    lyricsAdapter.notifyDataSetChanged()
                    layoutLyricsError.visibility = View.VISIBLE
                    tvLyricsError.text = getString(R.string.no_lyrics_found_for_song)
                }
            }
        }
    }

    private fun showLyricsPromptDialog(result: LyricsResponse, song: Song) {
        val ctx = context ?: return
        val lyricsText = result.lyricContent ?: return

        val container = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            val padding = (16 * ctx.resources.displayMetrics.density).toInt()
            setPadding(padding, padding / 2, padding, padding / 2)
        }

        val tvSub = TextView(ctx).apply {
            text = "Found lyrics for \"${result.suggestedTitle ?: song.title}\" by ${result.suggestedArtist ?: song.artist}"
            setTextAppearance(com.google.android.material.R.style.TextAppearance_Material3_BodyMedium)
            setTextColor(ThemeUtils.getThemeColor(ctx, com.google.android.material.R.attr.colorOnSurfaceVariant))
            setPadding(0, 0, 0, (12 * ctx.resources.displayMetrics.density).toInt())
        }
        container.addView(tvSub)

        val scrollView = ScrollView(ctx).apply {
            val maxH = (220 * ctx.resources.displayMetrics.density).toInt()
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                maxH
            )
        }

        val tvLyrics = TextView(ctx).apply {
            text = lyricsText
            setTextAppearance(com.google.android.material.R.style.TextAppearance_Material3_BodySmall)
            setTextColor(ThemeUtils.getThemeColor(ctx, com.google.android.material.R.attr.colorOnSurface))
            setLineSpacing(0f, 1.2f)
        }
        scrollView.addView(tvLyrics)
        container.addView(scrollView)

        lyricsPromptDialog?.dismiss()
        lyricsPromptDialog = MaterialAlertDialogBuilder(ctx)
            .setTitle(R.string.add_lyrics_prompt_title)
            .setView(container)
            .setPositiveButton(R.string.add_lyrics) { dialog, _ ->
                viewModel.saveLyrics(song.path, lyricsText)
                applyAndParseSongLyrics(lyricsText)
                layoutLyricsError.visibility = View.GONE
                layoutLyricsLoading.visibility = View.GONE
                updateLyricsVisibility()
                Toast.makeText(ctx, R.string.lyrics_applied, Toast.LENGTH_SHORT).show()
                dialog.dismiss()
            }
            .setNeutralButton(R.string.search_another) { dialog, _ ->
                dialog.dismiss()
                showLyricsSearchDialog()
            }
            .setNegativeButton(R.string.cancel) { dialog, _ ->
                dialog.dismiss()
                layoutLyricsError.visibility = View.VISIBLE
                tvLyricsError.text = getString(R.string.no_lyrics_found_for_song)
            }
            .show()
    }

    private fun showMetadataUpdateDialog(result: LyricsResponse, song: Song) {
        MaterialAlertDialogBuilder(requireContext())
            .setTitle("Update Track Info?")
            .setMessage("Official info found:\n\nTitle: ${result.suggestedTitle}\nArtist: ${result.suggestedArtist}\nAlbum: ${result.suggestedAlbum}\n\nApply these changes and lyrics to your track?")
            .setNegativeButton("Lyrics Only") { _, _ ->
                result.lyricContent?.let { content ->
                    viewModel.saveLyrics(song.path, content)
                    applyAndParseSongLyrics(content)
                    layoutLyricsError.visibility = View.GONE
                    layoutLyricsLoading.visibility = View.GONE
                    updateLyricsVisibility()
                    Toast.makeText(context, "Lyrics applied!", Toast.LENGTH_SHORT).show()
                }
            }
            .setPositiveButton("Update All") { _, _ ->
                result.lyricContent?.let { content ->
                    viewModel.saveLyrics(song.path, content)
                    applyAndParseSongLyrics(content)
                    layoutLyricsError.visibility = View.GONE
                    layoutLyricsLoading.visibility = View.GONE
                    updateLyricsVisibility()
                }
                
                if (result.suggestedTitle != null && result.suggestedArtist != null) {
                    viewLifecycleOwner.lifecycleScope.launch {
                        val artUri = result.artworkUrl?.let { url -> viewModel.downloadImageToCache(requireContext(), url) }
                        viewModel.permanentUpdateSongMetadata(
                            requireContext(), song.id, result.suggestedTitle, result.suggestedArtist, result.suggestedAlbum ?: song.album,
                            artUri = artUri, path = song.path
                        )
                    }
                }
            }
            .show()
    }

    private fun parseLyrics(raw: String) {
        val lines = mutableListOf<LyricLine>()
        val lrcRegex = Regex("""\[(\d{2}):(\d{2})\.(\d{2,3})\](.*)""")
        
        raw.lines().forEach { line ->
            val match = lrcRegex.find(line)
            if (match != null) {
                val min = match.groupValues[1].toLong()
                val sec = match.groupValues[2].toLong()
                val msStr = match.groupValues[3]
                val ms = if (msStr.length == 2) msStr.toLong() * 10 else msStr.toLong()
                val time = (min * 60 + sec) * 1000 + ms
                lines.add(LyricLine(time, match.groupValues[4].trim()))
            } else if (line.isNotBlank()) {
                // If not LRC, treat as plain text (assigning -1 as timestamp)
                lines.add(LyricLine(-1, line.trim()))
            }
        }
        
        val sorted = lines.sortedBy { it.timeMs }
        val songDuration = viewModel.currentPlayingSong.value?.duration?.toLong()?.times(1000) ?: 0L
        
        for (i in sorted.indices) {
            val current = sorted[i]
            if (current.timeMs < 0) continue
            
            val nextTime = if (i < sorted.size - 1) sorted[i + 1].timeMs else songDuration
            current.durationMs = (nextTime - current.timeMs).coerceAtLeast(0)
            
            if (i > 0) {
                val prev = sorted[i - 1]
                current.preGapMs = (current.timeMs - (prev.timeMs + prev.durationMs)).coerceAtLeast(0)
            } else {
                current.preGapMs = current.timeMs
            }
        }
        
        currentLyrics = sorted
        activeLyricIndex = -1
        isAutoScrolling = true
        lyricsAdapter.notifyDataSetChanged()
        rvLyrics.scrollToPosition(0)
    }

    private fun updateActiveLyric(pos: Int) {
        if (currentLyrics.isEmpty()) {
            tvLyricOverlay.text = ""
            tvLyricOverlay.alpha = 0f
            tvLyricOverlay.isVisible = false
            tvMinimalistLyric.text = ""
            updateLyricsVisibility()
            return
        }
        
        val adjustedPos = pos.toLong() + lyricOffsetMs
        var newIndex = -1
        
        // Find the active line for LRC
        if (currentLyrics.any { it.timeMs >= 0 }) {
            newIndex = currentLyrics.indexOfLast { it.timeMs <= adjustedPos }
        }

        val activeColor = viewModel.currentVibrantColor.value ?: Color.BLUE

        if (newIndex != activeLyricIndex) {
            val oldIndex = activeLyricIndex
            activeLyricIndex = newIndex
            
            // Always ensure auto-scrolling when the line actually changes
            isAutoScrolling = true
            
            if (newIndex != -1) {
                // Update UI for the active line
                val activeText = currentLyrics[activeLyricIndex].text
                tvLyricOverlay.text = activeText
                tvMinimalistLyric.text = activeText
                
                updateLyricsVisibility()
                
                // Simple fade animation for overlay
                if (tvLyricOverlay.isVisible) {
                    tvLyricOverlay.animate().cancel()
                    tvLyricOverlay.alpha = 0f
                    tvLyricOverlay.animate().alpha(1f).setDuration(250).start()
                }
                
                if (tvMinimalistLyric.isVisible) {
                    tvMinimalistLyric.animate().cancel()
                    tvMinimalistLyric.alpha = 0f
                    tvMinimalistLyric.animate().alpha(1f).setDuration(250).start()
                }
            } else {
                tvLyricOverlay.text = ""
                tvLyricOverlay.alpha = 0f
                tvLyricOverlay.isVisible = false
                tvMinimalistLyric.text = ""
                updateLyricsVisibility()
            }
            
            if (oldIndex != -1) lyricsAdapter.notifyItemChanged(oldIndex)
            if (activeLyricIndex != -1) lyricsAdapter.notifyItemChanged(activeLyricIndex)
            
            if (isAutoScrolling && activeLyricIndex != -1) {
                smoothScrollToCenter(activeLyricIndex)
            }
        }

        // Smooth progress update for the active line and countdown for the next
        if (activeLyricIndex != -1) {
            val holder = rvLyrics.findViewHolderForAdapterPosition(activeLyricIndex) as? LyricsAdapter.ViewHolder
            holder?.updateProgress(adjustedPos, activeColor)
        }
        
        val nextIndex = activeLyricIndex + 1
        if (nextIndex < currentLyrics.size) {
            val nextLyric = currentLyrics[nextIndex]
            val timeToNext = nextLyric.timeMs - adjustedPos
            if (nextLyric.preGapMs > 3000) {
                val nextHolder = rvLyrics.findViewHolderForAdapterPosition(nextIndex) as? LyricsAdapter.ViewHolder
                nextHolder?.updateCountdown(timeToNext, activeColor)
            } else {
                val nextHolder = rvLyrics.findViewHolderForAdapterPosition(nextIndex) as? LyricsAdapter.ViewHolder
                nextHolder?.itemView?.findViewById<View>(R.id.layoutCountdownDots)?.visibility = View.GONE
            }
        } else if (activeLyricIndex == -1 && currentLyrics.isNotEmpty()) {
            // First track intro countdown
            val firstLyric = currentLyrics[0]
            val timeToFirst = firstLyric.timeMs - adjustedPos
            if (firstLyric.preGapMs > 2000) {
                val firstHolder = rvLyrics.findViewHolderForAdapterPosition(0) as? LyricsAdapter.ViewHolder
                firstHolder?.updateCountdown(timeToFirst, activeColor)
            }
        }
    }

    private fun smoothScrollToCenter(position: Int) {
        val layoutManager = rvLyrics.layoutManager as? LinearLayoutManager ?: return
        val scroller = object : LinearSmoothScroller(requireContext()) {
            override fun calculateDtToFit(viewStart: Int, viewEnd: Int, boxStart: Int, boxEnd: Int, snapPreference: Int): Int {
                return (boxStart + (boxEnd - boxStart) / 2) - (viewStart + (viewEnd - viewStart) / 2)
            }
            override fun calculateSpeedPerPixel(displayMetrics: DisplayMetrics): Float {
                return 150f / displayMetrics.densityDpi
            }
        }
        scroller.targetPosition = position
        layoutManager.startSmoothScroll(scroller)
    }

    private fun updateOffsetUI() {
        val seconds = lyricOffsetMs / 1000f
        tvLyricsOffset.text = if (seconds > 0) "+%.1fs".format(seconds) else "%.1fs".format(seconds)
    }

    private fun showLyricsEditorDialog() {
        val song = viewModel.currentPlayingSong.value ?: return
        val dialogView = LayoutInflater.from(requireContext()).inflate(R.layout.dialog_edit_lyrics, null)
        val etLyrics = dialogView.findViewById<TextInputEditText>(R.id.etLyrics)
        
        // Load current lyrics into editor
        viewLifecycleOwner.lifecycleScope.launch {
            val raw = viewModel.getLyricsForSong(song.path)
            etLyrics.setText(raw)
        }

        MaterialAlertDialogBuilder(requireContext())
            .setTitle(R.string.edit_lyrics)
            .setView(dialogView)
            .setNegativeButton(R.string.cancel, null)
            .setPositiveButton(R.string.save) { _, _ ->
                val newLyrics = etLyrics.text?.toString().orEmpty()
                if (newLyrics.isNotBlank()) {
                    viewModel.saveLyrics(song.path, newLyrics)
                    parseLyrics(newLyrics)
                    Toast.makeText(context, R.string.lyrics_saved, Toast.LENGTH_SHORT).show()
                }
            }
            .show()
    }

    private fun showLyricsSearchDialog() {
        val song = viewModel.currentPlayingSong.value ?: return
        val dialogView = LayoutInflater.from(requireContext()).inflate(R.layout.dialog_search_lyrics, null)
        val etTitle = dialogView.findViewById<TextInputEditText>(R.id.etSearchTitle)
        val etArtist = dialogView.findViewById<TextInputEditText>(R.id.etSearchArtist)
        val btnSearch = dialogView.findViewById<View>(R.id.btnPerformSearch)
        val btnReset = dialogView.findViewById<View>(R.id.btnResetSearch)
        val progress = dialogView.findViewById<View>(R.id.progressSearch)
        val rvResults = dialogView.findViewById<RecyclerView>(R.id.rvSearchResults)
        
        etTitle.setText(song.title)
        etArtist.setText(song.artist)
        
        val dialog = MaterialAlertDialogBuilder(requireContext())
            .setTitle(R.string.search_online)
            .setView(dialogView)
            .setNegativeButton(R.string.close, null)
            .show()

        val adapter = LyricsSearchAdapter { result ->
            val content = result.lyricContent
            if (content != null) {
                viewModel.saveLyrics(song.path, content)
                parseLyrics(content)
                layoutLyricsError.visibility = View.GONE
                layoutLyricsLoading.visibility = View.GONE
                updateLyricsVisibility()
                Toast.makeText(context, R.string.lyrics_applied, Toast.LENGTH_SHORT).show()
                
                // Show metadata update dialog after applying lyrics
                if (result.suggestedTitle != null && result.suggestedArtist != null) {
                    showMetadataUpdateDialog(result, song)
                }
                
                dialog.dismiss()
            }
        }
        rvResults.layoutManager = LinearLayoutManager(requireContext())
        rvResults.adapter = adapter

        btnSearch.setOnClickListener {
            val t = etTitle.text?.toString().orEmpty()
            val a = etArtist.text?.toString().orEmpty()
            
            progress.visibility = View.VISIBLE
            rvResults.visibility = View.GONE
            
            viewLifecycleOwner.lifecycleScope.launch {
                val results = viewModel.searchLyricsOnline(t, a)
                progress.visibility = View.GONE
                if (results.isNotEmpty()) {
                    adapter.submitList(results)
                    rvResults.visibility = View.VISIBLE
                } else {
                    Toast.makeText(context, R.string.no_matches_found_lyrics, Toast.LENGTH_SHORT).show()
                }
            }
        }
        
        btnReset.setOnClickListener {
            etTitle.setText(song.title)
            etArtist.setText(song.artist)
        }
    }

    private inner class LyricsSearchAdapter(private val onSelected: (LyricsResponse) -> Unit) : RecyclerView.Adapter<LyricsSearchAdapter.ViewHolder>() {
        private var results: List<LyricsResponse> = emptyList()
        
        fun submitList(newResults: List<LyricsResponse>) {
            results = newResults
            notifyDataSetChanged()
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) = ViewHolder(LayoutInflater.from(parent.context).inflate(android.R.layout.simple_list_item_2, parent, false))
        
        override fun onBindViewHolder(holder: ViewHolder, position: Int) {
            val item = results[position]
            val tv1 = holder.itemView.findViewById<TextView>(android.R.id.text1)
            val tv2 = holder.itemView.findViewById<TextView>(android.R.id.text2)
            
            tv1.text = item.suggestedTitle
            tv2.text = "${item.suggestedArtist} • ${item.suggestedAlbum}"
            
            holder.itemView.setOnClickListener { onSelected(item) }
        }
        
        override fun getItemCount() = results.size
        inner class ViewHolder(v: View) : RecyclerView.ViewHolder(v)
    }

    private fun animateButton(view: View) {
        animationHelper.animateButton(view)
    }

    private fun setupSeekButtons() {
        var isLongPressing = false
        val seekRunnable = object : Runnable {
            override fun run() {
                if (isLongPressing) {
                    if (btnForward.isPressed) viewModel.fastForward(5000)
                    else if (btnRewind.isPressed) viewModel.fastRewind(5000)
                    volumeHandler.postDelayed(this, 300)
                }
            }
        }

        val touchListener = View.OnTouchListener { v, event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    isLongPressing = false
                    volumeHandler.postDelayed({
                        if (v.isPressed) {
                            isLongPressing = true
                            volumeHandler.post(seekRunnable)
                        }
                    }, 500)
                    animateButton(v)
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    if (!isLongPressing && event.action == MotionEvent.ACTION_UP) {
                        if (v.id == R.id.btnFullForward) viewModel.fastForward(5000)
                        else viewModel.fastRewind(5000)
                    }
                    isLongPressing = false
                    volumeHandler.removeCallbacks(seekRunnable)
                }
            }
            false
        }

        btnForward.setOnTouchListener(touchListener)
        btnRewind.setOnTouchListener(touchListener)
    }

    private fun setupVolumeControl() {
        val max = audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
        sbVolume.max = max
        val current = audioManager.getStreamVolume(AudioManager.STREAM_MUSIC)
        sbVolume.progress = current
        updateVolumeText(current, max)

        sbVolume.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(s: SeekBar?, p: Int, f: Boolean) { 
                if (f) {
                    audioManager.setStreamVolume(AudioManager.STREAM_MUSIC, p, 0)
                    updateVolumeText(p, max)
                    resetVolumeTimer()
                }
            }
            override fun onStartTrackingTouch(s: SeekBar?) { resetVolumeTimer() }
            override fun onStopTrackingTouch(s: SeekBar?) { resetVolumeTimer() }
        })

        btnVolumeToggle.setOnClickListener {
            val willShow = !layoutSliderExpandable.isVisible
            layoutSliderExpandable.isVisible = willShow
            if (willShow) resetVolumeTimer()
        }
        
        gestureHelper.setupRootView(requireView())
        
        view?.findViewById<View>(R.id.playerPageBackground)?.setOnClickListener {
            if ((activity as? MainActivity)?.isPlayerCollapsed() == true) {
                (activity as? MainActivity)?.expandFullPlayer()
            } else {
                closeAllPopups()
            }
        }
    }

    private fun closeAllPopups() {
        if (layoutSliderExpandable.isVisible) {
            layoutSliderExpandable.visibility = View.GONE
        }
        if (layoutSuggestionBanner.isVisible) {
            layoutSuggestionBanner.visibility = View.GONE
        }
    }

    private fun updateVolumeText(current: Int, max: Int) {
        val pct = if (max > 0) (current * 100) / max else 0
        tvVolumeNumber.text = "$pct%"
    }

    private fun resetVolumeTimer() {
        volumeHideRunnable?.let { volumeHandler.removeCallbacks(it) }
        volumeHideRunnable = Runnable {
            if (isAdded) {
                layoutSliderExpandable.animate()
                    .alpha(0f)
                    .setDuration(300)
                    .withEndAction { 
                        layoutSliderExpandable.visibility = View.GONE 
                        layoutSliderExpandable.alpha = 1f
                    }
                    .start()
            }
        }
        volumeHandler.postDelayed(volumeHideRunnable!!, 5000)
    }

    private fun startLivingBackground(color: Int?) {
        animationHelper.startLivingBackground(color, isBlurredModeCached)
    }

    private inner class LyricsAdapter : RecyclerView.Adapter<LyricsAdapter.ViewHolder>() {
        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) = 
            ViewHolder(LayoutInflater.from(parent.context).inflate(R.layout.item_lyric_line, parent, false))
        
        override fun onBindViewHolder(holder: ViewHolder, position: Int) {
            holder.bind(position)
        }
        
        override fun getItemCount() = currentLyrics.size

        inner class ViewHolder(v: View) : RecyclerView.ViewHolder(v) {
            private val tvLine: LyricLineView = v.findViewById(R.id.tvLyricLine)
            private val layoutCountdown: View = v.findViewById(R.id.layoutCountdownDots)
            private val dot1: View = v.findViewById(R.id.dot1)
            private val dot2: View = v.findViewById(R.id.dot2)
            private val dot3: View = v.findViewById(R.id.dot3)

            fun bind(position: Int) {
                val lyric = currentLyrics[position]
                tvLine.text = lyric.text
                
                val isActive = position == activeLyricIndex
                val color = if (isActive) viewModel.currentVibrantColor.value ?: Color.BLUE else Color.GRAY
                val textSizePx = itemView.context.resources.getDimension(if (isActive) R.dimen.text_size_lyric_active else R.dimen.text_size_lyric_inactive)
                val alpha = if (isActive) 1.0f else 0.5f
                
                tvLine.setTextSize(android.util.TypedValue.COMPLEX_UNIT_PX, textSizePx)
                tvLine.alpha = alpha
                tvLine.setTypeface(null, if (isActive) Typeface.BOLD else Typeface.NORMAL)
                tvLine.setActive(isActive)
                
                if (isActive && lyric.durationMs > 0) {
                    val progress = (viewModel.currentPosition.value + lyricOffsetMs - lyric.timeMs).toFloat() / lyric.durationMs
                    tvLine.setProgress(progress, color, Color.GRAY)
                } else {
                    tvLine.setProgress(0f, color, Color.GRAY)
                }

                // Countdown logic: Show dots if this line has a significant pre-gap
                val nextIndex = activeLyricIndex + 1
                if (position == nextIndex && nextIndex < currentLyrics.size) {
                    val nextLyric = currentLyrics[nextIndex]
                    val adjustedPos = viewModel.currentPosition.value + lyricOffsetMs
                    val timeToNext = nextLyric.timeMs - adjustedPos
                    
                    if (nextLyric.preGapMs > 3000) {
                        layoutCountdown.visibility = View.VISIBLE
                        updateCountdown(timeToNext, color)
                    } else {
                        layoutCountdown.visibility = View.GONE
                    }
                } else if (activeLyricIndex == -1 && position == 0 && currentLyrics.isNotEmpty()) {
                    // Handle intro gap for the very first line
                    val firstLyric = currentLyrics[0]
                    val timeToFirst = firstLyric.timeMs - (viewModel.currentPosition.value + lyricOffsetMs)
                    if (firstLyric.preGapMs > 2000) {
                        layoutCountdown.visibility = View.VISIBLE
                        updateCountdown(timeToFirst, color)
                    } else {
                        layoutCountdown.visibility = View.GONE
                    }
                } else {
                    layoutCountdown.visibility = View.GONE
                }

                itemView.setOnClickListener { flipCard() }
            }

            fun updateProgress(pos: Long, activeColor: Int) {
                val lyric = currentLyrics[layoutPosition]
                if (lyric.durationMs > 0) {
                    val progress = (pos - lyric.timeMs).toFloat() / lyric.durationMs
                    tvLine.setProgress(progress, activeColor, Color.GRAY)
                }
            }

            fun updateCountdown(timeToNext: Long, activeColor: Int) {
                layoutCountdown.visibility = View.VISIBLE
                dot1.alpha = if (timeToNext <= 3000) 1.0f else 0.3f
                dot2.alpha = if (timeToNext <= 2000) 1.0f else 0.3f
                dot3.alpha = if (timeToNext <= 1000) 1.0f else 0.3f
                
                dot1.backgroundTintList = ColorStateList.valueOf(activeColor)
                dot2.backgroundTintList = ColorStateList.valueOf(activeColor)
                dot3.backgroundTintList = ColorStateList.valueOf(activeColor)
            }
        }
    }

    private val volumeReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (intent.action == "android.media.VOLUME_CHANGED_ACTION") {
                val current = audioManager.getStreamVolume(AudioManager.STREAM_MUSIC)
                val max = audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
                sbVolume.progress = current
                updateVolumeText(current, max)
            }
        }
    }

    override fun onResume() {
        super.onResume()
        try {
            requireContext().registerReceiver(volumeReceiver,
                IntentFilter("android.media.VOLUME_CHANGED_ACTION")
            )
            
            // Refresh volume UI on resume (handles Bluetooth connection changes while app was backgrounded)
            view?.postDelayed({
                if (isAdded) {
                    val current = audioManager.getStreamVolume(AudioManager.STREAM_MUSIC)
                    val max = audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
                    sbVolume.progress = current
                    updateVolumeText(current, max)
                }
            }, 500)
        } catch (e: Exception) {
            Log.e("PlayerFragment", "Error registering volume receiver", e)
        }
    }

    override fun onPause() {
        super.onPause()
        try {
            requireContext().unregisterReceiver(volumeReceiver)
        } catch (e: Exception) {
            Log.e("PlayerFragment", "Error unregistering volume receiver", e)
        }
    }

    private fun resetAdaptiveColors() {
        val context = context ?: return
        viewLifecycleOwner.lifecycleScope.launch {
            viewModel.currentVibrantColor.value = null
            viewModel.currentBgColor.value = null
            viewModel.currentOnBgColor.value = null
            
            val themeBg = ThemeUtils.getThemeColor(context, android.R.attr.colorBackground)
            view?.findViewById<View>(R.id.playerPageBackground)?.setBackgroundColor(themeBg)
            
            // Clear blurred background
            ivBlurredBackground.alpha = 0f
            
            if (!isBlurredModeCached) {
                startLivingBackground(null)
            }
            
            syncControlTints()
        }
    }

    override fun onDestroyView() {
        visualizerComponent.release()
        animationHelper.cancelBackgroundAnimation()
        super.onDestroyView()
    }
}
