package com.music.rhythm.wear.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.wear.compose.material3.Button
import androidx.wear.compose.material3.ButtonDefaults
import androidx.wear.compose.material3.MaterialTheme
import androidx.wear.compose.material3.Text
import com.music.rhythm.wear.controller.WearMediaController

@Composable
fun WearNowPlayingScreen(
    controller: WearMediaController,
    onOpenQueue: () -> Unit
) {
    val isPlaying by controller.isPlaying.collectAsState()
    val title by controller.currentTitle.collectAsState()
    val artist by controller.currentArtist.collectAsState()

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 12.dp, vertical = 16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Text(
            text = title,
            style = MaterialTheme.typography.titleMedium,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            textAlign = TextAlign.Center
        )

        Text(
            text = artist,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            textAlign = TextAlign.Center
        )

        Spacer(modifier = Modifier.height(12.dp))

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceEvenly,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Button(
                onClick = { controller.skipToPrevious() },
                modifier = Modifier.size(40.dp)
            ) {
                Text(text = "⏮", style = MaterialTheme.typography.bodyMedium)
            }

            Button(
                onClick = { controller.playPause() },
                colors = ButtonDefaults.filledTonalButtonColors(),
                modifier = Modifier.size(52.dp)
            ) {
                Text(
                    text = if (isPlaying) "⏸" else "▶",
                    style = MaterialTheme.typography.titleLarge
                )
            }

            Button(
                onClick = { controller.skipToNext() },
                modifier = Modifier.size(40.dp)
            ) {
                Text(text = "⏭", style = MaterialTheme.typography.bodyMedium)
            }
        }

        Spacer(modifier = Modifier.height(10.dp))

        Button(
            onClick = onOpenQueue,
            modifier = Modifier
                .height(32.dp)
                .padding(horizontal = 8.dp)
        ) {
            Text(text = "Queue", style = MaterialTheme.typography.labelSmall)
        }
    }
}
