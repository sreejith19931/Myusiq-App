package com.music.myusiq.wear.ui

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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.wear.compose.material3.Button
import androidx.wear.compose.material3.ButtonDefaults
import androidx.wear.compose.material3.Icon
import androidx.wear.compose.material3.IconButton
import androidx.wear.compose.material3.IconButtonDefaults
import androidx.wear.compose.material3.MaterialTheme
import androidx.wear.compose.material3.Text
import com.music.myusiq.wear.R
import com.music.myusiq.wear.controller.WearMediaController

@Composable
fun WearNowPlayingScreen(
    controller: WearMediaController,
    onOpenQueue: () -> Unit,
) {
    val isPlaying by controller.isPlaying.collectAsState()
    val title by controller.currentTitle.collectAsState()
    val artist by controller.currentArtist.collectAsState()
    val accentColor by controller.accentColor.collectAsState()

    val playPauseColors = if (accentColor != null) {
        val container = accentColor!!
        val content = if (container.luminance() > 0.5f) Color.Black else Color.White
        IconButtonDefaults.filledIconButtonColors(
            containerColor = container,
            contentColor = content,
        )
    } else {
        IconButtonDefaults.filledTonalIconButtonColors()
    }

    val prevNextColors = if (accentColor != null) {
        val container = accentColor!!.copy(alpha = 0.35f)
        IconButtonDefaults.filledTonalIconButtonColors(
            containerColor = container,
            contentColor = Color.White
        )
    } else {
        IconButtonDefaults.filledTonalIconButtonColors()
    }

    val queueColors = if (accentColor != null) {
        val container = accentColor!!.copy(alpha = 0.25f)
        ButtonDefaults.filledTonalButtonColors(
            containerColor = container,
            contentColor = Color.White
        )
    } else {
        ButtonDefaults.buttonColors()
    }

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
            IconButton(
                onClick = { controller.skipToPrevious() },
                colors = prevNextColors,
                modifier = Modifier.size(40.dp)
            ) {
                Icon(
                    painter = painterResource(id = R.drawable.ic_prev),
                    contentDescription = "Previous",
                    modifier = Modifier.size(20.dp)
                )
            }

            IconButton(
                onClick = { controller.playPause() },
                colors = playPauseColors,
                modifier = Modifier.size(52.dp)
            ) {
                Icon(
                    painter = painterResource(id = if (isPlaying) R.drawable.ic_pause else R.drawable.ic_play),
                    contentDescription = "Play or Pause",
                    modifier = Modifier.size(26.dp)
                )
            }

            IconButton(
                onClick = { controller.skipToNext() },
                colors = prevNextColors,
                modifier = Modifier.size(40.dp)
            ) {
                Icon(
                    painter = painterResource(id = R.drawable.ic_next),
                    contentDescription = "Next",
                    modifier = Modifier.size(20.dp)
                )
            }
        }

        Spacer(modifier = Modifier.height(10.dp))

        Button(
            onClick = onOpenQueue,
            colors = queueColors,
            modifier = Modifier
                .height(32.dp)
                .padding(horizontal = 8.dp)
        ) {
            Text(text = "Queue", style = MaterialTheme.typography.labelSmall)
        }
    }
}
