package com.music.rhythm.ui

import android.annotation.SuppressLint
import android.content.ContentUris
import android.os.Build
import android.os.Bundle
import android.provider.MediaStore
import android.util.Size
import android.view.View
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
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import kotlin.math.abs
import kotlin.time.Duration.Companion.milliseconds
import com.music.rhythm.domain.models.Song
import com.music.rhythm.ui.viewmodels.PlaybackViewModel
import com.music.rhythm.ui.viewmodels.PendingTrackEdit
import com.music.rhythm.utils.*
import com.music.rhythm.R

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
            .setMessage("Rhythm will search online for official artwork and tags for these ${missingArtSongs.size} tracks. This requires an internet connection.\n\nContinue?")
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
            val updates = mutableListOf<PendingTrackEdit>()
            
            missingArtSongs.forEachIndexed { index, song ->
                withContext(Dispatchers.Main) {
                    if (isAdded) {
                        tvArtStatus.text = "Searching cloud ${index + 1} of ${missingArtSongs.size}: ${song.title}"
                    }
                }
                
                val metadata = CloudMetadataResolver.resolve(song.title, song.artist)
                if (metadata != null) {
                    val artworkUri = if (metadata.artworkUrl != null) {
                        viewModel.downloadImageToCache(context, metadata.artworkUrl)
                    } else null

                    updates.add(PendingTrackEdit(
                        songId = song.id,
                        title = song.title,
                        artist = song.artist,
                        album = metadata.album ?: song.album,
                        year = metadata.year ?: "",
                        genre = metadata.genre ?: "",
                        artworkUri = artworkUri,
                        path = song.path
                    ))
                }
                delay(800.milliseconds)
            }

            withContext(Dispatchers.Main) {
                if (isAdded) {
                    if (updates.isNotEmpty()) {
                        viewModel.requestBulkMetadataUpdate(requireContext(), updates)
                        Toast.makeText(context, "Applying matches for ${updates.size} tracks...", Toast.LENGTH_LONG).show()
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
            .setMessage("Rhythm will attempt to identify ${unknownSongs.size} tracks with generic or missing titles/artists and fetch official info.\n\nContinue?")
            .setNegativeButton("Cancel", null)
            .setPositiveButton("Start") { _, _ ->
                btnRepairUnknown.isEnabled = false
                viewModel.repairUnknownMetadata(requireContext(), unknownSongs,
                    onProgress = { current, total, title ->
                        tvRepairStatus.text = getString(R.string.repair_status_format, current, total, title)
                    },
                    onComplete = { count ->
                        Toast.makeText(requireContext(), "Successfully repaired $count tracks!", Toast.LENGTH_LONG).show()
                        btnRepairUnknown.isEnabled = true
                        performScan()
                    }
                )
            }
            .show()
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
                    var hasArt = (viewModel.artworkRefreshSignatures.value[PlatformUtils.normalizePath(song.path)] ?: 0L) > 0
                    
                    if (!hasArt) {
                        try {
                            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                                contentResolver.loadThumbnail(trackUri, Size(100, 100), null)
                                hasArt = true
                            }
                        } catch (_: Exception) {
                            // Thumbnails can fail for many reasons, don't flood logs but note it
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
