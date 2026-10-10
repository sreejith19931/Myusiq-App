package com.music.myusiq.wear.controller

import android.content.ComponentName
import android.content.Context
import android.graphics.BitmapFactory
import android.util.Log
import androidx.compose.ui.graphics.Color
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.Player
import androidx.media3.session.MediaBrowser
import androidx.media3.session.SessionToken
import androidx.palette.graphics.Palette
import com.google.android.gms.tasks.Tasks
import com.google.android.gms.wearable.DataClient
import com.google.android.gms.wearable.DataEvent
import com.google.android.gms.wearable.DataMapItem
import com.google.android.gms.wearable.Wearable
import com.google.common.util.concurrent.ListenableFuture
import com.google.common.util.concurrent.MoreExecutors
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

class WearMediaController(private val context: Context) {

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    private var browserFuture: ListenableFuture<MediaBrowser>? = null
    private var browser: MediaBrowser? = null

    private val _isPlaying = MutableStateFlow(false)
    val isPlaying: StateFlow<Boolean> = _isPlaying.asStateFlow()

    private val _currentTitle = MutableStateFlow("No Track Playing")
    val currentTitle: StateFlow<String> = _currentTitle.asStateFlow()

    private val _currentArtist = MutableStateFlow("Myusiq Music Player")
    val currentArtist: StateFlow<String> = _currentArtist.asStateFlow()

    private val _queueItems = MutableStateFlow<List<MediaItem>>(emptyList())
    val queueItems: StateFlow<List<MediaItem>> = _queueItems.asStateFlow()

    private val _accentColor = MutableStateFlow<Color?>(null)
    val accentColor: StateFlow<Color?> = _accentColor.asStateFlow()

    private val dataClientListener = DataClient.OnDataChangedListener { dataEvents ->
        for (event in dataEvents) {
            if (event.type == DataEvent.TYPE_CHANGED && event.dataItem.uri.path == "/myusiq/state") {
                val dataMap = DataMapItem.fromDataItem(event.dataItem).dataMap
                val playing = dataMap.getBoolean("isPlaying")
                val title = dataMap.getString("title", "No Track Playing")
                val artist = dataMap.getString("artist", "Myusiq")

                _isPlaying.value = playing
                _currentTitle.value = title
                _currentArtist.value = artist

                val queueTitles = dataMap.getStringArrayList("queueTitles") ?: emptyList()
                val queueArtists = dataMap.getStringArrayList("queueArtists") ?: emptyList()

                val items = mutableListOf<MediaItem>()
                for (i in queueTitles.indices) {
                    val t = queueTitles[i]
                    val a = if (i < queueArtists.size) queueArtists[i] else "Unknown"
                    val item = MediaItem.Builder()
                        .setMediaId("item_$i")
                        .setMediaMetadata(
                            MediaMetadata.Builder()
                                .setTitle(t)
                                .setArtist(a)
                                .build()
                        )
                        .build()
                    items.add(item)
                }
                _queueItems.value = items

                val asset = dataMap.getAsset("artwork")
                if (asset != null) {
                    scope.launch {
                        try {
                            val fdTask = Wearable.getDataClient(context).getFdForAsset(asset)
                            val fd = Tasks.await(fdTask)
                            fd.inputStream.use { stream ->
                                val bitmap = BitmapFactory.decodeStream(stream)
                                if (bitmap != null) {
                                    generatePalette(bitmap)
                                }
                            }
                        } catch (e: Exception) {
                            Log.e("WearMediaController", "Failed to decode artwork asset", e)
                        }
                    }
                }
            }
        }
    }

    fun connect() {
        try {
            Wearable.getDataClient(context).addListener(dataClientListener)
            sendMessageToPhone("/myusiq/request_sync")
        } catch (e: Exception) {
            Log.e("WearMediaController", "Failed to register Wearable data listener", e)
        }

        try {
            val serviceComponent = ComponentName("com.music.myusiq", "com.music.myusiq.service.PlaybackService")
            val sessionToken = SessionToken(context, serviceComponent)

            val future = MediaBrowser.Builder(context, sessionToken).buildAsync()
            browserFuture = future
            future.addListener({
                try {
                    val mediaBrowser = future.get()
                    if (mediaBrowser != null) {
                        browser = mediaBrowser
                        setupPlayerListener(mediaBrowser)
                        updateState(mediaBrowser)
                    }
                } catch (e: Exception) {
                    Log.e("WearMediaController", "Failed to connect to PlaybackService via MediaBrowser", e)
                }
            }, MoreExecutors.directExecutor())
        } catch (e: Exception) {
            Log.e("WearMediaController", "Error initializing MediaBrowser connection", e)
        }
    }

