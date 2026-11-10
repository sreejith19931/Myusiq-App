package com.music.myusiq.ui.components

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.core.view.isVisible
import androidx.fragment.app.FragmentActivity
import androidx.fragment.app.activityViewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.bumptech.glide.Glide
import com.google.android.material.bottomsheet.BottomSheetBehavior
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.google.android.material.bottomsheet.BottomSheetDialogFragment
import com.google.android.material.button.MaterialButton
import com.google.android.material.checkbox.MaterialCheckBox
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import com.music.myusiq.R
import com.music.myusiq.domain.models.JanitorUpdateItem
import com.music.myusiq.ui.viewmodels.PlaybackViewModel
import com.music.myusiq.utils.CloudMetadata
import com.music.myusiq.utils.CloudMetadataResolver
import java.io.File
import kotlin.time.Duration.Companion.milliseconds

class JanitorReviewBottomSheet : BottomSheetDialogFragment() {

    private val viewModel: PlaybackViewModel by activityViewModels()

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View? {
        return inflater.inflate(R.layout.dialog_janitor_review, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        val tvTitle = view.findViewById<TextView>(R.id.tvJanitorDialogTitle)
        val tvSub = view.findViewById<TextView>(R.id.tvJanitorDialogSub)
        val layoutProgress = view.findViewById<View>(R.id.layoutJanitorProgress)
        val tvProgressMsg = view.findViewById<TextView>(R.id.tvJanitorProgressMessage)
        val pulseLoadingView = view.findViewById<PulseLoadingView>(R.id.pulseLoadingView)
        val animatedLoadingBars = view.findViewById<AnimatedLoadingBars>(R.id.animatedLoadingBars)
        val writingLoadingView = view.findViewById<WritingLoadingView>(R.id.writingLoadingView)
        val progressJanitorCircular = view.findViewById<com.google.android.material.progressindicator.CircularProgressIndicator>(R.id.progressJanitorCircular)
        val progressJanitor = view.findViewById<com.google.android.material.progressindicator.LinearProgressIndicator>(R.id.progressJanitor)
        val layoutSelectAll = view.findViewById<View>(R.id.layoutSelectAllContainer)
        val frameContainer = view.findViewById<View>(R.id.frameReviewContainer)
        val btnBulkSearch = view.findViewById<MaterialButton>(R.id.btnBulkSearchOnline)
        val cbSelectAll = view.findViewById<MaterialCheckBox>(R.id.cbSelectAll)
        val rvReview = view.findViewById<RecyclerView>(R.id.rvJanitorReview)
        val btnCancel = view.findViewById<MaterialButton>(R.id.btnCancelReview)
        val btnApply = view.findViewById<MaterialButton>(R.id.btnApplySelected)

        fun showShapeLoading(type: JanitorLoadingType, message: String) {
            layoutProgress.isVisible = true
            pulseLoadingView?.isVisible = type == JanitorLoadingType.PULSE
            animatedLoadingBars?.isVisible = type == JanitorLoadingType.BARS
            writingLoadingView?.isVisible = type == JanitorLoadingType.WRITING
            progressJanitorCircular?.isVisible = type == JanitorLoadingType.CIRCULAR
            progressJanitor?.isVisible = false
            tvProgressMsg?.text = message
        }

        fun showLinearLoading(current: Int, total: Int, message: String) {
            layoutProgress.isVisible = true
            pulseLoadingView?.isVisible = false
            animatedLoadingBars?.isVisible = false
            writingLoadingView?.isVisible = false
            progressJanitorCircular?.isVisible = false
            progressJanitor?.isVisible = true
            progressJanitor?.max = total
            progressJanitor?.progress = current
            tvProgressMsg?.text = message
        }

        tvTitle.text = "Scanning & Analyzing Library"
        tvSub.text = "Analyzing audio waveforms and searching local/online metadata..."
        showShapeLoading(JanitorLoadingType.PULSE, "Preparing library scan...")

        layoutSelectAll.isVisible = false
        frameContainer.isVisible = false
        btnBulkSearch.isVisible = false
        btnApply.isVisible = false
        btnCancel.isVisible = true
        btnCancel.text = getString(R.string.cancel)

        var candidateItems: List<JanitorUpdateItem> = emptyList()

        fun updateApplyButtonText() {
            val selectedCount = candidateItems.count { it.isSelected }
            btnApply.text = "Apply Selected ($selectedCount)"
            btnApply.isEnabled = selectedCount > 0
        }

        btnCancel.setOnClickListener {
            viewModel.cancelJanitorScan()
            viewModel.cancelBulkUpdate()
            viewModel.clearJanitorScanState()
            dismiss()
        }

        btnApply.setOnClickListener {
            val selectedEdits = candidateItems.filter { it.isSelected }.map { it.proposedEdit }
            if (selectedEdits.isNotEmpty()) {
                tvTitle.text = "Applying Metadata Changes"
                tvSub.text = "Writing ID3/FLAC tags and updating MediaStore..."
                showShapeLoading(JanitorLoadingType.WRITING, "Preparing metadata updates...")

                layoutSelectAll.isVisible = false
                frameContainer.isVisible = false
                btnBulkSearch.isVisible = false
                btnApply.isVisible = false
                btnCancel.isEnabled = true
                btnCancel.text = "Stop"

                var isCancelledByUser = false

                btnCancel.setOnClickListener {
                    isCancelledByUser = true
                    viewModel.cancelBulkUpdate()
                    Toast.makeText(requireContext(), "Stopped. Updated tracks up to this point.", Toast.LENGTH_LONG).show()
                    viewModel.clearJanitorScanState()
                    dismiss()
                }

                viewModel.requestBulkMetadataUpdate(requireContext(), selectedEdits)

                val progressJob = viewLifecycleOwner.lifecycleScope.launch {
                    viewModel.bulkUpdateProgress.collectLatest { (current, total) ->
                        if (total > 0 && current <= total && !isCancelledByUser) {
                            val title = viewModel.bulkUpdateCurrentTitle.value
                            if (current == 0) {
                                showShapeLoading(JanitorLoadingType.WRITING, "Requesting write permissions...")
                            } else {
                                showLinearLoading(current, total, "Updating $current of $total: $title")
                            }
                        }
                    }
                }

                val permissionJob = viewLifecycleOwner.lifecycleScope.launch {
                    viewModel.pendingWriteIntent.collectLatest { intent ->
                        if (intent != null && !isCancelledByUser) {
                            tvTitle.text = "Permission Required"
                            tvSub.text = "Please grant write permission in the system prompt to save metadata changes..."
                            showShapeLoading(JanitorLoadingType.WRITING, "Awaiting write permission in system prompt...")
                        } else if (!isCancelledByUser && viewModel.isBulkUpdating.value) {
                            tvTitle.text = "Applying Metadata Changes"
                            tvSub.text = "Writing ID3/FLAC tags and updating MediaStore..."
                        }
                    }
                }

                viewLifecycleOwner.lifecycleScope.launch {
                    while (!viewModel.isBulkUpdating.value && viewModel.pendingWriteIntent.value == null && !isCancelledByUser) {
                        kotlinx.coroutines.delay(50.milliseconds)
                    }
                    while ((viewModel.isBulkUpdating.value || viewModel.pendingWriteIntent.value != null) && !isCancelledByUser) {
                        kotlinx.coroutines.delay(100.milliseconds)
                    }
                    progressJob.cancel()
                    permissionJob.cancel()

                    if (!isCancelledByUser) {
                        val appliedCount = viewModel.bulkUpdateProgress.value.first.let { if (it > 0) it else selectedEdits.size }
                        tvTitle.text = "Updates Applied Successfully!"
                        tvSub.text = "Successfully updated $appliedCount tracks in your music library."
                        layoutProgress.isVisible = false
                        btnCancel.text = "Done"
                        btnCancel.setOnClickListener {
                            viewModel.clearJanitorScanState()
                            dismiss()
                        }
                    } else {
                        viewModel.clearJanitorScanState()
                        dismiss()
                    }
                }
            } else {
                dismiss()
            }
        }

        btnBulkSearch.setOnClickListener {
            val unmatched = candidateItems.filter { it.proposedEdit.matchConfidence == 0 || it.proposedEdit.artworkUri == null }
            if (unmatched.isEmpty()) {
                Toast.makeText(requireContext(), "All tracks already have artwork and metadata assigned!", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }

            btnBulkSearch.isEnabled = false
            btnBulkSearch.text = "Searching online..."
            showShapeLoading(JanitorLoadingType.BARS, "Searching online databases for missing artwork & track tags...")

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
                        layoutProgress.isVisible = false
                        btnBulkSearch.isEnabled = true
                        btnBulkSearch.text = "Search All Messy Tracks Online"
                        rvReview.adapter?.notifyDataSetChanged()
                        updateApplyButtonText()
                        Toast.makeText(requireContext(), "Found online matches for $foundCount tracks!", Toast.LENGTH_SHORT).show()
                    }
                }
            }
        }

        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.janitorScanState.collectLatest { state ->
                    when (state) {
                        is com.music.myusiq.domain.usecase.ScanState.Progress -> {
                            showLinearLoading(state.percentage, 100, state.message)
                        }
                        is com.music.myusiq.domain.usecase.ScanState.ScanCompleted -> {
                            candidateItems = state.results

                            if (candidateItems.isNotEmpty()) {
                                tvTitle.text = "Review Proposed Updates"
                                val matchedCount = candidateItems.count { it.proposedEdit.matchConfidence > 0 }
                                val unmatchedCount = candidateItems.size - matchedCount
                                tvSub.text = when {
                                    matchedCount > 0 && unmatchedCount > 0 -> "Found $matchedCount automatic fixes and $unmatchedCount tracks requiring manual search."
                                    matchedCount > 0 -> "Found $matchedCount suggested fixes. Choose which tracks to update."
                                    else -> "Found ${candidateItems.size} tracks needing metadata/artwork."
                                }

                                layoutProgress.isVisible = false
                                layoutSelectAll.isVisible = true
                                frameContainer.isVisible = true
                                btnBulkSearch.isVisible = true
                                btnApply.isVisible = true

                                val adapter = JanitorReviewAdapter(candidateItems) {
                                    updateApplyButtonText()
                                    cbSelectAll.setOnCheckedChangeListener(null)
                                    cbSelectAll.isChecked = candidateItems.all { it.isSelected }
                                    cbSelectAll.setOnCheckedChangeListener { _, isChecked ->
                                        candidateItems.forEach { it.isSelected = isChecked }
                                        rvReview.adapter?.notifyDataSetChanged()
                                        updateApplyButtonText()
                                    }
                                }
                                rvReview.layoutManager = LinearLayoutManager(requireContext())
                                rvReview.adapter = adapter

                                cbSelectAll.isChecked = candidateItems.all { it.isSelected }
                                cbSelectAll.setOnCheckedChangeListener { _, isChecked ->
                                    candidateItems.forEach { it.isSelected = isChecked }
                                    rvReview.adapter?.notifyDataSetChanged()
                                    updateApplyButtonText()
                                }

                                updateApplyButtonText()
                            } else {
                                tvTitle.text = "No Updates Needed"
                                tvSub.text = "All analyzed tracks are already pristine."
                                layoutProgress.isVisible = false
                            }
                        }
                        null -> {}
                    }
                }
            }
        }
    }

    override fun onStart() {
        super.onStart()
        val dialog = dialog as? BottomSheetDialog
        dialog?.behavior?.state = BottomSheetBehavior.STATE_EXPANDED
        dialog?.behavior?.skipCollapsed = true
        val sheet = dialog?.findViewById<View>(com.google.android.material.R.id.design_bottom_sheet)
        sheet?.layoutParams?.height = ViewGroup.LayoutParams.MATCH_PARENT
    }

    private enum class JanitorLoadingType { PULSE, BARS, WRITING, CIRCULAR }

    companion object {
        const val TAG = "JanitorReviewBottomSheet"
        fun newInstance() = JanitorReviewBottomSheet()
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
                val activity = context as? FragmentActivity
                if (activity != null) {
                    activity.lifecycleScope.launch {
                        val vm = ViewModelProvider(activity)[PlaybackViewModel::class.java]
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
                        val activity = context as? FragmentActivity
                        if (activity != null) {
                            activity.lifecycleScope.launch {
                                val vm = ViewModelProvider(activity)[PlaybackViewModel::class.java]
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
        val btnSearch = dialogView.findViewById<MaterialButton>(R.id.btnRunManualSearch)
        val progress = dialogView.findViewById<android.widget.ProgressBar>(R.id.progressManualSearch)
        val rvResults = dialogView.findViewById<RecyclerView>(R.id.rvManualSearchResults)

        val initialQuery = "${item.proposedEdit.artist} ${item.proposedEdit.title}".trim().ifBlank { File(item.song.path).nameWithoutExtension }
        etQuery.setText(initialQuery)

        rvResults.layoutManager = LinearLayoutManager(context)

        var dialog: AlertDialog? = null

        val preloadedCandidates = item.suggestedMatches.map { match ->
            CloudMetadata(
                album = match.suggestedAlbum,
                year = match.suggestedYear,
                genre = match.suggestedGenre,
                artworkUrl = match.suggestedArtUri,
                foundArtist = match.suggestedArtist,
                foundTitle = match.suggestedTitle,
                matchConfidence = match.confidenceScore.toDouble()
            )
        }

        if (preloadedCandidates.isNotEmpty()) {
            rvResults.adapter = CandidateResultsAdapter(preloadedCandidates) { selectedCandidate ->
                onSelected(selectedCandidate)
                dialog?.dismiss()
            }
        }

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
