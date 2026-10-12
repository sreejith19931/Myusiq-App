package com.music.myusiq.ui.adapters

import android.annotation.SuppressLint
import android.graphics.Bitmap
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import android.content.ContentUris
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.drawable.Drawable
import android.util.Log
import androidx.core.graphics.ColorUtils
import androidx.palette.graphics.Palette
import com.bumptech.glide.Glide
import com.bumptech.glide.request.target.CustomTarget
import com.bumptech.glide.request.transition.Transition
import com.music.myusiq.R
import com.music.myusiq.utils.ThemeUtils
import com.music.myusiq.utils.isActivityDestroyed
import com.music.myusiq.ui.AlbumUIModel
import androidx.core.net.toUri

class AlbumsAdapter(
    private var albumNames: List<AlbumUIModel>,
    private val onAlbumClick: (String, View) -> Unit,
) : RecyclerView.Adapter<AlbumsAdapter.BaseAlbumViewHolder>() {

    init {
        stateRestorationPolicy = StateRestorationPolicy.PREVENT_WHEN_EMPTY
    }

    companion object {
        private const val VIEW_TYPE_LIST = 0
        private const val VIEW_TYPE_GRID = 1
    }

    // Tracks if layout should render as grid or list
    var isGridView: Boolean = true
        @SuppressLint("NotifyDataSetChanged")
        set(value) {
            field = value
            notifyDataSetChanged()
        }

    override fun getItemViewType(position: Int): Int {
        return if (isGridView) VIEW_TYPE_GRID else VIEW_TYPE_LIST
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): BaseAlbumViewHolder {
        val inflater = LayoutInflater.from(parent.context)
        return if (viewType == VIEW_TYPE_GRID) {
            val view = inflater.inflate(R.layout.item_album_grid, parent, false)
            AlbumGridViewHolder(view)
        } else {
            val view = inflater.inflate(R.layout.item_album_list, parent, false)
            AlbumListViewHolder(view)
        }
    }

    @SuppressLint("SetTextI18n")
    override fun onBindViewHolder(holder: BaseAlbumViewHolder, position: Int) {
        val album = albumNames[position]

        holder.tvAlbumName.text = album.name
        holder.ivAlbumArt.transitionName = "album_art_${album.name}"
        
        if (holder is AlbumListViewHolder) {
            holder.tvAlbumArtist.text = album.artist
            holder.tvBadgeCount?.text = album.tracksCount.toString()
        } else {
            holder.tvAlbumArtist.text =
                "${album.artist} • ${album.tracksCount} ${if (album.tracksCount == 1) "Track" else "Tracks"}"
        }

        holder.itemView.setOnClickListener {
            Log.d("AlbumClick", "Clicked album: ${album.name}")
            onAlbumClick(album.name, holder.ivAlbumArt)
        }

        val representativeSong = album.firstTrack
        if (representativeSong != null) {
            val context = holder.itemView.context
            if (!context.isActivityDestroyed()) {
                val cachedArtFile = com.music.myusiq.utils.ArtworkUtils.getArtworkFile(context, representativeSong.id, representativeSong.path)
                val sArtworkUri = "content://media/external/audio/albumart".toUri()
                val albumArtUri = ContentUris.withAppendedId(sArtworkUri, representativeSong.albumId)
                val modelToLoad: Any = cachedArtFile ?: albumArtUri

                if (holder is AlbumListViewHolder) {
                    Glide.with(context)
                        .asBitmap()
                        .load(modelToLoad)
                        .override(120, 120)
                        .placeholder(R.drawable.ic_music_note_2)
                        .error(R.drawable.ic_music_note_2)
                        .into(
                            object : CustomTarget<Bitmap>() {
                                override fun onResourceReady(resource: Bitmap, transition: Transition<in Bitmap>?) {
                                    holder.ivAlbumArt.setImageBitmap(resource)
                                    applyPaletteToBadge(holder, resource)
                                }

                                override fun onLoadCleared(placeholder: Drawable?) {
                                    holder.ivAlbumArt.setImageDrawable(placeholder)
                                    resetBadgeColors(holder)
                                }
                            },
                        )
                } else {
                    Glide.with(context)
                        .load(modelToLoad)
                        .override(300, 300)
                        .placeholder(R.drawable.ic_music_note_2)
                        .error(R.drawable.ic_music_note_2)
                        .into(holder.ivAlbumArt)
                }
            }
        } else {
            clearHolderArt(holder)
        }
    }

    private fun applyPaletteToBadge(holder: AlbumListViewHolder, bitmap: Bitmap) {
        Palette.from(bitmap).generate { palette ->
            // Use Muted color for the background to keep it subtle but visible
            val bgColor = palette?.getMutedColor(Color.LTGRAY) ?: Color.LTGRAY
            
            // Use Vibrant color for the text/icon to make it pop
            val contentColor = palette?.getVibrantColor(Color.BLACK) 
                ?: palette?.getDarkVibrantColor(Color.BLACK)
                ?: Color.BLACK

            // Ensure sufficient contrast between content and its background
            val finalContentColor = if (ColorUtils.calculateContrast(contentColor, bgColor) < 3.0) {
                if (ColorUtils.calculateLuminance(bgColor) > 0.5) Color.BLACK else Color.WHITE
            } else {
                contentColor
            }

            holder.layoutBadge?.backgroundTintList = ColorStateList.valueOf(bgColor)
            holder.ivBadgeIcon?.imageTintList = ColorStateList.valueOf(finalContentColor)
            holder.tvBadgeCount?.setTextColor(finalContentColor)
        }
    }

    private fun resetBadgeColors(holder: AlbumListViewHolder) {
        val bgColor = ThemeUtils.getThemeColor(holder.itemView.context, android.R.attr.colorBackground)
        val contentColor = ThemeUtils.getThemeColor(holder.itemView.context, android.R.attr.colorPrimary)
        
        holder.layoutBadge?.backgroundTintList = ColorStateList.valueOf(bgColor)
        holder.ivBadgeIcon?.imageTintList = ColorStateList.valueOf(contentColor)
        holder.tvBadgeCount?.setTextColor(contentColor)
    }

    override fun getItemCount(): Int = albumNames.size

    override fun onViewRecycled(holder: BaseAlbumViewHolder) {
        super.onViewRecycled(holder)
        clearHolderArt(holder)
    }

    @SuppressLint("NotifyDataSetChanged")
    fun updateAlbums(newAlbums: List<AlbumUIModel>) {
        this.albumNames = newAlbums
        notifyDataSetChanged()
    }

    private fun clearHolderArt(holder: BaseAlbumViewHolder) {
        val context = holder.itemView.context
        if (!context.isActivityDestroyed()) {
            Glide.with(context).clear(holder.ivAlbumArt)
        }
        holder.ivAlbumArt.setImageResource(R.drawable.ic_music_note_2)
        if (holder is AlbumListViewHolder) {
            resetBadgeColors(holder)
        }
    }

    // Unified base structural reference wrapper
    abstract class BaseAlbumViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        abstract val ivAlbumArt: ImageView
        abstract val tvAlbumName: TextView
        abstract val tvAlbumArtist: TextView
    }
    class AlbumGridViewHolder(view: View) : BaseAlbumViewHolder(view) {
        override val ivAlbumArt: ImageView = view.findViewById(R.id.ivGridAlbumArt)
        override val tvAlbumName: TextView = view.findViewById(R.id.tvGridAlbumName)
        override val tvAlbumArtist: TextView = view.findViewById(R.id.tvGridAlbumArtist)
    }
    class AlbumListViewHolder(view: View) : BaseAlbumViewHolder(view) {
        override val ivAlbumArt: ImageView = view.findViewById(R.id.ivListAlbumArt)
        override val tvAlbumName: TextView = view.findViewById(R.id.tvListAlbumName)
        override val tvAlbumArtist: TextView = view.findViewById(R.id.tvListAlbumArtist)
        val layoutBadge: View? = view.findViewById(R.id.layoutAlbumBadge)
        val ivBadgeIcon: ImageView? = view.findViewById(R.id.ivBadgeIcon)
        val tvBadgeCount: TextView? = view.findViewById(R.id.tvBadgeCount)
    }
}
