package com.music.myusiq.ui

import android.annotation.SuppressLint
import android.content.ContentUris
import android.os.Build
import android.os.Bundle
import android.provider.MediaStore
import android.transition.TransitionInflater
import android.util.Size
import android.view.View
import android.widget.FrameLayout
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
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import com.google.android.material.textfield.TextInputEditText
import com.music.myusiq.domain.models.Song
import com.music.myusiq.ui.viewmodels.PlaybackViewModel
import com.music.myusiq.ui.adapters.TrackAdapter
import com.music.myusiq.ui.adapters.TrackUiModel
import com.music.myusiq.ui.components.TrackInfoBottomSheet
import com.music.myusiq.ui.MainActivity
import com.music.myusiq.utils.PlatformUtils
import com.music.myusiq.R
import kotlinx.coroutines.delay
import kotlin.time.Duration.Companion.milliseconds

class PlaylistDetailsFragment : Fragment(R.layout.fragment_tracks) {
    private val viewModel: PlaybackViewModel by activityViewModels()
    private lateinit var trackAdapter: TrackAdapter
    private var favoriteTrackIds: Set<String> = emptySet()
    private var currentPlaylistSongs: List<Song> = emptyList()
    private var playlistId: Long = -1
    private var playlistName: String = ""

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        sharedElementEnterTransition = TransitionInflater.from(requireContext())
            .inflateTransition(android.R.transition.move)
    }

    @SuppressLint("SetTextI18n")
    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        postponeEnterTransition()
        
        playlistId = arguments?.getLong("playlistId") ?: -1
        playlistName = arguments?.getString("playlistName") ?: ""

        val ivHeaderArt = view.findViewById<ImageView>(R.id.ivHeaderArt)
        ivHeaderArt.visibility = View.VISIBLE
        view.findViewById<View>(R.id.layoutShimmer).visibility = View.GONE

        ivHeaderArt.transitionName = if (playlistId == -1L) "playlist_art_transition_${arguments?.getInt("position") ?: 0}" else "playlist_art_transition_$playlistId"
        view.findViewById<TextView>(R.id.tvHeaderTitle).text = playlistName
        view.findViewById<View>(R.id.appBarLayout).visibility = View.GONE
        view.findViewById<View>(R.id.btnSort).visibility = View.GONE
        
        val fabShuffleAll = view.findViewById<View>(R.id.fabShuffleAll)
        fabShuffleAll.visibility = View.VISIBLE
        fabShuffleAll.setOnClickListener {
            if (currentPlaylistSongs.isNotEmpty()) {
                viewModel.shuffleAllTracks(currentPlaylistSongs)
            }
        }

        val rvTracks = view.findViewById<RecyclerView>(R.id.rvTracks)
        trackAdapter = TrackAdapter(
            onTrackClicked = { song, imageView -> 
                val list = trackAdapter.currentList.map { it.song }
                val index = list.indexOf(song)
                if (index != -1) {
                    (activity as? MainActivity)?.playFromView(list, index, imageView)
                }
            },
            onOptionSelected = { song, id ->
                when (id) {
                    R.id.action_play_next -> viewModel.insertIntoQueueNext(song)
                    R.id.action_add_to_queue -> viewModel.appendToQueue(song)
                    R.id.action_start_radio -> viewModel.playMoreLikeThis(song)
                    R.id.action_add_to_playlist -> showAddToPlaylistDialog(song)
                    R.id.action_make_ringtone -> viewModel.setAsRingtone(requireContext(), song)
                    R.id.action_trim_audio -> navigateToTrimmer(song)
                    R.id.action_remove_from_playlist -> {
                        viewModel.removeTrackFromPlaylist(playlistId, song.id.toString())
                        Toast.makeText(requireContext(), "Removed from playlist", Toast.LENGTH_SHORT).show()
                        currentPlaylistSongs = currentPlaylistSongs.filter { it.id != song.id }
                        updateAdapterItems()
                    }
                    R.id.action_delete -> viewModel.deleteTrackFromStorage(requireContext(), song)
                    R.id.action_edit_tags -> showTrackInfo(song, startEdit = true)
                    R.id.action_track_info -> showTrackInfo(song, startEdit = false)
                }
            },
            menuCustomizer = { _, menu ->
                menu.findItem(R.id.action_remove_from_playlist)?.isVisible = (playlistId != -1L)
            }
        )
        rvTracks.layoutManager = LinearLayoutManager(requireContext())
        rvTracks.adapter = trackAdapter

        setupObservers(ivHeaderArt, view.findViewById(R.id.tvHeaderSubtitle))
    }

    private fun showTrackInfo(song: Song, startEdit: Boolean = false) {
        TrackInfoBottomSheet.newInstance(song.id, startEdit).show(childFragmentManager, "track_info")
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

    private fun setupObservers(ivHeaderArt: ImageView, tvSubtitle: TextView) {
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch {
                    if (playlistId == -1L) {
                        currentPlaylistSongs = withContext(Dispatchers.IO) {
                            when (playlistName) {
                                "Recently Added" -> viewModel.getRecentlyAddedTracks()
                                "Most Popular" -> viewModel.getMostPopularTracks()
                                "Morning Mix" -> viewModel.getMorningMix()
                                "Evening Chill" -> viewModel.getEveningMix()
                                "Top Artists Mix" -> viewModel.getTopArtistsMix()
                                "Forgotten Favorites" -> viewModel.getForgottenFavorites()
                                "Discover" -> viewModel.getDiscoverMix()
                                else -> emptyList()
                            }
                        }
                        updateAdapterItems()
                        tvSubtitle.text = getString(R.string.tracks_count_format, currentPlaylistSongs.size)
                        loadHeaderArt(currentPlaylistSongs.firstOrNull(), ivHeaderArt)
                    } else {
                        viewModel.getPlaylistWithTracks(playlistId).collectLatest { p ->
                            if (p != null) {
                                currentPlaylistSongs = p.tracks.mapNotNull { t -> viewModel.originalTrackList.find { it.id.toString() == t.id } }
                                updateAdapterItems()
                                tvSubtitle.text = getString(R.string.tracks_count_format, currentPlaylistSongs.size)
                                loadHeaderArt(currentPlaylistSongs.firstOrNull(), ivHeaderArt)
                            }
                        }
                    }
                }
                launch { viewModel.favoriteTrackIds.collect { ids -> favoriteTrackIds = ids; updateAdapterItems() } }
                launch { viewModel.currentPlayingSong.collect { updateAdapterItems() } }
                launch { viewModel.isPlaying.collect { updateAdapterItems() } }
                launch { viewModel.currentPosition.collect { updateAdapterItems() } }
            }
        }
    }

    private fun updateAdapterItems() {
        val currentSong = viewModel.currentPlayingSong.value
        val isPlaying = viewModel.isPlaying.value
        val position = viewModel.currentPosition.value
        trackAdapter.submitList(currentPlaylistSongs.map { song ->
            val isActive = song.id == currentSong?.id
            TrackUiModel(
                song = song, 
                isFavorite = favoriteTrackIds.contains(song.id.toString()), 
                isActive = isActive, 
                isPlaying = (isActive && isPlaying),
                progress = if (isActive && (song.duration > 0)) position.toFloat() / song.duration.toFloat() else 0f,
            )
        })
    }

    private fun loadHeaderArt(song: Song?, imageView: ImageView) {
        if (song == null) { startPostponedEnterTransition(); return }
        viewLifecycleOwner.lifecycleScope.launch(Dispatchers.IO) {
            val bitmap = try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    val uri = ContentUris.withAppendedId(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, song.id)
                    requireContext().contentResolver.loadThumbnail(uri, Size(600, 600), null)
                } else null
            } catch (_: Exception) { null }
            withContext(Dispatchers.Main) {
                if (bitmap != null) imageView.setImageBitmap(bitmap)
                else imageView.setImageResource(R.drawable.ic_playlist)
                startPostponedEnterTransition()
            }
        }
    }
}
