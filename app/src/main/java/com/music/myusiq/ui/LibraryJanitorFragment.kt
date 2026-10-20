package com.music.myusiq.ui

import android.annotation.SuppressLint
import android.content.ContentUris
import android.os.Build
import android.os.Bundle
import android.provider.MediaStore
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
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import kotlin.math.abs
import kotlin.time.Duration.Companion.milliseconds
import com.music.myusiq.domain.models.Song
import com.music.myusiq.domain.models.JanitorUpdateItem
import com.music.myusiq.ui.viewmodels.PlaybackViewModel
import com.music.myusiq.ui.viewmodels.PendingTrackEdit
import com.music.myusiq.utils.*
import com.music.myusiq.R

class LibraryJanitorFragment : Fragment(R.layout.fragment_library_janitor) {
    private val viewModel: PlaybackViewModel by activityViewModels()

    private lateinit var tvDuplicateStatus: TextView
    private lateinit var btnCleanDuplicates: MaterialButton
    private lateinit var cardAutoFix: View
    private lateinit var tvAutoFixStatus: TextView
    private lateinit var btnAutoFixAll: MaterialButton
    private lateinit var cardUndoFix: View
    private lateinit var btnUndoLastFix: MaterialButton

    private var duplicateGroups: List<List<Song>> = emptyList()
    private var allFixCandidates: List<Song> = emptyList()
    private var activeScanJob: kotlinx.coroutines.Job? = null

