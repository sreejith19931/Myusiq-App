@file:Suppress("DEPRECATION")

package com.music.myusiq.service

import android.Manifest
import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.ValueAnimator
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.widget.Toast
import android.bluetooth.BluetoothDevice
import android.content.BroadcastReceiver
import android.content.ContentUris
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ServiceInfo
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.media.PlaybackParams
import android.media.audiofx.PresetReverb
import android.net.Uri
import android.os.Build
import androidx.core.app.NotificationCompat
import android.os.Binder
import android.os.Bundle
import android.os.IBinder
import android.provider.MediaStore
import android.util.Log
import androidx.annotation.OptIn
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.session.CommandButton
import androidx.media3.session.DefaultMediaNotificationProvider
import androidx.media3.session.MediaLibraryService
import androidx.media3.session.MediaLibraryService.MediaLibrarySession
import androidx.media3.session.MediaLibraryService.LibraryParams
import androidx.media3.session.LibraryResult
import androidx.media3.session.MediaSession
import androidx.media3.session.SessionCommand
import androidx.media3.session.SessionError
import androidx.media3.session.SessionResult
import com.google.common.collect.ImmutableList
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture
import com.google.common.util.concurrent.SettableFuture
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import java.io.File
import java.lang.ref.WeakReference
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import androidx.core.net.toUri
import androidx.media3.common.AuxEffectInfo
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.audio.AudioSink
import androidx.media3.exoplayer.audio.DefaultAudioSink
import com.music.myusiq.MainApplication
import com.music.myusiq.ui.MainActivity
import com.music.myusiq.R
import com.music.myusiq.data.MusicRepository
import com.music.myusiq.data.platform.AndroidMusicScanner
import com.music.myusiq.domain.models.*
import com.music.myusiq.data.dao.SongHistoryDao
import com.music.myusiq.utils.*
import kotlinx.coroutines.Job
import com.google.android.gms.wearable.Asset
import com.google.android.gms.wearable.PutDataMapRequest
import com.google.android.gms.wearable.Wearable

@OptIn(UnstableApi::class)
class PlaybackService : MediaLibraryService() {
    companion object {
        const val ACTION_WEAR_COMMAND = "com.music.myusiq.ACTION_WEAR_COMMAND"
        const val EXTRA_WEAR_COMMAND_PATH = "wear_command_path"
        const val EXTRA_WEAR_COMMAND_PAYLOAD = "wear_command_payload"

        private var activeInstance: WeakReference<PlaybackService>? = null

        fun getInstance(): PlaybackService? = activeInstance?.get()
    }

    enum class RepeatState { OFF, ALL, ONE }

    private val serviceScope = CoroutineScope(Dispatchers.Main + SupervisorJob())
    private lateinit var persistence: PlaybackPersistence
    private lateinit var songHistoryDao: SongHistoryDao
    private lateinit var musicRepository: MusicRepository
    private lateinit var audioManager: AudioManager
    private var presetReverb: PresetReverb? = null

    lateinit var playerA: ExoPlayer
        private set
    lateinit var playerB: ExoPlayer
        private set
    
    private var activePlayerReference: WeakReference<ExoPlayer>? = null
    
    val exoPlayer: ExoPlayer
        get() = activePlayerReference?.get() ?: playerA
        
    private val inactivePlayer: ExoPlayer
        get() = if (exoPlayer === playerA) playerB else playerA

    internal var mediaSession: MediaLibrarySession? = null
    
    var isUpdatingMetadataInPlace = false
    private var lastBluetoothConnectionTime: Long = 0L
    
    var stopAfterCurrentTrack: Boolean = false
    private var isFadingForSleep = false
    private var isFadingForStopAfterTrack = false

    var currentRepeatState: RepeatState
        get() = when (exoPlayer.repeatMode) {
            Player.REPEAT_MODE_ONE -> RepeatState.ONE
            Player.REPEAT_MODE_ALL -> RepeatState.ALL
            else -> RepeatState.OFF
        }
        set(value) {
            val mode = when (value) {
                RepeatState.ONE -> Player.REPEAT_MODE_ONE
                RepeatState.ALL -> Player.REPEAT_MODE_ALL
                else -> Player.REPEAT_MODE_OFF
            }
            playerA.repeatMode = mode
            playerB.repeatMode = mode
            updateWidgets()
        }

    private var volumeAnimatorA: ValueAnimator? = null
    private var volumeAnimatorB: ValueAnimator? = null
    private var crossfadeAnimator: ValueAnimator? = null

    private var audioFocusRequest: AudioFocusRequest? = null
    private var duckingFactor = 1.0f
    private var wasPlayingBeforeFocusLoss = false

    private val audioFocusChangeListener = AudioManager.OnAudioFocusChangeListener { focusChange ->
        when (focusChange) {
            AudioManager.AUDIOFOCUS_GAIN -> {
                duckingFactor = 1.0f
                updateAllPlayersVolume()
                if (wasPlayingBeforeFocusLoss) {
                    exoPlayer.play()
                    wasPlayingBeforeFocusLoss = false
                }
            }
            AudioManager.AUDIOFOCUS_LOSS -> {
                wasPlayingBeforeFocusLoss = false
                exoPlayer.pause()
                inactivePlayer.pause()
            }
            AudioManager.AUDIOFOCUS_LOSS_TRANSIENT -> {
                wasPlayingBeforeFocusLoss = exoPlayer.isPlaying
                exoPlayer.pause()
                inactivePlayer.pause()
            }
            AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK -> {
                duckingFactor = 0.2f
                updateAllPlayersVolume()
            }
        }
    }

    private fun updateAllPlayersVolume() {
        if ((volumeAnimatorA?.isRunning != true) && (crossfadeAnimator?.isRunning != true)) {
            playerA.volume = 1.0f * duckingFactor
        }
        if ((volumeAnimatorB?.isRunning != true) && (crossfadeAnimator?.isRunning != true)) {
            playerB.volume = 1.0f * duckingFactor
        }
    }

    private fun requestAudioFocus(): Boolean {
        val attr = android.media.AudioAttributes.Builder()
            .setUsage(android.media.AudioAttributes.USAGE_MEDIA)
            .setContentType(android.media.AudioAttributes.CONTENT_TYPE_MUSIC)
            .build()
        audioFocusRequest = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
            .setAudioAttributes(attr)
            .setAcceptsDelayedFocusGain(true)
            .setOnAudioFocusChangeListener(audioFocusChangeListener)
            .build()
        val res = audioManager.requestAudioFocus(audioFocusRequest!!)
        
        if (res == AudioManager.AUDIOFOCUS_REQUEST_DELAYED) {
            wasPlayingBeforeFocusLoss = true
            return true
        }
        return res == AudioManager.AUDIOFOCUS_REQUEST_GRANTED
    }
    
    private fun fadeIn(player: ExoPlayer, duration: Long = 1000L) {
        if (player === playerA) volumeAnimatorA?.cancel() else volumeAnimatorB?.cancel()
        player.volume = 0f
        val animator = ValueAnimator.ofFloat(0f, 1f).apply {
            this.duration = duration
            addUpdateListener { 
                val v = it.animatedValue as Float
                // Less aggressive curve for crossfade: v * (2-v) is a smooth arch
                player.volume = v * (2f - v) * duckingFactor
            }
            start()
        }
        if (player === playerA) volumeAnimatorA = animator else volumeAnimatorB = animator
    }

    private fun fadeOut(player: ExoPlayer, duration: Long = 1000L) {
        if (player === playerA) volumeAnimatorA?.cancel() else volumeAnimatorB?.cancel()
        val currentVol = player.volume
        val animator = ValueAnimator.ofFloat(currentVol, 0f).apply {
            this.duration = duration
            addUpdateListener { 
                val v = it.animatedValue as Float
                player.volume = v * v * duckingFactor
            }
            start()
        }
        if (player === playerA) volumeAnimatorA = animator else volumeAnimatorB = animator
    }

    private var playbackMonitorJob: Job? = null
    private fun startPlaybackMonitoring() {
        playbackMonitorJob?.cancel()
        val p = getSharedPreferences("app_prefs", MODE_PRIVATE)
        val crossfadeEnabled = p.getBoolean("crossfade_enabled", false)
        
        if (!crossfadeEnabled && !stopAfterCurrentTrack) {
            volumeAnimatorA?.cancel()
            volumeAnimatorB?.cancel()
            if (!isFadingForSleep && !isFadingForStopAfterTrack) {
                exoPlayer.volume = 1f
            }
            return
        }
        
        val crossfadeDurationMs = p.getFloat("crossfade_duration", 2.0f).toLong() * 1000L
        
        playbackMonitorJob = serviceScope.launch {
            while (true) {
                val current = exoPlayer
                // Skip crossfade if Repeat One is enabled, as the song will loop back rather than transitioning to the next item
                val shouldSkipCrossfade = current.repeatMode == Player.REPEAT_MODE_ONE
                
                if ((current.isPlaying) && (current.duration > 0)) {
                    val remaining = current.duration - current.currentPosition
                    
                    if (stopAfterCurrentTrack) {
                        if ((remaining <= 8000L) && (!isFadingForStopAfterTrack) && (!isFadingForSleep)) {
                            isFadingForStopAfterTrack = true
                            fadeOut(current, remaining.coerceAtLeast(1000L))
                        }
                        delay(500.milliseconds)
                        continue
                    }
                    
                    if (crossfadeEnabled && !shouldSkipCrossfade) {
                        if (remaining in (101..crossfadeDurationMs)) {
                            performCrossfade(crossfadeDurationMs)
                            break
                        }
                        // Adaptive delay
                        val nextCheck = (remaining - crossfadeDurationMs).coerceIn(50, 500)
                        delay(nextCheck.milliseconds)
                    } else {
                        delay(500.milliseconds)
                    }
                } else {
                    delay(500.milliseconds)
                }
            }
        }
    }

