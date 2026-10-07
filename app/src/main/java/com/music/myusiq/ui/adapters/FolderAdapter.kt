package com.music.myusiq.ui.adapters

import android.annotation.SuppressLint
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.recyclerview.widget.ListAdapter
import android.widget.TextView
import androidx.appcompat.widget.PopupMenu
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.materialswitch.MaterialSwitch
import com.music.myusiq.R
import com.music.myusiq.domain.models.FolderItem

class FolderAdapter(
    private val onToggleFolder: (FolderItem, Boolean) -> Unit,
    private val onFolderClicked: (FolderItem) -> Unit,
    private val onPlayFolderRequested: (FolderItem) -> Unit,
) : ListAdapter<FolderItem, FolderAdapter.BaseFolderViewHolder>(FolderDiffCallback()) {

    companion object {
        private const val VIEW_TYPE_LIST = 0
        private const val VIEW_TYPE_GRID = 1
    }

    // Expose the view toggle framework trigger state engine
    var isGridView: Boolean = false
        @SuppressLint("NotifyDataSetChanged")
        set(value) {
            field = value
            notifyDataSetChanged()
        }

    override fun getItemViewType(position: Int): Int {
        return if (isGridView) VIEW_TYPE_GRID else VIEW_TYPE_LIST
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): BaseFolderViewHolder {
        val inflater = LayoutInflater.from(parent.context)
        return if (viewType == VIEW_TYPE_GRID) {
            val view = inflater.inflate(R.layout.item_folder_grid, parent, false)
            FolderGridViewHolder(view)
        } else {
            val view = inflater.inflate(R.layout.item_folder, parent, false)
            FolderListViewHolder(view)
        }
    }

    override fun onBindViewHolder(holder: BaseFolderViewHolder, position: Int) {
        holder.bind(getItem(position), onToggleFolder, onFolderClicked, onPlayFolderRequested)
    }

    override fun onViewRecycled(holder: BaseFolderViewHolder) {
        super.onViewRecycled(holder)
        holder.clearListeners()
    }

    // Unified abstract parent class containing the entire view functional engine logic
    abstract class BaseFolderViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        abstract val tvFolderName: TextView
        abstract val tvFolderPath: TextView
        abstract val switchFolder: MaterialSwitch
        abstract val btnFolderOptions: View
        private var activePopupMenu: PopupMenu? = null

        @SuppressLint("SetTextI18n")
        fun bind(
            item: FolderItem,
            onToggleFolder: (FolderItem, Boolean) -> Unit,
            onFolderClicked: (FolderItem) -> Unit,
            onPlayFolderRequested: (FolderItem) -> Unit,
        ) {
            tvFolderName.text = item.folderName
            tvFolderPath.text = "${item.songCount} Tracks • ${item.absolutePath}"

            itemView.setOnClickListener {
                onFolderClicked(item)
            }

            btnFolderOptions.setOnClickListener { view ->
                activePopupMenu?.dismiss()

                val popup = PopupMenu(view.context, view)
                activePopupMenu = popup
                popup.menuInflater.inflate(R.menu.menu_folder_options, popup.menu)
                popup.setOnMenuItemClickListener { menuItem ->
                    when (menuItem.itemId) {
                        R.id.action_play_folder -> {
                            onPlayFolderRequested(item)
                            true
                        }
                        else -> false
                    }
                }
                popup.setOnDismissListener { activePopupMenu = null }
                popup.show()
            }
            switchFolder.setOnCheckedChangeListener(null)
            switchFolder.isChecked = item.isIncluded
            switchFolder.setOnCheckedChangeListener { _, isChecked ->
                onToggleFolder(item, isChecked)
            }
        }

        fun clearListeners() {
            itemView.setOnClickListener(null)
            btnFolderOptions.setOnClickListener(null)
            switchFolder.setOnCheckedChangeListener(null)
            activePopupMenu?.dismiss()
            activePopupMenu = null
        }
    }

    // Targets your original item_folder.xml layout IDs
    class FolderListViewHolder(view: View) : BaseFolderViewHolder(view) {
        override val tvFolderName: TextView = view.findViewById(R.id.tvFolderName)
        override val tvFolderPath: TextView = view.findViewById(R.id.tvFolderPath)
        override val switchFolder: MaterialSwitch = view.findViewById(R.id.switchFolder)
        override val btnFolderOptions: View = view.findViewById(R.id.btnFolderOptions)
    }

    // Targets your new item_folder_grid.xml layout IDs
    class FolderGridViewHolder(view: View) : BaseFolderViewHolder(view) {
        override val tvFolderName: TextView = view.findViewById(R.id.tvGridFolderName)
        override val tvFolderPath: TextView = view.findViewById(R.id.tvGridFolderPath)
        override val switchFolder: MaterialSwitch = view.findViewById(R.id.switchGridFolder)
        override val btnFolderOptions: View = view.findViewById(R.id.btnGridFolderOptions)
    }

    private class FolderDiffCallback : DiffUtil.ItemCallback<FolderItem>() {
        override fun areItemsTheSame(oldItem: FolderItem, newItem: FolderItem): Boolean {
            return oldItem.absolutePath == newItem.absolutePath
        }
        override fun areContentsTheSame(oldItem: FolderItem, newItem: FolderItem): Boolean {
            return oldItem == newItem
        }
    }
}
