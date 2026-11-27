package com.music.myusiq.ui

import android.annotation.SuppressLint
import android.content.ContentUris
import android.os.Build
import android.os.Bundle
import android.provider.MediaStore
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import android.widget.Toast
import androidx.core.view.isVisible
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.google.android.material.button.MaterialButton
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import kotlin.math.abs
import com.music.myusiq.domain.models.Song
import com.music.myusiq.ui.viewmodels.PlaybackViewModel
import com.music.myusiq.ui.components.JanitorReviewBottomSheet
import com.music.myusiq.utils.*
import com.music.myusiq.R

class LibraryJanitorFragment : Fragment(R.layout.fragment_library_janitor) {
    private val viewModel: PlaybackViewModel by activityViewModels()

    private lateinit var tvDuplicateStatus: TextView
    private lateinit var btnCleanDuplicates: MaterialButton
    private lateinit var cardAutoFix: View
    private lateinit var tvAutoFixStatus: TextView
    private lateinit var btnAutoFixAll: MaterialButton
    private lateinit var btnRescanAllTracks: MaterialButton
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
        btnRescanAllTracks = view.findViewById(R.id.btnRescanAllTracks)
        cardUndoFix = view.findViewById(R.id.cardUndoFix)
        btnUndoLastFix = view.findViewById(R.id.btnUndoLastFix)

        setupObservers()
        updateUndoCardVisibility()

