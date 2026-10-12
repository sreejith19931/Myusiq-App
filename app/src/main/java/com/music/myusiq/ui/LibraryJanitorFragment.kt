package com.music.myusiq.ui

import android.annotation.SuppressLint
import android.content.ContentUris
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.MediaStore
import android.util.Size
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.core.view.isVisible
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.bumptech.glide.Glide
import com.google.android.material.button.MaterialButton
import com.google.android.material.checkbox.MaterialCheckBox
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import kotlin.math.abs
import kotlin.time.Duration.Companion.milliseconds
import com.music.myusiq.domain.models.Song
import com.music.myusiq.ui.viewmodels.PlaybackViewModel
import com.music.myusiq.ui.viewmodels.PendingTrackEdit
import com.music.myusiq.utils.*
import com.music.myusiq.R

data class JanitorUpdateItem(
    val song: Song,
    val currentArtworkUri: Uri?,
    val proposedEdit: PendingTrackEdit,
    var isSelected: Boolean = true
)

class LibraryJanitorFragment : Fragment(R.layout.fragment_library_janitor) {
    private val viewModel: PlaybackViewModel by activityViewModels()

    private lateinit var tvDuplicateStatus: TextView
    private lateinit var btnCleanDuplicates: MaterialButton
    private lateinit var tvArtStatus: TextView
    private lateinit var btnFindMissingArt: MaterialButton
    private lateinit var tvRepairStatus: TextView
    private lateinit var btnRepairUnknown: MaterialButton

    private var duplicateGroups: List<List<Song>> = emptyList()
    private var missingArtSongs: List<Song> = emptyList()
    private var unknownSongs: List<Song> = emptyList()

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        tvDuplicateStatus = view.findViewById(R.id.tvDuplicateStatus)
        btnCleanDuplicates = view.findViewById(R.id.btnCleanDuplicates)
        tvArtStatus = view.findViewById(R.id.tvArtStatus)
        btnFindMissingArt = view.findViewById(R.id.btnFindMissingArt)
        tvRepairStatus = view.findViewById(R.id.tvRepairStatus)
        btnRepairUnknown = view.findViewById(R.id.btnRepairUnknown)

        setupObservers()

        btnCleanDuplicates.setOnClickListener { showCleanDuplicatesDialog() }
        btnFindMissingArt.setOnClickListener { startAutoFix() }
        btnRepairUnknown.setOnClickListener { startRepairUnknown() }

