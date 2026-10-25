package com.music.myusiq.ui.adapters

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.music.myusiq.R

data class CustomFolderItem(
    val uriString: String,
    val displayName: String,
    val trackCount: Int = 0
)

class CustomFolderAdapter(
    private val onRemoveFolderClicked: (CustomFolderItem) -> Unit
) : ListAdapter<CustomFolderItem, CustomFolderAdapter.ViewHolder>(DiffCallback()) {

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val view = LayoutInflater.from(parent.context).inflate(R.layout.item_custom_folder, parent, false)
        return ViewHolder(view)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        holder.bind(getItem(position), onRemoveFolderClicked)
    }

    class ViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        private val tvName: TextView = itemView.findViewById(R.id.tvCustomFolderName)
        private val tvPath: TextView = itemView.findViewById(R.id.tvCustomFolderPath)
        private val btnRemove: View = itemView.findViewById(R.id.btnRemoveCustomFolder)

        fun bind(item: CustomFolderItem, onRemove: (CustomFolderItem) -> Unit) {
            tvName.text = item.displayName
            val trackInfo = if (item.trackCount > 0) "${item.trackCount} Tracks • " else ""
            tvPath.text = "$trackInfo${item.uriString}"
            btnRemove.setOnClickListener { onRemove(item) }
        }
    }

    private class DiffCallback : DiffUtil.ItemCallback<CustomFolderItem>() {
        override fun areItemsTheSame(oldItem: CustomFolderItem, newItem: CustomFolderItem): Boolean {
            return oldItem.uriString == newItem.uriString
        }

        override fun areContentsTheSame(oldItem: CustomFolderItem, newItem: CustomFolderItem): Boolean {
            return oldItem == newItem
        }
    }
}
