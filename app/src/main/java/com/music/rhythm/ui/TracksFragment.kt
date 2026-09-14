package com.music.rhythm.ui

import android.content.Context
import android.graphics.Color
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.animation.AnimationUtils
import android.view.inputmethod.InputMethodManager
import android.widget.FrameLayout
import android.widget.PopupWindow
import android.widget.TextView
import android.widget.Toast
import androidx.core.graphics.drawable.toDrawable
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import androidx.swiperefreshlayout.widget.SwipeRefreshLayout
import com.google.android.material.button.MaterialButton
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.floatingactionbutton.ExtendedFloatingActionButton
import kotlin.time.Duration.Companion.milliseconds
import com.google.android.material.textfield.TextInputEditText
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import com.music.rhythm.domain.models.Song
import com.music.rhythm.domain.models.Playlist
import com.music.rhythm.R
import com.music.rhythm.ui.adapters.TrackAdapter
import com.music.rhythm.ui.adapters.TrackUiModel
import com.music.rhythm.ui.viewmodels.PlaybackViewModel
import com.music.rhythm.ui.viewmodels.PendingTrackEdit
import com.music.rhythm.ui.components.TrackInfoBottomSheet
import com.music.rhythm.utils.*
import com.music.rhythm.ui.*
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow

class TracksFragment : Fragment() {
    private val viewModel: PlaybackViewModel by activityViewModels()
    private var trackAdapter: TrackAdapter? = null
    private var rvTracks: RecyclerView? = null
    private var etSearch: TextInputEditText? = null
    private var layoutEmptyState: View? = null
    private var btnEmptyAction: View? = null
    private var swipeRefresh: SwipeRefreshLayout? = null
    private var tvHeaderTitle: TextView? = null
    private var tvHeaderSubtitle: TextView? = null
    private var btnSort: MaterialButton? = null
    private var btnBatchEdit: MaterialButton? = null
    private var fabShuffleAll: ExtendedFloatingActionButton? = null
    private var fabGoToTop: View? = null
    private var layoutShimmer: View? = null
    private var btnBack: View? = null
    private var isSelectionMode = false
    private val selectedSongs = MutableStateFlow<Set<Long>>(emptySet())

