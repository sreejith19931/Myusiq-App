package com.music.myusiq.ui.components

import android.animation.ValueAnimator
import android.content.DialogInterface
import android.graphics.Color
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.ImageView
import android.widget.SeekBar
import android.widget.TextView
import androidx.fragment.app.DialogFragment
import androidx.fragment.app.activityViewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.bumptech.glide.Glide
import com.google.android.material.button.MaterialButton
import com.google.android.material.chip.Chip
import com.google.android.material.chip.ChipGroup
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import com.music.myusiq.R
import com.music.myusiq.service.FftEngineManager
import com.music.myusiq.ui.viewmodels.PlaybackViewModel
import com.music.myusiq.utils.ArtworkUtils
import com.music.myusiq.utils.ThemeUtils
import com.music.myusiq.utils.applyBlurEffect

class FullscreenLyricsStageDialogFragment : DialogFragment() {

    private val viewModel: PlaybackViewModel by activityViewModels()

    private lateinit var ivStageArtBackground: ImageView
    private lateinit var stageVisualizerView: FullscreenVisualizerView
    private lateinit var rvStageLyrics: RecyclerView
    private lateinit var tvStageTrackTitle: TextView
    private lateinit var tvStageArtistName: TextView
    private lateinit var btnStagePlayPause: MaterialButton
    private lateinit var sbStageProgress: SeekBar
    private lateinit var tvStageProgress: TextView
    private lateinit var btnStageClose: MaterialButton

