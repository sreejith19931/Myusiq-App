package com.music.myusiq.domain.models

import android.net.Uri

data class PendingTrackEdit(
    val songId: Long,
    val title: String,
    val artist: String,
    val album: String,
    val year: String = "",
    val genre: String = "",
    val artworkUri: Uri? = null,
    val path: String = "",
    val lyrics: String? = null,
    val matchConfidence: Int = 100
)
