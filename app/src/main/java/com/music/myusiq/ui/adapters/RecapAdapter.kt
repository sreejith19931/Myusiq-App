package com.music.myusiq.ui.adapters

import android.annotation.SuppressLint
import android.content.ContentUris
import android.provider.MediaStore
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.music.myusiq.R
import com.music.myusiq.utils.isActivityDestroyed
import com.music.myusiq.domain.models.TopTrack
import com.bumptech.glide.Glide
import com.bumptech.glide.load.resource.drawable.DrawableTransitionOptions

class RecapAdapter(private val tracks: List<TopTrack>) : RecyclerView.Adapter<RecapAdapter.ViewHolder>() {

    class ViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        val tvRank: TextView = view.findViewById(R.id.tvRank)
        val ivTrackArt: ImageView = view.findViewById(R.id.ivTrackArt)
        val tvTrackTitle: TextView = view.findViewById(R.id.tvTrackTitle)
        val tvTrackArtist: TextView = view.findViewById(R.id.tvTrackArtist)
        val tvPlayCount: TextView = view.findViewById(R.id.tvPlayCount)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val view = LayoutInflater.from(parent.context).inflate(R.layout.item_recap_track, parent, false)
        return ViewHolder(view)
    }

    @SuppressLint("SetTextI18n")
    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val track = tracks[position]
        holder.tvRank.text = (position + 1).toString()
        holder.tvTrackTitle.text = track.title
        holder.tvTrackArtist.text = track.artist
        holder.tvPlayCount.text = "${track.playCount} plays"

        val songIdLong = track.songId.toLongOrNull() ?: 0L
        if (songIdLong != 0L) {
            val trackUri = ContentUris.withAppendedId(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, songIdLong)
            
            val context = holder.ivTrackArt.context
            if (!context.isActivityDestroyed()) {
                Glide.with(context)
                    .load(trackUri)
                    .placeholder(R.drawable.ic_music_note_2)
                    .error(R.drawable.ic_music_note_2)
                    .transition(DrawableTransitionOptions.withCrossFade())
                    .into(holder.ivTrackArt)
            }
        } else {
            holder.ivTrackArt.setImageResource(R.drawable.ic_music_note_2)
        }
    }

    override fun onViewRecycled(holder: ViewHolder) {
        super.onViewRecycled(holder)
        val context = holder.ivTrackArt.context
        if (!context.isActivityDestroyed()) {
            Glide.with(context).clear(holder.ivTrackArt)
        }
    }

    override fun getItemCount() = tracks.size
}
