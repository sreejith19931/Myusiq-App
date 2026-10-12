package com.music.myusiq.ui

import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.View
import android.view.animation.AnimationUtils
import android.widget.ImageView
import android.widget.TextView
import android.widget.Toast
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import androidx.recyclerview.widget.GridLayoutManager
import androidx.swiperefreshlayout.widget.SwipeRefreshLayout
import com.google.android.material.search.SearchBar
import com.google.android.material.search.SearchView
import kotlinx.coroutines.launch
import java.io.File
import com.music.myusiq.domain.models.FolderItem
import com.music.myusiq.R
import com.music.myusiq.ui.adapters.FolderAdapter
import com.music.myusiq.ui.viewmodels.PlaybackViewModel
import com.music.myusiq.utils.*

class FoldersFragment : Fragment(R.layout.fragment_folders) {
    private val viewModel: PlaybackViewModel by activityViewModels()
    private var rvFolders: RecyclerView? = null
    private var swipeRefresh: SwipeRefreshLayout? = null
    private lateinit var folderAdapter: FolderAdapter
    private lateinit var searchFolderAdapter: FolderAdapter
    private lateinit var tvHeaderSubtitle: TextView
    private var searchBar: SearchBar? = null
    private var searchView: SearchView? = null
    private var rvSearchResults: RecyclerView? = null
    private var layoutSearchEmptyState: View? = null
    private var layoutEmptyState: View? = null
    private var isFolderGridView = false

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        rvFolders = view.findViewById(R.id.rvFolders)
        swipeRefresh = view.findViewById(R.id.swipeRefreshFolders)
        val header = view.findViewById<View>(R.id.foldersHeader)
        header.findViewById<TextView>(R.id.tvHeaderTitle).setText(R.string.nav_folders)
        tvHeaderSubtitle = header.findViewById(R.id.tvHeaderSubtitle)
        
        searchBar = view.findViewById(R.id.searchBar)
        searchView = view.findViewById(R.id.searchView)
        rvSearchResults = view.findViewById(R.id.rvSearchResults)
        layoutSearchEmptyState = view.findViewById(R.id.layoutSearchEmptyState)
        layoutEmptyState = view.findViewById(R.id.layoutEmptyState)

        context?.let { ctx ->
            PageGuideManager.showGuideIfNeeded(
                ctx,
                pageKey = "folders",
                pageTitle = "Folders Browser Guide",
                headerIconRes = R.drawable.ic_folder,
                features = listOf(
                    GuideFeatureItem(R.drawable.ic_folder, "Directory Browsing", "Browse tracks directly by phone storage directories and SD card folders."),
                    GuideFeatureItem(R.drawable.ic_edit, "Folder Inclusion & Exclusion", "Toggle folder checkboxes to include or exclude specific directories (e.g. ringtones or voice notes) from media scans."),
                    GuideFeatureItem(R.drawable.ic_play, "Play Entire Folder", "Tap the folder play icon to instantly play all tracks contained inside that directory.")
                )
            )
        }
        
        val btnToggleView = view.findViewById<ImageView>(R.id.btnToggleView)

        folderAdapter = FolderAdapter(
            onToggleFolder = { folder, isChecked ->
                viewModel.toggleFolderBlacklist(folder.absolutePath, isChecked)
            },
            onFolderClicked = { folder ->
                navigateToFolderDetails(folder)
            },
            onPlayFolderRequested = { folder ->
                val currentTrackLibrary = viewModel.originalTrackList
                val folderSongs = currentTrackLibrary.filter { song ->
                    val songFile = File(song.path)
                    (songFile.absolutePath.startsWith(folder.absolutePath + File.separator)) ||
                            (songFile.parentFile?.absolutePath == folder.absolutePath)
                }
                if (folderSongs.isNotEmpty()) {
                    viewModel.playSong(folderSongs, index = 0)
                    Toast.makeText(requireContext(), "Playing folder contents", Toast.LENGTH_SHORT).show()
                } else {
                    Toast.makeText(requireContext(), "No valid audio tracks found in this directory", Toast.LENGTH_SHORT).show()
                }
            },
        )
        rvFolders?.adapter = folderAdapter
        rvFolders?.layoutAnimation = AnimationUtils.loadLayoutAnimation(context, R.anim.layout_animation_fall_down)
        updateLayoutConfiguration(btnToggleView)
        btnToggleView?.setOnClickListener {
            isFolderGridView = !isFolderGridView
            updateLayoutConfiguration(btnToggleView)
        }