    private fun performCrossfade(durationMs: Long) {
        val current = exoPlayer
        val next = inactivePlayer
        
        if (!current.hasNextMediaItem()) return
        
        Log.d("PlaybackService", "Starting synchronized crossfade sequence")
        
        // Prepare next player
        val nextIndex = current.nextMediaItemIndex
        next.stop()
        next.clearMediaItems()
        next.setMediaItems(getAllMediaItems(), nextIndex, 0L)
        
        // Sync player states for seamless transition
        next.repeatMode = current.repeatMode
        next.shuffleModeEnabled = current.shuffleModeEnabled
        
        next.prepare()
        next.volume = 0f
        
        // Wait for next player to be ready to ensure gapless start
        val listener = object : Player.Listener {
            override fun onPlaybackStateChanged(state: Int) {
                if (state == Player.STATE_READY) {
                    next.removeListener(this)
                    startCrossfadeAnimation(current, next, durationMs)
                }
            }
        }
        next.addListener(listener)
        next.playWhenReady = true
    }

    private fun startCrossfadeAnimation(current: ExoPlayer, next: ExoPlayer, durationMs: Long) {
        crossfadeAnimator?.cancel()
        volumeAnimatorA?.cancel()
        volumeAnimatorB?.cancel()

        crossfadeAnimator = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = durationMs
            addUpdateListener { anim ->
                val v = anim.animatedValue as Float
                // Fade out current
                current.volume = (1f - v) * (1f - v) * duckingFactor
                // Fade in next
                next.volume = v * (2f - v) * duckingFactor
            }
            addListener(
                object : AnimatorListenerAdapter() {
                    override fun onAnimationEnd(animation: Animator) {
                        if (current !== exoPlayer) {
                            current.pause()
                            current.stop()
                            current.clearMediaItems()
                            current.volume = 1f 
                        }
                    }
                },
            )
            start()
        }
        
