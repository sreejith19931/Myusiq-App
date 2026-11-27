package com.music.myusiq.ui

import android.annotation.SuppressLint
import android.content.ContentUris
import android.content.res.ColorStateList
import android.os.Bundle
import android.provider.MediaStore
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.animation.AnimationUtils
import android.widget.EditText
import android.widget.ImageView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.widget.PopupMenu
import androidx.core.view.isVisible
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import android.view.Menu
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.chip.Chip
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.floatingactionbutton.FloatingActionButton
import com.bumptech.glide.Glide
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import com.music.myusiq.domain.models.*
import com.music.myusiq.data.AppDatabase
import com.music.myusiq.data.dao.PlaylistDao
import com.music.myusiq.domain.usecase.MoodClassifier
import com.music.myusiq.ui.viewmodels.PlaybackViewModel
import com.music.myusiq.utils.*
import com.music.myusiq.DatabaseProvider
import com.music.myusiq.MainApplication
import com.music.myusiq.R
import java.util.Locale

class PlaylistsFragment : Fragment(R.layout.fragment_playlists) {
    private val viewModel: PlaybackViewModel by activityViewModels()
    private lateinit var adapter: PlaylistsAdapter
    private lateinit var tvHeaderSubtitle: TextView

    companion object {
        private const val SUGGEST_TRACKS_ID = 11
        private const val TYPE_INSIGHTS = 0
        private const val TYPE_REPLAY = 1
        private const val TYPE_SMART = 2
        private const val TYPE_USER = 3
        private const val TYPE_HEADER = 4
    }

    data class SmartPlaylist(val name: String, val iconResId: Int)

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        val rvPlaylists = view.findViewById<RecyclerView>(R.id.rvPlaylists)
        val fabAddPlaylist = view.findViewById<FloatingActionButton>(R.id.fabAddPlaylist)
        val header = view.findViewById<View>(R.id.playlistsHeader)
        header.findViewById<TextView>(R.id.tvHeaderTitle).text = getString(R.string.playlists)
        tvHeaderSubtitle = header.findViewById(R.id.tvHeaderSubtitle)

        context?.let { ctx ->
            PageGuideManager.showGuideIfNeeded(
                ctx,
                pageKey = "playlists",
                pageTitle = "Playlists & Smart Mixes Guide",
                headerIconRes = R.drawable.ic_playlist,
                features = listOf(
                    GuideFeatureItem(R.drawable.ic_speed, "Smart AI Auto-Mixes", "Enjoy auto-generated Morning Mix, Top Artists, Forgotten Favorites, and Discover mixes."),
                    GuideFeatureItem(R.drawable.ic_heart_filled, "Mood Playlists", "Tap a mood chip (Energize, Relax, Party, Focus, Romance, Drive) to open its mood playlist. The Mood Engine automatically analyzes your library in the background and learns from your playlists. Long-press a chip to play it instantly."),
                    GuideFeatureItem(R.drawable.ic_add, "Custom Playlists", "Tap the floating '+' button to create user playlists and organize your audio collection.")
                )
            )
        }

        adapter = PlaylistsAdapter(
            onPlaylistClick = { item, transitionView, position ->
                when (item) {
                    is ReplayInfo -> {
                        viewLifecycleOwner.lifecycleScope.launch(Dispatchers.IO) {
                            val songHistoryDao = (requireContext().applicationContext as MainApplication).database.songHistoryDao()
                            val topTracks = songHistoryDao.getTopTracksInRange(item.startTime, item.endTime, 15)
                            withContext(Dispatchers.Main) {
                                if (topTracks.isNotEmpty()) {
                                    val mediaItems = topTracks.mapNotNull { track ->
                                        viewModel.originalTrackList.find { it.id.toString() == track.songId }
                                    }
                                    if (mediaItems.isNotEmpty()) {
                                        viewModel.playSong(mediaItems, 0, shouldExpand = true)
                                        Toast.makeText(requireContext(), getString(R.string.playing_mix_toast, item.title), Toast.LENGTH_SHORT).show()
                                        viewModel.navigateToTab(R.id.dest_queue)
                                    }
                                }
                            }
                        }
                    }
                    else -> {
                        val fragment = PlaylistDetailsFragment().apply {
                            arguments = Bundle().apply {
                                putInt("position", position)
                                when (item) {
                                    is PlaylistWithTracks -> {
                                        putLong("playlistId", item.playlist.id)
                                        putString("playlistName", item.playlist.name)
                                    }
                                    is String -> {
                                        putLong("playlistId", -1L)
                                        putString("playlistName", item)
                                    }
                                    is SmartPlaylist -> {
                                        putLong("playlistId", -1L)
                                        putString("playlistName", item.name)
                                    }
                                }
                            }
                        }
                        parentFragmentManager.beginTransaction()
                            .setReorderingAllowed(true)
                            .addSharedElement(transitionView, transitionView.transitionName)
                            .setCustomAnimations(R.anim.slide_in_up, 0, 0, R.anim.slide_out_down)
                            .add(R.id.navHostFragment, fragment)
                            .hide(this@PlaylistsFragment)
                            .addToBackStack(null)
                            .commit()
                    }
                }
            },
            onPlaylistOptions = { playlist, itemView ->
                showPlaylistOptions(playlist, itemView)
            },
        )

