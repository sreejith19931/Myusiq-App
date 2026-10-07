package com.music.myusiq.wear

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.wear.compose.material3.AppScaffold
import androidx.wear.compose.material3.MaterialTheme
import com.music.myusiq.wear.controller.WearMediaController
import com.music.myusiq.wear.ui.WearNowPlayingScreen
import com.music.myusiq.wear.ui.WearQueueScreen

class WearActivity : ComponentActivity() {

    private lateinit var mediaController: WearMediaController

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        mediaController = WearMediaController(applicationContext)
        mediaController.connect()

        setContent {
            MaterialTheme {
                AppScaffold {
                    var showQueueScreen by remember { mutableStateOf(false) }

                    if (showQueueScreen) {
                        WearQueueScreen(
                            controller = mediaController,
                            onBackToNowPlaying = { showQueueScreen = false }
                        )
                    } else {
                        WearNowPlayingScreen(
                            controller = mediaController,
                            onOpenQueue = { showQueueScreen = true }
                        )
                    }
                }
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        mediaController.disconnect()
    }
}