        context?.let { ctx ->
            PageGuideManager.showGuideIfNeeded(
                ctx,
                pageKey = "janitor",
                pageTitle = "Library Janitor Guide",
                headerIconRes = R.drawable.ic_edit,
                features = listOf(
                    GuideFeatureItem(R.drawable.ic_edit, "Duplicate Track Cleaner", "Scan your device for duplicate audio files and clean up redundant tracks."),
                    GuideFeatureItem(R.drawable.ic_album, "High-Res Artwork Scraper", "Search online databases for official album artwork and embed 1000x1000 covers into ID3/FLAC tags."),
                    GuideFeatureItem(R.drawable.ic_tracks, "Identify Unknown Tracks", "Automatically identify messy file titles and resolve missing artist/album metadata.")
                )
            )
        }
    }

    private fun setupObservers() {
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch {
                    viewModel.songsLibrary.collectLatest { 
                        performScan() 
                    }
                }
                launch {
                    viewModel.artworkRefreshSignatures.collectLatest {
                        performScan()
                    }
                }
            }
        }
    }

    private fun startAutoFix() {
        if (missingArtSongs.isEmpty()) return
        
        MaterialAlertDialogBuilder(requireContext())
            .setTitle("Auto-Fix Metadata")
            .setMessage("Myusiq will search online for official artwork and tags for these ${missingArtSongs.size} tracks. This requires an internet connection.\n\nContinue?")
            .setNegativeButton("Cancel", null)
            .setPositiveButton("Start") { _, _ ->
                executeAutoFix()
            }
            .show()
    }

    @SuppressLint("SetTextI18n")
    private fun executeAutoFix() {
        val context = requireContext().applicationContext
        viewLifecycleOwner.lifecycleScope.launch(Dispatchers.IO) {
            val candidateItems = mutableListOf<JanitorUpdateItem>()
            
            missingArtSongs.forEachIndexed { index, song ->
                withContext(Dispatchers.Main) {
                    if (isAdded) {
                        tvArtStatus.text = "Searching cloud ${index + 1} of ${missingArtSongs.size}: ${song.title}"
                    }
                }
                
                val metadata = CloudMetadataResolver.resolve(song.title, song.artist)
                if (metadata != null) {
                    val artworkUri = metadata.artworkUrl?.let { viewModel.downloadImageToCache(context, it) }

                    val proposedEdit = PendingTrackEdit(
                        songId = song.id,
                        title = song.title,
                        artist = song.artist,
                        album = metadata.album ?: song.album,
                        year = metadata.year ?: "",
                        genre = metadata.genre ?: "",
                        artworkUri = artworkUri,
                        path = song.path
                    )
                    
                    val currentArtUri = try {
                        ContentUris.withAppendedId(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, song.id)
                    } catch (_: Exception) { null }

                    candidateItems.add(JanitorUpdateItem(song, currentArtUri, proposedEdit))
                }
                delay(600.milliseconds)
            }

            withContext(Dispatchers.Main) {
                if (isAdded) {
                    tvArtStatus.text = "Scan complete. Review proposed artwork updates."
                    if (candidateItems.isNotEmpty()) {
                        showJanitorReviewDialog(
                            candidateItems = candidateItems,
                            dialogTitle = "Review Missing Artwork Updates",
                            dialogSub = "Compare current info/art with found cloud matches and select which tracks to update."
                        )
                    } else {
                        Toast.makeText(context, "No cloud matches found for missing artwork.", Toast.LENGTH_SHORT).show()
                    }
                    performScan()
                }
            }
        }
    }

    private fun startRepairUnknown() {
        if (unknownSongs.isEmpty()) return
        
        MaterialAlertDialogBuilder(requireContext())
            .setTitle("Repair Missing Info")
            .setMessage("Myusiq will attempt to identify ${unknownSongs.size} tracks with generic or missing titles/artists and fetch official info.\n\nContinue?")
            .setNegativeButton("Cancel", null)
            .setPositiveButton("Start") { _, _ ->
                btnRepairUnknown.isEnabled = false
                executeRepairUnknown()
            }
            .show()
    }

    @SuppressLint("SetTextI18n")
    private fun executeRepairUnknown() {
        val context = requireContext().applicationContext
        val unknownArtist = getString(R.string.unknown_artist)
        
        viewLifecycleOwner.lifecycleScope.launch(Dispatchers.IO) {
            val candidateItems = mutableListOf<JanitorUpdateItem>()
            
            unknownSongs.forEachIndexed { index, song ->
                withContext(Dispatchers.Main) {
                    if (isAdded) {
                        tvRepairStatus.text = "Searching cloud ${index + 1} of ${unknownSongs.size}: ${song.title}"
                    }
                }
                
                val (extractedArtist, extractedTitle) = YouTubeTitleCleaner.cleanAndSplit(song.title)
                val cleanTitle = extractedTitle.ifBlank { YouTubeTitleCleaner.cleanTitle(song.title) }
                val cleanArtist = if (song.artist == unknownArtist || song.artist.isBlank()) {
                    if (extractedArtist != "Unknown Artist") extractedArtist else ""
                } else song.artist

                val metadata = CloudMetadataResolver.resolve(cleanTitle, cleanArtist)
                if (metadata != null) {
                    val artworkUri = metadata.artworkUrl?.let { viewModel.downloadImageToCache(context, it) }
                    val proposedEdit = PendingTrackEdit(
                        songId = song.id,
                        title = cleanTitle,
                        artist = cleanArtist,
                        album = metadata.album ?: song.album,
                        year = metadata.year ?: "",
                        genre = metadata.genre ?: "",
                        artworkUri = artworkUri,
                        path = song.path
                    )
                    
                    val currentArtUri = try {
                        ContentUris.withAppendedId(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, song.id)
                    } catch (_: Exception) { null }

                    candidateItems.add(JanitorUpdateItem(song, currentArtUri, proposedEdit))
                }
                delay(600.milliseconds)
            }

            withContext(Dispatchers.Main) {
                if (isAdded) {
                    tvRepairStatus.text = "Scan complete. Review proposed metadata repairs."
                    btnRepairUnknown.isEnabled = true
                    if (candidateItems.isNotEmpty()) {
                        showJanitorReviewDialog(
                            candidateItems = candidateItems,
                            dialogTitle = "Review Metadata Repairs",
                            dialogSub = "Compare current info/art with found official info and select which tracks to update."
                        )
                    } else {
                        Toast.makeText(context, "No cloud matches found for repair.", Toast.LENGTH_SHORT).show()
                    }
                    performScan()
                }
            }
        }
    }

    private fun showJanitorReviewDialog(
        candidateItems: List<JanitorUpdateItem>,
        dialogTitle: String,
        dialogSub: String
    ) {
        val dialogView = LayoutInflater.from(requireContext()).inflate(R.layout.dialog_janitor_review, null)
        
        dialogView.findViewById<TextView>(R.id.tvJanitorDialogTitle).text = dialogTitle
        dialogView.findViewById<TextView>(R.id.tvJanitorDialogSub).text = dialogSub

        val cbSelectAll = dialogView.findViewById<MaterialCheckBox>(R.id.cbSelectAll)
        val rvJanitorReview = dialogView.findViewById<RecyclerView>(R.id.rvJanitorReview)
        val btnCancelReview = dialogView.findViewById<MaterialButton>(R.id.btnCancelReview)
        val btnApplySelected = dialogView.findViewById<MaterialButton>(R.id.btnApplySelected)

        var dialog: AlertDialog? = null

        fun updateApplyButtonText() {
            val selectedCount = candidateItems.count { it.isSelected }
            btnApplySelected.text = "Apply Selected ($selectedCount)"
            btnApplySelected.isEnabled = selectedCount > 0
        }

        val adapter = JanitorReviewAdapter(candidateItems) {
            updateApplyButtonText()
            cbSelectAll.setOnCheckedChangeListener(null)
            cbSelectAll.isChecked = candidateItems.all { it.isSelected }
            cbSelectAll.setOnCheckedChangeListener { _, isChecked ->
                candidateItems.forEach { it.isSelected = isChecked }
                rvJanitorReview.adapter?.notifyDataSetChanged()
                updateApplyButtonText()
            }
        }

        rvJanitorReview.layoutManager = LinearLayoutManager(requireContext())
        rvJanitorReview.adapter = adapter

        cbSelectAll.isChecked = candidateItems.all { it.isSelected }
        cbSelectAll.setOnCheckedChangeListener { _, isChecked ->
            candidateItems.forEach { it.isSelected = isChecked }
            adapter.notifyDataSetChanged()
            updateApplyButtonText()
        }

        updateApplyButtonText()

        btnCancelReview.setOnClickListener { dialog?.dismiss() }

        btnApplySelected.setOnClickListener {
            val selectedEdits = candidateItems.filter { it.isSelected }.map { it.proposedEdit }
            if (selectedEdits.isNotEmpty()) {
                viewModel.requestBulkMetadataUpdate(requireContext(), selectedEdits)
                Toast.makeText(requireContext(), "Applying changes for ${selectedEdits.size} tracks...", Toast.LENGTH_LONG).show()
            }
            dialog?.dismiss()
            performScan()
        }

        dialog = MaterialAlertDialogBuilder(requireContext())
            .setView(dialogView)
            .create()

        dialog.show()
    }

    @SuppressLint("SetTextI18n")
    private fun performScan() {
        val allSongs = viewModel.songsLibrary.value
        if (allSongs.isEmpty()) {
            tvDuplicateStatus.text = "No songs found in library."
            tvArtStatus.text = "No songs found in library."
            tvRepairStatus.text = "No songs found in library."
            return
        }

        val appContext = requireContext().applicationContext
        val contentResolver = appContext.contentResolver

        viewLifecycleOwner.lifecycleScope.launch(Dispatchers.Default) {
            val groups = allSongs.groupBy { "${it.title.lowercase()}|${it.artist.lowercase()}" }
                .values
                .filter { it.size > 1 }
                .map { group ->
                    group.filter { s1 ->
                        group.any { s2 -> s1 != s2 && abs(s1.duration - s2.duration) < 2000 }
                    }
                }
                .filter { it.size > 1 }

            val missingArt = allSongs
                .distinctBy { PlatformUtils.normalizePath(it.path) }
                .filter { song ->
                    val trackUri = ContentUris.withAppendedId(
                        MediaStore.Audio.Media.EXTERNAL_CONTENT_URI,
                        song.id
                    )
                    var hasArt = false

                    // 1. Check local artwork cache, persistent backup, and physical embedded picture
                    val artFile = ArtworkUtils.getArtworkFile(appContext, song.id, song.path)
                    if (artFile != null && artFile.exists() && artFile.length() > 0) {
                        hasArt = true
                    }

                    // 2. Fall back to MediaStore system thumbnail if local image file wasn't found
                    if (!hasArt) {
                        try {
                            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                                contentResolver.loadThumbnail(trackUri, Size(100, 100), null)
                                hasArt = true
                            }
                        } catch (_: Exception) {
                            // Thumbnails fail if no artwork is embedded or present
                        }
                    }
                    !hasArt
                }

            val unknownArtist = getString(R.string.unknown_artist)
            val toRepair = allSongs.filter { 
                it.artist == unknownArtist || it.title.contains(".mp3", true) || it.title.lowercase() == "track"
            }

            if (!isAdded) return@launch

            withContext(Dispatchers.Main) {
                if (!isAdded) return@withContext
                
                duplicateGroups = groups
                missingArtSongs = missingArt
                unknownSongs = toRepair

                val totalDuplicates = groups.sumOf { it.size - 1 }
                tvDuplicateStatus.text = if (totalDuplicates > 0) "Found $totalDuplicates redundant duplicates." else "No duplicates found."
                btnCleanDuplicates.isVisible = totalDuplicates > 0

                tvArtStatus.text = if (missingArt.isNotEmpty()) "Found ${missingArt.size} tracks without cover art." else "All tracks have artwork."
                btnFindMissingArt.isVisible = missingArt.isNotEmpty()

                tvRepairStatus.text = if (toRepair.isNotEmpty()) "Found ${toRepair.size} tracks with missing or messy info." else "All metadata looks clean."
                btnRepairUnknown.isVisible = toRepair.isNotEmpty()
            }
        }
    }

    private fun showCleanDuplicatesDialog() {
        val totalToDelete = duplicateGroups.sumOf { it.size - 1 }
        val fileListText = duplicateGroups.flatMap { it.drop(1) }
            .take(15)
            .joinToString("\n") { "• ${it.title}" } + if (totalToDelete > 15) "\n...and ${totalToDelete - 15} more" else ""

        MaterialAlertDialogBuilder(requireContext())
            .setTitle("Clean Duplicates")
            .setMessage("This will delete $totalToDelete redundant files:\n\n$fileListText")
            .setNegativeButton("Cancel", null)
            .setPositiveButton("Clean Up") { _, _ -> deleteDuplicates() }
            .show()
    }

    private fun deleteDuplicates() {
        val songsToDelete = duplicateGroups.flatMap { it.drop(1) }
        if (songsToDelete.isEmpty()) return

        viewLifecycleOwner.lifecycleScope.launch(Dispatchers.IO) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                val uris = songsToDelete.map {
                    ContentUris.withAppendedId(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, it.id)
                }
                val intent = MediaStore.createDeleteRequest(requireContext().contentResolver, uris)
                withContext(Dispatchers.Main) {
                    viewModel.requestDelete(intent)
                }
            } else {
                var deletedCount = 0
                songsToDelete.forEach { if (File(it.path).delete()) deletedCount++ }
                withContext(Dispatchers.Main) {
                    Toast.makeText(requireContext(), "Deleted $deletedCount files.", Toast.LENGTH_LONG).show()
                    viewModel.forceScanMediaLibrary()
                }
            }
        }
    }
}