        btnCleanDuplicates.setOnClickListener { showCleanDuplicatesDialog() }
        btnAutoFixAll.setOnClickListener { startAutoFix() }
        btnRescanAllTracks.setOnClickListener { startRescanAllTracks() }
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
                // A fresh background scan may already be persisted — open it
                // directly instead of re-scanning the whole library online.
                val cached = com.music.myusiq.utils.JanitorResultsCache.rebuildItems(
                    requireContext().applicationContext,
                    viewModel.songsLibrary.value
                )
                if (cached.isNotEmpty()) {
                    viewModel.showCachedJanitorResults(cached)
                    JanitorReviewBottomSheet().show(parentFragmentManager, JanitorReviewBottomSheet.TAG)
                } else {
                    startAutoFix()
                }
            }
        }
    }

    @OptIn(kotlinx.coroutines.FlowPreview::class)
    private fun setupObservers() {
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                // Debounced: rapid library/artwork emissions re-trigger the
                // whole duplicate scan; wait for things to settle first.
                launch {
                    viewModel.songsLibrary.debounce(600).collectLatest { performScan() }
                }
                launch {
                    viewModel.artworkRefreshSignatures.debounce(600).collectLatest { performScan() }
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
                        btnRescanAllTracks.isEnabled = !isScanning
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

    private fun startRescanAllTracks() {
        val allSongs = viewModel.songsLibrary.value
        if (allSongs.isEmpty()) {
            Toast.makeText(requireContext(), "No tracks found in library", Toast.LENGTH_SHORT).show()
            return
        }

        startAcousticAutoId()

        val appContext = requireContext().applicationContext
        val prefs = appContext.getSharedPreferences("app_prefs", android.content.Context.MODE_PRIVATE)
        prefs.edit().putBoolean("janitor_initial_scan_done", true).apply()

        showUnifiedJanitorDialog(allSongs)
    }

    private fun startAcousticAutoId() {
        context?.let { ctx ->
            val workRequest = androidx.work.OneTimeWorkRequestBuilder<com.music.myusiq.service.AudioMatcherWorker>().build()
            androidx.work.WorkManager.getInstance(ctx).enqueue(workRequest)
            Toast.makeText(ctx, "Acoustic Auto-ID & Metadata Healer started in background", Toast.LENGTH_SHORT).show()
        }
    }

    private fun startAutoFix() {
        val allSongs = viewModel.songsLibrary.value
        if (allSongs.isEmpty()) {
            Toast.makeText(requireContext(), "No tracks found in library", Toast.LENGTH_SHORT).show()
            return
        }

        val appContext = requireContext().applicationContext
        val prefs = appContext.getSharedPreferences("app_prefs", android.content.Context.MODE_PRIVATE)

        val candidates = if (allFixCandidates.isNotEmpty()) {
            allFixCandidates
        } else {
            allSongs.filter { song ->
                needsFixOrArtwork(appContext, song)
            }
        }

        if (candidates.isEmpty()) {
            Toast.makeText(requireContext(), "All tracks in your library are already up to date!", Toast.LENGTH_SHORT).show()
            return
        }

        prefs.edit().putBoolean("janitor_initial_scan_done", true).apply()
        showUnifiedJanitorDialog(candidates)
    }

    private fun showUnifiedJanitorDialog(tracksToScan: List<Song>) {
        viewModel.startJanitorScan(tracksToScan, requireContext())
        val bottomSheet = JanitorReviewBottomSheet()
        bottomSheet.show(parentFragmentManager, JanitorReviewBottomSheet.TAG)
    }

    private fun isMessyOrUnknownTrack(song: Song): Boolean {
        val title = song.title.trim().lowercase()
        val artist = song.artist.trim().lowercase()
        val album = song.album.trim().lowercase()

        val isUnknownArtist = artist.isBlank() || artist == "<unknown>" || artist == "unknown artist" || artist == "unknown"
        val isUnknownAlbum = album.isBlank() || album == "<unknown>" || album == "unknown album" || album == "unknown"
        val hasFileExtension = title.endsWith(".mp3") || title.endsWith(".flac") || title.endsWith(".m4a") || title.endsWith(".wav") || title.endsWith(".aac")
        val isGenericTitle = Regex("(?i)^(track|audio|sound|recording|song)[_\\s\\-]*\\d*$").matches(title) || title.isBlank()
        
        val hasClutter = title.contains("_") ||
                         Regex("""(?i)\b(www\.|https?://)?[a-z0-9\-]+\.(com|net|org|io|me|co|info|biz)\b""").containsMatchIn(title) ||
                         Regex("""(?i)[(\[{].*?(official|music\s+video|official\s+audio|lyric\s+video|hd\s+1080p|4k|visualizer|y2mate|ytmp3).*?[)\]}]""").containsMatchIn(title)

        return isUnknownArtist || isUnknownAlbum || hasFileExtension || isGenericTitle || hasClutter
    }

    private fun calculateSongQualityScore(song: Song): Long {
        var score = 0L
        val file = File(song.path)
        val ext = file.extension.lowercase()
        val fileSize = if (file.exists()) file.length() else 0L

        when (ext) {
            "flac", "alac", "wav" -> score += 10_000_000L
            "m4a", "aac" -> score += 5_000_000L
            "mp3" -> score += 2_000_000L
            else -> score += 1_000_000L
        }

        score += (fileSize / 1024L)

        if (!song.album.equals("<unknown>", ignoreCase = true) && song.album.isNotBlank()) score += 100_000L
        if (!song.artist.equals("<unknown>", ignoreCase = true) && song.artist.isNotBlank()) score += 100_000L

        return score
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
            val pathGroups = allSongs.groupBy { PlatformUtils.normalizePath(it.path).lowercase() }
                .values
                .filter { it.size > 1 }
                .map { group -> group.sortedByDescending { calculateSongQualityScore(it) } }

            val titleGroups = allSongs.groupBy { "${it.title.trim().lowercase()}|${it.artist.trim().lowercase()}" }
                .values
                .filter { it.size > 1 }
                .map { group ->
                    group.filter { s1 ->
                        group.any { s2 -> s1 != s2 && abs(s1.duration - s2.duration) < 2000 }
                    }
                }
                .filter { it.size > 1 }
                .map { group ->
                    group.sortedByDescending { calculateSongQualityScore(it) }
                }

            val groups = (pathGroups + titleGroups).distinctBy { group -> group.map { it.id }.sorted() }

            val prefs = appContext.getSharedPreferences("app_prefs", android.content.Context.MODE_PRIVATE)
            // A previous install's processed record survives data clears —
            // treat it as "the initial scan already happened".
            val initialScanDone = prefs.getBoolean("janitor_initial_scan_done", false) ||
                com.music.myusiq.utils.JanitorProcessedCache.hasRecords(appContext)

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
                    allFixCandidates.isEmpty() ->
                        "All metadata and artwork look clean. Use 'Rescan & Fix All Tracks' to perform a complete reset and scan."
                    toRepair.isNotEmpty() && missingArt.isNotEmpty() ->
                        "Found ${toRepair.size} tracks with messy metadata and ${missingArt.size} tracks with missing artwork. Tap to review."
                    toRepair.isNotEmpty() ->
                        "Found ${toRepair.size} tracks with messy metadata. Tap to review."
                    missingArt.isNotEmpty() ->
                        "Found ${missingArt.size} tracks with missing artwork. Tap to review."
                    else ->
                        "All metadata and artwork look clean. Use 'Rescan & Fix All Tracks' to perform a complete reset and scan."
                }
                tvAutoFixStatus.text = statusMessage
                btnAutoFixAll.isVisible = consolidatedCandidates.isNotEmpty()
                btnRescanAllTracks.isVisible = allSongs.isNotEmpty()
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
        val fileListText = duplicateGroups.flatMap { group ->
            group.drop(1).map { song ->
                val ext = File(song.path).extension.uppercase().ifBlank { "AUDIO" }
                val sizeMb = String.format(java.util.Locale.US, "%.1f MB", File(song.path).length() / (1024.0 * 1024.0))
                "• ${song.title} ($ext • $sizeMb)"
            }
        }.take(15).joinToString("\n") + if (totalToDelete > 15) "\n...and ${totalToDelete - 15} more" else ""

        MaterialAlertDialogBuilder(requireContext())
            .setTitle("Clean Duplicates")
            .setMessage("Myusiq identified $totalToDelete lower-quality redundant files to delete (keeping highest quality FLAC/higher bitrate versions):\n\n$fileListText")
            .setNegativeButton("Cancel", null)
            .setPositiveButton("Clean Up") { _, _ -> deleteDuplicates() }
            .show()
    }

    private fun deleteDuplicates() {
        val songsToDelete = duplicateGroups.flatMap { it.drop(1) }
        if (songsToDelete.isEmpty()) return

        // Removed tracks' mood profiles would linger as stale evidence
        viewModel.invalidateMoodProfilesFor(songsToDelete.map { it.id })

        viewLifecycleOwner.lifecycleScope.launch(Dispatchers.IO) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                try {
                    val uris = songsToDelete.map {
                        ContentUris.withAppendedId(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, it.id)
                    }
                    val intent = MediaStore.createDeleteRequest(requireContext().contentResolver, uris)
                    withContext(Dispatchers.Main) {
                        viewModel.requestDelete(intent, songsToDelete)
                    }
                } catch (e: Exception) {
                    android.util.Log.e("LibraryJanitorFragment", "Failed to create delete request for duplicates", e)
                    var deletedCount = 0
                    songsToDelete.forEach { if (File(it.path).delete()) deletedCount++ }
                    withContext(Dispatchers.Main) {
                        Toast.makeText(requireContext(), "Deleted $deletedCount files.", Toast.LENGTH_LONG).show()
                        viewModel.forceScanMediaLibrary()
                    }
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
