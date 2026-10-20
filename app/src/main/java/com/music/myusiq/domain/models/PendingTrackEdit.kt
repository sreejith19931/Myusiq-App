package com.music.myusiq.domain.models

import android.net.Uri

/**
 * Represents a suggested metadata edit for a track.
 */
data class PendingTrackEdit(
    val songId: String,
    val title: String,
    val artist: String,
    val album: String,
    val year: String,
    val genre: String,
    val artworkUri: Uri?,
    val path: String,
    val matchConfidence: Int
)
