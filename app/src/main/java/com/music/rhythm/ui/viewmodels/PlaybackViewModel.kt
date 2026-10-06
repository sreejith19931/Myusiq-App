package com.music.rhythm.ui.viewmodels

import com.music.rhythm.data.MusicRepository
import com.music.rhythm.data.platform.AndroidMusicScanner
import com.music.rhythm.service.PlaybackService
import com.music.rhythm.service.EqualizerManager
import com.music.rhythm.utils.*

import android.app.Application
import android.app.PendingIntent
import android.content.ComponentName
import android.content.ContentUris
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.database.ContentObserver
import android.media.AudioDeviceCallback
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.media.MediaScannerConnection
import android.media.RingtoneManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.util.Log
import android.provider.MediaStore
import android.widget.Toast
import androidx.core.net.toUri
import androidx.activity.result.IntentSenderRequest
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.lang.ref.WeakReference
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.util.Locale
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.common.Timeline
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import androidx.media3.session.SessionCommand
import androidx.media3.session.SessionResult
import com.google.common.util.concurrent.ListenableFuture
import com.google.common.util.concurrent.MoreExecutors
import com.music.rhythm.R
import com.music.rhythm.DatabaseProvider
import com.music.rhythm.MainApplication
// LyricsResponse is now in the same package
import com.music.rhythm.domain.models.*
import kotlin.math.abs

data class PendingTrackEdit(
    val songId: Long,
    val title: String,
    val artist: String,
    val album: String,
    val year: String = "",
    val genre: String = "",
    val artworkUri: Uri? = null,
    val path: String = "",
)

class PlaybackViewModel(application: Application) : AndroidViewModel(application) {
    enum class TrackSortOrder { ALPHABETICAL, LAST_ADDED, SIZE, MOST_POPULAR, RECENTLY_PLAYED, ARTIST, ALBUM }
    enum class AlbumSortOrder { ALPHABETICAL, ARTIST, TRACK_COUNT, LAST_ADDED }
    enum class SortDirection { ASCENDING, DESCENDING }

    private val musicRepository = MusicRepository(
        songDao = DatabaseProvider.getDatabase(application).songDao(),
        playlistDao = DatabaseProvider.getDatabase(application).playlistDao(),
        songHistoryDao = DatabaseProvider.getDatabase(application).songHistoryDao(),
        musicScanner = AndroidMusicScanner(application)
    )
    private val searchUseCase = SearchUseCase(application)

    private val lyricsUseCase = LyricsUseCase(musicRepository, application, viewModelScope)
    private val smartPlaylistUseCase = SmartPlaylistUseCase(musicRepository)
    private val metadataUpdateUseCase = MetadataUpdateUseCase(musicRepository, application, viewModelScope)

    private val metadataUpdateListener = object : MetadataUpdateUseCase.MetadataUpdateListener {
        override fun onPendingWriteIntent(intent: IntentSenderRequest, data: PendingTrackEdit) {
            _pendingWriteIntent.value = intent
            pendingTrackEditData = data
        }

        override fun onUpdateStarted() {
            isPerformingManualUpdate = true
            mediaController?.let { controller ->
                try {
                    val startBundle = Bundle().apply { putBoolean("ENABLED", true) }
                    controller.sendCustomCommand(SessionCommand("SET_METADATA_UPDATING", Bundle.EMPTY), startBundle)
                } catch (e: Exception) {
                    Log.e("MetadataUpdate", "Failed to send update start command", e)
                }
            }
        }

        override fun onUpdateProgress(songId: Long, title: String, artist: String, album: String) {
            // Update local lists immediately in memory
            originalTrackList = originalTrackList.map { song ->
                if (song.id == songId) {
                    song.copy(title = title, artist = artist, album = album)
                } else song
            }

            currentSortedList = currentSortedList?.map { song ->
                if (song.id == songId) {
                    song.copy(title = title, artist = artist, album = album)
                } else song
            }

            _currentQueue.value = _currentQueue.value.map { song ->
                if (song.id == songId) {
                    song.copy(title = title, artist = artist, album = album)
                } else song
            }

            if (_currentPlayingSong.value?.id == songId) {
                _currentPlayingSong.value = _currentPlayingSong.value?.copy(
                    title = title, artist = artist, album = album
                )
            }

            refreshLibraryDisplay()
        }

        override fun onUpdateFinished(data: PendingTrackEdit, refreshLibrary: Boolean) {
            finishMetadataUpdate(data, refreshLibrary)
        }

        override fun getOriginalTrackList(): List<Song> = originalTrackList
        override fun getCurrentPlayingSong(): Song? = _currentPlayingSong.value
    }

    private val _expandPlayerSignal = MutableStateFlow(value = false)
    val expandPlayerSignal: StateFlow<Boolean> = _expandPlayerSignal.asStateFlow()
    fun expandPlayer() { _expandPlayerSignal.value = true }
    fun consumeExpandPlayerSignal() { _expandPlayerSignal.value = false }

    private val _currentSongIndex = MutableStateFlow(-1)
    val currentSongIndex: StateFlow<Int> = _currentSongIndex.asStateFlow()
    
    private var mediaController: MediaController? = null
    private var controllerFuture: ListenableFuture<MediaController>? = null
    private var progressJob: Job? = null
    private var sleepTimerJob: Job? = null
    private var isUserChangingTrack = false
    private var isPerformingManualUpdate = false
    private var mediaStoreObserver: ContentObserver? = null
    private var waveformJob: Job? = null
    
    private val _navigateToTab = MutableStateFlow<Int?>(null)
    val navigateToTab: StateFlow<Int?> = _navigateToTab.asStateFlow()
    
    private val _pendingDeleteIntent = MutableStateFlow<IntentSenderRequest?>(null)
    val pendingDeleteIntent: StateFlow<IntentSenderRequest?> = _pendingDeleteIntent.asStateFlow()
    
    private val _shuffleModeEnabled = MutableStateFlow(false)
    val shuffleModeEnabled: StateFlow<Boolean> = _shuffleModeEnabled.asStateFlow()
    
    private val _songsLibrary = MutableStateFlow<List<Song>>(emptyList())
    val songsLibrary: StateFlow<List<Song>> = _songsLibrary.asStateFlow()
    
    private val _currentQueue = MutableStateFlow<List<Song>>(emptyList())
    val currentQueue: StateFlow<List<Song>> = _currentQueue.asStateFlow()
    
    private val _currentPlayingSong = MutableStateFlow<Song?>(null)
    val currentPlayingSong: StateFlow<Song?> = _currentPlayingSong.asStateFlow()
    
    private val _currentPosition = MutableStateFlow(0)
    val currentPosition: StateFlow<Int> = _currentPosition.asStateFlow()
    
    private val _currentPositionThrottled = MutableStateFlow(0)
    val currentPositionThrottled: StateFlow<Int> = _currentPositionThrottled.asStateFlow()
    
    private val _playbackProgressPct = MutableStateFlow(0)
    val playbackProgressPct: StateFlow<Int> = _playbackProgressPct.asStateFlow()
    