    private fun sendMessageToPhone(path: String, payload: ByteArray? = null) {
        scope.launch {
            try {
                val nodeClient = Wearable.getNodeClient(context)
                val nodes = Tasks.await(nodeClient.connectedNodes)
                val messageClient = Wearable.getMessageClient(context)
                for (node in nodes) {
                    messageClient.sendMessage(node.id, path, payload ?: ByteArray(0))
                }
            } catch (e: Exception) {
                Log.e("WearMediaController", "Error sending message to phone: $path", e)
            }
        }
    }

    private fun setupPlayerListener(player: Player) {
        player.addListener(object : Player.Listener {
            override fun onIsPlayingChanged(isPlaying: Boolean) {
                _isPlaying.value = isPlaying
            }

            override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
                updateState(player)
            }

            override fun onEvents(player: Player, events: Player.Events) {
                updateState(player)
            }
        })
    }

    private fun updateState(player: Player) {
        _isPlaying.value = player.isPlaying
        val metadata = player.mediaMetadata
        _currentTitle.value = metadata.title?.toString() ?: "No Track Playing"
        _currentArtist.value = metadata.artist?.toString() ?: "Myusiq"

        extractAlbumArtColor(metadata)

        val items = mutableListOf<MediaItem>()
        for (i in 0 until player.mediaItemCount) {
            items.add(player.getMediaItemAt(i))
        }
        _queueItems.value = items
    }

    private fun extractAlbumArtColor(metadata: MediaMetadata) {
        scope.launch {
            val artworkData = metadata.artworkData
            val artworkUri = metadata.artworkUri

            if (artworkData != null && artworkData.isNotEmpty()) {
                try {
                    val bitmap = BitmapFactory.decodeByteArray(artworkData, 0, artworkData.size)
                    if (bitmap != null) {
                        generatePalette(bitmap)
                        return@launch
                    }
                } catch (e: Exception) {
                    Log.e("WearMediaController", "Failed to decode artworkData", e)
                }
            }

            if (artworkUri != null) {
                try {
                    context.contentResolver.openInputStream(artworkUri)?.use { inputStream ->
                        val bitmap = BitmapFactory.decodeStream(inputStream)
                        if (bitmap != null) {
                            generatePalette(bitmap)
                            return@launch
                        }
                    }
                } catch (e: Exception) {
                    Log.e("WearMediaController", "Failed to decode artworkUri: $artworkUri", e)
                }
            }

            _accentColor.value = null
        }
    }

    private fun generatePalette(bitmap: android.graphics.Bitmap) {
        Palette.from(bitmap).generate { palette ->
            if (palette != null) {
                val vibrant = palette.getVibrantColor(0)
                val lightVibrant = palette.getLightVibrantColor(0)
                val darkVibrant = palette.getDarkVibrantColor(0)
                val dominant = palette.getDominantColor(0)

                val selectedIntColor = listOf(vibrant, lightVibrant, darkVibrant, dominant)
                    .firstOrNull { it != 0 }

                if (selectedIntColor != null) {
                    _accentColor.value = Color(selectedIntColor)
                } else {
                    _accentColor.value = null
                }
            } else {
                _accentColor.value = null
            }
        }
    }

    fun playPause() {
        sendMessageToPhone("/myusiq/play_pause")
        val controller = browser ?: return
        if (controller.isPlaying) {
            controller.pause()
        } else {
            controller.play()
        }
    }

    fun skipToNext() {
        sendMessageToPhone("/myusiq/next")
        browser?.seekToNext()
    }

    fun skipToPrevious() {
        sendMessageToPhone("/myusiq/prev")
        browser?.seekToPrevious()
    }

    fun skipToQueueItem(index: Int) {
        sendMessageToPhone("/myusiq/play_index", index.toString().toByteArray())
        browser?.seekToDefaultPosition(index)
    }

    fun adjustVolume(delta: Int) {
        val path = if (delta > 0) "/myusiq/vol_up" else "/myusiq/vol_down"
        sendMessageToPhone(path)
        try {
            if (delta > 0) {
                browser?.increaseDeviceVolume()
            } else {
                browser?.decreaseDeviceVolume()
            }
            val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as? android.media.AudioManager
            audioManager?.adjustStreamVolume(
                android.media.AudioManager.STREAM_MUSIC,
                if (delta > 0) android.media.AudioManager.ADJUST_RAISE else android.media.AudioManager.ADJUST_LOWER,
                android.media.AudioManager.FLAG_SHOW_UI
            )
        } catch (e: Exception) {
            Log.e("WearMediaController", "Error adjusting volume", e)
        }
    }

    fun disconnect() {
        try {
            Wearable.getDataClient(context).removeListener(dataClientListener)
        } catch (e: Exception) {
            Log.e("WearMediaController", "Error unregistering Wearable data listener", e)
        }
        browserFuture?.let { MediaBrowser.releaseFuture(it) }
        browser = null
        scope.cancel()
    }
}
