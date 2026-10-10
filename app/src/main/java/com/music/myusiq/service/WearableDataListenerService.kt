package com.music.myusiq.service

import android.content.Intent
import android.util.Log
import androidx.core.content.ContextCompat
import com.google.android.gms.wearable.MessageEvent
import com.google.android.gms.wearable.WearableListenerService

class WearableDataListenerService : WearableListenerService() {

    override fun onMessageReceived(messageEvent: MessageEvent) {
        val path = messageEvent.path
        val payload = messageEvent.data
        Log.d("WearableListener", "Received message from Wear: $path")

        val activeService = PlaybackService.getInstance()
        if (activeService != null) {
            activeService.handleWearCommand(path, payload)
        } else {
            val intent = Intent(this, PlaybackService::class.java).apply {
                action = PlaybackService.ACTION_WEAR_COMMAND
                putExtra(PlaybackService.EXTRA_WEAR_COMMAND_PATH, path)
                putExtra(PlaybackService.EXTRA_WEAR_COMMAND_PAYLOAD, payload)
            }
            try {
                ContextCompat.startForegroundService(this, intent)
            } catch (e: Exception) {
                Log.e("WearableListener", "Failed to start PlaybackService for wear command", e)
            }
        }
    }
}