class JanitorReviewAdapter(
    private val items: List<JanitorUpdateItem>,
    private val onItemSelectionChanged: () -> Unit
) : RecyclerView.Adapter<JanitorReviewAdapter.ViewHolder>() {

    class ViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        val cbSelectTrack: MaterialCheckBox = view.findViewById(R.id.cbSelectTrack)
        val tvTrackPath: TextView = view.findViewById(R.id.tvTrackPath)
        val ivCurrentArt: ImageView = view.findViewById(R.id.ivCurrentArt)
        val tvCurrentTitle: TextView = view.findViewById(R.id.tvCurrentTitle)
        val tvCurrentArtist: TextView = view.findViewById(R.id.tvCurrentArtist)
        val tvCurrentAlbum: TextView = view.findViewById(R.id.tvCurrentAlbum)
        val ivFoundArt: ImageView = view.findViewById(R.id.ivFoundArt)
        val tvFoundTitle: TextView = view.findViewById(R.id.tvFoundTitle)
        val tvFoundArtist: TextView = view.findViewById(R.id.tvFoundArtist)
        val tvFoundAlbum: TextView = view.findViewById(R.id.tvFoundAlbum)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val view = LayoutInflater.from(parent.context).inflate(R.layout.item_janitor_update_review, parent, false)
        return ViewHolder(view)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val item = items[position]
        val context = holder.itemView.context

        holder.cbSelectTrack.setOnCheckedChangeListener(null)
        holder.cbSelectTrack.isChecked = item.isSelected
        holder.cbSelectTrack.setOnCheckedChangeListener { _, isChecked ->
            item.isSelected = isChecked
            onItemSelectionChanged()
        }

        holder.tvTrackPath.text = File(item.song.path).name

        // Current Info
        holder.tvCurrentTitle.text = item.song.title
        holder.tvCurrentArtist.text = item.song.artist
        holder.tvCurrentAlbum.text = item.song.album

        if (item.currentArtworkUri != null) {
            Glide.with(context).load(item.currentArtworkUri).placeholder(R.drawable.ic_music_note_2).error(R.drawable.ic_music_note_2).into(holder.ivCurrentArt)
        } else {
            Glide.with(context).load(R.drawable.ic_music_note_2).into(holder.ivCurrentArt)
        }

        // Found Info
        holder.tvFoundTitle.text = item.proposedEdit.title
        holder.tvFoundArtist.text = item.proposedEdit.artist
        holder.tvFoundAlbum.text = item.proposedEdit.album

        if (item.proposedEdit.artworkUri != null) {
            Glide.with(context).load(item.proposedEdit.artworkUri).placeholder(R.drawable.ic_music_note_2).error(R.drawable.ic_music_note_2).into(holder.ivFoundArt)
        } else {
            Glide.with(context).load(R.drawable.ic_music_note_2).into(holder.ivFoundArt)
        }
    }

    override fun getItemCount(): Int = items.size
}