    private val _isPlaying = MutableStateFlow(false)
    val isPlaying: StateFlow<Boolean> = _isPlaying.asStateFlow()
    
    private val _allFolderItems = MutableStateFlow<List<FolderItem>>(emptyList())
    private val _uiFolderItems = MutableStateFlow<List<FolderItem>>(emptyList())
    val uiFolderItems: StateFlow<List<FolderItem>> = _uiFolderItems.asStateFlow()
    
    private val _recentSearches = MutableStateFlow<List<String>>(emptyList())
    val recentSearches: StateFlow<List<String>> = _recentSearches.asStateFlow()

    private val _isLibraryLoading = MutableStateFlow(false)
    val isLibraryLoading: StateFlow<Boolean> = _isLibraryLoading.asStateFlow()

    private val _playbackSpeed = MutableStateFlow(1.0f)
    val playbackSpeed: StateFlow<Float> = _playbackSpeed.asStateFlow()

    private val _stopAfterCurrentTrack = MutableStateFlow(false)
    val stopAfterCurrentTrack: StateFlow<Boolean> = _stopAfterCurrentTrack.asStateFlow()

    private val _isCurrentTrackFavorite = MutableStateFlow(false)
    val isCurrentTrackFavorite: StateFlow<Boolean> = _isCurrentTrackFavorite.asStateFlow()

    private val _favoriteTrackIds = MutableStateFlow<Set<String>>(emptySet())
    val favoriteTrackIds: StateFlow<Set<String>> = _favoriteTrackIds.asStateFlow()

    private val _audioSessionId = MutableStateFlow(-1)
    val audioSessionId: StateFlow<Int> = _audioSessionId.asStateFlow()

    private val _sleepTimerTimeLeft = MutableStateFlow("")
    val sleepTimerTimeLeft: StateFlow<String> = _sleepTimerTimeLeft.asStateFlow()

    private val _isSleepTimerActive = MutableStateFlow(false)
    val isSleepTimerActive: StateFlow<Boolean> = _isSleepTimerActive.asStateFlow()

    private val _currentTrackWaveform = MutableStateFlow<String?>(null)
    val currentTrackWaveform: StateFlow<String?> = _currentTrackWaveform.asStateFlow()

    private val _artworkRefreshSignatures = MutableStateFlow<Map<String, Long>>(emptyMap())
    val artworkRefreshSignatures: StateFlow<Map<String, Long>> = _artworkRefreshSignatures.asStateFlow()

    private val _pendingWriteIntent = MutableStateFlow<IntentSenderRequest?>(null)
    val pendingWriteIntent: StateFlow<IntentSenderRequest?> = _pendingWriteIntent.asStateFlow()

    val allPlaylists = musicRepository.getAllPlaylists()
    val playlistsWithTracks = musicRepository.getPlaylistsWithTracks()

    var lastSearchQuery: String = ""
    private var isSongsLoaded = false

    private val _trackSortOrder = MutableStateFlow(TrackSortOrder.ALPHABETICAL)
    val trackSortOrder: StateFlow<TrackSortOrder> = _trackSortOrder.asStateFlow()

    private val _trackSortDirection = MutableStateFlow(SortDirection.ASCENDING)
    val trackSortDirection: StateFlow<SortDirection> = _trackSortDirection.asStateFlow()

    private val _albumSortOrder = MutableStateFlow(AlbumSortOrder.ALPHABETICAL)
    val albumSortOrder: StateFlow<AlbumSortOrder> = _albumSortOrder.asStateFlow()

    private val _albumSortDirection = MutableStateFlow(SortDirection.ASCENDING)
    val albumSortDirection: StateFlow<SortDirection> = _albumSortDirection.asStateFlow()

    var currentSortOrder: TrackSortOrder
        get() = _trackSortOrder.value
        set(value) { _trackSortOrder.value = value }

    var currentSortDirection: SortDirection
        get() = _trackSortDirection.value
        set(value) { _trackSortDirection.value = value }

    var currentAlbumSortOrder: AlbumSortOrder
        get() = _albumSortOrder.value
        set(value) { _albumSortOrder.value = value }

    var currentAlbumSortDirection: SortDirection
        get() = _albumSortDirection.value
        set(value) { _albumSortDirection.value = value }

    init {
        val p = application.getSharedPreferences("app_prefs", Context.MODE_PRIVATE)
        val savedTrackOrder = p.getString("track_sort_order", TrackSortOrder.ALPHABETICAL.name)
        val trackOrder = runCatching { TrackSortOrder.valueOf(savedTrackOrder!!) }.getOrDefault(TrackSortOrder.ALPHABETICAL)

        val savedTrackDir = p.getString("track_sort_direction", SortDirection.ASCENDING.name)
        val trackDir = runCatching { SortDirection.valueOf(savedTrackDir!!) }.getOrDefault(SortDirection.ASCENDING)

        val savedAlbumOrder = p.getString("album_sort_order", AlbumSortOrder.ALPHABETICAL.name)
        val albumOrder = runCatching { AlbumSortOrder.valueOf(savedAlbumOrder!!) }.getOrDefault(AlbumSortOrder.ALPHABETICAL)

        val savedAlbumDir = p.getString("album_sort_direction", SortDirection.ASCENDING.name)
        val albumDir = runCatching { SortDirection.valueOf(savedAlbumDir!!) }.getOrDefault(SortDirection.ASCENDING)

        _trackSortOrder.value = trackOrder
        _trackSortDirection.value = trackDir
        _albumSortOrder.value = albumOrder
        _albumSortDirection.value = albumDir
    }

    var originalTrackList: List<Song> = emptyList()
    private val persistence = (application as MainApplication).playbackPersistence
    private var pendingDeleteSongId: Long? = null
    private val audioManager = application.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    private var pendingTrackEditData: PendingTrackEdit? = null
    private var pendingBulkTrackEditData: List<PendingTrackEdit>? = null
    
    val currentVibrantColor = MutableStateFlow<Int?>(null)
    val currentOnBgColor = MutableStateFlow<Int?>(null)
    val currentBgColor = MutableStateFlow<Int?>(null)
    private var pendingPlayback: Pair<List<Song>, Int>? = null

    private val _repeatMode = MutableStateFlow(PlaybackService.RepeatState.OFF)
    val repeatMode = _repeatMode.asStateFlow()

    val currentRepeatState: PlaybackService.RepeatState
        get() = _repeatMode.value

    fun loadRecentSearches() { viewModelScope.launch { _recentSearches.value = searchUseCase.getRecentSearches(10) } }
    fun saveSearchQuery(query: String) { viewModelScope.launch { searchUseCase.saveSearchQuery(query); loadRecentSearches() } }

    suspend fun getLyricsForSong(path: String): String? = lyricsUseCase.getLyricsForSong(path)

    fun saveLyrics(path: String, lyrics: String) = lyricsUseCase.saveLyrics(path, lyrics)

    fun deleteLyrics(path: String) = lyricsUseCase.deleteLyrics(path)

