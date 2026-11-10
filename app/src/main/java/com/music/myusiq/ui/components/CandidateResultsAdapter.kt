package com.music.myusiq.ui.components

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.bumptech.glide.Glide
import com.google.android.material.button.MaterialButton
import com.music.myusiq.R
import com.music.myusiq.utils.CloudMetadata

class CandidateResultsAdapter(
    private val candidates: List<CloudMetadata>,
    private val onSelect: (CloudMetadata) -> Unit
) : RecyclerView.Adapter<CandidateResultsAdapter.ViewHolder>() {

    class ViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        val ivArt: ImageView = view.findViewById(R.id.ivResultArt)
        val tvTitle: TextView = view.findViewById(R.id.tvResultTitle)
        val tvArtist: TextView = view.findViewById(R.id.tvResultArtist)
        val tvAlbumInfo: TextView = view.findViewById(R.id.tvResultAlbumInfo)
        val btnSelect: MaterialButton = view.findViewById(R.id.btnSelectCandidate)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val view = LayoutInflater.from(parent.context).inflate(R.layout.item_cloud_search_result, parent, false)
        return ViewHolder(view)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val item = candidates[position]
        val context = holder.itemView.context

        holder.tvTitle.text = item.foundTitle ?: "Unknown Title"
        holder.tvArtist.text = item.foundArtist ?: "Unknown Artist"
        val pct = (item.matchConfidence * 100).toInt()
        val infoStr = listOf(item.album, item.year, item.genre, "$pct% match").filterNotNull().filter { it.isNotBlank() }.joinToString(" • ")
        holder.tvAlbumInfo.text = infoStr

        if (item.artworkUrl != null) {
            Glide.with(context).load(item.artworkUrl).placeholder(R.drawable.ic_music_note_2).error(R.drawable.ic_music_note_2).into(holder.ivArt)
        } else {
            Glide.with(context).load(R.drawable.ic_music_note_2).into(holder.ivArt)
        }

        holder.itemView.setOnClickListener { onSelect(item) }
        holder.btnSelect.setOnClickListener { onSelect(item) }
    }

    override fun getItemCount(): Int = candidates.size
}