        val spanCount = resources.getInteger(R.integer.album_grid_span)
        val layoutManager = GridLayoutManager(requireContext(), spanCount)
        layoutManager.spanSizeLookup = object : GridLayoutManager.SpanSizeLookup() {
            override fun getSpanSize(position: Int): Int {
                return if (adapter.getItemViewType(position) == TYPE_SMART) 1 else spanCount
            }
        }
        rvPlaylists.layoutManager = layoutManager
        rvPlaylists.adapter = adapter
        rvPlaylists.layoutAnimation = AnimationUtils.loadLayoutAnimation(context, R.anim.layout_animation_fall_down)

        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.playlistsWithTracks.collectLatest { playlists ->
                    // Fetch Insights
                    val appContext = requireContext().applicationContext
                    val insights = withContext(Dispatchers.IO) {
                        val db = DatabaseProvider.getDatabase(appContext)
                        val dao = db.songHistoryDao()
                        val totalMs = dao.getTotalListeningTimeAllTime() ?: 0L
                        val totalPlays = dao.getTotalPlaysAllTime()
                        val topArtist = dao.getMostPlayedArtist()
                        val topTrack = dao.getMostPlayedTrack()

                        val formattedTime = when {
                            totalMs >= 3600000 -> appContext.getString(R.string.hours_format, totalMs / 3600000)
                            totalMs >= 60000 -> appContext.getString(R.string.minutes_format, totalMs / 60000)
                            else -> appContext.getString(R.string.seconds_format, totalMs / 1000)
                        }
                        val noneYet = appContext.getString(R.string.none_yet)

                        mapOf(
                            "totalTime" to formattedTime,
                            "totalPlays" to String.format(Locale.getDefault(), "%,d", totalPlays),
                            "topArtist" to (topArtist?.artist ?: noneYet),
                            "topTrack" to (topTrack?.title ?: noneYet),
                        )
                    }

                    if (!isAdded) return@collectLatest

                    val allItems = mutableListOf<Any>()
                    allItems.add(insights)

                    val activeReplays = ReplayManager.getActiveReplays()
                    if (activeReplays.isNotEmpty()) {
                        allItems.add("Weekly Recaps")
                        allItems.addAll(activeReplays)
                    }

                    allItems.add("Made For You")
                    allItems.add(SmartPlaylist(getString(R.string.recently_added), R.drawable.ic_refresh))
                    allItems.add(SmartPlaylist(getString(R.string.most_popular), R.drawable.ic_heart_filled))
                    allItems.add(SmartPlaylist(getString(R.string.morning_mix), R.drawable.ic_light_mode))
                    allItems.add(SmartPlaylist("Evening Chill", R.drawable.ic_dark_mode))
                    allItems.add(SmartPlaylist(getString(R.string.top_artists_mix), R.drawable.ic_playlist))
                    allItems.add(SmartPlaylist(getString(R.string.forgotten_favorites), R.drawable.ic_featured_playlist))
                    allItems.add(SmartPlaylist(getString(R.string.discover), R.drawable.ic_music_note_2))

                    if (playlists.isNotEmpty()) {
                        allItems.add("Your Playlists")
                        allItems.addAll(playlists)
                    }

                    adapter.submitList(allItems)
                    val count = playlists.size
                    tvHeaderSubtitle.text = resources.getQuantityString(R.plurals.number_of_playlists, count, count)
                }
            }
        }

        fabAddPlaylist.setOnClickListener { view ->
            val menu = PopupMenu(requireContext(), view)
            menu.menu.add(0, Menu.FIRST + 1, Menu.NONE, R.string.create_playlist)
            menu.menu.add(0, Menu.FIRST + 2, Menu.NONE, R.string.import_m3u)
            menu.setOnMenuItemClickListener {
                when (it.itemId) {
                    Menu.FIRST + 2 -> { importM3ULauncher.launch(arrayOf("*/*")); true }
                    else -> { showCreatePlaylistDialog(); true }
                }
            }
            menu.show()
        }

        setupMoodChips(view)
    }

    private fun setupMoodChips(view: View) {
        // chipId -> (engine mood key, display name)
        val moods = mapOf(
            R.id.chipEnergize to MoodChip(MoodClassifier.MOOD_ENERGIZE, getString(R.string.mood_energize)),
            R.id.chipRelax to MoodChip(MoodClassifier.MOOD_RELAX, getString(R.string.mood_relax)),
            R.id.chipParty to MoodChip(MoodClassifier.MOOD_PARTY, getString(R.string.mood_party)),
            R.id.chipFocus to MoodChip(MoodClassifier.MOOD_FOCUS, getString(R.string.mood_focus)),
            R.id.chipRomance to MoodChip(MoodClassifier.MOOD_ROMANCE, getString(R.string.mood_romance)),
            R.id.chipDrive to MoodChip(MoodClassifier.MOOD_DRIVE, getString(R.string.mood_drive))
        )

        moods.forEach { (chipId, chip) ->
            view.findViewById<Chip?>(chipId)?.apply {
                // Tap: open the Mood Engine-powered playlist page for this mood.
                // Long-press: instantly start playing the mix (legacy behavior).
                // While the engine is still organizing the library, taps show a
                // "preparing" toast instead of an empty playlist.
                setOnClickListener {
                    if (viewModel.isMoodEngineReady()) {
                        openMoodPlaylist(chip.key, chip.displayName)
                    } else {
                        Toast.makeText(requireContext(), R.string.mood_preparing, Toast.LENGTH_SHORT).show()
                    }
                }
                setOnLongClickListener {
                    if (viewModel.isMoodEngineReady()) {
                        startMoodPlaylist(chip.key, chip.displayName)
                    } else {
                        Toast.makeText(requireContext(), R.string.mood_preparing, Toast.LENGTH_SHORT).show()
                    }
                    true
                }
            }
        }

        // Dim the chips while the engine organizes; restore once ready.
        viewModel.moodEngineReady.observe(viewLifecycleOwner) { ready ->
            moods.keys.forEach { id ->
                view.findViewById<Chip?>(id)?.alpha = if (ready == true) 1f else 0.4f
            }
        }
    }

    private data class MoodChip(val key: String, val displayName: String)

    /** Opens the mood playlist page with the engine-ranked track list. */
    private fun openMoodPlaylist(moodKey: String, displayName: String) {
        val fragment = PlaylistDetailsFragment().apply {
            arguments = Bundle().apply {
                putLong("playlistId", -1L)
                putString("playlistName", displayName)
                putString("moodKey", moodKey)
            }
        }
        parentFragmentManager.beginTransaction()
            .setReorderingAllowed(true)
            .setCustomAnimations(R.anim.slide_in_up, 0, 0, R.anim.slide_out_down)
            .add(R.id.navHostFragment, fragment)
            .hide(this@PlaylistsFragment)
            .addToBackStack(null)
            .commit()
    }

    private fun startMoodPlaylist(moodKey: String, displayName: String) {
        val allTracks = viewModel.originalTrackList
        if (allTracks.isEmpty()) {
            Toast.makeText(requireContext(), R.string.library_loading, Toast.LENGTH_SHORT).show()
            return
        }

        viewLifecycleOwner.lifecycleScope.launch {
            val songs = viewModel.getMoodMix(moodKey)

            if (songs.isNotEmpty()) {
                viewModel.playSong(songs, 0, true)
                val msg = getString(R.string.playing_mood_mix, displayName)
                Toast.makeText(requireContext(), msg, Toast.LENGTH_SHORT).show()
                viewModel.navigateToTab(R.id.dest_queue)
            } else {
                val msg = getString(R.string.no_mood_tracks_found, displayName)
                Toast.makeText(requireContext(), msg, Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun showCreatePlaylistDialog() {
        val editText = EditText(requireContext())
        MaterialAlertDialogBuilder(requireContext())
            .setTitle(R.string.create_playlist)
            .setView(editText)
            .setPositiveButton(R.string.create) { _, _ ->
                val name = editText.text.toString()
                if (name.isNotBlank()) {
                    viewModel.createNewPlaylist(name)
                }
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private var pendingM3UExport: Pair<String, List<Song>>? = null

    private val exportM3ULauncher = registerForActivityResult(
        ActivityResultContracts.CreateDocument("audio/x-mpegurl")
    ) { uri ->
        val pending = pendingM3UExport ?: return@registerForActivityResult
        pendingM3UExport = null
        uri ?: return@registerForActivityResult
        lifecycleScope.launch(Dispatchers.IO) {
            val ok = try {
                requireContext().contentResolver.openOutputStream(uri)?.let {
                    com.music.myusiq.utils.M3UUtils.write(pending.first, pending.second, it)
                } ?: false
            } catch (_: Exception) { false }
            withContext(Dispatchers.Main) {
                val resId = if (ok) R.string.m3u_exported else R.string.backup_failed
                Toast.makeText(requireContext(), resId, Toast.LENGTH_SHORT).show()
            }
        }
    }

    private val importM3ULauncher = registerForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri ->
        uri ?: return@registerForActivityResult
        lifecycleScope.launch(Dispatchers.IO) {
            val parsed = try {
                requireContext().contentResolver.openInputStream(uri)?.bufferedReader()?.use {
                    com.music.myusiq.utils.M3UUtils.parse(it.readText())
                }
            } catch (_: Exception) { null }
            if (parsed == null) {
                withContext(Dispatchers.Main) {
                    Toast.makeText(requireContext(), R.string.m3u_import_failed, Toast.LENGTH_SHORT).show()
                }
                return@launch
            }
            viewModel.importM3U(parsed.name, parsed.paths) { matched, missing ->
                val msg = getString(R.string.m3u_imported, matched, missing)
                Toast.makeText(requireContext(), msg, Toast.LENGTH_LONG).show()
            }
        }
    }

    private fun showPlaylistOptions(playlist: PlaylistWithTracks, view: View) {
        val popup = PopupMenu(requireContext(), view)
        popup.menu.add(SUGGEST_TRACKS_ID, Menu.FIRST + 1, Menu.NONE, R.string.mood_suggest_menu)
        popup.menu.add(SUGGEST_TRACKS_ID, Menu.FIRST + 2, Menu.NONE, R.string.export_m3u)
        popup.menu.add(R.string.delete)
        popup.setOnMenuItemClickListener {
            when (it.itemId) {
                SUGGEST_TRACKS_ID -> {
                    showTrackSuggestions(playlist)
                    true
                }
                Menu.FIRST + 2 -> {
                    exportPlaylistM3U(playlist)
                    true
                }
                else -> {
                    viewModel.deletePlaylist(playlist.playlist.id)
                    true
                }
            }
        }
        popup.show()
    }

    /** Resolves the playlist's songs, then opens the SAF file picker for
     *  the .m3u destination. */
    private fun exportPlaylistM3U(playlist: PlaylistWithTracks) {
        viewModel.exportPlaylistM3U(playlist.playlist.id) { name, songs ->
            if (songs.isEmpty()) {
                Toast.makeText(requireContext(), R.string.m3u_empty_playlist, Toast.LENGTH_SHORT).show()
                return@exportPlaylistM3U
            }
            pendingM3UExport = name to songs
            try {
                val safeName = name.replace(Regex("[\\\\/:*?\"<>|]"), "_")
                exportM3ULauncher.launch("$safeName.m3u")
            } catch (_: Exception) {}
        }
    }

    /**
     * Mood Engine suggestions: ranks library tracks that are not in the
     * playlist by similarity to its learned profile; the user picks which
     * ones to add.
     */
    private fun showTrackSuggestions(playlist: PlaylistWithTracks) {
        viewLifecycleOwner.lifecycleScope.launch {
            val existingIds = playlist.tracks.map { it.id }.toSet()
            val suggestions = viewModel.suggestTracksForPlaylist(playlist.playlist.id, existingIds)
            if (suggestions.isEmpty()) {
                Toast.makeText(requireContext(), R.string.mood_suggest_none, Toast.LENGTH_LONG).show()
                return@launch
            }
            val labels = suggestions.map { "${it.title} — ${it.artist}" }.toTypedArray()
            val checked = BooleanArray(labels.size) { false }
            MaterialAlertDialogBuilder(requireContext())
                .setTitle(getString(R.string.mood_suggest_title, playlist.playlist.name))
                .setMultiChoiceItems(labels, checked) { _, which, isChecked -> checked[which] = isChecked }
                .setPositiveButton(R.string.mood_suggest_add) { _, _ ->
                    val selected = suggestions.filterIndexed { i, _ -> checked[i] }
                    selected.forEach { song ->
                        viewModel.addTrackToPlaylist(song.id.toString(), playlist.playlist.id)
                    }
                    if (selected.isNotEmpty()) {
                        Toast.makeText(requireContext(), R.string.mood_suggest_added, Toast.LENGTH_SHORT).show()
                    }
                }
                .setNegativeButton(R.string.cancel, null)
                .show()
        }
    }

    private inner class PlaylistsAdapter(
        private val onPlaylistClick: (Any, View, Int) -> Unit,
        private val onPlaylistOptions: (PlaylistWithTracks, View) -> Unit
    ) : RecyclerView.Adapter<RecyclerView.ViewHolder>() {
        private var items = emptyList<Any>()

        fun submitList(newItems: List<Any>) {
            val oldItems = items
            items = newItems

            DiffUtil.calculateDiff(object : DiffUtil.Callback() {
                
                override fun getOldListSize() = oldItems.size
                override fun getNewListSize() = items.size
                override fun areItemsTheSame(oldPos: Int, newPos: Int): Boolean {
                    val old = oldItems[oldPos]
                    val new = items[newPos]
                    return when (old) {
                        is Map<*, *> -> new is Map<*, *>
                        is ReplayInfo -> new is ReplayInfo && old.startTime == new.startTime
                        is SmartPlaylist -> new is SmartPlaylist && old.name == new.name
                        is PlaylistWithTracks -> new is PlaylistWithTracks && old.playlist.id == new.playlist.id
                        is String -> new is String && old == new
                        else -> false
                    }
                }
                override fun areContentsTheSame(oldPos: Int, newPos: Int) = oldItems[oldPos] == items[newPos]
            }).dispatchUpdatesTo(this)
        }

        override fun getItemViewType(position: Int): Int {
            return when (items[position]) {
                is Map<*, *> -> TYPE_INSIGHTS
                is ReplayInfo -> TYPE_REPLAY
                is SmartPlaylist -> TYPE_SMART
                is String -> TYPE_HEADER
                else -> TYPE_USER
            }
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
            val inflater = LayoutInflater.from(parent.context)
            return when (viewType) {
                TYPE_INSIGHTS -> InsightsViewHolder(inflater.inflate(R.layout.layout_listening_insights, parent, false))
                TYPE_REPLAY -> ReplayViewHolder(inflater.inflate(R.layout.layout_replay_banner, parent, false))
                TYPE_SMART -> ViewHolder(inflater.inflate(R.layout.item_playlist_grid, parent, false))
                TYPE_HEADER -> HeaderViewHolder(inflater.inflate(R.layout.item_playlist_header, parent, false))
                else -> ViewHolder(inflater.inflate(R.layout.item_playlist, parent, false))
            }
        }

        @SuppressLint("SetTextI18n")
        override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
            val item = items[position]
            if (holder is HeaderViewHolder && item is String) {
                holder.tvTitle.text = item
            } else if (holder is InsightsViewHolder && item is Map<*, *>) {
                holder.tvTotalTime.text = item["totalTime"] as? String ?: "0h"
                holder.tvTotalPlays.text = item["totalPlays"] as? String ?: "0"
                holder.tvTopArtist.text = item["topArtist"] as? String ?: "None yet"
                holder.tvTopTrack.text = item["topTrack"] as? String ?: "None yet"

                holder.itemView.setOnClickListener {
                    if (!holder.layoutExpanded.isVisible) {
                        val totalTime = item["totalTime"] as? String ?: "0h"
                        val totalPlays = item["totalPlays"] as? String ?: "0"
                        val topArtist = item["topArtist"] as? String ?: "None yet"
                        val topTrack = item["topTrack"] as? String ?: "None yet"
                        holder.tvDetailedStats.text = "All-time Stats:\n\n• Total Listening: $totalTime\n• Total Play Count: $totalPlays\n• Most Played Artist: $topArtist\n• Most Played Track: $topTrack\n\nKeep listening to see more detailed trends!"
                        
                        holder.layoutExpanded.visibility = View.VISIBLE
                        holder.layoutExpanded.alpha = 0f
                        holder.layoutExpanded.animate().alpha(1f).setDuration(300).start()
                    } else {
                        holder.layoutExpanded.animate().alpha(0f).setDuration(200).withEndAction {
                            holder.layoutExpanded.visibility = View.GONE
                        }.start()
                    }
                }
            } else if (holder is ReplayViewHolder && item is ReplayInfo) {
                val params = holder.itemView.layoutParams as? ViewGroup.MarginLayoutParams
                params?.setMargins(16, 8, 16, 24)
                holder.itemView.layoutParams = params

                holder.tvTitle.text = item.title
                holder.tvSubtitle.text = item.subtitle
                holder.btnDismiss.visibility = View.GONE
                holder.itemView.setOnClickListener { onPlaylistClick(item, holder.tvTitle, position) }
                holder.btnPlay.setOnClickListener { onPlaylistClick(item, it, position) }
            } else if (holder is ViewHolder) {
                if (item is SmartPlaylist) {
                    holder.tvName.text = item.name
                    holder.tvCount.visibility = View.GONE
                    holder.btnOptions.visibility = View.GONE
                    holder.ivArt.setImageResource(item.iconResId)
                    holder.ivArt.imageTintList = ColorStateList.valueOf(ThemeUtils.getThemeColor(holder.itemView.context, android.R.attr.colorPrimary))
                    holder.ivArt.transitionName = "playlist_art_transition_$position"
                    holder.itemView.setOnClickListener { onPlaylistClick(item.name, holder.ivArt, position) }
                } else if (item is PlaylistWithTracks) {
                    holder.tvName.text = item.playlist.name
                    holder.tvCount.visibility = View.VISIBLE
                    holder.tvCount.text = holder.itemView.context.getString(R.string.tracks_count_format, item.tracks.size)
                    holder.btnOptions.visibility = View.VISIBLE
                    holder.ivArt.transitionName = "playlist_art_transition_${item.playlist.id}"
                    
                    if (item.tracks.isNotEmpty()) {
                        val firstSong = item.tracks[0]
                        val trackArtUri = ContentUris.withAppendedId(
                            MediaStore.Audio.Media.EXTERNAL_CONTENT_URI,
                            firstSong.id.toLong()
                        )
                        Glide.with(holder.itemView.context)
                            .asBitmap()
                            .load(trackArtUri)
                            .placeholder(R.drawable.ic_playlist)
                            .error(R.drawable.ic_playlist)
                            .into(holder.ivArt)
                    } else {
                        holder.ivArt.setImageResource(R.drawable.ic_playlist)
                    }

                    holder.itemView.setOnClickListener { onPlaylistClick(item, holder.ivArt, position) }
                    holder.btnOptions.setOnClickListener { onPlaylistOptions(item, it) }
                }
            }
        }

        override fun getItemCount() = items.size

        inner class ViewHolder(view: View) : RecyclerView.ViewHolder(view) {
            val ivArt: ImageView = view.findViewById(R.id.ivPlaylistArt)
            val tvName: TextView = view.findViewById(R.id.tvPlaylistName)
            val tvCount: TextView = view.findViewById(R.id.tvPlaylistTrackCount)
            val btnOptions: View = view.findViewById(R.id.btnPlaylistOptions)
        }

        inner class InsightsViewHolder(view: View) : RecyclerView.ViewHolder(view) {
            val tvTotalTime: TextView = view.findViewById(R.id.tvTotalTime)
            val tvTotalPlays: TextView = view.findViewById(R.id.tvTotalPlays)
            val tvTopArtist: TextView = view.findViewById(R.id.tvTopArtist)
            val tvTopTrack: TextView = view.findViewById(R.id.tvTopTrack)
            val layoutExpanded: View = view.findViewById(R.id.layoutExpandedInsights)
            val tvDetailedStats: TextView = view.findViewById(R.id.tvDetailedStats)
        }

        inner class ReplayViewHolder(view: View) : RecyclerView.ViewHolder(view) {
            val tvTitle: TextView = view.findViewById(R.id.tvReplayTitle)
            val tvSubtitle: TextView = view.findViewById(R.id.tvReplaySubtitle)
            val btnPlay: View = view.findViewById(R.id.btnPlayReplay)
            val btnDismiss: View = view.findViewById(R.id.btnDismissReplay)
        }
        
        inner class HeaderViewHolder(view: View) : RecyclerView.ViewHolder(view) {
            val tvTitle: TextView = view.findViewById(R.id.tvHeaderTitle)
        }
    }
}