    private var fftEngineManager: FftEngineManager? = null
    private var breathAnimator: ValueAnimator? = null
    private var stageLyricsAdapter: StageLyricsAdapter? = null
    private var isUserSeeking = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setStyle(STYLE_NORMAL, android.R.style.Theme_Black_NoTitleBar_Fullscreen)
    }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View? {
        return inflater.inflate(R.layout.dialog_fullscreen_lyrics_stage, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        ivStageArtBackground = view.findViewById(R.id.ivStageArtBackground)
        stageVisualizerView = view.findViewById(R.id.stageVisualizerView)
        rvStageLyrics = view.findViewById(R.id.rvStageLyrics)
        tvStageTrackTitle = view.findViewById(R.id.tvStageTrackTitle)
        tvStageArtistName = view.findViewById(R.id.tvStageArtistName)
        btnStagePlayPause = view.findViewById(R.id.btnStagePlayPause)
        sbStageProgress = view.findViewById(R.id.sbStageProgress)
        tvStageProgress = view.findViewById(R.id.tvStageProgress)
        btnStageClose = view.findViewById(R.id.btnStageClose)

        btnStageClose.setOnClickListener { dismiss() }

        stageLyricsAdapter = StageLyricsAdapter()
        rvStageLyrics.layoutManager = LinearLayoutManager(requireContext())
        rvStageLyrics.adapter = stageLyricsAdapter

        setupVisualizerMenu(view)
        setupPlayPauseAndSeek()
        startBreathingAnimation()
        setupObservers()

        fftEngineManager = FftEngineManager(requireContext()) { fft ->
            stageVisualizerView.updateFftData(fft)
        }
    }

    private val requestAudioPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { isGranted ->
            if (isGranted) {
                viewModel.refreshAudioSessionId()
            }
        }

    override fun onStart() {
        super.onStart()
        dialog?.window?.apply {
            setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
            addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            setWindowAnimations(com.google.android.material.R.style.Animation_Design_BottomSheetDialog)
        }
        if (ContextCompat.checkSelfPermission(requireContext(), Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            requestAudioPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
        } else {
            viewModel.refreshAudioSessionId()
        }
    }

    override fun onDismiss(dialog: DialogInterface) {
        activity?.window?.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        super.onDismiss(dialog)
    }

    private fun setupVisualizerMenu(view: View) {
        val btnMenu = view.findViewById<MaterialButton>(R.id.btnStageVisualizerMenu)
        btnMenu?.setOnClickListener {
            val popup = androidx.appcompat.widget.PopupMenu(requireContext(), btnMenu)
            popup.menu.add(0, 0, 0, "Visualizer Off")
            popup.menu.add(0, 1, 1, "Spectrum Analyzer")
            popup.menu.add(0, 2, 2, "Circular Pulse Ring")
            popup.menu.add(0, 3, 3, "Liquid Waveform")
            popup.menu.add(0, 4, 4, "Particle Field")

            popup.setOnMenuItemClickListener { menuItem ->
                when (menuItem.itemId) {
                    0 -> {
                        stageVisualizerView.visibility = View.GONE
                    }
                    1 -> {
                        stageVisualizerView.visibility = View.VISIBLE
                        stageVisualizerView.currentMode = FullscreenVisualizerView.MODE_SPECTRUM
                    }
                    2 -> {
                        stageVisualizerView.visibility = View.VISIBLE
                        stageVisualizerView.currentMode = FullscreenVisualizerView.MODE_CIRCULAR
                    }
                    3 -> {
                        stageVisualizerView.visibility = View.VISIBLE
                        stageVisualizerView.currentMode = FullscreenVisualizerView.MODE_WAVEFORM
                    }
                    4 -> {
                        stageVisualizerView.visibility = View.VISIBLE
                        stageVisualizerView.currentMode = FullscreenVisualizerView.MODE_PARTICLES
                    }
                }
                true
            }
            popup.show()
        }
    }

    private fun setupPlayPauseAndSeek() {
        btnStagePlayPause.setOnClickListener {
            viewModel.togglePlayPause()
        }

        sbStageProgress.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                if (fromUser) {
                    val duration = viewModel.currentPlayingSong.value?.duration ?: 1
                    tvStageProgress.text = "${formatTime(progress)} / ${formatTime(duration)}"
                }
            }

            override fun onStartTrackingTouch(seekBar: SeekBar?) {
                isUserSeeking = true
            }

            override fun onStopTrackingTouch(seekBar: SeekBar?) {
                isUserSeeking = false
                seekBar?.progress?.let { viewModel.seekTo(it) }
            }
        })
    }

    private fun startBreathingAnimation() {
        breathAnimator = ValueAnimator.ofFloat(1.0f, 1.06f).apply {
            duration = 6000L
            repeatCount = ValueAnimator.INFINITE
            repeatMode = ValueAnimator.REVERSE
            addUpdateListener { anim ->
                val scale = anim.animatedValue as Float
                ivStageArtBackground.scaleX = scale
                ivStageArtBackground.scaleY = scale
            }
            start()
        }
    }

    private fun setupObservers() {
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch {
                    viewModel.currentPlayingSong.collectLatest { song ->
                        if (song != null) {
                            tvStageTrackTitle.text = song.title
                            tvStageArtistName.text = song.artist
                            val artworkFile = ArtworkUtils.getArtworkFile(requireContext(), song.id, song.path, song.album)
                            Glide.with(this@FullscreenLyricsStageDialogFragment)
                                .load(artworkFile)
                                .placeholder(R.drawable.ic_album)
                                .into(ivStageArtBackground)
                            ivStageArtBackground.applyBlurEffect(30f)

                            val rawLyrics = viewModel.getLyricsForSong(song.path)
                            stageLyricsAdapter?.setRawLyrics(rawLyrics ?: "")
                        }
                    }
                }

                launch {
                    viewModel.isPlaying.collectLatest { isPlaying ->
                        stageVisualizerView.isPlaying = isPlaying
                        btnStagePlayPause.setIconResource(
                            if (isPlaying) R.drawable.ic_pause else R.drawable.ic_play
                        )
                    }
                }

                launch {
                    viewModel.currentPosition.collectLatest { pos ->
                        if (!isUserSeeking) {
                            val duration = viewModel.currentPlayingSong.value?.duration ?: 1
                            sbStageProgress.max = duration
                            sbStageProgress.progress = pos
                            tvStageProgress.text = "${formatTime(pos)} / ${formatTime(duration)}"
                            stageLyricsAdapter?.updatePosition(pos.toLong()) { activeIndex ->
                                if (activeIndex >= 0) {
                                    rvStageLyrics.smoothScrollToPosition(activeIndex)
                                }
                            }
                        }
                    }
                }

                launch {
                    viewModel.audioSessionId.collectLatest { sessionId ->
                        if (sessionId > 0) {
                            fftEngineManager?.attachToSession(sessionId)
                        }
                    }
                }

                launch {
                    viewModel.currentVibrantColor.collectLatest { color ->
                        val accent = color ?: ThemeUtils.getThemeColor(requireContext(), android.R.attr.colorPrimary)
                        stageVisualizerView.accentColor = accent
                        stageLyricsAdapter?.setActiveColor(accent)
                    }
                }
            }
        }
    }

    private fun formatTime(ms: Int): String {
        val s = ms / 1000
        return "%02d:%02d".format(s / 60, s % 60)
    }

    override fun onDestroyView() {
        breathAnimator?.cancel()
        fftEngineManager?.release()
        fftEngineManager = null
        super.onDestroyView()
    }

    companion object {
        const val TAG = "FullscreenLyricsStageDialogFragment"
        fun newInstance() = FullscreenLyricsStageDialogFragment()
    }

    // Inner Adapter for Fullscreen Stage Lyrics
    private class StageLyricsAdapter : RecyclerView.Adapter<StageLyricsAdapter.StageViewHolder>() {
        private var lines = mutableListOf<LyricLine>()
        private var activeIndex = -1
        private var activeColor = Color.CYAN

        data class LyricLine(val timeMs: Long, val text: String)

        fun setRawLyrics(raw: String) {
            lines.clear()
            if (raw.isNotBlank()) {
                val parsed = mutableListOf<LyricLine>()
                raw.lineSequence().forEach { line ->
                    if (line.contains("[")) {
                        val timestampStr = line.substringAfter("[").substringBefore("]")
                        val parts = timestampStr.split(":")
                        if (parts.size >= 2) {
                            val min = parts[0].toLongOrNull() ?: 0L
                            val secParts = parts[1].split(".")
                            val sec = secParts[0].toLongOrNull() ?: 0L
                            val hundredths = if (secParts.size > 1) secParts[1].take(2).toLongOrNull() ?: 0L else 0L
                            val timeMs = (min * 60 + sec) * 1000 + hundredths * 10
                            val cleanText = line.substringAfter("]").trim()
                            if (cleanText.isNotBlank()) {
                                parsed.add(LyricLine(timeMs, cleanText))
                            }
                        }
                    } else if (line.trim().isNotBlank()) {
                        parsed.add(LyricLine(-1L, line.trim()))
                    }
                }
                lines.addAll(parsed)
            }
            activeIndex = -1
            notifyDataSetChanged()
        }

        fun updatePosition(posMs: Long, onActiveChanged: (Int) -> Unit) {
            if (lines.isEmpty()) return
            val newIndex = lines.indexOfLast { it.timeMs >= 0 && it.timeMs <= posMs }
            if (newIndex != activeIndex && newIndex >= 0) {
                val oldIndex = activeIndex
                activeIndex = newIndex
                if (oldIndex >= 0) notifyItemChanged(oldIndex)
                notifyItemChanged(activeIndex)
                onActiveChanged(activeIndex)
            }
        }

        fun setActiveColor(color: Int) {
            activeColor = color
            if (activeIndex >= 0) notifyItemChanged(activeIndex)
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): StageViewHolder {
            val tv = TextView(parent.context).apply {
                layoutParams = ViewGroup.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                )
                setPadding(0, 24, 0, 24)
                textSize = 22f
                textAlignment = View.TEXT_ALIGNMENT_CENTER
                setTextColor(Color.parseColor("#80FFFFFF"))
            }
            return StageViewHolder(tv)
        }

        override fun onBindViewHolder(holder: StageViewHolder, position: Int) {
            val item = lines[position]
            val tv = holder.itemView as TextView
            tv.text = item.text

            if (position == activeIndex) {
                tv.setTextColor(Color.WHITE)
                tv.textSize = 28f
                tv.animate().scaleX(1.1f).scaleY(1.1f).setDuration(200).start()
                tv.setShadowLayer(16f, 0f, 0f, activeColor)
            } else {
                tv.setTextColor(Color.parseColor("#80FFFFFF"))
                tv.textSize = 22f
                tv.animate().scaleX(1.0f).scaleY(1.0f).setDuration(200).start()
                tv.setShadowLayer(0f, 0f, 0f, Color.TRANSPARENT)
            }
        }

        override fun getItemCount(): Int = lines.size
        private class StageViewHolder(view: View) : RecyclerView.ViewHolder(view)
    }
}