        searchFolderAdapter = FolderAdapter(
            onToggleFolder = { folder, isChecked -> viewModel.toggleFolderBlacklist(folder.absolutePath, isChecked) },
            onFolderClicked = { folder -> navigateToFolderDetails(folder); searchView?.hide() },
            onPlayFolderRequested = { folder ->
                // Play logic
                val folderSongs = viewModel.originalTrackList.filter { s -> File(s.path).absolutePath.startsWith(folder.absolutePath) }
                if (folderSongs.isNotEmpty()) {
                    viewModel.playSong(folderSongs, 0)
                    searchView?.hide()
                }
            }
        )
        rvSearchResults?.layoutManager = LinearLayoutManager(requireContext())
        rvSearchResults?.adapter = searchFolderAdapter

        setupSearch()

        searchBar?.setOnMenuItemClickListener { menuItem ->
            if (menuItem.itemId == R.id.action_clear) {
                searchBar?.setText("")
                searchView?.setText("")
                viewModel.applyFilterAndSearch("")
            }
            true
        }

        swipeRefresh?.setOnRefreshListener {
            viewModel.forceScanMediaLibrary()
        }
        
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch {
                    viewModel.uiFolderItems.collect { items ->
                        folderAdapter.submitList(items)
                        val count = items.size
                        tvHeaderSubtitle.text = resources.getQuantityString(R.plurals.number_of_folders, count, count)
                        layoutEmptyState?.visibility = if (items.isEmpty()) View.VISIBLE else View.GONE
                    }
                }
                
                launch {
                    viewModel.isLibraryLoading.collect { isLoading ->
                        swipeRefresh?.isRefreshing = isLoading
                    }
                }
            }
        }

        if (viewModel.uiFolderItems.value.isEmpty()) {
            viewModel.loadFolders()
        }
    }
    private fun updateLayoutConfiguration(btnToggleView: ImageView?) {
        folderAdapter.isGridView = isFolderGridView

        if (isFolderGridView) {
            val spanCount = resources.getInteger(R.integer.album_grid_span)
            rvFolders?.layoutManager = GridLayoutManager(requireContext(), spanCount)
            btnToggleView?.setImageResource(R.drawable.ic_view_list)
        } else {
            rvFolders?.layoutManager = LinearLayoutManager(requireContext())
            btnToggleView?.setImageResource(R.drawable.ic_grid_view)
        }

        rvFolders?.invalidateItemDecorations()
    }
    private fun navigateToFolderDetails(folder: FolderItem) {
        val fragment = TracksFragment().apply {
            arguments = Bundle().apply {
                putString("folder_path", folder.absolutePath)
                putString("folder_name", folder.folderName)
            }
        }
        parentFragmentManager.beginTransaction()
            .setCustomAnimations(R.anim.slide_in_right, R.anim.slide_out_left, R.anim.slide_in_left, R.anim.slide_out_right)
            .add(R.id.navHostFragment, fragment)
            .hide(this)
            .addToBackStack(null)
            .commit()
    }

    private fun setupSearch() {
        searchView?.editText?.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}

            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                val query = s.toString()
                viewModel.applyFilterAndSearch(query)
                if (query.isEmpty()) {
                    searchBar?.setText("")
                    searchFolderAdapter.submitList(emptyList())
                    layoutSearchEmptyState?.visibility = View.GONE
                    return
                }
                val filtered = viewModel.uiFolderItems.value.filter { it.folderName.contains(query, ignoreCase = true) }
                searchFolderAdapter.submitList(filtered)
                layoutSearchEmptyState?.visibility = if (filtered.isEmpty()) View.VISIBLE else View.GONE
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
        rvFolders?.adapter = null
        rvFolders = null
        swipeRefresh = null
        super.onDestroyView()
    }
}
