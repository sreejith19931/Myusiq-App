package com.music.myusiq.ui

import android.content.ContentUris
import android.graphics.BitmapFactory
import android.os.Build
import android.os.Bundle
import android.provider.MediaStore
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
import androidx.transition.TransitionInflater
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import androidx.core.net.toUri
import com.google.android.material.textfield.TextInputEditText
import com.music.myusiq.domain.models.Song
import com.music.myusiq.ui.viewmodels.PlaybackViewModel
import com.music.myusiq.ui.adapters.TrackAdapter
import com.music.myusiq.ui.adapters.TrackUiModel
import com.music.myusiq.ui.components.TrackInfoBottomSheet
import com.music.myusiq.utils.*
import com.music.myusiq.R
import kotlinx.coroutines.delay

class AlbumDetailsFragment : Fragment(R.layout.fragment_tracks) {
    private val viewModel: PlaybackViewModel by activityViewModels()
    private lateinit var trackAdapter: TrackAdapter
    private var favoriteTrackIds: Set<String> = emptySet()
    private var currentAlbumSongs: List<Song> = emptyList()

    companion object {
        const val ARG_ALBUM_NAME = "album_name"
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val transition = TransitionInflater.from(requireContext())
            .inflateTransition(android.R.transition.move)
        sharedElementEnterTransition = transition
        sharedElementReturnTransition = transition
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        postponeEnterTransition()
        
        val albumName = arguments?.getString(ARG_ALBUM_NAME) ?: return
        val transitionName = arguments?.getString("transition_name")

        val ivHeaderArt = view.findViewById<ImageView>(R.id.ivHeaderArt)
        ivHeaderArt.visibility = View.VISIBLE
        view.findViewById<TextView>(R.id.tvHeaderTitle).text = albumName
        
        // Hide unused elements on details page
        view.findViewById<View>(R.id.layoutShimmer).visibility = View.GONE
        view.findViewById<View>(R.id.appBarLayout).visibility = View.GONE
        view.findViewById<View>(R.id.btnSort).visibility = View.GONE
        view.findViewById<View>(R.id.btnBatchEdit).visibility = View.GONE
        
        if (transitionName != null) {
            ivHeaderArt.transitionName = transitionName
        }

        val rv = view.findViewById<RecyclerView>(R.id.rvTracks)
        trackAdapter = TrackAdapter(
            isQueueMode = false,
            onTrackClicked = { song, imageView ->
                val list = trackAdapter.currentList.map { it.song }
                val index = list.indexOf(song)
                if (index != -1) {
                    (activity as? MainActivity)?.playFromView(list, index, imageView)
                }
            },
            onOptionSelected = { song, optionId ->
                when (optionId) {
                    R.id.action_play_next -> viewModel.insertIntoQueueNext(song)
                    R.id.action_add_to_queue -> viewModel.appendToQueue(song)
                    R.id.action_start_radio -> viewModel.playMoreLikeThis(song)
                    R.id.action_add_to_playlist -> showAddToPlaylistDialog(song)
                    R.id.action_make_ringtone -> viewModel.setAsRingtone(requireContext(), song)
                    R.id.action_trim_audio -> navigateToTrimmer(song)
                    R.id.action_delete -> viewModel.deleteTrackFromStorage(requireContext(), song)
                    R.id.action_edit_tags -> showTrackInfo(song)
                    R.id.action_track_info -> showTrackInfo(song)
                }
            }
        )
        rv.adapter = trackAdapter
        rv.layoutManager = LinearLayoutManager(requireContext())

        view.findViewById<View>(R.id.fabShuffleAll).setOnClickListener {
            val list = trackAdapter.currentList.map { it.song }
            if (list.isNotEmpty()) {
                viewModel.shuffleAllTracks(list)
            }
        }

        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch {
                    viewModel.songsLibrary.collect { allSongs ->
                        currentAlbumSongs = allSongs.filter { AlbumUtils.getGroupingKey(it.album).equals(albumName, ignoreCase = true) }
                        updateAdapterItems()
                        view.findViewById<TextView>(R.id.tvHeaderSubtitle).text = "${currentAlbumSongs.size} tracks"

                        // Load header art from first song
                        currentAlbumSongs.firstOrNull()?.let { song ->
                            launch(Dispatchers.IO) {
                                val bitmap = try {
                                    val cachedFile = com.music.myusiq.utils.ArtworkUtils.getArtworkFile(requireContext(), song.id, song.path)
                                    if (cachedFile != null) {
                                        BitmapFactory.decodeFile(cachedFile.absolutePath)
                                    } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                                        val trackUri = ContentUris.withAppendedId(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, song.id)
                                        requireContext().contentResolver.loadThumbnail(trackUri, Size(600, 600), null)
                                    } else {
                                        val artUri = "content://media/external/audio/albumart".toUri()
                                        val albumArtUri = ContentUris.withAppendedId(artUri, song.albumId)
                                        val inputStream = requireContext().contentResolver.openInputStream(albumArtUri)
                                        BitmapFactory.decodeStream(inputStream)
                                    }
                                } catch (_: Exception) { null }

                                withContext(Dispatchers.Main) {
                                    if (bitmap != null) {
                                        ivHeaderArt.setImageBitmap(bitmap)
                                    } else {
                                        ivHeaderArt.setImageResource(R.drawable.ic_music_note_2)
                                    }
                                    startPostponedEnterTransition()
                                }
                            }
                        } ?: startPostponedEnterTransition()
                    }
                }

                launch {
                    viewModel.favoriteTrackIds.collect { ids ->
                        favoriteTrackIds = ids
                        updateAdapterItems()
                    }
                }

                launch { viewModel.currentPlayingSong.collect { updateAdapterItems() } }
                launch { viewModel.isPlaying.collect { updateAdapterItems() } }
                launch { viewModel.artworkRefreshSignatures.collect { updateAdapterItems() } }
            }
        }
    }

    private fun showTrackInfo(song: Song) {
        TrackInfoBottomSheet.newInstance(song.id).show(childFragmentManager, "track_info")
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
                            delay(500)
                            val p = viewModel.allPlaylists.first().find { it.name == name }
                            p?.let { viewModel.addTrackToPlaylist(song.id.toString(), it.id) }
                        }
                    }
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun updateAdapterItems() {
        val currentSong = viewModel.currentPlayingSong.value
        val isPlaying = viewModel.isPlaying.value
        val signatures = viewModel.artworkRefreshSignatures.value

        val uiModels = currentAlbumSongs.map { song ->
            val normPath = PlatformUtils.normalizePath(song.path)
            TrackUiModel(
                song = song,
                isFavorite = favoriteTrackIds.contains(song.id.toString()),
                isActive = song.id == currentSong?.id,
                isPlaying = (song.id == currentSong?.id && isPlaying),
                artworkSignature = signatures[normPath] ?: 0L
            )
        }
        trackAdapter.submitList(uiModels)
    }
}