    suspend fun clearAllLyrics() {
        val songs = if (originalTrackList.isNotEmpty()) originalTrackList else musicRepository.getSongs(false)
        lyricsUseCase.deleteAllLyrics(songs)
    }

    suspend fun getSongsWithLyrics(): List<Song> {
        val songs = if (originalTrackList.isNotEmpty()) originalTrackList else musicRepository.getSongs(false)
        return lyricsUseCase.getSongsWithLyrics(songs)
    }

    fun deleteLyricsForSongs(songs: List<Song>) {
        songs.forEach { deleteLyrics(it.path) }
    }

    suspend fun searchLyricsOnline(title: String, artist: String): List<LyricsResponse> = lyricsUseCase.searchLyricsOnline(title, artist)

    suspend fun fetchLyricsOnline(title: String, artist: String): LyricsResponse? = lyricsUseCase.fetchLyricsOnline(title, artist)

    fun getPlaylistWithTracks(id: Long) = musicRepository.getPlaylistWithTracks(id)
    fun getRecentlyAddedTracks() = originalTrackList.asSequence().sortedByDescending { it.dateAdded }.take(50).toList()
    suspend fun getMostPopularTracks(): List<Song> {
        val counts = musicRepository.getAllPlayCounts().associateBy { it.songId }
        return originalTrackList.asSequence()
            .filter { (counts[it.id.toString()]?.playCount ?: 0) > 0 }
            .sortedByDescending { counts[it.id.toString()]?.playCount ?: 0 }
            .take(50)
            .toList()
    }

    fun navigateToTab(tabId: Int) { _navigateToTab.value = tabId }
    fun consumeNavigateToTab() { _navigateToTab.value = null }
    fun clearPendingWriteIntent() { _pendingWriteIntent.value = null; pendingTrackEditData = null }

    fun clearPlaybackHistory() {
        viewModelScope.launch {
            musicRepository.clearHistory()
        }
    }

    fun toggleStopAfterCurrentTrack() {
        val next = !_stopAfterCurrentTrack.value
        mediaController?.let { controller ->
            val bundle = Bundle().apply { putBoolean("ENABLED", next) }
            controller.sendCustomCommand(SessionCommand("SET_STOP_AFTER_CURRENT", Bundle.EMPTY), bundle)
            _stopAfterCurrentTrack.value = next
            
            // If enabling "Stop after track", cancel the countdown sleep timer
            if (next) {
                cancelSleepTimer()
            }
        }
    }

    fun toggleFavoriteForCurrentTrack() {
        val song = _currentPlayingSong.value ?: return
        val trackId = song.id.toString()
        val next = !_isCurrentTrackFavorite.value
        viewModelScope.launch(Dispatchers.IO) {
            // 1. Sync with Library Tracks (for waveform/meta)
            val existing = musicRepository.getTrackByIdSync(trackId)
            if (existing == null) {
                musicRepository.upsertTrack(TrackEntity(trackId, song.title, song.artist, next))
            } else {
                musicRepository.updateFavoriteStatus(trackId, next)
            }

            // 2. Manage the "Favorites" Playlist
            val allWithTracks = musicRepository.getPlaylistsWithTracks().first()
            val favPlaylist = allWithTracks.find { it.playlist.name == "Favorites" }

            if (next) {
                val pId = favPlaylist?.playlist?.id ?: musicRepository.createPlaylist(Playlist(name = "Favorites"))
                musicRepository.addTrackToPlaylist(PlaylistTrackCrossRef(pId, trackId))
            } else {
                favPlaylist?.let { musicRepository.removeTrackFromPlaylist(it.playlist.id, trackId) }
            }

            withContext(Dispatchers.Main) { _isCurrentTrackFavorite.value = next }
        }
    }

    fun createNewPlaylist(name: String) { viewModelScope.launch(Dispatchers.IO) { musicRepository.createPlaylist(Playlist(name = name)) } }

    fun addTrackToPlaylist(trackId: String, playlistId: Long) {
        viewModelScope.launch(Dispatchers.IO) {
            val song = originalTrackList.find { it.id.toString() == trackId }
            if (song != null) {
                val existing = musicRepository.getTrackByIdSync(trackId)
                if (existing == null) {
                    musicRepository.upsertTrack(TrackEntity(trackId, song.title, song.artist))
                }
            }

            musicRepository.addTrackToPlaylist(PlaylistTrackCrossRef(playlistId, trackId))
            
            val playlists = musicRepository.getAllPlaylists().first()
            if (playlists.find { it.id == playlistId }?.name == "Favorites") {
                if (song != null) {
                    musicRepository.updateFavoriteStatus(trackId, true)
                }
            }
            
            if ((trackId == _currentPlayingSong.value?.id?.toString()) && (playlists.find { it.id == playlistId }?.name == "Favorites")) {
                withContext(Dispatchers.Main) { _isCurrentTrackFavorite.value = true }
            }
        }
    }

    fun deletePlaylist(id: Long) { viewModelScope.launch(Dispatchers.IO) { musicRepository.deletePlaylist(id) } }
    fun removeTrackFromPlaylist(pId: Long, tId: String) {
        viewModelScope.launch(Dispatchers.IO) {
            musicRepository.removeTrackFromPlaylist(pId, tId)

            // If removing from Favorites, update the isFavorite flag in library_tracks
            val playlists = musicRepository.getAllPlaylists().first()
            if (playlists.find { it.id == pId }?.name == "Favorites") {
                musicRepository.updateFavoriteStatus(tId, false)
            }

            if (tId == _currentPlayingSong.value?.id?.toString()) withContext(Dispatchers.Main) { _isCurrentTrackFavorite.value = false }
        }
    }

    fun permanentUpdateSongMetadata(context: Context, songId: Long, title: String, artist: String, album: String, year: String = "", genre: String = "", artUri: Uri? = null, path: String = "") {
        metadataUpdateUseCase.permanentUpdateSongMetadata(context, songId, title, artist, album, year, genre, artUri, path, metadataUpdateListener)
    }

