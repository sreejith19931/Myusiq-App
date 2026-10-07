package com.music.myusiq.wear.ui

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.wear.compose.foundation.lazy.TransformingLazyColumn
import androidx.wear.compose.foundation.lazy.rememberTransformingLazyColumnState
import androidx.wear.compose.material3.Button
import androidx.wear.compose.material3.ListHeader
import androidx.wear.compose.material3.MaterialTheme
import androidx.wear.compose.material3.ScreenScaffold
import androidx.wear.compose.material3.Text
import com.music.myusiq.wear.controller.WearMediaController

@Composable
fun WearQueueScreen(
    controller: WearMediaController,
    onBackToNowPlaying: () -> Unit
) {
    val queueItems by controller.queueItems.collectAsState()
    val columnState = rememberTransformingLazyColumnState()

    ScreenScaffold(scrollState = columnState) { contentPadding ->
        TransformingLazyColumn(
            state = columnState,
            contentPadding = contentPadding
        ) {
            item {
                ListHeader {
                    Text(
                        text = "Current Queue (${queueItems.size})",
                        style = MaterialTheme.typography.titleSmall
                    )
                }
            }

            if (queueItems.isEmpty()) {
                item {
                    Text(
                        text = "No items in queue",
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.padding(16.dp)
                    )
                }
            } else {
                items(queueItems.size) { index ->
                    val mediaItem = queueItems[index]
                    val title = mediaItem.mediaMetadata.title?.toString() ?: "Track $index"
                    val artist = mediaItem.mediaMetadata.artist?.toString() ?: "Unknown"

                    Button(
                        onClick = { onBackToNowPlaying() },
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 2.dp)
                    ) {
                        Text(
                            text = "$title - $artist",
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            style = MaterialTheme.typography.bodySmall
                        )
                    }
                }
            }

            item {
                Button(
                    onClick = onBackToNowPlaying,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 8.dp)
                ) {
                    Text(text = "Back to Player", style = MaterialTheme.typography.labelSmall)
                }
            }
        }
    }
}
