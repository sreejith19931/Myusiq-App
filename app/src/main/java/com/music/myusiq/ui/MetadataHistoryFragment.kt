package com.music.myusiq.ui

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import android.widget.Toast
import androidx.core.view.isVisible
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.button.MaterialButton
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.music.myusiq.R
import com.music.myusiq.ui.viewmodels.PlaybackViewModel
import com.music.myusiq.ui.viewmodels.PendingTrackEdit
import com.music.myusiq.utils.MetadataBackupUtils
import com.music.myusiq.utils.TrackEditHistoryItem
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class MetadataHistoryFragment : Fragment(R.layout.fragment_metadata_history) {
    private val viewModel: PlaybackViewModel by activityViewModels()

    private lateinit var rvHistory: RecyclerView
    private lateinit var tvEmpty: TextView
    private lateinit var btnClearAll: MaterialButton
    private lateinit var btnBack: View

    private var historyItems: MutableList<TrackEditHistoryItem> = mutableListOf()

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        rvHistory = view.findViewById(R.id.rvMetadataHistory)
        tvEmpty = view.findViewById(R.id.tvEmptyHistory)
        btnClearAll = view.findViewById(R.id.btnClearAllHistory)
        btnBack = view.findViewById(R.id.btnBackHistory)

        btnBack.setOnClickListener {
            requireActivity().onBackPressedDispatcher.onBackPressed()
        }

        btnClearAll.setOnClickListener {
            if (historyItems.isEmpty()) return@setOnClickListener
            MaterialAlertDialogBuilder(requireContext())
                .setTitle("Clear History")
                .setMessage("Are you sure you want to clear all metadata edit history?")
                .setNegativeButton("Cancel", null)
                .setPositiveButton("Clear") { _, _ ->
                    MetadataBackupUtils.clearTrackEditHistory(requireContext())
                    loadHistory()
                    Toast.makeText(requireContext(), "Edit history cleared.", Toast.LENGTH_SHORT).show()
                }
                .show()
        }

        rvHistory.layoutManager = LinearLayoutManager(requireContext())
        loadHistory()
    }

    private fun loadHistory() {
        historyItems = MetadataBackupUtils.loadTrackEditHistory(requireContext()).toMutableList()
        tvEmpty.isVisible = historyItems.isEmpty()
        rvHistory.isVisible = historyItems.isNotEmpty()
        btnClearAll.isVisible = historyItems.isNotEmpty()

        rvHistory.adapter = MetadataHistoryAdapter(historyItems) { historyItem ->
            rollbackItem(historyItem)
        }
    }

    private fun rollbackItem(item: TrackEditHistoryItem) {
        MaterialAlertDialogBuilder(requireContext())
            .setTitle("Rollback Track Metadata")
            .setMessage("Do you want to restore '${item.newTitle}' back to '${item.oldTitle}' (${item.oldArtist})?")
            .setNegativeButton("Cancel", null)
            .setPositiveButton("Rollback") { _, _ ->
                viewModel.permanentUpdateSongMetadata(
                    context = requireContext(),
                    songId = item.songId,
                    title = item.oldTitle,
                    artist = item.oldArtist,
                    album = item.oldAlbum,
                    year = item.oldYear,
                    genre = item.oldGenre,
                    artUri = null,
                    path = item.path
                )
                Toast.makeText(requireContext(), "Rolling back metadata for ${item.oldTitle}...", Toast.LENGTH_SHORT).show()
                val ctx = requireContext()
                val current = MetadataBackupUtils.loadTrackEditHistory(ctx).toMutableList()
                current.removeAll { it.songId == item.songId && it.timestamp == item.timestamp }
                MetadataBackupUtils.clearTrackEditHistory(ctx)
                current.forEach { h ->
                    val fakeEdit = PendingTrackEdit(h.songId, h.newTitle, h.newArtist, h.newAlbum, h.newYear, h.newGenre, null, h.path)
                    val fakeSong = com.music.myusiq.domain.models.Song(h.songId, h.oldTitle, h.oldArtist, h.path, 0, 0L, h.oldAlbum, h.oldGenre, h.oldYear, 0L, 0L)
                    MetadataBackupUtils.logTrackEdit(ctx, fakeSong, fakeEdit)
                }
                loadHistory()
            }
            .show()
    }
}

class MetadataHistoryAdapter(
    private val items: List<TrackEditHistoryItem>,
    private val onRollback: (TrackEditHistoryItem) -> Unit
) : RecyclerView.Adapter<MetadataHistoryAdapter.ViewHolder>() {

    class ViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        val tvTrackName: TextView = view.findViewById(R.id.tvHistoryTrackName)
        val tvTimestamp: TextView = view.findViewById(R.id.tvHistoryTimestamp)
        val tvArtistAlbum: TextView = view.findViewById(R.id.tvHistoryArtistAlbum)
        val tvOldMeta: TextView = view.findViewById(R.id.tvHistoryOldMeta)
        val tvNewMeta: TextView = view.findViewById(R.id.tvHistoryNewMeta)
        val btnRollback: MaterialButton = view.findViewById(R.id.btnRollbackSingle)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val view = LayoutInflater.from(parent.context).inflate(R.layout.item_metadata_history, parent, false)
        return ViewHolder(view)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val item = items[position]
        holder.tvTrackName.text = item.newTitle.ifBlank { File(item.path).nameWithoutExtension }
        holder.tvArtistAlbum.text = "${item.newArtist} • ${item.newAlbum}"
        holder.tvOldMeta.text = "${item.oldTitle}\n${item.oldArtist}"
        holder.tvNewMeta.text = "${item.newTitle}\n${item.newArtist}"

        val sdf = SimpleDateFormat("MMM dd, yyyy HH:mm", Locale.getDefault())
        holder.tvTimestamp.text = sdf.format(Date(item.timestamp))

        holder.btnRollback.setOnClickListener { onRollback(item) }
    }

    override fun getItemCount(): Int = items.size
}
