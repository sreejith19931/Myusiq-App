package com.music.rhythm.wear.controller

import android.content.ComponentName
import android.content.Context
import android.util.Log
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.session.MediaBrowser
import androidx.media3.session.SessionToken
import com.google.common.util.concurrent.ListenableFuture
import com.google.common.util.concurrent.MoreExecutors
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

class WearMediaController(private val context: Context) {

    private var browserFuture: ListenableFuture<MediaBrowser>? = null
    private var browser: MediaBrowser? = null

    private val _isPlaying = MutableStateFlow(false)
    val isPlaying: StateFlow<Boolean> = _isPlaying.asStateFlow()

    private val _currentTitle = MutableStateFlow("No Track Playing")
    val currentTitle: StateFlow<String> = _currentTitle.asStateFlow()

    private val _currentArtist = MutableStateFlow("Rhythm Music Player")
    val currentArtist: StateFlow<String> = _currentArtist.asStateFlow()

    private val _queueItems = MutableStateFlow<List<MediaItem>>(emptyList())
    val queueItems: StateFlow<List<MediaItem>> = _queueItems.asStateFlow()

    fun connect() {
        val serviceComponent = ComponentName("com.music.rhythm", "com.music.rhythm.service.PlaybackService")
        val sessionToken = SessionToken(context, serviceComponent)

        browserFuture = MediaBrowser.Builder(context, sessionToken).buildAsync()
        browserFuture?.addListener({
            try {
                val mediaBrowser = browserFuture?.get()
                if (mediaBrowser != null) {
                    browser = mediaBrowser
                    setupPlayerListener(mediaBrowser)
                    updateState(mediaBrowser)
                }
            } catch (e: Exception) {
                Log.e("WearMediaController", "Failed to connect to PlaybackService", e)
            }
        }, MoreExecutors.directExecutor())
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
        _currentArtist.value = metadata.artist?.toString() ?: "Rhythm"

        val items = mutableListOf<MediaItem>()
        for (i in 0 until player.mediaItemCount) {
            items.add(player.getMediaItemAt(i))
        }
        _queueItems.value = items
    }

    fun playPause() {
        val controller = browser ?: return
        if (controller.isPlaying) {
            controller.pause()
        } else {
            controller.play()
        }
    }

    fun skipToNext() {
        browser?.seekToNext()
    }

    fun skipToPrevious() {
        browser?.seekToPrevious()
    }

    fun disconnect() {
        browserFuture?.let { MediaBrowser.releaseFuture(it) }
        browser = null
    }
}