    private fun finishMetadataUpdate(data: PendingTrackEdit, refresh: Boolean) {
        viewModelScope.launch(Dispatchers.Main) {
            mediaController?.let { controller ->
                // 1. End the "updating" state to resume transitions
                val endBundle = Bundle().apply { putBoolean("ENABLED", false) }
                controller.sendCustomCommand(SessionCommand("SET_METADATA_UPDATING", Bundle.EMPTY), endBundle)
                
                // 2. Explicitly tell the service to refresh the current item's metadata
                controller.sendCustomCommand(SessionCommand("REFRESH_CURRENT_METADATA", Bundle.EMPTY), Bundle.EMPTY)
                
                // 3. Force a local sync immediately
                delay(200.milliseconds)
                syncServiceStateWithLiveData()
            }
            
            // Update local originalTrackList if possible to show changes immediately
            originalTrackList = originalTrackList.map { song ->
                if (song.id == data.songId) {
                    song.copy(
                        title = data.title,
                        artist = data.artist,
                        album = data.album,
                        path = data.path.ifEmpty { song.path }
                    )
                } else song
            }
            
            // Update current queue and refresh library lists
            _currentQueue.value = _currentQueue.value.map { song ->
                if (song.id == data.songId) {
                    song.copy(
                        title = data.title,
                        artist = data.artist,
                        album = data.album,
                        path = data.path.ifEmpty { song.path }
                    )
                } else song
            }
            
            val normPath = PlatformUtils.normalizePath(data.path)
            val newTimestamp = System.currentTimeMillis()
            _artworkRefreshSignatures.value = _artworkRefreshSignatures.value + (normPath to newTimestamp)

            // Ensure currentSortedList is also updated by re-sorting, which also calls refreshLibraryDisplay()
            sortTracks(currentSortOrder, currentSortDirection)
            
            // Also update current song if it's the one being edited
            if (_currentPlayingSong.value?.id == data.songId) {
                _currentPlayingSong.value = _currentPlayingSong.value?.copy(
                    title = data.title,
                    artist = data.artist,
                    album = data.album
                )
            }

            if (refresh) {
                delay(2.5.seconds) // Give MediaStore and MediaScanner more time
                loadSongs(force = true)
            }
            isPerformingManualUpdate = false
        }
    }

    private val audioDeviceCallback = object : AudioDeviceCallback() {
        override fun onAudioDevicesAdded(addedDevices: Array<out AudioDeviceInfo>?) { evaluateActiveAudioDevice() }
        override fun onAudioDevicesRemoved(removedDevices: Array<out AudioDeviceInfo>?) { evaluateActiveAudioDevice() }
    }

    init {
        viewModelScope.launch(Dispatchers.Main) {
            val context = getApplication<Application>().applicationContext
            
            // 2. Load library in parallel
            withContext(Dispatchers.IO) {
                loadSongsInternal()
                loadFoldersInternal()
            }

            val observer = object : ContentObserver(Handler(Looper.getMainLooper())) {
                override fun onChange(selfChange: Boolean, uri: Uri?) {
                    if (isPerformingManualUpdate) {
                        Log.d("PlaybackViewModel", "Skipping observer reload during manual update")
                        return
                    }
                    viewModelScope.launch { delay(2500.milliseconds); isSongsLoaded = false; loadSongs() }
                }
            }
            mediaStoreObserver = observer
            context.contentResolver.registerContentObserver(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, true, observer)

            launch {
                musicRepository.getPlaylistsWithTracks().collect { playlists ->
                    val favs = playlists.find { it.playlist.name == "Favorites" }
                _favoriteTrackIds.value = favs?.tracks?.asSequence()?.map { it.id }?.toSet() ?: emptySet()
                    
                    // Also update isCurrentTrackFavorite if current song is set
                    _currentPlayingSong.value?.let { song ->
                        _isCurrentTrackFavorite.value = _favoriteTrackIds.value.contains(song.id.toString())
                    }
                }
            }
            launch {
                audioSessionId.collect { id ->
                    if (id != -1) {
                        EqualizerManager.initEqualizer(context, id)
                    }
                }
            }
            loadRecentSearches()
        }
        audioManager.registerAudioDeviceCallback(audioDeviceCallback, Handler(Looper.getMainLooper()))
        evaluateActiveAudioDevice()
    }

    private fun evaluateActiveAudioDevice() {
        val devices = audioManager.getDevices(AudioManager.GET_DEVICES_OUTPUTS)
        var route = EqualizerManager.PROFILE_SPEAKER
        for (d in devices) {
            if (d.isSink) {
                when (d.type) {
                    AudioDeviceInfo.TYPE_BLUETOOTH_A2DP, AudioDeviceInfo.TYPE_BLUETOOTH_SCO -> { route = EqualizerManager.PROFILE_BLUETOOTH; break }
                    AudioDeviceInfo.TYPE_WIRED_HEADSET, AudioDeviceInfo.TYPE_WIRED_HEADPHONES, AudioDeviceInfo.TYPE_USB_HEADSET -> { route = EqualizerManager.PROFILE_WIRED; break }
                    else -> { /* Other devices use default PROFILE_SPEAKER */ }
                }
            }
        }
        EqualizerManager.updateDetectedHardwareRoute(route)
    }

    fun forceScanMediaLibrary() { viewModelScope.launch(Dispatchers.IO) { _isLibraryLoading.value = true; isSongsLoaded = false; loadSongsInternal() } }