    override fun onDestroyView() {
        activeScanJob?.cancel()
        super.onDestroyView()
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        tvDuplicateStatus = view.findViewById(R.id.tvDuplicateStatus)
        btnCleanDuplicates = view.findViewById(R.id.btnCleanDuplicates)
        cardAutoFix = view.findViewById(R.id.cardAutoFix)
        tvAutoFixStatus = view.findViewById(R.id.tvAutoFixStatus)
        btnAutoFixAll = view.findViewById(R.id.btnAutoFixAll)
        cardUndoFix = view.findViewById(R.id.cardUndoFix)
        btnUndoLastFix = view.findViewById(R.id.btnUndoLastFix)

        setupObservers()
        updateUndoCardVisibility()

        btnCleanDuplicates.setOnClickListener { showCleanDuplicatesDialog() }
        btnAutoFixAll.setOnClickListener { startAutoFix() }
        cardAutoFix.setOnClickListener { startAutoFix() }
        tvAutoFixStatus.setOnClickListener { startAutoFix() }
        btnUndoLastFix.setOnClickListener { performRevertMetadataFix() }

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

        if (arguments?.getBoolean("auto_start_scan", false) == true) {
            arguments?.putBoolean("auto_start_scan", false)
            view.post {
                startAutoFix()
            }
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
                launch {
                    viewModel.janitorScanState.collectLatest { state ->
                        when (state) {
                            is com.music.myusiq.domain.usecase.ScanState.Progress -> {
                                tvAutoFixStatus.text = state.message
                            }
                            is com.music.myusiq.domain.usecase.ScanState.ScanCompleted -> {
                                tvAutoFixStatus.text = "Scan complete. Review proposed updates."
                                btnAutoFixAll.isEnabled = true
                                if (state.results.isNotEmpty() && isAdded) {
                                    val matchedCount = state.results.count { it.proposedEdit.matchConfidence > 0 }
                                    val unmatchedCount = state.results.size - matchedCount
                                    val dialogSub = when {
                                        matchedCount > 0 && unmatchedCount > 0 ->
                                            "Found $matchedCount automatic fixes and $unmatchedCount tracks requiring manual search. Tap 'Search Online' on any track to find metadata."
                                        matchedCount > 0 ->
                                            "Found $matchedCount suggested fixes. Choose which tracks to update or search online."
                                        else ->
                                            "Found ${state.results.size} tracks needing metadata/artwork. Tap 'Search Online' on any track to find its metadata."
                                    }
                                    showJanitorReviewDialog(
                                        candidateItems = state.results.toMutableList(),
                                        dialogTitle = "Review Proposed Fixes (Artwork & Metadata Updates)",
                                        dialogSub = dialogSub
                                    )
                                } else if (isAdded) {
                                    Toast.makeText(context, "No tracks needing metadata fixes.", Toast.LENGTH_SHORT).show()
                                }
                                performScan()
                            }
                            null -> {}
                            else -> {}
                        }
                    }
                }
                launch {
                    viewModel.isJanitorScanning.collectLatest { isScanning ->
                        btnAutoFixAll.isEnabled = !isScanning
                    }
                }
            }
        }
    }

    private fun needsFixOrArtwork(context: android.content.Context, song: Song): Boolean {
        val isMessy = isMessyOrUnknownTrack(song)
        val artFile = ArtworkUtils.getArtworkFile(context, song.id, song.path, song.album)
        val hasMissingArt = artFile == null || !artFile.exists() || artFile.length() == 0L
        return isMessy || hasMissingArt
    }

    private fun startAutoFix() {
        val allSongs = viewModel.songsLibrary.value
        if (allSongs.isEmpty()) {
            Toast.makeText(requireContext(), "No tracks found in library", Toast.LENGTH_SHORT).show()
            return
        }

        val appContext = requireContext().applicationContext
        val prefs = appContext.getSharedPreferences("app_prefs", android.content.Context.MODE_PRIVATE)
        val initialScanDone = prefs.getBoolean("janitor_initial_scan_done", false)

        val candidates = if (!initialScanDone) {
            allSongs
        } else {
            allSongs.filter { needsFixOrArtwork(appContext, it) }
        }

        if (candidates.isEmpty()) {
            Toast.makeText(requireContext(), "All tracks in your library are already up to date!", Toast.LENGTH_SHORT).show()
            return
        }

        val messageText = if (!initialScanDone) {
            "First-time Library Scan: Myusiq will scan all ${candidates.size} tracks in your library for possible artwork and metadata improvements.\n\nContinue?"
        } else {
            "Myusiq will search online for official artwork and metadata for ${candidates.size} tracks with missing artwork or messy tags.\n\nContinue?"
        }

        MaterialAlertDialogBuilder(requireContext())
            .setTitle("Auto-Fix Metadata & Artwork")
            .setMessage(messageText)
            .setNegativeButton("Skip") { _, _ ->
                prefs.edit().putBoolean("janitor_initial_scan_done", true).apply()
                performScan()
            }
            .setPositiveButton("Start") { _, _ ->
                executeScanAndFix(candidates, "Artwork & Metadata Updates")
            }
            .show()
    }

    @SuppressLint("SetTextI18n")
    private fun executeScanAndFix(tracksToScan: List<Song>, scanType: String) {
        viewModel.startJanitorScan(tracksToScan, requireContext())
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

        val btnBulkSearchOnline = dialogView.findViewById<MaterialButton>(R.id.btnBulkSearchOnline)
        btnBulkSearchOnline?.setOnClickListener {
            val unmatched = candidateItems.filter { it.proposedEdit.matchConfidence == 0 || it.proposedEdit.artworkUri == null }
            if (unmatched.isEmpty()) {
                Toast.makeText(requireContext(), "All tracks already have artwork and metadata assigned!", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }

            btnBulkSearchOnline.isEnabled = false
            btnBulkSearchOnline.text = "Searching online..."

            lifecycleScope.launch(Dispatchers.IO) {
                var foundCount = 0
                val ctx = requireContext().applicationContext
                for (item in unmatched) {
                    val query = "${item.proposedEdit.artist} ${item.proposedEdit.title}".trim().ifBlank { File(item.song.path).nameWithoutExtension }
                    val results = CloudMetadataResolver.searchMultiple(query, item.song.title, item.song.artist)
                    if (results.isNotEmpty()) {
                        val top = results.first()
                        val uri = top.artworkUrl?.let { viewModel.downloadImageToCache(ctx, it) }
                        val confidencePct = (top.matchConfidence * 100).toInt()

                        item.proposedEdit = item.proposedEdit.copy(
                            title = top.foundTitle ?: item.proposedEdit.title,
                            artist = top.foundArtist ?: item.proposedEdit.artist,
                            album = top.album ?: item.proposedEdit.album,
                            year = top.year ?: item.proposedEdit.year,
                            genre = top.genre ?: item.proposedEdit.genre,
                            artworkUri = uri ?: item.proposedEdit.artworkUri,
                            matchConfidence = confidencePct
                        )
                        item.isSelected = true
                        foundCount++
                    }
                }

                withContext(Dispatchers.Main) {
                    if (isAdded) {
                        btnBulkSearchOnline.isEnabled = true
                        btnBulkSearchOnline.text = "Search All Messy Tracks Online"
                        rvJanitorReview.adapter?.notifyDataSetChanged()
                        updateApplyButtonText()
                        Toast.makeText(requireContext(), "Found online matches for $foundCount tracks!", Toast.LENGTH_SHORT).show()
                    }
                }
            }
        }

        btnCancelReview.setOnClickListener {
            val prefs = requireContext().getSharedPreferences("app_prefs", android.content.Context.MODE_PRIVATE)
            prefs.edit().putBoolean("janitor_initial_scan_done", true).apply()
            dialog?.dismiss()
        }

        btnApplySelected.setOnClickListener {
            val prefs = requireContext().getSharedPreferences("app_prefs", android.content.Context.MODE_PRIVATE)
            prefs.edit().putBoolean("janitor_initial_scan_done", true).apply()
            val selectedEdits = candidateItems.filter { it.isSelected }.map { it.proposedEdit }
            if (selectedEdits.isNotEmpty()) {
                viewModel.requestBulkMetadataUpdate(requireContext(), selectedEdits)
                Toast.makeText(requireContext(), "Applying changes for ${selectedEdits.size} tracks...", Toast.LENGTH_LONG).show()
            }
            dialog?.dismiss()
        }

        dialog = MaterialAlertDialogBuilder(requireContext())
            .setView(dialogView)
            .create()

        val prefs = requireContext().getSharedPreferences("app_prefs", android.content.Context.MODE_PRIVATE)
        prefs.edit().putBoolean("janitor_initial_scan_done", true).apply()

        dialog.show()
    }

    private fun isMessyOrUnknownTrack(song: Song): Boolean {
        val artist = song.artist.trim()
        val title = song.title.trim()

        val isUnknownArtist = CloudMetadataResolver.isGenericArtist(artist)
        val hasFileExtension = Regex("(?i)\\.(mp3|m4a|flac|wav|aac|ogg|wma)$").containsMatchIn(title)
        val isGenericTitle = Regex("(?i)^(track|audio|sound|recording)[_\\s\\-]*\\d*$").matches(title) || title.isBlank()
        
        val cleanedTitle = YouTubeTitleCleaner.cleanTitle(title)
        val hasClutter = !cleanedTitle.equals(title, ignoreCase = true) && 
                         (title.contains("_") || 
                          title.contains(Regex("""(?i)[(\[{].*?(official|music|video|audio|lyric|hd|4k|remix|visualizer|mv|topic|prod|feat|ft|full song).*?[)\]}]""")) || 
                          title.contains(Regex("""(?i)\b(www\.|https?://)?[a-z0-9\-]+\.(com|net|org|io|me|co|info|biz)\b""")))

        val isGenericArtistWithGenericTitle = isUnknownArtist && (isGenericTitle || artist.isBlank())

        return isGenericArtistWithGenericTitle || hasFileExtension || isGenericTitle || hasClutter
    }

    @SuppressLint("SetTextI18n")
    private fun performScan() {
        val allSongs = viewModel.songsLibrary.value
        if (allSongs.isEmpty()) {
            tvDuplicateStatus.text = "No songs found in library."
            tvAutoFixStatus.text = "No songs found in library."
            return
        }

        val appContext = requireContext().applicationContext

        viewLifecycleOwner.lifecycleScope.launch(Dispatchers.Default) {
            val groups = allSongs.groupBy { "${it.title.trim().lowercase()}|${it.artist.trim().lowercase()}" }
                .values
                .filter { it.size > 1 }
                .map { group ->
                    group.filter { s1 ->
                        group.any { s2 -> s1 != s2 && abs(s1.duration - s2.duration) < 2000 }
                    }
                }
                .filter { it.size > 1 }

            val prefs = appContext.getSharedPreferences("app_prefs", android.content.Context.MODE_PRIVATE)
            val initialScanDone = prefs.getBoolean("janitor_initial_scan_done", false)

            val missingArt = allSongs
                .distinctBy { PlatformUtils.normalizePath(it.path) }
                .filter { song ->
                    val artFile = ArtworkUtils.getArtworkFile(appContext, song.id, song.path, song.album)
                    artFile == null || !artFile.exists() || artFile.length() == 0L
                }

            val toRepair = allSongs.filter { song ->
                isMessyOrUnknownTrack(song)
            }

            val consolidatedCandidates = if (!initialScanDone) {
                allSongs
            } else {
                (missingArt + toRepair).distinctBy { it.id }
            }

            if (!isAdded) return@launch

            withContext(Dispatchers.Main) {
                if (!isAdded) return@withContext
                
                duplicateGroups = groups
                allFixCandidates = consolidatedCandidates

                val totalDuplicates = groups.sumOf { it.size - 1 }
                tvDuplicateStatus.text = if (totalDuplicates > 0) "Found $totalDuplicates redundant duplicates." else "No duplicates found."
                btnCleanDuplicates.isVisible = totalDuplicates > 0

                val statusMessage = when {
                    !initialScanDone ->
                        "First-time Scan: ${allSongs.size} tracks ready for artwork & metadata check. Tap to start."
                    toRepair.isNotEmpty() && missingArt.isNotEmpty() ->
                        "Found ${toRepair.size} tracks with messy metadata and ${missingArt.size} tracks with missing artwork. Tap to review."
                    toRepair.isNotEmpty() ->
                        "Found ${toRepair.size} tracks with messy metadata. Tap to review."
                    missingArt.isNotEmpty() ->
                        "Found ${missingArt.size} tracks with missing artwork. Tap to review."
                    else ->
                        "All metadata and artwork look clean."
                }
                tvAutoFixStatus.text = statusMessage
                btnAutoFixAll.isVisible = consolidatedCandidates.isNotEmpty()
                updateUndoCardVisibility()
            }
        }
    }

    private fun updateUndoCardVisibility() {
        val ctx = context ?: return
        cardUndoFix.isVisible = MetadataBackupUtils.hasPreEditSnapshots(ctx)
    }

    private fun performRevertMetadataFix() {
        val ctx = requireContext()
        MaterialAlertDialogBuilder(ctx)
            .setTitle("Revert Metadata Fix")
            .setMessage("Do you want to restore your metadata and artwork back to the previous state?")
            .setNegativeButton("Cancel", null)
            .setPositiveButton("Revert") { _, _ ->
                Toast.makeText(ctx, "Restoring previous metadata...", Toast.LENGTH_SHORT).show()
                viewModel.revertLastMetadataFix(ctx) { count ->
                    Toast.makeText(ctx, "Reverted metadata for $count tracks.", Toast.LENGTH_LONG).show()
                    updateUndoCardVisibility()
                    performScan()
                }
            }
            .show()
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
        val btnSearchOnlineTrack: android.widget.ImageButton? = view.findViewById(R.id.btnSearchOnlineTrack)
        val btnEditProposed: android.widget.ImageButton = view.findViewById(R.id.btnEditProposed)
        val ivCurrentArt: ImageView = view.findViewById(R.id.ivCurrentArt)
        val tvCurrentTitle: TextView = view.findViewById(R.id.tvCurrentTitle)
        val tvCurrentArtist: TextView = view.findViewById(R.id.tvCurrentArtist)
        val tvCurrentAlbum: TextView = view.findViewById(R.id.tvCurrentAlbum)
        val tvCurrentYearGenre: TextView = view.findViewById(R.id.tvCurrentYearGenre)
        val layoutFoundSection: View? = view.findViewById(R.id.layoutFoundSection)
        val ivFoundArt: ImageView = view.findViewById(R.id.ivFoundArt)
        val tvFoundTitle: TextView = view.findViewById(R.id.tvFoundTitle)
        val tvFoundArtist: TextView = view.findViewById(R.id.tvFoundArtist)
        val tvFoundAlbum: TextView = view.findViewById(R.id.tvFoundAlbum)
        val tvFoundYearGenre: TextView = view.findViewById(R.id.tvFoundYearGenre)
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
        val currentYrGenre = item.song.genre
        holder.tvCurrentYearGenre.text = currentYrGenre.ifBlank { "—" }

        if (item.currentArtworkUri != null) {
            Glide.with(context).load(item.currentArtworkUri).placeholder(R.drawable.ic_music_note_2).error(R.drawable.ic_music_note_2).into(holder.ivCurrentArt)
        } else {
            Glide.with(context).load(R.drawable.ic_music_note_2).into(holder.ivCurrentArt)
        }

        // Found Info
        holder.tvFoundTitle.text = item.proposedEdit.title
        holder.tvFoundArtist.text = item.proposedEdit.artist
        holder.tvFoundAlbum.text = item.proposedEdit.album
        val matchLabel = if (item.proposedEdit.matchConfidence > 0) "${item.proposedEdit.matchConfidence}% match" else "No online match • Tap to Search"
        val foundYrGenre = if (item.proposedEdit.matchConfidence > 0) {
            listOf(item.proposedEdit.year, item.proposedEdit.genre, matchLabel).filter { it.isNotBlank() }.joinToString(" • ")
        } else {
            matchLabel
        }
        holder.tvFoundYearGenre.text = foundYrGenre.ifBlank { "—" }

        if (item.proposedEdit.artworkUri != null) {
            Glide.with(context).load(item.proposedEdit.artworkUri).placeholder(R.drawable.ic_music_note_2).error(R.drawable.ic_music_note_2).into(holder.ivFoundArt)
        } else {
            Glide.with(context).load(R.drawable.ic_music_note_2).into(holder.ivFoundArt)
        }

        fun triggerOnlineSearch() {
            showManualSearchDialog(context, item, position) { candidate ->
                val activity = context as? androidx.fragment.app.FragmentActivity
                if (activity != null) {
                    activity.lifecycleScope.launch {
                        val vm = androidx.lifecycle.ViewModelProvider(activity)[PlaybackViewModel::class.java]
                        val uri = candidate.artworkUrl?.let { vm.downloadImageToCache(context, it) }
                        val confidencePct = (candidate.matchConfidence * 100).toInt()
                        item.proposedEdit = item.proposedEdit.copy(
                            title = candidate.foundTitle ?: item.proposedEdit.title,
                            artist = candidate.foundArtist ?: item.proposedEdit.artist,
                            album = candidate.album ?: item.proposedEdit.album,
                            year = candidate.year ?: item.proposedEdit.year,
                            genre = candidate.genre ?: item.proposedEdit.genre,
                            artworkUri = uri ?: item.proposedEdit.artworkUri,
                            matchConfidence = confidencePct
                        )
                        item.isSelected = true
                        notifyItemChanged(position)
                        onItemSelectionChanged()
                    }
                }
            }
        }

        holder.btnSearchOnlineTrack?.setOnClickListener { triggerOnlineSearch() }
        holder.layoutFoundSection?.setOnClickListener { triggerOnlineSearch() }
        holder.btnEditProposed.setOnClickListener {
            showInlineEditDialog(context, item, position)
        }
    }

    private fun showInlineEditDialog(context: android.content.Context, item: JanitorUpdateItem, position: Int) {
        val density = context.resources.displayMetrics.density
        val padding = (20 * density).toInt()

        val layout = android.widget.LinearLayout(context).apply {
            orientation = android.widget.LinearLayout.VERTICAL
            setPadding(padding, (10 * density).toInt(), padding, (10 * density).toInt())
        }

        fun createInput(label: String, initialValue: String): com.google.android.material.textfield.TextInputEditText {
            val til = com.google.android.material.textfield.TextInputLayout(context)
            til.hint = label
            val tiet = com.google.android.material.textfield.TextInputEditText(context).apply {
                setText(initialValue)
                maxLines = 1
            }
            til.addView(tiet)
            layout.addView(til)
            return tiet
        }

        val etTitle = createInput("Title", item.proposedEdit.title)
        val etArtist = createInput("Artist", item.proposedEdit.artist)
        val etAlbum = createInput("Album", item.proposedEdit.album)
        val etYear = createInput("Year", item.proposedEdit.year)
        val etGenre = createInput("Genre", item.proposedEdit.genre)

        val btnSearchOnline = MaterialButton(context, null, com.google.android.material.R.attr.materialButtonOutlinedStyle).apply {
            text = "Search Online"
            setIconResource(R.drawable.ic_search)
            setOnClickListener {
                showManualSearchDialog(context, item, position) { candidate ->
                    etTitle.setText(candidate.foundTitle ?: item.proposedEdit.title)
                    etArtist.setText(candidate.foundArtist ?: item.proposedEdit.artist)
                    etAlbum.setText(candidate.album ?: item.proposedEdit.album)
                    etYear.setText(candidate.year ?: item.proposedEdit.year)
                    etGenre.setText(candidate.genre ?: item.proposedEdit.genre)

                    candidate.artworkUrl?.let { url ->
                        val activity = context as? androidx.fragment.app.FragmentActivity
                        if (activity != null) {
                            activity.lifecycleScope.launch {
                                val vm = androidx.lifecycle.ViewModelProvider(activity)[PlaybackViewModel::class.java]
                                val uri = vm.downloadImageToCache(context, url)
                                if (uri != null) {
                                    item.proposedEdit = item.proposedEdit.copy(artworkUri = uri)
                                    notifyItemChanged(position)
                                }
                            }
                        }
                    }
                }
            }
        }
        layout.addView(btnSearchOnline)

        MaterialAlertDialogBuilder(context)
            .setTitle("Edit Proposed Fix")
            .setView(layout)
            .setPositiveButton("Save") { _, _ ->
                val updatedEdit = item.proposedEdit.copy(
                    title = etTitle.text?.toString()?.trim().orEmpty().ifBlank { item.song.title },
                    artist = etArtist.text?.toString()?.trim().orEmpty().ifBlank { item.song.artist },
                    album = etAlbum.text?.toString()?.trim().orEmpty().ifBlank { item.song.album },
                    year = etYear.text?.toString()?.trim().orEmpty(),
                    genre = etGenre.text?.toString()?.trim().orEmpty()
                )
                items[position].proposedEdit = updatedEdit
                items[position].isSelected = true
                notifyItemChanged(position)
                onItemSelectionChanged()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun showManualSearchDialog(
        context: android.content.Context,
        item: JanitorUpdateItem,
        position: Int,
        onSelected: (CloudMetadata) -> Unit
    ) {
        val dialogView = LayoutInflater.from(context).inflate(R.layout.dialog_manual_cloud_search, null)
        val etQuery = dialogView.findViewById<com.google.android.material.textfield.TextInputEditText>(R.id.etManualSearchQuery)
        val btnSearch = dialogView.findViewById<com.google.android.material.button.MaterialButton>(R.id.btnRunManualSearch)
        val progress = dialogView.findViewById<android.widget.ProgressBar>(R.id.progressManualSearch)
        val rvResults = dialogView.findViewById<RecyclerView>(R.id.rvManualSearchResults)

        val initialQuery = "${item.proposedEdit.artist} ${item.proposedEdit.title}".trim().ifBlank { File(item.song.path).nameWithoutExtension }
        etQuery.setText(initialQuery)

        rvResults.layoutManager = LinearLayoutManager(context)

        var dialog: AlertDialog? = null

        fun doSearch() {
            val q = etQuery.text?.toString()?.trim().orEmpty()
            if (q.isBlank()) return
            progress.isVisible = true
            rvResults.isVisible = false

            (context as? androidx.lifecycle.LifecycleOwner)?.lifecycleScope?.launch(Dispatchers.IO) {
                val results = CloudMetadataResolver.searchMultiple(q, item.song.title, item.song.artist)
                withContext(Dispatchers.Main) {
                    progress.isVisible = false
                    rvResults.isVisible = true
                    if (results.isNotEmpty()) {
                        rvResults.adapter = CandidateResultsAdapter(results) { selectedCandidate ->
                            onSelected(selectedCandidate)
                            dialog?.dismiss()
                        }
                    } else {
                        Toast.makeText(context, "No online matches found", Toast.LENGTH_SHORT).show()
                    }
                }
            }
        }

        btnSearch.setOnClickListener { doSearch() }

        dialog = MaterialAlertDialogBuilder(context)
            .setView(dialogView)
            .setNegativeButton("Cancel", null)
            .create()

        dialog.show()
        doSearch()
    }

    override fun getItemCount(): Int = items.size
}

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
