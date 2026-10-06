@file:Suppress("DEPRECATION")

package com.music.rhythm.ui.adapters

import android.animation.ObjectAnimator
import android.animation.ValueAnimator
import android.content.ContentUris
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.Typeface
import android.provider.MediaStore
import androidx.core.graphics.ColorUtils
import android.view.LayoutInflater
import android.view.Menu
import android.view.View
import android.view.ViewGroup
import android.view.animation.LinearInterpolator
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.widget.PopupMenu
import androidx.constraintlayout.widget.Guideline
import androidx.core.view.isVisible
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.bumptech.glide.Glide
import com.bumptech.glide.load.engine.DiskCacheStrategy
import com.bumptech.glide.signature.ObjectKey
import androidx.core.net.toUri
import com.music.rhythm.R
import com.music.rhythm.utils.ThemeUtils
import com.music.rhythm.utils.isActivityDestroyed
import com.music.rhythm.domain.models.Song
import java.io.File

data class TrackUiModel(
    val song: Song,
    val isFavorite: Boolean = false,
    val isActive: Boolean = false,
    val isPast: Boolean = false,
    val isPlaying: Boolean = false,
    val isSelected: Boolean = false,
    val progress: Float = 0f,
    val artworkSignature: Long = 0L,
)

class TrackAdapter(
    private val isQueueMode: Boolean = false,
    private val onTrackClicked: (Song, ImageView) -> Unit,
    private val onTrackLongClicked: ((Song) -> Unit)? = null,
    private val onOptionSelected: ((Song, itemId: Int) -> Unit)? = null,
    private val menuCustomizer: ((Song, Menu) -> Unit)? = null,
) : ListAdapter<TrackUiModel, TrackAdapter.TrackViewHolder>(TrackDiffCallback()) {

    var onOrderChangedListener: ((List<Song>) -> Unit)? = null

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): TrackViewHolder {
        val view = LayoutInflater.from(parent.context).inflate(R.layout.item_track, parent, false)
        return TrackViewHolder(view)
    }

    override fun onBindViewHolder(holder: TrackViewHolder, position: Int, payloads: MutableList<Any>) {
        if (payloads.isNotEmpty()) {
            val model = getItem(position)
            val p = (payloads.first() as? Set<*>) ?: return
            
            if (p.contains("PAYLOAD_SELECTED")) holder.updateSelectionState(model)
            if (p.contains("PAYLOAD_FAVORITE")) holder.updateFavoriteState(model)
            if (p.contains("PAYLOAD_ACTIVE") || p.contains("PAYLOAD_PLAYING") || p.contains("PAYLOAD_PROGRESS")) holder.updatePlaybackState(model, isQueueMode)
        } else {
            super.onBindViewHolder(holder, position, payloads)
        }
    }

    override fun onBindViewHolder(holder: TrackViewHolder, position: Int) {
        val model = getItem(position)
        holder.bind(model, isQueueMode, model.artworkSignature, onTrackClicked, onTrackLongClicked, onOptionSelected, menuCustomizer)
    }

    override fun onViewRecycled(holder: TrackViewHolder) {
        super.onViewRecycled(holder)
        val context = holder.itemView.context
        if (!context.isActivityDestroyed()) {
            Glide.with(context).clear(holder.ivTrackArt)
        }
        holder.stopVisualizerAnimation()
    }

    class TrackViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        private val tvTitle: TextView = view.findViewById(R.id.tvTrackTitle)
        private val tvArtist: TextView = view.findViewById(R.id.tvTrackArtist)
        val ivTrackArt: ImageView = view.findViewById(R.id.ivTrackArt)
        private val btnTrackOptions: View = view.findViewById(R.id.btnTrackOptions)
        private val ivFavorite: ImageView = view.findViewById(R.id.ivFavoriteIndicator)
        private val ivSelectionIndicator: ImageView = view.findViewById(R.id.ivSelectionIndicator)
        private val viewSelectionOverlay: View = view.findViewById(R.id.viewSelectionOverlay)
        private val viewPlayingIndicator: View = view.findViewById(R.id.viewPlayingIndicator)
        private val viewProgressShade: View = view.findViewById(R.id.viewProgressShade)
        private val guidelineProgress: Guideline = view.findViewById(R.id.guidelineProgress)
        private val layoutVisualizer: LinearLayout = view.findViewById(R.id.layoutVisualizer)
        private val bar1: View = view.findViewById(R.id.bar1)
        private val bar2: View = view.findViewById(R.id.bar2)
        private val bar3: View = view.findViewById(R.id.bar3)
        
        private val colorPrimary = ThemeUtils.getThemeColor(view.context, android.R.attr.colorPrimary)
        private val colorOnSurface = ThemeUtils.getThemeColor(view.context, android.R.attr.textColorPrimary)
        private val colorOnSurfaceVariant = ThemeUtils.getThemeColor(view.context, android.R.attr.textColorSecondary)
        private val colorSecondaryContainer = ThemeUtils.getThemeColor(view.context, com.google.android.material.R.attr.colorSecondaryContainer)
        private val colorOnSecondaryContainer = ThemeUtils.getThemeColor(view.context, com.google.android.material.R.attr.colorOnSecondaryContainer)

        private val animators = mutableListOf<ObjectAnimator>()

        fun updateSelectionState(model: TrackUiModel) {
            if (model.isSelected) {
                itemView.setBackgroundColor(ColorUtils.setAlphaComponent(colorPrimary, 40))
                ivSelectionIndicator.visibility = View.VISIBLE
                viewSelectionOverlay.visibility = View.VISIBLE
            } else {
                itemView.setBackgroundColor(Color.TRANSPARENT)
                ivSelectionIndicator.visibility = View.GONE
                viewSelectionOverlay.visibility = View.GONE
            }
        }

        fun updateFavoriteState(model: TrackUiModel) {
            ivFavorite.visibility = if (model.isFavorite) View.VISIBLE else View.INVISIBLE
        }

        fun updatePlaybackState(model: TrackUiModel, isQueueMode: Boolean) {
            val isCurrentlyActive = model.isActive
            val isCurrentlyPlaying = model.isPlaying

            if (isQueueMode) {
                itemView.alpha = if (isCurrentlyActive) 1.0f else (if (model.isPast) 0.45f else 1.0f)
            } else {
                itemView.alpha = 1.0f
            }

            if (isCurrentlyActive) {
                tvTitle.setTextColor(if (isCurrentlyPlaying) colorPrimary else colorOnSurface)
                tvArtist.setTextColor(if (isCurrentlyPlaying) colorPrimary else colorOnSurfaceVariant)
                viewPlayingIndicator.isVisible = isCurrentlyPlaying
                
                // Update progress shade
                viewProgressShade.isVisible = true
                guidelineProgress.setGuidelinePercent(model.progress)
                
                if (isCurrentlyPlaying) {
                    layoutVisualizer.visibility = View.VISIBLE
                    startVisualizerAnimation()
                    if (isQueueMode) {
                        itemView.backgroundTintList = ColorStateList.valueOf(colorSecondaryContainer)
                        tvTitle.setTextColor(colorOnSecondaryContainer)
                        tvArtist.setTextColor(colorOnSecondaryContainer)
                        bar1.setBackgroundColor(colorOnSecondaryContainer)
                        bar2.setBackgroundColor(colorOnSecondaryContainer)
                        bar3.setBackgroundColor(colorOnSecondaryContainer)
                    }
                } else {
                    stopVisualizerAnimation()
                    layoutVisualizer.visibility = View.GONE
                    viewPlayingIndicator.isVisible = false
                    if (isQueueMode) itemView.backgroundTintList = ColorStateList.valueOf(Color.TRANSPARENT)
                }
            } else {
                stopVisualizerAnimation()
                layoutVisualizer.visibility = View.GONE
                viewPlayingIndicator.isVisible = false
                viewProgressShade.isVisible = false
                tvTitle.setTextColor(colorOnSurface)
                tvArtist.setTextColor(colorOnSurfaceVariant)
                if (isQueueMode) itemView.backgroundTintList = ColorStateList.valueOf(Color.TRANSPARENT)
            }
        }

        fun bind(
            model: TrackUiModel,
            isQueueMode: Boolean,
            artworkSignature: Long?,
            onTrackClicked: (Song, ImageView) -> Unit,
            onTrackLongClicked: ((Song) -> Unit)?,
            onOptionSelected: ((Song, itemId: Int) -> Unit)?,
            menuCustomizer: ((Song, Menu) -> Unit)?,
        ) {
            val song = model.song
            tvTitle.text = song.title
            tvArtist.text = song.artist
            tvTitle.isSelected = true
            
            ivFavorite.visibility = if (model.isFavorite) View.VISIBLE else View.INVISIBLE
            
            // Selection State Overlay
            if (model.isSelected) {
                itemView.setBackgroundColor(ColorUtils.setAlphaComponent(colorPrimary, 40))
                ivSelectionIndicator.visibility = View.VISIBLE
                viewSelectionOverlay.visibility = View.VISIBLE
            } else {
                itemView.setBackgroundColor(Color.TRANSPARENT)
                ivSelectionIndicator.visibility = View.GONE
                viewSelectionOverlay.visibility = View.GONE
            }
            
            if (isQueueMode || (onOptionSelected == null)) {
                btnTrackOptions.visibility = View.GONE
                btnTrackOptions.setOnClickListener(null)
            } else {
                btnTrackOptions.visibility = View.VISIBLE
                btnTrackOptions.setOnClickListener { v ->
                    val popup = PopupMenu(v.context, v)
                    popup.menuInflater.inflate(R.menu.menu_track_options, popup.menu)
                    menuCustomizer?.invoke(song, popup.menu)
                    popup.setOnMenuItemClickListener { menuItem ->
                        onOptionSelected(song, menuItem.itemId)
                        true
                    }
                    popup.show()
                }
            }

            val context = itemView.context
            if (!context.isActivityDestroyed()) {
                val currentSignature = artworkSignature ?: 0L
                val cachedArtFile = com.music.rhythm.utils.ArtworkUtils.getArtworkFile(context, song.id, song.path)

                if (cachedArtFile != null) {
                    Glide.with(context)
                        .asBitmap()
                        .load(cachedArtFile)
                        .signature(ObjectKey(currentSignature))
                        .placeholder(R.drawable.ic_music_note_2)
                        .error(R.drawable.ic_music_note_2)
                        .diskCacheStrategy(DiskCacheStrategy.NONE)
                        .skipMemoryCache(true)
                        .override(120, 120)
                        .into(ivTrackArt)
                } else {
                    val trackUri = ContentUris.withAppendedId(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, song.id)
                    val albumArtUri = if (song.albumId > 0) {
                        ContentUris.withAppendedId("content://media/external/audio/albumart".toUri(), song.albumId)
                    } else null

                    val albumRequest = albumArtUri?.let {
                        Glide.with(context)
                            .asBitmap()
                            .load(it)
                            .signature(ObjectKey(currentSignature))
                            .error(R.drawable.ic_music_note_2)
                    }

                    val fileRequest = Glide.with(context)
                        .asBitmap()
                        .load(File(song.path))
                        .signature(ObjectKey(currentSignature))
                        .error(albumRequest ?: Glide.with(context).asBitmap().load(R.drawable.ic_music_note_2))

                    Glide.with(context)
                        .asBitmap()
                        .load(trackUri)
                        .signature(ObjectKey(currentSignature))
                        .placeholder(R.drawable.ic_music_note_2)
                        .error(fileRequest)
                        .diskCacheStrategy(DiskCacheStrategy.ALL)
                        .override(120, 120)
                        .into(ivTrackArt)
                }
            }

            ivTrackArt.transitionName = "track_art_${song.id}"

            // Visual State
            when {
                model.isActive && isQueueMode -> {
                    itemView.alpha = 1.0f
                    tvTitle.setTextColor(if (model.isPlaying) colorPrimary else colorOnSurface)
                    tvTitle.setTypeface(tvTitle.typeface, Typeface.BOLD)
                    tvArtist.setTextColor(if (model.isPlaying) colorPrimary else colorOnSurfaceVariant)
                    viewPlayingIndicator.isVisible = model.isPlaying
                    viewProgressShade.isVisible = true
                    guidelineProgress.setGuidelinePercent(model.progress)
                    
                    if (model.isPlaying) {
                        layoutVisualizer.visibility = View.VISIBLE
                        startVisualizerAnimation()
                        itemView.backgroundTintList = ColorStateList.valueOf(colorSecondaryContainer)
                        tvTitle.setTextColor(colorOnSecondaryContainer)
                        tvArtist.setTextColor(colorOnSecondaryContainer)
                        bar1.setBackgroundColor(colorOnSecondaryContainer)
                        bar2.setBackgroundColor(colorOnSecondaryContainer)
                        bar3.setBackgroundColor(colorOnSecondaryContainer)
                    } else {
                        stopVisualizerAnimation()
                        layoutVisualizer.visibility = View.GONE
                        viewPlayingIndicator.isVisible = false
                        itemView.backgroundTintList = ColorStateList.valueOf(Color.TRANSPARENT)
                    }
                }
                model.isPast && isQueueMode -> {
                    stopVisualizerAnimation()
                    layoutVisualizer.visibility = View.GONE
                    viewPlayingIndicator.isVisible = false
                    viewProgressShade.isVisible = false
                    itemView.alpha = 0.45f
                    itemView.backgroundTintList = ColorStateList.valueOf(Color.TRANSPARENT)
                    tvTitle.setTextColor(colorOnSurfaceVariant)
                    tvTitle.setTypeface(tvTitle.typeface, Typeface.BOLD)
                    tvArtist.setTextColor(colorOnSurfaceVariant)
                }
                else -> {
                    stopVisualizerAnimation()
                    layoutVisualizer.visibility = View.GONE
                    viewPlayingIndicator.isVisible = model.isPlaying && model.isActive
                    viewProgressShade.isVisible = model.isActive
                    if (model.isActive) guidelineProgress.setGuidelinePercent(model.progress)
                    
                    itemView.alpha = 1.0f
                    itemView.backgroundTintList = ColorStateList.valueOf(Color.TRANSPARENT)
                    tvTitle.setTextColor(colorOnSurface)
                    tvTitle.setTypeface(tvTitle.typeface, Typeface.BOLD)
                    tvArtist.setTextColor(colorOnSurfaceVariant)
                }
            }

            itemView.setOnClickListener {
                onTrackClicked(song, ivTrackArt)
            }

            itemView.setOnLongClickListener {
                onTrackLongClicked?.invoke(song)
                true
            }
        }

        private fun startVisualizerAnimation() {
            if (animators.isNotEmpty()) return
            bar1.post { bar1.pivotY = bar1.height.toFloat() }

            bar2.post { bar2.pivotY = bar2.height.toFloat() }
            bar3.post { bar3.pivotY = bar3.height.toFloat() }
            animators.add(createBarAnimator(bar1, 360L, 0.2f))
            animators.add(createBarAnimator(bar2, 440L, 0.5f))
            animators.add(createBarAnimator(bar3, 400L, 0.0f))
            animators.forEach { it.start() }
        }

        fun stopVisualizerAnimation() {
            animators.forEach { it.cancel() }
            animators.clear()
            bar1.scaleY = 0.2f
            bar2.scaleY = 0.2f
            bar3.scaleY = 0.2f
        }

        private fun createBarAnimator(targetView: View, durationMs: Long, delayScale: Float): ObjectAnimator {
            return ObjectAnimator.ofFloat(targetView, "scaleY", 0.2f, 1.0f).apply {
                duration = durationMs
                repeatCount = ValueAnimator.INFINITE
                repeatMode = ValueAnimator.REVERSE
                interpolator = LinearInterpolator()
                startDelay = (durationMs * delayScale).toLong()
            }
        }
    }

    class TrackDiffCallback : DiffUtil.ItemCallback<TrackUiModel>() {
        override fun areItemsTheSame(oldItem: TrackUiModel, newItem: TrackUiModel): Boolean {
            return oldItem.song.id == newItem.song.id
        }

        override fun areContentsTheSame(oldItem: TrackUiModel, newItem: TrackUiModel): Boolean {
            return oldItem == newItem
        }

        override fun getChangePayload(oldItem: TrackUiModel, newItem: TrackUiModel): Any? {
            val payloads = mutableSetOf<String>()
            if (oldItem.isActive != newItem.isActive) payloads.add("PAYLOAD_ACTIVE")
            if (oldItem.isPlaying != newItem.isPlaying) payloads.add("PAYLOAD_PLAYING")
            if (oldItem.isFavorite != newItem.isFavorite) payloads.add("PAYLOAD_FAVORITE")
            if (oldItem.isSelected != newItem.isSelected) payloads.add("PAYLOAD_SELECTED")
            if (oldItem.progress != newItem.progress) payloads.add("PAYLOAD_PROGRESS")
            
            return payloads.ifEmpty { super.getChangePayload(oldItem, newItem) }
        }
    }
}