    fun deleteTrackFromStorage(context: Context, song: Song) {
        viewModelScope.launch(Dispatchers.IO) {
            val uri = ContentUris.withAppendedId(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, song.id)
            pendingDeleteSongId = song.id
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                val intent = MediaStore.createDeleteRequest(context.contentResolver, listOf(uri))
                _pendingDeleteIntent.value = IntentSenderRequest.Builder(intent.intentSender).build()
            } else { context.contentResolver.delete(uri, null, null); withContext(Dispatchers.Main) { processTrackDeletion() } }
        }
    }

    fun requestDelete(intent: PendingIntent) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            _pendingDeleteIntent.value = IntentSenderRequest.Builder(intent.intentSender).build()
        }
    }

    fun requestEdit(intent: PendingIntent) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            _pendingWriteIntent.value = IntentSenderRequest.Builder(intent.intentSender).build()
        }
    }

    fun consumePendingDeleteIntent() { _pendingDeleteIntent.value = null; pendingDeleteSongId = null }

    fun processTrackDeletion() {
        val id = pendingDeleteSongId ?: return
        val controller = mediaController ?: return
        val q = _currentQueue.value
        val idx = q.indexOfFirst { it.id == id }
        
        // Delete associated lyrics first
        originalTrackList.find { it.id == id }?.let { deleteLyrics(it.path) }
        
        if (idx != -1) {
            try { 
                if (idx < controller.mediaItemCount) controller.removeMediaItem(idx) 
            } catch (e: Exception) {
                Log.e("PlaybackViewModel", "Error removing track from controller", e)
            }
            val nq = q.filter { it.id != id }
            _currentQueue.value = nq
        }
        
        originalTrackList = originalTrackList.filter { it.id != id }
        currentSortedList = currentSortedList?.filter { it.id != id }
        refreshLibraryDisplay(); consumePendingDeleteIntent(); syncServiceStateWithLiveData()
    }

    private fun startProgressPolling() {
        progressJob?.cancel()
        progressJob = viewModelScope.launch(Dispatchers.Main) {
            var lastSavedPos = -1
            var lastThrottledUpdate = 0L
            while (true) {
                val controller = mediaController
                if (controller != null && controller.isPlaying) {
                    val pos = controller.currentPosition.toInt(); val dur = controller.duration.toInt()
                    _currentPosition.value = pos
                    
                    val now = System.currentTimeMillis()
                    if (now - lastThrottledUpdate >= 500) {
                        _currentPositionThrottled.value = pos
                        lastThrottledUpdate = now
                    }

                    if (dur > 0) _playbackProgressPct.value = ((pos * 100f) / dur.toFloat()).toInt().coerceIn(0, 100)
                    if (abs(pos - lastSavedPos) > 5000) { lastSavedPos = pos; viewModelScope.launch(Dispatchers.IO) { persistence.saveLastPosition(pos.toLong()) } }
                }
                delay(32.milliseconds) // Faster polling for smooth lyrics shading and UI progress
            }
        }
    }

    private fun initController(context: Context) {
        try {
            val sessionToken = SessionToken(context, ComponentName(context, PlaybackService::class.java))
            controllerFuture = MediaController.Builder(context, sessionToken).buildAsync()
            controllerFuture?.addListener({
                try {
                    mediaController = controllerFuture?.get()
                    mediaController?.addListener(
                        object : Player.Listener {
                            override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) { 
                                syncServiceStateWithLiveData()
                                refreshAudioSessionId()
                            }
                            override fun onPlaybackStateChanged(playbackState: Int) { syncServiceStateWithLiveData() }
                            override fun onIsPlayingChanged(isPlaying: Boolean) { syncServiceStateWithLiveData() }
                            override fun onPositionDiscontinuity(oldPosition: Player.PositionInfo, newPosition: Player.PositionInfo, reason: Int) { syncServiceStateWithLiveData() }
                            override fun onShuffleModeEnabledChanged(shuffleModeEnabled: Boolean) { syncServiceStateWithLiveData() }
                            override fun onRepeatModeChanged(repeatMode: Int) { syncServiceStateWithLiveData() }
                            override fun onTimelineChanged(timeline: Timeline, reason: Int) {
                                if (reason == Player.TIMELINE_CHANGE_REASON_PLAYLIST_CHANGED) {
                                    syncServiceStateWithLiveData()
                                }
                            }
                        },
                    )

                    syncServiceStateWithLiveData()
                    refreshAudioSessionId()
                    
                    // If there's a pending playback from an early click, execute it now
                    pendingPlayback?.let { (list, index) ->
                        Log.i("PlaybackViewModel", "Executing pending playback for ${list.getOrNull(index)?.title}")
                        playSong(list, index)
                        pendingPlayback = null
                    } ?: run {
                        restoreLastPlayedTrack()
                    }
                    
                    startProgressPolling()
                } catch (t: Throwable) {
                    Log.e("PlaybackViewModel", "Error after MediaController connection", t)
                }
            }, MoreExecutors.directExecutor())
        } catch (t: Throwable) {
            Log.e("PlaybackViewModel", "Failed to initialize MediaController", t)
        }
    }

    private fun refreshAudioSessionId() {
        val controller = mediaController ?: return
        val command = SessionCommand("GET_AUDIO_SESSION_ID", Bundle.EMPTY)
        val future = controller.sendCustomCommand(command, Bundle.EMPTY)
        future.addListener({
            try {
                val result = future.get()
                if (result.resultCode == SessionResult.RESULT_SUCCESS) {
                    val sessionId = result.extras.getInt("AUDIO_SESSION_ID", -1)
                    if (sessionId != -1) {
                        _audioSessionId.value = sessionId
                    }
                }
            } catch (e: Exception) {
                Log.e("PlaybackViewModel", "Error refreshing session ID", e)
            }
        }, MoreExecutors.directExecutor())
    }

    fun bindToPlaybackService(context: Context) {
        initController(context)
    }

    private fun syncServiceStateWithLiveData() {
        val controller = mediaController ?: return
        if (isUserChangingTrack) return
        
        // 1. Restore queue if empty (e.g., after app restart with background service running)
        if (_currentQueue.value.isEmpty() && controller.mediaItemCount > 0) {
            val restoredQueue = mutableListOf<Song>()
            for (i in 0 until controller.mediaItemCount) {
                val item = controller.getMediaItemAt(i)
                val metadata = item.mediaMetadata
                val extras = metadata.extras ?: Bundle.EMPTY
                restoredQueue.add(Song(
                    id = extras.getLong("id", item.mediaId.toLongOrNull() ?: -1L),
                    title = metadata.title?.toString() ?: "Unknown",
                    artist = metadata.artist?.toString() ?: "Unknown",
                    path = extras.getString("path", ""),
                    duration = extras.getInt("duration", 0),
                    albumId = extras.getLong("albumId", -1L),
                    album = metadata.albumTitle?.toString() ?: "Unknown"
                ))
            }
            _currentQueue.value = restoredQueue
        }

        // 2. Ensure player is prepared if it has items but is idle
        if (controller.playbackState == Player.STATE_IDLE && controller.mediaItemCount > 0) {
            controller.prepare()
        }

        val currentMediaItem = controller.currentMediaItem
        val currentMediaId = currentMediaItem?.mediaId
        val song = _currentQueue.value.find { it.id.toString() == currentMediaId }
        
        Log.d("PlaybackViewModel", "Sync State: mediaId=$currentMediaId, songFound=${song != null}, queueSize=${_currentQueue.value.size}")

        if (song != null) {
            _currentPlayingSong.value = song
        } else if (currentMediaItem == null) {
            _currentPlayingSong.value = null
        }
        
        _currentSongIndex.value = controller.currentMediaItemIndex
        _isPlaying.value = controller.isPlaying
        _shuffleModeEnabled.value = controller.shuffleModeEnabled
        _playbackSpeed.value = controller.playbackParameters.speed
        _currentPosition.value = controller.currentPosition.toInt()
        
        _repeatMode.value = when(controller.repeatMode) {
            Player.REPEAT_MODE_ONE -> PlaybackService.RepeatState.ONE
            Player.REPEAT_MODE_ALL -> PlaybackService.RepeatState.ALL
            else -> PlaybackService.RepeatState.OFF
        }
        
        song?.let { songItem ->
            waveformJob?.cancel()
            waveformJob = viewModelScope.launch {
                val fav = musicRepository.isTrackFavorite(songItem.id.toString())
                val entity = musicRepository.getTrackByIdSync(songItem.id.toString())
                _isCurrentTrackFavorite.value = fav
                _currentTrackWaveform.value = entity?.waveformAmplitudes

                if (entity?.waveformAmplitudes == null) {
                    val waveform = WaveformExtractor.extractWaveform(getApplication(), songItem.path.toUri())
                    if (waveform != null) {
                        _currentTrackWaveform.value = waveform
                        val energy = MusicEnergyAnalyzer.analyze(waveform)
                        val updatedEntity = (entity ?: TrackEntity(
                            id = songItem.id.toString(),
                            title = songItem.title,
                            artist = songItem.artist
                        )).copy(waveformAmplitudes = waveform, energyScore = energy)
                        musicRepository.upsertTrack(updatedEntity)
                    }
                } else if (entity.energyScore == -1) {
                    // Back-fill energy score if waveform exists but energy doesn't
                    val energy = MusicEnergyAnalyzer.analyze(entity.waveformAmplitudes)
                    musicRepository.upsertTrack(entity.copy(energyScore = energy))
                }
            }
        }
    }

    fun playSong(list: List<Song>, index: Int, playImmediately: Boolean = true, shouldExpand: Boolean = false) {
        Log.d("PlaybackViewModel", "playSong called: index=$index, listSize=${list.size}, playImmediately=$playImmediately")
        
        // 1. Update UI state flows immediately for maximum responsiveness
        _currentQueue.value = list
        _currentPlayingSong.value = if (index in list.indices) list[index] else null
        _currentSongIndex.value = index
        _isPlaying.value = playImmediately
        if (shouldExpand) expandPlayer()

        val controller = mediaController
        if (controller == null) {
            Log.w("PlaybackViewModel", "MediaController not ready, queuing playback request")
            pendingPlayback = Pair(list, index)
            return
        }

        // 2. Optimization: If queue is already this list, just seek
        val currentQueueIds = (0 until controller.mediaItemCount).map { controller.getMediaItemAt(it).mediaId }
        val newQueueIds = list.map { it.id.toString() }
        
        if (currentQueueIds == newQueueIds) {
            if (controller.currentMediaItemIndex != index) {
                controller.seekTo(index, C.TIME_UNSET)
            }
            if (playImmediately) {
                controller.prepare()
                controller.play()
            }
            return
        }

        // 3. Set items and start playback
        val items = list.map { it.toMediaItem() }
        controller.setMediaItems(items, index, C.TIME_UNSET)
        controller.prepare()
        controller.playWhenReady = playImmediately
        
        viewModelScope.launch(Dispatchers.IO) {
            persistence.saveCurrentQueue(list.map { it.path })
        }
    }

    fun insertIntoQueueNext(song: Song) {
        mediaController?.let { controller ->
            val list = _currentQueue.value.toMutableList()
            list.remove(song)
            val idx = if (_currentSongIndex.value == -1) 0 else (_currentSongIndex.value + 1).coerceAtMost(list.size)
            list.add(idx, song)
            _currentQueue.value = list
            controller.addMediaItem(idx.coerceAtMost(controller.mediaItemCount), song.toMediaItem())
            viewModelScope.launch(Dispatchers.IO) { persistence.saveCurrentQueue(list.map { it.path }) }
        }
    }

    fun appendToQueue(song: Song) {
        val list = _currentQueue.value.toMutableList()
        val existingIndex = list.indexOfFirst { it.id == song.id }
        
        if (existingIndex != -1) {
            // Move to end if already present
            if (existingIndex == list.size - 1) return // Already at the end
            
            val s = list.removeAt(existingIndex)
            list.add(s)
            _currentQueue.value = list
            mediaController?.moveMediaItem(existingIndex, list.size - 1)
            viewModelScope.launch(Dispatchers.IO) { persistence.saveCurrentQueue(list.map { it.path }) }
            return
        }
        
        list.add(song)
        _currentQueue.value = list
        mediaController?.addMediaItem(song.toMediaItem())
        viewModelScope.launch(Dispatchers.IO) { persistence.saveCurrentQueue(list.map { it.path }) }
    }

    fun moveTrackInQueue(from: Int, to: Int) {
        if (from == to) return
        val list = _currentQueue.value.toMutableList()
        val oldIndex = _currentSongIndex.value
        
        if (from in list.indices && to in list.indices) {
            val s = list.removeAt(from)
            list.add(to, s)
            
            // Predict new index if the playing song moved or was affected by the move
            val newIndex = when (oldIndex) {
                from -> to
                in (from + 1)..to -> oldIndex - 1
                in to..<from -> oldIndex + 1
                else -> oldIndex
            }
            
            _currentSongIndex.value = newIndex
            _currentQueue.value = list
        }
    }

    fun reorderQueueTracks(from: Int, to: Int) {
        if (from == to) return
        
        // Sync with media controller
        mediaController?.let { controller ->
            if (from < controller.mediaItemCount && to < controller.mediaItemCount) {
                controller.moveMediaItem(from, to)
                
                // Force a re-sync of current index and playing song from the service's truth
                viewModelScope.launch {
                    delay(200)
                    syncServiceStateWithLiveData()
                }
            }
        }
        
        viewModelScope.launch(Dispatchers.IO) {
            persistence.saveCurrentQueue(_currentQueue.value.map { it.path })
        }
    }

    fun deleteTrackFromQueue(index: Int) {
        mediaController?.let { controller ->
            controller.removeMediaItem(index)
            val list = _currentQueue.value.toMutableList()
            if (index in list.indices) {
                list.removeAt(index)
                _currentQueue.value = list
                viewModelScope.launch(Dispatchers.IO) { persistence.saveCurrentQueue(list.map { it.path }) }
            }
            _currentSongIndex.value = controller.currentMediaItemIndex
        }
    }

    fun shuffleAllTracks(list: List<Song>) {
        if (list.isEmpty()) return
        val rand = list.shuffled()
        playSong(rand, 0, true)
        expandPlayer()
    }

    private fun restoreLastPlayedTrack() {
        val controller = mediaController ?: return
        if (controller.mediaItemCount > 0) return
        viewModelScope.launch {
            // Wait for library to be loaded, but with a timeout
            var timeout = 0
            while (!isSongsLoaded && timeout < 20) { 
                delay(500)
                timeout++
            }
            
            val savedQueuePaths = persistence.getSavedQueuePaths()
            val lastPath = persistence.lastSongPath.firstOrNull()
            val lastPos = persistence.lastPosition.firstOrNull() ?: 0L
            
            if (savedQueuePaths.isNotEmpty()) {
                val restoredQueue = mutableListOf<Song>()
                for (path in savedQueuePaths) {
                    val normSaved = PlatformUtils.normalizePath(path)
                    // 1. Direct path match
                    var found = originalTrackList.find { PlatformUtils.normalizePath(it.path) == normSaved }
                    
                    // 2. Fallback: Filename match (handles moves between folders)
                    if (found == null) {
                        val fileName = File(path).name
                        found = originalTrackList.find { File(it.path).name == fileName }
                    }
                    
                    found?.let { restoredQueue.add(it) }
                }
                
                if (restoredQueue.isNotEmpty()) {
                    var restoredIdx = 0
                    if (lastPath != null) {
                        val normLast = PlatformUtils.normalizePath(lastPath)
                        val idx = restoredQueue.indexOfFirst { PlatformUtils.normalizePath(it.path) == normLast }
                        if (idx != -1) restoredIdx = idx
                        else {
                            // Fallback to filename for last track index
                            val lastFileName = File(lastPath).name
                            val idx2 = restoredQueue.indexOfFirst { File(it.path).name == lastFileName }
                            if (idx2 != -1) restoredIdx = idx2
                        }
                    }
                    
                    playSong(restoredQueue, restoredIdx, false)
                    controller.seekTo(restoredIdx, lastPos)
                    syncServiceStateWithLiveData()
                    return@launch
                }
            }

            // Fallback to library restoration if no saved queue or all paths failed
            if (originalTrackList.isNotEmpty() && lastPath != null) {
                val normalizedPath = PlatformUtils.normalizePath(lastPath)
                val idx = originalTrackList.indexOfFirst { PlatformUtils.normalizePath(it.path) == normalizedPath }
                
                if (idx != -1) {
                    playSong(originalTrackList, idx, false)
                    controller.seekTo(idx, lastPos)
                    syncServiceStateWithLiveData()
                } else {
                    playSong(originalTrackList, 0, false)
                }
            } else if (originalTrackList.isNotEmpty()) {
                playSong(originalTrackList, 0, false)
            }
        }
    }

    fun togglePlayPause() {
        mediaController?.let {
            if (it.isPlaying) it.pause() else it.play()
            syncServiceStateWithLiveData()
        }
    }
    fun pausePlayback() {
        mediaController?.pause()
        syncServiceStateWithLiveData()
    }

    fun playNextSong() {
        mediaController?.let {
            if (it.hasNextMediaItem()) it.seekToNextMediaItem()
            syncServiceStateWithLiveData()
        }
    }
    fun playPreviousSong() {
        mediaController?.let {
            if (it.currentPosition > 3000) {
                it.seekTo(0)
            } else if (it.hasPreviousMediaItem()) {
                it.seekToPreviousMediaItem()
            } else {
                it.seekTo(0)
            }
            syncServiceStateWithLiveData()
        }
    }
    fun seekTo(position: Int) { mediaController?.seekTo(position.toLong()) }
    fun setShuffleEnabled(enabled: Boolean) {
        _shuffleModeEnabled.value = enabled
        mediaController?.let {
            it.shuffleModeEnabled = enabled
        }
    }
    fun setRepeatState(state: PlaybackService.RepeatState) {
        _repeatMode.value = state
        mediaController?.let {
            it.repeatMode = when(state) {
                PlaybackService.RepeatState.ONE -> Player.REPEAT_MODE_ONE
                PlaybackService.RepeatState.ALL -> Player.REPEAT_MODE_ALL
                else -> Player.REPEAT_MODE_OFF
            }
        }
    }

    fun setPlaybackSpeed(speed: Float) {
        mediaController?.let {
            it.setPlaybackSpeed(speed)
            _playbackSpeed.value = speed
        }
    }

    fun fastForward(ms: Int) {
        mediaController?.let {
            val newPos = (it.currentPosition + ms).coerceAtMost(it.duration)
            it.seekTo(newPos)
        }
    }

    fun fastRewind(ms: Int) {
        mediaController?.let {
            val newPos = (it.currentPosition - ms).coerceAtLeast(0)
            it.seekTo(newPos)
        }
    }

    fun playMoreLikeThis(song: Song) {
        viewModelScope.launch(Dispatchers.Default) {
            val candidates = originalTrackList.filter { it.id != song.id }
            if (candidates.isEmpty()) {
                withContext(Dispatchers.Main) {
                    playSong(listOf(song), 0, true)
                    expandPlayer()
                }
                return@launch
            }

            fun calculateSimilarityScore(candidate: Song): Int {
                var score = 1

                if (song.genre.isNotBlank() && candidate.genre.isNotBlank()) {
                    if (song.genre.equals(candidate.genre, ignoreCase = true)) {
                        score += 10
                    } else if (song.genre.contains(candidate.genre, ignoreCase = true) || candidate.genre.contains(song.genre, ignoreCase = true)) {
                        score += 6
                    }
                }

                if (song.artist.isNotBlank() && candidate.artist.equals(song.artist, ignoreCase = true)) {
                    score += 8
                }
                if (song.album.isNotBlank() && candidate.album.equals(song.album, ignoreCase = true)) {
                    score += 5
                }

                val songDir = File(song.path).parent
                val candidateDir = File(candidate.path).parent
                if (songDir != null && songDir == candidateDir) {
                    score += 4
                }

                val durationDiff = kotlin.math.abs(song.duration - candidate.duration)
                if (durationDiff <= 45000) {
                    score += 3
                }

                return score
            }

            val scoredCandidates = candidates.map { candidate -> candidate to calculateSimilarityScore(candidate) }
                .sortedByDescending { it.second }

            val topMatches = scoredCandidates.take(30).map { it.first }.shuffled()

            withContext(Dispatchers.Main) {
                playSong(listOf(song) + topMatches, 0, true)
                expandPlayer()
            }
        }
    }

    fun startSleepTimer(min: Int) {
        cancelSleepTimer() // Cancel any existing timer before starting a new one
        
        // If starting a countdown timer, disable "Stop after current track" to avoid confusion
        if (_stopAfterCurrentTrack.value) {
            disableStopAfterCurrentTrack()
        }
        
        _isSleepTimerActive.value = true
        sleepTimerJob = viewModelScope.launch {
            var s = min * 60
            var fadeTriggered = false
            while (s > 0) {
                _sleepTimerTimeLeft.value = String.format(Locale.getDefault(), "%02d:%02d", s / 60, s % 60)
                
                if (s == 60 && !fadeTriggered) {
                    fadeTriggered = true
                    mediaController?.sendCustomCommand(SessionCommand("START_SLEEP_FADE", Bundle.EMPTY), Bundle.EMPTY)
                }

                delay(1000)
                s--
            }
            _isSleepTimerActive.value = false
            togglePlayPause()
        }
    }

    fun cancelSleepTimer() { 
        sleepTimerJob?.cancel()
        _isSleepTimerActive.value = false
        _sleepTimerTimeLeft.value = ""
        mediaController?.sendCustomCommand(SessionCommand("CANCEL_SLEEP_FADE", Bundle.EMPTY), Bundle.EMPTY)
    }

    private fun disableStopAfterCurrentTrack() {
        mediaController?.let { controller ->
            val bundle = Bundle().apply { putBoolean("ENABLED", false) }
            controller.sendCustomCommand(SessionCommand("SET_STOP_AFTER_CURRENT", Bundle.EMPTY), bundle)
            _stopAfterCurrentTrack.value = false
        }
    }

    fun loadFolders() { viewModelScope.launch(Dispatchers.IO) { loadFoldersInternal() } }
    private suspend fun loadFoldersInternal() { 
        val items = musicRepository.getFolderItems()
        _allFolderItems.value = items
        refreshLibraryDisplay()
    }

    fun loadSongs(force: Boolean = false) { viewModelScope.launch(Dispatchers.IO) { loadSongsInternal(force) } }
    private suspend fun loadSongsInternal(force: Boolean = false) {
        if (!force && isSongsLoaded && originalTrackList.isNotEmpty()) return
        _isLibraryLoading.value = true
        try {
            val p = getApplication<Application>().getSharedPreferences("app_prefs", Context.MODE_PRIVATE)
            val s = musicRepository.getSongs(p.getBoolean("filter_short_audio", true))
            originalTrackList = s
            withContext(Dispatchers.Main) { 
                _songsLibrary.value = s
                sortTracks(currentSortOrder, currentSortDirection) 
            }
        } finally { isSongsLoaded = true; _isLibraryLoading.value = false; loadFoldersInternal(); startAutoMetadataImprovement() }
    }

    fun repairUnknownMetadata(context: Context, songs: List<Song>, onProgress: (Int, Int, String) -> Unit, onComplete: (Int) -> Unit) {
        metadataUpdateUseCase.repairUnknownMetadata(context, songs, onProgress, onComplete, metadataUpdateListener)
    }

    private fun startAutoMetadataImprovement() {
        metadataUpdateUseCase.startAutoMetadataImprovement(metadataUpdateListener)
    }

    fun applyFilterAndSearch(q: String) { lastSearchQuery = q; refreshLibraryDisplay() }

    private fun refreshLibraryDisplay() {
        viewModelScope.launch(Dispatchers.Default) { 
            _songsLibrary.value = searchUseCase.filterSongs(currentSortedList ?: originalTrackList, lastSearchQuery) 
            _uiFolderItems.value = if (lastSearchQuery.isBlank()) {
                _allFolderItems.value
            } else {
                _allFolderItems.value.filter { it.folderName.contains(lastSearchQuery, true) }
            }
        }
    }

    private var currentSortedList: List<Song>? = null
    private var sortJob: Job? = null
    fun sortTracks(c: TrackSortOrder, d: SortDirection) {
        _trackSortOrder.value = c
        _trackSortDirection.value = d
        val p = getApplication<Application>().getSharedPreferences("app_prefs", Context.MODE_PRIVATE)
        p.edit()
            .putString("track_sort_order", c.name)
            .putString("track_sort_direction", d.name)
            .apply()

        sortJob?.cancel()
        sortJob = viewModelScope.launch(Dispatchers.Default) {
            val s = when (c) {
                TrackSortOrder.ALPHABETICAL -> originalTrackList.sortedBy { it.title.lowercase() }
                TrackSortOrder.ARTIST -> originalTrackList.sortedBy { it.artist.lowercase() }
                TrackSortOrder.ALBUM -> originalTrackList.sortedBy { it.album.lowercase() }
                TrackSortOrder.LAST_ADDED -> originalTrackList.sortedBy { it.dateAdded }
                TrackSortOrder.SIZE -> originalTrackList.sortedBy { it.size }
                TrackSortOrder.MOST_POPULAR -> {
                    val counts = musicRepository.getAllPlayCounts().associateBy { it.songId }
                    originalTrackList.sortedByDescending { counts[it.id.toString()]?.playCount ?: 0 }
                }
                TrackSortOrder.RECENTLY_PLAYED -> {
                    val timestamps = musicRepository.getLastPlayedTimestamps().associateBy { it.songId }
                    originalTrackList.sortedByDescending { timestamps[it.id.toString()]?.lastPlayed ?: 0L }
                }
            }
            currentSortedList = if (d == SortDirection.DESCENDING) s.reversed() else s
            refreshLibraryDisplay()
        }
    }

    fun sortAlbums(c: AlbumSortOrder, d: SortDirection) {
        _albumSortOrder.value = c
        _albumSortDirection.value = d
        val p = getApplication<Application>().getSharedPreferences("app_prefs", Context.MODE_PRIVATE)
        p.edit()
            .putString("album_sort_order", c.name)
            .putString("album_sort_direction", d.name)
            .apply()
    }

    fun toggleFolderBlacklist(path: String, white: Boolean) {
        viewModelScope.launch(Dispatchers.IO) { 
            musicRepository.toggleFolderBlacklist(path, white)
            
            // Sync with allowed list if a whitelist is active
            val allowed = musicRepository.getAllowedFolderUris()
            if (white) {
                // If user included this folder, ensure it's in the allowed list
                if (!musicRepository.isPathInAllowedFolders(path, allowed)) {
                    musicRepository.addAllowedFolderUri(path)
                }
            }
            
            isSongsLoaded = false
            loadSongsInternal(force = true) 
        }
    }

    suspend fun downloadImageToCache(context: Context, imageUrl: String): Uri? = metadataUpdateUseCase.downloadImageToCache(context, imageUrl)

    fun refreshAudioSettings() {
        mediaController?.sendCustomCommand(SessionCommand("REFRESH_SETTINGS", Bundle.EMPTY), Bundle.EMPTY)
    }

    fun setReverbPreset(preset: Int) {
        mediaController?.let { controller ->
            val bundle = Bundle().apply { putInt("PRESET", preset) }
            controller.sendCustomCommand(SessionCommand("SET_REVERB", Bundle.EMPTY), bundle)
        }
    }
    private val _settingsChangedSignal = MutableStateFlow(0L)
    val settingsChangedSignal = _settingsChangedSignal.asStateFlow()

    fun notifySettingsChanged() {
        _settingsChangedSignal.value = System.currentTimeMillis()
        refreshAudioSettings()
    }

    suspend fun getMorningMix(): List<Song> = smartPlaylistUseCase.getMorningMix(originalTrackList)

    suspend fun getTopArtistsMix(): List<Song> = smartPlaylistUseCase.getTopArtistsMix(originalTrackList)

    suspend fun getForgottenFavorites(): List<Song> = smartPlaylistUseCase.getForgottenFavorites(originalTrackList)

    suspend fun getDiscoverMix(): List<Song> = smartPlaylistUseCase.getDiscoverMix(originalTrackList)

    suspend fun getGenreMix(genre: String): List<Song> = smartPlaylistUseCase.getGenreMix(originalTrackList, genre)

    suspend fun getMoodMix(mood: String): List<Song> = smartPlaylistUseCase.getMoodMix(originalTrackList, mood)

    fun requestBulkMetadataUpdate(context: Context, updates: List<PendingTrackEdit>) {
        metadataUpdateUseCase.requestBulkMetadataUpdate(context, updates, metadataUpdateListener) { intent ->
            _pendingWriteIntent.value = intent
            pendingBulkTrackEditData = updates
        }
    }

    fun setAsRingtone(context: Context, song: Song) {
        metadataUpdateUseCase.setAsRingtone(context, song)
    }

    fun executePendingWrite(context: Context) {
        metadataUpdateUseCase.executePendingWrite(context, pendingTrackEditData, pendingBulkTrackEditData, metadataUpdateListener) {
            consumePendingWriteIntent()
        }
    }

    fun consumePendingWriteIntent() { 
        _pendingWriteIntent.value = null
        pendingTrackEditData = null
        pendingBulkTrackEditData = null
    }

    override fun onCleared() {
        // 1. Release Media3 Controller
        controllerFuture?.let { future ->
            MediaController.releaseFuture(future)
        }
        mediaController = null
        
        // 2. Unregister Receivers/Callbacks
        try {
            audioManager.unregisterAudioDeviceCallback(audioDeviceCallback)
        } catch (e: Exception) {
            Log.e("PlaybackViewModel", "Error unregistering audio callback", e)
        }
        
        // 3. Unregister ContentObserver
        try {
            mediaStoreObserver?.let { 
                getApplication<Application>().contentResolver.unregisterContentObserver(it)
            }
        } catch (e: Exception) {
            Log.e("PlaybackViewModel", "Error unregistering observer", e)
        }
        
        // 4. Cancel Jobs
        progressJob?.cancel()
        sleepTimerJob?.cancel()
        
        // 5. Release Hardware Resources
        EqualizerManager.release()
    }
}
