package com.music.rhythm.ui

import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.animation.AnimationUtils
import android.widget.ImageView
import android.widget.PopupWindow
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.core.graphics.drawable.toDrawable
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.button.MaterialButton
import com.google.android.material.search.SearchBar
import com.google.android.material.search.SearchView
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import com.music.rhythm.domain.models.Song
import com.music.rhythm.R
import com.music.rhythm.ui.adapters.AlbumsAdapter
import com.music.rhythm.ui.viewmodels.PlaybackViewModel
import com.music.rhythm.utils.*

data class AlbumUIModel(
    val name: String,
    val artist: String,
    val tracksCount: Int,
    val firstTrack: Song?,
    val dateAdded: Long = 0L,
)

class AlbumsFragment : Fragment() {
    private lateinit var recyclerView: RecyclerView
    private lateinit var albumsAdapter: AlbumsAdapter
    private lateinit var searchAlbumsAdapter: AlbumsAdapter
    private lateinit var tvHeaderSubtitle: TextView
    private var searchBar: SearchBar? = null
    private var searchView: SearchView? = null
    private var rvSearchResults: RecyclerView? = null
    private var layoutSearchEmptyState: View? = null
    private var layoutEmptyState: View? = null
    private var btnSort: MaterialButton? = null
    private val viewModel: PlaybackViewModel by activityViewModels()
    private var isAlbumGridView = true

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View? {
        return inflater.inflate(R.layout.fragment_albums, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        recyclerView = view.findViewById(R.id.albumsRecyclerView)
        val header = view.findViewById<View>(R.id.albumsHeader)
        header.findViewById<TextView>(R.id.tvHeaderTitle).text = getString(R.string.nav_albums)
        tvHeaderSubtitle = header.findViewById(R.id.tvHeaderSubtitle)
        
        searchBar = view.findViewById(R.id.searchBar)
        searchView = view.findViewById(R.id.searchView)
        rvSearchResults = view.findViewById(R.id.rvSearchResults)
        layoutSearchEmptyState = view.findViewById(R.id.layoutSearchEmptyState)
        layoutEmptyState = view.findViewById(R.id.layoutEmptyState)
        btnSort = view.findViewById(R.id.btnSort)
        
        val btnToggleView = view.findViewById<ImageView>(R.id.btnToggleView)

        setupAdapter()
        updateLayout(btnToggleView)

        btnToggleView?.setOnClickListener {
            isAlbumGridView = !isAlbumGridView
            updateLayout(btnToggleView)
        }

        btnSort?.setOnClickListener { showAlbumSortPopup() }

        setupSearch()

        searchBar?.setOnMenuItemClickListener { menuItem ->
            if (menuItem.itemId == R.id.action_clear) {
                searchBar?.setText("")
                searchView?.setText("")
                viewModel.applyFilterAndSearch("")
            }
            true
        }

        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                combine(
                    viewModel.songsLibrary,
                    viewModel.albumSortOrder,
                    viewModel.albumSortDirection
                ) { songs, order, direction ->
                    Triple(songs, order, direction)
                }.collectLatest { (songs, order, direction) ->
                    updateAlbums(songs, order, direction)
                }
            }
        }
    }

    private fun setupAdapter() {
        albumsAdapter = AlbumsAdapter(emptyList()) { albumName, sharedView ->
            val fragment = AlbumDetailsFragment().apply {
                arguments = Bundle().apply {
                    putString(AlbumDetailsFragment.ARG_ALBUM_NAME, albumName)
                    putString("transition_name", sharedView.transitionName)
                }
            }
            parentFragmentManager.beginTransaction()
                .setReorderingAllowed(true)
                .addSharedElement(sharedView, sharedView.transitionName)
                .add(R.id.navHostFragment, fragment)
                .hide(this)
                .addToBackStack(null)
                .commit()
        }
        recyclerView.adapter = albumsAdapter
        recyclerView.layoutAnimation = AnimationUtils.loadLayoutAnimation(context, R.anim.layout_animation_fall_down)
        
        searchAlbumsAdapter = AlbumsAdapter(emptyList()) { albumName, _ ->
            val fragment = AlbumDetailsFragment().apply {
                arguments = Bundle().apply {
                    putString(AlbumDetailsFragment.ARG_ALBUM_NAME, albumName)
                }
            }
            parentFragmentManager.beginTransaction()
                .replace(R.id.navHostFragment, fragment)
                .addToBackStack(null)
                .commit()
            searchView?.hide()
        }
        val spanCount = resources.getInteger(R.integer.album_grid_span)
        rvSearchResults?.layoutManager = GridLayoutManager(requireContext(), spanCount)
        rvSearchResults?.adapter = searchAlbumsAdapter
    }

    private fun updateLayout(btn: ImageView?) {
        val spanCount = resources.getInteger(R.integer.album_grid_span)
        albumsAdapter.isGridView = isAlbumGridView
        recyclerView.layoutManager = if (isAlbumGridView) GridLayoutManager(requireContext(), spanCount) else LinearLayoutManager(requireContext())
        btn?.setImageResource(if (isAlbumGridView) R.drawable.ic_view_list else R.drawable.ic_grid_view)
    }

    private fun updateAlbums(
        songs: List<Song>,
        order: PlaybackViewModel.AlbumSortOrder,
        direction: PlaybackViewModel.SortDirection
    ) {
        lifecycleScope.launch(Dispatchers.Default) {
            val albums = groupAndSortAlbums(songs, order, direction)
            withContext(Dispatchers.Main) {
                albumsAdapter.updateAlbums(albums)
                tvHeaderSubtitle.text = resources.getQuantityString(R.plurals.number_of_albums, albums.size, albums.size)
                layoutEmptyState?.visibility = if (albums.isEmpty()) View.VISIBLE else View.GONE
            }
        }
    }

    private fun groupAndSortAlbums(
        songs: List<Song>,
        order: PlaybackViewModel.AlbumSortOrder,
        direction: PlaybackViewModel.SortDirection
    ): List<AlbumUIModel> {
        val albumGroups = songs.asSequence()
            .groupBy { AlbumUtils.getGroupingKey(it.album).lowercase() }
            .map { (_, list) ->
                val first = list.first()
                val albumName = AlbumUtils.getGroupingKey(first.album)
                val distinctArtists = list.map { it.artist.trim() }
                    .filter { it.isNotBlank() }
                    .distinctBy { it.lowercase() }
                val artistName = when {
                    distinctArtists.isEmpty() -> "Unknown Artist"
                    distinctArtists.size == 1 -> distinctArtists.first()
                    else -> "Various Artists"
                }
                val maxDate = list.maxOfOrNull { it.dateAdded } ?: 0L
                AlbumUIModel(
                    name = albumName,
                    artist = artistName,
                    tracksCount = list.size,
                    firstTrack = first,
                    dateAdded = maxDate
                )
            }
            .toList()

        val sorted = when (order) {
            PlaybackViewModel.AlbumSortOrder.ALPHABETICAL ->
                albumGroups.sortedBy { it.name.lowercase() }
            PlaybackViewModel.AlbumSortOrder.ARTIST ->
                albumGroups.sortedWith(compareBy({ it.artist.lowercase() }, { it.name.lowercase() }))
            PlaybackViewModel.AlbumSortOrder.TRACK_COUNT ->
                albumGroups.sortedWith(compareBy({ it.tracksCount }, { it.name.lowercase() }))
            PlaybackViewModel.AlbumSortOrder.LAST_ADDED ->
                albumGroups.sortedWith(compareBy({ it.dateAdded }, { it.name.lowercase() }))
        }

        return if (direction == PlaybackViewModel.SortDirection.DESCENDING) sorted.reversed() else sorted
    }

    private fun showAlbumSortPopup() {
        val btnSort = btnSort ?: return
        val popupView = LayoutInflater.from(requireContext()).inflate(R.layout.layout_sort_album_popup, btnSort.parent as? ViewGroup, false)
        val width = (180 * resources.displayMetrics.density).toInt()
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

        var currentOrder = viewModel.albumSortOrder.value
        var currentDir = viewModel.albumSortDirection.value

        val btnAsc = popupView.findViewById<ImageView>(R.id.btnGlobalAsc)
        val btnDesc = popupView.findViewById<ImageView>(R.id.btnGlobalDesc)
        val btnSortName = popupView.findViewById<TextView>(R.id.btnSortName)
        val btnSortArtist = popupView.findViewById<TextView>(R.id.btnSortArtist)
        val btnSortTrackCount = popupView.findViewById<TextView>(R.id.btnSortTrackCount)
        val btnSortDate = popupView.findViewById<TextView>(R.id.btnSortDate)

        fun updateSortUI() {
            styleDirectionButton(btnAsc, currentDir == PlaybackViewModel.SortDirection.ASCENDING)
            styleDirectionButton(btnDesc, currentDir == PlaybackViewModel.SortDirection.DESCENDING)

            styleSortOptionItem(btnSortName, currentOrder == PlaybackViewModel.AlbumSortOrder.ALPHABETICAL)
            styleSortOptionItem(btnSortArtist, currentOrder == PlaybackViewModel.AlbumSortOrder.ARTIST)
            styleSortOptionItem(btnSortTrackCount, currentOrder == PlaybackViewModel.AlbumSortOrder.TRACK_COUNT)
            styleSortOptionItem(btnSortDate, currentOrder == PlaybackViewModel.AlbumSortOrder.LAST_ADDED)
        }

        updateSortUI()

        btnAsc.setOnClickListener {
            currentDir = PlaybackViewModel.SortDirection.ASCENDING
            updateSortUI()
            viewModel.sortAlbums(currentOrder, currentDir)
        }

        btnDesc.setOnClickListener {
            currentDir = PlaybackViewModel.SortDirection.DESCENDING
            updateSortUI()
            viewModel.sortAlbums(currentOrder, currentDir)
        }

        btnSortName.setOnClickListener {
            currentOrder = PlaybackViewModel.AlbumSortOrder.ALPHABETICAL
            viewModel.sortAlbums(currentOrder, currentDir)
            popupWindow.dismiss()
        }
        btnSortArtist.setOnClickListener {
            currentOrder = PlaybackViewModel.AlbumSortOrder.ARTIST
            viewModel.sortAlbums(currentOrder, currentDir)
            popupWindow.dismiss()
        }
        btnSortTrackCount.setOnClickListener {
            currentOrder = PlaybackViewModel.AlbumSortOrder.TRACK_COUNT
            viewModel.sortAlbums(currentOrder, currentDir)
            popupWindow.dismiss()
        }
        btnSortDate.setOnClickListener {
            currentOrder = PlaybackViewModel.AlbumSortOrder.LAST_ADDED
            viewModel.sortAlbums(currentOrder, currentDir)
            popupWindow.dismiss()
        }

        popupWindow.showAsDropDown(btnSort, 0, 10, Gravity.END)
    }

    private fun styleSortOptionItem(textView: TextView, isSelected: Boolean) {
        val context = textView.context
        if (isSelected) {
            val primaryColor = ThemeUtils.getThemeColor(context, android.R.attr.colorPrimary)
            val secondaryContainer = ThemeUtils.getThemeColor(context, com.google.android.material.R.attr.colorSecondaryContainer)

            textView.setTextColor(primaryColor)
            textView.setTypeface(null, Typeface.BOLD)

            val checkDrawable = ContextCompat.getDrawable(context, R.drawable.ic_check)?.mutate()
            checkDrawable?.setTint(primaryColor)
            textView.setCompoundDrawablesWithIntrinsicBounds(null, null, checkDrawable, null)

            val shape = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = 12f * context.resources.displayMetrics.density
                setColor(secondaryContainer)
            }
            textView.background = shape
        } else {
            val onSurfaceColor = ThemeUtils.getThemeColor(context, com.google.android.material.R.attr.colorOnSurface)
            textView.setTextColor(onSurfaceColor)
            textView.setTypeface(null, Typeface.NORMAL)
            textView.setCompoundDrawablesWithIntrinsicBounds(null, null, null, null)
            textView.background = null
        }
    }

    private fun styleDirectionButton(button: ImageView, isActive: Boolean) {
        val context = button.context
        if (isActive) {
            val primaryContainer = ThemeUtils.getThemeColor(context, com.google.android.material.R.attr.colorPrimaryContainer)
            val onPrimaryContainer = ThemeUtils.getThemeColor(context, com.google.android.material.R.attr.colorOnPrimaryContainer)
            button.alpha = 1.0f
            button.backgroundTintList = ColorStateList.valueOf(primaryContainer)
            button.imageTintList = ColorStateList.valueOf(onPrimaryContainer)
        } else {
            val onSurface = ThemeUtils.getThemeColor(context, com.google.android.material.R.attr.colorOnSurface)
            button.alpha = 0.4f
            button.backgroundTintList = ColorStateList.valueOf(Color.TRANSPARENT)
            button.imageTintList = ColorStateList.valueOf(onSurface)
        }
    }

    private fun setupSearch() {
        searchView?.editText?.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                val query = s.toString()
                viewModel.applyFilterAndSearch(query)
                if (query.isEmpty()) {
                    searchBar?.setText("")
                    searchAlbumsAdapter.updateAlbums(emptyList())
                    layoutSearchEmptyState?.visibility = View.GONE
                    return
                }
                viewLifecycleOwner.lifecycleScope.launch {
                    val allSongs = viewModel.songsLibrary.value
                    val currentOrder = viewModel.albumSortOrder.value
                    val currentDir = viewModel.albumSortDirection.value

                    val filteredSongs = allSongs.filter { song ->
                        AlbumUtils.getGroupingKey(song.album).contains(query, ignoreCase = true) ||
                                song.artist.contains(query, ignoreCase = true)
                    }
                    val filteredAlbums = groupAndSortAlbums(filteredSongs, currentOrder, currentDir)

                    searchAlbumsAdapter.updateAlbums(filteredAlbums)
                    layoutSearchEmptyState?.visibility = if (filteredAlbums.isEmpty()) View.VISIBLE else View.GONE
                }
            }
            
            override fun afterTextChanged(s: Editable?) {}
        })

        searchView?.editText?.setOnEditorActionListener { v, _, _ ->
            val query = v.text.toString()
            if (query.isNotBlank()) {
                viewModel.saveSearchQuery(query)
                searchBar?.setText(query)
                searchView?.hide()
            }
            false
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        btnSort = null
    }
}
