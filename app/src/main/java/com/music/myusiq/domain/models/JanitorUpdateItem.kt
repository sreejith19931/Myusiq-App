package com.music.myusiq.domain.models

import android.net.Uri
import com.music.myusiq.domain.models.Song
import com.music.myusiq.domain.models.JanitorItemType

/**
 * A wrapper for a track song and its suggested metadata edit from the Janitor Scan.
 * This object is used to track which tracks need user intervention or which ones were automatically fixed.
 */
data class JanitorUpdateItem(
    val songId: String,
    val song: Song,
    val currentArtworkUri: Uri?,
    val itemType: JanitorItemType,
    val rawMetadata: Map<String, String?>,
    var proposedEdit: PendingTrackEdit,
    var isSelected: Boolean = true,
    var suggestedMatches: List<SuggestedMetadataItem> = emptyList()
)
