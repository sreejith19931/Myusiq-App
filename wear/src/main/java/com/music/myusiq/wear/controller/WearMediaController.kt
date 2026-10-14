package com.music.myusiq.wear.controller

import android.content.Context
import android.graphics.BitmapFactory
import android.util.Log
import androidx.compose.ui.graphics.Color
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.palette.graphics.Palette
import com.google.android.gms.tasks.Tasks
import com.google.android.gms.wearable.DataClient
import com.google.android.gms.wearable.DataEvent
import com.google.android.gms.wearable.DataMap
import com.google.android.gms.wearable.DataMapItem
import com.google.android.gms.wearable.Wearable
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
                processStateDataMap(dataMap)
            }
        }
    }

    private fun processStateDataMap(dataMap: DataMap) {
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

    fun connect() {
        try {
            Wearable.getDataClient(context).addListener(dataClientListener)
            fetchInitialData()
            sendMessageToPhone("/myusiq/request_sync")
        } catch (e: Exception) {
            Log.e("WearMediaController", "Failed to register Wearable data listener", e)
        }
    }

    private fun fetchInitialData() {
        scope.launch {
            try {
                val dataItemBuffer = Tasks.await(Wearable.getDataClient(context).dataItems)
                for (dataItem in dataItemBuffer) {
                    if (dataItem.uri.path == "/myusiq/state") {
                        val dataMap = DataMapItem.fromDataItem(dataItem).dataMap
                        processStateDataMap(dataMap)
                    }
                }
                dataItemBuffer.release()
            } catch (e: Exception) {
                Log.e("WearMediaController", "Failed to fetch initial data item", e)
            }
        }
    }

    private fun sendMessageToPhone(path: String, payload: ByteArray? = null) {
        scope.launch {
            try {
                val nodeClient = Wearable.getNodeClient(context)
                val nodes = Tasks.await(nodeClient.connectedNodes)
                if (nodes.isEmpty()) {
                    Log.w("WearMediaController", "No connected phone nodes found for message: $path")
                }
                val messageClient = Wearable.getMessageClient(context)
                for (node in nodes) {
                    messageClient.sendMessage(node.id, path, payload ?: ByteArray(0))
                }
            } catch (e: Exception) {
                Log.e("WearMediaController", "Error sending message to phone: $path", e)
            }
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
        _isPlaying.value = !_isPlaying.value
        sendMessageToPhone("/myusiq/play_pause")
    }

    fun skipToNext() {
        sendMessageToPhone("/myusiq/next")
    }

    fun skipToPrevious() {
        sendMessageToPhone("/myusiq/prev")
    }

    fun skipToQueueItem(index: Int) {
        sendMessageToPhone("/myusiq/play_index", index.toString().toByteArray())
    }

    fun adjustVolume(delta: Int) {
        val path = if (delta > 0) "/myusiq/vol_up" else "/myusiq/vol_down"
        sendMessageToPhone(path)
    }

    fun disconnect() {
        try {
            Wearable.getDataClient(context).removeListener(dataClientListener)
        } catch (e: Exception) {
            Log.e("WearMediaController", "Error unregistering Wearable data listener", e)
        }
        scope.cancel()
    }
}