    private var lastSortOrder: PlaybackViewModel.TrackSortOrder? = null
    private var lastSortDirection: PlaybackViewModel.SortDirection? = null

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View? {
        return inflater.inflate(R.layout.fragment_tracks, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        rvTracks = view.findViewById(R.id.rvTracks)
        etSearch = view.findViewById(R.id.etSearch)
        layoutEmptyState = view.findViewById(R.id.layoutEmptyState)
        btnEmptyAction = view.findViewById(R.id.btnEmptyAction)
        
        swipeRefresh = view.findViewById(R.id.swipeRefreshTracks)
        val header = view.findViewById<View>(R.id.tracksHeader)
        tvHeaderTitle = header.findViewById(R.id.tvHeaderTitle)
        tvHeaderSubtitle = header.findViewById(R.id.tvHeaderSubtitle)
        btnBack = header.findViewById(R.id.btnBack)
        
        val folderPath = arguments?.getString("folder_path")
        val folderName = arguments?.getString("folder_name")
        
        if (folderPath != null) {
            tvHeaderTitle?.text = folderName ?: "Folder"
            btnBack?.visibility = View.VISIBLE
            btnBack?.setOnClickListener { parentFragmentManager.popBackStack() }
        } else {
            tvHeaderTitle?.text = getString(R.string.nav_tracks)
            btnBack?.visibility = View.GONE
        }
        
        btnSort = view.findViewById(R.id.btnSort)
        btnBatchEdit = view.findViewById(R.id.btnBatchEdit)
        fabShuffleAll = view.findViewById(R.id.fabShuffleAll)
        fabGoToTop = view.findViewById(R.id.fabGoToTop)
        layoutShimmer = view.findViewById(R.id.layoutShimmer)

        setupRecyclerView()
        setupObservers()
        setupSearch()

        swipeRefresh?.setOnRefreshListener { viewModel.forceScanMediaLibrary() }
        btnEmptyAction?.setOnClickListener { viewModel.forceScanMediaLibrary() }
        fabShuffleAll?.setOnClickListener { viewModel.shuffleAllTracks(viewModel.songsLibrary.value) }
        
        fabGoToTop?.setOnClickListener {
            rvTracks?.smoothScrollToPosition(0)
        }
        
        btnBatchEdit?.setOnClickListener { toggleSelectionMode() }

        // Sorting dialog logic
        btnSort?.setOnClickListener { showSortPopup() }
    }

    private fun toggleSelectionMode() {
        if (isSelectionMode) {
            showBatchActionsDialog()
            return
        }
        isSelectionMode = true
        btnBatchEdit?.visibility = View.VISIBLE
    }

    private fun showBatchActionsDialog() {
        val options = arrayOf("Edit Metadata", "Add to Playlist", "Delete from Device", "Clear Selection")
        MaterialAlertDialogBuilder(requireContext())
            .setTitle("Batch Actions (${selectedSongs.value.size} items)")
            .setItems(options) { _, which ->
                when (which) {
                    0 -> showBatchEditDialog()
                    1 -> showBatchAddToPlaylistDialog()
                    2 -> showBatchDeleteDialog()
                    3 -> {
                        isSelectionMode = false
                        selectedSongs.value = emptySet()
                        btnBatchEdit?.visibility = View.GONE
                    }
                }
            }
            .show()
    }

    private fun showBatchEditDialog() {
        val dialogView = LayoutInflater.from(requireContext()).inflate(R.layout.dialog_batch_edit, null)
        val etArtist = dialogView.findViewById<TextInputEditText>(R.id.etBatchArtist)
        val etAlbum = dialogView.findViewById<TextInputEditText>(R.id.etBatchAlbum)
        val etGenre = dialogView.findViewById<TextInputEditText>(R.id.etBatchGenre)

        MaterialAlertDialogBuilder(requireContext())
            .setTitle("Batch Edit (${selectedSongs.value.size} tracks)")
            .setView(dialogView)
            .setPositiveButton("Save") { _, _ ->
                val artist = etArtist.text?.toString().orEmpty()
                val album = etAlbum.text?.toString().orEmpty()
                val genre = etGenre.text?.toString().orEmpty()

                val updates = viewModel.songsLibrary.value.asSequence()
                    .filter { selectedSongs.value.contains(it.id) }
                    .map { song ->
                        PendingTrackEdit(
                            songId = song.id,
                            title = song.title,
                            artist = artist.ifBlank { song.artist },
                            album = album.ifBlank { song.album },
                            genre = genre,
                            path = song.path
                        )
                    }
                    .toList()
                viewModel.requestBulkMetadataUpdate(requireContext(), updates)
                toggleSelectionMode()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun showBatchAddToPlaylistDialog() {
        viewLifecycleOwner.lifecycleScope.launch {
            val playlists = viewModel.allPlaylists.first()
            val names = playlists.map { it.name }.toTypedArray()
            MaterialAlertDialogBuilder(requireContext())
                .setTitle("Add to Playlist")
                .setItems(names) { _, which ->
                    selectedSongs.value.forEach { id ->
                        viewModel.addTrackToPlaylist(id.toString(), playlists[which].id)
                    }
                    Toast.makeText(requireContext(), "Added ${selectedSongs.value.size} tracks to ${playlists[which].name}", Toast.LENGTH_SHORT).show()
                    toggleSelectionMode()
                }
                .setNegativeButton("Cancel", null)
                .show()
        }
    }

    private fun showBatchDeleteDialog() {
        MaterialAlertDialogBuilder(requireContext())
            .setTitle("Delete tracks?")
            .setMessage("Are you sure you want to delete ${selectedSongs.value.size} tracks from your device? This cannot be undone.")
            .setNegativeButton("Cancel", null)
            .setPositiveButton("Delete") { _, _ ->
                val songsToDelete = viewModel.songsLibrary.value.filter { selectedSongs.value.contains(it.id) }
                songsToDelete.forEach { viewModel.deleteTrackFromStorage(requireContext(), it) }
                isSelectionMode = false
                selectedSongs.value = emptySet()
            }
            .show()
    }

    private fun setupRecyclerView() {
        trackAdapter = TrackAdapter(
            onTrackClicked = { song, imageView -> 
                if (isSelectionMode) {
                    toggleSongSelection(song.id)
                } else {
                    val list = trackAdapter?.currentList?.map { it.song }.orEmpty()
                    val index = list.indexOfFirst { it.id == song.id }
                    if (index != -1) {
                        (activity as? MainActivity)?.playFromView(list, index, imageView)
                    }
                }
            },
            onTrackLongClicked = { song ->
                if (!isSelectionMode) {
                    isSelectionMode = true
                    btnBatchEdit?.visibility = View.VISIBLE
                    btnBatchEdit?.setIconResource(R.drawable.ic_edit)
                }
                toggleSongSelection(song.id)
            },
            onOptionSelected = { song, id -> 
                when (id) {
                    R.id.action_play_next -> viewModel.insertIntoQueueNext(song)
                    R.id.action_add_to_queue -> viewModel.appendToQueue(song)
                    R.id.action_start_radio -> viewModel.playMoreLikeThis(song)
                    R.id.action_add_to_playlist -> showAddToPlaylistDialog(song)
                    R.id.action_make_ringtone -> viewModel.setAsRingtone(requireContext(), song)
                    R.id.action_edit_tags -> showTrackInfo(song, startEdit = true)
                    R.id.action_track_info -> showTrackInfo(song, startEdit = false)
                    R.id.action_trim_audio -> navigateToTrimmer(song)
                    R.id.action_delete -> viewModel.deleteTrackFromStorage(requireContext(), song)
                }
            },
            menuCustomizer = { _, menu ->
                menu.findItem(R.id.action_remove_from_playlist)?.isVisible = false
            }
        )

        rvTracks?.apply {
            adapter = trackAdapter
            layoutManager = LinearLayoutManager(requireContext())
            layoutAnimation = AnimationUtils.loadLayoutAnimation(context, R.anim.layout_animation_fall_down)
            
            addOnScrollListener(object : RecyclerView.OnScrollListener() {
                
                override fun onScrolled(recyclerView: RecyclerView, dx: Int, dy: Int) {
                    super.onScrolled(recyclerView, dx, dy)
                    val firstVisible = (layoutManager as? LinearLayoutManager)?.findFirstVisibleItemPosition() ?: 0
                    if (firstVisible > 0) {
                        if (fabShuffleAll?.visibility == View.VISIBLE) fabShuffleAll?.hide()
                        if (fabGoToTop?.visibility != View.VISIBLE) fabGoToTop?.visibility = View.VISIBLE
                    } else {
                        if (fabShuffleAll?.visibility != View.VISIBLE) fabShuffleAll?.show()
                        if (fabGoToTop?.visibility == View.VISIBLE) fabGoToTop?.visibility = View.GONE
                    }
                }
            })
        }
    }

    private fun navigateToTrimmer(song: Song) {
        val fragment = AudioTrimmerFragment().apply {
            arguments = Bundle().apply {
                putLong("songId", song.id)
            }
        }
        parentFragmentManager.beginTransaction()
            .setCustomAnimations(R.anim.slide_in_right, R.anim.slide_out_left, R.anim.slide_in_left, R.anim.slide_out_right)
            .add(R.id.navHostFragment, fragment)
            .hide(this)
            .addToBackStack(null)
            .commit()
    }

    private fun toggleSongSelection(id: Long) {
        val current = selectedSongs.value.toMutableSet()
        if (current.contains(id)) current.remove(id)
        else current.add(id)
        selectedSongs.value = current
        
        if (current.isEmpty()) {
            isSelectionMode = false
            btnBatchEdit?.visibility = View.GONE
        } else {
            btnBatchEdit?.visibility = View.VISIBLE
        }
    }

    private fun showTrackInfo(song: Song, startEdit: Boolean = false) {
        TrackInfoBottomSheet.newInstance(song.id, startEdit).show(childFragmentManager, "track_info")
    }

    private fun showAddToPlaylistDialog(song: Song) {
        viewLifecycleOwner.lifecycleScope.launch {
            val playlists = viewModel.allPlaylists.first()
            val names = mutableListOf<String>()
            names.add("+ Create New Playlist")
            names.addAll(playlists.map { it.name })
            
            MaterialAlertDialogBuilder(requireContext())
                .setTitle("Add to Playlist")
                .setItems(names.toTypedArray()) { _, which ->
                    if (which == 0) {
                        showCreatePlaylistDialog(song)
                    } else {
                        viewModel.addTrackToPlaylist(song.id.toString(), playlists[which - 1].id)
                        Toast.makeText(requireContext(), "Added to ${playlists[which - 1].name}", Toast.LENGTH_SHORT).show()
                    }
                }
                .setNegativeButton("Cancel", null)
                .show()
        }
    }

    private fun showCreatePlaylistDialog(song: Song? = null) {
        val et = TextInputEditText(requireContext())
        val layout = FrameLayout(requireContext())
        layout.setPadding(45, 20, 45, 0)
        layout.addView(et)
        et.hint = "Playlist Name"
        
        MaterialAlertDialogBuilder(requireContext())
            .setTitle("New Playlist")
            .setView(layout)
            .setPositiveButton("Create") { _, _ ->
                val name = et.text?.toString().orEmpty()
                if (name.isNotBlank()) {
                    viewModel.createNewPlaylist(name)
                    if (song != null) {
                        viewLifecycleOwner.lifecycleScope.launch {
                            // Wait a bit for the playlist to be created and observed
                            delay(500.milliseconds)
                            val p = viewModel.allPlaylists.first().find { it.name == name }
                            p?.let { viewModel.addTrackToPlaylist(song.id.toString(), it.id) }
                        }
                    }
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun setupObservers() {
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch {
                    combine(
                        viewModel.songsLibrary,
                        viewModel.currentPlayingSong,
                        viewModel.isPlaying,
                        viewModel.favoriteTrackIds,
                        viewModel.artworkRefreshSignatures,
                        selectedSongs,
                        viewModel.currentPositionThrottled
                    ) { args: Array<Any?> ->
                        @Suppress("UNCHECKED_CAST")
                        val allSongs = args[0] as? List<Song> ?: emptyList()
                        val currentSong = args[1] as? Song
                        val isPlaying = args[2] as? Boolean ?: false
                        val favorites = args[3] as? Set<String> ?: emptySet()
                        val signatures = args[4] as? Map<String, Long> ?: emptyMap()
                        val selected = args[5] as? Set<Long> ?: emptySet()
                        val position = args[6] as? Int ?: 0

                        val folderPath = arguments?.getString("folder_path")
                        val songs = if (folderPath != null) {
                            allSongs.filter { it.path.startsWith(folderPath, ignoreCase = true) }
                        } else {
                            allSongs
                        }

                        songs.map { song ->
                            val normPath = PlatformUtils.normalizePath(song.path)
                            val isActive = song.id == currentSong?.id
                            TrackUiModel(
                                song = song,
                                isFavorite = favorites.contains(song.id.toString()),
                                isActive = isActive,
                                isPlaying = isActive && isPlaying,
                                isSelected = selected.contains(song.id),
                                progress = if (isActive && (song.duration > 0)) position.toFloat() / song.duration.toFloat() else 0f,
                                artworkSignature = signatures[normPath] ?: 0L
                            )
                        }
                    }.collectLatest { uiModels ->
                        val currentOrder = viewModel.currentSortOrder
                        val currentDir = viewModel.currentSortDirection
                        val hasSortChanged = lastSortOrder != currentOrder || lastSortDirection != currentDir
                        
                        trackAdapter?.submitList(uiModels) {
                            if (hasSortChanged && lastSortOrder != null) {
                                rvTracks?.scrollToPosition(0)
                            }
                        }
                        
                        lastSortOrder = currentOrder
                        lastSortDirection = currentDir
                        tvHeaderSubtitle?.text = if (isSelectionMode) "${selectedSongs.value.size} Selected" else "${uiModels.size} Tracks"
                        
                        // Update empty state based on filtered list
                        if (!viewModel.isLibraryLoading.value) {
                            layoutEmptyState?.visibility = if (uiModels.isEmpty()) View.VISIBLE else View.GONE
                        }
                    }
                }

                launch { viewModel.isLibraryLoading.collectLatest { isLoading -> 
                    swipeRefresh?.isRefreshing = isLoading
                    layoutShimmer?.visibility = if (isLoading) View.VISIBLE else View.GONE
                    rvTracks?.visibility = if (isLoading) View.GONE else View.VISIBLE
                    btnBatchEdit?.visibility = if (isLoading || !isSelectionMode) View.GONE else View.VISIBLE
                } }

                launch { viewModel.recentSearches.collectLatest { _ ->
                    // Handle search history in rvSearchHistory if needed
                } }
            }
        }
    }


    private fun setupSearch() {
        etSearch?.hint = getString(R.string.search)
        etSearch?.setOnFocusChangeListener { _, hasFocus ->
            if (hasFocus) {
                etSearch?.hint = null
            } else {
                if (etSearch?.text.isNullOrEmpty()) {
                    etSearch?.hint = getString(R.string.search)
                }
            }
        }
        
        etSearch?.setOnClickListener {
            etSearch?.hint = null
        }

        etSearch?.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                viewModel.applyFilterAndSearch(s.toString())
            }
            override fun afterTextChanged(s: Editable?) {}
        })

        etSearch?.setOnEditorActionListener { v, _, _ ->
            val query = v.text.toString()
            if (query.isNotBlank()) {
                viewModel.saveSearchQuery(query)
                // Hide keyboard on search action
                val imm = requireContext().getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
                imm.hideSoftInputFromWindow(v.windowToken, 0)
            }
            false
        }
    }

    private fun showSortPopup() {
        val popupView = LayoutInflater.from(requireContext()).inflate(R.layout.layout_sort_popup, btnSort?.parent as? ViewGroup, false)
        val width = (130 * resources.displayMetrics.density).toInt()
        val popupWindow = PopupWindow(
            popupView,
            width,
            ViewGroup.LayoutParams.WRAP_CONTENT,
            true
        ).apply {
            setBackgroundDrawable(Color.TRANSPARENT.toDrawable())
            elevation = 10f
            overlapAnchor = false
        }

        var direction = viewModel.currentSortDirection

        val btnAsc = popupView.findViewById<View>(R.id.btnGlobalAsc)
        val btnDesc = popupView.findViewById<View>(R.id.btnGlobalDesc)

        fun updateDirectionUI() {
            btnAsc.alpha = if (direction == PlaybackViewModel.SortDirection.ASCENDING) 1.0f else 0.4f
            btnDesc.alpha = if (direction == PlaybackViewModel.SortDirection.DESCENDING) 1.0f else 0.4f
        }

        updateDirectionUI()

        btnAsc.setOnClickListener {
            direction = PlaybackViewModel.SortDirection.ASCENDING
            updateDirectionUI()
            viewModel.sortTracks(viewModel.currentSortOrder, direction)
        }

        btnDesc.setOnClickListener {
            direction = PlaybackViewModel.SortDirection.DESCENDING
            updateDirectionUI()
            viewModel.sortTracks(viewModel.currentSortOrder, direction)
        }

        popupView.findViewById<View>(R.id.btnSortName).setOnClickListener {
            viewModel.sortTracks(PlaybackViewModel.TrackSortOrder.ALPHABETICAL, direction)
            popupWindow.dismiss()
        }
        popupView.findViewById<View>(R.id.btnSortDate).setOnClickListener {
            viewModel.sortTracks(PlaybackViewModel.TrackSortOrder.LAST_ADDED, direction)
            popupWindow.dismiss()
        }
        popupView.findViewById<View>(R.id.btnSortSize).setOnClickListener {
            viewModel.sortTracks(PlaybackViewModel.TrackSortOrder.SIZE, direction)
            popupWindow.dismiss()
        }
        popupView.findViewById<View>(R.id.btnSortPopular).setOnClickListener {
            viewModel.sortTracks(PlaybackViewModel.TrackSortOrder.MOST_POPULAR, direction)
            popupWindow.dismiss()
        }
        popupView.findViewById<View>(R.id.btnSortArtist).setOnClickListener {
            viewModel.sortTracks(PlaybackViewModel.TrackSortOrder.ARTIST, direction)
            popupWindow.dismiss()
        }
        popupView.findViewById<View>(R.id.btnSortAlbum).setOnClickListener {
            viewModel.sortTracks(PlaybackViewModel.TrackSortOrder.ALBUM, direction)
            popupWindow.dismiss()
        }
        popupView.findViewById<View>(R.id.btnSortRecentlyPlayed).setOnClickListener {
            viewModel.sortTracks(PlaybackViewModel.TrackSortOrder.RECENTLY_PLAYED, direction)
            popupWindow.dismiss()
        }

        popupWindow.elevation = 10f
        popupWindow.overlapAnchor = false
        popupWindow.showAsDropDown(btnSort!!, 0, 10, Gravity.END)
    }

    override fun onDestroyView() {
        rvTracks = null
        etSearch = null
        layoutEmptyState = null
        btnEmptyAction = null
        swipeRefresh = null
        tvHeaderTitle = null
        tvHeaderSubtitle = null
        btnSort = null
        btnBatchEdit = null
        fabShuffleAll = null
        fabGoToTop = null
        layoutShimmer = null
        trackAdapter = null
        super.onDestroyView()
    }
}
