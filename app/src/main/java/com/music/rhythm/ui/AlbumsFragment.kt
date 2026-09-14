package com.music.rhythm.ui

import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.animation.AnimationUtils
import android.widget.ImageView
import android.widget.TextView
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.search.SearchBar
import com.google.android.material.search.SearchView
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.collectLatest
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
        
        val btnToggleView = view.findViewById<ImageView>(R.id.btnToggleView)

        setupAdapter()
        updateLayout(btnToggleView)

        btnToggleView?.setOnClickListener {
            isAlbumGridView = !isAlbumGridView
            updateLayout(btnToggleView)
        }

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
                viewModel.songsLibrary.collectLatest { songs ->
                    updateAlbums(songs)
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

    private suspend fun updateAlbums(songs: List<Song>) {
        withContext(Dispatchers.Default) {
            val albums = songs.asSequence()
                .groupBy { "${AlbumUtils.getGroupingKey(it.album)}|${it.artist.lowercase()}" }
                .map { (_, list) -> 
                    val first = list.first()
                    AlbumUIModel(AlbumUtils.getGroupingKey(first.album), first.artist, list.size, first) 
                }
                .sortedBy { it.name.lowercase() }
                .toList()
            
            withContext(Dispatchers.Main) {
                albumsAdapter.updateAlbums(albums)
                tvHeaderSubtitle.text = resources.getQuantityString(R.plurals.number_of_albums, albums.size, albums.size)
                layoutEmptyState?.visibility = if (albums.isEmpty()) View.VISIBLE else View.GONE
            }
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
                // Filtering logic for albums
                viewLifecycleOwner.lifecycleScope.launch {
                    val allSongs = viewModel.songsLibrary.value
                    val filteredAlbums = allSongs.groupBy { s -> AlbumUtils.getGroupingKey(s.album) }
                        .filter { entry -> 
                            entry.key.contains(query, ignoreCase = true) || 
                                    entry.value.any { song -> song.artist.contains(query, ignoreCase = true) } 
                        }
                        .map { entry -> AlbumUIModel(entry.key, entry.value.firstOrNull()?.artist ?: "Unknown Artist", entry.value.size, entry.value.firstOrNull()) }
                        .sortedBy { m -> m.name.lowercase() }
                    
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
}