        // Swap active player reference so UI updates immediately
        activePlayerReference = WeakReference(next)
        mediaSession?.player = next
    }

    private fun getAllMediaItems(): List<MediaItem> {
        val items = mutableListOf<MediaItem>()
        for (i in 0 until exoPlayer.mediaItemCount) {
            items.add(exoPlayer.getMediaItemAt(i))
        }
        return items
    }

    private val bluetoothReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            val p = getSharedPreferences("app_prefs", MODE_PRIVATE)
            EqualizerManager.handleAudioDeviceRouting(applicationContext, audioManager)
            
            when (intent.action) {
                BluetoothDevice.ACTION_ACL_CONNECTED -> {
                    lastBluetoothConnectionTime = System.currentTimeMillis()
                    if (p.getBoolean("bluetooth_resume_on_connect", false)) {
                        exoPlayer.play()
                    }
                }
                BluetoothDevice.ACTION_ACL_DISCONNECTED -> {
                    if (p.getBoolean("bluetooth_stop_on_unplug", true)) {
                        exoPlayer.pause()
                    }
                }
                AudioManager.ACTION_AUDIO_BECOMING_NOISY -> {
                    if (p.getBoolean("bluetooth_stop_on_unplug", true)) {
                        exoPlayer.pause()
                    }
                }
            }
        }
    }

    override fun onCreate() {
        super.onCreate()
        activeInstance = WeakReference(this)
        Log.d("PlaybackService", "onCreate started")

        val filter = IntentFilter().apply {
            addAction(BluetoothDevice.ACTION_ACL_CONNECTED)
            addAction(BluetoothDevice.ACTION_ACL_DISCONNECTED)
            addAction(AudioManager.ACTION_AUDIO_BECOMING_NOISY)
            addAction(Intent.ACTION_HEADSET_PLUG)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(bluetoothReceiver, filter, RECEIVER_EXPORTED)
        } else {
            registerReceiver(bluetoothReceiver, filter)
        }

        persistence = (applicationContext as MainApplication).playbackPersistence
        audioManager = getSystemService(AUDIO_SERVICE) as AudioManager
        EqualizerManager.handleAudioDeviceRouting(applicationContext, audioManager)
        val db = (applicationContext as MainApplication).database
        songHistoryDao = db.songHistoryDao()
        musicRepository = MusicRepository(
            songDao = db.songDao(),
            playlistDao = db.playlistDao(),
            songHistoryDao = db.songHistoryDao(),
            musicScanner = AndroidMusicScanner(this),
        )
        
        val renderersFactory = object : DefaultRenderersFactory(this) {
            override fun buildAudioSink(context: Context, enableFloatOutput: Boolean, enableAudioTrackPlaybackParams: Boolean): AudioSink {
                return DefaultAudioSink.Builder(context)
                    .build()
            }
        }
        
        playerA = ExoPlayer.Builder(this, renderersFactory)
            .setAudioAttributes(AudioAttributes.Builder().setContentType(C.AUDIO_CONTENT_TYPE_MUSIC).setUsage(C.USAGE_MEDIA).build(), false) // handleAudioFocus = false
            .setHandleAudioBecomingNoisy(true)
            .setSeekBackIncrementMs(5000)
            .setSeekForwardIncrementMs(5000)
            .setPauseAtEndOfMediaItems(false)
            .build()

        playerB = ExoPlayer.Builder(this, renderersFactory)
            .setAudioAttributes(AudioAttributes.Builder().setContentType(C.AUDIO_CONTENT_TYPE_MUSIC).setUsage(C.USAGE_MEDIA).build(), false) // handleAudioFocus = false
            .setHandleAudioBecomingNoisy(true)
            .setSeekBackIncrementMs(5000)
            .setSeekForwardIncrementMs(5000)
            .setPauseAtEndOfMediaItems(false)
            .build()
            
        activePlayerReference = WeakReference(playerA)
        updateAllPlayersVolume()
            
        refreshAudioSettings()
        startWidgetUpdateTimer()
        
        setupPlayer(playerA)
        setupPlayer(playerB)

        val intent = Intent(this, MainActivity::class.java)
        val pendingIntent = PendingIntent.getActivity(this, 0, intent, PendingIntent.FLAG_IMMUTABLE)

        val callback = object : MediaLibrarySession.Callback {
            override fun onGetLibraryRoot(
                session: MediaLibrarySession,
                browser: MediaSession.ControllerInfo,
                params: LibraryParams?,
            ): ListenableFuture<LibraryResult<MediaItem>> {
                val rootItem = createBrowsableMediaItem("ROOT", "Library", MediaMetadata.MEDIA_TYPE_FOLDER_MIXED)
                val rootExtras = Bundle().apply {
                    putBoolean("android.media.browse.SEARCH_SUPPORTED", true)
                    putBoolean("android.media.browse.CONTENT_STYLE_SUPPORTED", true)
                    putInt("android.media.browse.CONTENT_STYLE_BROWSABLE_HINT", 1)
                    putInt("android.media.browse.CONTENT_STYLE_PLAYABLE_HINT", 1)
                }
                val rootParams = params?.let {
                    LibraryParams.Builder()
                        .setExtras(Bundle(it.extras).apply { putAll(rootExtras) })
                        .build()
                } ?: LibraryParams.Builder().setExtras(rootExtras).build()
                return Futures.immediateFuture(LibraryResult.ofItem(rootItem, rootParams))
            }

            override fun onSubscribe(
                session: MediaLibrarySession,
                browser: MediaSession.ControllerInfo,
                parentId: String,
                params: LibraryParams?
            ): ListenableFuture<LibraryResult<Void>> {
                val rootParams = params ?: LibraryParams.Builder().build()
                return Futures.immediateFuture(LibraryResult.ofVoid(rootParams))
            }

            override fun onGetChildren(
                session: MediaLibrarySession,
                browser: MediaSession.ControllerInfo,
                parentId: String,
                page: Int,
                pageSize: Int,
                params: LibraryParams?
            ): ListenableFuture<LibraryResult<ImmutableList<MediaItem>>> {
                val future = SettableFuture.create<LibraryResult<ImmutableList<MediaItem>>>()
                serviceScope.launch(Dispatchers.IO) {
                    try {
                        val allSongs = musicRepository.getSongs(filterShortAudio = false)
                        var resultParams = params

                        val items = when (parentId.uppercase()) {
                            "ROOT", "" -> {
                                if (allSongs.isEmpty()) {
                                    listOf(
                                        createPlayableMediaItem(
                                            "NO_SONGS_FOUND",
                                            "No Local Music Found",
                                            "Open Myusiq on phone to scan storage or grant permissions"
                                        )
                                    )
                                } else {
                                    val distinctArtists = allSongs.map { it.artist }.distinct()
                                    val distinctAlbums = allSongs.groupBy { it.albumId }
                                    val playlists = musicRepository.getPlaylistsWithTracks().firstOrNull() ?: emptyList()
                                    val distinctFolders = allSongs.mapNotNull { song ->
                                        File(song.path).parentFile?.absolutePath
                                    }.distinct()

                                    val activeEqPreset = EqualizerManager.getActivePresetForProfile(applicationContext)
                                    val shuffleIconUri = Uri.parse("android.resource://$packageName/${R.drawable.ic_shuffle_on}")
                                    listOf(
                                        createPlayableMediaItem("SHUFFLE_ALL", "Shuffle All", "${allSongs.size} tracks • Tap to play randomly", artworkUri = shuffleIconUri),
                                        createBrowsableMediaItem("TRACKS", "All Tracks", MediaMetadata.MEDIA_TYPE_FOLDER_MIXED, subtitle = "${allSongs.size} tracks"),
                                        createBrowsableMediaItem("ARTISTS", "Artists", MediaMetadata.MEDIA_TYPE_FOLDER_ARTISTS, subtitle = "${distinctArtists.size} artists"),
                                        createBrowsableMediaItem("ALBUMS", "Albums", MediaMetadata.MEDIA_TYPE_FOLDER_ALBUMS, subtitle = "${distinctAlbums.size} albums"),
                                        createBrowsableMediaItem("PLAYLISTS", "Playlists", MediaMetadata.MEDIA_TYPE_FOLDER_PLAYLISTS, subtitle = "${playlists.size} playlists"),
                                        createBrowsableMediaItem("RECENTLY_PLAYED", "Recently Played", MediaMetadata.MEDIA_TYPE_FOLDER_MIXED, subtitle = "Recent play history"),
                                        createBrowsableMediaItem("FOLDERS", "Folders", MediaMetadata.MEDIA_TYPE_FOLDER_MIXED, subtitle = "${distinctFolders.size} directories"),
                                        createBrowsableMediaItem("AUDIO_PRESETS", "Audio Presets", MediaMetadata.MEDIA_TYPE_FOLDER_MIXED, subtitle = "EQ: $activeEqPreset • Sound Profiles")
                                    )
                                }
                            }
                            "AUDIO_PRESETS" -> {
                                val activePreset = EqualizerManager.getActivePresetForProfile(applicationContext)
                                val presets = listOf("Flat", "Bass Boost", "Vocal", "Rock", "Pop", "Jazz", "Classical", "Club")
                                presets.map { preset ->
                                    val isActive = preset.equals(activePreset, ignoreCase = true)
                                    createBrowsableMediaItem(
                                        id = "EQ_${preset}",
                                        title = preset,
                                        type = MediaMetadata.MEDIA_TYPE_FOLDER_MIXED,
                                        subtitle = if (isActive) "✓ Active EQ Profile" else "Tap to apply EQ profile"
                                    )
                                }
                            }
                            "TRACKS" -> {
                                val shuffleIconUri = Uri.parse("android.resource://$packageName/${R.drawable.ic_shuffle_on}")
                                val shuffleItem = createPlayableMediaItem(
                                    id = "SHUFFLE_ALL",
                                    title = "Shuffle All Tracks",
                                    subtitle = "Tap to play all ${allSongs.size} tracks randomly",
                                    artworkUri = shuffleIconUri
                                )
                                listOf(shuffleItem) + allSongs.map { it.toMediaItem() }
                            }
                            "ARTISTS" -> {
                                val gridExtras = Bundle().apply {
                                    putInt("android.media.browse.CONTENT_STYLE_BROWSABLE_HINT", 2)
                                    putInt("android.media.browse.CONTENT_STYLE_PLAYABLE_HINT", 1)
                                }
                                resultParams = params?.let {
                                    LibraryParams.Builder().setExtras(Bundle(it.extras).apply { putAll(gridExtras) }).build()
                                } ?: LibraryParams.Builder().setExtras(gridExtras).build()

                                allSongs.groupBy { it.artist }.map { (artistName, artistSongs) ->
                                    val first = artistSongs.firstOrNull()
                                    val artworkUri = first?.albumId?.let { albumId ->
                                        ContentUris.withAppendedId("content://media/external/audio/albumart".toUri(), albumId)
                                    }
                                    createBrowsableMediaItem(
                                        id = "ARTIST_${artistName}",
                                        title = artistName,
                                        type = MediaMetadata.MEDIA_TYPE_FOLDER_ARTISTS,
                                        artworkUri = artworkUri,
                                        subtitle = "${artistSongs.size} tracks"
                                    )
                                }
                            }
                            "ALBUMS" -> {
                                val gridExtras = Bundle().apply {
                                    putInt("android.media.browse.CONTENT_STYLE_BROWSABLE_HINT", 2)
                                    putInt("android.media.browse.CONTENT_STYLE_PLAYABLE_HINT", 1)
                                }
                                resultParams = params?.let {
                                    LibraryParams.Builder().setExtras(Bundle(it.extras).apply { putAll(gridExtras) }).build()
                                } ?: LibraryParams.Builder().setExtras(gridExtras).build()

                                allSongs.groupBy { it.albumId }.mapNotNull { (id, albumSongs) ->
                                    val first = albumSongs.firstOrNull() ?: return@mapNotNull null
                                    val artworkUri = ContentUris.withAppendedId("content://media/external/audio/albumart".toUri(), id)
                                    createBrowsableMediaItem(
                                        id = "ALBUM_$id",
                                        title = first.album,
                                        type = MediaMetadata.MEDIA_TYPE_FOLDER_ALBUMS,
                                        artworkUri = artworkUri,
                                        subtitle = "${first.artist} • ${albumSongs.size} tracks"
                                    )
                                }
                            }
                            "PLAYLISTS" -> {
                                val playlists = musicRepository.getPlaylistsWithTracks().firstOrNull() ?: emptyList()
                                playlists.map { playlist ->
                                    createBrowsableMediaItem(
                                        id = "PLAYLIST_${playlist.playlist.id}",
                                        title = playlist.playlist.name,
                                        type = MediaMetadata.MEDIA_TYPE_FOLDER_PLAYLISTS,
                                        subtitle = "${playlist.tracks.size} tracks"
                                    )
                                }
                            }
                            "RECENTLY_PLAYED" -> {
                                val recentInfo = songHistoryDao.getLastPlayedTimestamps().sortedByDescending { it.lastPlayed }
                                val recentSongs = recentInfo.mapNotNull { info ->
                                    allSongs.find { it.id.toString() == info.songId }
                                }
                                recentSongs.map { it.toMediaItem() }
                            }
                            "FOLDERS" -> {
                                allSongs.groupBy { song ->
                                    File(song.path).parentFile?.absolutePath ?: "Unknown"
                                }.map { (folderPath, folderSongs) ->
                                    val folderName = File(folderPath).name.ifBlank { "Folder" }
                                    createBrowsableMediaItem(
                                        id = "FOLDER_${folderPath}",
                                        title = folderName,
                                        type = MediaMetadata.MEDIA_TYPE_FOLDER_MIXED,
                                        subtitle = "${folderSongs.size} tracks • $folderPath"
                                    )
                                }
                            }
                            else -> {
                                when {
                                    parentId.startsWith("ARTIST_", ignoreCase = true) -> {
                                        val artistName = parentId.substring("ARTIST_".length)
                                        allSongs.filter { it.artist.equals(artistName, ignoreCase = true) }.map { it.toMediaItem() }
                                    }
                                    parentId.startsWith("ALBUM_", ignoreCase = true) -> {
                                        val id = parentId.substring("ALBUM_".length).toLongOrNull() ?: -1L
                                        allSongs.filter { it.albumId == id }.map { it.toMediaItem() }
                                    }
                                    parentId.startsWith("PLAYLIST_", ignoreCase = true) -> {
                                        val id = parentId.substring("PLAYLIST_".length).toLongOrNull() ?: -1L
                                        val playlistWithTracks = musicRepository.getPlaylistWithTracks(id).firstOrNull()
                                        playlistWithTracks?.tracks?.mapNotNull { track ->
                                            allSongs.find { it.id.toString() == track.id }?.toMediaItem()
                                        } ?: emptyList()
                                    }
                                    parentId.startsWith("FOLDER_", ignoreCase = true) -> {
                                        val folderPath = parentId.substring("FOLDER_".length)
                                        allSongs.filter { song ->
                                            val songParent = File(song.path).parentFile?.absolutePath
                                            songParent.equals(folderPath, ignoreCase = true) || song.path.startsWith(folderPath, ignoreCase = true)
                                        }.map { it.toMediaItem() }
                                    }
                                    else -> emptyList()
                                }
                            }
                        }
                        future.set(LibraryResult.ofItemList(ImmutableList.copyOf(items), resultParams))
                    } catch (e: Exception) {
                        Log.e("PlaybackService", "Error in onGetChildren for parentId: $parentId", e)
                        future.set(LibraryResult.ofItemList(ImmutableList.of(), params))
                    }
                }
                return future
            }

            override fun onGetItem(
                session: MediaLibrarySession,
                browser: MediaSession.ControllerInfo,
                mediaId: String
            ): ListenableFuture<LibraryResult<MediaItem>> {
                val future = SettableFuture.create<LibraryResult<MediaItem>>()
                serviceScope.launch(Dispatchers.IO) {
                    try {
                        val allSongs = musicRepository.getSongs(filterShortAudio = false)
                        val item = when (mediaId.uppercase()) {
                            "ROOT", "" -> createBrowsableMediaItem("ROOT", "Library", MediaMetadata.MEDIA_TYPE_FOLDER_MIXED)
                            "SHUFFLE_ALL" -> createPlayableMediaItem("SHUFFLE_ALL", "Shuffle All", "${allSongs.size} tracks • Tap to play randomly")
                            "TRACKS" -> createBrowsableMediaItem("TRACKS", "All Tracks", MediaMetadata.MEDIA_TYPE_FOLDER_MIXED, subtitle = "${allSongs.size} tracks")
                            "ARTISTS" -> createBrowsableMediaItem("ARTISTS", "Artists", MediaMetadata.MEDIA_TYPE_FOLDER_ARTISTS)
                            "ALBUMS" -> createBrowsableMediaItem("ALBUMS", "Albums", MediaMetadata.MEDIA_TYPE_FOLDER_ALBUMS)
                            "PLAYLISTS" -> createBrowsableMediaItem("PLAYLISTS", "Playlists", MediaMetadata.MEDIA_TYPE_FOLDER_PLAYLISTS)
                            "RECENTLY_PLAYED" -> createBrowsableMediaItem("RECENTLY_PLAYED", "Recently Played", MediaMetadata.MEDIA_TYPE_FOLDER_MIXED, subtitle = "Recent play history")
                            "FOLDERS" -> createBrowsableMediaItem("FOLDERS", "Folders", MediaMetadata.MEDIA_TYPE_FOLDER_MIXED)
                            "AUDIO_PRESETS" -> createBrowsableMediaItem("AUDIO_PRESETS", "Audio Presets", MediaMetadata.MEDIA_TYPE_FOLDER_MIXED, subtitle = "Equalizer & Sound Profiles")
                            else -> {
                                when {
                                    mediaId.startsWith("EQ_", ignoreCase = true) -> {
                                        val presetName = mediaId.substring("EQ_".length)
                                        val activePreset = EqualizerManager.getActivePresetForProfile(applicationContext)
                                        val isActive = presetName.equals(activePreset, ignoreCase = true)
                                        createBrowsableMediaItem(mediaId, presetName, MediaMetadata.MEDIA_TYPE_FOLDER_MIXED, subtitle = if (isActive) "✓ Active EQ Profile" else "Tap to apply EQ profile")
                                    }
                                    mediaId.startsWith("ARTIST_", ignoreCase = true) -> {
                                        val artistName = mediaId.substring("ARTIST_".length)
                                        val artistSongs = allSongs.filter { it.artist.equals(artistName, ignoreCase = true) }
                                        val first = artistSongs.firstOrNull()
                                        val artworkUri = first?.albumId?.let { albumId ->
                                            ContentUris.withAppendedId("content://media/external/audio/albumart".toUri(), albumId)
                                        }
                                        createBrowsableMediaItem(mediaId, artistName, MediaMetadata.MEDIA_TYPE_FOLDER_ARTISTS, artworkUri, subtitle = "${artistSongs.size} tracks")
                                    }
                                    mediaId.startsWith("ALBUM_", ignoreCase = true) -> {
                                        val id = mediaId.substring("ALBUM_".length).toLongOrNull() ?: -1L
                                        val albumSongs = allSongs.filter { it.albumId == id }
                                        val first = albumSongs.firstOrNull()
                                        val artworkUri = ContentUris.withAppendedId("content://media/external/audio/albumart".toUri(), id)
                                        if (first != null) createBrowsableMediaItem(mediaId, first.album, MediaMetadata.MEDIA_TYPE_FOLDER_ALBUMS, artworkUri, subtitle = "${first.artist} • ${albumSongs.size} tracks") else null
                                    }
                                    mediaId.startsWith("PLAYLIST_", ignoreCase = true) -> {
                                        val id = mediaId.substring("PLAYLIST_".length).toLongOrNull() ?: -1L
                                        val playlistWithTracks = musicRepository.getPlaylistWithTracks(id).firstOrNull()
                                        if (playlistWithTracks != null) createBrowsableMediaItem(mediaId, playlistWithTracks.playlist.name, MediaMetadata.MEDIA_TYPE_FOLDER_PLAYLISTS, subtitle = "${playlistWithTracks.tracks.size} tracks") else null
                                    }
                                    mediaId.startsWith("FOLDER_", ignoreCase = true) -> {
                                        val folderPath = mediaId.substring("FOLDER_".length)
                                        val folderName = File(folderPath).name.ifBlank { "Folder" }
                                        val folderSongs = allSongs.filter { song ->
                                            val songParent = File(song.path).parentFile?.absolutePath
                                            songParent.equals(folderPath, ignoreCase = true) || song.path.startsWith(folderPath, ignoreCase = true)
                                        }
                                        createBrowsableMediaItem(mediaId, folderName, MediaMetadata.MEDIA_TYPE_FOLDER_MIXED, subtitle = "${folderSongs.size} tracks • $folderPath")
                                    }
                                    else -> {
                                        allSongs.find { it.id.toString() == mediaId }?.toMediaItem()
                                    }
                                }
                            }
                        }
                        if (item != null) {
                            future.set(LibraryResult.ofItem(item, null))
                        } else {
                            future.set(LibraryResult.ofError(SessionError.ERROR_BAD_VALUE))
                        }
                    } catch (e: Exception) {
                        Log.e("PlaybackService", "Error in onGetItem for mediaId: $mediaId", e)
                        future.set(LibraryResult.ofError(SessionError.ERROR_UNKNOWN))
                    }
                }
                return future
            }

            override fun onAddMediaItems(
                mediaSession: MediaSession,
                controller: MediaSession.ControllerInfo,
                mediaItems: List<MediaItem>
            ): ListenableFuture<List<MediaItem>> {
                val future = SettableFuture.create<List<MediaItem>>()
                serviceScope.launch(Dispatchers.IO) {
                    try {
                        val resolvedItems = mutableListOf<MediaItem>()
                        val allSongs = musicRepository.getSongs(filterShortAudio = false)

                        for (item in mediaItems) {
                            val id = item.mediaId
                            val reqMeta = item.requestMetadata
                            val searchQuery = reqMeta.searchQuery
                            val mediaFocus = reqMeta.extras?.getString(MediaStore.EXTRA_MEDIA_FOCUS)
                            val reqArtist = reqMeta.extras?.getString(MediaStore.EXTRA_MEDIA_ARTIST)
                                ?: reqMeta.extras?.getString("android.intent.extra.artist")
                            val reqAlbum = reqMeta.extras?.getString(MediaStore.EXTRA_MEDIA_ALBUM)
                                ?: reqMeta.extras?.getString("android.intent.extra.album")
                            val reqTitle = reqMeta.extras?.getString(MediaStore.EXTRA_MEDIA_TITLE)
                                ?: reqMeta.extras?.getString("android.intent.extra.title")

                            when {
                                !searchQuery.isNullOrBlank() || !reqArtist.isNullOrBlank() || !reqAlbum.isNullOrBlank() || !reqTitle.isNullOrBlank() || !mediaFocus.isNullOrBlank() -> {
                                    val query = searchQuery ?: id
                                    val voiceResults = performSmartVoiceSearch(query, reqArtist, reqAlbum, reqTitle, mediaFocus, allSongs)
                                    if (voiceResults.isNotEmpty()) {
                                        resolvedItems.addAll(voiceResults.map { it.toMediaItem() })
                                    } else {
                                        resolvedItems.addAll(allSongs.shuffled().map { it.toMediaItem() })
                                    }
                                }
                                id.equals("SHUFFLE_ALL", ignoreCase = true) -> {
                                    resolvedItems.addAll(allSongs.shuffled().map { it.toMediaItem() })
                                }
                                id.equals("ROOT", ignoreCase = true) || id.equals("TRACKS", ignoreCase = true) || id.equals("ARTISTS", ignoreCase = true) || id.equals("ALBUMS", ignoreCase = true) || id.equals("PLAYLISTS", ignoreCase = true) || id.equals("FOLDERS", ignoreCase = true) || id.equals("AUDIO_PRESETS", ignoreCase = true) -> {
                                    resolvedItems.addAll(allSongs.map { it.toMediaItem() })
                                }
                                id.startsWith("EQ_", ignoreCase = true) -> {
                                    val presetName = id.substring("EQ_".length)
                                    val success = EqualizerManager.applyPresetToHardware(applicationContext, presetName)
                                    serviceScope.launch(Dispatchers.Main) {
                                        if (success) {
                                            Toast.makeText(applicationContext, "Equalizer set to $presetName", Toast.LENGTH_SHORT).show()
                                        }
                                    }
                                    val currentItem = withContext(Dispatchers.Main) { exoPlayer.currentMediaItem }
                                    if (currentItem != null) {
                                        resolvedItems.add(currentItem)
                                    } else {
                                        resolvedItems.addAll(allSongs.map { it.toMediaItem() })
                                    }
                                }
                                id.equals("RECENTLY_PLAYED", ignoreCase = true) -> {
                                    val recentInfo = songHistoryDao.getLastPlayedTimestamps().sortedByDescending { it.lastPlayed }
                                    val recentSongs = recentInfo.mapNotNull { info ->
                                        allSongs.find { it.id.toString() == info.songId }
                                    }
                                    resolvedItems.addAll(recentSongs.map { it.toMediaItem() })
                                }
                                id.startsWith("ARTIST_", ignoreCase = true) -> {
                                    val artistName = id.substring("ARTIST_".length)
                                    val artistSongs = allSongs.filter { it.artist.equals(artistName, ignoreCase = true) }
                                    resolvedItems.addAll(artistSongs.map { it.toMediaItem() })
                                }
                                id.startsWith("ALBUM_", ignoreCase = true) -> {
                                    val albumId = id.substring("ALBUM_".length).toLongOrNull() ?: -1L
                                    val albumSongs = allSongs.filter { it.albumId == albumId }
                                    resolvedItems.addAll(albumSongs.map { it.toMediaItem() })
                                }
                                id.startsWith("PLAYLIST_", ignoreCase = true) -> {
                                    val playlistId = id.substring("PLAYLIST_".length).toLongOrNull() ?: -1L
                                    val playlistWithTracks = musicRepository.getPlaylistWithTracks(playlistId).firstOrNull()
                                    playlistWithTracks?.tracks?.forEach { track ->
                                        allSongs.find { it.id.toString() == track.id }?.let {
                                            resolvedItems.add(it.toMediaItem())
                                        }
                                    }
                                }
                                id.startsWith("FOLDER_", ignoreCase = true) -> {
                                    val folderPath = id.substring("FOLDER_".length)
                                    val folderSongs = allSongs.filter { song ->
                                        val songParent = File(song.path).parentFile?.absolutePath
                                        songParent.equals(folderPath, ignoreCase = true) || song.path.startsWith(folderPath, ignoreCase = true)
                                    }
                                    resolvedItems.addAll(folderSongs.map { it.toMediaItem() })
                                }
                                else -> {
                                    val foundSong = allSongs.find { it.id.toString() == id }
                                    if (foundSong != null) {
                                        resolvedItems.add(foundSong.toMediaItem())
                                    } else {
                                        val voiceResults = performSmartVoiceSearch(id, allSongs = allSongs)
                                        if (voiceResults.isNotEmpty()) {
                                            resolvedItems.addAll(voiceResults.map { it.toMediaItem() })
                                        } else if (item.localConfiguration != null) {
                                            resolvedItems.add(item)
                                        }
                                    }
                                }
                            }
                        }
                        future.set(resolvedItems)
                    } catch (e: Exception) {
                        Log.e("PlaybackService", "Error in onAddMediaItems", e)
                        future.set(emptyList())
                    }
                }
                return future
            }

            override fun onSearch(
                session: MediaLibrarySession,
                browser: MediaSession.ControllerInfo,
                query: String,
                params: LibraryParams?
            ): ListenableFuture<LibraryResult<Void>> {
                val future = SettableFuture.create<LibraryResult<Void>>()
                serviceScope.launch(Dispatchers.IO) {
                    try {
                        val allSongs = musicRepository.getSongs(filterShortAudio = false)
                        val results = performSmartVoiceSearch(query = query, allSongs = allSongs)
                        session.notifySearchResultChanged(browser, query, results.size, params)
                        future.set(LibraryResult.ofVoid(params))
                    } catch (e: Exception) {
                        Log.e("PlaybackService", "Error in onSearch for query: $query", e)
                        future.set(LibraryResult.ofError(SessionError.ERROR_UNKNOWN))
                    }
                }
                return future
            }

            override fun onGetSearchResult(
                session: MediaLibrarySession,
                browser: MediaSession.ControllerInfo,
                query: String,
                page: Int,
                pageSize: Int,
                params: LibraryParams?
            ): ListenableFuture<LibraryResult<ImmutableList<MediaItem>>> {
                val future = SettableFuture.create<LibraryResult<ImmutableList<MediaItem>>>()
                serviceScope.launch(Dispatchers.IO) {
                    try {
                        val allSongs = musicRepository.getSongs(filterShortAudio = false)
                        val matchingSongs = performSmartVoiceSearch(query = query, allSongs = allSongs).map { it.toMediaItem() }

                        val fromIndex = (page * pageSize).coerceAtMost(matchingSongs.size)
                        val toIndex = ((page + 1) * pageSize).coerceAtMost(matchingSongs.size)
                        val pagedItems = if (fromIndex < matchingSongs.size) matchingSongs.subList(fromIndex, toIndex) else emptyList()

                        future.set(LibraryResult.ofItemList(ImmutableList.copyOf(pagedItems), params))
                    } catch (e: Exception) {
                        Log.e("PlaybackService", "Error in onGetSearchResult for query: $query", e)
                        future.set(LibraryResult.ofItemList(ImmutableList.of(), params))
                    }
                }
                return future
            }

            override fun onConnect(session: MediaSession, controller: MediaSession.ControllerInfo): MediaSession.ConnectionResult {
                val availableSessionCommands = MediaSession.ConnectionResult.DEFAULT_SESSION_COMMANDS.buildUpon()
                    .add(SessionCommand.COMMAND_CODE_LIBRARY_GET_LIBRARY_ROOT)
                    .add(SessionCommand.COMMAND_CODE_LIBRARY_GET_CHILDREN)
                    .add(SessionCommand.COMMAND_CODE_LIBRARY_GET_ITEM)
                    .add(SessionCommand.COMMAND_CODE_LIBRARY_SUBSCRIBE)
                    .add(SessionCommand.COMMAND_CODE_LIBRARY_UNSUBSCRIBE)
                    .add(SessionCommand.COMMAND_CODE_LIBRARY_SEARCH)
                    .add(SessionCommand.COMMAND_CODE_LIBRARY_GET_SEARCH_RESULT)
                    .add(SessionCommand("ACTION_TOGGLE_FAVORITE", Bundle.EMPTY))
                    .add(SessionCommand("ACTION_TOGGLE_SHUFFLE", Bundle.EMPTY))
                    .add(SessionCommand("ACTION_TOGGLE_REPEAT", Bundle.EMPTY))
                    .add(SessionCommand("ACTION_CYCLE_EQ", Bundle.EMPTY))
                    .add(SessionCommand("GET_AUDIO_SESSION_ID", Bundle.EMPTY))
                    .add(SessionCommand("SET_REVERB", Bundle.EMPTY))
                    .add(SessionCommand("SET_STOP_AFTER_CURRENT", Bundle.EMPTY))
                    .add(SessionCommand("SET_METADATA_UPDATING", Bundle.EMPTY))
                    .add(SessionCommand("REFRESH_CURRENT_METADATA", Bundle.EMPTY))
                    .build()
                
                val availablePlayerCommands = MediaSession.ConnectionResult.DEFAULT_PLAYER_COMMANDS.buildUpon()
                    .add(Player.COMMAND_PLAY_PAUSE)
                    .add(Player.COMMAND_SEEK_TO_NEXT)
                    .add(Player.COMMAND_SEEK_TO_PREVIOUS)
                    .add(Player.COMMAND_SEEK_TO_MEDIA_ITEM)
                    .add(Player.COMMAND_SEEK_TO_DEFAULT_POSITION)
                    .add(Player.COMMAND_SEEK_BACK)
                    .add(Player.COMMAND_SEEK_FORWARD)
                    .add(Player.COMMAND_CHANGE_MEDIA_ITEMS)
                    .add(Player.COMMAND_SET_SHUFFLE_MODE)
                    .add(Player.COMMAND_SET_REPEAT_MODE)
                    .add(Player.COMMAND_ADJUST_DEVICE_VOLUME_WITH_FLAGS)
                    .add(Player.COMMAND_SET_DEVICE_VOLUME_WITH_FLAGS)
                    .build()

                return MediaSession.ConnectionResult.AcceptedResultBuilder(session)
                    .setAvailableSessionCommands(availableSessionCommands)
                    .setAvailablePlayerCommands(availablePlayerCommands)
                    .build()
            }

            override fun onPlaybackResumption(
                session: MediaSession,
                controller: MediaSession.ControllerInfo,
                isForPlayback: Boolean
            ): ListenableFuture<MediaSession.MediaItemsWithStartPosition> {
                val future = SettableFuture.create<MediaSession.MediaItemsWithStartPosition>()
                serviceScope.launch {
                    try {
                        val restoredData = loadSavedQueueData()
                        if (restoredData != null) {
                            future.set(restoredData)
                        } else {
                            future.setException(UnsupportedOperationException("No playback state to resume"))
                        }
                    } catch (e: Exception) {
                        future.setException(e)
                    }
                }
                return future
            }

            @Deprecated("Use Player.Listener#onAvailableCommandsChanged instead")
            override fun onPlayerCommandRequest(
                session: MediaSession,
                controller: MediaSession.ControllerInfo,
                playerCommand: Int
            ): Int {
                if (playerCommand == Player.COMMAND_PLAY_PAUSE) {
                    val isRecentBtConnection = System.currentTimeMillis() - lastBluetoothConnectionTime < 2000 // 2 seconds window
                    val p = getSharedPreferences("app_prefs", MODE_PRIVATE)
                    val autoResumeEnabled = p.getBoolean("bluetooth_resume_on_connect", false)

                    // If it's a recent Bluetooth connection and auto-resume is OFF, block play commands from external controllers
                    // but ONLY if the player is currently paused (otherwise it might be a pause command).
                    if (isRecentBtConnection && !autoResumeEnabled && controller.packageName != packageName && !exoPlayer.playWhenReady) {
                        Log.d("PlaybackService", "Blocking automatic play command from ${controller.packageName} due to bluetooth_resume_on_connect=false")
                        return SessionResult.RESULT_INFO_SKIPPED
                    }

                    // Check if we are about to start playing
                    if (!exoPlayer.playWhenReady) {
                        if (!requestAudioFocus()) {
                            return SessionResult.RESULT_ERROR_PERMISSION_DENIED
                        }
                    }
                }
                return super.onPlayerCommandRequest(session, controller, playerCommand)
            }

            override fun onCustomCommand(session: MediaSession, controller: MediaSession.ControllerInfo, customCommand: SessionCommand, args: Bundle): ListenableFuture<SessionResult> {
                when (customCommand.customAction) {
                    "ACTION_TOGGLE_FAVORITE" -> {
                        serviceScope.launch(Dispatchers.IO) {
                            val currentItem = withContext(Dispatchers.Main) { exoPlayer.currentMediaItem }
                            val trackId = currentItem?.mediaId
                            if (trackId != null) {
                                val isFav = musicRepository.isTrackFavorite(trackId)
                                val nextFav = !isFav
                                musicRepository.updateFavoriteStatus(trackId, nextFav)

                                val playlists = musicRepository.getPlaylistsWithTracks().firstOrNull() ?: emptyList()
                                val favPlaylist = playlists.find { it.playlist.name == "Favorites" }
                                val pId = favPlaylist?.playlist?.id ?: musicRepository.createPlaylist(
                                    Playlist(name = "Favorites")
                                )
                                if (nextFav) {
                                    musicRepository.addTrackToPlaylist(PlaylistTrackCrossRef(pId, trackId))
                                } else {
                                    musicRepository.removeTrackFromPlaylist(pId, trackId)
                                }
                                updateMediaSessionCustomLayout()
                            }
                        }
                        return Futures.immediateFuture(SessionResult(SessionResult.RESULT_SUCCESS))
                    }
                    "ACTION_TOGGLE_SHUFFLE" -> {
                        serviceScope.launch(Dispatchers.Main) {
                            val nextMode = !exoPlayer.shuffleModeEnabled
                            exoPlayer.shuffleModeEnabled = nextMode
                            inactivePlayer.shuffleModeEnabled = nextMode
                            updateMediaSessionCustomLayout()
                        }
                        return Futures.immediateFuture(SessionResult(SessionResult.RESULT_SUCCESS))
                    }
                    "ACTION_TOGGLE_REPEAT" -> {
                        serviceScope.launch(Dispatchers.Main) {
                            val nextMode = when (exoPlayer.repeatMode) {
                                Player.REPEAT_MODE_OFF -> Player.REPEAT_MODE_ALL
                                Player.REPEAT_MODE_ALL -> Player.REPEAT_MODE_ONE
                                else -> Player.REPEAT_MODE_OFF
                            }
                            exoPlayer.repeatMode = nextMode
                            inactivePlayer.repeatMode = nextMode
                            updateMediaSessionCustomLayout()
                        }
                        return Futures.immediateFuture(SessionResult(SessionResult.RESULT_SUCCESS))
                    }
                    "ACTION_CYCLE_EQ" -> {
                        serviceScope.launch(Dispatchers.Main) {
                            val presets = listOf("Flat", "Bass Boost", "Vocal", "Rock", "Pop", "Jazz", "Classical", "Club")
                            val current = EqualizerManager.getActivePresetForProfile(applicationContext)
                            val currIndex = presets.indexOfFirst { it.equals(current, ignoreCase = true) }
                            val nextIndex = if (currIndex != -1) (currIndex + 1) % presets.size else 0
                            val nextPreset = presets[nextIndex]
                            EqualizerManager.applyPresetToHardware(applicationContext, nextPreset)
                            Toast.makeText(applicationContext, "Equalizer: $nextPreset", Toast.LENGTH_SHORT).show()
                            updateMediaSessionCustomLayout()
                        }
                        return Futures.immediateFuture(SessionResult(SessionResult.RESULT_SUCCESS))
                    }
                    "GET_AUDIO_SESSION_ID" -> {
                        val resultBundle = Bundle()
                        resultBundle.putInt("AUDIO_SESSION_ID", exoPlayer.audioSessionId)
                        return Futures.immediateFuture(SessionResult(SessionResult.RESULT_SUCCESS, resultBundle))
                    }
                    "SET_REVERB" -> {
                        val preset = args.getInt("PRESET", 0)
                        applyReverbPreset(preset)
                        return Futures.immediateFuture(SessionResult(SessionResult.RESULT_SUCCESS))
                    }
                    "REFRESH_SETTINGS" -> {
                        refreshAudioSettings()
                        return Futures.immediateFuture(SessionResult(SessionResult.RESULT_SUCCESS))
                    }
                    "SET_STOP_AFTER_CURRENT" -> {
                        val enabled = args.getBoolean("ENABLED", false)
                        if (stopAfterCurrentTrack && !enabled) {
                            cancelStopAfterTrackFade()
                        }
                        stopAfterCurrentTrack = enabled
                        if (stopAfterCurrentTrack) {
                            isFadingForStopAfterTrack = false
                            startPlaybackMonitoring()
                        }
                        return Futures.immediateFuture(SessionResult(SessionResult.RESULT_SUCCESS))
                    }
                    "START_SLEEP_FADE" -> {
                        startSleepFade()
                        return Futures.immediateFuture(SessionResult(SessionResult.RESULT_SUCCESS))
                    }
                    "CANCEL_SLEEP_FADE" -> {
                        cancelSleepFade()
                        return Futures.immediateFuture(SessionResult(SessionResult.RESULT_SUCCESS))
                    }
                    "SET_METADATA_UPDATING" -> {
                        isUpdatingMetadataInPlace = args.getBoolean("ENABLED", false)
                        return Futures.immediateFuture(SessionResult(SessionResult.RESULT_SUCCESS))
                    }
                    "REFRESH_CURRENT_METADATA" -> {
                        refreshCurrentMediaItemMetadata()
                        return Futures.immediateFuture(SessionResult(SessionResult.RESULT_SUCCESS))
                    }
                }
                return super.onCustomCommand(session, controller, customCommand, args)
            }
        }

        mediaSession = MediaLibrarySession.Builder(this, exoPlayer, callback)
            .setSessionActivity(pendingIntent)
            .build()

        updateMediaSessionCustomLayout()

        createNotificationChannel()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            val status = checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)
            Log.d("PlaybackService", "POST_NOTIFICATIONS permission status: $status (0 is granted)")
        }
        restorePlaybackState()
        syncStateToWearables()
    }

    private fun updateMediaSessionCustomLayout() {
        serviceScope.launch(Dispatchers.IO) {
            val currentItem = withContext(Dispatchers.Main) { exoPlayer.currentMediaItem }
            val trackId = currentItem?.mediaId
            val isFav = if (trackId != null) musicRepository.isTrackFavorite(trackId) else false
            val isShuffle = withContext(Dispatchers.Main) { exoPlayer.shuffleModeEnabled }
            val repeatMode = withContext(Dispatchers.Main) { exoPlayer.repeatMode }

            val favButton = CommandButton.Builder()
                .setDisplayName(if (isFav) "Remove Favorite" else "Favorite")
                .setIconResId(if (isFav) R.drawable.ic_heart_filled else R.drawable.ic_heart_border)
                .setSessionCommand(SessionCommand("ACTION_TOGGLE_FAVORITE", Bundle.EMPTY))
                .setEnabled(true)
                .build()

            val shuffleButton = CommandButton.Builder()
                .setDisplayName(if (isShuffle) "Shuffle On" else "Shuffle Off")
                .setIconResId(if (isShuffle) R.drawable.ic_shuffle_on else R.drawable.ic_shuffle)
                .setSessionCommand(SessionCommand("ACTION_TOGGLE_SHUFFLE", Bundle.EMPTY))
                .setEnabled(true)
                .build()

            val repeatIcon = when (repeatMode) {
                Player.REPEAT_MODE_ONE -> R.drawable.ic_repeat_one_on
                Player.REPEAT_MODE_ALL -> R.drawable.ic_repeat_on
                else -> R.drawable.ic_repeat
            }
            val repeatLabel = when (repeatMode) {
                Player.REPEAT_MODE_ONE -> "Repeat One"
                Player.REPEAT_MODE_ALL -> "Repeat All"
                else -> "Repeat Off"
            }

            val repeatButton = CommandButton.Builder()
                .setDisplayName(repeatLabel)
                .setIconResId(repeatIcon)
                .setSessionCommand(SessionCommand("ACTION_TOGGLE_REPEAT", Bundle.EMPTY))
                .setEnabled(true)
                .build()

            val activeEqPreset = EqualizerManager.getActivePresetForProfile(applicationContext)
            val eqButton = CommandButton.Builder()
                .setDisplayName("EQ: $activeEqPreset")
                .setIconResId(R.drawable.ic_equalizer)
                .setSessionCommand(SessionCommand("ACTION_CYCLE_EQ", Bundle.EMPTY))
                .setEnabled(true)
                .build()

            val customLayout = listOf(favButton, shuffleButton, repeatButton, eqButton)

            withContext(Dispatchers.Main) {
                mediaSession?.setCustomLayout(customLayout)
            }
        }
    }

    private fun setupPlayer(player: ExoPlayer) {
        player.addListener(
            object : Player.Listener {
                override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
                if (isUpdatingMetadataInPlace) return
                if (exoPlayer !== player) return // Only handle active player transitions
                
                isFadingForStopAfterTrack = false
                
                if (stopAfterCurrentTrack && reason == Player.MEDIA_ITEM_TRANSITION_REASON_AUTO) {
                    stopAfterCurrentTrack = false; player.pause()
                }
                
                val p = getSharedPreferences("app_prefs", MODE_PRIVATE)
                val fadeInEnabled = p.getBoolean("audio_fade_in", false)
                val crossfadeEnabled = p.getBoolean("crossfade_enabled", false)
                
                if (crossfadeAnimator?.isRunning != true) {
                    if (fadeInEnabled || (crossfadeEnabled && reason == Player.MEDIA_ITEM_TRANSITION_REASON_AUTO)) {
                        val fadeDuration = if (crossfadeEnabled) p.getFloat("crossfade_duration", 2.0f).toLong() * 1000L else 1000L
                        fadeIn(player, fadeDuration)
                    } else {
                        if (player === playerA) volumeAnimatorA?.cancel() else volumeAnimatorB?.cancel()
                        player.volume = 1.0f * duckingFactor
                    }
                }
                
                mediaItem?.mediaMetadata?.extras?.getString("path")?.let { path ->
                    serviceScope.launch(Dispatchers.IO) {
                        persistence.saveLastSongPath(path)
                    }
                }

                updateWidgets()
                updateMediaSessionCustomLayout()
                startTrackingJob()
                startPlaybackMonitoring()
            }
            
            override fun onPositionDiscontinuity(oldPosition: Player.PositionInfo, newPosition: Player.PositionInfo, reason: Int) {
                if (exoPlayer !== player) return
                if (reason == Player.DISCONTINUITY_REASON_SEEK) {
                    startPlaybackMonitoring()
                }
            }
            
            override fun onPlaybackStateChanged(playbackState: Int) {
                if (exoPlayer !== player) return
                Log.d("PlaybackService", "onPlaybackStateChanged: $playbackState")
                updateWidgets()
            }
            
            override fun onIsPlayingChanged(isPlaying: Boolean) {
                if (exoPlayer !== player) return
                Log.d("PlaybackService", "onIsPlayingChanged: $isPlaying")
                updateWidgets() 
                if (!isPlaying) {
                    savePlaybackState()
                }
            }
            
            override fun onRepeatModeChanged(repeatMode: Int) {
                if (exoPlayer !== player) return
                // Sync to the other player to ensure gapless/crossfade consistency
                inactivePlayer.repeatMode = repeatMode
                updateWidgets()
                updateMediaSessionCustomLayout()
            }
            
            override fun onShuffleModeEnabledChanged(shuffleModeEnabled: Boolean) {
                if (exoPlayer !== player) return
                // Sync to the other player
                inactivePlayer.shuffleModeEnabled = shuffleModeEnabled
                updateWidgets()
                updateMediaSessionCustomLayout()
            }

            override fun onPlayerError(error: PlaybackException) {
                if (exoPlayer !== player) return
                Log.e("PlaybackService", "Player error: ${error.errorCode}", error)
                if (error.errorCode == PlaybackException.ERROR_CODE_IO_FILE_NOT_FOUND ||
                    error.errorCode == PlaybackException.ERROR_CODE_IO_BAD_HTTP_STATUS) {
                    serviceScope.launch {
                        withContext(Dispatchers.Main) {
                            Toast.makeText(applicationContext, "Error playing track", Toast.LENGTH_SHORT).show()
                            if (player.hasNextMediaItem()) {
                                delay(1000.milliseconds)
                                player.seekToNextMediaItem()
                                player.prepare()
                                player.play()
                            }
                        }
                    }
                }
            }
        })
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaLibrarySession? {
        Log.d("PlaybackService", "onGetSession called from: ${controllerInfo.packageName}, session is null: ${mediaSession == null}")
        return mediaSession
    }

    override fun onUpdateNotification(session: MediaSession, startForeground: Boolean) {
        Log.d("PlaybackService", "onUpdateNotification startForeground: $startForeground")
        super.onUpdateNotification(session, startForeground)
    }

    private fun createBrowsableMediaItem(
        id: String,
        title: String,
        type: Int,
        artworkUri: Uri? = null,
        subtitle: String? = null,
        extras: Bundle? = null
    ): MediaItem {
        val metadata = MediaMetadata.Builder()
            .setTitle(title)
            .setSubtitle(subtitle)
            .setMediaType(type)
            .setIsBrowsable(true)
            .setIsPlayable(false)

        artworkUri?.let { metadata.setArtworkUri(it) }
        extras?.let { metadata.setExtras(it) }

        return MediaItem.Builder()
            .setMediaId(id)
            .setMediaMetadata(metadata.build())
            .build()
    }

    private fun createPlayableMediaItem(
        id: String,
        title: String,
        subtitle: String? = null,
        artworkUri: Uri? = null,
        extras: Bundle? = null
    ): MediaItem {
        val metadata = MediaMetadata.Builder()
            .setTitle(title)
            .setSubtitle(subtitle)
            .setIsBrowsable(false)
            .setIsPlayable(true)
            .setMediaType(MediaMetadata.MEDIA_TYPE_MUSIC)

        artworkUri?.let { metadata.setArtworkUri(it) }
        extras?.let { metadata.setExtras(it) }

        return MediaItem.Builder()
            .setMediaId(id)
            .setMediaMetadata(metadata.build())
            .build()
    }

    private suspend fun performSmartVoiceSearch(
        query: String,
        artist: String? = null,
        album: String? = null,
        title: String? = null,
        mediaFocus: String? = null,
        allSongs: List<Song>
    ): List<Song> {
        if (allSongs.isEmpty()) return emptyList()

        if (mediaFocus == MediaStore.Audio.Artists.ENTRY_CONTENT_TYPE && !artist.isNullOrBlank()) {
            val artistMatches = allSongs.filter { it.artist.contains(artist, ignoreCase = true) }
            if (artistMatches.isNotEmpty()) return artistMatches
        }
        if (mediaFocus == MediaStore.Audio.Albums.ENTRY_CONTENT_TYPE && !album.isNullOrBlank()) {
            val albumMatches = allSongs.filter { it.album.contains(album, ignoreCase = true) }
            if (albumMatches.isNotEmpty()) return albumMatches
        }
        if (mediaFocus == MediaStore.Audio.Playlists.ENTRY_CONTENT_TYPE && query.isNotBlank()) {
            val playlists = musicRepository.getPlaylistsWithTracks().firstOrNull() ?: emptyList()
            val match = playlists.find { it.playlist.name.contains(query, ignoreCase = true) }
            if (match != null) {
                val trackIds = match.tracks.map { it.id }.toSet()
                val playlistSongs = allSongs.filter { trackIds.contains(it.id.toString()) }
                if (playlistSongs.isNotEmpty()) return playlistSongs
            }
        }

        if (!artist.isNullOrBlank() || !album.isNullOrBlank() || !title.isNullOrBlank()) {
            val filtered = allSongs.filter { song ->
                val matchArtist = artist.isNullOrBlank() || song.artist.contains(artist, ignoreCase = true)
                val matchAlbum = album.isNullOrBlank() || song.album.contains(album, ignoreCase = true)
                val matchTitle = title.isNullOrBlank() || song.title.contains(title, ignoreCase = true)
                matchArtist && matchAlbum && matchTitle
            }
            if (filtered.isNotEmpty()) return filtered
        }

        val q = query.trim().lowercase()
        if (q.isBlank()) return allSongs

        if (q.contains("favorite")) {
            val favTracks = musicRepository.getFavoriteTracks().firstOrNull() ?: emptyList()
            val favIds = favTracks.map { it.id }.toSet()
            val favSongs = allSongs.filter { favIds.contains(it.id.toString()) }
            if (favSongs.isNotEmpty()) return favSongs
        }

        val exactTitleMatch = allSongs.filter { it.title.equals(q, ignoreCase = true) }
        if (exactTitleMatch.isNotEmpty()) return exactTitleMatch

        val exactArtistMatch = allSongs.filter { it.artist.equals(q, ignoreCase = true) }
        if (exactArtistMatch.isNotEmpty()) return exactArtistMatch

        val exactAlbumMatch = allSongs.filter { it.album.equals(q, ignoreCase = true) }
        if (exactAlbumMatch.isNotEmpty()) return exactAlbumMatch

        val cleanedQuery = q.replace(Regex("\\b(play|song|music|by|artist|album|on|myusiq|the|a|an)\\b"), "")
            .trim()
            .replace(Regex("\\s+"), " ")

        val targetQuery = if (cleanedQuery.isNotBlank()) cleanedQuery else q

        val substringMatches = allSongs.filter { song ->
            song.title.lowercase().contains(targetQuery) ||
            song.artist.lowercase().contains(targetQuery) ||
            song.album.lowercase().contains(targetQuery)
        }
        if (substringMatches.isNotEmpty()) return substringMatches

        val tokens = targetQuery.split(" ").filter { it.length > 2 }
        if (tokens.isNotEmpty()) {
            val tokenMatches = allSongs.filter { song ->
                tokens.any { token ->
                    song.title.lowercase().contains(token) ||
                    song.artist.lowercase().contains(token) ||
                    song.album.lowercase().contains(token)
                }
            }
            if (tokenMatches.isNotEmpty()) return tokenMatches
        }

        return emptyList()
    }

    private fun startWidgetUpdateTimer() {
        serviceScope.launch {
            while (true) {
                if (exoPlayer.isPlaying) {
                    updateWidgets()
                }
                delay(1000.milliseconds)
            }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val action = intent?.action
        Log.d("PlaybackService", "onStartCommand: action=$action")

        if (action == ACTION_WEAR_COMMAND) {
            val path = intent.getStringExtra(EXTRA_WEAR_COMMAND_PATH)
            val payload = intent.getByteArrayExtra(EXTRA_WEAR_COMMAND_PAYLOAD)
            if (!path.isNullOrEmpty()) {
                handleWearCommand(path, payload)
            }
        }

        // Explicitly start foreground for widget/wear actions to avoid ForegroundServiceDidNotStartInTimeException
        // when the service is started via Context.startForegroundService() but playback is paused.
        if (action == "TOGGLE_PLAY_PAUSE" || action == "NEXT" || action == "PREV" || action == "UPDATE_WIDGET" || action == "SHUFFLE" || action == "REPEAT" || action == ACTION_WEAR_COMMAND) {
            val notification = NotificationCompat.Builder(this, "PLAYBACK_CHANNEL")
                .setSmallIcon(R.drawable.ic_music_note_2)
                .setContentTitle(getString(R.string.app_name))
                .setContentText("Syncing...")
                .setPriority(NotificationCompat.PRIORITY_LOW)
                .setSilent(true)
                .build()

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                startForeground(1, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK)
            } else {
                startForeground(1, notification)
            }
        }

        handleIntent(action)
        return super.onStartCommand(intent, flags, startId)
    }

    override fun onBind(intent: Intent?): IBinder? {
        return super.onBind(intent)
    }

    private fun updateWidgets() {
        val currentItem = exoPlayer.currentMediaItem
        val isPlaying = exoPlayer.isPlaying
        val position = exoPlayer.currentPosition
        
        Log.d("PlaybackService", "updateWidgets: item=${currentItem?.mediaId}, isPlaying=$isPlaying")
        
        val song = currentItem?.let { item ->
            val metadata = item.mediaMetadata
            val extras = metadata.extras
            if (extras != null && extras.containsKey("id")) {
                Song(
                    id = extras.getLong("id"),
                    title = metadata.title?.toString() ?: "",
                    artist = metadata.artist?.toString() ?: "",
                    path = extras.getString("path") ?: "",
                    duration = extras.getInt("duration"),
                    albumId = extras.getLong("albumId"),
                    album = metadata.albumTitle?.toString() ?: ""
                )
            } else {
                Log.w("PlaybackService", "updateWidgets: item has no extras or ID")
                null
            }
        }
        
        MyusiqWidgetProvider.updateAllWidgets(applicationContext, song, isPlaying, position)
        MyusiqSolidWidgetProvider.updateAllWidgets(applicationContext, song, isPlaying, position)
    }

    private fun refreshAudioSettings() {
        val p = getSharedPreferences("app_prefs", MODE_PRIVATE)
        val reverbPreset = p.getInt("audio_reverb_preset", 0)
        applyReverbPreset(reverbPreset)
    }

    private fun applyReverbPreset(preset: Int) {
        try {
            if (presetReverb == null) {
                presetReverb = PresetReverb(1, 0)
            }
            presetReverb?.preset = preset.toShort()
            presetReverb?.enabled = preset != 0
            
            playerA.setAuxEffectInfo(PlaybackParams().let {
                AuxEffectInfo(presetReverb?.id ?: 0, 1.0f)
            })
            playerB.setAuxEffectInfo(PlaybackParams().let {
                AuxEffectInfo(presetReverb?.id ?: 0, 1.0f)
           })
        } catch (e: Exception) {
            Log.e("PlaybackService", "Error applying reverb", e)
        }
    }
    
    private fun cancelStopAfterTrackFade() {
        if (playerA === exoPlayer) volumeAnimatorA?.cancel() else volumeAnimatorB?.cancel()
        exoPlayer.volume = 1.0f
    }
    
    private fun startSleepFade() {
        isFadingForSleep = true
        fadeOut(exoPlayer, 60000L) // 1 minute fade out
    }
    
    private fun cancelSleepFade() {
        isFadingForSleep = false
        if (playerA === exoPlayer) volumeAnimatorA?.cancel() else volumeAnimatorB?.cancel()
        exoPlayer.volume = 1.0f
    }

    private fun refreshCurrentMediaItemMetadata() {
        if (exoPlayer.currentMediaItem == null) return
            
        // Trigger a fake transition or metadata update
        isUpdatingMetadataInPlace = true
        // ... update session metadata ...
        isUpdatingMetadataInPlace = false
    }

    private var trackingJob: Job? = null
    private fun startTrackingJob() {
        trackingJob?.cancel()
        trackingJob = serviceScope.launch {
            // Player access MUST be on main thread
            val item = withContext(Dispatchers.Main) { exoPlayer.currentMediaItem }
            val duration = withContext(Dispatchers.Main) { exoPlayer.duration }
            
            withContext(Dispatchers.IO) {
                val currentSong = item?.mediaId?.let { musicRepository.getTrackByIdSync(it) }
                if (currentSong != null) {
                    delay(10.seconds) // Track after 10 seconds of listening
                    songHistoryDao.insertPlayEvent(SongHistory(
                        songId = currentSong.id,
                        title = currentSong.title,
                        artist = currentSong.artist,
                        durationMs = if (duration > 0) duration else 0L,
                        timestamp = System.currentTimeMillis()
                    ))
                }
            }
        }
    }

    private suspend fun loadSavedQueueData(): MediaSession.MediaItemsWithStartPosition? {
        val savedQueuePaths = persistence.getSavedQueuePaths()
        val lastPath = persistence.lastSongPath.firstOrNull()
        val lastPos = persistence.lastPosition.firstOrNull() ?: 0L
        
        if (savedQueuePaths.isEmpty() && lastPath == null) return null

        val allSongs = musicRepository.getSongs(filterShortAudio = false)
        val restoredQueue = mutableListOf<MediaItem>()
        
        if (savedQueuePaths.isNotEmpty()) {
            for (path in savedQueuePaths) {
                val normSaved = PlatformUtils.normalizePath(path)
                var found = allSongs.find { PlatformUtils.normalizePath(it.path) == normSaved }
                
                if (found == null) {
                    val fileName = File(path).name
                    found = allSongs.find { File(it.path).name == fileName }
                }
                
                found?.let { restoredQueue.add(it.toMediaItem()) }
            }
        }

        var startIndex = 0
        if (lastPath != null) {
            val normLast = PlatformUtils.normalizePath(lastPath)
            val idx = restoredQueue.indexOfFirst { 
                it.mediaMetadata.extras?.getString("path")?.let { p -> PlatformUtils.normalizePath(p) } == normLast 
            }
            if (idx != -1) {
                startIndex = idx
            } else {
                val lastFileName = File(lastPath).name
                val idx2 = restoredQueue.indexOfFirst { 
                    it.mediaMetadata.extras?.getString("path")?.let { p -> File(p).name } == lastFileName 
                }
                if (idx2 != -1) startIndex = idx2
            }
        }

        // If queue was empty but we have a last path, try to restore at least that
        if (restoredQueue.isEmpty() && lastPath != null) {
            val normLast = PlatformUtils.normalizePath(lastPath)
            val found = allSongs.find { PlatformUtils.normalizePath(it.path) == normLast } 
                ?: allSongs.find { File(it.path).name == File(lastPath).name }
            
            found?.let { 
                restoredQueue.add(it.toMediaItem())
                startIndex = 0
            }
        }

        return if (restoredQueue.isNotEmpty()) {
            MediaSession.MediaItemsWithStartPosition(restoredQueue, startIndex, lastPos)
        } else null
    }

    private fun savePlaybackState() {
        val song = exoPlayer.currentMediaItem
        val pos = exoPlayer.currentPosition
        serviceScope.launch(Dispatchers.IO) {
            song?.mediaMetadata?.extras?.getString("path")?.let { path ->
                persistence.saveLastSongPath(path)
                persistence.saveLastPosition(pos)
            }
        }
    }

    private fun restorePlaybackState() {
        serviceScope.launch {
            val restoredData = loadSavedQueueData()
            if (restoredData != null) {
                withContext(Dispatchers.Main) {
                    playerA.setMediaItems(restoredData.mediaItems, restoredData.startIndex, restoredData.startPositionMs)
                    activePlayerReference = WeakReference(playerA)
                    mediaSession?.player = playerA
                }
            }
        }
    }

    override fun onDestroy() {
        if (activeInstance?.get() === this) activeInstance = null
        serviceScope.cancel()
        trackingJob?.cancel()
        playbackMonitorJob?.cancel()
        playerA.release()
        playerB.release()
        mediaSession?.release()
        try {
            unregisterReceiver(bluetoothReceiver)
        } catch (e: Exception) {
            Log.w("PlaybackService", "Bluetooth receiver not registered or already unregistered", e)
        }
        super.onDestroy()
    }

    fun handleWearCommand(path: String, payload: ByteArray? = null) {
        serviceScope.launch(Dispatchers.Main) {
            when (path) {
                "/myusiq/play_pause", "/play_pause" -> {
                    if (exoPlayer.isPlaying) {
                        exoPlayer.pause()
                    } else {
                        if (requestAudioFocus()) {
                            exoPlayer.play()
                        }
                    }
                }
                "/myusiq/next", "/next" -> {
                    if (requestAudioFocus()) {
                        exoPlayer.seekToNext()
                        if (!exoPlayer.isPlaying) exoPlayer.play()
                    }
                }
                "/myusiq/prev", "/prev" -> {
                    if (requestAudioFocus()) {
                        exoPlayer.seekToPrevious()
                        if (!exoPlayer.isPlaying) exoPlayer.play()
                    }
                }
                "/myusiq/vol_up", "/vol_up" -> {
                    audioManager.adjustStreamVolume(
                        AudioManager.STREAM_MUSIC,
                        AudioManager.ADJUST_RAISE,
                        AudioManager.FLAG_SHOW_UI
                    )
                }
                "/myusiq/vol_down", "/vol_down" -> {
                    audioManager.adjustStreamVolume(
                        AudioManager.STREAM_MUSIC,
                        AudioManager.ADJUST_LOWER,
                        AudioManager.FLAG_SHOW_UI
                    )
                }
                "/myusiq/play_index", "/play_index" -> {
                    payload?.let { bytes ->
                        val index = String(bytes).toIntOrNull()
                        if (index != null && index >= 0 && index < exoPlayer.mediaItemCount) {
                            if (requestAudioFocus()) {
                                exoPlayer.seekToDefaultPosition(index)
                                exoPlayer.play()
                            }
                        }
                    }
                }
                "/myusiq/request_sync", "/request_sync" -> {
                    syncStateToWearables()
                }
            }
            syncStateToWearables()
        }
    }

    fun syncStateToWearables() {
        serviceScope.launch(Dispatchers.IO) {
            try {
                val isPlaying = withContext(Dispatchers.Main) { exoPlayer.isPlaying }
                val currentItem = withContext(Dispatchers.Main) { exoPlayer.currentMediaItem }
                val metadata = currentItem?.mediaMetadata
                val title = metadata?.title?.toString() ?: "No Track Playing"
                val artist = metadata?.artist?.toString() ?: "Myusiq"

                val queueCount = withContext(Dispatchers.Main) { exoPlayer.mediaItemCount }
                val queueTitles = mutableListOf<String>()
                val queueArtists = mutableListOf<String>()
                withContext(Dispatchers.Main) {
                    for (i in 0 until queueCount) {
                        val item = exoPlayer.getMediaItemAt(i)
                        queueTitles.add(item.mediaMetadata.title?.toString() ?: "Track $i")
                        queueArtists.add(item.mediaMetadata.artist?.toString() ?: "Unknown")
                    }
                }

                val artworkData = metadata?.artworkData
                val artworkUri = metadata?.artworkUri

                val artBytes: ByteArray? = if (artworkData != null && artworkData.isNotEmpty()) {
                    artworkData
                } else if (artworkUri != null) {
                    try {
                        contentResolver.openInputStream(artworkUri)?.use { stream ->
                            stream.readBytes()
                        }
                    } catch (e: Exception) {
                        Log.e("PlaybackService", "Error reading artwork uri for wear", e)
                        null
                    }
                } else {
                    null
                }

                val putDataMapReq = PutDataMapRequest.create("/myusiq/state").apply {
                    dataMap.putBoolean("isPlaying", isPlaying)
                    dataMap.putString("title", title)
                    dataMap.putString("artist", artist)
                    dataMap.putLong("timestamp", System.currentTimeMillis())
                    dataMap.putStringArrayList("queueTitles", ArrayList(queueTitles))
                    dataMap.putStringArrayList("queueArtists", ArrayList(queueArtists))
                    if (artBytes != null && artBytes.isNotEmpty()) {
                        dataMap.putAsset("artwork", Asset.createFromBytes(artBytes))
                    }
                }
                val request = putDataMapReq.asPutDataRequest().setUrgent()
                Wearable.getDataClient(applicationContext).putDataItem(request)
            } catch (e: Exception) {
                Log.e("PlaybackService", "Error syncing state to wearables", e)
            }
        }
    }
    
    // Direct controls for other components
    fun togglePlayPause() {
        if (exoPlayer.isPlaying) {
            exoPlayer.pause()
        } else {
            if (requestAudioFocus()) {
                exoPlayer.play()
            }
        }
    }
    
    fun playNextSong() {
        if (exoPlayer.hasNextMediaItem()) {
            exoPlayer.seekToNextMediaItem()
            exoPlayer.prepare()
            if (requestAudioFocus()) {
                exoPlayer.play()
            }
        }
    }
    
    fun playPreviousSong() {
        if (exoPlayer.hasPreviousMediaItem()) {
            exoPlayer.seekToPreviousMediaItem()
            exoPlayer.prepare()
            if (requestAudioFocus()) {
                exoPlayer.play()
            }
        }
    }

    fun handleIntent(action: String?) {
        Log.d("PlaybackService", "handleIntent: $action, hasNext=${exoPlayer.hasNextMediaItem()}, hasPrev=${exoPlayer.hasPreviousMediaItem()}")
        when (action) {
            "TOGGLE_PLAY_PAUSE" -> {
                togglePlayPause()
            }
            "NEXT" -> {
                playNextSong()
            }
            "PREV" -> {
                playPreviousSong()
            }
            "REPEAT" -> {
                val next = when (currentRepeatState) {
                    RepeatState.OFF -> RepeatState.ALL
                    RepeatState.ALL -> RepeatState.ONE
                    else -> RepeatState.OFF
                }
                currentRepeatState = next
            }
            "SHUFFLE" -> {
                val enabled = !exoPlayer.shuffleModeEnabled
                playerA.shuffleModeEnabled = enabled
                playerB.shuffleModeEnabled = enabled
            }
        }
        updateWidgets()
    }

    private fun createNotificationChannel() {
        val name = "Playback"
        val descriptionText = "Music playback controls"
        val importance = NotificationManager.IMPORTANCE_LOW
        val channel = NotificationChannel("PLAYBACK_CHANNEL", name, importance).apply {
            description = descriptionText
            setShowBadge(false)
        }
        val notificationManager: NotificationManager =
            getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        notificationManager.createNotificationChannel(channel)
    }
}
